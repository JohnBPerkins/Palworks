package dev.palworks.gametest.neoforge.mixin;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tests only (the palworks_gametest mod, never published). Lets payloads through to a GameTest mock
 * player. Worker tests give their Pokémon a mock player as owner, and Cobblemon sends a party's
 * owner sync packets. NeoForge refuses to send a payload the client never negotiated, and a mock
 * player's fake connection (a Netty EmbeddedChannel, which just collects packets) negotiated
 * nothing. Real connections are unaffected.
 */
@Mixin(value = NetworkRegistry.class, remap = false)
public abstract class MockPlayerPayloadMixin {

    @Inject(method = "checkPacket(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/protocol/common/ServerCommonPacketListener;)V",
            at = @At("HEAD"), cancellable = true)
    private static void palworksGametest$allowMockPlayers(Packet<?> packet, ServerCommonPacketListener listener, CallbackInfo ci) {
        if (listener.getConnection().channel() instanceof EmbeddedChannel) {
            ci.cancel();
        }
    }
}
