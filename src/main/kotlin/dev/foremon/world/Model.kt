package dev.foremon.world

import com.cobblemon.mod.common.pokemon.Pokemon
import dev.foremon.Config
import dev.foremon.Foremon
import dev.foremon.rules.Job
import dev.foremon.rules.SkillState
import dev.foremon.rules.WorkerProfile
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/**
 * The Poke Balls a chest can be marked with, from the plain Poke Ball to the Master Ball. Each chest of a job site
 * shows its ball above it, so chests with different filters can be told apart at a glance. Ids are Cobblemon item ids.
 */
val BALLS = listOf(
    "poke_ball", "great_ball", "ultra_ball", "master_ball", "premier_ball", "luxury_ball", "heal_ball", "net_ball",
    "dusk_ball", "quick_ball", "timer_ball", "dive_ball", "love_ball", "moon_ball", "friend_ball", "cherish_ball",
)

fun ballIndex(id: String): Int = BALLS.indexOf(id).coerceAtLeast(0)

/** Settings of one extra chest: the ball it is marked with, and which items may be put in it. */
class ChestInfo(var ball: String) {
    val filter = Filter()
}

/** "Only these" or "all except these" over blocks and items. Entries are ids ("minecraft:stone") or tags ("#c:ores"). */
class Filter {
    var only = false
    val entries = ArrayList<String>()

    private var blocks: Set<Block>? = null
    private var items: Set<Item> = emptySet()
    private var blockTags: List<TagKey<Block>> = emptyList()
    private var itemTags: List<TagKey<Item>> = emptyList()

    fun changed() { blocks = null }

    private fun resolve() {
        if (blocks != null) return
        val b = HashSet<Block>(); val i = HashSet<Item>()
        val bt = ArrayList<TagKey<Block>>(); val it = ArrayList<TagKey<Item>>()
        for (e in entries) {
            val id = ResourceLocation.tryParse(e.removePrefix("#")) ?: continue
            if (e.startsWith("#")) {
                bt.add(TagKey.create(Registries.BLOCK, id)); it.add(TagKey.create(Registries.ITEM, id))
            } else {
                BuiltInRegistries.BLOCK.getOptional(id).ifPresent { b.add(it) }
                BuiltInRegistries.ITEM.getOptional(id).ifPresent { item -> i.add(item) }
            }
        }
        blocks = b; items = i; blockTags = bt; itemTags = it
    }

    fun allows(state: BlockState): Boolean {
        if (entries.isEmpty()) return true
        resolve()
        val hit = state.block in blocks!! || blockTags.any { state.`is`(it) }
        return hit == only
    }

    fun allows(stack: ItemStack): Boolean {
        if (entries.isEmpty()) return true
        resolve()
        val hit = stack.item in items || itemTags.any { stack.`is`(it) }
        return hit == only
    }

    fun save() = CompoundTag().also { t ->
        t.putBoolean("only", only)
        t.put("entries", ListTag().also { l -> entries.forEach { l.add(StringTag.valueOf(it)) } })
    }

    fun load(t: CompoundTag) {
        only = t.getBoolean("only")
        entries.clear()
        t.getList("entries", Tag.TAG_STRING.toInt()).forEach { entries.add(it.asString) }
        changed()
    }
}

/**
 * Guard: a box drawn on the map, all the way up and down. [AREA]: guards only work inside areas (once there is one),
 * and a guard posted to an area stays in it. [KEEP_OUT]: no hunting and no torches. [NO_TORCH]: hunting yes, torches no.
 * Coordinates are world x/z, both ends included; [id] stays the same while other boxes come and go.
 */
class Zone(val kind: Int, val id: Int, val x1: Int, val z1: Int, val x2: Int, val z2: Int) {
    fun has(x: Int, z: Int) = x in x1..x2 && z in z1..z2

