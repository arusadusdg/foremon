package dev.foremon.rules

/** Everything the rules need to know about one Pokemon, copied out of Cobblemon once at assignment. */
class MonInput(
    val species: String,
    val types: List<String>,
    /** Computed stats (level, IVs, EVs and nature already applied), keyed hp/attack/defence/special_attack/special_defence/speed. */
    val stats: Map<String, Int>,
    val level: Int,
    val ability: String,
    /** Known moves: active and benched, so nobody has to ruin a battle moveset to get a work perk. */
    val moves: Set<String>,
    val nature: String,
    val width: Double,
    val height: Double,
    val canFly: Boolean,
    val breathesUnderwater: Boolean,
    val fireImmune: Boolean,
) {
    fun stat(key: String) = stats[key] ?: 0
}

/** One line of the "why is this Pokemon good at this" breakdown shown in the menu. */
class Factor(val id: String, val mult: Double)

class JobScore(
    /** Speed multiplier before friendship, food and the skill tree. 0 means the Pokemon cannot do this job. */
    val mult: Double,
    val factors: List<Factor>,
    val stars: Int,
) {
    val able get() = mult > 0.0
}

/** Cached per worker. Job ticks only multiply these numbers; nothing is recomputed while working. */
class WorkerProfile(
    val scores: Map<Job, JobScore>,
    val carry: Int,
    val reachBonus: Int,
    val capabilities: Set<Capability>,
    val miningTier: Int,
    /** Level perks this Pokemon has reached, per job: perk id to its strength. */
    val unlocks: Map<Job, Map<String, Double>>,
    /** The next perk stage it has not reached yet, per job. */
    val nextUnlock: Map<Job, Unlock>,
    /** How many perk stages it has reached in total; used to notice a new one after a level-up. */
    val stages: Int,
    val size: SizeClass,
    val styleId: String,
    val style: Style,
    val perks: Set<String>,
    val quirks: Set<String>,
) {
    fun score(job: Job): JobScore = scores[job] ?: NONE

    /** Strength of a level perk for a job, or [fallback] if the Pokemon has not unlocked it. */
    fun unlock(job: Job, id: String, fallback: Double = 0.0): Double = unlocks[job]?.get(id) ?: fallback

    companion object {
        private val NONE = JobScore(0.0, emptyList(), 0)
    }
}

object Suitability {
    fun profile(mon: MonInput, rules: RuleSet): WorkerProfile {
        val g = rules.global
        val size = sizeOf(mon, g)
        val style = g.style(mon.nature)
        val styleId = g.styleId(mon.nature)

        val caps = HashSet<Capability>()
        if (mon.canFly) caps.add(Capability.FLIGHT)
        if (mon.breathesUnderwater) caps.add(Capability.UNDERWATER)
        if (mon.fireImmune) caps.add(Capability.LAVA)
        fun grant(name: String?) { runCatching { Capability.valueOf(name?.uppercase() ?: return) }.onSuccess { caps.add(it) } }
        grant(g.capabilityAbilities[mon.ability])
        mon.moves.forEach { grant(g.capabilityMoves[it]) }
        mon.types.forEach { grant(g.capabilityTypes[it]) }

        var carry = g.carryBase + mon.stat("hp") * g.carryPerHp
        if (size == SizeClass.LARGE) carry *= g.carryLarge
        if (size == SizeClass.TINY) carry *= g.carryTiny
        carry *= style.carry

        var tier = g.miningTierLevels.count { mon.level >= it } - 1
        if (mon.types.any { it in g.miningTierTypes }) tier++
        tier = tier.coerceIn(0, g.miningTierLevels.size - 1)

        val unlocked = HashMap<Job, Map<String, Double>>()
        val next = HashMap<Job, Unlock>()
        var stages = 0
        for (job in Job.entries) {
            val list = g.unlocks[job.key] ?: continue
            val got = HashMap<String, Double>()
            for (u in list) if (mon.level >= u.level) { got.merge(u.id, u.value, ::maxOf); stages++ }
            if (got.isNotEmpty()) unlocked[job] = got
            list.filter { mon.level < it.level }.minByOrNull { it.level }?.let { next[job] = it }
        }

        return WorkerProfile(
            scores = Job.entries.associateWith { score(mon, rules.job(it), g, size, styleId, style) },
            carry = carry.toInt().coerceIn(g.carryMin, g.carryMax),
            reachBonus = if (size == SizeClass.LARGE) g.reachLarge else 0,
            capabilities = caps,
            miningTier = tier,
            unlocks = unlocked,
            nextUnlock = next,
            stages = stages,
            size = size,
            styleId = styleId,
            style = style,
            perks = HashSet<String>().apply { g.perks[mon.ability]?.let(::add); mon.moves.forEach { m -> g.movePerks[m]?.let(::add) } },
            quirks = setOfNotNull(g.quirks[mon.ability]),
        )
    }

