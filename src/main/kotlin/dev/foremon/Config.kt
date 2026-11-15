package dev.foremon

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * Server settings, saved as config/foremon.json.
 *
 * The top-level fields are the simple switches a new server owner needs. Everything under
 * "advanced" is for owners who want to tune the mod; the defaults there are safe to ignore.
 * Deeper rules (which types suit which job, the skill tree) live in data packs, see data/foremon/foremon/.
 */
class ConfigData {
    /** relaxed, normal or hardcore. Sets where the skill tree starts, what it costs and how demanding upkeep is. */
    var mode = "normal"
    var workersMayBreakBlocks = true
    var workersMayPlaceBlocks = true
    /** Allows the Night Shift upgrade to work at all. Off: workers always stop when their owner logs out. */
    var allowWorkWhileOwnerOffline = false
    var maxWorkersPerPlayer = 12
    var maxJobSitesPerPlayer = 6
    /** Stamina, rest and food. Off: workers never tire. */
    var upkeep = true
    var advanced = Advanced()

    class Advanced {
        /** Block checks the whole mod may spend per tick looking for work. The main lag control. */
        var scanBudgetPerTick = 800
        /** Upper bound on block checks one job site may use per tick. */
        var scanPerSitePerTick = 200
        var rescanIntervalTicks = 200
        var maxWorkersPerChunk = 8
        var stuckTicks = 100
        var teleportWhenStuck = true
        var teleportCooldownTicks = 400
        var baseStamina = 150
        var restTicks = 600
        var staminaPerBerry = 60
        /** Work XP (skill tree currency) per finished action. */
        var workXpPerAction = 1.0
        /** Every this many actions the Pokemon gains battle EXP and friendship. 0 turns it off. */
        var pokemonRewardEveryActions = 25
        var pokemonExpPerReward = 12
        /** Job mastery: XP needed for Apprentice, Skilled, Expert, Master. */
        var masteryLevels = listOf(40, 160, 480, 1200)
        var masterySpeedPerLevel = 0.06
        var maxMiningVolume = 32 * 32 * 64
        var maxPlanBlocks = 200_000
        /** Dimensions where no job site may work, e.g. "minecraft:the_end". */
        var blockedDimensions = listOf<String>()
        var allowLegendaries = true
        /** Players within this many blocks receive work effects. */
        var effectRange = 48
        /** Writes every block a worker breaks or places to logs/foremon-audit.log. */
        var auditLog = false
        var relaxedUpkeepFactor = 0.5
        var hardcoreUpkeepFactor = 1.5
    }
}

object Config {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file get() = FabricLoader.getInstance().configDir.resolve("foremon.json")
    var d = ConfigData()
        private set

    val a get() = d.advanced

    /** How hard stamina drains in the current mode. */
    val upkeepFactor: Double
        get() = when (d.mode) { "relaxed" -> a.relaxedUpkeepFactor; "hardcore" -> a.hardcoreUpkeepFactor; else -> 1.0 }

    fun load() {
        try {
            if (Files.exists(file)) d = gson.fromJson(Files.readString(file), ConfigData::class.java) ?: ConfigData()
        } catch (e: Exception) {
            // A broken file must not stop the server or silently wipe the owner's settings.
            Foremon.LOG.error("config/foremon.json could not be read; using defaults for this run and leaving the file alone", e)
            d = ConfigData()
            return
        }
        save()
    }

    fun save() {
        try { Files.writeString(file, gson.toJson(d)) } catch (e: Exception) { Foremon.LOG.error("Could not write config/foremon.json", e) }
    }

    /** Sets one simple switch by name. Returns false for an unknown key or a bad value. Used by the admin command and menu. */
    fun set(key: String, value: String): Boolean {
        try {
            when (key) {
                "mode" -> if (value in listOf("relaxed", "normal", "hardcore")) d.mode = value else return false
                "workersMayBreakBlocks" -> d.workersMayBreakBlocks = value.toBooleanStrict()
                "workersMayPlaceBlocks" -> d.workersMayPlaceBlocks = value.toBooleanStrict()
                "allowWorkWhileOwnerOffline" -> d.allowWorkWhileOwnerOffline = value.toBooleanStrict()
                "upkeep" -> d.upkeep = value.toBooleanStrict()
                "maxWorkersPerPlayer" -> d.maxWorkersPerPlayer = value.toInt().coerceIn(0, 200)
                "maxJobSitesPerPlayer" -> d.maxJobSitesPerPlayer = value.toInt().coerceIn(0, 200)
                else -> return false
            }
        } catch (e: IllegalArgumentException) {
            return false
        }
        save()
        return true
    }

    val simpleKeys = listOf("mode", "workersMayBreakBlocks", "workersMayPlaceBlocks", "allowWorkWhileOwnerOffline", "upkeep", "maxWorkersPerPlayer", "maxJobSitesPerPlayer")

    fun get(key: String): String = when (key) {
        "mode" -> d.mode
        "workersMayBreakBlocks" -> d.workersMayBreakBlocks.toString()
        "workersMayPlaceBlocks" -> d.workersMayPlaceBlocks.toString()
        "allowWorkWhileOwnerOffline" -> d.allowWorkWhileOwnerOffline.toString()
        "upkeep" -> d.upkeep.toString()
        "maxWorkersPerPlayer" -> d.maxWorkersPerPlayer.toString()
        "maxJobSitesPerPlayer" -> d.maxJobSitesPerPlayer.toString()
        else -> "?"
    }
}
