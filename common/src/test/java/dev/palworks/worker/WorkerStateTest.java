package dev.palworks.worker;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the worker enabled flag: owned Pokemon work by default, and an explicit disable must
 * survive a save/load round trip instead of being silently turned back on.
 */
class WorkerStateTest {

    @BeforeAll
    static void bootstrap() {
        // WorkerState holds an ItemStack, which needs the vanilla registries to be initialised.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void newStateIsEnabled() {
        assertTrue(new WorkerState().isEnabled());
    }

    @Test
    void disabledSurvivesSaveAndLoad() {
        WorkerState state = new WorkerState();
        state.setEnabled(false);

        WorkerState loaded = WorkerState.fromNBT(state.serializeNBT());

        assertFalse(loaded.isEnabled(), "a disabled worker must stay disabled after reload");
    }

    @Test
    void enabledSurvivesSaveAndLoad() {
        WorkerState loaded = WorkerState.fromNBT(new WorkerState().serializeNBT());

        assertTrue(loaded.isEnabled());
    }

    @Test
    void stateWithoutEnabledKeyLoadsAsEnabled() {
        assertTrue(WorkerState.fromNBT(new CompoundTag()).isEnabled(),
            "data saved before the enabled key existed should default to enabled");
    }
}
