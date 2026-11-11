package dev.palworks.gametest.neoforge;

import dev.palworks.gametest.Templates;
import dev.palworks.gametest.TestRegistration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Registers the shared in-game tests (Templates.TEST_CLASSES) on NeoForge (see TestRegistration),
 * and writes a JUnit-style report to the file in {@code -Dpalworks.gametest.report} (see
 * neoforge/build.gradle), like Fabric's {@code fabric-api.gametest.report-file}.
 */
@Mod("palworks_gametest")
public class PalworksGameTestsNeoForge {

    public PalworksGameTestsNeoForge(IEventBus modBus) {
        modBus.addListener(
                RegisterGameTestsEvent.class,
                event -> TestRegistration.register(
                        Templates.TEST_CLASSES, System.getProperty("neoforge.enabledGameTestNamespaces")));
        TestRegistration.reportTo(System.getProperty("palworks.gametest.report"));
    }
}
