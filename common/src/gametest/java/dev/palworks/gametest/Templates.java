package dev.palworks.gametest;

import java.util.List;

/**
 * The in-game tests, shared by both loaders: the same classes run on Fabric and NeoForge. They use
 * only vanilla Minecraft, Cobblemon and Palworks; each loader registers them its own way (Fabric
 * through its fabric-gametest entrypoint, NeoForge through {@code PalworksGameTestsNeoForge}).
 */
public final class Templates {
    private Templates() {}

    /** An empty 8x8x8 test area (data/palworks_gametest/structure/empty.nbt, see make_structures.py). */
    public static final String EMPTY = "palworks_gametest:empty";
    /** An empty 16x24x16 test area, big enough for full-size trees (arena.nbt). */
    public static final String ARENA = "palworks_gametest:arena";

    /** Every test class. Fabric lists the same classes in its fabric.mod.json entrypoint. */
    public static final List<Class<?>> TEST_CLASSES = List.of(
        TreeGameTests.class,
        CropGameTests.class,
        JobGameTests.class,
        WorkerGameTests.class,
        RealTreeGameTests.class,
        WorkStatusGameTests.class,
        PickupGameTests.class,
        TestAreaGameTests.class);
}
