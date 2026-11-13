package dev.palworks.sleep;

import com.cobblemon.mod.common.entity.PoseType;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;

/**
 * A sleeping worker's body: lying in Cobblemon's sleep pose (if its species has one) and kept still,
 * so Cobblemon's wandering doesn't walk it off in its sleep. Palworks holds the pose itself rather
 * than through Cobblemon's sleep memory, which Cobblemon's own tasks would wake it from.
 */
public final class SleepPose {
    private SleepPose() {}

    /** Each tick it sleeps: hold it still, in its sleep pose if it has one. */
    public static void hold(PokemonEntity entity) {
        entity.getNavigation().stop();
        entity.setDeltaMovement(0, entity.getDeltaMovement().y, 0);
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

    /** It wakes: Cobblemon works out its pose again. */
    public static void wake(PokemonEntity entity) {
        if (!entity.getEnablePoseTypeRecalculation()) {
            entity.setEnablePoseTypeRecalculation(true);
        }
    }
}
