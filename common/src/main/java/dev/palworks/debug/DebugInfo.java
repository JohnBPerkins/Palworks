package dev.palworks.debug;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.reservation.ReservationId;
import dev.palworks.suitability.Suitability;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

/**
 * Read-only snapshot of a worker's state, formatted for the {@code /palworks inspect worker} command.
 */
public class DebugInfo {

    /** Get debug info for a worker Pokemon, or null if it has no worker state. */
    @Nullable
    public static WorkerDebugInfo getWorkerInfo(Pokemon pokemon, @Nullable Mob entity) {
        WorkerState state = WorkerStateManager.get(pokemon);
        if (state == null) {
            return null;
        }
        return new WorkerDebugInfo(pokemon, state, entity);
    }

    public static class WorkerDebugInfo {
        public final String speciesName;
        public final boolean enabled;
        public final String assignedJob;
        public final String currentReservation;
        public final List<String> suitabilities;
        public final int pathFailCount;
        public final boolean isStuck;
        public final int jobStep;
        public final int stepTicks;
        public final BlockPos entityPosition;

        public WorkerDebugInfo(Pokemon pokemon, WorkerState state, @Nullable Mob entity) {
            this.speciesName = pokemon.getSpecies().getName();
            this.enabled = state.isEnabled();
            this.assignedJob =
                    state.getAssignedJob() != null ? state.getAssignedJob().name() : "None";

            ReservationId reservation = state.getCurrentReservation();
            if (reservation != null) {
                BlockPos pos = reservation.getStationPos();
                this.currentReservation = String.format(
                        "%s at [%d, %d, %d]", reservation.getJobType().name(), pos.getX(), pos.getY(), pos.getZ());
            } else {
                this.currentReservation = "None";
            }

            this.suitabilities = new ArrayList<>();
            Set<Suitability> suits = dev.palworks.suitability.SuitabilityDetector.getSuitabilities(pokemon);
            for (Suitability suit : suits) {
                // With its work level: "watering 3"
                this.suitabilities.add(suit.getId() + " "
                        + dev.palworks.suitability.SuitabilityDetector.getSuitabilityLevel(pokemon, suit));
            }

            this.pathFailCount = state.getPathFailCount();
            this.isStuck = state.isStuck();
            this.jobStep = state.getJobStep();
            this.stepTicks = state.getStepTicks();
            this.entityPosition = entity != null ? entity.blockPosition() : null;
        }

        public List<String> toStringList() {
            List<String> lines = new ArrayList<>();
            lines.add("=== Worker: " + speciesName + " ===");
            lines.add("Enabled: " + (enabled ? "Yes" : "No"));
            lines.add("Job: " + assignedJob);
            lines.add("Reservation: " + currentReservation);

            if (entityPosition != null) {
                lines.add(String.format(
                        "Position: [%d, %d, %d]", entityPosition.getX(), entityPosition.getY(), entityPosition.getZ()));
            }

            lines.add("Suitabilities: " + (suitabilities.isEmpty() ? "None" : ""));
            for (String suit : suitabilities) {
                lines.add("  - " + suit);
            }

            lines.add("Job Step: " + jobStep + " (" + stepTicks + " ticks in)");
            lines.add("Path Fails: " + pathFailCount + (isStuck ? " (STUCK)" : ""));

            return lines;
        }
    }
}
