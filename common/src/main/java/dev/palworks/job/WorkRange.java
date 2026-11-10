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

    private WorkRange() {}

    /** Whether {@code pos} is within work range of a worker at {@code worker}. */
    public static boolean contains(@Nullable BlockPos worker, BlockPos pos) {
        return worker != null
                && Math.abs(worker.getX() - pos.getX()) <= HORIZONTAL
                && Math.abs(worker.getZ() - pos.getZ()) <= HORIZONTAL
                && Math.abs(worker.getY() - pos.getY()) <= VERTICAL;
    }
}
