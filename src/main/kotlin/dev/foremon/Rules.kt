package dev.foremon

import dev.foremon.rules.GlobalRules
import dev.foremon.rules.Job
import dev.foremon.rules.RuleSet
import dev.foremon.rules.SkillRules
import dev.foremon.rules.SkillState
import dev.foremon.rules.SkillTree
import dev.foremon.world.PlayerStore
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.resources.ResourceManager
import java.util.UUID

/** The live rule set, loaded from data packs (data/<namespace>/foremon/...) and replaced on /reload. */
object Rules {
    @Volatile var set = RuleSet(GlobalRules(), emptyMap())
        private set
    @Volatile var skills = SkillRules()
        private set
    /** Bumped on every reload so cached worker profiles know to rebuild. */
    @Volatile var version = 0
        private set

    private var tree: SkillTree? = null

    fun tree(): SkillTree {
        val t = tree
        if (t != null && t.rules === skills && t.modeName == Config.d.mode) return t
        return SkillTree(skills, Config.d.mode).also { tree = it }
    }

    /** A player's value for a skill node, e.g. skill(server, id, "work_area"). */
    fun skill(server: MinecraftServer, player: UUID, node: String): Double = tree().value(PlayerStore.of(server).peek(player), node)
    fun skillLevel(server: MinecraftServer, player: UUID, node: String): Int = tree().level(PlayerStore.of(server).peek(player), node)
    fun has(server: MinecraftServer, player: UUID, node: String): Boolean = tree().unlocked(PlayerStore.of(server).peek(player), node)
    fun state(server: MinecraftServer, player: UUID): SkillState = PlayerStore.of(server).state(player)

    /** Called by each loader's server data reload (world start and /reload). */
    fun reload(manager: ResourceManager) {
        fun read(path: String): String? = manager.getResource(Foremon.id(path)).map { r -> r.open().use { String(it.readAllBytes()) } }.orElse(null)
        // A broken data pack file falls back to defaults for that file and says so, instead of failing the whole reload.
        fun <T> safe(path: String, fallback: T, parse: (String) -> T): T = try {
            read(path)?.let(parse) ?: fallback.also { Foremon.LOG.warn("Missing {}; using built-in defaults", path) }
        } catch (e: Exception) {
            Foremon.LOG.error("Could not read $path; using built-in defaults", e); fallback
        }
        set = RuleSet(
            safe("foremon/rules.json", GlobalRules(), RuleSet::parseGlobal),
            Job.entries.associateWith { safe("foremon/jobs/${it.key}.json", dev.foremon.rules.JobRule(), RuleSet::parseJob) }
        )
        skills = safe("foremon/skills.json", SkillRules(), SkillRules::parse)
        tree = null
        version++
        Foremon.LOG.info("Loaded {} job rule sets and {} skill nodes", set.jobs.size, skills.nodes.size)
    }
}
