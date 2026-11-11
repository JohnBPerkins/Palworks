package dev.palworks.stress.neoforge;

import dev.palworks.gametest.TestRegistration;
import dev.palworks.stress.StressGameTests;
import java.util.List;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Registers the stress test on NeoForge (see TestRegistration and neoforge/build.gradle). */
@Mod("palworks_stress")
public class PalworksStressNeoForge {

    public PalworksStressNeoForge(IEventBus modBus) {
        modBus.addListener(
                RegisterGameTestsEvent.class,
                event -> TestRegistration.register(
                        List.of(StressGameTests.class), System.getProperty("neoforge.enabledGameTestNamespaces")));
        TestRegistration.reportTo(System.getProperty("palworks.gametest.report"));
    }
}
