package dev.palworks.manager;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.ai.WorkPositions;
import dev.palworks.ai.path.WorkerPathfinder.Reach;
import dev.palworks.job.JobType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/** The {@code /palworks jobs} report: a level's jobs and workers, and why idle workers aren't working. */
final class JobsDebugReport {
    // Job positions listed per job type
    private static final int JOB_POSITIONS_SHOWN = 3;

    private JobsDebugReport() {}

    /**
     * Get debug information about current job state.
     * Returns a list of formatted strings for display.
     */
    static List<String> build(ServerLevel level, JobPool pool, WorkerRegistry workers, JobAssigner assigner) {
        List<String> lines = new ArrayList<>();

        // Header
        lines.add("§6=== Palworks Job Debug ===");

        // Available jobs summary
        lines.add("");
        lines.add("§e[Available Jobs: " + pool.available().size() + "]");
        if (pool.available().isEmpty()) {
            lines.add("  §7(none)");
        } else {
            // Group by type
            Map<JobType, List<JobRecord>> byType =
                    pool.available().stream().collect(Collectors.groupingBy(j -> j.type()));
            for (Map.Entry<JobType, List<JobRecord>> entry : byType.entrySet()) {
                JobType type = entry.getKey();
                List<JobRecord> jobs = entry.getValue();
                lines.add(String.format(
                        "  §a%s§f: %d jobs (need §b%s§f)",
                        type.name(), jobs.size(), jobs.get(0).requiredSuitabilities()));
                int shown = 0;
                for (JobRecord job : jobs) {
                    if (shown >= JOB_POSITIONS_SHOWN) {
                        lines.add(String.format("    §7... and %d more", jobs.size() - JOB_POSITIONS_SHOWN));
                        break;
                    }
                    lines.add(String.format(
                            "    §7[%d, %d, %d] pri=%d",
                            job.position().getX(),
                            job.position().getY(),
                            job.position().getZ(),
                            job.priority()));
                    shown++;
                }
            }
        }

        // Reserved jobs
        lines.add("");
        lines.add("§e[Reserved Jobs: " + pool.reserved().size() + "]");
        if (pool.reserved().isEmpty()) {
            lines.add("  §7(none)");
        } else {
            for (JobRecord job : pool.reserved()) {
                String workerName = "unknown";
                if (job.reservedBy() != null) {
                    Pokemon pokemon = workers.pokemon(job.reservedBy());
                    if (pokemon != null) {
                        workerName = pokemon.getSpecies().getName();
                    }
                }
                lines.add(String.format(
                        "  §a%s§f at [%d, %d, %d] -> §b%s",
                        job.type().name(),
                        job.position().getX(),
                        job.position().getY(),
                        job.position().getZ(),
                        workerName));
            }
        }

        // Active workers
        lines.add("");
        lines.add("§e[Active Workers: " + workers.size() + "]");
        if (workers.all().isEmpty()) {
            lines.add("  §7(none)");
        } else {
            for (WorkerInfo worker : workers.all()) {
                Pokemon pokemon = workers.pokemon(worker.pokemonUUID);
                String name = pokemon != null ? pokemon.getSpecies().getName() : "unknown";
                String status;
                if (worker.idle) {
                    status = "§7IDLE";
                } else if (worker.currentReservation != null) {
                    status = "§a" + worker.currentReservation.getJobType().name();
                } else {
                    status = "§cNO_RESERVATION";
                }

                // Get suitabilities as short string
                String suits = worker.suitabilities.stream()
                        .map(s -> s.name().substring(0, Math.min(3, s.name().length())))
                        .collect(Collectors.joining(","));

                lines.add(String.format("  §b%s§f: %s §7[%s]", name, status, suits));
            }
        }

        // Check for workers that should be able to take available jobs but aren't
        lines.add("");
        lines.add("§e[Assignment Analysis]");
        for (WorkerInfo worker : workers.all()) {
            if (!worker.idle || worker.currentReservation != null) continue;

            Pokemon pokemon = workers.pokemon(worker.pokemonUUID);
            String name = pokemon != null ? pokemon.getSpecies().getName() : "unknown";
            Mob entity = pokemon != null ? (Mob) pokemon.getEntity() : null;

            // Count jobs by category for this worker
            int noSuitability = 0;
            int noPath = 0;
            int canAssign = 0;
            Map<JobType, Integer> noPathByType = new EnumMap<>(JobType.class);

            for (JobRecord job : pool.available()) {
                // Check suitability
                if (!JobAssigner.canDoJob(worker, job)) {
                    noSuitability++;
                    continue;
                }

                // Check pathfinding to the resolved stand position
                BlockPos standPos =
                        entity != null ? WorkPositions.standingPosFor(level, job.position(), job.type(), entity) : null;
                if (entity != null
                        && (standPos == null
                                || assigner.canPathTo(entity, standPos).reach() != Reach.YES)) {
                    noPath++;
                    noPathByType.merge(job.type(), 1, Integer::sum);
                    continue;
                }

                canAssign++;
            }

            // Show summary for this worker
            if (canAssign > 0) {
                lines.add(String.format(
                        "  §a%s§f: %d assignable, %d no path, %d wrong suitability",
                        name, canAssign, noPath, noSuitability));
            } else if (noPath > 0) {
                lines.add(String.format(
                        "  §c%s§f: 0 assignable, §c%d no path§f, %d wrong suitability", name, noPath, noSuitability));
                // Show breakdown of path failures by job type
                for (Map.Entry<JobType, Integer> entry : noPathByType.entrySet()) {
                    lines.add(String.format(
                            "    §7- %s: %d jobs can't path", entry.getKey().name(), entry.getValue()));
                }
            } else if (noSuitability > 0) {
                lines.add(String.format("  §7%s§f: no matching suitabilities (%d jobs)", name, noSuitability));
            } else {
                lines.add(String.format("  §7%s§f: no jobs available", name));
            }
        }

        if (workers.all().stream().allMatch(w -> !w.idle || w.currentReservation != null)) {
            lines.add("  §7(all workers busy)");
        }

        return lines;
    }
}
