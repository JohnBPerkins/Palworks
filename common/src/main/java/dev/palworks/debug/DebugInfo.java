package dev.palworks.debug;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.reservation.ReservationId;
import dev.palworks.station.StationState;
import dev.palworks.station.StationStateManager;
import dev.palworks.suitability.Suitability;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Provides debug information for workers, stations, and bases.
 * Used for rendering debug overlays and troubleshooting.
 */
public class DebugInfo {

    /**
     * Get debug info for a worker Pokemon.
     */
    @Nullable
    public static WorkerDebugInfo getWorkerInfo(Pokemon pokemon, @Nullable Mob entity) {
        WorkerState state = WorkerStateManager.get(pokemon);
        if (state == null) {
            return null;
        }

        return new WorkerDebugInfo(pokemon, state, entity);
    }

    /**
     * Get debug info for a station at a position.
     */
    @Nullable
    public static StationDebugInfo getStationInfo(Level level, BlockPos pos) {
        StationState state = StationStateManager.get(level, pos);
        if (state == null) {
            return null;
        }

        return new StationDebugInfo(state, pos);
    }

    /**
     * Worker debug information.
     */
    public static class WorkerDebugInfo {
        public final String speciesName;
        public final boolean enabled;
        public final String assignedJob;
        public final String currentReservation;
        public final String basePosition;
        public final List<String> suitabilities;
        public final List<String> cooldowns;
        public final int pathFailCount;
        public final boolean isStuck;
        public final int consecutiveWorkTicks;
        public final BlockPos entityPosition;

        public WorkerDebugInfo(Pokemon pokemon, WorkerState state, @Nullable Mob entity) {
            this.speciesName = pokemon.getSpecies().getName();
            this.enabled = state.isEnabled();
            this.assignedJob = state.getAssignedJob() != null ? state.getAssignedJob().name() : "None";

            ReservationId reservation = state.getCurrentReservation();
            if (reservation != null) {
                BlockPos pos = reservation.getStationPos();
                this.currentReservation = String.format("%s at [%d, %d, %d]",
                        reservation.getJobType().name(), pos.getX(), pos.getY(), pos.getZ());
            } else {
                this.currentReservation = "None";
            }

            this.basePosition = "N/A";  // Base system removed

            this.suitabilities = new ArrayList<>();
            Set<Suitability> suits = state.getSuitabilities();
            for (Suitability suit : suits) {
                String cooldownInfo = state.isOnCooldown(suit) ?
                        String.format(" (CD: %ds)", state.getRemainingCooldown(suit) / 20) :
                        "";
                this.suitabilities.add(suit.getId() + cooldownInfo);
            }

            this.cooldowns = new ArrayList<>();
            for (Suitability suit : Suitability.values()) {
                if (state.isOnCooldown(suit)) {
                    this.cooldowns.add(String.format("%s: %ds",
                            suit.getId(), state.getRemainingCooldown(suit) / 20));
                }
            }

            this.pathFailCount = state.getPathFailCount();
            this.isStuck = state.isStuck();
            this.consecutiveWorkTicks = state.getConsecutiveWorkTicks();
            this.entityPosition = entity != null ? entity.blockPosition() : null;
        }

        public List<String> toStringList() {
            List<String> lines = new ArrayList<>();
            lines.add("=== Worker: " + speciesName + " ===");
            lines.add("Enabled: " + (enabled ? "Yes" : "No"));
            lines.add("Job: " + assignedJob);
            lines.add("Reservation: " + currentReservation);
            lines.add("Base: " + basePosition);

            if (entityPosition != null) {
                lines.add(String.format("Position: [%d, %d, %d]",
                        entityPosition.getX(), entityPosition.getY(), entityPosition.getZ()));
            }

            lines.add("Suitabilities: " + (suitabilities.isEmpty() ? "None" : ""));
            for (String suit : suitabilities) {
                lines.add("  - " + suit);
            }

            if (!cooldowns.isEmpty()) {
                lines.add("Cooldowns:");
                for (String cd : cooldowns) {
                    lines.add("  - " + cd);
                }
            }

            lines.add("Work Ticks: " + consecutiveWorkTicks);
            lines.add("Path Fails: " + pathFailCount + (isStuck ? " (STUCK)" : ""));

            return lines;
        }
    }

    /**
     * Station debug information.
     */
    public static class StationDebugInfo {
        public final BlockPos position;
        public final String jobType;
        public final boolean enabled;
        public final int queueSize;
        public final boolean hasCurrentJob;
        public final float progress;
        public final String reservedWorker;

        public StationDebugInfo(StationState state, BlockPos pos) {
            this.position = pos;
            this.jobType = state.getJobType().name();
            this.enabled = state.isEnabled();
            this.queueSize = state.getQueueSize();
            this.hasCurrentJob = state.hasCurrentJob();
            this.progress = state.getProgress();
            this.reservedWorker = state.getReservedWorkerUUID() != null ?
                    state.getReservedWorkerUUID().toString().substring(0, 8) + "..." :
                    "None";
        }

        public List<String> toStringList() {
            List<String> lines = new ArrayList<>();
            lines.add("=== Station ===");
            lines.add(String.format("Position: [%d, %d, %d]",
                    position.getX(), position.getY(), position.getZ()));
            lines.add("Type: " + jobType);
            lines.add("Enabled: " + (enabled ? "Yes" : "No"));
            lines.add("Queue: " + queueSize + " jobs");
            lines.add("Current Job: " + (hasCurrentJob ? "Yes" : "No"));
            if (hasCurrentJob) {
                lines.add("Progress: " + String.format("%.1f%%", progress));
            }
            lines.add("Reserved By: " + reservedWorker);
            return lines;
        }
    }
}
