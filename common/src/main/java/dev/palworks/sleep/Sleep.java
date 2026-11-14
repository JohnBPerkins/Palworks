package dev.palworks.sleep;

import com.cobblemon.mod.common.CobblemonMemories;
import com.cobblemon.mod.common.api.spawning.TimeRange;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.ai.RestBehaviour;
import dev.palworks.config.PalworksConfig;
import dev.palworks.worker.WorkerEligibility;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import java.util.function.IntPredicate;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import org.jetbrains.annotations.Nullable;

/**
 * Worker sleep. Only pastured workers keep a schedule, as only a Palworld base's pals do: party
 * workers stay awake with their trainer. Each sleeps in its species' Cobblemon sleep hours
 * ({@code behaviour.resting.times}), at night if those are the whole day, every species alike, even
 * one Cobblemon gives no sleep animation (it lies still in its normal pose, see {@link SleepPose}).
 * It sleeps in a free bed it can reach, or where it is, and then it wakes Tired (see
 * {@link SleepSchedule#wakesTired}): slower until it next sleeps. A Tired one doesn't have to wait
 * for night: it naps in a free bed in the day ({@link #wantsNap}), and once it's slept it off it's
 * Tired no more.
 *
 * <p>Cobblemon's own sleep is left alone: an idle pastured worker still naps when Cobblemon makes it
 * drowsy. Palworks only keeps it from dozing off mid-job, or in its bedtime, which it takes over
 * (see DrowsySensorMixin, {@link #blocksCobblemonDrowsiness}). With its work off, or with sleep off
 * in the config, it's Cobblemon's alone.
 */
public final class Sleep {
    private Sleep() {}

    private static PalworksConfig.SleepConfig config() {
        return PalworksConfig.current().sleep();
    }

    /**
     * Whether Palworks' sleep applies to this Pokémon: sleep is on, it's owned, out in the world,
     * pastured (not from a party), and it works ({@link WorkerEligibility#works}: its work is on and
     * it has work it can do). {@code state} is its worker state, or null if it has none yet.
     */
    public static boolean appliesTo(Pokemon pokemon, @Nullable WorkerState state) {
        return config().enabled()
                && pokemon.getOwnerUUID() != null
                && pokemon.getEntity() != null
                && !WorkerEligibility.isFromParty(pokemon)
                && WorkerEligibility.works(pokemon, state);
    }

    /** Its species' (and form's) sleep behaviour. */
    private static RestBehaviour restOf(PokemonEntity entity) {
        return entity.getBehaviour().getResting();
    }

    /** Whether it's bedtime for this worker: its species' sleep hours, or night's if they're "any". */
    public static boolean isBedtime(PokemonEntity entity) {
        TimeRange hours = restOf(entity).getTimes();
        TimeRange night = TimeRange.Companion.getTimeRanges().get("night");
        IntPredicate nightHours = night == null ? time -> false : night::contains;
        return SleepSchedule.isBedtime(entity.level().getDayTime(), hours::contains, nightHours);
    }

    /**
     * How long a worker stays awake after something wakes it in the night (15 seconds), before it goes
     * back to bed if it's still its bedtime.
     */
    public static final long WOKEN_TICKS = 300;

    /**
     * Something disturbed this worker (it was hit or hurt): if it's asleep, or in its bed, it wakes
     * and stays up for {@link #WOKEN_TICKS}. WorkerAI wakes it on its next tick.
     */
    public static void disturb(PokemonEntity entity) {
        WorkerState state = WorkerStateManager.get(entity.getPokemon());
        if (state != null && (state.isAsleep() || state.isInBed())) {
            state.setWokenUntil(entity.level().getGameTime() + WOKEN_TICKS);
        }
    }

    /** Whether it's awake after being woken (from its night's sleep or a nap), and stays up a while yet. */
    public static boolean isWoken(PokemonEntity entity, @Nullable WorkerState state) {
        return state != null && state.getWokenUntil() > entity.level().getGameTime();
    }

    /**
     * How long a Tired worker naps in a bed in the day to sleep it off: a minute. Woken before then,
     * it's still Tired.
     */
    public static final long NAP_TICKS = 1200;

    /**
     * Whether this worker would nap in a bed now: its sleep applies, it's Tired, it isn't its bedtime
     * (when it goes to bed anyway), and nothing has just woken it. With no bed to be had, it doesn't:
     * it carries on, Tired.
     */
    public static boolean wantsNap(PokemonEntity entity, @Nullable WorkerState state) {
        return state != null
                && state.isTired()
                && appliesTo(entity.getPokemon(), state)
                && !isBedtime(entity)
                && !isWoken(entity, state);
    }

    /**
     * Whether a worker in a bed is napping (it stays Tired while it does), and hasn't yet slept it
     * off: it isn't its bedtime, and it's slept less than {@link #NAP_TICKS} in the bed.
     */
    public static boolean isNapping(PokemonEntity entity, WorkerState state) {
        return state.isTired() && !isBedtime(entity) && state.getBedSleepTicks() < NAP_TICKS;
    }

    /** Whether this worker wants a bed now: it's its bedtime, or it's Tired for a nap. */
    public static boolean wantsBed(PokemonEntity entity, @Nullable WorkerState state) {
        return isBedtimeFor(entity, state) || wantsNap(entity, state);
    }

    /** Whether Palworks has this worker go to sleep now: its sleep applies, and it's its bedtime. */
    public static boolean isBedtimeFor(PokemonEntity entity, @Nullable WorkerState state) {
        return appliesTo(entity.getPokemon(), state) && isBedtime(entity);
    }

    /**
     * Whether its species has a sleep animation (Cobblemon's {@code canSleep}). Only the animation:
     * one without still sleeps, lying still in its normal pose.
     */
    public static boolean hasSleepPose(PokemonEntity entity) {
        return restOf(entity).getCanSleep();
    }

    /**
     * Whether Cobblemon is to be kept from making this worker drowsy: while it has a job (so it
     * doesn't doze off mid-job), and in its bedtime (when Palworks puts it to bed). An idle worker
     * outside its bedtime naps when Cobblemon says, as any pastured Pokémon does.
     */
    public static boolean blocksCobblemonDrowsiness(PokemonEntity entity) {
        Pokemon pokemon = entity.getPokemon();
        WorkerState state = WorkerStateManager.get(pokemon);
        if (!appliesTo(pokemon, state)) {
            return false;
        }
        return (state != null && state.getCurrentReservation() != null) || isBedtime(entity);
    }

    /**
     * Whether Cobblemon has this Pokémon asleep (napping, or in a resting place it found). Only a
     * species Cobblemon lets sleep has the memory at all: asking any other's brain for it throws.
     */
    public static boolean cobblemonHasItAsleep(LivingEntity entity) {
        Brain<?> brain = entity.getBrain();
        return brain.hasMemoryValue(CobblemonMemories.POKEMON_SLEEPING)
                && brain.getMemory(CobblemonMemories.POKEMON_SLEEPING).orElse(false);
    }

    /** How fast this worker works and walks, as a share of its usual speed: slower while it's Tired. */
    public static double workSpeed(Pokemon pokemon, @Nullable WorkerState state) {
        return state != null && state.isTired() && appliesTo(pokemon, state) ? config().tiredWorkSpeed() : 1.0;
    }
}
