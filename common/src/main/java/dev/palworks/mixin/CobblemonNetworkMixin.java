package dev.palworks.mixin;

import com.cobblemon.mod.common.CobblemonNetwork;
import com.cobblemon.mod.common.net.PacketRegisterInfo;
import dev.palworks.net.handlers.OpenInventoryHandler;
import dev.palworks.net.handlers.RequestPcWorkLevelsHandler;
import dev.palworks.net.handlers.ToggleWorkerHandler;
import dev.palworks.net.handlers.WorkActivityHandler;
import dev.palworks.net.handlers.WorkLevelsHandler;
import dev.palworks.net.handlers.WorkerDebugHandler;
import dev.palworks.net.handlers.WorkerMoodHandler;
import dev.palworks.net.handlers.WorkerStatusHandler;
import dev.palworks.net.packets.OpenInventoryPacket;
import dev.palworks.net.packets.RequestPcWorkLevelsPacket;
import dev.palworks.net.packets.ToggleWorkerPacket;
import dev.palworks.net.packets.WorkActivityPacket;
import dev.palworks.net.packets.WorkLevelsPacket;
import dev.palworks.net.packets.WorkerDebugPacket;
import dev.palworks.net.packets.WorkerMoodPacket;
import dev.palworks.net.packets.WorkerStatusPacket;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
                OpenInventoryPacket.ID, OpenInventoryPacket::decode, new OpenInventoryHandler(), null));

        list.add(new PacketRegisterInfo<>(
                ToggleWorkerPacket.ID, ToggleWorkerPacket::decode, new ToggleWorkerHandler(), null));

        list.add(new PacketRegisterInfo<>(
                RequestPcWorkLevelsPacket.ID,
                RequestPcWorkLevelsPacket::decode,
                new RequestPcWorkLevelsHandler(),
                null));
    }

    /**
     * Inject into the S2C (Server to Client) packet list to add our packets.
     */
    @Inject(method = "generateS2CPacketInfoList", at = @At("RETURN"))
    private void addPalworksS2CPackets(CallbackInfoReturnable<List<PacketRegisterInfo<?>>> cir) {
        cir.getReturnValue()
                .add(new PacketRegisterInfo<>(
                        WorkerStatusPacket.ID, WorkerStatusPacket::decode, new WorkerStatusHandler(), null));
        cir.getReturnValue()
                .add(new PacketRegisterInfo<>(
                        WorkActivityPacket.ID, WorkActivityPacket::decode, new WorkActivityHandler(), null));
        cir.getReturnValue()
                .add(new PacketRegisterInfo<>(
                        WorkerDebugPacket.ID, WorkerDebugPacket::decode, new WorkerDebugHandler(), null));
        cir.getReturnValue()
                .add(new PacketRegisterInfo<>(
                        WorkLevelsPacket.ID, WorkLevelsPacket::decode, new WorkLevelsHandler(), null));
        cir.getReturnValue()
                .add(new PacketRegisterInfo<>(
                        WorkerMoodPacket.ID, WorkerMoodPacket::decode, new WorkerMoodHandler(), null));
    }
}
