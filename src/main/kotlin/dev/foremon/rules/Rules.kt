package dev.foremon.rules

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder

/**
 * The "who is good at which job" rules. Pure data and maths: nothing in this package touches
 * Minecraft or Cobblemon, so it can be unit tested and reloaded from data packs freely.
 *
 * Every number comes from JSON under data/<namespace>/foremon/ so server owners can retune
 * the mod with a data pack. The defaults ship in this jar under the `foremon` namespace.
 */
enum class Job(val key: String) {
    LUMBER("lumber"), MINING("mining"), SORTING("sorting"), HAULING("hauling"), FARMING("farming"),
    SMELTING("smelting"), BUILDING("building"), DEMOLITION("demolition"), GUARD("guard"), CRAFTING("crafting");

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun of(key: String): Job? = byKey[key]
    }
}

/** Things a Pokemon is physically able to do, which gate where it can work. */
enum class Capability { FLIGHT, UNDERWATER, LAVA }

enum class SizeClass(val key: String) { TINY("tiny"), NORMAL("normal"), LARGE("large") }

/** One job's rules. Bonuses are fractions: 0.30 means +30% speed, -0.20 means 20% slower. */
class JobRule {
    /** The best of these stats counts as the main stat. */
    var primaryStats: List<String> = listOf("attack")
    var secondaryStat: String = "speed"
    /** Ticks one action takes at a multiplier of 1.0 (20 ticks = 1 second). */
    var baseTicks: Int = 30
    var types: Map<String, Double> = emptyMap()
    var abilities: Map<String, Double> = emptyMap()
    var moves: Map<String, Double> = emptyMap()
    var size: Map<String, Double> = emptyMap()
    /** Per-species adjustment, keyed by species id such as "machamp". */
    var species: Map<String, Double> = emptyMap()
    /** If any list here is non-empty, the Pokemon must match at least one entry to do the job at all. */
    var requires: Requirement = Requirement()
}

class Requirement {
    var types: List<String> = emptyList()
    var abilities: List<String> = emptyList()
    var moves: List<String> = emptyList()

    val isOpen get() = types.isEmpty() && abilities.isEmpty() && moves.isEmpty()
}

/** One stage of a level perk. */
class Unlock {
    var level: Int = 1
    var id: String = ""
    var value: Double = 1.0
}

/** How a nature colours the way a Pokemon works. */
class Style {
    var speed: Double = 1.0
    var staminaDrain: Double = 1.0
    var stamina: Double = 1.0
    var carry: Double = 1.0
    var xp: Double = 1.0
    var bonusYield: Double = 0.0
}

class GlobalRules {
    var statBase = 0.5
    var statPrimaryWeight = 0.7
    var statSecondaryWeight = 0.3
    var statDivisor = 200.0
    var statMin = 0.5
    var statMax = 2.0
    var typeMin = 0.7
    var typeMax = 1.6
    /** A second matching type counts for this share of its bonus. */
    var secondTypeShare = 0.5
    var moveCap = 0.35
    var finalMin = 0.25
    var finalMax = 3.0
    var friendshipMin = 0.90
    var friendshipSpan = 0.20
    var fedBonus = 0.15

    var largeWidth = 1.5
    var largeHeight = 3.0
    var tinyWidth = 0.5
    var tinyHeight = 0.6

    var carryBase = 8.0
    var carryPerHp = 1.0 / 3.0
    var carryMin = 4
    var carryMax = 256
    var carryLarge = 2.0
    var carryTiny = 0.5
    var reachLarge = 2

    /** Level needed for each mining tier, starting at tier 0. Tier 1 = stone tools, 2 = iron, 3 = diamond. */
    var miningTierLevels: List<Int> = listOf(1, 10, 25, 45)
    /** These types dig one tier above their level. */
    var miningTierTypes: List<String> = listOf("rock", "ground", "steel")

    /**
     * Level perks: things a Pokemon unlocks for a job when it reaches a level. Keyed by job, each
     * entry names a perk the code knows (timber, leaf_sweep, vein, sweep, batch, load, multi) and
     * how strong it is at that level. The strongest stage reached counts.
     */
    var unlocks: Map<String, List<Unlock>> = emptyMap()

    /** Multiplier thresholds for 2, 3, 4 and 5 stars in the Work Dex. */
    var starThresholds: List<Double> = listOf(0.75, 1.0, 1.35, 1.8)

    var capabilityAbilities: Map<String, String> = emptyMap()
    var capabilityMoves: Map<String, String> = emptyMap()
    var capabilityTypes: Map<String, String> = emptyMap()

    /** Nature id -> style id. Natures not listed use "balanced". */
    var natures: Map<String, String> = emptyMap()
    var styles: Map<String, Style> = emptyMap()

    /** Ability id -> named special effect handled in code (vacuum, double_harvest, ...). */
    var perks: Map<String, String> = emptyMap()
    /** Move id -> the same kind of named special effect, for field moves such as Cut. */
    var movePerks: Map<String, String> = emptyMap()
    /** Ability id -> named drawback handled in code (truant, slow_start). */
    var quirks: Map<String, String> = emptyMap()

    fun style(nature: String): Style = styles[natures[nature] ?: "balanced"] ?: DEFAULT_STYLE
    fun styleId(nature: String): String = natures[nature] ?: "balanced"

    companion object {
        private val DEFAULT_STYLE = Style()
    }
}

class RuleSet(val global: GlobalRules, val jobs: Map<Job, JobRule>) {
    fun job(job: Job): JobRule = jobs[job] ?: EMPTY_JOB

    companion object {
        private val EMPTY_JOB = JobRule()
        private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()

        fun parseGlobal(json: String): GlobalRules = gson.fromJson(json, GlobalRules::class.java) ?: GlobalRules()
        fun parseJob(json: String): JobRule = gson.fromJson(json, JobRule::class.java) ?: JobRule()
    }
}
