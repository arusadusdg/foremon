package dev.foremon

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import dev.foremon.net.Actions
import dev.foremon.net.Net
import dev.foremon.net.PlanUpload
import dev.foremon.rules.Job
import dev.foremon.work.Inv
import dev.foremon.work.Ticker
import dev.foremon.world.PlayerStore
import dev.foremon.world.Site
import dev.foremon.world.SiteStore
import dev.foremon.world.Workers
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.properties.ChestType
import org.slf4j.LoggerFactory
import kotlin.math.abs

object Foremon : ModInitializer {
    const val ID = "foremon"
    val LOG = LoggerFactory.getLogger(ID)

    /** Start the game with -Dforemon.debug=true to log every decision a worker makes. Off in normal play. */
    val DEBUG = System.getProperty("foremon.debug") != null

    fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(ID, path)

    /** The one item of the mod. Use it on a chest to make a job site; everything else happens in its menu. */
    val WHISTLE: Item = Item(Item.Properties().stacksTo(1))

    /** Action-bar message from a translation key. Players without the mod see the fallback key rather than nothing. */
    fun tell(player: ServerPlayer, key: String, vararg args: String) {
        if (DEBUG) LOG.info("[to {}] {} {}", player.gameProfile.name, key, args.joinToString(" "))
        player.displayClientMessage(Component.translatable(key, *args), true)
    }

    override fun onInitialize() {
        Registry.register(BuiltInRegistries.ITEM, id("whistle"), WHISTLE)
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register { it.accept(WHISTLE) }
        Rules.register()
        Net.register()

        ServerLifecycleEvents.SERVER_STARTING.register { Config.load() }
        ServerTickEvents.START_WORLD_TICK.register { Ticker.tick(it); Net.syncMarkers(it) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            Actions.selected.remove(handler.player.uuid); PlanUpload.forget(handler.player.uuid); Net.forgetMarkers(handler.player.uuid)
        }

        UseBlockCallback.EVENT.register { player, level, hand, hit ->
            if (!player.getItemInHand(hand).`is`(WHISTLE) || player.isSpectator) return@register InteractionResult.PASS
            if (level is ServerLevel && player is ServerPlayer)
                Actions.guarded(player) { onWhistle(player, level, hit.blockPos, player.isShiftKeyDown) }
            InteractionResult.SUCCESS
        }
        UseItemCallback.EVENT.register { player, _, hand ->
            val stack = player.getItemInHand(hand)
            if (!stack.`is`(WHISTLE)) return@register InteractionResultHolder.pass(stack)
            // Blowing the whistle in the open brings up the Foreman's Board.
            if (player is ServerPlayer) Actions.guarded(player) { Actions.sendBoard(player) }
            InteractionResultHolder.success(stack)
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> dispatcher.register(command()) }
    }

