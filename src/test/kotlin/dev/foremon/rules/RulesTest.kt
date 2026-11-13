package dev.foremon.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Runs against the JSON that actually ships in the jar, so a typo in the defaults fails the build. */
class RulesTest {
    private val dir = File("src/main/resources/data/foremon/foremon")
    private val rules = RuleSet(
        RuleSet.parseGlobal(File(dir, "rules.json").readText()),
        Job.entries.associateWith { RuleSet.parseJob(File(dir, "jobs/${it.key}.json").readText()) }
    )
    private val skills = SkillRules.parse(File(dir, "skills.json").readText())

    private fun mon(
        types: List<String>, attack: Int = 100, level: Int = 50, ability: String = "none", moves: Set<String> = emptySet(),
        nature: String = "hardy", width: Double = 0.8, height: Double = 1.0, canFly: Boolean = false,
    ) = MonInput(
        "testmon", types,
        mapOf("hp" to 120, "attack" to attack, "defence" to 100, "special_attack" to 100, "special_defence" to 100, "speed" to 100),
        level, ability, moves, nature, width, height, canFly, breathesUnderwater = false, fireImmune = false
    )

    private fun mining(m: MonInput) = Suitability.profile(m, rules).score(Job.MINING).mult

    @Test
    fun `every shipped job file parses and has real numbers`() {
        for (job in Job.entries) {
            val r = rules.job(job)
            assertTrue(r.baseTicks > 0, "${job.key} base ticks")
            assertTrue(r.types.isNotEmpty(), "${job.key} has no type bonuses")
            assertTrue(r.primaryStats.isNotEmpty())
        }
        assertEquals(5, rules.global.styles.size - 1, "five nature styles plus balanced")
    }

    @Test
    fun `a matching type is faster than a neutral one and a bad one is slower`() {
        val neutral = mining(mon(listOf("normal")))
        assertTrue(mining(mon(listOf("ground"))) > neutral)
        assertTrue(mining(mon(listOf("flying"))) < neutral)
    }

    @Test
    fun `dual types - best bonus in full, second at half`() {
        val neutral = mining(mon(listOf("normal")))
        // ground .30 + rock .25 * 0.5 = 1.425
        assertEquals(1.425, mining(mon(listOf("rock", "ground"))) / neutral, 1e-9)
        // order of the two types must not matter
        assertEquals(mining(mon(listOf("ground", "rock"))), mining(mon(listOf("rock", "ground"))), 1e-9)
        // ground .30 + flying -.20 * 0.5 = 1.20
        assertEquals(1.20, mining(mon(listOf("ground", "flying"))) / neutral, 1e-9)
    }

    @Test
    fun `moves are capped and abilities stack on top`() {
        val neutral = mining(mon(listOf("normal")))
        val allMoves = mining(mon(listOf("normal"), moves = setOf("dig", "rocksmash", "drillrun", "earthquake")))
        assertEquals(1.0 + rules.global.moveCap, allMoves / neutral, 1e-9)
        assertEquals(1.20, mining(mon(listOf("normal"), ability = "sandrush")) / neutral, 1e-9)
    }

    @Test
    fun `the final multiplier never leaves its clamp`() {
        val g = rules.global
        val monster = mon(listOf("ground", "rock"), attack = 999, ability = "hugepower", moves = setOf("dig", "rocksmash"), nature = "jolly", width = 3.0)
        assertEquals(g.finalMax, mining(monster), 1e-9)
        assertTrue(mining(mon(listOf("flying"), attack = 1)) >= g.finalMin)
    }

