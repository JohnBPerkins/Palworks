package dev.palworks.gametest;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import javax.xml.parsers.ParserConfigurationException;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.JUnitLikeTestReporter;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.gametest.framework.TestFunction;
import org.jetbrains.annotations.Nullable;

/**
 * Registers test classes with vanilla's GameTest registry, the way Fabric's GameTest API does:
 * each {@code @GameTest} method becomes a test, using its template exactly as written
 * ("palworks_gametest:arena"), and each {@code @BeforeBatch} and {@code @AfterBatch} method runs
 * before and after its batch. NeoForge's own registration would instead build the structure name
 * from a NeoForge-only annotation and the class name, which the shared classes can't carry. Used by
 * the NeoForge test mods (in-game tests and the stress test); Fabric's API does this itself.
 */
public final class TestRegistration {
    private TestRegistration() {}

    /**
     * Register every {@code @GameTest} method of these classes whose template is in one of
     * {@code enabledNamespaces} (comma-separated, as NeoForge's
     * {@code -Dneoforge.enabledGameTestNamespaces}), or all of them if that's null. NeoForge only
     * applies that filter to tests it registers itself, so it's applied here too: the stress run
     * loads the in-game test mod, but runs none of its tests.
     */
    public static void register(List<Class<?>> testClasses, @Nullable String enabledNamespaces) {
        Set<String> namespaces = enabledNamespaces == null ? null : Set.of(enabledNamespaces.split(","));
        registerAll(testClasses, namespaces);
    }

    /** Write a JUnit-style report to {@code reportPath}, if given (like Fabric's report-file option). */
    public static void reportTo(String reportPath) {
        if (reportPath == null) {
            return;
        }
        try {
            File file = new File(reportPath);
            file.getParentFile().mkdirs();
            GlobalTestReporter.replaceWith(new JUnitLikeTestReporter(file));
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("couldn't set up the GameTest report " + reportPath, e);
        }
    }

    private static void registerAll(List<Class<?>> testClasses, @Nullable Set<String> namespaces) {
        for (Class<?> testClass : testClasses) {
            boolean anyRegistered = false;
            for (Method method : testClass.getDeclaredMethods()) {
                GameTest test = method.getAnnotation(GameTest.class);
                if (test == null) {
                    continue;
                }
                String namespace =
                        test.template().substring(0, Math.max(0, test.template().indexOf(':')));
                if (namespaces != null && !namespaces.contains(namespace)) {
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
                anyRegistered = true;
            }
            if (anyRegistered) {
                registerBatchHooks(testClass);
            }
            GameTestRegistry.getAllTestClassNames().add(testClass.getSimpleName());
        }
    }

    /**
     * Register a class's {@code @BeforeBatch} and {@code @AfterBatch} methods: by vanilla itself,
     * which registers only those for a method without {@code @GameTest}.
     */
    private static void registerBatchHooks(Class<?> testClass) {
        for (Method method : testClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(BeforeBatch.class) || method.isAnnotationPresent(AfterBatch.class)) {
                GameTestRegistry.register(method);
            }
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
