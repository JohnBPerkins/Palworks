package dev.palworks.mixin;

import dev.palworks.job.JobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells JobManager when a block entity's contents change (see
 * {@link JobManager#onContainerChanged}): a chest or furnace filled or emptied, by hand or by
 * hopper, or a furnace using up its fuel. The game calls this static {@code setChanged} for every
 * such change (the instance one, which containers call, comes through it too); it's how the chunk
 * knows to save. Listed in palworks-common.mixins.json.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityChangedMixin {

    @Inject(
            method =
                    "setChanged(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("HEAD"))
    private static void palworks$onChanged(Level level, BlockPos pos, BlockState state, CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel) {
            JobManager.onContainerChanged(serverLevel, pos);
        }
    }
}