    companion object {
        const val AREA = 0
        const val KEEP_OUT = 1
        const val NO_TORCH = 2
        /** What a check is for. */
        const val TORCHES = 0
        const val PATROL = 1
        const val MAX = 16
        const val MAX_ROUTE = 16

        /** May guards do [what] at x/z? A guard [post]ed to an area only works inside that area. */
        fun allows(zones: List<Zone>, what: Int, x: Int, z: Int, post: Int = 0): Boolean {
            var areas = false; var inArea = false
            for (b in zones) when (b.kind) {
                KEEP_OUT -> if (b.has(x, z)) return false
                NO_TORCH -> if (what == TORCHES && b.has(x, z)) return false
                AREA -> {
                    areas = true
                    if (b.has(x, z)) inArea = true
                    if (post != 0 && b.id == post && !b.has(x, z)) return false
                }
            }
            return !areas || inArea
        }

        fun pack(zones: List<Zone>) = zones.flatMap { listOf(it.kind, it.id, it.x1, it.z1, it.x2, it.z2) }.toIntArray()
        fun unpack(a: IntArray) = (0 until a.size / 6).map { i -> Zone(a[6 * i], a[6 * i + 1], a[6 * i + 2], a[6 * i + 3], a[6 * i + 4], a[6 * i + 5]) }
    }
}

/**
 * A site asking for [count] more of an item: haulers bring it from the owner's other chests. Any item in [accepts] will
 * do (a recipe that takes coal also takes charcoal). Asked again while still short.
 */
class Request(var count: Int, var at: Long, val accepts: Set<Item>)

/** A hauler's one-off load for a request: [count] of anything in [accepts] from the chest at [from] to the site at [to]. */
class Fetch(val from: BlockPos, val to: BlockPos, val item: Item, val accepts: Set<Item>, val count: Int)

/** What a worker is heading to do. Saved state stays small on purpose: a step is cheap to re-plan after a restart. */
class Step(val pos: BlockPos, val kind: Kind, val data: Int = 0) {
    enum class Kind { WORK, CHEST, DELIVER, PICKUP, PATROL }
}

enum class Emote { NONE, WORKING, TIRED, HUNGRY, NEED, STUCK, DONE, SLEEP }

class Worker(val pokemonId: UUID) {
    var tetherId: UUID = UUID.randomUUID()
    var entityId: UUID? = null
    var stamina = Config.a.baseStamina.toDouble()
    var restUntil = 0L
    var actions = 0
    /** Picked from a PC box rather than the party: it stays in the PC when it stops working. */
    var fromPc = false
    /** Results on their way to a chest. */
    val carried = ArrayList<ItemStack>()
    /** Materials taken from the chest to work with (blocks to place, ore to smelt). */
    val supplies = ArrayList<ItemStack>()

    // Not saved: rebuilt while running.
    var pokemon: Pokemon? = null
    var profile: WorkerProfile? = null
    var profileVersion = -1
    var profileLevel = -1
    var step: Step? = null
    var progress = 0
    var need = 1
    var stuck = 0
    var bestDist = Double.MAX_VALUE
    var failures = 0
    var planAt = 0L
    var teleportAt = 0L
    var truantRest = false
    var emote = Emote.NONE
    var shown: ItemStack = ItemStack.EMPTY
    var slowStartUntil = 0L
    /** Hauling: the trip of the current load. Null: none. Empty: off on a far road, out of sight. Else the flight path. */
    var path: List<net.minecraft.world.phys.Vec3>? = null
    /** Hauling: what the current load looks like (shown in its paws and on the depot board), and when it started. */
    var hauling: ItemStack = ItemStack.EMPTY
    var loadStart = 0L
    /** Guard: the patrol point it walks to next. */
    var patrolIndex = 0
    /** Hauling: the request load it is on, if any. */
    var fetch: Fetch? = null

    fun count(list: List<ItemStack>) = list.sumOf { it.count }
}

