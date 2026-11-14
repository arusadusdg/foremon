package dev.foremon.net

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.util.party
import com.cobblemon.mod.common.util.pc
import dev.foremon.Config
import dev.foremon.Foremon
import dev.foremon.Rules
import dev.foremon.rules.Job
import dev.foremon.rules.WorkerProfile
import dev.foremon.work.Jobs
import dev.foremon.world.Plan
import dev.foremon.world.PlayerStore
import dev.foremon.world.Site
import dev.foremon.world.SiteStore
import dev.foremon.world.Workers
import dev.foremon.world.BALLS
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

/** Server side of every screen: builds the data a screen shows and carries out what the player clicks, after checking it. */
object Actions {
    /** The job site each player last opened; the whistle's sneak-click actions apply to it. */
    val selected = HashMap<UUID, GlobalPos>()

    /** Hauling: the chest each player picked as the start of a new route, with the site it is for. */
    val routeStart = HashMap<UUID, Pair<BlockPos, BlockPos>>()

    /**
     * Hauling: a sneak-click on a chest. The first click picks where a route starts, the second where it goes.
     * Any chest the player may use and that is in reach of the site works, the site's own chest too.
     * Returns the message for the player: its key, then its arguments.
     */
    fun routeClick(player: ServerPlayer, level: ServerLevel, s: Site, chest: BlockPos, chestSiteOwner: UUID?): List<String> {
        if (chest != s.pos) {
            if (chestSiteOwner != null && chestSiteOwner != s.owner) return listOf("foremon.fail.not_owner")
            if (!dev.foremon.work.Protection.playerMayUse(level, player, chest)) return listOf("foremon.fail.chest_protected")
            val reach = Rules.skill(player.server, s.owner, "supply_lines")
            if (chest.distSqr(s.pos) > reach * reach) return listOf("foremon.fail.route_far", reach.toInt().toString())
        }
        val pending = routeStart.remove(player.uuid)
        val start = if (pending?.first == s.pos) pending.second else null
        if (start == null) { routeStart[player.uuid] = s.pos to chest; return listOf("foremon.msg.route_from", "${chest.x} ${chest.y} ${chest.z}") }
        return addRoute(player.server, s, start, chest)
    }

    /** Adds a route once both ends are known to be fine. Returns the message for the player, like [routeClick]. */
    private fun addRoute(server: net.minecraft.server.MinecraftServer, s: Site, from: BlockPos, to: BlockPos): List<String> {
        if (from == to) return listOf("foremon.msg.route_cancelled")
        if (s.routes.any { it.first == from && it.second == to }) return listOf("foremon.fail.route_exists")
        val max = Rules.skill(server, s.owner, "linked_chests").toInt()
        if (s.routes.size >= max) return listOf("foremon.fail.route_limit", max.toString())
        // Both ends become chests of the site, so they get a ball marker and a filter (the site's own chest already has both).
        for (p in listOf(from, to)) if (p != s.pos && p !in s.links) { s.links.add(p); s.chest(p) }
        s.routes.add(from to to)
        return listOf("foremon.msg.route_added", s.routes.size.toString(), max.toString())
    }

    /**
     * Remote Control: a route between two of the owner's job sites picked from the Routes tab, without going there.
     * Both ends must be job sites of the same owner in this dimension (or the depot itself), within Supply Lines range.
     */
    fun remoteRoute(level: ServerLevel, s: Site, from: BlockPos, to: BlockPos): List<String> {
        val reach = Rules.skill(level.server, s.owner, "supply_lines")
        val sites = SiteStore.of(level).sites
        for (p in listOf(from, to)) {
            // The depot and its own chests were reached by hand already; other job sites need Remote Control.
            if (p == s.pos || p in s.links) continue
            if (sites[p]?.owner != s.owner) return listOf("foremon.fail.not_owner")
            if (!Rules.has(level.server, s.owner, "remote_control")) return listOf("foremon.fail.needs_remote")
            if (p.distSqr(s.pos) > reach * reach) return listOf("foremon.fail.route_far", reach.toInt().toString())
        }
        return addRoute(level.server, s, from, to)
    }

