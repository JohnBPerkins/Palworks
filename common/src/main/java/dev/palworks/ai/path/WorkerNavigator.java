package dev.palworks.ai.path;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Walks workers to their jobs along {@link WorkerPathfinder}'s paths, and keeps them on those.
 *
 * <p>The path goes to the mob's own navigation to follow, which would otherwise swap it for one of
 * Cobblemon's: vanilla navigation recomputes its path to its target when a block changes near it
 * (a crop growing will do) or the mob drifts off it. So the navigation is given no target of its
 * own (its recomputing then does nothing), and every tick a worker walks, this checks it's still on
 * its path, puts it back if something else moved it, and rechecks the next few moves now and then,
 * so a block put in its way gets it a new path before it walks into it.
 *
 * <p>Server thread only, like the rest of the worker AI.
 */
public final class WorkerNavigator {
    private WorkerNavigator() {}

    /** How long a path planned when the job was handed out stays good to start walking. */
    private static final int OFFER_TICKS = 40;
    /** Least time between plans for the same tile, so a tile it can't get to isn't searched every tick. */
    private static final int REPLAN_TICKS = 20;
    /** How often a walking worker's next moves are rechecked. */
    private static final int RECHECK_TICKS = 10;
    /** How many moves ahead are rechecked. */
    private static final int RECHECK_MOVES = 4;
    /** How near the start of a path the worker must be to start walking it without planning again. */
    private static final double START_NEAR_SQ = 1.5 * 1.5;
    /** How near the next point of its path a worker must be to be put back on it. */
    private static final double NEAR_NEXT_SQ = 2.0 * 2.0;

    /** Each worker's walk: the tile it's going to, its path (null if it found none), when planned. */
    private static final class Walk {
        final BlockPos goal;

        @Nullable
        Path path;

        final long plannedAt;
        boolean started;

        Walk(BlockPos goal, @Nullable Path path, long plannedAt) {
            this.goal = goal;
            this.path = path;
            this.plannedAt = plannedAt;
        }
    }

    private static final Map<Mob, Walk> WALKS = new WeakHashMap<>();

    /**
     * Keep a path planned when the job was handed out (see {@code JobManager}), so the walk there
     * starts on it instead of planning the same one again.
     */
    public static void offer(Mob mob, BlockPos goal, Path path) {
        WALKS.put(mob, new Walk(goal.immutable(), path, mob.level().getGameTime()));
    }

    /**
     * Walk the mob to stand on {@code goal}, at this speed. Called every tick it's on its way; does
     * nothing while it's walking a good path there.
     */
    public static void walkTo(Mob mob, BlockPos goal, double speed) {
        PathNavigation navigation = mob.getNavigation();
        if (!WorkerPathfinder.handles(mob)) {
            // Flying or swimming: Cobblemon's navigation plans that
            WALKS.remove(mob);
            navigation.moveTo(goal.getX(), goal.getY(), goal.getZ(), speed);
            return;
        }
        long now = mob.level().getGameTime();
        Walk walk = WALKS.get(mob);
        if (walk != null && walk.goal.equals(goal)) {
            Path ours = walk.path;
            Path current = navigation.getPath();
            if (walk.started && ours != null && !ours.isDone()) {
                if (isWalking(current, ours)) {
                    // On its way. Now and then, make sure nothing has got in its way since.
                    if ((now + mob.getId()) % RECHECK_TICKS == 0
                            && !WorkerPathfinder.stillClear(mob, ours, RECHECK_MOVES)) {
                        plan(mob, goal, speed, now);
                    }
                    return;
                }
                if (current != null && nearNextNode(mob, ours)) {
                    // Something else gave its navigation another path: back onto this one
                    start(mob, walk, speed);
                    return;
                }
            }
            if (!walk.started && ours != null && now - walk.plannedAt <= OFFER_TICKS && startsNear(mob, ours)) {
                start(mob, walk, speed);
                return;
            }
            if (now - walk.plannedAt < REPLAN_TICKS) {
                // Just planned: it found no way, or its walk stopped short. Leave the worker's own
                // stuck detection to see that, rather than search again every tick.
                return;
            }
        }
        plan(mob, goal, speed, now);
    }

    /**
     * Whether the mob is walking to {@code goal} on one of these paths (or, while it's flying or
     * swimming, its navigation is headed there).
     */
    public static boolean isHeadingTo(Mob mob, BlockPos goal) {
        PathNavigation navigation = mob.getNavigation();
        Walk walk = WALKS.get(mob);
        if (walk != null && walk.goal.equals(goal) && walk.path != null && isWalking(navigation.getPath(), walk.path)) {
            return !walk.path.isDone();
        }
        return goal.equals(navigation.getTargetPos());
    }

    /** Forget the mob's walk (it's done with the job, or stopped). */
    public static void forget(Mob mob) {
        WALKS.remove(mob);
    }

    private static void plan(Mob mob, BlockPos goal, double speed, long now) {
        WorkerPathfinder.Result result = WorkerPathfinder.find(mob, goal);
        switch (result.reach()) {
            case YES -> start(mob, new Walk(goal.immutable(), result.path(), now), speed);
            case NO -> {
                mob.getNavigation().stop();
                WALKS.put(mob, new Walk(goal.immutable(), null, now));
            }
            case UNKNOWN -> {
                // In the air: try again once it's landed
            }
        }
    }

    private static void start(Mob mob, Walk walk, double speed) {
        PathNavigation navigation = mob.getNavigation();
        navigation.moveTo(walk.path, speed);
        // moveTo keeps the path it has if this one is the same, so follow whichever it's on
        walk.path = navigation.getPath();
        walk.started = true;
        // No target of its own, so it doesn't recompute this path into one of Cobblemon's
        navigation.targetPos = null;
        WALKS.put(mob, walk);
    }

    /**
     * Whether the navigation is following this very path object. Identity, not equality: it is
     * what tells us nothing has swapped the path (a path with the same nodes is a different walk if
     * it's from elsewhere; Path doesn't override equals anyway).
     */
    @SuppressWarnings("ReferenceEquality")
    private static boolean isWalking(@Nullable Path current, Path ours) {
        return current == ours;
    }

    /** Whether the mob is still about where this path has got it to, so it can carry on along it. */
    private static boolean nearNextNode(Mob mob, Path path) {
        Vec3 next = path.getNextEntityPos(mob);
        double dx = next.x - mob.getX();
        double dz = next.z - mob.getZ();
        return dx * dx + dz * dz <= NEAR_NEXT_SQ && Math.abs(next.y - mob.getY()) <= 2.0;
    }

    /** Whether the mob is near enough the start of this path to walk it from where it is. */
    private static boolean startsNear(Mob mob, Path path) {
        if (path.getNodeCount() == 0) {
            return false;
        }
        Vec3 first = path.getEntityPosAtNode(mob, 0);
        double dx = first.x - mob.getX();
        double dz = first.z - mob.getZ();
        return dx * dx + dz * dz <= START_NEAR_SQ && Math.abs(first.y - mob.getY()) <= 1.5;
    }
}
