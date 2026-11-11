package dev.palworks.gametest.mixin;

import net.minecraft.gametest.framework.StructureGridSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Tests only (the palworks_gametest mod, never published). Spaces tests that run at the same time
 * far enough apart that they can't touch each other. Minecraft lays them out 5 blocks apart in a
 * row and 6 between rows, but a worker looks for jobs 32 blocks round itself, and a worker test
 * clears everything within 40 blocks of its area when it starts (Workers.isolate). So a test could
 * delete a neighbour's dropped items, or a worker take a neighbour's jobs, failing tests at random.
 * {@link #GAP} blocks apart, neither reaches.
 */
@Mixin(value = StructureGridSpawner.class, remap = false)
public abstract class StructureGridSpawnerMixin {
    /** Blocks between tests: past a test's clearing (40) and a worker's job range (32), with room. */
    private static final int GAP = 64;

    @ModifyConstant(method = "spawnStructure", constant = @Constant(intValue = 5))
    private int palworksGametest$spaceColumns(int vanilla) {
        return GAP;
    }

    @ModifyConstant(method = "spawnStructure", constant = @Constant(intValue = 6))
    private int palworksGametest$spaceRows(int vanilla) {
        return GAP;
    }
}
