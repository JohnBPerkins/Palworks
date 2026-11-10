package dev.palworks.gametest.neoforge;

import dev.palworks.gametest.Templates;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.JUnitLikeTestReporter;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Registers the shared in-game tests (Templates.TEST_CLASSES) on NeoForge, as Fabric's GameTest
 * API does: each {@code @GameTest} method becomes a test, using its template exactly as written
 * ("palworks_gametest:arena"). NeoForge's own registration would instead build the structure name
 * from a NeoForge-only annotation and the class name, which the shared classes can't carry.
 *
 * <p>Writes a JUnit-style report to the file in {@code -Dpalworks.gametest.report} (see
 * neoforge/build.gradle), like Fabric's {@code fabric-api.gametest.report-file}.
 */
@Mod("palworks_gametest")
public class PalworksGameTestsNeoForge {

    public PalworksGameTestsNeoForge(IEventBus modBus) {
        modBus.addListener(RegisterGameTestsEvent.class, event -> registerAll());

        String report = System.getProperty("palworks.gametest.report");
        if (report != null) {
            try {
                File file = new File(report);
                file.getParentFile().mkdirs();
                GlobalTestReporter.replaceWith(new JUnitLikeTestReporter(file));
            } catch (Exception e) {
                throw new IllegalStateException("couldn't set up the GameTest report " + report, e);
            }
        }
    }

    private static void registerAll() {
        for (Class<?> testClass : Templates.TEST_CLASSES) {
            for (Method method : testClass.getDeclaredMethods()) {
                GameTest test = method.getAnnotation(GameTest.class);
                if (test == null) {
                    continue;
                }
                String name = testClass.getSimpleName().toLowerCase(Locale.ROOT) + "."
                        + method.getName().toLowerCase(Locale.ROOT);
                GameTestRegistry.getAllTestFunctions()
                        .add(new TestFunction(
                                test.batch(),
                                name,
                                test.template(),
                                StructureUtils.getRotationForRotationSteps(test.rotationSteps()),
                                test.timeoutTicks(),
                                test.setupTicks(),
                                test.required(),
                                test.manualOnly(),
                                test.attempts(),
                                test.requiredSuccesses(),
                                test.skyAccess(),
                                invoke(testClass, method)));
            }
            GameTestRegistry.getAllTestClassNames().add(testClass.getSimpleName());
        }
    }

    /** Run a test method on a fresh instance of its class, as vanilla does. */
    private static Consumer<GameTestHelper> invoke(Class<?> testClass, Method method) {
        return helper -> {
            try {
                method.invoke(testClass.getConstructor().newInstance(), helper);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException runtime) {
                    // Unwrapped on purpose: the test's own failure, not the reflection wrapper
                    throw runtime; // NOPMD - assertion failures (GameTestAssertException) included
                }
                throw new RuntimeException(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        };
    }
}
