package dev.palworks.mixin;

import dev.palworks.drops.TrackedDrops;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * What a block a player mines drops (and a container they break spills) is theirs: transporters
 * pick it up (see TrackedDrops). Listed in palworks-common.mixins.json.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void palworks$beforeBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        TrackedDrops.begin(null, pos, false);
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void palworks$afterBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        TrackedDrops.end();
    }
}
