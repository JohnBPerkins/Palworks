package dev.palworks.job;

import dev.palworks.config.PalworksConfig;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * How far from where it is a worker works: a box {@link #horizontal()} blocks out each way and
 * {@link #vertical()} up and down (both set in {@code config/palworks.json}, see
 * {@link PalworksConfig}). Jobs are scanned for in this box around each worker, and a worker
 * is only given jobs inside its own box, so it doesn't head off to (or path-check, loading chunks
 * on the way) jobs found around some other, far-away worker.
 */
public final class WorkRange {
    /** How far (blocks) out each way round itself a worker works. */
    public static int horizontal() {
        return PalworksConfig.current().workRangeHorizontal();
    }

    /** How far (blocks) up and down from itself a worker works. */
    public static int vertical() {
        return PalworksConfig.current().workRangeVertical();
    }

    /** How many chunks out each way from a worker's own chunk its work range reaches. */
    public static int chunkRadius() {
        return (horizontal() + 15) / 16;
    }

    /**
     * How far (straight line) from its owner a party Pokémon takes jobs. Cobblemon teleports an
     * idle party Pokémon back to its owner once it's more than {@link #OWNER_LEASH} blocks away, so
     * a job further off would have it yanked back mid-walk. Kept inside that (the config can't set
     * more), so its owner can move about a bit while it works.
     */
    public static int ownerRange() {
        return PalworksConfig.current().ownerRange();
    }

    /** Cobblemon's teleport-back distance for party Pokémon (its pokemon_follows_owner behaviour). */
    public static final int OWNER_LEASH = 32;

    private WorkRange() {}

    /** Whether {@code pos} is within work range of a worker at {@code worker}. */
    public static boolean contains(@Nullable BlockPos worker, BlockPos pos) {
        return worker != null
                && Math.abs(worker.getX() - pos.getX()) <= horizontal()
                && Math.abs(worker.getZ() - pos.getZ()) <= horizontal()
                && Math.abs(worker.getY() - pos.getY()) <= vertical();
    }

    /** Whether a party Pokémon whose owner is at {@code owner} may take a job at {@code pos}. */
    public static boolean nearOwner(@Nullable BlockPos owner, BlockPos pos) {
        return owner != null && owner.distSqr(pos) <= (double) ownerRange() * ownerRange();
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
