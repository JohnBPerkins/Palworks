package dev.palworks.ai.path;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * The blocks that hurt a mob that touches them (a cactus, a sweet berry bush, fire, a lit campfire,
 * magma underfoot). Which blocks those are is Minecraft's own pathfinding's say, so a mod's blocks
 * count where the mod says so.
 *
 * <p>A worker's walk to its job is planned by {@link WorkerPathfinder}, which keeps its whole body
 * out of them all the way ({@link BodyClearance#hurtsIn}).
 */
public final class HurtingBlocks {
    private HurtingBlocks() {}

    /**
     * Whether a block Minecraft's pathfinding calls this hurts the mob when it's in it (or, for the
     * block it's standing on, on it): damage, or fire unless fire doesn't hurt this mob.
     */
    static boolean hurts(PathType type, Mob mob) {
        return type == PathType.DAMAGE_OTHER || (type == PathType.DAMAGE_FIRE && !mob.fireImmune());
    }
}
