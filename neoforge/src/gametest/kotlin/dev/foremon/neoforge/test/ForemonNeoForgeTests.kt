package dev.foremon.neoforge.test

import dev.foremon.test.ForemonGameTest
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestGenerator
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.StructureUtils
import net.minecraft.gametest.framework.TestFunction
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.event.RegisterGameTestsEvent
import java.lang.reflect.InvocationTargetException

@Mod("foremon_gametest")
class ForemonNeoForgeTestMod(modBus: IEventBus) {
    init {
        modBus.addListener(RegisterGameTestsEvent::class.java) { it.register(ForemonNeoForgeTests::class.java) }
    }
}

/**
 * The shared in-game tests (../src/gametest), run on NeoForge's game test server. NeoForge would prefix their
 * test area name ("fabric-gametest-api-v1:empty") with a namespace and the class name, so they are built here
 * with the name exactly as written; this build carries that empty area in its test resources.
 */
class ForemonNeoForgeTests {
    @GameTestGenerator
    fun tests(): Collection<TestFunction> = ForemonGameTest::class.java.declaredMethods.mapNotNull { m ->
        val t = m.getAnnotation(GameTest::class.java) ?: return@mapNotNull null
        TestFunction(
            t.batch, "foremongametest." + m.name.lowercase(), t.template, StructureUtils.getRotationForRotationSteps(t.rotationSteps),
            t.timeoutTicks, t.setupTicks, t.required, t.manualOnly, t.attempts, t.requiredSuccesses, t.skyAccess,
        ) { helper: GameTestHelper ->
            try { m.invoke(ForemonGameTest(), helper) }
            catch (e: InvocationTargetException) { throw e.cause as? RuntimeException ?: RuntimeException(e.cause) }
        }
    }
}
