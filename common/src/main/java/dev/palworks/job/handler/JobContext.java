package dev.palworks.job.handler;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.animation.WorkAnimations;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.reservation.ReservationId;
import dev.palworks.worker.WorkerState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

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

    /** The worker's species id, which its animations can be particular to. */
    String species() {
        return WorkAnimations.speciesOf(pokemon);
    }
    /**
     * The worker has done {@code units} of work: it uses up fullness for it (see
     * {@link dev.palworks.hunger.Hunger#didWork}). Only where the work really happened.
     */
    void didWork(int units) {
        dev.palworks.hunger.Hunger.didWork(pokemon, state, units);
    }

    /** Data steps leave for later steps (saved, and handed over with the job). */
    net.minecraft.nbt.CompoundTag data() {
        return state.getJobData();
    }
}
