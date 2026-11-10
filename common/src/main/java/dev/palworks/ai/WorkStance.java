package dev.palworks.ai;

import net.minecraft.world.entity.Mob;

/**
 * Where a worker stands to do a step of its work, measured from the edge of its body rather than
 * its middle, so one stance suits every size of Pokémon: a melee swipe needs the body right up
 * against the block whether it's a Scizor or a Steelix, and a bigger one's middle simply ends up
 * further back. Sizes come from the hitbox, which Cobblemon already scales with the Pokémon's size.
 * A job's stance comes from its animation (see {@code WorkAnimation}), so it can differ per species.
 *
 * <p>Where it heads for is a <em>ring</em>: the tiles a given number of blocks (Chebyshev) from the
 * target's tile, ring 1 touching it. A worker's ring is the one that leaves at most {@link #gap}
 * blocks between its body's edge and the target block, but never nearer than its body fits without
 * overlapping the block, and within {@link #minRing}..{@link #maxRing}. See {@link #ringFor}.
 *
 * <p>Once it's there, or its walk ends near there, it's in its stance as long as its body is no
 * more than {@link #slack} further from the block than it would be standing in the middle of a
 * tile on that ring (see {@link #maxGap}): no fidgeting to hit an exact tile.
 *
 * @param gap     the most clear space, in blocks, between the worker's body and the target block
 * @param minRing the nearest ring it may stand on
 * @param maxRing the furthest ring it may stand on
 * @param slack   how much further than its ring's middle, in blocks, still counts as in its stance
 */
public record WorkStance(double gap, int minRing, int maxRing, double slack) {

    /**
     * Near the work, with room to spare, for a ranged move or reaching into a station: a block and
     * a half between body and block, from ring 2 (never flush against it) to ring 4. Anywhere within
     * two blocks of that will do.
     */
    public static final WorkStance NEAR = new WorkStance(1.5, 2, 4, 2.0);

    /**
     * Right up against the work, as close as the body fits, for a contact move: a lumberjack at the
     * trunk. Only just off the middle of its tile counts (crossing into the next tile out doesn't).
     */
    public static final WorkStance AGAINST = new WorkStance(0.0, 1, Integer.MAX_VALUE, 0.35);

    public WorkStance {
        if (gap < 0 || slack < 0) {
            throw new IllegalArgumentException("gap and slack must not be negative: " + gap + ", " + slack);
        }
        if (minRing < 1 || maxRing < minRing) {
            throw new IllegalArgumentException("rings must be 1 <= minRing <= maxRing: " + minRing + ".." + maxRing);
        }
    }

    /** The ring this worker stands on: see {@link #ringFor(double)}. */
    public int ringFor(Mob worker) {
        return ringFor(worker.getBbWidth() / 2.0);
    }

    /**
     * The ring a worker this wide stands on. Standing on ring {@code d}, its middle is {@code d}
     * blocks from the target's middle, so its edge is {@code d - halfWidth - 0.5} from the target
     * block's edge: it fits without overlapping the block from ring {@code ceil(halfWidth + 0.5)},
     * and the gap stays within {@link #gap} up to ring {@code floor(gap + halfWidth + 0.5)}. The
     * furthest of those (a ranged stance keeps its distance), at least the nearest it fits on.
     *
     * @param halfWidth half the worker's hitbox width, in blocks
     */
    public int ringFor(double halfWidth) {
        int fits = (int) Math.ceil(halfWidth + 0.5 - 1e-3); // 1e-3: a body exactly a ring wide fits it
        int withinGap = (int) Math.floor(gap + halfWidth + 0.5);
        return Math.max(minRing, Math.min(maxRing, Math.max(fits, withinGap)));
    }

    /**
     * The most space there can be between a worker this wide and the target block (across, the
     * larger of the x and z gaps) for it to be in this stance: what there is in the middle of its
     * ring's tiles, plus {@link #slack}.
     *
     * @param halfWidth half the worker's hitbox width, in blocks
     */
    public double maxGap(double halfWidth) {
        return Math.max(0.0, ringFor(halfWidth) - 0.5 - halfWidth) + slack;
    }

    /**
     * Whether a worker has to be on its tile itself to be in this stance, not just near it: when
     * the slack doesn't stretch to the next tile.
     */
    public boolean needsItsTile() {
        return slack < 1.0;
    }

    /** Whether there's little enough space between this worker and its target block: see {@link #maxGap}. */
    public boolean reaches(double halfWidth, double gapNow) {
        return gapNow <= maxGap(halfWidth) + 1e-6;
    }
}
