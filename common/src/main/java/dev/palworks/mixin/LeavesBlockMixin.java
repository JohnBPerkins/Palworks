package dev.palworks.mixin;

import dev.palworks.drops.DecayingCanopies;
import dev.palworks.drops.TrackedDrops;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What a chopped tree's leaves drop as they decay is the worker's, to pick up (see
 * DecayingCanopies, TrackedDrops). Only leaves that are decaying get random ticks at all, so this
 * runs for those alone. Listed in palworks-common.mixins.json.
 */
@Mixin(LeavesBlock.class)
public abstract class LeavesBlockMixin {
    @Inject(method = "randomTick", at = @At("HEAD"))
    private void palworks$beforeDecay(
            BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (DecayingCanopies.get(level).contains(pos)) {
            TrackedDrops.begin(null, null, true);
        }
    }

    @Inject(method = "randomTick", at = @At("RETURN"))
    private void palworks$afterDecay(
            BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        // Same answer as at the start: the canopies don't change during a random tick
        if (DecayingCanopies.get(level).contains(pos)) {
            TrackedDrops.end();
        }
    }
}