    /** A double chest is one job site, whichever half is clicked. */
    private fun siteAt(level: ServerLevel, pos: BlockPos): Site? {
        val store = SiteStore.of(level)
        store.sites[pos]?.let { return it }
        val state = level.getBlockState(pos)
        if (state.block is ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
            return store.sites[pos.relative(ChestBlock.getConnectedDirection(state))]
        return null
    }

    private fun onWhistle(player: ServerPlayer, level: ServerLevel, pos: BlockPos, sneaking: Boolean) {
        val store = SiteStore.of(level)
        val here = siteAt(level, pos)
        if (!sneaking) {
            if (here != null) {
                if (here.owner != player.uuid && !player.hasPermissions(2)) { tell(player, "foremon.fail.not_owner"); return }
                Actions.selected[player.uuid] = GlobalPos.of(level.dimension(), here.pos)
                Actions.sendSite(player, level, here)
                return
            }
            if (Inv.storage(level, pos) == null) { tell(player, "foremon.hint.use_on_chest"); return }
            if (level.dimension().location().toString() in Config.a.blockedDimensions) { tell(player, "foremon.fail.dimension"); return }
            if (!dev.foremon.work.Protection.playerMayUse(level, player, pos)) { tell(player, "foremon.status.not_allowed", "${pos.x} ${pos.y} ${pos.z}"); return }
            // A chest that is someone else's link target cannot be taken over as a site.
            if (store.sites.values.any { pos in it.links && it.owner != player.uuid }) { tell(player, "foremon.fail.not_owner"); return }
            val max = Workers.maxSites(level, player.uuid)
            if (SiteStore.countFor(player.server, player.uuid) >= max) { tell(player, "foremon.fail.site_limit", max.toString()); return }
            val site = Site(pos, player.uuid, player.gameProfile.name)
            store.sites[pos] = site; store.setDirty()
            Actions.selected[player.uuid] = GlobalPos.of(level.dimension(), pos)
            tell(player, "foremon.msg.site_created")
            Actions.sendSite(player, level, site)
            return
        }

        // Sneak-click: mark things for the site that was opened last.
        val sel = Actions.selected[player.uuid]
        val s = if (sel != null && sel.dimension() == level.dimension()) store.sites[sel.pos()] else null
        if (s == null || (s.owner != player.uuid && !player.hasPermissions(2))) { tell(player, "foremon.hint.open_first"); return }
        val r = Workers.radius(level, s); val v = Workers.vertical(level, s)

        // Hauling: sneak-click the chest items come from, then the chest they go to.
        if (s.job == Job.HAULING && Inv.storage(level, pos) != null) {
            val msg = Actions.routeClick(player, level, s, here?.pos ?: pos, here?.owner)
            tell(player, msg[0], *msg.drop(1).toTypedArray())
            store.setDirty()
            return
        }
        if (Inv.storage(level, pos) != null && here !== s) {
            val linkPos = here?.pos ?: pos
            if (s.removeLink(linkPos)) { tell(player, "foremon.msg.unlinked") }
            else {
                val maxLinks = Rules.skill(player.server, s.owner, "linked_chests").toInt()
                val reach = Rules.skill(player.server, s.owner, "long_haul")
                if (here != null && here.owner != s.owner) { tell(player, "foremon.fail.not_owner"); return }
                // An extra chest becomes part of the site's storage, so only chests the player may use themselves can be added.
                if (!dev.foremon.work.Protection.playerMayUse(level, player, linkPos)) { tell(player, "foremon.fail.chest_protected"); return }
                if (s.links.size >= maxLinks) { tell(player, "foremon.fail.link_limit", maxLinks.toString()); return }
                if (linkPos.distSqr(s.pos) > reach * reach) { tell(player, "foremon.fail.link_far", reach.toInt().toString()); return }
                s.links.add(linkPos); s.chest(linkPos)
                tell(player, "foremon.msg.linked", s.links.size.toString(), maxLinks.toString())
            }
            store.setDirty()
            return
        }
        if (s.job != Job.MINING && s.job != Job.DEMOLITION) { tell(player, "foremon.hint.corners_job"); return }
        val d = pos.subtract(s.pos)
        if (abs(d.x) > r || abs(d.z) > r || abs(d.y) > v) { tell(player, "foremon.fail.outside_area", r.toString(), v.toString()); return }
        if (s.cornerA == null || s.cornerB != null) {
            s.cornerA = pos; s.cornerB = null
            tell(player, "foremon.msg.corner_a")
        } else {
            val a = s.cornerA!!
            val volume = (abs(a.x - pos.x) + 1L) * (abs(a.y - pos.y) + 1L) * (abs(a.z - pos.z) + 1L)
            if (volume > Config.a.maxMiningVolume) { tell(player, "foremon.fail.area_big", Config.a.maxMiningVolume.toString()); return }
            s.cornerB = pos
            tell(player, "foremon.msg.corner_b", volume.toString())
        }
        Actions.reset(s)
        store.setDirty()
    }

    private fun command() = Commands.literal("foremon")
        .then(Commands.literal("board").executes { c -> c.source.playerOrException.let { Actions.sendBoard(it) }; 1 })
        .then(Commands.literal("skills").executes { c -> c.source.playerOrException.let { Actions.sendSkills(it) }; 1 })
        .then(Commands.literal("dex").executes { c -> c.source.playerOrException.let { Actions.sendDex(it, "") }; 1 })
        .then(Commands.literal("perks").executes { c -> c.source.playerOrException.let { Actions.sendPerks(it) }; 1 })
        .then(Commands.literal("admin").requires { it.hasPermission(2) }
            .then(Commands.literal("config")
                .executes { c ->
                    Config.simpleKeys.forEach { k -> c.source.sendSuccess({ Component.literal("$k = ${Config.get(k)}") }, false) }; 1
                }
                .then(Commands.argument("key", StringArgumentType.word()).suggests { _, b -> Config.simpleKeys.forEach(b::suggest); b.buildFuture() }
                    .then(Commands.argument("value", StringArgumentType.word()).executes { c ->
                        val k = StringArgumentType.getString(c, "key"); val v = StringArgumentType.getString(c, "value")
                        val ok = Config.set(k, v)
                        c.source.sendSuccess({ Component.literal(if (ok) "$k = ${Config.get(k)}" else "Unknown setting or bad value: $k $v") }, true)
                        if (ok) 1 else 0
                    })))
            .then(Commands.literal("xp").then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("amount", IntegerArgumentType.integer(-1_000_000, 1_000_000)).executes { c ->
                val p = EntityArgument.getPlayer(c, "player"); val n = IntegerArgumentType.getInteger(c, "amount")
                val st = Rules.state(c.source.server, p.uuid)
                st.xp = (st.xp + n).coerceIn(0, dev.foremon.rules.SkillTree.MAX_XP); PlayerStore.of(c.source.server).setDirty()
                c.source.sendSuccess({ Component.literal("${p.gameProfile.name} now has ${st.xp} Work XP") }, true); 1
            })))
            .then(Commands.literal("reset").then(Commands.argument("player", EntityArgument.player()).executes { c ->
                val p = EntityArgument.getPlayer(c, "player")
                PlayerStore.of(c.source.server).reset(p.uuid)
                c.source.sendSuccess({ Component.literal("Reset the skill tree of ${p.gameProfile.name}") }, true); 1
            }))
            .then(Commands.literal("list").executes { c ->
                var n = 0
                for ((level, s) in SiteStore.all(c.source.server)) {
                    n++
                    c.source.sendSuccess({ Component.literal("${s.ownerName}: ${s.job.key} at ${s.pos.x} ${s.pos.y} ${s.pos.z} in ${level.dimension().location()} (${s.workers.size} workers, ${if (s.active) "running" else "paused"})") }, false)
                }
                c.source.sendSuccess({ Component.literal("$n job sites") }, false); n
            })
            .then(Commands.literal("perf").executes { c ->
                val ticks = Ticker.measuredTicks.coerceAtLeast(1)
                val ms = Ticker.spentNanos / 1_000_000.0 / ticks
                var sites = 0; var workers = 0; var running = 0
                for ((_, s) in SiteStore.all(c.source.server)) { sites++; workers += s.workers.size; if (s.active) running++ }
                c.source.sendSuccess({ Component.literal("Foremon: %.3f ms per tick on average over the last %d ticks; %d job sites (%d running), %d workers. Measuring again from now.".format(java.util.Locale.ROOT, ms, ticks, sites, running, workers)) }, false)
                Ticker.resetTiming()
                1
            })
            .then(Commands.literal("pauseall").executes { c ->
                var n = 0
                for ((level, s) in SiteStore.all(c.source.server)) if (s.active) { s.active = false; n++; SiteStore.of(level).setDirty() }
                c.source.sendSuccess({ Component.literal("Paused $n job sites") }, true); n
            })
            .then(Commands.literal("remove").then(Commands.argument("player", EntityArgument.player()).executes { c ->
                val p = EntityArgument.getPlayer(c, "player")
                var n = 0
                for (level in c.source.server.allLevels) {
                    val store = SiteStore.of(level)
                    for (s in store.sites.values.filter { it.owner == p.uuid }) { Workers.dissolve(level, s); store.sites.remove(s.pos); n++ }
                    store.setDirty()
                }
                c.source.sendSuccess({ Component.literal("Removed $n job sites of ${p.gameProfile.name}; their Pokemon are back in the PC") }, true); n
            })))
}
