package dev.palworks.mixin;

import dev.palworks.manager.JobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells JobManager about block changes, so workers re-check jobs they couldn't reach as soon as
 * the terrain changes (see {@link JobManager#onBlockStateChange}). Listed in
 * palworks-common.mixins.json, which both loaders load.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Inject(method = "onBlockStateChange", at = @At("HEAD"))
    private void palworks$onBlockStateChange(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        JobManager.onBlockStateChange((ServerLevel) (Object) this, pos, oldState, newState);
    }
}