class Site(val pos: BlockPos, val owner: UUID, var ownerName: String) {
    var job = Job.LUMBER
    var active = false
    val workers = ArrayList<Worker>()
    var cornerA: BlockPos? = null
    var cornerB: BlockPos? = null
    val links = ArrayList<BlockPos>()
    val filter = Filter()
    /** The ball the site's own chest is marked with. */
    var ball = BALLS[0]
    /** Ball and filter of each extra chest, by position. */
    val chests = HashMap<BlockPos, ChestInfo>()

    /** Settings of an extra chest, created with the next ball in line if it has none yet. */
    fun chest(pos: BlockPos): ChestInfo = chests.getOrPut(pos) { ChestInfo(BALLS[(links.indexOf(pos) + 1).coerceIn(1, BALLS.size - 1)]) }

    /** The filter for [pos]: the site's own filter for its chest, the extra chest's filter otherwise. */
    fun filterFor(pos: BlockPos): Filter = if (pos == this.pos || pos !in links) filter else chest(pos).filter

    /** Hauling: where items go, from one chest to another. Either end may be the site's own chest or any extra chest. */
    val routes = ArrayList<Pair<BlockPos, BlockPos>>()

    /** Removes an extra chest, and every route that used it. */
    fun removeLink(pos: BlockPos): Boolean {
        chests.remove(pos)
        if (routes.removeIf { it.first == pos || it.second == pos }) { routeIdle.clear(); crews.values.removeIf { it.first == pos || it.second == pos } }
        return links.remove(pos)
    }

    /** Removes a route, and any extra chest no other route uses any more. */
    fun removeRoute(index: Int) {
        val r = routes.getOrNull(index) ?: return
        routes.removeAt(index); routeIdle.clear(); crews.values.removeIf { it == r }
        for (p in listOf(r.first, r.second)) if (p != pos && routes.none { it.first == p || it.second == p }) { links.remove(p); chests.remove(p) }
    }
    /** Guard: where torches go and where mobs are hunted, see Zone. */
    val zones = ArrayList<Zone>()
    fun zoneAllows(what: Int, x: Int, z: Int, post: Int = 0) = Zone.allows(zones, what, x, z, post)
    /** Guard: the area each guard is posted to, by Pokemon (zone id). Guards without a post work every area. */
    val posts = HashMap<UUID, Int>()
    /** Guard: points idle guards walk between, in order. */
    val patrol = ArrayList<BlockPos>()

    fun removeZone(id: Int) { zones.removeIf { it.id == id }; posts.values.removeIf { it == id } }

    /** Build job: uploaded placement, see Plan. */
    var plan: Plan? = null
    var done = 0L

    // Not saved.
    var status = ""
    var statusArg = ""
    val skip = HashMap<BlockPos, Long>()
    var scan: Any? = null
    val queue = ArrayDeque<BlockPos>()
    var rescanAt = 0L
    var validatedAt = 0L
    var notifiedAt = 0L
    /** Hauling: the route to try first next time, so one busy route cannot starve the others. */
    var routeCursor = 0
    /** Hauling: far routes that came up empty, by index, and when to try them again (so far chunks are not loaded for nothing). */
    val routeIdle = HashMap<Int, Long>()
    /** Hauling with Dispatch: haulers (by Pokemon) that only work one route. The rest work every route in turn. */
    val crews = HashMap<UUID, Pair<BlockPos, BlockPos>>()
    /** Hauling: items moved so far, for the depot board. */
    var hauled = 0L
    /** What this site is short of, for haulers to bring. Not saved: jobs ask again while they are still short. */
    val requests = LinkedHashMap<Item, Request>()
    /** Hauling: whether this depot's haulers also bring other sites what they ask for. */
    var fillRequests = true

