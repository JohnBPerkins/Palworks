package dev.palworks.job.handler;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.advancement.WorkerAdvancements;
import dev.palworks.animation.WorkAnimations;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.reservation.ReservationId;
import dev.palworks.worker.WorkSpeed;
import dev.palworks.worker.WorkerState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Everything a {@link JobHandler} needs to act at the work site. */
public record JobContext(Pokemon pokemon, Mob entity, Level level, WorkerState state, ReservationId reservation) {
    BlockPos pos() {
        return reservation.getStationPos();
    }

    WorkerInventory inventory() {
        return WorkerInventoryManager.getOrCreate(pokemon);
    }

    String name() {
        return pokemon.getSpecies().getName();
    }

    /**
     * How fast it works, as a share of its usual speed (see WorkSpeed): its steps take that much
     * longer, and its animations play that much slower. Eating and sleeping too.
     */
    double pace() {
        return WorkSpeed.of(pokemon, state);
    }

    /** The worker's species id, which its animations can be particular to. */
    String species() {
        return WorkAnimations.speciesOf(pokemon);
    }
    /**
     * The worker has done {@code units} of work: it uses up fullness for it (see
     * {@link dev.palworks.hunger.Hunger#didWork}), and wears its mood down (see
     * {@link dev.palworks.mood.Mood#didWork}). Only where the work really happened.
     */
    void didWork(int units) {
        didWork(units, level.getBlockState(pos()), 1);
    }

    /**
     * {@link #didWork(int)}, for work that changed its block out of recognition (broke it): {@code
     * worked} is the block as it was, and {@code amount} how much of it there was (a tree's logs),
     * for its owner's advancements (see WorkerAdvancements).
     */
    void didWork(int units, BlockState worked, int amount) {
        dev.palworks.hunger.Hunger.didWork(pokemon, state, units);
        dev.palworks.mood.Mood.didWork(pokemon, state, units);
        WorkerAdvancements.jobDone(pokemon, level, reservation.getJobType(), pos(), worked, amount);
    }

    /**
     * The work also took {@code units} more out of the worker than its mood shows: fullness only
     * (see {@link dev.palworks.hunger.Hunger#didWork}), for work that's hungry work (raising a
     * boulder).
     */
    void usedFood(int units) {
        dev.palworks.hunger.Hunger.didWork(pokemon, state, units);
    }

    /** Data steps leave for later steps (saved, and handed over with the job). */
    net.minecraft.nbt.CompoundTag data() {
        return state.getJobData();
    }
}
