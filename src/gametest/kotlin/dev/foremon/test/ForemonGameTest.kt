package dev.foremon.test

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import dev.foremon.Config
import dev.foremon.Rules
import dev.foremon.rules.Capability
import dev.foremon.rules.Job
import dev.foremon.work.Inv
import dev.foremon.world.Plan
import dev.foremon.world.Site
import dev.foremon.world.SiteStore
import dev.foremon.world.Worker
import dev.foremon.world.Workers
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestAssertException
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import java.util.UUID

/**
 * Runs on a real headless server with real Cobblemon Pokemon: every job is set up in a small test
 * area, a worker is put on it, and the test waits until the work is actually done in the world.
 * Run with: gradlew runGametest
 */
class ForemonGameTest : FabricGameTest {
    private val owner = UUID.fromString("00000000-0000-4000-8000-00000000f0e0")

    private fun fail(msg: String): Nothing = throw GameTestAssertException(msg)

    /** A stone floor, an owner who may work while offline (test players are never online), and no upkeep. */
    private fun setup(helper: GameTestHelper) {
        Config.d.allowWorkWhileOwnerOffline = true
        Config.d.upkeep = false
        Rules.state(helper.level.server, owner).bought["night_shift"] = 1
        for (x in 0..7) for (z in 0..7) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
    }

    private fun site(helper: GameTestHelper, rel: BlockPos, job: Job): Site {
        helper.setBlock(rel, Blocks.CHEST)
        val pos = helper.absolutePos(rel)
        val s = Site(pos, owner, "tester")
        s.job = job
        SiteStore.of(helper.level).sites[pos] = s
        return s
    }

    private fun worker(helper: GameTestHelper, s: Site, species: String, pokemonLevel: Int = 40): Worker {
        val level = helper.level
        val p = PokemonProperties.parse("$species level=$pokemonLevel").create()
        val pc = Cobblemon.storage.getPC(owner, level.registryAccess())
        if (!pc.add(p)) fail("could not put $species in the test PC")
        val w = Worker(p.uuid)
        w.pokemon = p
        s.workers.add(w)
        Workers.spawn(level, s, w, p)
        if (Workers.entity(level, w) == null) fail("$species did not spawn")
        return w
    }

    private fun put(helper: GameTestHelper, s: Site, stack: ItemStack) {
        val storage = Inv.storage(helper.level, s.pos) ?: fail("no chest")
        if (!Inv.insert(storage, stack).isEmpty) fail("chest refused items")
    }

    private fun count(helper: GameTestHelper, pos: BlockPos, item: Item): Int {
        val storage = Inv.storage(helper.level, pos) ?: return 0
        var n = 0
        for (v in storage.nonEmptyViews()) if (v.resource.item == item) n += v.amount.toInt()
        return n
    }

