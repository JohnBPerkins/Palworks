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
 * Sent from server to a player with {@code /palworks debug} on: every worker near them, what it's
 * doing, where its job is, the path it's walking, and why it might not be getting anywhere.
 * Replaces the previous one; an empty list clears the overlay.
 */
public class WorkerDebugPacket implements NetworkPacket<WorkerDebugPacket> {
    public static final ResourceLocation ID = PalworksResource.of("worker_debug");

    /**
     * @param entityId    the worker's entity id (it's in the client's world)
     * @param target      the block its job is at, or null with no job
     * @param path        the rest of the path it's walking, in order (empty when not walking)
     * @param details     timers and path status
     * @param unreachable the spots it currently remembers it couldn't reach (it won't take jobs there)
     */
    public record Entry(int entityId, WorkActivity activity, @Nullable BlockPos target, List<BlockPos> path,
                        Details details, List<BlockPos> unreachable) {}

    /** Whether the path it's walking gets it to its job. */
    public enum PathStatus {
        /** Not walking anywhere. */
        NONE,
        /** The path reaches the job's stand tile. */
        REACHES,
        /** The path only gets partway: it will stop short of the job. */
        PARTIAL;

        static PathStatus fromOrdinal(int ordinal) {
            PathStatus[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NONE;
        }
    }

    /**
     * @param jobAgeTicks    how long it has had its job; -1 with no job
     * @param expiresInTicks ticks until it gives the job up for not having started it; -1 when it
     *                       won't (no job, already working on it, or paused)
     * @param stepName       the step it's on ("chop"); empty when it hasn't started the job
     * @param stepIndex      which step (from 0)
     * @param stepCount      how many steps the job has
     * @param stepTicks      ticks into the step
     * @param stepLength     ticks the step takes
     * @param pathStatus     whether its path gets it to the job
     * @param pathFailures   walks that ended short of the job, recently (it gives up at
     *                       {@code WorkerState.STUCK_AFTER_PATH_FAILURES})
     * @param stepInTicks    ticks spent stepping onto its tile after its walk ended just short (lumberjacks)
     * @param stillTicks     ticks it has stood still while meant to be walking (counted while
     *                       someone has the overlay on)
     */
    public record Details(int jobAgeTicks, int expiresInTicks, String stepName, int stepIndex, int stepCount,
                          int stepTicks, int stepLength, PathStatus pathStatus, int pathFailures,
                          int stepInTicks, int stillTicks) {
        public static final Details NONE = new Details(-1, -1, "", 0, 0, 0, 0, PathStatus.NONE, 0, 0, 0);
    }

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
            writePositions(buffer, entry.path());

            Details d = entry.details();
            buffer.writeVarInt(d.jobAgeTicks() + 1);
            buffer.writeVarInt(d.expiresInTicks() + 1);
            buffer.writeUtf(d.stepName());
            buffer.writeVarInt(d.stepIndex());
            buffer.writeVarInt(d.stepCount());
            buffer.writeVarInt(d.stepTicks());
            buffer.writeVarInt(d.stepLength());
            buffer.writeVarInt(d.pathStatus().ordinal());
            buffer.writeVarInt(d.pathFailures());
            buffer.writeVarInt(d.stepInTicks());
            buffer.writeVarInt(d.stillTicks());

            writePositions(buffer, entry.unreachable());
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
            List<BlockPos> path = readPositions(buffer);

            Details details = new Details(buffer.readVarInt() - 1, buffer.readVarInt() - 1, buffer.readUtf(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                    PathStatus.fromOrdinal(buffer.readVarInt()), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readVarInt());

            List<BlockPos> unreachable = readPositions(buffer);
            entries.add(new Entry(entityId, new WorkActivity(phase, job), target, path, details, unreachable));
        }
        return new WorkerDebugPacket(entries);
    }

    private static void writePositions(RegistryFriendlyByteBuf buffer, List<BlockPos> positions) {
        buffer.writeVarInt(positions.size());
        for (BlockPos pos : positions) {
            buffer.writeBlockPos(pos);
        }
    }

    private static List<BlockPos> readPositions(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<BlockPos> positions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            positions.add(buffer.readBlockPos());
        }
        return positions;
    }
}
