package dev.palworks.sleep;

import com.cobblemon.mod.common.CobblemonMemories;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.entity.pokemon.ai.sensors.DrowsySensor;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.ai.path.WorkerNavigator;
import dev.palworks.block.WorkerBedBlock;
import dev.palworks.config.PalworksConfig;
import dev.palworks.job.JobManager;
import dev.palworks.job.JobManager.NonWorkerBed;
import dev.palworks.worker.WorkerEligibility;
import dev.palworks.worker.WorkerStateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.phys.Vec3;

/**
 * A pastured Pokémon that isn't working (no work skills, or its work switched off) sleeps in a bed if
 * one's free. Its
 * sleep stays Cobblemon's: the moment Cobblemon makes it drowsy ({@link #onDrowsinessChecked}), it
 * takes a free bed by its pasture (JobManager's non-worker beds: none a worker has) and walks there,
 * as a worker does ({@link WorkerNavigator}: Cobblemon's own walking can't get onto a bed). On the
 * way it isn't drowsy, as far as Cobblemon is concerned, or Cobblemon would put it to sleep on the
 * spot (it does that in the same brain tick it makes it drowsy). In the middle of the bed it's
 * drowsy again, and Cobblemon puts it to sleep there, and wakes it, as anywhere else. No bed free,
 * or it can't get to one: it sleeps where Cobblemon has it. It never gets Tired: it doesn't work.
 * Pastured workers sleep by Palworks' own schedule instead ({@link Sleep}); party Pokémon are left to
 * Cobblemon (it never makes one sent out from the party drowsy).
 */
public final class NonWorkerSleep {
    // How near the bed's middle counts as there (as for a worker: WorkPositions' ON_TILE_DIST)
    private static final double ARRIVED = 0.35;
    // Its walk speed to bed (a worker's)
    private static final double WALK_SPEED = 0.6;
    // The longest it may take to get to its bed before it gives up and sleeps where it is (30s)
    private static final long WALK_TICKS = 600;
    // How long, in its bed, for Cobblemon to put it to sleep: Cobblemon's own search for a resting
    // place waits that long (its path cooldown), so it isn't walked off somewhere else meanwhile
    private static final long SETTLE_TICKS = 40;
    // How many times /palworks sleep runs Cobblemon's drowsiness check (a chance of 1 in 300 or so
    // each, for the sleepiest species 1 in 30) before saying it won't
    private static final int MAKE_DROWSY_TRIES = 100_000;

    private NonWorkerSleep() {}

    /**
     * Whether this goes for it: sleep on, owned, pastured (not from a party: as for {@link
     * Sleep#appliesTo}), and not working ({@link WorkerEligibility#works}: no work it can do, or its
     * work switched off).
     */
    public static boolean appliesTo(Pokemon pokemon) {
        return PalworksConfig.current().sleep().enabled()
                && pokemon.getOwnerUUID() != null
                && !WorkerEligibility.isFromParty(pokemon)
                && !WorkerEligibility.works(pokemon, WorkerStateManager.get(pokemon));
    }

    /**
     * After Cobblemon's drowsiness check (DrowsySensorMixin), which runs before its go-to-sleep task
     * in the same brain tick: a non-worker it's made drowsy takes a free bed, if there is one, and
     * until it's in it, it isn't drowsy, so the task doesn't put it to sleep where it is.
     */
    public static void onDrowsinessChecked(PokemonEntity entity) {
        Pokemon pokemon = entity.getPokemon();
        if (!(entity.level() instanceof ServerLevel level)
                || !appliesTo(pokemon)
                || !drowsy(entity)
                || Sleep.cobblemonHasItAsleep(entity)) {
            return;
        }
        JobManager jobs = JobManager.getInstance(level);
        NonWorkerBed bed = jobs.nonWorkerBed(pokemon.getUuid());
        if (bed == null) {
            bed = jobs.claimNonWorkerBed(entity);
            Palworks.LOGGER.debug(
                    "{} is drowsy: {}",
                    pokemon.getSpecies().getName(),
                    bed == null
                            ? "no bed free, it sleeps where it is"
                            : "off to the bed at " + bed.pos().toShortString());
        }
        if (bed != null && !there(entity, level, bed)) {
            entity.getBrain().eraseMemory(CobblemonMemories.POKEMON_DROWSY); // until it's there
        }
    }

