package dev.palworks.ai.path;

import com.cobblemon.mod.common.entity.OmniPathingEntity;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Flies workers that can fly (Cobblemon's {@link OmniPathingEntity#canFly}) to their jobs: a trip
 * further than {@link #FLY_DISTANCE}, one {@link #FLY_UP} or more higher up (onto a pillar, a
 * tree's leaves), or one there's no walking to, with open sky over both ends. It takes off straight up to a height clear of everything
 * on the way, flies straight across, comes down onto its tile and lands. No path to plan: the sky
 * above the tallest block on the way is clear by definition, which also makes a flight cheaper
 * than a walk. Under a roof, or with a ceiling on the way up, it walks.
 *
 * <p>Steered through its move control, which Cobblemon flies it by while it's flying. One that
 * hasn't got there in good time (something it can't see in the heightmap, like a glass roof it's
 * below, or being knocked about) lands and walks.
 *
 * <p>Server thread only, like the rest of the worker AI.
 */
public final class WorkerFlight {
    private WorkerFlight() {}

    /** Trips further than this (across, in blocks) are flown, not walked. */
    static final double FLY_DISTANCE = 8.0;
    /** Jobs this much higher up than its feet (in blocks) or more are flown to, however near. */
    private static final int FLY_UP = 2;
    /** How far above the tallest block on the way it flies. */
    private static final int CLEARANCE = 3;
    /** How near above its tile it is when it lands. */
    private static final double LANDING_HEIGHT = 0.6;
    /** How near its tile it is, across, when it comes down. */
    private static final double OVER_IT = 0.5;
    /** Allowance for a flight, on top of the time the distance takes at {@link #BLOCKS_PER_TICK}. */
    private static final int SPARE_TICKS = 100;
    /** The slowest a flight is expected to go (blocks a tick), for how long it's given. */
    private static final double BLOCKS_PER_TICK = 0.1;

    /** How many ticks climbing without getting higher end the climb (something's holding it back). */
    private static final int CLIMB_STALL_TICKS = 10;

    /** A flight under way: where to, how high, when it's given up on, and how its climb is going. */
    private static final class Flight {
        final BlockPos goal;
        double cruiseY;
        final long giveUpAt;
        double highestY = Double.NEGATIVE_INFINITY;
        int stalled;
        // It's as high as it flies here: it goes across at the highest it got, and doesn't climb again
        boolean topped;

        Flight(BlockPos goal, double cruiseY, long giveUpAt) {
            this.goal = goal;
            this.cruiseY = cruiseY;
            this.giveUpAt = giveUpAt;
        }
    }

    private static final Map<Mob, Flight> FLIGHTS = new WeakHashMap<>();
    // Goals a flight to was given up on (it walks there instead), until it heads somewhere else
    private static final Map<Mob, BlockPos> GROUNDED = new WeakHashMap<>();

    /** Whether this mob can fly at all, as Cobblemon has it. */
    public static boolean canFly(Mob mob) {
        return mob instanceof OmniPathingEntity pather && pather.canFly() && !mob.isPassenger();
    }

    /**
     * Whether {@code goal} (a tile to stand on) is a trip to fly rather than walk: far across, or
     * higher up than a step. No looking for a walk up there first: what goes up two blocks or more
     * so near is mostly no walk at all (a tree's top, a pillar), and finding that out searches its
     * whole budget.
     */
    public static boolean isWorthFlying(Mob mob, BlockPos goal) {
        double dx = goal.getX() + 0.5 - mob.getX();
        double dz = goal.getZ() + 0.5 - mob.getZ();
        return dx * dx + dz * dz > FLY_DISTANCE * FLY_DISTANCE || goal.getY() - feet(mob) >= FLY_UP;
    }

    /**
     * Where its feet are, rounded up: on farmland (or a slab) they're inside the top of the block it
     * stands on, which is the heightmap's block, not under it.
     */
    private static int feet(Mob mob) {
        return (int) Math.ceil(mob.getY() - 1.0e-3);
    }

    /**
     * Whether it can fly from where it is to stand on {@code goal}: it flies, nothing's over it or
     * the tile (it goes up and comes down straight), and it hasn't just given up flying there.
     */
    public static boolean canFlyTo(Mob mob, BlockPos goal) {
        if (!canFly(mob) || goal.equals(GROUNDED.get(mob))) {
            return false;
        }
        Level level = mob.level();
        return skyAbove(level, mob.getBlockX(), feet(mob), mob.getBlockZ())
                && skyAbove(level, goal.getX(), goal.getY(), goal.getZ());
    }

    /** Whether nothing that blocks movement is above height {@code y} at this column. */
    private static boolean skyAbove(Level level, int x, int y, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) <= y;
    }

    /** Whether it's flying to a job right now. */
    public static boolean isFlying(Mob mob) {
        return FLIGHTS.containsKey(mob);
    }

    /** Whether it's on its way to {@code goal} by air right now. */
    public static boolean isFlyingTo(Mob mob, BlockPos goal) {
        Flight flight = FLIGHTS.get(mob);
        return flight != null && flight.goal.equals(goal);
    }

    /**
     * Fly the mob a tick nearer standing on {@code goal}, taking off if it's not in the air yet.
     * Returns false once it's landed there, or has given up (it walks from there).
     */
    public static boolean flyTo(Mob mob, BlockPos goal, double speed) {
        if (!(mob instanceof OmniPathingEntity pather)) {
            return false;
        }
        long now = mob.level().getGameTime();
        Flight flight = FLIGHTS.get(mob);
        if (flight == null || !flight.goal.equals(goal)) {
            double cruiseY = cruiseHeight(mob, goal);
            flight = new Flight(goal.immutable(), cruiseY, now + allowance(mob, goal, cruiseY));
            FLIGHTS.put(mob, flight);
            mob.getNavigation().stop();
        }
        if (now > flight.giveUpAt) {
            GROUNDED.put(mob, flight.goal);
            land(mob);
            return false;
        }
        // Nothing else steers it meanwhile: Cobblemon's own AI gives it somewhere to walk now and then
        // (as WorkerNavigator sees off for a walk), and its navigation, following that, pulls it down
        if (mob.getNavigation().isInProgress()) {
            mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            mob.getNavigation().stop();
        }
        if (!pather.isFlying()) {
            pather.setFlying(true);
        }

        double goalX = goal.getX() + 0.5;
        double goalZ = goal.getZ() + 0.5;
        double dx = goalX - mob.getX();
        double dz = goalZ - mob.getZ();
        boolean overIt = dx * dx + dz * dz <= OVER_IT * OVER_IT;
        if (overIt) {
            if (mob.getY() - goal.getY() <= LANDING_HEIGHT) {
                land(mob);
                return false;
            }
            mob.getMoveControl().setWantedPosition(goalX, goal.getY(), goalZ, speed);
        } else if (!flight.topped && mob.getY() < flight.cruiseY - 1) {
            // Straight up first, clear of everything on the way. One that stops getting higher (held
            // back by something the heightmap doesn't show) goes across at the highest it got: not
            // where it's sunk to since, which lower each time would end up below what it's flying over
            flight.stalled = mob.getY() > flight.highestY + 0.05 ? 0 : flight.stalled + 1;
            flight.highestY = Math.max(flight.highestY, mob.getY());
            if (flight.stalled >= CLIMB_STALL_TICKS) {
                flight.cruiseY = flight.highestY;
                flight.topped = true;
            }
            mob.getMoveControl().setWantedPosition(mob.getX(), flight.cruiseY, mob.getZ(), speed);
        } else {
            mob.getMoveControl().setWantedPosition(goalX, flight.cruiseY, goalZ, speed);
        }
        return true;
    }

    /** Bring it down where it is (it's there, or it's done with flying there), and forget the flight. */
    public static void land(Mob mob) {
        FLIGHTS.remove(mob);
        if (mob instanceof OmniPathingEntity pather && pather.isFlying()) {
            pather.setFlying(false);
        }
    }

    /** Forget its flight and any goal it gave up flying to (it's done with the job). */
    public static void forget(Mob mob) {
        land(mob);
        GROUNDED.remove(mob);
    }

    /** How high to fly from where the mob is to {@code goal}: clear of every block on the straight way. */
    private static double cruiseHeight(Mob mob, BlockPos goal) {
        Level level = mob.level();
        double fromX = mob.getX();
        double fromZ = mob.getZ();
        double toX = goal.getX() + 0.5;
        double toZ = goal.getZ() + 0.5;
        int steps = (int) Math.ceil(Math.max(Math.abs(toX - fromX), Math.abs(toZ - fromZ))) + 1;
        int top = Math.max(mob.getBlockY(), goal.getY());
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.floor(fromX + (toX - fromX) * t);
            int z = (int) Math.floor(fromZ + (toZ - fromZ) * t);
            // Either side of the line too: its body is wider than a point
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + ox, z + oz));
                }
            }
        }
        return top + CLEARANCE;
    }

    /** How long a flight there is given: up, across and down, at the slowest it's expected to go. */
    private static long allowance(Mob mob, BlockPos goal, double cruiseY) {
        double across = Math.sqrt(mob.distanceToSqr(goal.getX() + 0.5, mob.getY(), goal.getZ() + 0.5));
        double upAndDown = 2.0 * Math.abs(cruiseY - mob.getY());
        return SPARE_TICKS + (long) ((across + upAndDown) / BLOCKS_PER_TICK);
    }
}