    /**
     * Dispatch: puts hauler [mon] on route [index] alone, or back on every route if it already was. How many routes may
     * have a crew of their own is the Dispatch level. Returns a message for the player, or null when it worked.
     */
    fun crewToggle(server: net.minecraft.server.MinecraftServer, s: Site, index: Int, mon: UUID): List<String>? {
        val r = s.routes.getOrNull(index) ?: return null
        if (s.workers.none { it.pokemonId == mon }) return null
        if (s.crews[mon] == r) { s.crews.remove(mon); return null }
        val max = Rules.skill(server, s.owner, "dispatch").toInt()
        val manned = s.workers.mapNotNull { s.crews[it.pokemonId] }.toSet()
        if (r !in manned && manned.size >= max) return if (max == 0) listOf("foremon.fail.needs_dispatch") else listOf("foremon.fail.dispatch_limit", max.toString())
        s.crews[mon] = r
        return null
    }

    /** A bad packet or a bug in one action must never crash the server. */
    fun guarded(player: ServerPlayer, block: () -> Unit) {
        try { block() } catch (e: Exception) {
            Foremon.LOG.error("Action from ${player.gameProfile.name} failed", e)
            Foremon.tell(player, "foremon.fail.generic")
        }
    }

    fun handle(player: ServerPlayer, t: CompoundTag) {
        val server = player.server
        when (val op = t.getString("op")) {
            "board" -> sendBoard(player)
            "skills" -> sendSkills(player)
            "buy" -> {
                val state = Rules.state(server, player.uuid)
                if (Rules.tree().buy(state, t.getString("node"))) PlayerStore.of(server).setDirty()
                sendSkills(player)
            }
            "dex" -> sendDex(player, t.getString("query"))
            "perks" -> sendPerks(player)
            "admin" -> if (player.hasPermissions(2)) sendAdmin(player)
            "admin_set" -> if (player.hasPermissions(2)) {
                if (!Config.set(t.getString("key"), t.getString("value"))) Foremon.tell(player, "foremon.fail.bad_value")
                sendAdmin(player)
            }
            else -> siteOp(player, op, t)
        }
    }

    private fun levelOf(player: ServerPlayer, t: CompoundTag): ServerLevel? {
        if (!t.contains("dim")) return player.serverLevel()
        val id = ResourceLocation.tryParse(t.getString("dim")) ?: return null
        return player.server.getLevel(ResourceKey.create(Registries.DIMENSION, id))
    }

    private fun siteOp(player: ServerPlayer, op: String, t: CompoundTag) {
        val level = levelOf(player, t) ?: return
        val pos = BlockPos.of(t.getLong("pos"))
        val store = SiteStore.of(level)
        val s = store.sites[pos] ?: run { Foremon.tell(player, "foremon.fail.no_site"); return }
        val admin = player.hasPermissions(2)
        if (s.owner != player.uuid && !admin) { Foremon.tell(player, "foremon.fail.not_owner"); return }
        val far = level != player.level() || player.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) > 48.0 * 48.0
        if (far && !admin && !Rules.has(player.server, player.uuid, "remote_control")) { Foremon.tell(player, "foremon.fail.too_far"); return }

