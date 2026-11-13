package dev.palworks.hunger;

import com.cobblemon.mod.common.entity.PosableEntity;
import dev.palworks.block.TroughBlockEntity;
import dev.palworks.job.JobType;
import dev.palworks.reservation.ReservationId;
import dev.palworks.worker.WorkerState;
import java.util.List;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * What a worker eating from a trough looks like: it holds the food it's eating (in its main hand,
 * which Cobblemon draws on models that have a spot for an item), plays Cobblemon's {@code eat}
 * animation if its species has one (few do), and drops crumbs of the food as it chews.
 */
public final class EatingDisplay {
    /** Ticks between mouthfuls of crumbs. */
    private static final int CRUMB_INTERVAL = 10;

    private EatingDisplay() {}

    /**
     * The food this worker is eating right now, to show in its mouth: the last food put in the
     * trough, while it's at the trough eating. Empty otherwise.
     */
    public static ItemStack eating(Level level, WorkerState state) {
        ReservationId reservation = state.getCurrentReservation();
        if (reservation == null || reservation.getJobType() != JobType.EAT_FOOD || state.getStepTicks() <= 0) {
            return ItemStack.EMPTY;
        }
        return level.getBlockEntity(reservation.getStationPos()) instanceof TroughBlockEntity trough && trough.hasFeed()
                ? trough.lastFood()
                : ItemStack.EMPTY;
    }

    /** Animate a worker that's eating: its eat animation as it starts on a bite, crumbs as it chews. */
    public static void tick(Mob entity, Level level, WorkerState state) {
        ItemStack food = eating(level, state);
        if (food.isEmpty() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        int ticks = state.getStepTicks();
        if (ticks == 1 && entity instanceof PosableEntity posable) {
            // Only Munchlax, Wooloo and Dubwool have one: anything else ignores it
            posable.playAnimation("eat", List.of());
        }
        if (ticks % CRUMB_INTERVAL == CRUMB_INTERVAL / 2) {
            // In front of its face
            Vec3 mouth = entity.getEyePosition()
                    .add(entity.getLookAngle().scale(entity.getBbWidth() * 0.5))
                    .add(0, -0.1, 0);
            serverLevel.sendParticles(
                    new ItemParticleOption(ParticleTypes.ITEM, food.copyWithCount(1)),
                    mouth.x,
                    mouth.y,
                    mouth.z,
                    5,
                    0.1,
                    0.05,
                    0.1,
                    0.05);
        }
    }
}
