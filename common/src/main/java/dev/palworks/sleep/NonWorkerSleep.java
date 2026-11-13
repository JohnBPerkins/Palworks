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
 * A pastured Pokémon that isn't working (no work skills, or its work switched off) sleeps in a bed
 * if one's free. When it gets sleepy it takes a free bed by its pasture (JobManager's non-worker
 * beds: none a worker has) and walks there, as a worker does ({@link WorkerNavigator}: Cobblemon's
 * own walking can't get onto a bed); in it, Palworks holds it asleep, as it does a worker
 * ({@link SleepPose}: its sleep pose if its species has one, else lying still), until it's no longer
 * sleepy. No bed free, or it can't get to one: as without beds. It never gets Tired: it doesn't work.
 * <ul>
 *   <li>A species Cobblemon lets sleep is sleepy while Cobblemon has it drowsy: it takes a bed the
 *       moment Cobblemon makes it drowsy ({@link #onDrowsinessChecked}), and on its way it isn't
 *       drowsy as far as Cobblemon's concerned, or Cobblemon would put it to sleep on the spot (it
 *       does, in the same brain tick it makes it drowsy). Cobblemon stops it being drowsy as its
 *       resting hours end, which wakes it. With no bed, Cobblemon sleeps it where it is.</li>
 *   <li>One Cobblemon never lets sleep (and never makes drowsy) is sleepy in its bedtime, as a
 *       worker's ({@link Sleep#isBedtime}): it goes to a free bed then, and lies there till it's over.
 *       With no bed, it stays up, as Cobblemon has it.</li>
 * </ul>
 * Pastured workers sleep by Palworks' own schedule instead ({@link Sleep}); party Pokémon are left to
 * Cobblemon (it never makes one sent out from the party drowsy).
 */
public final class NonWorkerSleep {
    // How near the bed's middle counts as there (as for a worker: WorkPositions' ON_TILE_DIST)
    private static final double ARRIVED = 0.35;
    // Its walk speed to bed (a worker's)
    private static final double WALK_SPEED = 0.6;
    // The longest it may take to get to its bed before it gives up (30s)
    private static final long WALK_TICKS = 600;
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
            bed = claim(jobs, entity);
        }
        if (bed != null && !bed.inBed()) {
            entity.getBrain().eraseMemory(CobblemonMemories.POKEMON_DROWSY); // until it's in its bed
        }
    }

    /** Each tick, for every owned Pokémon out in the world (only one with a bed has anything to do). */
    public static void tick(PokemonEntity entity, ServerLevel level) {
        Pokemon pokemon = entity.getPokemon();
        JobManager jobs = JobManager.getInstance(level);
        NonWorkerBed bed = jobs.nonWorkerBed(pokemon.getUuid());
        if (bed == null) {
            // One Cobblemon never makes drowsy goes to bed at its bedtime
            if (!Sleep.hasSleepPose(entity) && appliesTo(pokemon) && Sleep.isBedtime(entity)) {
                claim(jobs, entity);
            }
            return;
        }
        if (!appliesTo(pokemon)) {
            letGo(jobs, entity, bed, "it's working now, or no longer in a pasture");
            return;
        }
        if (!(level.getBlockState(bed.pos()).getBlock() instanceof WorkerBedBlock bedBlock)) {
            letGo(jobs, entity, bed, "its bed is gone");
            return;
        }
        Vec3 middle = bedBlock.center(bed.pos());
        if (bed.inBed()) {
            if (!sleepy(entity)) {
                letGo(jobs, entity, bed, "it woke up");
                return;
            }
            SleepPose.hold(entity); // asleep in its bed till it isn't sleepy
            return;
        }
        if (there(entity, bedBlock, bed, middle)) {
            // In the middle, on top: it lies down, and sleeps
            entity.moveTo(middle.x, middle.y, middle.z, entity.getYRot(), entity.getXRot());
            WorkerNavigator.forget(entity);
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            if (Sleep.hasSleepPose(entity)) {
                entity.getBrain().setMemory(CobblemonMemories.POKEMON_DROWSY, true); // still sleepy
            }
            jobs.settleNonWorkerInBed(pokemon.getUuid());
            SleepPose.hold(entity);
            return;
        }
        if (level.getGameTime() - bed.since() > WALK_TICKS) {
            letGo(
                    jobs,
                    entity,
                    bed,
                    "it couldn't get to its bed at " + bed.pos().toShortString());
            jobs.giveUpNonWorkerBed(pokemon.getUuid());
            return;
        }
        WorkerNavigator.walkTo(entity, Beds.standTile(bed.pos(), bedBlock), WALK_SPEED);
    }

    /** Whether it's a non-worker lying in its bed, asleep (Palworks holding it so). */
    public static boolean isInBed(PokemonEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        NonWorkerBed bed =
                JobManager.getInstance(level).nonWorkerBed(entity.getPokemon().getUuid());
        return bed != null && bed.inBed();
    }

    /** It takes a free bed, if there is one (null if not), and says so in the debug log. */
    private static NonWorkerBed claim(JobManager jobs, PokemonEntity entity) {
        NonWorkerBed bed = jobs.claimNonWorkerBed(entity);
        Palworks.LOGGER.debug(
                "{} is sleepy: {}",
                entity.getPokemon().getSpecies().getName(),
                bed == null ? "no bed free" : "off to the bed at " + bed.pos().toShortString());
        return bed;
    }

    /**
     * Whether it's still sleepy, in its bed: Cobblemon has it drowsy (or asleep), or, for a species
     * Cobblemon never lets sleep, it's its bedtime.
     */
    private static boolean sleepy(PokemonEntity entity) {
        return Sleep.hasSleepPose(entity)
                ? drowsy(entity) || Sleep.cobblemonHasItAsleep(entity)
                : Sleep.isBedtime(entity);
    }

    /**
     * Whether it's got to its bed: on the tile it walks to (the bed's middle one: where its walk
     * ends, not always the exact middle), or near the middle.
     */
    private static boolean there(PokemonEntity entity, WorkerBedBlock bedBlock, NonWorkerBed bed, Vec3 middle) {
        if (entity.blockPosition().equals(Beds.standTile(bed.pos(), bedBlock))) {
            return true;
        }
        double dx = entity.getX() - middle.x;
        double dz = entity.getZ() - middle.z;
        return dx * dx + dz * dz < ARRIVED * ARRIVED;
    }

    /**
     * It's done with its bed: up out of it if it was lying in it. If it never got that far (its
     * bed's gone, it can't get there) and Cobblemon had it drowsy, it's drowsy again, so Cobblemon
     * puts it to sleep where it is.
     */
    private static void letGo(JobManager jobs, PokemonEntity entity, NonWorkerBed bed, String why) {
        jobs.releaseNonWorkerBed(entity.getPokemon().getUuid());
        WorkerNavigator.forget(entity);
        if (bed.inBed()) {
            SleepPose.wake(entity);
        } else if (Sleep.hasSleepPose(entity) && appliesTo(entity.getPokemon())) {
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
        for (int i = 0; i < MAKE_DROWSY_TRIES && !madeSleepy(entity, jobs); i++) {
            DrowsySensor.Companion.drowsyLogic(entity);
        }
        return madeSleepy(entity, jobs);
    }

    private static boolean madeSleepy(PokemonEntity entity, JobManager jobs) {
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
