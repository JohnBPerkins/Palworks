package dev.palworks.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.palworks.drops.TrackedDrops;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;

/**
 * What a block a player mines drops (and a container they break spills) is theirs: transporters
 * pick it up (see TrackedDrops). The whole break is wrapped, rather than hooked at its start and
 * its returns, so the marking stops however the break ends: another mod cancelling it with a
 * return of its own (Fabric API's break event does), or the break throwing. Listed in
 * palworks-common.mixins.json.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @WrapMethod(method = "destroyBlock")
    private boolean palworks$trackBreak(BlockPos pos, Operation<Boolean> original) {
        TrackedDrops.begin(null, pos, false);
        try {
            return original.call(pos);
        } finally {
            TrackedDrops.end();
        }
    }
}
