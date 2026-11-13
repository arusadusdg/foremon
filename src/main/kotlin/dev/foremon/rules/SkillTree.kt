package dev.foremon.rules

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The player skill tree. Pure data and maths, loaded from data/<namespace>/foremon/skills.json,
 * so an admin can change which nodes exist, what they cost, where each mode starts and how far
 * each node goes without touching code. The node ids are what the code looks up.
 */
class SkillNode {
    var id: String = ""
    var branch: String = "foreman"
    /** Highest level reachable. 0 switches the node off. */
    var max: Int = 1
    var baseCost: Int = 50
    /** Each level costs this many times the previous one. */
    var costGrowth: Double = 1.6
    /** Value at level 0. */
    var base: Double = 0.0
    /** Added per level. For on/off nodes: 1.0 at level 1. */
    var perLevel: Double = 1.0
    /** Other nodes that must be at least this level first. */
    var requires: Map<String, Int> = emptyMap()
    /** Item id shown as the node's icon. */
    var icon: String = "minecraft:paper"
    /** Position in the tree screen, in grid cells. */
    var x: Int = 0
    var y: Int = 0
}

class SkillMode {
    var costMultiplier: Double = 1.0
    var xpMultiplier: Double = 1.0
    /** Levels every player has from the start in this mode, by node id. */
    var start: Map<String, Int> = emptyMap()
}

class SkillRules {
    var modes: Map<String, SkillMode> = mapOf("normal" to SkillMode())
    var nodes: List<SkillNode> = emptyList()

    @Transient private var index: Map<String, SkillNode>? = null

    fun node(id: String): SkillNode? {
        val i = index ?: nodes.associateBy { it.id }.also { index = it }
        return i[id]
    }

    fun mode(name: String): SkillMode = modes[name] ?: modes["normal"] ?: FALLBACK_MODE

    companion object {
        private val FALLBACK_MODE = SkillMode()
        private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        fun parse(json: String): SkillRules = gson.fromJson(json, SkillRules::class.java) ?: SkillRules()
    }
}

/** One player's progress: unspent Work XP and the levels they have bought on top of the mode's start. */
class SkillState {
    var xp: Int = 0
    val bought = HashMap<String, Int>()
}

class SkillTree(val rules: SkillRules, val modeName: String) {
    private val mode = rules.mode(modeName)

    fun level(state: SkillState?, id: String): Int {
        val node = rules.node(id) ?: return 0
        return ((mode.start[id] ?: 0) + (state?.bought?.get(id) ?: 0)).coerceIn(0, node.max)
    }

    /** The node's effect at the player's current level: base + perLevel x level. Unknown nodes give [fallback]. */
    fun value(state: SkillState?, id: String, fallback: Double = 0.0): Double {
        val node = rules.node(id) ?: return fallback
        return node.base + node.perLevel * level(state, id)
    }

    fun unlocked(state: SkillState?, id: String) = level(state, id) > 0

    /** Cost of the next level, or null when the node is already maxed or does not exist. */
    fun cost(state: SkillState?, id: String): Int? {
        val node = rules.node(id) ?: return null
        val lvl = level(state, id)
        if (lvl >= node.max) return null
        return (node.baseCost * node.costGrowth.pow(lvl) * mode.costMultiplier).roundToInt().coerceAtLeast(1)
    }

    /** Null if the player may buy the next level, otherwise the reason they may not. */
    fun blocked(state: SkillState, id: String): String? {
        val node = rules.node(id) ?: return "unknown"
        val cost = cost(state, id) ?: return "maxed"
        for ((req, lvl) in node.requires) if (level(state, req) < lvl) return "requires:$req:$lvl"
        if (state.xp < cost) return "xp:$cost"
        return null
    }

    fun buy(state: SkillState, id: String): Boolean {
        if (blocked(state, id) != null) return false
        state.xp -= cost(state, id)!!
        state.bought.merge(id, 1, Int::plus)
        return true
    }

    fun earn(state: SkillState, amount: Double) {
        state.xp = (state.xp + (amount * mode.xpMultiplier)).roundToInt().coerceAtMost(MAX_XP)
    }

    companion object {
        const val MAX_XP = 100_000_000
    }
}
