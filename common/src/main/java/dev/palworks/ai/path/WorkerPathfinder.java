package dev.palworks.ai.path;

import com.cobblemon.mod.common.entity.OmniPathingEntity;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import org.jetbrains.annotations.Nullable;

/**
 * Plans a worker's walk to the tile it works from, checking its whole body all the way: the real
 * size of its hitbox against the blocks' real collision shapes, how high it can jump and how far it
 * can drop, room over its head when it jumps, and no cutting corners (see {@link BodyClearance}).
 * Cobblemon's own pathfinder checks bodies by whole blocks from the corner of their hitbox, skips
 * the headroom for jumps by anything a block wide or more, and doesn't walk-check Pokémon about
 * 1.6 blocks big at all, so workers were sent along routes they didn't fit.
 *
 * <p>An A* search over walking positions, bounded to the area round the worker and its tile and to
 * a budget of positions, so an unreachable job costs a bounded amount. The result is a vanilla
 * {@link Path}, which the mob's own navigation then follows (and Cobblemon's move control walks
 * and jumps along). Walking only: a Pokémon flying or swimming is left to Cobblemon ({@link
 * #handles}).
 */
public final class WorkerPathfinder {
    private WorkerPathfinder() {}

    /**
     * How far past the worker and its tile the search may go round obstacles, across, for a body
     * up to a block wide. A wider one gets its width again on top: going round something, its middle
     * keeps half its width further out on each side, so a Wailord-sized worker needs a wider berth
     * than a Bulbasaur to get round the same wall. From each Pokémon's own hitbox, so it follows
     * Cobblemon's per-Pokémon sizes.
     */
    private static final int MARGIN = 8;
    /** How far above and below them it may go. */
    private static final int VERTICAL_MARGIN = 6;
    /** Most positions it looks on from before calling the tile unreachable. */
    static final int MAX_EXPANDED = 1500;
    /**
     * The same three, for a trip to a trough or a bed: wider, as it can be from right across a
     * pasture, round ponds, buildings and hills; and rarer than walking to work, so it can cost more.
     */
    private static final int FAR_MARGIN = 24;