    fun save(reg: HolderLookup.Provider) = CompoundTag().also { t ->
        t.putLong("pos", pos.asLong()); t.putUUID("owner", owner); t.putString("ownerName", ownerName)
        t.putString("job", job.key); t.putBoolean("active", active); t.putLong("done", done); t.putLong("hauled", hauled)
        cornerA?.let { t.putLong("a", it.asLong()) }; cornerB?.let { t.putLong("b", it.asLong()) }
        t.putLongArray("links", links.map { it.asLong() })
        t.putLongArray("routes", routes.flatMap { listOf(it.first.asLong(), it.second.asLong()) })
        if (zones.isNotEmpty()) t.putIntArray("zones2", Zone.pack(zones))
        if (patrol.isNotEmpty()) t.putLongArray("patrol", patrol.map { it.asLong() })
        if (!fillRequests) t.putBoolean("noRequests", true)
        t.put("filter", filter.save())
        t.putString("ball", ball)
        t.put("chests", ListTag().also { l ->
            for ((p, info) in chests) if (p in links) l.add(CompoundTag().also { c ->
                c.putLong("pos", p.asLong()); c.putString("ball", info.ball); c.put("filter", info.filter.save())
            })
        })
        plan?.let { t.put("plan", it.save()) }
        t.put("workers", ListTag().also { l ->
            for (w in workers) l.add(CompoundTag().also { c ->
                c.putUUID("pokemon", w.pokemonId); c.putUUID("tether", w.tetherId)
                w.entityId?.let { c.putUUID("entity", it) }
                c.putDouble("stamina", w.stamina); c.putLong("restUntil", w.restUntil); c.putInt("actions", w.actions)
                c.putBoolean("fromPc", w.fromPc)
                crews[w.pokemonId]?.let { (a, b) -> c.putLong("routeFrom", a.asLong()); c.putLong("routeTo", b.asLong()) }
                posts[w.pokemonId]?.let { c.putInt("post", it) }
                c.put("carried", stacks(w.carried, reg)); c.put("supplies", stacks(w.supplies, reg))
            })
        })
    }

    companion object {
        private fun stacks(list: List<ItemStack>, reg: HolderLookup.Provider) =
            ListTag().also { l -> list.filter { !it.isEmpty }.forEach { l.add(it.save(reg)) } }

        private fun stacks(list: ListTag, reg: HolderLookup.Provider, into: MutableList<ItemStack>) =
            list.forEach { ItemStack.parse(reg, it).ifPresent(into::add) }

        fun load(t: CompoundTag, reg: HolderLookup.Provider) =
            Site(BlockPos.of(t.getLong("pos")), t.getUUID("owner"), t.getString("ownerName")).also { s ->
                s.job = Job.of(t.getString("job")) ?: Job.LUMBER
                s.active = t.getBoolean("active"); s.done = t.getLong("done"); s.hauled = t.getLong("hauled")
                if (t.contains("a")) s.cornerA = BlockPos.of(t.getLong("a"))
                if (t.contains("b")) s.cornerB = BlockPos.of(t.getLong("b"))
                t.getLongArray("links").forEach { s.links.add(BlockPos.of(it)) }
                t.getLongArray("routes").let { a -> for (i in 0 until a.size / 2) s.routes.add(BlockPos.of(a[2 * i]) to BlockPos.of(a[2 * i + 1])) }
                s.zones.addAll(Zone.unpack(t.getIntArray("zones2")))
                t.getLongArray("patrol").forEach { s.patrol.add(BlockPos.of(it)) }
                s.fillRequests = !t.getBoolean("noRequests")
                s.filter.load(t.getCompound("filter"))
                if (t.contains("ball")) s.ball = t.getString("ball").takeIf { it in BALLS } ?: BALLS[0]
                for (c in t.getList("chests", Tag.TAG_COMPOUND.toInt())) {
                    c as CompoundTag
                    val info = ChestInfo(c.getString("ball").takeIf { it in BALLS } ?: BALLS[1])
                    info.filter.load(c.getCompound("filter"))
                    s.chests[BlockPos.of(c.getLong("pos"))] = info
                }
                if (t.contains("plan")) s.plan = Plan.load(t.getCompound("plan"))
                for (c in t.getList("workers", Tag.TAG_COMPOUND.toInt())) {
                    c as CompoundTag
                    val w = Worker(c.getUUID("pokemon"))
                    w.tetherId = c.getUUID("tether")
                    if (c.hasUUID("entity")) w.entityId = c.getUUID("entity")
                    w.stamina = c.getDouble("stamina"); w.restUntil = c.getLong("restUntil"); w.actions = c.getInt("actions")
                    w.fromPc = c.getBoolean("fromPc")
                    if (c.contains("routeTo")) s.crews[w.pokemonId] = BlockPos.of(c.getLong("routeFrom")) to BlockPos.of(c.getLong("routeTo"))
                    if (c.contains("post")) s.posts[w.pokemonId] = c.getInt("post")
                    stacks(c.getList("carried", Tag.TAG_COMPOUND.toInt()), reg, w.carried)
                    stacks(c.getList("supplies", Tag.TAG_COMPOUND.toInt()), reg, w.supplies)
                    s.workers.add(w)
                }
            }
    }
}

