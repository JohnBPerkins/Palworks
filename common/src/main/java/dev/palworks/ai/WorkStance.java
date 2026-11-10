package dev.palworks.ai;

import net.minecraft.world.entity.Mob;

/**
 * How far from its work a worker stands, measured from the edge of its body rather than its
 * middle, so one stance suits every size of Pokémon: a melee swipe needs the body right up against
 * the block whether it's a Scizor or a Steelix, and a bigger one's middle simply ends up further
 * back. Sizes come from the hitbox, which Cobblemon already scales with the Pokémon's size.
 *
 * <p>The distance is a <em>ring</em>: the tiles a given number of blocks (Chebyshev) from the
 * target's tile, ring 1 touching it. A worker's ring is the one that leaves at most {@link #gap}
 * blocks between its body's edge and the target block, but never nearer than its body fits without
 * overlapping the block, and within {@link #minRing}..{@link #maxRing}. See {@link #ringFor}.
 *
 * @param gap     the most clear space, in blocks, between the worker's body and the target block
 * @param minRing the nearest ring it may stand on
 * @param maxRing the furthest ring it may stand on
 */
public record WorkStance(double gap, int minRing, int maxRing) {

    /**
     * Near the work, with room to spare: a block and a half between body and block, from ring 2
     * (never flush against it) to ring 4. Stations, crops and anything else that isn't a tree.
     */
    public static final WorkStance NEAR = new WorkStance(1.5, 2, 4);

    /** Right up against the work, as close as the body fits: a lumberjack at the trunk. */
    public static final WorkStance AGAINST = new WorkStance(0.0, 1, Integer.MAX_VALUE);

    public WorkStance {
        if (gap < 0) {
            throw new IllegalArgumentException("gap must not be negative: " + gap);
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
}