    private static final int FAR_VERTICAL_MARGIN = 16;
    static final int FAR_MAX_EXPANDED = 6000;
    /** Extra cost of a jump up, over its distance: slower than walking, and riskier. */
    private static final double JUMP_COST = 0.5;
    /** Extra cost per block dropped. */
    private static final double DROP_COST = 0.25;
    /** How far below a worker that's in the air it looks for the ground it's about to land on. */
    private static final int LANDING_SEARCH = 3;
    /** How far off its tile's middle a path may end, across: a node's middle, which for a mob 1 to 2 blocks wide is a block's corner. */
    private static final double GOAL_SLACK = 0.5 + 1.0e-3;

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    /**
     * From the start, its own node too: the search starts where the mob really is, which for one 1
     * to 2 blocks wide (steered by block corners) is half a block off its node.
     */
    private static final int[][] FROM_START = {
        {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /** Whether a path reaches a spot: yes, no, or unknown (no path could be planned right now). */
    public enum Reach {
        YES,
        NO,
        UNKNOWN
    }

    /** A planned walk: whether it reaches the tile, and the path there if it does. */
    public record Result(Reach reach, @Nullable Path path) {
        static final Result NO = new Result(Reach.NO, null);
        static final Result UNKNOWN = new Result(Reach.UNKNOWN, null);
    }

    /**
     * Whether this pathfinder plans this mob's walks: it's on its feet (or about to be), not flying
     * or swimming. Anything else is left to its own navigation.
     */
    public static boolean handles(Mob mob) {
        if (mob instanceof OmniPathingEntity pather) {
            if (!pather.canWalk() || pather.isFlying()) {
                return false;
            }
            if (mob.isInWater() && pather.canSwimInWater() && !mob.onGround()) {
                return false;
            }
        }
        return !mob.isPassenger();
    }

    /**
     * Plan the mob's walk to stand on {@code standPos} (a tile, as {@code WorkPositions} picks
     * them: the block its feet go in). {@link Reach#UNKNOWN} when it's in the air with no ground
     * close below it (just sent out, falling): that says nothing about the tile.
     */
    public static Result find(Mob mob, BlockPos standPos) {
        return find(mob, standPos, false);
    }

    /**
     * {@link #find(Mob, BlockPos)}, searching further round if it's a {@code farTrip} (to a trough
     * or a bed: see {@link #FAR_MARGIN}).
     */
    public static Result find(Mob mob, BlockPos standPos, boolean farTrip) {
        BodyClearance body = new BodyClearance(mob);

        // Where it's going: its body on that tile, on whatever floor is there
        double goalX = standPos.getX() + 0.5;
        double goalZ = standPos.getZ() + 0.5;
        double goalFloor = body.floorAt(goalX, standPos.getY(), goalZ);
        if (Double.isNaN(goalFloor) || !body.fits(goalX, goalFloor, goalZ)) {
            return Result.NO;
        }
        int goalRow = BodyClearance.row(goalFloor);

        // Where it is
        double startFloor = startFloor(body, mob);
        if (Double.isNaN(startFloor)) {
            return Result.UNKNOWN;
        }
        Step start = new Step(
                body.node(mob.getX()), BodyClearance.row(startFloor), body.node(mob.getZ()), startFloor, 0, null);

        int margin = (farTrip ? FAR_MARGIN : MARGIN) + (int) Math.ceil(body.halfWidth * 2);
        int verticalMargin = farTrip ? FAR_VERTICAL_MARGIN : VERTICAL_MARGIN;
        Bounds bounds = new Bounds(start, body.node(goalX), goalRow, body.node(goalZ), margin, verticalMargin);
        int maxExpanded = farTrip ? FAR_MAX_EXPANDED : MAX_EXPANDED;
        Step reached = search(body, mob, start, bounds, maxExpanded, goalX, goalFloor, goalRow, goalZ);
        if (reached == null) {
            return Result.NO;
        }
        return new Result(Reach.YES, toPath(reached, standPos));
    }

    /**
     * Whether the body could still make the next few moves of a path it's on: rechecked as it
     * walks, so a block put in its way (a door shut, a fence placed) gets it a new path before it
     * walks into it.
     */
    public static boolean stillClear(Mob mob, Path path, int ahead) {
        BodyClearance body = new BodyClearance(mob);
        int from = Math.max(0, path.getNextNodeIndex() - 1);
        int to = Math.min(path.getNodeCount() - 1, from + ahead);
        for (int i = from; i < to; i++) {
            Node a = path.getNode(i);
            Node b = path.getNode(i + 1);
            double fa = body.floorAtNode(a.x, a.y, a.z);
            double fb = body.floorAtNode(b.x, b.y, b.z);
            if (Double.isNaN(fa) || Double.isNaN(fb)) {
                return false;
            }
            if (!body.canMove(body.centre(a.x), fa, body.centre(a.z), body.centre(b.x), fb, body.centre(b.z))) {
                return false;
            }
        }
        return true;
    }

    /** The floor under the mob now, or the ground it's about to land on; NaN if none is close. */
    private static double startFloor(BodyClearance body, Mob mob) {
        if (mob.onGround()) {
            return mob.getY();
        }
        int row = BodyClearance.row(mob.getY());
        for (int y = row; y >= row - LANDING_SEARCH; y--) {
            double floor = body.floorAt(mob.getX(), y, mob.getZ());
            if (!Double.isNaN(floor) && floor <= mob.getY() + 1.0e-3) {
                return floor;
            }
        }
        return Double.NaN;
    }

    // ============================================================
    // The search
    // ============================================================

    /** A position reached in the search, and how: a node, the floor it stands on there, its cost. */
    private static final class Step {
        final int x;
        final int y;
        final int z;
        final double floor;
        final double cost;
        final float malus;

        @Nullable
        final Step from;

        double estimate;
        boolean done;

        Step(int x, int y, int z, double floor, float malus, @Nullable Step from) {
            this(x, y, z, floor, malus, from, 0);
        }

        Step(int x, int y, int z, double floor, float malus, @Nullable Step from, double cost) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.floor = floor;
            this.malus = malus;
            this.from = from;
            this.cost = cost;
        }

        long key() {
            return BlockPos.asLong(x, y, z);
        }
    }

    /** The box of nodes the search stays in. */
    private record Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        Bounds(Step start, int goalX, int goalY, int goalZ, int margin, int verticalMargin) {
            this(
                    Math.min(start.x, goalX) - margin,
                    Math.max(start.x, goalX) + margin,
                    Math.min(start.y, goalY) - verticalMargin,
                    Math.max(start.y, goalY) + verticalMargin,
                    Math.min(start.z, goalZ) - margin,
                    Math.max(start.z, goalZ) + margin);
        }

        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    @Nullable
    private static Step search(
            BodyClearance body,
            Mob mob,
            Step start,
            Bounds bounds,
            int maxExpanded,
            double goalX,
            double goalFloor,
            int goalRow,
            double goalZ) {
        Long2ObjectMap<Step> best = new Long2ObjectOpenHashMap<>();
        Queue<Step> open = new PriorityQueue<>((a, b) -> Double.compare(a.estimate, b.estimate));
        // The start is where the mob is, not a node, so it isn't one of the nodes it can go to
        start.estimate = heuristic(body, start, goalX, goalZ);
        open.add(start);

        int expanded = 0;
        while (!open.isEmpty() && expanded < maxExpanded) {
            Step step = open.poll();
            if (step.done || (step.from != null && best.get(step.key()) != step)) {
                continue; // superseded by a cheaper way here
            }
            step.done = true;
            expanded++;

            // Where it is (for the start, the mob's real position, not its node's middle)
            double atX = step.from == null ? mob.getX() : body.centre(step.x);
            double atZ = step.from == null ? mob.getZ() : body.centre(step.z);
            if (isGoal(body, step, atX, atZ, goalX, goalFloor, goalRow, goalZ)) {
                return step;
            }

            for (int[] d : step.from == null ? FROM_START : DIRECTIONS) {
                Step next = move(body, step, atX, atZ, d[0], d[1], bounds);
                if (next == null) {
                    continue;
                }
                Step known = best.get(next.key());
                if (known != null && (known.done || known.cost <= next.cost)) {
                    continue;
                }
                next.estimate = next.cost + heuristic(body, next, goalX, goalZ);
                best.put(next.key(), next);
                open.add(next);
            }
        }
        return null;
    }

    /**
     * Whether a step ends the walk: its middle is on the tile (or, for a mob 1 to 2 wide, on one of
     * the tile's corners), at the tile's height, and the body can go straight on from there to the
     * tile's middle, which the worker steps the last bit to.
     */
    private static boolean isGoal(
            BodyClearance body,
            Step step,
            double atX,
            double atZ,
            double goalX,
            double goalFloor,
            int goalRow,
            double goalZ) {
        if (step.y != goalRow) {
            return false;
        }
        double offX = Math.abs(atX - goalX);
        double offZ = Math.abs(atZ - goalZ);
        if (offX > GOAL_SLACK || offZ > GOAL_SLACK) {
            return false;
        }
        return (offX < 1.0e-6 && offZ < 1.0e-6) || body.canMove(atX, step.floor, atZ, goalX, goalFloor, goalZ);
    }

    /**
     * The step from {@code step} one node over (dx, dz), at whatever height the body ends up at
     * there: up onto what's in the way if it can jump onto it, level, or down off an edge as far as
     * it can drop. Null if it can't go that way.
     */
    @Nullable
    private static Step move(BodyClearance body, Step step, double atX, double atZ, int dx, int dz, Bounds bounds) {
        int x = step.x + dx;
        int z = step.z + dz;
        if (x < bounds.minX() || x > bounds.maxX() || z < bounds.minZ() || z > bounds.maxZ()) {
            return null;
        }
        double toX = body.centre(x);
        double toZ = body.centre(z);

        // Something to step up onto: then it's that or nothing (it's in the way at this level)
        int up = step.y + 1;
        double floorUp = body.floorAtNode(x, up, z);
        if (!Double.isNaN(floorUp)) {
            return arrive(body, step, atX, atZ, x, up, z, floorUp, bounds);
        }
        double floorLevel = body.floorAtNode(x, step.y, z);
        if (!Double.isNaN(floorLevel)) {
            return arrive(body, step, atX, atZ, x, step.y, z, floorLevel, bounds);
        }
        // Nothing to stand on at this height: down to the first floor below, if it's not too far
        for (int y = step.y - 1; y >= step.y - body.maxDrop - 1; y--) {
            double floor = body.floorAtNode(x, y, z);
            if (!Double.isNaN(floor)) {
                return arrive(body, step, atX, atZ, x, y, z, floor, bounds);
            }
            if (!body.clear(body.box(toX, y, toZ))) {
                return null; // no floor yet, but something in the way of dropping
            }
        }
        return null;
    }

    @Nullable
    private static Step arrive(
            BodyClearance body, Step step, double atX, double atZ, int x, int y, int z, double floor, Bounds bounds) {
        if (!bounds.contains(x, y, z)) {
            return null;
        }
        double toX = body.centre(x);
        double toZ = body.centre(z);
        if (!body.fits(toX, floor, toZ)) {
            return null;
        }
        float malus = body.malusAt(x, y, z);
        if (BodyClearance.forbidden(malus)) {
            return null;
        }
        if (!body.canMove(atX, step.floor, atZ, toX, floor, toZ)) {
            return null;
        }
        double distance = Math.hypot(toX - atX, toZ - atZ);
        double rise = floor - step.floor;
        double cost = step.cost + distance + malus + (rise > 1.0e-3 ? JUMP_COST : 0) + Math.max(0, -rise) * DROP_COST;
        return new Step(x, y, z, floor, malus, step, cost);
    }

    /** Octile distance across to the tile: never more than the cheapest walk there. */
    private static double heuristic(BodyClearance body, Step step, double goalX, double goalZ) {
        double dx = Math.abs(body.centre(step.x) - goalX);
        double dz = Math.abs(body.centre(step.z) - goalZ);
        return Math.max(dx, dz) + (Math.sqrt(2) - 1) * Math.min(dx, dz);
    }

    /** The walk to {@code reached}, as a path the mob's navigation can follow. */
    private static Path toPath(Step reached, BlockPos standPos) {
        List<Node> nodes = new ArrayList<>();
        // From where the mob is: its start isn't a node it's steered to, unless it's already there
        for (Step step = reached; step != null && (step.from != null || nodes.isEmpty()); step = step.from) {
            Node node = new Node(step.x, step.y, step.z);
            // Walkable throughout: Cobblemon's navigation takes an "open" node to mean flying
            node.type = PathType.WALKABLE;
            node.costMalus = step.malus;
            nodes.add(node);
        }
        Collections.reverse(nodes);
        return new Path(nodes, standPos, true);
    }
}
