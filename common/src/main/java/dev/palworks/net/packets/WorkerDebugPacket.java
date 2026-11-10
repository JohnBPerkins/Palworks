package dev.palworks.net.packets;

import com.cobblemon.mod.common.api.net.NetworkPacket;
import dev.palworks.job.JobType;
import dev.palworks.net.PalworksResource;
import dev.palworks.worker.WorkActivity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Sent from server to a player with {@code /palworks debug overlay} on: every worker near them,
 * what it's doing, where its job is and the path it's walking. Replaces the previous one; an empty
 * list clears the overlay.
 */
public class WorkerDebugPacket implements NetworkPacket<WorkerDebugPacket> {
    public static final ResourceLocation ID = PalworksResource.of("worker_debug");

    /**
     * @param entityId the worker's entity id (it's in the client's world)
     * @param target   the block its job is at, or null with no job
     * @param path     the rest of the path it's walking, in order (empty when not walking)
     */
    public record Entry(int entityId, WorkActivity activity, @Nullable BlockPos target, List<BlockPos> path) {}

    private final List<Entry> entries;

    public WorkerDebugPacket(List<Entry> entries) {
        this.entries = entries;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    @NotNull
    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public void encode(@NotNull RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buffer.writeVarInt(entry.entityId());
            buffer.writeVarInt(entry.activity().phase().ordinal());
            buffer.writeUtf(entry.activity().job() == null ? "" : entry.activity().job().name());
            buffer.writeBoolean(entry.target() != null);
            if (entry.target() != null) {
                buffer.writeBlockPos(entry.target());
            }
            buffer.writeVarInt(entry.path().size());
            for (BlockPos node : entry.path()) {
                buffer.writeBlockPos(node);
            }
        }
    }

    public static WorkerDebugPacket decode(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int entityId = buffer.readVarInt();
            WorkActivity.Phase phase = WorkActivity.Phase.fromOrdinal(buffer.readVarInt());
            String jobName = buffer.readUtf();
            JobType job = null;
            if (!jobName.isEmpty()) {
                try {
                    job = JobType.valueOf(jobName);
                } catch (IllegalArgumentException ignored) {
                    // A job this client doesn't know: show it without a job icon.
                }
            }
            BlockPos target = buffer.readBoolean() ? buffer.readBlockPos() : null;
            int nodes = buffer.readVarInt();
            List<BlockPos> path = new ArrayList<>(nodes);
            for (int n = 0; n < nodes; n++) {
                path.add(buffer.readBlockPos());
            }
            entries.add(new Entry(entityId, new WorkActivity(phase, job), target, path));
        }
        return new WorkerDebugPacket(entries);
    }
}