    @Test
    fun `smelting needs fire in some form`() {
        assertFalse(Suitability.profile(mon(listOf("water")), rules).score(Job.SMELTING).able)
        assertTrue(Suitability.profile(mon(listOf("fire")), rules).score(Job.SMELTING).able)
        assertTrue(Suitability.profile(mon(listOf("rock"), ability = "flamebody"), rules).score(Job.SMELTING).able)
        assertTrue(Suitability.profile(mon(listOf("dragon"), moves = setOf("flamethrower")), rules).score(Job.SMELTING).able)
        // every other job is open to everyone
        val anyone = Suitability.profile(mon(listOf("water")), rules)
        assertTrue(Job.entries.filter { it != Job.SMELTING }.all { anyone.score(it).able })
    }

    @Test
    fun `mining tier comes from level and type, not tools`() {
        fun tier(level: Int, type: String) = Suitability.profile(mon(listOf(type), level = level), rules).miningTier
        assertEquals(0, tier(5, "normal"))
        assertEquals(1, tier(10, "normal"))
        assertEquals(2, tier(25, "normal"))
        assertEquals(3, tier(45, "normal"))
        assertEquals(1, tier(5, "rock"), "rock digs one tier above its level")
        assertEquals(3, tier(100, "steel"), "tier is capped")
    }

    @Test
    fun `level perks unlock in stages and the strongest stage counts`() {
        fun at(level: Int) = Suitability.profile(mon(listOf("grass"), level = level), rules)
        // Lumber: Leaf Sweep first, then Timber in three stages.
        assertEquals(0.0, at(9).unlock(Job.LUMBER, "leaf_sweep"))
        assertEquals(1.0, at(10).unlock(Job.LUMBER, "leaf_sweep"))
        assertEquals(1.0, at(19).unlock(Job.LUMBER, "timber", 1.0), "no Timber before level 20")
        assertEquals(20, at(19).nextUnlock[Job.LUMBER]!!.level)
        assertEquals(4.0, at(20).unlock(Job.LUMBER, "timber"))
        assertEquals(12.0, at(35).unlock(Job.LUMBER, "timber"))
        assertEquals(256.0, at(50).unlock(Job.LUMBER, "timber"), "whole trees")
        assertNull(at(100).nextUnlock[Job.LUMBER], "nothing left to unlock")
        // Every job has a ladder, and reaching more levels never loses a stage.
        for (job in Job.entries) assertNotNull(rules.global.unlocks[job.key], "${job.key} has no level perks")
        assertTrue(at(100).stages > at(30).stages && at(30).stages > at(1).stages)
        assertEquals(0, at(1).stages)
    }

    @Test
    fun `capabilities, size and nature style`() {
        val levitator = Suitability.profile(mon(listOf("ghost"), ability = "levitate"), rules)
        assertTrue(Capability.FLIGHT in levitator.capabilities)
        assertTrue(Capability.UNDERWATER in Suitability.profile(mon(listOf("water")), rules).capabilities)
        assertFalse(Capability.FLIGHT in Suitability.profile(mon(listOf("flying")), rules).capabilities, "Flying type alone is not flight (Dodrio)")

        val big = Suitability.profile(mon(listOf("normal"), width = 2.0), rules)
        val normal = Suitability.profile(mon(listOf("normal")), rules)
        assertEquals(SizeClass.LARGE, big.size)
        assertEquals(normal.carry * 2, big.carry)
        assertEquals("eager", Suitability.profile(mon(listOf("normal"), nature = "jolly"), rules).styleId)
        assertEquals("balanced", normal.styleId)
        assertTrue("truant" in Suitability.profile(mon(listOf("normal"), ability = "truant"), rules).quirks)
    }

    @Test
    fun `stars follow the multiplier`() {
        val weak = Suitability.profile(mon(listOf("flying"), attack = 10), rules).score(Job.MINING)
        val strong = Suitability.profile(mon(listOf("ground", "rock"), attack = 250, moves = setOf("dig")), rules).score(Job.MINING)
        assertEquals(1, weak.stars)
        assertEquals(5, strong.stars)
    }

