package dev.palworks.job;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * How far from where it is a worker works: a box {@link #HORIZONTAL} blocks out each way and
 * {@link #VERTICAL} up and down. Jobs are scanned for in this box around each worker, and a worker
 * is only given jobs inside its own box, so it doesn't head off to (or path-check, loading chunks
 * on the way) jobs found around some other, far-away worker.
 */
public final class WorkRange {
    public static final int HORIZONTAL = 32;
    public static final int VERTICAL = 32;

    /**
     * How far (straight line) from its owner a party Pokémon takes jobs. Cobblemon teleports an
     * idle party Pokémon back to its owner once it's more than {@link #OWNER_LEASH} blocks away, so
     * a job further off would have it yanked back mid-walk. Kept well inside that, so its owner can
     * move about a bit while it works.
     */
    public static final int OWNER_RANGE = 24;
    /** Cobblemon's teleport-back distance for party Pokémon (its pokemon_follows_owner behaviour). */
    public static final int OWNER_LEASH = 32;

    private WorkRange() {}

    /** Whether {@code pos} is within work range of a worker at {@code worker}. */
    public static boolean contains(@Nullable BlockPos worker, BlockPos pos) {
        return worker != null
                && Math.abs(worker.getX() - pos.getX()) <= HORIZONTAL
                && Math.abs(worker.getZ() - pos.getZ()) <= HORIZONTAL
                && Math.abs(worker.getY() - pos.getY()) <= VERTICAL;
    }

    /** Whether a party Pokémon whose owner is at {@code owner} may take a job at {@code pos}. */
    public static boolean nearOwner(@Nullable BlockPos owner, BlockPos pos) {
        return owner != null && owner.distSqr(pos) <= (double) OWNER_RANGE * OWNER_RANGE;
    }

    /**
     * Whether a party Pokémon whose owner is at {@code owner} should keep going to a job it has at
     * {@code pos}: until it's past where Cobblemon would teleport it back. Looser than
     * {@link #nearOwner}, so its owner stepping about doesn't make it drop and retake a job.
     */
    public static boolean withinOwnerLeash(@Nullable BlockPos owner, BlockPos pos) {
        return owner != null && owner.distSqr(pos) <= (double) OWNER_LEASH * OWNER_LEASH;
    }
}
