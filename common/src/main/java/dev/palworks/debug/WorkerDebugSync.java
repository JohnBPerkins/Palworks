package dev.palworks.debug;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.ai.WorkerAI;
import dev.palworks.animation.WorkAnimations;
import dev.palworks.hunger.Hunger;
import dev.palworks.job.JobManager;
import dev.palworks.job.JobType;
import dev.palworks.job.handler.JobHandlerRegistry;
import dev.palworks.net.packets.WorkerDebugPacket;
import dev.palworks.reservation.ReservationId;
import dev.palworks.sleep.Sleep;
import dev.palworks.worker.WorkActivity;
import dev.palworks.worker.WorkActivitySync;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Sends players with {@code /palworks debug} on what every worker near them is doing
 * ({@link WorkerDebugPacket}), for the in-world overlay: its job, path, how long it has had the job
 * and how long until it gives it up, the step it's on, whether its path reaches the job, recent
 * path failures, how long it has stood still while meant to be walking, and the spots it remembers
 * it couldn't reach. Debug only: nothing is sent to anyone else. Ticked by {@code JobManager.tick}
 * for each level.
 */
public final class WorkerDebugSync {
    private static final int INTERVAL_TICKS = 5;
    private static final double RADIUS = 48;
    private static final int MAX_PATH_NODES = 64;
    // Moving less than this between samples counts as standing still
    private static final double STILL_DIST_SQ = 0.05 * 0.05;
    private static final long FORGET_STILL_AFTER_TICKS = 100;

    /** Players who had debugging on at the last send, so turning it off clears their overlay once. */
    private static final Set<UUID> SENT_TO = ConcurrentHashMap.newKeySet();

    /** Where a walking worker was at the last sample, and how long it has stood still. */
    private record Still(Vec3 position, long sampledAt, int ticks) {}

    private static final Map<UUID, Still> STILL = new ConcurrentHashMap<>();