    fun sizeOf(mon: MonInput, g: GlobalRules): SizeClass = when {
        mon.width > g.largeWidth || mon.height > g.largeHeight -> SizeClass.LARGE
        mon.width <= g.tinyWidth && mon.height <= g.tinyHeight -> SizeClass.TINY
        else -> SizeClass.NORMAL
    }

    private fun score(mon: MonInput, job: JobRule, g: GlobalRules, size: SizeClass, styleId: String, style: Style): JobScore {
        val req = job.requires
        if (!req.isOpen && mon.types.none { it in req.types } && mon.ability !in req.abilities && mon.moves.none { it in req.moves })
            return JobScore(0.0, listOf(Factor("requirement", 0.0)), 0)

        val factors = ArrayList<Factor>(8)
        val primary = job.primaryStats.maxOfOrNull { mon.stat(it) } ?: 0
        val stat = (g.statBase + (g.statPrimaryWeight * primary + g.statSecondaryWeight * mon.stat(job.secondaryStat)) / g.statDivisor)
            .coerceIn(g.statMin, g.statMax)
        factors.add(Factor("stat", stat))
        var mult = stat

        // Dual types: the better bonus counts in full, the second one for a share of it.
        val typeBonuses = mon.types.mapNotNull { t -> job.types[t]?.let { t to it } }.sortedByDescending { it.second }
        if (typeBonuses.isNotEmpty()) {
            var sum = 0.0
            typeBonuses.forEachIndexed { i, (_, b) -> sum += if (i == 0) b else b * g.secondTypeShare }
            val type = (1.0 + sum).coerceIn(g.typeMin, g.typeMax)
            factors.add(Factor("type:" + typeBonuses.joinToString("+") { it.first }, type))
            mult *= type
        }

        job.abilities[mon.ability]?.let { factors.add(Factor("ability:${mon.ability}", 1.0 + it)); mult *= 1.0 + it }

        var moveSum = 0.0
        var bestMove: String? = null
        var bestMoveBonus = 0.0
        for (m in mon.moves) {
            val b = job.moves[m] ?: continue
            moveSum += b
            if (b > bestMoveBonus) { bestMoveBonus = b; bestMove = m }
        }
        if (moveSum != 0.0) {
            val moves = 1.0 + moveSum.coerceAtMost(g.moveCap)
            factors.add(Factor("move:$bestMove", moves))
            mult *= moves
        }

        job.size[size.key]?.let { factors.add(Factor("size:${size.key}", 1.0 + it)); mult *= 1.0 + it }
        job.species[mon.species]?.let { factors.add(Factor("species", 1.0 + it)); mult *= 1.0 + it }
        if (style.speed != 1.0) { factors.add(Factor("nature:$styleId", style.speed)); mult *= style.speed }

        mult = mult.coerceIn(g.finalMin, g.finalMax)
        return JobScore(mult, factors, 1 + g.starThresholds.count { mult >= it })
    }

    /** Friendship and food, applied at work time because they change while the Pokemon works. */
    fun care(friendship: Int, fed: Boolean, g: GlobalRules): Double =
        (g.friendshipMin + g.friendshipSpan * friendship.coerceIn(0, 255) / 255.0) * if (fed) 1.0 + g.fedBonus else 1.0

    /** Ticks for one action. [extra] carries skill tree, mastery and environment multipliers. */
    fun actionTicks(job: JobRule, mult: Double, extra: Double, hardness: Double = 1.0): Int =
        (job.baseTicks * hardness / (mult * extra).coerceAtLeast(0.05)).toInt().coerceAtLeast(2)
}