        when (op) {
            // The open site screen asks for fresh numbers (load progress, stamina) every half second.
            "refresh" -> { sendSite(player, level, s, full = false); return }
            "open" -> selected[player.uuid] = GlobalPos.of(level.dimension(), pos)
            "pc" -> { sendPc(player, s, t.getInt("box")); return }
            "job" -> Job.of(t.getString("job"))?.let { if (s.job != it) { s.job = it; reset(s) } }
            "toggle" -> { s.active = !s.active; reset(s) }
            "assign" -> {
                val id = t.getUUID("pokemon")
                val p = player.party().find { it.uuid == id } ?: player.pc()[id]
                if (p == null || s.owner != player.uuid) Foremon.tell(player, "foremon.fail.generic")
                else Workers.assign(player, level, s, p)?.let { Foremon.tell(player, it, p.species.name) }
            }
            "unassign" -> s.workers.firstOrNull { it.pokemonId == t.getUUID("pokemon") }?.let { Workers.unassign(level, s, it, player) }
            "filter_mode", "filter_add", "filter_remove", "filter_clear" -> {
                // Every chest has its own filter; "chest" says which one (the site's own chest when missing).
                val chestPos = if (t.contains("chest")) BlockPos.of(t.getLong("chest")) else s.pos
                val f = s.filterFor(chestPos)
                when (op) {
                    "filter_mode" -> f.only = !f.only
                    "filter_add" -> {
                        val e = t.getString("entry")
                        val max = Rules.skill(player.server, s.owner, "filter_slots").toInt()
                        if (ResourceLocation.tryParse(e.removePrefix("#")) != null && e !in f.entries) {
                            if (f.entries.size >= max) Foremon.tell(player, "foremon.fail.filter_full", max.toString()) else f.entries.add(e)
                        }
                    }
                    "filter_remove" -> f.entries.remove(t.getString("entry"))
                    else -> f.entries.clear()
                }
                f.changed(); reset(s)
            }
            "chest_ball" -> {
                val chestPos = BlockPos.of(t.getLong("chest"))
                val ball = t.getString("ball").takeIf { it in BALLS } ?: BALLS[0]
                if (chestPos == s.pos) s.ball = ball else if (chestPos in s.links) s.chest(chestPos).ball = ball
            }
            "route_remove" -> { s.removeRoute(t.getInt("index")); reset(s) }
            "route_crew" -> crewToggle(player.server, s, t.getInt("index"), t.getUUID("mon"))?.let { Foremon.tell(player, it[0], *it.drop(1).toTypedArray()) }
            "route_add" -> {
                val msg = remoteRoute(level, s, BlockPos.of(t.getLong("from")), BlockPos.of(t.getLong("to")))
                Foremon.tell(player, msg[0], *msg.drop(1).toTypedArray())
                reset(s)
            }
            "link_remove" -> s.removeLink(BlockPos.of(t.getLong("chest")))
            "clear_area" -> { s.cornerA = null; s.cornerB = null; reset(s) }
            "clear_links" -> { s.links.clear(); s.chests.clear() }
            "plan_clear" -> { s.plan = null; if (s.job == Job.BUILDING) s.active = false; reset(s) }
            "restart" -> {
                // The manual way out of any stuck state: everyone back to the chest, forget what was unreachable.
                reset(s)
                s.workers.forEach { w -> Workers.entity(level, w)?.let { Workers.home(level, s, it) }; w.restUntil = 0 }
            }
            "remove" -> {
                Workers.dissolve(level, s); store.sites.remove(pos); store.setDirty()
                selected.remove(player.uuid)
                Net.view(player, "close", CompoundTag())
                return
            }
        }
        store.setDirty()
        sendSite(player, level, s)
    }

    /** Forget everything that was only true for the previous job, area or filter. */
    fun reset(s: Site) {
        s.queue.clear(); s.scan = null; s.rescanAt = 0; s.skip.clear(); s.status = ""; s.statusArg = ""
        s.workers.forEach { it.step = null; it.progress = 0; it.planAt = 0; it.failures = 0 }
    }

    // ------------------------------------------------------------ views

    private fun mon(p: Pokemon, t: CompoundTag = CompoundTag()): CompoundTag {
        t.putUUID("id", p.uuid)
        t.putString("species", p.species.resourceIdentifier.toString())
        t.put("aspects", ListTag().also { l -> p.aspects.forEach { l.add(StringTag.valueOf(it)) } })
        t.putString("name", p.getDisplayName().string)
        t.putInt("level", p.level)
        return t
    }

    private fun scores(profile: WorkerProfile, p: Pokemon?, t: CompoundTag, job: Job) {
        t.putIntArray("stars", Job.entries.map { profile.score(it).stars })
        val sc = profile.score(job)
        t.putDouble("mult", sc.mult)
        t.put("factors", ListTag().also { l -> sc.factors.forEach { l.add(StringTag.valueOf("${it.id}=${"%.2f".format(java.util.Locale.ROOT, it.mult)}")) } })
        t.putInt("tier", profile.miningTier)
        t.put("unlocks", ListTag().also { l -> profile.unlocks[job]?.forEach { (id, v) -> l.add(StringTag.valueOf("$id=$v")) } })
        profile.nextUnlock[job]?.let { t.putString("nextUnlock", "${it.level}|${it.id}|${it.value}") }
        t.putInt("carry", profile.carry)
        t.putString("style", profile.styleId)
        t.putString("caps", profile.capabilities.joinToString(",") { it.name.lowercase() })
        if (p != null) {
            t.putIntArray("mastery", Job.entries.map { Workers.masteryLevel(p, it) })
            t.putInt("masteryXp", Workers.masteryXp(p, job))
        }
    }

    /** [full] false leaves out the party: the twice-a-second refresh only needs what changes while working. */
    fun sendSite(player: ServerPlayer, level: ServerLevel, s: Site, full: Boolean = true) {
        val server = player.server
        val t = CompoundTag()
        t.putLong("pos", s.pos.asLong()); t.putString("dim", level.dimension().location().toString())
        t.putString("job", s.job.key); t.putBoolean("active", s.active)
        t.putString("status", s.status); t.putString("statusArg", s.statusArg)
        t.putString("owner", s.ownerName); t.putBoolean("mine", s.owner == player.uuid)
        t.putInt("radius", Workers.radius(level, s)); t.putInt("vertical", Workers.vertical(level, s))
        t.putInt("maxWorkers", Workers.maxWorkers(level, s.owner))
        t.putInt("maxLinks", Rules.skill(server, s.owner, "linked_chests").toInt()); t.putInt("links", s.links.size)
        t.putInt("maxFilter", Rules.skill(server, s.owner, "filter_slots").toInt())
        t.putInt("xp", Rules.state(server, s.owner).xp); t.putLong("done", s.done)
        t.putBoolean("upkeep", Config.d.upkeep)
        t.putBoolean("filterOnly", s.filter.only)
        t.put("filter", ListTag().also { l -> s.filter.entries.forEach { l.add(StringTag.valueOf(it)) } })
        // Every chest of the site, the site's own chest first, with its ball and its filter.
        t.put("chests", ListTag().also { l ->
            fun add(p: BlockPos, ball: String, f: dev.foremon.world.Filter, main: Boolean) = l.add(CompoundTag().also { c ->
                c.putLong("pos", p.asLong()); c.putString("ball", ball); c.putBoolean("main", main); c.putBoolean("only", f.only)
                c.put("entries", ListTag().also { el -> f.entries.forEach { el.add(StringTag.valueOf(it)) } })
                // A far Hauling chest may be in an unloaded chunk: it is not missing, just not loaded, and must not be loaded to look.
                val loaded = level.isLoaded(p)
                c.putBoolean("present", !loaded || dev.foremon.work.Inv.storage(level, p) != null)
                c.putBoolean("loaded", loaded)
                c.putInt("dist", kotlin.math.sqrt(p.distSqr(s.pos)).toInt())
            })
            add(s.pos, s.ball, s.filter, true)
            for (link in s.links) { val info = s.chest(link); add(link, info.ball, info.filter, false) }
        })
        // Hauling: the owner's other job sites here, to pick route ends from with Remote Control.
        t.putBoolean("remote", Rules.has(server, s.owner, "remote_control"))
        t.putInt("routeReach", Rules.skill(server, s.owner, "supply_lines").toInt())
        t.putInt("dispatch", Rules.skill(server, s.owner, "dispatch").toInt())
        if (s.job == Job.HAULING) t.put("sites", ListTag().also { l ->
            for (o in SiteStore.of(level).sites.values) if (o.owner == s.owner && o.pos != s.pos) l.add(CompoundTag().also { c ->
                c.putLong("pos", o.pos.asLong()); c.putString("job", o.job.key)
                c.putInt("dist", kotlin.math.sqrt(o.pos.distSqr(s.pos)).toInt())
            })
        })
        // Hauling routes, from one of the chests above to another.
        t.put("routes", ListTag().also { l ->
            for ((a, b) in s.routes) l.add(CompoundTag().also { c ->
                c.putLong("from", a.asLong()); c.putLong("to", b.asLong())
                c.putInt("dist", kotlin.math.sqrt(a.distSqr(b)).toInt())
                c.putBoolean("loaded", level.isLoaded(a) && level.isLoaded(b))
                // Dispatch: the haulers that work only this route.
                c.put("crew", ListTag().also { cl -> for (w in s.workers) if (s.crews[w.pokemonId] == (a to b)) cl.add(StringTag.valueOf(w.pokemonId.toString())) })
            })
        })
        Jobs.box(s)?.let { (lo, hi) -> t.putLong("lo", lo.asLong()); t.putLong("hi", hi.asLong()); t.putBoolean("areaDone", s.cornerB != null) }
        s.plan?.let { p -> t.put("plan", CompoundTag().also { c -> c.putString("name", p.name); c.putInt("size", p.size); c.putInt("index", p.index); c.putInt("placed", p.placed); c.putInt("skipped", p.skipped) }) }

        t.put("workers", ListTag().also { l ->
            for (w in s.workers) {
                val p = w.pokemon ?: continue
                val c = mon(p)
                Workers.profile(w)?.let { scores(it, p, c, s.job) }
                c.putDouble("stamina", w.stamina); c.putDouble("maxStamina", Workers.maxStamina(level, s, w))
                c.putBoolean("resting", w.restUntil > level.gameTime); c.putInt("emote", w.emote.ordinal)
                c.putInt("carried", w.count(w.carried) + w.count(w.supplies)); c.putInt("actions", w.actions)
                // How far along the current action is: the load bar of a hauler.
                val working = w.step?.kind == dev.foremon.world.Step.Kind.WORK && w.need > 0
                c.putFloat("progress", if (working) (w.progress.toFloat() / w.need).coerceIn(0f, 1f) else 0f)
                l.add(c)
            }
        })
        if (full) t.put("party", ListTag().also { l ->
            if (s.owner == player.uuid) for (p in player.party()) {
                val c = mon(p)
                scores(Workers.profile(p), p, c, s.job)
                c.putBoolean("fainted", p.isFainted())
                l.add(c)
            }
        })
        Net.view(player, "site", t)
    }

    /** One box of the player's PC, rated for the site's job, so workers can be picked straight from the PC. */
    fun sendPc(player: ServerPlayer, s: Site, box: Int) {
        val t = CompoundTag()
        t.putLong("pos", s.pos.asLong())
        val boxes = if (s.owner == player.uuid) player.pc().boxes else emptyList()
        val index = if (boxes.isEmpty()) 0 else box.mod(boxes.size)
        val b = boxes.getOrNull(index)
        t.putInt("box", index); t.putInt("boxes", boxes.size); t.putString("name", b?.name ?: "")
        t.put("slots", ListTag().also { l ->
            for (i in 0 until 30) {
                val p = b?.get(i)
                if (p == null) { l.add(CompoundTag()); continue }
                val c = mon(p)
                scores(Workers.profile(p), p, c, s.job)
                c.putBoolean("fainted", p.isFainted()); c.putBoolean("busy", p.tetheringId != null)
                l.add(c)
            }
        })
        Net.view(player, "pc", t)
    }

    fun sendBoard(player: ServerPlayer) {
        val server = player.server
        val t = CompoundTag()
        t.putInt("xp", Rules.state(server, player.uuid).xp)
        t.putInt("maxSites", Workers.maxSites(player.serverLevel(), player.uuid))
        t.putBoolean("remote", Rules.has(server, player.uuid, "remote_control"))
        t.putBoolean("admin", player.hasPermissions(2))
        t.put("sites", ListTag().also { l ->
            for ((level, s) in SiteStore.all(server)) {
                if (s.owner != player.uuid) continue
                l.add(CompoundTag().also { c ->
                    c.putLong("pos", s.pos.asLong()); c.putString("dim", level.dimension().location().toString())
                    c.putString("job", s.job.key); c.putBoolean("active", s.active)
                    c.putString("status", s.status); c.putString("statusArg", s.statusArg)
                    c.putInt("workers", s.workers.size); c.putLong("done", s.done)
                    s.plan?.let { p -> c.putInt("planSize", p.size); c.putInt("planIndex", p.index) }
                    c.put("mons", ListTag().also { ml -> s.workers.forEach { w -> w.pokemon?.let { ml.add(mon(it)) } } })
                })
            }
        })
        Net.view(player, "board", t)
    }

    fun sendSkills(player: ServerPlayer) {
        val tree = Rules.tree()
        val state = Rules.state(player.server, player.uuid)
        val t = CompoundTag()
        t.putInt("xp", state.xp); t.putString("mode", Config.d.mode)
        t.put("nodes", ListTag().also { l ->
            for (n in tree.rules.nodes) {
                if (n.max <= 0) continue
                l.add(CompoundTag().also { c ->
                    c.putString("id", n.id); c.putString("branch", n.branch); c.putInt("max", n.max)
                    c.putString("icon", n.icon); c.putInt("x", n.x); c.putInt("y", n.y)
                    c.putInt("level", tree.level(state, n.id)); c.putInt("cost", tree.cost(state, n.id) ?: -1)
                    c.putString("blocked", tree.blocked(state, n.id) ?: "")
                    c.putDouble("value", tree.value(state, n.id)); c.putDouble("perLevel", n.perLevel)
                    c.putString("requires", n.requires.entries.joinToString(",") { "${it.key}:${it.value}" })
                })
            }
        })
        Net.view(player, "skills", t)
    }

    /** Work Dex: ratings for any species by name, plus the player's own best Pokemon for each job. */
    fun sendDex(player: ServerPlayer, query: String) {
        val t = CompoundTag()
        val clean = query.trim().lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40)
        if (clean.isNotEmpty()) {
            val p = runCatching { PokemonProperties.parse("$clean level=50").create() }.getOrNull()
            if (p != null && p.species.resourceIdentifier.path == clean.replace("-", "").replace("_", "")) {
                t.put("lookup", mon(p).also { scores(Workers.profile(p), null, it, Job.LUMBER); it.putString("types", p.types.joinToString(",") { ty -> ty.name.lowercase() }) })
            } else t.putString("notFound", clean)
        }
        val mine = (player.party().toList() + player.pc().toList()).take(600)
        val profiles = mine.map { it to Workers.profile(it) }
        t.put("best", CompoundTag().also { b ->
            for (job in Job.entries) b.put(job.key, ListTag().also { l ->
                profiles.filter { it.second.score(job).able }.sortedByDescending { it.second.score(job).mult }.take(6).forEach { (p, prof) ->
                    l.add(mon(p).also { it.putInt("starsFor", prof.score(job).stars); it.putDouble("mult", prof.score(job).mult); it.putInt("mastery", Workers.masteryLevel(p, job)) })
                }
            })
        })
        Net.view(player, "dex", t)
    }

    /** The level perk ladders, plus the player's party so the screen can show who has reached what. */
    fun sendPerks(player: ServerPlayer) {
        val t = CompoundTag()
        t.put("jobs", CompoundTag().also { jobs ->
            for (job in Job.entries) jobs.put(job.key, ListTag().also { l ->
                Rules.set.global.unlocks[job.key]?.sortedBy { it.level }?.forEach { u ->
                    l.add(CompoundTag().also { c -> c.putInt("level", u.level); c.putString("id", u.id); c.putDouble("value", u.value) })
                }
            })
        })
        t.put("party", ListTag().also { l -> for (p in player.party()) l.add(mon(p)) })
        Net.view(player, "perks", t)
    }

    private fun sendAdmin(player: ServerPlayer) {
        val t = CompoundTag()
        for (k in Config.simpleKeys) t.putString(k, Config.get(k))
        var sites = 0; var workers = 0
        for ((_, s) in SiteStore.all(player.server)) { sites++; workers += s.workers.size }
        t.putInt("_sites", sites); t.putInt("_workers", workers)
        Net.view(player, "admin", t)
    }
}

