package dev.palworks.sleep;

import dev.palworks.ai.WorkPositions;
import dev.palworks.block.WorkerBedBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Which bed a worker can sleep in: one at least as wide as its body (a Pokémon up to 1 block
 * wide in any, up to 2 in a large or huge one, up to 3 in a huge one), with headroom over it. The
 * very widest (Avalugg, Regigigas: 4) sleep in a huge one if the space round it is clear too. Of the
 * beds it fits, it takes the smallest, so big beds are left for big Pokémon.
 */
public final class Beds {
    // A body a hair wider than the bed still lies in it
    private static final double SLACK = 0.05;

    private Beds() {}

    /**
     * Whether a body {@code width} blocks wide fits a bed {@code size} blocks a side: it's no wider,
     * or it's the biggest bed there is (its sleeper then also needs the space round it clear).
     */
    public static boolean bodyFits(double width, int size) {
        return width <= size + SLACK || size >= WorkerBedBlock.MAX_SIZE;
    }

    /** Whether this worker can sleep in the bed whose corner block is {@code origin}. */
    public static boolean fits(Level level, BlockPos origin, WorkerBedBlock bed, Mob worker) {
        if (!bodyFits(worker.getBbWidth(), bed.size())) {
            return false;
        }
        // Its body lying in the middle: everything it takes up must be clear (the bed itself has no
        // collision), for its whole height
        Vec3 center = bed.center(origin);
        double half = worker.getBbWidth() / 2.0;
        int minX = Mth.floor(center.x - half + 1e-3);
        int maxX = Mth.floor(center.x + half - 1e-3);
        int minZ = Mth.floor(center.z - half + 1e-3);
        int maxZ = Mth.floor(center.z + half - 1e-3);
        int headroom = WorkPositions.headroomFor(worker);
        BlockPos.MutableBlockPos space = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = 0; y < headroom; y++) {
                    space.set(x, origin.getY() + y, z);
                    if (!level.getBlockState(space)
                            .getCollisionShape(level, space)
                            .isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** The tile a worker walks to, to lie in this bed: the one its middle is on (the bed's middle, or by it). */
    public static BlockPos standTile(BlockPos origin, WorkerBedBlock bed) {
        Vec3 center = bed.center(origin);
        return BlockPos.containing(center.x, origin.getY(), center.z);
    }
}
