package dev.palworks.fabric.mixin;

import dev.palworks.manager.JobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric copy of dev.palworks.mixin.ServerLevelMixin: Fabric loads this module's own
 * palworks.mixins.json rather than common's, and this config is the one set up for remapping.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Inject(method = "onBlockStateChange", at = @At("HEAD"))
    private void palworks$onBlockStateChange(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        JobManager.onBlockStateChange((ServerLevel) (Object) this, pos, oldState, newState);
    }
}