/** Job sites of one dimension, saved with the world. */
class SiteStore : SavedData() {
    val sites = HashMap<BlockPos, Site>()

    override fun save(tag: CompoundTag, reg: HolderLookup.Provider): CompoundTag {
        tag.put("sites", ListTag().also { l -> sites.values.forEach { l.add(it.save(reg)) } })
        return tag
    }

    companion object {
        private val FACTORY = Factory({ SiteStore() }, { tag, reg ->
            SiteStore().also { st ->
                for (c in tag.getList("sites", Tag.TAG_COMPOUND.toInt())) {
                    // One unreadable site must not take the others down with it.
                    runCatching { Site.load(c as CompoundTag, reg) }
                        .onSuccess { st.sites[it.pos] = it }
                        .onFailure { Foremon.LOG.error("Skipping an unreadable job site", it) }
                }
            }
        }, null)

        fun of(level: ServerLevel): SiteStore = level.dataStorage.computeIfAbsent(FACTORY, "foremon_sites")

        fun all(server: MinecraftServer): Sequence<Pair<ServerLevel, Site>> =
            server.allLevels.asSequence().flatMap { l -> of(l).sites.values.asSequence().map { l to it } }

        fun countFor(server: MinecraftServer, owner: UUID) = all(server).count { it.second.owner == owner }
        fun workersFor(server: MinecraftServer, owner: UUID) = all(server).filter { it.second.owner == owner }.sumOf { it.second.workers.size }
    }
}

/** Work XP and bought skill levels per player, saved in the overworld. */
class PlayerStore : SavedData() {
    private val players = HashMap<UUID, SkillState>()

    fun state(id: UUID): SkillState = players.getOrPut(id) { SkillState() }
    fun peek(id: UUID): SkillState? = players[id]
    fun reset(id: UUID) { players.remove(id); setDirty() }

    override fun save(tag: CompoundTag, reg: HolderLookup.Provider): CompoundTag {
        for ((id, s) in players) tag.put(id.toString(), CompoundTag().also { c ->
            c.putInt("xp", s.xp)
            c.put("bought", CompoundTag().also { b -> s.bought.forEach { (k, v) -> b.putInt(k, v) } })
        })
        return tag
    }

    companion object {
        private val FACTORY = Factory({ PlayerStore() }, { tag, _ ->
            PlayerStore().also { st ->
                for (key in tag.allKeys) {
                    val id = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
                    val c = tag.getCompound(key)
                    val s = st.state(id)
                    s.xp = c.getInt("xp")
                    val b = c.getCompound("bought")
                    b.allKeys.forEach { s.bought[it] = b.getInt(it) }
                }
            }
        }, null)

        fun of(server: MinecraftServer): PlayerStore = server.overworld().dataStorage.computeIfAbsent(FACTORY, "foremon_players")
    }
}