    private WorkerDebugSync() {}

    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (now % INTERVAL_TICKS != 0) {
            return;
        }
        STILL.values().removeIf(still -> now - still.sampledAt() > FORGET_STILL_AFTER_TICKS);
        for (ServerPlayer player : level.players()) {
            UUID uuid = player.getUUID();
            if (DebugManager.isDebugging(uuid)) {
                new WorkerDebugPacket(entriesIn(level, player.getBoundingBox().inflate(RADIUS))).sendToPlayer(player);
                SENT_TO.add(uuid);
            } else if (SENT_TO.remove(uuid)) {
                new WorkerDebugPacket(List.of()).sendToPlayer(player);
            }
        }
    }

    /** What the overlay shows for every worker in this area. */
    public static List<WorkerDebugPacket.Entry> entriesIn(ServerLevel level, AABB area) {
        long now = level.getGameTime();
        JobManager jobs = JobManager.getInstance(level);
        List<WorkerDebugPacket.Entry> entries = new ArrayList<>();
        for (PokemonEntity entity : level.getEntitiesOfClass(PokemonEntity.class, area)) {
            Pokemon pokemon = entity.getPokemon();
            if (pokemon == null || pokemon.getOwnerUUID() == null) {
                continue; // wild Pokemon don't work
            }
            WorkerState state = WorkerStateManager.get(pokemon);
            if (state == null) {
                continue; // hasn't ticked as a worker yet
            }
            ReservationId reservation = state.getCurrentReservation();
            WorkActivity activity = WorkActivitySync.activityOf(entity, state);
            Path path = entity.getNavigation().getPath();
            boolean walking = path != null && !path.isDone();

            entries.add(new WorkerDebugPacket.Entry(
                    entity.getId(),
                    activity,
                    reservation == null ? null : reservation.getStationPos(),
                    walking ? remainingPath(path) : List.of(),
                    details(entity, state, reservation, activity, path, walking, now),
                    jobs.unreachableFor(pokemon.getUuid(), now)));
        }
        return entries;
    }

    private static WorkerDebugPacket.Details details(
            PokemonEntity entity,
            WorkerState state,
            ReservationId reservation,
            WorkActivity activity,
            Path path,
            boolean walking,
            long now) {
        int jobAge = -1;
        int expiresIn = -1;
        if (reservation != null) {
            jobAge = (int) Math.max(0, now - reservation.getTimestamp());
            // Only a job it hasn't started yet expires (a paused one is kept fresh): see WorkerAI.tick
            if (activity.phase() == WorkActivity.Phase.WALKING) {
                expiresIn = (int) Math.max(0, WorkerAI.RESERVATION_TIMEOUT_TICKS - jobAge);
            }
        }

        String stepName = "";
        int stepIndex = 0;
        int stepCount = 0;
        int stepLength = 0;
        if (reservation != null && state.isWorking()) {
            JobHandlerRegistry.StepView step = JobHandlerRegistry.stepView(
                    reservation.getJobType(), state.getJobStep(), WorkAnimations.speciesOf(entity));
            if (step != null) {
                stepName = step.name();
                stepIndex = step.index();
                stepCount = step.count();
                stepLength = step.ticks();
            }
        }

        WorkerDebugPacket.PathStatus pathStatus = !walking
                ? WorkerDebugPacket.PathStatus.NONE
                : path.canReach() ? WorkerDebugPacket.PathStatus.REACHES : WorkerDebugPacket.PathStatus.PARTIAL;

        return new WorkerDebugPacket.Details(
                jobAge,
                expiresIn,
                stepName,
                stepIndex,
                stepCount,
                state.getStepTicks(),
                stepLength,
                pathStatus,
                state.getRecentPathFailCount(),
                state.getStepInTicks(),
                stillTicks(entity, activity, now),
                fullness(entity.getPokemon(), state),
                rest(entity, state, reservation));
    }

    private static WorkerDebugPacket.Rest rest(PokemonEntity entity, WorkerState state, ReservationId reservation) {
        boolean sleeps = Sleep.appliesTo(entity.getPokemon(), state);
        return new WorkerDebugPacket.Rest(
                sleeps,
                sleeps && Sleep.isBedtime(entity),
                state.isAsleep(),
                state.isAsleep() && reservation != null && reservation.getJobType() == JobType.SLEEP,
                state.isTired());
    }

    private static WorkerDebugPacket.Fullness fullness(Pokemon pokemon, WorkerState state) {
        return new WorkerDebugPacket.Fullness(
                pokemon.getCurrentFullness(),
                pokemon.getMaxFullness(),
                Hunger.troughCap(pokemon),
                (int) Math.round(state.getFullnessOwed() * 100),
                Hunger.appliesTo(pokemon, state));
    }

    /**
     * How long a worker that's meant to be walking to its job has stood still, from samples taken
     * when the overlay is sent (so only while someone has it on).
     */
    private static int stillTicks(PokemonEntity entity, WorkActivity activity, long now) {
        UUID uuid = entity.getUUID();
        if (activity.phase() != WorkActivity.Phase.WALKING) {
            STILL.remove(uuid);
            return 0;
        }
        Vec3 position = entity.position();
        Still last = STILL.get(uuid);
        if (last == null) {
            STILL.put(uuid, new Still(position, now, 0));
            return 0;
        }
        if (last.sampledAt() == now) {
            return last.ticks(); // already sampled this tick (another player's overlay)
        }
        int ticks = position.distanceToSqr(last.position()) < STILL_DIST_SQ
                ? last.ticks() + (int) (now - last.sampledAt())
                : 0;
        STILL.put(uuid, new Still(position, now, ticks));
        return ticks;
    }

    private static List<BlockPos> remainingPath(Path path) {
        List<BlockPos> nodes = new ArrayList<>();
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount() && nodes.size() < MAX_PATH_NODES; i++) {
            nodes.add(path.getNodePos(i));
        }
        return nodes;
    }

    /** Drop everything (server stop). */
    public static void clear() {
        SENT_TO.clear();
        STILL.clear();
    }
}