    @Test
    fun `action time scales with speed and never reaches zero`() {
        val job = rules.job(Job.MINING)
        assertEquals(job.baseTicks, Suitability.actionTicks(job, 1.0, 1.0))
        assertEquals(job.baseTicks / 2, Suitability.actionTicks(job, 2.0, 1.0))
        assertEquals(2, Suitability.actionTicks(job, 3.0, 100.0))
        assertTrue(Suitability.care(255, true, rules.global) > Suitability.care(0, false, rules.global))
    }

    @Test
    fun `skill tree - every node is sane and its requirements exist`() {
        assertTrue(skills.nodes.size >= 30)
        assertEquals(skills.nodes.size, skills.nodes.map { it.id }.toSet().size, "duplicate node id")
        for (n in skills.nodes) {
            assertTrue(n.max >= 1 && n.baseCost > 0, n.id)
            for ((req, lvl) in n.requires) {
                val target = skills.node(req)
                assertNotNull(target, "${n.id} requires missing node $req")
                assertTrue(lvl <= target!!.max, "${n.id} requires $req at an impossible level")
            }
        }
        // No two nodes of a branch share a cell in the tree screen.
        assertEquals(skills.nodes.size, skills.nodes.map { Triple(it.branch, it.x, it.y) }.toSet().size, "two nodes overlap in the tree")
    }

    @Test
    fun `skill tree - you start with one chunk, one worker, one site`() {
        val tree = SkillTree(skills, "normal")
        val fresh = SkillState()
        assertEquals(8.0, tree.value(fresh, "work_area"), "radius 8 = one 16x16 chunk")
        assertEquals(1.0, tree.value(fresh, "crew_size"))
        assertEquals(1.0, tree.value(fresh, "job_sites"))
        assertFalse(tree.unlocked(fresh, "tree_feller"))
    }

    @Test
    fun `skill tree - buying, requirements, costs and modes`() {
        val tree = SkillTree(skills, "normal")
        val s = SkillState()
        assertEquals("xp:40", tree.blocked(s, "work_area"))
        assertFalse(tree.buy(s, "work_area"))

        s.xp = 10_000
        assertEquals("requires:speed_lumber:2", tree.blocked(s, "tree_feller"))
        assertTrue(tree.buy(s, "speed_lumber")); assertTrue(tree.buy(s, "speed_lumber"))
        assertTrue(tree.buy(s, "tree_feller"))
        assertEquals("maxed", tree.blocked(s, "tree_feller"))
        assertNull(tree.cost(s, "tree_feller"))

        val before = s.xp
        val cost = tree.cost(s, "work_area")!!
        assertTrue(tree.buy(s, "work_area"))
        assertEquals(before - cost, s.xp)
        assertEquals(16.0, tree.value(s, "work_area"))
        assertTrue(tree.cost(s, "work_area")!! > cost, "each level costs more")

        // Relaxed starts ahead and pays less; Hardcore pays more.
        val relaxed = SkillTree(skills, "relaxed"); val hardcore = SkillTree(skills, "hardcore")
        val fresh = SkillState()
        assertEquals(16.0, relaxed.value(fresh, "work_area"))
        assertTrue(relaxed.cost(fresh, "endurance")!! < tree.cost(fresh, "endurance")!!)
        assertTrue(hardcore.cost(fresh, "endurance")!! > tree.cost(fresh, "endurance")!!)
        // An unknown mode falls back to normal instead of crashing.
        assertEquals(8.0, SkillTree(skills, "nonsense").value(fresh, "work_area"))
    }

    @Test
    fun `skill tree - levels never exceed the node maximum`() {
        val tree = SkillTree(skills, "relaxed")
        val s = SkillState().apply { xp = SkillTree.MAX_XP }
        while (tree.buy(s, "crew_size")) { /* buy until refused */ }
        assertEquals(skills.node("crew_size")!!.max, tree.level(s, "crew_size"))
        tree.earn(s, 1e12)
        assertEquals(SkillTree.MAX_XP, s.xp, "XP is capped so it can never overflow")
    }
}
