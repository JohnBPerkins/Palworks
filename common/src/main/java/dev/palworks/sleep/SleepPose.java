package dev.palworks.sleep;

import com.cobblemon.mod.common.entity.PoseType;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

/**
 * A sleeping worker's body: lying in Cobblemon's sleep pose (if its species has one) and kept still,
 * so Cobblemon's wandering doesn't walk it off in its sleep, nor its look target turn it to watch a
 * player go by. Palworks holds the pose itself rather than through Cobblemon's sleep memory, which
 * Cobblemon's own tasks would wake it from.
 */
public final class SleepPose {
    // Which way each sleeper lay down facing (its body's yaw, its head's pitch), to keep it there.
    // Weak, so a sleeper that's gone (recalled, unloaded) is let go of; server thread only.
    private static final Map<PokemonEntity, float[]> LYING = new WeakHashMap<>();

    private SleepPose() {}

    /**
     * Each tick it sleeps: hold it still, facing the way it lay down, in its sleep pose if it has
     * one. Called after Cobblemon's brain has had its tick, so it undoes any turn its look took.
     */
    public static void hold(PokemonEntity entity) {
        entity.getNavigation().stop();
        entity.setDeltaMovement(0, entity.getDeltaMovement().y, 0);
        keepFacing(entity);
        if (!Sleep.hasSleepPose(entity)) {
            return; // no sleep animation: it lies still in its normal pose
        }
        if (entity.getEnablePoseTypeRecalculation()) {
            entity.setEnablePoseTypeRecalculation(false);
        }
        if (entity.getEntityData().get(PokemonEntity.getPOSE_TYPE()) != PoseType.SLEEP) {
            entity.getEntityData().set(PokemonEntity.getPOSE_TYPE(), PoseType.SLEEP);
        }
    }

    /** Facing the way it lay down, body and head, and not looking at anything. */
    private static void keepFacing(PokemonEntity entity) {
        float[] lying = LYING.computeIfAbsent(entity, e -> new float[] {e.yBodyRot, e.getXRot()});
        entity.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        entity.setYRot(lying[0]);
        entity.setYBodyRot(lying[0]);
        entity.setYHeadRot(lying[0]);
        entity.setXRot(lying[1]);
    }

    /** It wakes: Cobblemon works out its pose again, and it turns as it likes. */
    public static void wake(PokemonEntity entity) {
        LYING.remove(entity);
        if (!entity.getEnablePoseTypeRecalculation()) {
            entity.setEnablePoseTypeRecalculation(true);
        }
    }
}