    private fun cleanup(helper: GameTestHelper, s: Site) {
        Workers.dissolve(helper.level, s)
        SiteStore.of(helper.level).sites.remove(s.pos)
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    fun rulesAndProfiles(helper: GameTestHelper) {
        if (Rules.set.jobs.size != Job.entries.size) fail("job rules did not load from the data pack")
        if (Rules.skills.nodes.size < 30) fail("skill tree did not load from the data pack")
        fun profile(s: String) = Workers.profile(PokemonProperties.parse("$s level=30").create())
        val geodude = profile("geodude"); val pidgey = profile("pidgey"); val charmander = profile("charmander"); val squirtle = profile("squirtle")
        if (geodude.score(Job.MINING).mult <= pidgey.score(Job.MINING).mult) fail("Geodude should out-mine Pidgey")
        if (geodude.miningTier <= pidgey.miningTier) fail("Rock/Ground should dig a tier higher")
        if (!charmander.score(Job.SMELTING).able) fail("Charmander should be able to smelt")
        if (squirtle.score(Job.SMELTING).able) fail("Squirtle should not be able to smelt")
        if (Capability.UNDERWATER !in squirtle.capabilities) fail("Squirtle should work underwater")
        helper.succeed()
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun lumberFellsATree(helper: GameTestHelper) {
        setup(helper)
        helper.setBlock(BlockPos(5, 0, 5), Blocks.DIRT)
        for (y in 1..4) helper.setBlock(BlockPos(5, y, 5), Blocks.OAK_LOG)
        for (x in 4..6) for (z in 4..6) for (y in 4..5) if (!(x == 5 && z == 5 && y == 4)) helper.setBlock(BlockPos(x, y, z), Blocks.OAK_LEAVES)
        val s = site(helper, BlockPos(1, 1, 1), Job.LUMBER)
        worker(helper, s, "machop")
        s.active = true
        helper.succeedWhen {
            if (!helper.getBlockState(BlockPos(5, 1, 5)).isAir && !helper.getBlockState(BlockPos(5, 1, 5)).`is`(Blocks.OAK_SAPLING)) fail("the trunk is still standing")
            if (count(helper, s.pos, Items.OAK_LOG) < 4) fail("logs have not reached the chest yet")
            cleanup(helper, s)
        }
    }

    private fun tree(helper: GameTestHelper, logs: Int) {
        helper.setBlock(BlockPos(5, 0, 5), Blocks.DIRT)
        for (y in 1..logs) helper.setBlock(BlockPos(5, y, 5), Blocks.OAK_LOG)
        for (x in 4..6) for (z in 4..6) for (y in logs..logs + 1) if (!(x == 5 && z == 5 && y == logs)) helper.setBlock(BlockPos(x, y, z), Blocks.OAK_LEAVES)
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun timberFellsTheWholeTreeInOneSwingAtLevel50(helper: GameTestHelper) {
        setup(helper)
        tree(helper, 5)
        val s = site(helper, BlockPos(1, 1, 1), Job.LUMBER)
        worker(helper, s, "machop", 50)
        s.active = true
        helper.succeedWhen {
            if (count(helper, s.pos, Items.OAK_LOG) < 5) fail("logs have not reached the chest yet")
            if (s.done != 1L) fail("a level 50 Pokemon should fell all 5 logs in one swing, but it took ${s.done}")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun noTimberBelowLevel20(helper: GameTestHelper) {
        setup(helper)
        tree(helper, 5)
        val s = site(helper, BlockPos(1, 1, 1), Job.LUMBER)
        worker(helper, s, "machop", 12)
        s.active = true
        helper.succeedWhen {
            if (count(helper, s.pos, Items.OAK_LOG) < 5) fail("logs have not reached the chest yet")
            if (s.done != 5L) fail("a level 12 Pokemon should need one swing per log, but 5 logs took ${s.done} swings")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun timberTakesFourLogsPerSwingAtLevel20(helper: GameTestHelper) {
        setup(helper)
        tree(helper, 6)
        val s = site(helper, BlockPos(1, 1, 1), Job.LUMBER)
        worker(helper, s, "machop", 20)
        s.active = true
        helper.succeedWhen {
            if (count(helper, s.pos, Items.OAK_LOG) < 6) fail("logs have not reached the chest yet")
            if (s.done != 2L) fail("a level 20 Pokemon takes 4 logs per swing, so 6 logs should take 2 swings, not ${s.done}")
            cleanup(helper, s)
        }
    }

    private fun leavesLeft(helper: GameTestHelper, logs: Int): Int {
        var n = 0
        for (x in 4..6) for (z in 4..6) for (y in logs..logs + 1) if (helper.getBlockState(BlockPos(x, y, z)).`is`(Blocks.OAK_LEAVES)) n++
        return n
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun leafSweepClearsTheCrownFromLevel10(helper: GameTestHelper) {
        setup(helper)
        tree(helper, 4)
        val s = site(helper, BlockPos(1, 1, 1), Job.LUMBER)
        worker(helper, s, "machop", 12)
        s.active = true
        helper.succeedWhen {
            if (!helper.getBlockState(BlockPos(5, 1, 5)).isAir && !helper.getBlockState(BlockPos(5, 1, 5)).`is`(Blocks.OAK_SAPLING)) fail("the trunk is still standing")
            if (leavesLeft(helper, 4) != 0) fail("Leaf Sweep should have removed every leaf, ${leavesLeft(helper, 4)} are left")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun noLeafSweepBelowLevel10(helper: GameTestHelper) {
        setup(helper)
        tree(helper, 4)
        val s = site(helper, BlockPos(1, 1, 1), Job.LUMBER)
        worker(helper, s, "machop", 5)
        s.active = true
        helper.succeedWhen {
            if (count(helper, s.pos, Items.OAK_LOG) < 4) fail("logs have not reached the chest yet")
            if (leavesLeft(helper, 4) == 0) fail("a level 5 Pokemon has no Leaf Sweep, so the leaves must still be there")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun veinMinerTakesFourOrePerSwingAtLevel15(helper: GameTestHelper) {
        setup(helper)
        for (x in 3..6) helper.setBlock(BlockPos(x, 1, 4), Blocks.COAL_ORE)
        val s = site(helper, BlockPos(1, 1, 1), Job.MINING)
        s.cornerA = helper.absolutePos(BlockPos(3, 1, 4)); s.cornerB = helper.absolutePos(BlockPos(6, 1, 4))
        worker(helper, s, "geodude", 15)
        s.active = true
        helper.succeedWhen {
            for (x in 3..6) if (!helper.getBlockState(BlockPos(x, 1, 4)).isAir) fail("ore not mined yet")
            if (count(helper, s.pos, Items.COAL) < 4) fail("coal has not reached the chest yet")
            if (s.done != 1L) fail("a level 15 miner takes 4 connected ore per swing, but this took ${s.done} swings")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun miningClearsTheMarkedArea(helper: GameTestHelper) {
        setup(helper)
        for (x in 3..5) for (z in 3..5) for (y in 1..2) helper.setBlock(BlockPos(x, y, z), Blocks.DIRT)
        val s = site(helper, BlockPos(1, 1, 1), Job.MINING)
        s.cornerA = helper.absolutePos(BlockPos(3, 1, 3)); s.cornerB = helper.absolutePos(BlockPos(5, 2, 5))
        worker(helper, s, "geodude")
        s.active = true
        helper.succeedWhen {
            for (x in 3..5) for (z in 3..5) for (y in 1..2) if (!helper.getBlockState(BlockPos(x, y, z)).isAir) fail("area not cleared")
            if (count(helper, s.pos, Items.DIRT) < 18) fail("dirt has not reached the chest yet")
            if (!helper.getBlockState(BlockPos(4, 0, 4)).`is`(Blocks.STONE)) fail("dug below the marked area")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun miningRespectsTheFilter(helper: GameTestHelper) {
        setup(helper)
        helper.setBlock(BlockPos(4, 1, 4), Blocks.DIRT)
        helper.setBlock(BlockPos(5, 1, 4), Blocks.COBBLESTONE)
        val s = site(helper, BlockPos(1, 1, 1), Job.MINING)
        s.cornerA = helper.absolutePos(BlockPos(4, 1, 4)); s.cornerB = helper.absolutePos(BlockPos(5, 1, 4))
        s.filter.only = true; s.filter.entries.add("minecraft:dirt"); s.filter.changed()
        worker(helper, s, "geodude")
        s.active = true
        helper.succeedWhen {
            if (!helper.getBlockState(BlockPos(4, 1, 4)).isAir) fail("dirt should be mined")
            if (count(helper, s.pos, Items.DIRT) < 1) fail("dirt not delivered yet")
            if (!helper.getBlockState(BlockPos(5, 1, 4)).`is`(Blocks.COBBLESTONE)) fail("cobblestone is filtered out and must stay")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun buildingFollowsThePlan(helper: GameTestHelper) {
        setup(helper)
        val s = site(helper, BlockPos(1, 1, 1), Job.BUILDING)
        val blocks = ArrayList<Pair<BlockPos, net.minecraft.world.level.block.state.BlockState>>()
        for (x in 3..5) for (z in 3..5) blocks.add(helper.absolutePos(BlockPos(x, 1, z)) to Blocks.OAK_PLANKS.defaultBlockState())
        blocks.add(helper.absolutePos(BlockPos(4, 2, 4)) to Blocks.TORCH.defaultBlockState())
        s.plan = Plan.ordered("test", blocks)
        put(helper, s, ItemStack(Items.OAK_PLANKS, 16))
        put(helper, s, ItemStack(Items.TORCH, 2))
        worker(helper, s, "machop")
        s.active = true
        helper.succeedWhen {
            for (x in 3..5) for (z in 3..5) if (!helper.getBlockState(BlockPos(x, 1, z)).`is`(Blocks.OAK_PLANKS)) fail("platform not finished")
            if (!helper.getBlockState(BlockPos(4, 2, 4)).`is`(Blocks.TORCH)) fail("torch not placed")
            if (s.active) fail("site should stop by itself when the build is done")
            if (count(helper, s.pos, Items.OAK_PLANKS) != 7) fail("exactly 9 planks should have been used")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun smeltingWithFire(helper: GameTestHelper) {
        setup(helper)
        val s = site(helper, BlockPos(1, 1, 1), Job.SMELTING)
        put(helper, s, ItemStack(Items.RAW_IRON, 3))
        worker(helper, s, "charmander")
        s.active = true
        helper.succeedWhen {
            if (count(helper, s.pos, Items.IRON_INGOT) < 3) fail("ingots not delivered yet")
            if (count(helper, s.pos, Items.RAW_IRON) != 0) fail("raw iron left over")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun sortingTopsUpMatchingChests(helper: GameTestHelper) {
        setup(helper)
        val s = site(helper, BlockPos(1, 1, 1), Job.SORTING)
        helper.setBlock(BlockPos(6, 1, 6), Blocks.CHEST)
        val target = helper.absolutePos(BlockPos(6, 1, 6))
        Inv.insert(Inv.storage(helper.level, target)!!, ItemStack(Items.COBBLESTONE, 1))
        s.links.add(target)
        put(helper, s, ItemStack(Items.COBBLESTONE, 8))
        put(helper, s, ItemStack(Items.DIRT, 4))
        worker(helper, s, "abra")
        s.active = true
        helper.succeedWhen {
            if (count(helper, target, Items.COBBLESTONE) < 9) fail("cobblestone not sorted yet")
            if (count(helper, target, Items.DIRT) != 0) fail("dirt has no matching chest and must stay")
            if (count(helper, s.pos, Items.DIRT) != 4) fail("dirt must stay in the site chest")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun buildersTakeMaterialsFromExtraChests(helper: GameTestHelper) {
        setup(helper)
        val s = site(helper, BlockPos(1, 1, 1), Job.BUILDING)
        helper.setBlock(BlockPos(1, 1, 6), Blocks.CHEST)
        val extra = helper.absolutePos(BlockPos(1, 1, 6))
        s.links.add(extra)
        // Every plank is in the extra chest; the job site chest itself is empty.
        Inv.insert(Inv.storage(helper.level, extra)!!, ItemStack(Items.OAK_PLANKS, 8))
        val blocks = ArrayList<Pair<BlockPos, net.minecraft.world.level.block.state.BlockState>>()
        for (x in 3..5) blocks.add(helper.absolutePos(BlockPos(x, 1, 3)) to Blocks.OAK_PLANKS.defaultBlockState())
        s.plan = Plan.ordered("test", blocks)
        worker(helper, s, "machop")
        s.active = true
        helper.succeedWhen {
            for (x in 3..5) if (!helper.getBlockState(BlockPos(x, 1, 3)).`is`(Blocks.OAK_PLANKS)) fail("row not built yet")
            if (count(helper, extra, Items.OAK_PLANKS) != 5) fail("exactly 3 planks should have come out of the extra chest")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun aFullChestOverflowsIntoExtraChests(helper: GameTestHelper) {
        setup(helper)
        val s = site(helper, BlockPos(1, 1, 1), Job.MINING)
        val main = Inv.storage(helper.level, s.pos)!!
        repeat(27) { Inv.insert(main, ItemStack(Items.STONE, 64)) }
        helper.setBlock(BlockPos(1, 1, 6), Blocks.CHEST)
        val extra = helper.absolutePos(BlockPos(1, 1, 6))
        s.links.add(extra)
        helper.setBlock(BlockPos(4, 1, 4), Blocks.DIRT)
        s.cornerA = helper.absolutePos(BlockPos(4, 1, 4)); s.cornerB = helper.absolutePos(BlockPos(4, 1, 4))
        worker(helper, s, "geodude")
        s.active = true
        helper.succeedWhen {
            if (!helper.getBlockState(BlockPos(4, 1, 4)).isAir) fail("dirt not mined yet")
            if (count(helper, extra, Items.DIRT) != 1) fail("the dirt should land in the extra chest because the main chest is full")
            if (count(helper, s.pos, Items.STONE) != 27 * 64) fail("the main chest must be untouched")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun farmingHarvestsAndReplants(helper: GameTestHelper) {
        setup(helper)
        for (x in 3..5) {
            helper.setBlock(BlockPos(x, 0, 4), Blocks.FARMLAND)
            helper.setBlock(BlockPos(x, 1, 4), (Blocks.WHEAT as net.minecraft.world.level.block.CropBlock).getStateForAge(7))
        }
        val s = site(helper, BlockPos(1, 1, 1), Job.FARMING)
        worker(helper, s, "bulbasaur")
        s.active = true
        helper.succeedWhen {
            if (count(helper, s.pos, Items.WHEAT) < 3) fail("wheat has not reached the chest yet")
            for (x in 3..5) {
                val st = helper.getBlockState(BlockPos(x, 1, 4))
                if (!st.`is`(Blocks.WHEAT)) fail("the crop at $x was not replanted")
                if ((Blocks.WHEAT as net.minecraft.world.level.block.CropBlock).isMaxAge(st)) fail("the crop at $x was not harvested")
            }
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun haulingRespectsEachChestsOwnFilter(helper: GameTestHelper) {
        setup(helper)
        val s = site(helper, BlockPos(1, 1, 1), Job.HAULING)
        helper.setBlock(BlockPos(6, 1, 2), Blocks.CHEST); helper.setBlock(BlockPos(6, 1, 5), Blocks.CHEST)
        val stone = helper.absolutePos(BlockPos(6, 1, 2)); val dirt = helper.absolutePos(BlockPos(6, 1, 5))
        s.links.add(stone); s.links.add(dirt)
        s.routes.add(s.pos to stone); s.routes.add(s.pos to dirt)
        s.chest(stone).filter.apply { only = true; entries.add("minecraft:cobblestone"); changed() }
        s.chest(dirt).filter.apply { only = true; entries.add("minecraft:dirt"); changed() }
        put(helper, s, ItemStack(Items.DIRT, 6))
        put(helper, s, ItemStack(Items.COBBLESTONE, 5))
        worker(helper, s, "pidgey")
        s.active = true
        helper.succeedWhen {
            if (count(helper, stone, Items.COBBLESTONE) != 5) fail("all cobblestone should be in the cobblestone chest")
            if (count(helper, dirt, Items.DIRT) != 6) fail("all dirt should be in the dirt chest")
            if (count(helper, stone, Items.DIRT) + count(helper, dirt, Items.COBBLESTONE) != 0) fail("something went into the wrong chest")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun haulersFlyNearbyRoutes(helper: GameTestHelper) {
        setup(helper)
        // A Lumber site's chest full of logs, a Smelting site's empty chest, and a Hauling depot between them.
        val lumber = site(helper, BlockPos(6, 1, 1), Job.LUMBER)
        val smelter = site(helper, BlockPos(6, 1, 6), Job.SMELTING)
        put(helper, lumber, ItemStack(Items.OAK_LOG, 10))
        val depot = site(helper, BlockPos(1, 1, 1), Job.HAULING)
        // One route straight from the Lumber chest to the Smelting chest; the depot's own chest is not on the way.
        depot.links.add(lumber.pos); depot.links.add(smelter.pos)
        depot.routes.add(lumber.pos to smelter.pos)
        val w = worker(helper, depot, "pidgey")
        depot.active = true
        var farthest = 0.0
        helper.onEachTick {
            Workers.entity(helper.level, w)?.let { farthest = maxOf(farthest, kotlin.math.sqrt(it.distanceToSqr(depot.pos.x + 0.5, it.y, depot.pos.z + 0.5))) }
        }
        helper.succeedWhen {
            if (count(helper, smelter.pos, Items.OAK_LOG) != 10) fail("all 10 logs should end up in the Smelting site's chest")
            if (farthest < 5.0) fail("the hauler only got ${"%.1f".format(farthest)} blocks from the depot; it should fly the route")
            val e = Workers.entity(helper.level, w)
            if (e == null || e.isNoGravity || e.isInvisible || w.path != null) fail("after its loads the hauler should be back on its feet")
            if (count(helper, lumber.pos, Items.OAK_LOG) + count(helper, depot.pos, Items.OAK_LOG) != 0) fail("logs were left behind on the way")
            if (w.actions < 1) fail("each load should count as an action")
            cleanup(helper, depot); cleanup(helper, lumber); cleanup(helper, smelter)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    fun routeEndsCanBeActiveJobSites(helper: GameTestHelper) {
        setup(helper)
        // Sneak-clicking a working Lumber site's chest and then a Smelting site's chest makes a route between them.
        val lumber = site(helper, BlockPos(6, 1, 1), Job.LUMBER).also { it.active = true }
        val smelter = site(helper, BlockPos(6, 1, 6), Job.SMELTING).also { it.active = true }
        val depot = site(helper, BlockPos(1, 1, 1), Job.HAULING)
        val player = helper.makeMockServerPlayerInLevel()
        val first = dev.foremon.net.Actions.routeClick(player, helper.level, depot, lumber.pos, lumber.owner)
        if (first[0] != "foremon.msg.route_from") fail("a job site's chest should start a route, got ${first[0]}")
        val second = dev.foremon.net.Actions.routeClick(player, helper.level, depot, smelter.pos, smelter.owner)
        if (second[0] != "foremon.msg.route_added") fail("a job site's chest should end a route, got ${second[0]}")
        if (depot.routes != listOf(lumber.pos to smelter.pos)) fail("expected a route from Lumber to Smelting, got ${depot.routes}")
        if (!lumber.active || !smelter.active || lumber.job != Job.LUMBER) fail("the job sites should keep working as they were")
        cleanup(helper, depot); cleanup(helper, lumber); cleanup(helper, smelter)
        helper.succeed()
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    fun twoSneakClicksMakeARoute(helper: GameTestHelper) {
        setup(helper)
        val depot = site(helper, BlockPos(1, 1, 1), Job.HAULING)
        helper.setBlock(BlockPos(5, 1, 1), Blocks.CHEST); helper.setBlock(BlockPos(5, 1, 5), Blocks.CHEST)
        val a = helper.absolutePos(BlockPos(5, 1, 1)); val b = helper.absolutePos(BlockPos(5, 1, 5))
        val player = helper.makeMockServerPlayerInLevel()
        val first = dev.foremon.net.Actions.routeClick(player, helper.level, depot, a, null)
        if (first[0] != "foremon.msg.route_from") fail("the first click should start a route, got ${first[0]}")
        val second = dev.foremon.net.Actions.routeClick(player, helper.level, depot, b, null)
        if (second[0] != "foremon.msg.route_added") fail("the second click should finish the route, got ${second[0]}")
        if (depot.routes != listOf(a to b)) fail("expected one route from A to B, got ${depot.routes}")
        if (a !in depot.links || b !in depot.links) fail("both ends should become chests of the site")
        dev.foremon.net.Actions.routeClick(player, helper.level, depot, b, null)
        val again = dev.foremon.net.Actions.routeClick(player, helper.level, depot, b, null)
        if (again[0] != "foremon.msg.route_cancelled") fail("clicking the same chest twice should cancel, got ${again[0]}")
        depot.removeRoute(0)
        if (depot.routes.isNotEmpty() || depot.links.isNotEmpty()) fail("removing the only route should also drop its chests")
        SiteStore.of(helper.level).sites.remove(depot.pos)
        helper.succeed()
    }

    /** A job site of the test owner at an absolute position, with its chest. */
    private fun farSite(helper: GameTestHelper, pos: BlockPos, job: Job): Site {
        helper.level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState())
        val s = Site(pos, owner, "tester"); s.job = job
        SiteStore.of(helper.level).sites[pos] = s
        return s
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 6000, batch = "far_route")
    fun farRouteBringsWoodFromALumberSite600BlocksAway(helper: GameTestHelper) {
        setup(helper)
        withSkill(helper, "remote_control", 1); withSkill(helper, "supply_lines", 1)
        val depot = site(helper, BlockPos(1, 1, 1), Job.HAULING)
        val lumber = farSite(helper, depot.pos.offset(600, 0, 0), Job.LUMBER)
        put(helper, lumber, ItemStack(Items.OAK_LOG, 12))
        val msg = dev.foremon.net.Actions.remoteRoute(helper.level, depot, lumber.pos, depot.pos)
        if (msg[0] != "foremon.msg.route_added") fail("the remote route was refused: ${msg[0]}")
        val w = worker(helper, depot, "pidgey")
        depot.active = true
        var hidden = false
        helper.onEachTick { if (Workers.entity(helper.level, w)?.isInvisible == true) hidden = true }
        helper.succeedWhen {
            if (count(helper, depot.pos, Items.OAK_LOG) != 12) fail("the logs have not all reached the depot yet")
            if (!hidden) fail("on a far road the hauler should be out of sight, not standing at the depot")
            if (depot.hauled != 12L) fail("the depot board should count 12 items moved, got ${depot.hauled}")
            cleanup(helper, depot); cleanup(helper, lumber)
            withSkill(helper, "remote_control", 0); withSkill(helper, "supply_lines", 0)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 2400, batch = "dispatch")
    fun dispatchSendsHaulersWhereTheyAreTold(helper: GameTestHelper) {
        setup(helper)
        val depot = site(helper, BlockPos(1, 1, 1), Job.HAULING)
        val logsFrom = site(helper, BlockPos(6, 1, 1), Job.LUMBER); val logsTo = site(helper, BlockPos(6, 1, 3), Job.SMELTING)
        val stoneFrom = site(helper, BlockPos(6, 1, 5), Job.MINING); val stoneTo = site(helper, BlockPos(3, 1, 6), Job.BUILDING)
        put(helper, logsFrom, ItemStack(Items.OAK_LOG, 8)); put(helper, stoneFrom, ItemStack(Items.COBBLESTONE, 8))
        for (st in listOf(logsFrom, logsTo, stoneFrom, stoneTo)) depot.links.add(st.pos)
        depot.routes.add(logsFrom.pos to logsTo.pos); depot.routes.add(stoneFrom.pos to stoneTo.pos)
        val w = worker(helper, depot, "pidgey")
        withSkill(helper, "dispatch", 0)
        val no = dev.foremon.net.Actions.crewToggle(helper.level.server, depot, 1, w.pokemonId)
        if (no?.get(0) != "foremon.fail.needs_dispatch") fail("without Dispatch a hauler cannot get a route of its own, got $no")
        withSkill(helper, "dispatch", 1)
        if (dev.foremon.net.Actions.crewToggle(helper.level.server, depot, 1, w.pokemonId) != null) fail("with Dispatch 1 the hauler should get route 2")
        depot.active = true
        helper.succeedWhen {
            if (count(helper, stoneTo.pos, Items.COBBLESTONE) != 8) fail("the stone route should be done")
            if (count(helper, logsFrom.pos, Items.OAK_LOG) != 8) fail("the hauler was sent to the stone route only, but it moved logs")
            for (st in listOf(depot, logsFrom, logsTo, stoneFrom, stoneTo)) cleanup(helper, st)
            withSkill(helper, "dispatch", 0)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "route_rules")
    fun remoteRoutesNeedRemoteControlAndRange(helper: GameTestHelper) {
        setup(helper)
        val depot = site(helper, BlockPos(1, 1, 1), Job.HAULING)
        val near = farSite(helper, depot.pos.offset(300, 0, 0), Job.LUMBER)
        val far = farSite(helper, depot.pos.offset(0, 0, 2000), Job.MINING)
        withSkill(helper, "remote_control", 0); withSkill(helper, "supply_lines", 0)
        val a = dev.foremon.net.Actions.remoteRoute(helper.level, depot, near.pos, depot.pos)
        if (a[0] != "foremon.fail.needs_remote") fail("without Remote Control it should be refused, got ${a[0]}")
        withSkill(helper, "remote_control", 1)
        val b = dev.foremon.net.Actions.remoteRoute(helper.level, depot, far.pos, depot.pos)
        if (b[0] != "foremon.fail.route_far" || b[1] != "500") fail("2000 blocks is past the 500 block start, got $b")
        val c = dev.foremon.net.Actions.remoteRoute(helper.level, depot, near.pos, depot.pos)
        if (c[0] != "foremon.msg.route_added") fail("300 blocks with Remote Control should work, got ${c[0]}")
        withSkill(helper, "supply_lines", 5)
        if (Rules.skill(helper.level.server, owner, "supply_lines") != 5000.0) fail("Supply Lines should top out at 5000 blocks")
        val d = dev.foremon.net.Actions.remoteRoute(helper.level, depot, far.pos, depot.pos)
        if (d[0] != "foremon.msg.route_added") fail("2000 blocks with Supply Lines 5 should work, got ${d[0]}")
        for (st in listOf(depot, near, far)) SiteStore.of(helper.level).sites.remove(st.pos)
        withSkill(helper, "remote_control", 0); withSkill(helper, "supply_lines", 0)
        helper.succeed()
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600, batch = "bone_meal_on")
    fun boneMealSkillGrowsSaplings(helper: GameTestHelper) {
        setup(helper)
        withSkill(helper, "bone_meal", 3)
        helper.setBlock(BlockPos(5, 0, 5), Blocks.DIRT)
        helper.setBlock(BlockPos(5, 1, 5), Blocks.OAK_SAPLING)
        val s = site(helper, BlockPos(1, 1, 1), Job.LUMBER)
        put(helper, s, ItemStack(Items.BONE_MEAL, 16))
        worker(helper, s, "machop")
        s.active = true
        helper.succeedWhen {
            if (count(helper, s.pos, Items.BONE_MEAL) >= 16) fail("no bone meal was used yet")
            val grown = helper.getBlockState(BlockPos(5, 1, 5)).`is`(net.minecraft.tags.BlockTags.LOGS) || count(helper, s.pos, Items.OAK_LOG) > 0
            if (!grown) fail("the sapling has not grown into a tree yet")
            cleanup(helper, s)
            withSkill(helper, "bone_meal", 0)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400, batch = "bone_meal_off")
    fun withoutTheSkillSaplingsAreLeftAlone(helper: GameTestHelper) {
        setup(helper)
        withSkill(helper, "bone_meal", 0)
        helper.setBlock(BlockPos(5, 0, 5), Blocks.DIRT)
        helper.setBlock(BlockPos(5, 1, 5), Blocks.OAK_SAPLING)
        val s = site(helper, BlockPos(1, 1, 1), Job.LUMBER)
        put(helper, s, ItemStack(Items.BONE_MEAL, 16))
        worker(helper, s, "machop")
        s.active = true
        helper.runAfterDelay(300) {
            if (count(helper, s.pos, Items.BONE_MEAL) != 16) fail("bone meal was used without the Bone Meal skill")
            cleanup(helper, s)
            helper.succeed()
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun demolitionTakesTheAreaApart(helper: GameTestHelper) {
        setup(helper)
        for (x in 3..5) for (y in 1..2) helper.setBlock(BlockPos(x, y, 4), Blocks.OAK_PLANKS)
        val s = site(helper, BlockPos(1, 1, 1), Job.DEMOLITION)
        s.cornerA = helper.absolutePos(BlockPos(3, 1, 4)); s.cornerB = helper.absolutePos(BlockPos(5, 2, 4))
        worker(helper, s, "machop")
        s.active = true
        helper.succeedWhen {
            for (x in 3..5) for (y in 1..2) if (!helper.getBlockState(BlockPos(x, y, 4)).isAir) fail("not all blocks are down yet")
            if (!helper.getBlockState(BlockPos(4, 0, 4)).`is`(Blocks.STONE)) fail("dug below the marked area")
            if (count(helper, s.pos, Items.OAK_PLANKS) < 1) fail("no planks came back, each should come back 70 percent of the time")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 200)
    fun sendingAWorkerHomeKeepsThePokemonSafe(helper: GameTestHelper) {
        setup(helper)
        val s = site(helper, BlockPos(1, 1, 1), Job.LUMBER)
        val w = worker(helper, s, "bulbasaur")
        val id = w.pokemonId
        helper.runAfterDelay(10) { Workers.unassign(helper.level, s, w, null) }
        helper.succeedWhen {
            if (s.workers.isNotEmpty()) fail("worker still on the site")
            val p = Cobblemon.storage.getPC(owner, helper.level.registryAccess())[id] ?: fail("THE POKEMON WAS LOST")
            if (p.tetheringId != null) fail("Pokemon is still marked as working")
            if (p.entity != null && p.entity!!.isAlive) fail("worker entity still in the world")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    fun theWhistleRecipeExists(helper: GameTestHelper) {
        val r = helper.level.recipeManager.byKey(dev.foremon.Foremon.id("whistle")).orElse(null) ?: fail("no recipe for the whistle")
        if (!r.value().getResultItem(helper.level.registryAccess()).`is`(dev.foremon.Foremon.WHISTLE)) fail("the recipe does not make the whistle")
        helper.succeed()
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 200)
    fun roamingBoxGrowsAfterAWorkAreaUpgrade(helper: GameTestHelper) {
        setup(helper)
        val s = site(helper, BlockPos(1, 1, 1), Job.LUMBER)
        val w = worker(helper, s, "bulbasaur")
        val before = Workers.entity(helper.level, w)!!.tethering!!.maxRoamPos
        val state = Rules.state(helper.level.server, owner)
        state.bought["work_area"] = (state.bought["work_area"] ?: 0) + 2
        helper.runAfterDelay(5) { Workers.validate(helper.level, s) }
        helper.succeedWhen {
            val e = Workers.entity(helper.level, w) ?: fail("worker vanished")
            val after = e.tethering?.maxRoamPos ?: fail("worker lost its job site")
            if (after.x - before.x != 16) fail("the roaming box should be 16 blocks wider on each side, but grew by ${after.x - before.x}")
            state.bought["work_area"] = (state.bought["work_area"] ?: 2) - 2
            cleanup(helper, s)
        }
    }

    // Upkeep changes a global setting, so it runs in its own batch, after the others.
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400, batch = "upkeep")
    fun tiredWorkersEatBerriesThenSleepWhenThereAreNone(helper: GameTestHelper) {
        setup(helper)
        Config.d.upkeep = true
        for (x in 3..6) for (z in 3..6) helper.setBlock(BlockPos(x, 1, z), Blocks.DIRT)
        val s = site(helper, BlockPos(1, 1, 1), Job.MINING)
        s.cornerA = helper.absolutePos(BlockPos(3, 1, 3)); s.cornerB = helper.absolutePos(BlockPos(6, 1, 6))
        val berry = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cobblemon", "oran_berry"))
        put(helper, s, ItemStack(berry, 1))
        val w = worker(helper, s, "geodude")
        w.stamina = 1.0
        s.active = true
        var ate = false
        helper.succeedWhen {
            if (!ate) {
                if (count(helper, s.pos, berry) != 0) fail("the worker has not eaten the berry yet")
                ate = true
                w.stamina = 0.0
            }
            if (w.restUntil <= helper.level.gameTime) fail("with no berries left it should fall asleep")
            if (w.emote != dev.foremon.world.Emote.SLEEP && w.emote != dev.foremon.world.Emote.HUNGRY) fail("it should show the sleep or hungry icon, not ${w.emote}")
            Config.d.upkeep = false
            cleanup(helper, s)
        }
    }

    /**
     * 5 job sites with 4 workers each, all mining at once, for 600 ticks. Measures Foremon's own time per tick
     * and the whole server tick (which also includes Cobblemon moving and animating 20 Pokemon).
     * Runs in its own batch because it builds outside the normal test area.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1400, batch = "stress")
    fun stressTwentyWorkersFiveSites(helper: GameTestHelper) {
        setup(helper)
        val level = helper.level
        val base = helper.absolutePos(BlockPos(0, 0, 0))
        for (x in -2..46) for (z in -2..14) level.setBlockAndUpdate(base.offset(x, 0, z), Blocks.STONE.defaultBlockState())
        val sites = ArrayList<dev.foremon.world.Site>()
        val species = listOf("geodude", "machop", "onix", "diglett", "sandshrew")
        for (i in 0 until 5) {
            val sx = i * 9
            val chestPos = base.offset(sx + 1, 1, 1)
            level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState())
            for (x in 2..7) for (z in 4..9) for (y in 1..3) level.setBlockAndUpdate(base.offset(sx + x, y, z), Blocks.DIRT.defaultBlockState())
            val s = dev.foremon.world.Site(chestPos, owner, "tester")
            s.job = Job.MINING
            s.cornerA = base.offset(sx + 2, 1, 4); s.cornerB = base.offset(sx + 7, 3, 9)
            SiteStore.of(level).sites[chestPos] = s
            repeat(4) { k -> worker(helper, s, species[(i + k) % species.size]) }
            s.active = true
            sites.add(s)
        }
        val tickTimes = ArrayList<Long>()
        var last = 0L
        var ticks = 0
        dev.foremon.work.Ticker.resetTiming()
        helper.onEachTick {
            val now = System.nanoTime()
            if (last != 0L) tickTimes.add(now - last)
            last = now
            ticks++
        }
        helper.runAfterDelay(600) {
            val foremonMs = dev.foremon.work.Ticker.spentNanos / 1_000_000.0 / dev.foremon.work.Ticker.measuredTicks.coerceAtLeast(1)
            val sorted = tickTimes.sorted()
            val avg = tickTimes.average() / 1_000_000.0
            val p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)] / 1_000_000.0
            val mined = sites.sumOf { it.done }
            dev.foremon.Foremon.LOG.info("STRESS RESULT: 20 workers on 5 sites for {} ticks: Foremon {} ms per tick; whole server tick average {} ms, 95th percentile {} ms; {} blocks mined",
                ticks, "%.3f".format(java.util.Locale.ROOT, foremonMs), "%.2f".format(java.util.Locale.ROOT, avg), "%.2f".format(java.util.Locale.ROOT, p95), mined)
            sites.forEach { s -> Workers.dissolve(level, s); SiteStore.of(level).sites.remove(s.pos) }
            if (mined < 40) fail("the crews barely worked ($mined blocks), so the timing means nothing")
            if (foremonMs > 2.0) fail("Foremon took $foremonMs ms per tick with 20 workers; the budget is 2 ms")
            helper.succeed()
        }
    }

    // ------------------------------------------------------------ level perks

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun wideHarvestTakesTheRowInOneAction(helper: GameTestHelper) {
        setup(helper)
        for (x in 3..4) {
            helper.setBlock(BlockPos(x, 0, 4), Blocks.FARMLAND)
            helper.setBlock(BlockPos(x, 1, 4), (Blocks.WHEAT as net.minecraft.world.level.block.CropBlock).getStateForAge(7))
        }
        val s = site(helper, BlockPos(1, 1, 1), Job.FARMING)
        worker(helper, s, "bulbasaur", 20)
        s.active = true
        helper.succeedWhen {
            if (count(helper, s.pos, Items.WHEAT) < 2) fail("wheat has not reached the chest yet")
            if (s.done != 1L) fail("a level 20 farmer has Wide Harvest, so 2 crops side by side should take 1 action, not ${s.done}")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun bigBatchSmeltsTwoAtOnceAtLevel20(helper: GameTestHelper) {
        setup(helper)
        val s = site(helper, BlockPos(1, 1, 1), Job.SMELTING)
        put(helper, s, ItemStack(Items.RAW_IRON, 4))
        worker(helper, s, "charmander", 20)
        s.active = true
        helper.succeedWhen {
            if (count(helper, s.pos, Items.IRON_INGOT) < 4) fail("ingots not delivered yet")
            if (s.done != 2L) fail("Big Batch at level 20 smelts 2 at once, so 4 raw iron should take 2 actions, not ${s.done}")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
    fun quickHandsBuildsTwoBlocksPerActionAtLevel25(helper: GameTestHelper) {
        setup(helper)
        val s = site(helper, BlockPos(1, 1, 1), Job.BUILDING)
        val blocks = ArrayList<Pair<BlockPos, net.minecraft.world.level.block.state.BlockState>>()
        for (x in 3..6) blocks.add(helper.absolutePos(BlockPos(x, 1, 4)) to Blocks.OAK_PLANKS.defaultBlockState())
        s.plan = Plan.ordered("test", blocks)
        put(helper, s, ItemStack(Items.OAK_PLANKS, 8))
        worker(helper, s, "machop", 25)
        s.active = true
        helper.succeedWhen {
            for (x in 3..6) if (!helper.getBlockState(BlockPos(x, 1, 4)).`is`(Blocks.OAK_PLANKS)) fail("row not built yet")
            if (s.done > 2L) fail("Quick Hands at level 25 places 2 blocks per action, so 4 blocks should take at most 2 actions, not ${s.done}")
            if (count(helper, s.pos, Items.OAK_PLANKS) != 4) fail("exactly 4 planks should have been used")
            cleanup(helper, s)
        }
    }

    // ------------------------------------------------------------ skill upgrades (own batch: they change the shared test owner)

    private fun withSkill(helper: GameTestHelper, node: String, level: Int = 1) { Rules.state(helper.level.server, owner).bought[node] = level }
    private fun dropSkill(helper: GameTestHelper, node: String) { Rules.state(helper.level.server, owner).bought.remove(node) }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600, batch = "skills")
    fun treeFellerSkillFellsTheWholeTreeAtAnyLevel(helper: GameTestHelper) {
        setup(helper)
        withSkill(helper, "tree_feller")
        tree(helper, 5)
        val s = site(helper, BlockPos(1, 1, 1), Job.LUMBER)
        worker(helper, s, "machop", 5)
        s.active = true
        helper.succeedWhen {
            if (count(helper, s.pos, Items.OAK_LOG) < 5) fail("logs have not reached the chest yet")
            if (s.done != 1L) fail("with Tree Feller even a level 5 Pokemon fells the tree in 1 swing, not ${s.done}")
            if (leavesLeft(helper, 5) != 0) fail("Tree Feller also clears the leaves")
            dropSkill(helper, "tree_feller")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600, batch = "skills")
    fun veinMinerSkillTakesTheVeinAtAnyLevel(helper: GameTestHelper) {
        setup(helper)
        withSkill(helper, "vein_miner")
        for (x in 3..6) helper.setBlock(BlockPos(x, 1, 4), Blocks.COAL_ORE)
        val s = site(helper, BlockPos(1, 1, 1), Job.MINING)
        s.cornerA = helper.absolutePos(BlockPos(3, 1, 4)); s.cornerB = helper.absolutePos(BlockPos(6, 1, 4))
        worker(helper, s, "geodude", 5)
        s.active = true
        helper.succeedWhen {
            for (x in 3..6) if (!helper.getBlockState(BlockPos(x, 1, 4)).isAir) fail("ore not mined yet")
            if (s.done != 1L) fail("with Vein Miner the whole vein comes out in 1 swing, not ${s.done}")
            dropSkill(helper, "vein_miner")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600, batch = "skills")
    fun twinFurnaceSkillSmeltsTwoAtOnce(helper: GameTestHelper) {
        setup(helper)
        withSkill(helper, "twin_furnace")
        val s = site(helper, BlockPos(1, 1, 1), Job.SMELTING)
        put(helper, s, ItemStack(Items.RAW_IRON, 4))
        worker(helper, s, "charmander", 10)
        s.active = true
        helper.succeedWhen {
            if (count(helper, s.pos, Items.IRON_INGOT) < 4) fail("ingots not delivered yet")
            if (s.done != 2L) fail("Twin Furnace smelts 2 at once, so 4 raw iron should take 2 actions, not ${s.done}")
            dropSkill(helper, "twin_furnace")
            cleanup(helper, s)
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 200, batch = "skills")
    fun packMuleSkillCarriesMore(helper: GameTestHelper) {
        setup(helper)
        val s = site(helper, BlockPos(1, 1, 1), Job.LUMBER)
        val w = worker(helper, s, "machop", 30)
        val base = Workers.profile(w)!!.carry
        withSkill(helper, "pack_mule", 2)
        val c = dev.foremon.work.Ctx(helper.level, s, Inv.storage(helper.level, s.pos)!!, helper.level.gameTime)
        c.w = w; c.e = Workers.entity(helper.level, w)!!; c.p = w.pokemon!!; c.profile = Workers.profile(w)!!
        val expected = (base * 1.5).toInt()
        if (c.capacity() != expected) fail("two levels of Pack Mule should carry ${expected}, not ${c.capacity()}")
        dropSkill(helper, "pack_mule")
        cleanup(helper, s)
        helper.succeed()
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 200)
    fun breakingTheChestSendsWorkersHome(helper: GameTestHelper) {
        setup(helper)
        val s = site(helper, BlockPos(1, 1, 1), Job.LUMBER)
        val w = worker(helper, s, "bulbasaur")
        val id = w.pokemonId
        s.active = true
        helper.runAfterDelay(20) { helper.setBlock(BlockPos(1, 1, 1), Blocks.AIR) }
        helper.succeedWhen {
            if (SiteStore.of(helper.level).sites.containsKey(s.pos)) fail("site still exists")
            val p = Cobblemon.storage.getPC(owner, helper.level.registryAccess())[id] ?: fail("THE POKEMON WAS LOST")
            if (p.tetheringId != null) fail("Pokemon is still marked as working")
            if (p.entity != null && p.entity!!.isAlive) fail("worker entity still in the world")
        }
    }
}
