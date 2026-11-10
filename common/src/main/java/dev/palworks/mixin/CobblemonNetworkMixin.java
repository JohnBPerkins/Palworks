package dev.palworks.mixin;

import com.cobblemon.mod.common.CobblemonNetwork;
import com.cobblemon.mod.common.net.PacketRegisterInfo;
import dev.palworks.net.handlers.OpenInventoryHandler;
import dev.palworks.net.handlers.ToggleWorkerHandler;
import dev.palworks.net.handlers.WorkActivityHandler;
import dev.palworks.net.handlers.WorkerStatusHandler;
import dev.palworks.net.packets.OpenInventoryPacket;
import dev.palworks.net.packets.ToggleWorkerPacket;
import dev.palworks.net.packets.WorkActivityPacket;
import dev.palworks.net.packets.WorkerStatusPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Mixin to register Palworks network packets with Cobblemon's network system.
 */
@Mixin(value = CobblemonNetwork.class, remap = false)
public abstract class CobblemonNetworkMixin {

    /**
     * Inject into the C2S (Client to Server) packet list to add our packets.
     */
    @Inject(method = "generateC2SPacketInfoList", at = @At("RETURN"))
    private void addPalworksC2SPackets(CallbackInfoReturnable<List<PacketRegisterInfo<?>>> cir) {
        List<PacketRegisterInfo<?>> list = cir.getReturnValue();

        // Register OpenInventoryPacket
        list.add(new PacketRegisterInfo<>(
                OpenInventoryPacket.ID,
                OpenInventoryPacket::decode,
                new OpenInventoryHandler(),
                null
        ));

        list.add(new PacketRegisterInfo<>(
                ToggleWorkerPacket.ID,
                ToggleWorkerPacket::decode,
                new ToggleWorkerHandler(),
                null
        ));
    }

    /**
     * Inject into the S2C (Server to Client) packet list to add our packets.
     */
    @Inject(method = "generateS2CPacketInfoList", at = @At("RETURN"))
    private void addPalworksS2CPackets(CallbackInfoReturnable<List<PacketRegisterInfo<?>>> cir) {
        cir.getReturnValue().add(new PacketRegisterInfo<>(
                WorkerStatusPacket.ID,
                WorkerStatusPacket::decode,
                new WorkerStatusHandler(),
                null
        ));
        cir.getReturnValue().add(new PacketRegisterInfo<>(
                WorkActivityPacket.ID,
                WorkActivityPacket::decode,
                new WorkActivityHandler(),
                null
        ));
    }
}