    /** Each tick, for every owned Pokémon out in the world (only one with a bed has anything to do). */
    public static void tick(PokemonEntity entity, ServerLevel level) {
        Pokemon pokemon = entity.getPokemon();
        JobManager jobs = JobManager.getInstance(level);
        NonWorkerBed bed = jobs.nonWorkerBed(pokemon.getUuid());
        if (bed == null) {
            return; // it takes one as it gets drowsy (onDrowsinessChecked)
        }
        if (!appliesTo(pokemon)) {
            letGo(jobs, entity, "it's working now, or no longer in a pasture", false);
            return;
        }
        if (!(level.getBlockState(bed.pos()).getBlock() instanceof WorkerBedBlock bedBlock)) {
            letGo(jobs, entity, "its bed is gone", true); // broken: it sleeps where it is
            return;
        }
        boolean there = there(entity, level, bed);
        if (Sleep.cobblemonHasItAsleep(entity)) {
            if (!there) {
                letGo(jobs, entity, "it's asleep somewhere else", false);
            }
            return; // asleep in its bed: Cobblemon wakes it
        }
        long since = level.getGameTime() - bed.since();
        Brain<?> brain = entity.getBrain();
        if (there) {
            if (since > WALK_TICKS + SETTLE_TICKS) {
                letGo(jobs, entity, "Cobblemon doesn't put it to sleep there", false);
                return;
            }
            // In the middle, on top, and drowsy again: Cobblemon puts it to sleep here
            Vec3 middle = bedBlock.center(bed.pos());
            entity.moveTo(middle.x, middle.y, middle.z, entity.getYRot(), entity.getXRot());
            WorkerNavigator.forget(entity);
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
            brain.setMemory(CobblemonMemories.POKEMON_DROWSY, true);
            brain.setMemoryWithExpiry(CobblemonMemories.PATH_COOLDOWN, true, SETTLE_TICKS);
            return;
        }
        if (since > WALK_TICKS) {
            letGo(jobs, entity, "it couldn't get to its bed at " + bed.pos().toShortString(), true);
            jobs.giveUpNonWorkerBed(pokemon.getUuid());
            return;
        }
        WorkerNavigator.walkTo(entity, Beds.standTile(bed.pos(), bedBlock), WALK_SPEED);
    }

    /**
     * Whether it's got to its bed: on the tile it walks to (the bed's middle one: where its walk
     * ends, not always the exact middle), or near the middle. It's then put in the very middle.
     */
    private static boolean there(PokemonEntity entity, ServerLevel level, NonWorkerBed bed) {
        if (!(level.getBlockState(bed.pos()).getBlock() instanceof WorkerBedBlock bedBlock)) {
            return false;
        }
        if (entity.blockPosition().equals(Beds.standTile(bed.pos(), bedBlock))) {
            return true;
        }
        Vec3 middle = bedBlock.center(bed.pos());
        double dx = entity.getX() - middle.x;
        double dz = entity.getZ() - middle.z;
        return dx * dx + dz * dz < ARRIVED * ARRIVED;
    }

    /**
     * It's done with its bed. {@code stillSleepy}: it hasn't slept yet (its bed's gone, it can't get
     * there), so it's drowsy again, and Cobblemon puts it to sleep where it is.
     */
    private static void letGo(JobManager jobs, PokemonEntity entity, String why, boolean stillSleepy) {
        jobs.releaseNonWorkerBed(entity.getPokemon().getUuid());
        WorkerNavigator.forget(entity);
        if (stillSleepy) {
            entity.getBrain().setMemory(CobblemonMemories.POKEMON_DROWSY, true);
        }
        Palworks.LOGGER.debug(
                "{} lets its bed go: {}", entity.getPokemon().getSpecies().getName(), why);
    }

    /**
     * Have Cobblemon make it drowsy now ({@code /palworks sleep}): its own drowsiness check (a small
     * chance each second, in its resting hours) is run until it takes, so all that follows is as in
     * play, a bed included. Nothing happens outside its resting hours, or for a species Cobblemon
     * never lets sleep. Returns whether it got drowsy (or has a bed to go to, on the way to which it
     * isn't drowsy, or is asleep already).
     */
    public static boolean makeDrowsy(PokemonEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        JobManager jobs = JobManager.getInstance(level);
        for (int i = 0; i < MAKE_DROWSY_TRIES && !sleepy(entity, jobs); i++) {
            DrowsySensor.Companion.drowsyLogic(entity);
        }
        return sleepy(entity, jobs);
    }

    private static boolean sleepy(PokemonEntity entity, JobManager jobs) {
        return drowsy(entity)
                || Sleep.cobblemonHasItAsleep(entity)
                || jobs.nonWorkerBed(entity.getPokemon().getUuid()) != null;
    }

    /** Whether Cobblemon has made it drowsy. Only a species Cobblemon lets sleep has the memory. */
    public static boolean drowsy(PokemonEntity entity) {
        Brain<?> brain = entity.getBrain();
        return brain.hasMemoryValue(CobblemonMemories.POKEMON_DROWSY)
                && brain.getMemory(CobblemonMemories.POKEMON_DROWSY).orElse(false);
    }
}