/** Receives a Litematica placement from the client in pieces and turns it into a build plan for a job site. */
object PlanUpload {
    private class Pending(val site: BlockPos, val name: String, val total: Int) { val parts = HashMap<Int, ByteArray>(); var bytes = 0 }
    private val pending = HashMap<UUID, Pending>()

    fun forget(player: UUID) { pending.remove(player) }

    fun receive(player: ServerPlayer, part: Net.PlanPart) {
        val level = player.serverLevel()
        val s = SiteStore.of(level).sites[part.site]
        if (s == null || s.owner != player.uuid) { Foremon.tell(player, "foremon.fail.not_owner"); return }
        // Every limit here exists because this data comes from a client and cannot be trusted.
        val maxParts = Config.a.maxPlanBlocks * 14 / Net.PlanPart.MAX_BYTES + 2
        if (part.total !in 1..maxParts || part.index !in 0 until part.total || part.data.size > Net.PlanPart.MAX_BYTES) { pending.remove(player.uuid); return }
        var p = pending[player.uuid]
        if (p == null || p.site != part.site || p.name != part.name || p.total != part.total) { p = Pending(part.site, part.name, part.total); pending[player.uuid] = p }
        if (p.parts.put(part.index, part.data) == null) p.bytes += part.data.size
        if (p.parts.size < p.total) return
        pending.remove(player.uuid)

        val all = ByteArray(p.bytes)
        var at = 0
        for (i in 0 until p.total) { val d = p.parts[i] ?: return; System.arraycopy(d, 0, all, at, d.size); at += d.size }
        val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(all))
        val count = buf.readVarInt()
        if (count !in 1..Config.a.maxPlanBlocks) { Foremon.tell(player, "foremon.fail.plan_too_big", Config.a.maxPlanBlocks.toString()); return }
        val r = Workers.radius(level, s); val v = Workers.vertical(level, s)
        val blocks = ArrayList<Pair<BlockPos, BlockState>>(count)
        var outside = 0
        repeat(count) {
            val pos = BlockPos.of(buf.readLong())
            val state = Block.stateById(buf.readVarInt())
            if (state.isAir) return@repeat
            val d = pos.subtract(s.pos)
            if (kotlin.math.abs(d.x) > r || kotlin.math.abs(d.z) > r || kotlin.math.abs(d.y) > v || !level.isInWorldBounds(pos)) outside++
            else blocks.add(pos to state)
        }
        if (blocks.isEmpty()) { Foremon.tell(player, "foremon.fail.plan_outside", r.toString()); return }
        s.plan = Plan.ordered(p.name.take(48), blocks)
        s.job = Job.BUILDING
        Actions.reset(s)
        SiteStore.of(level).setDirty()
        Foremon.tell(player, if (outside > 0) "foremon.msg.plan_partial" else "foremon.msg.plan_loaded", blocks.size.toString(), outside.toString())
        Actions.sendSite(player, level, s)
    }
}
