package dev.palworks.mood;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.config.PalworksConfig;
import dev.palworks.hunger.Hunger;
import dev.palworks.reservation.ReservationId;
import dev.palworks.sleep.Sleep;
import dev.palworks.worker.WorkerEligibility;
import dev.palworks.worker.WorkerState;
import org.jetbrains.annotations.Nullable;

/**
 * A worker's mood: its own stat, 0 to 100, separate from friendship (kept in its {@link
 * WorkerState}, so it's still there after the PC).
 *
 * <ul>
 *   <li>Work wears it down ({@link #didWork}, by the same units hunger counts), and so does being
 *       Hungry or Tired ({@link #tick}). With neither, and while it isn't at work (idle, eating or
 *       asleep), it comes back up, slowly.</li>
 *   <li>Unhappy (below half) it works and walks slower ({@link #workSpeed}), along with any
 *       slowdown for hunger or sleep.</li>
 *   <li>At 0 it goes on strike ({@link #isStriking}): it finishes what it's doing, then does no work
 *       (it still eats and sleeps) until its mood is back up a way.</li>
 * </ul>
 *
 * <p>Party workers have a mood too, but never sleep, so are never Tired. With its work off (or mood
 * off in the config), or in its ball, its mood holds where it is.
 */
public final class Mood {
    private Mood() {}

    private static PalworksConfig.MoodConfig config() {
        return PalworksConfig.current().mood();
    }

    /** Whether workers have a mood at all (the config's {@code mood.enabled}). */
    public static boolean enabled() {
        return config().enabled();
    }

    /**
     * Whether mood applies to this Pokémon now: mood is on, it's owned and out in the world, and it
     * works ({@link WorkerEligibility#works}: its work is on, and it has work it can do). {@code
     * state} is its worker state, or null if it has none yet.
     */
    public static boolean appliesTo(Pokemon pokemon, @Nullable WorkerState state) {
        return enabled()
                && pokemon.getOwnerUUID() != null
                && pokemon.getEntity() != null
                && WorkerEligibility.works(pokemon, state);
    }

    /** Whether it's unhappy: its mood is low enough that it works slower. */
    public static boolean isUnhappy(Pokemon pokemon, @Nullable WorkerState state) {
        return state != null && appliesTo(pokemon, state) && MoodMath.unhappy(state.getMood(), config().unhappyBelow());
    }

    /** Whether it's on strike: its mood reached 0, and it does no work until it's back up a way. */
    public static boolean isStriking(Pokemon pokemon, @Nullable WorkerState state) {
        return state != null && state.isStriking() && appliesTo(pokemon, state);
    }

    /** How fast this worker works and walks, as a share of its usual speed: slower when it's unhappy. */
    public static double workSpeed(Pokemon pokemon, @Nullable WorkerState state) {
        return isUnhappy(pokemon, state) ? config().unhappyWorkSpeed() : 1.0;
    }

    /** The worker has done {@code units} of work (see Hunger.didWork): it wears its mood down. */
    public static void didWork(Pokemon pokemon, WorkerState state, int units) {
        if (appliesTo(pokemon, state)) {
            change(state, -MoodMath.workDrain(units, config().workPerPoint()));
        }
    }

    /**
     * Each tick a worker's work is on (and it isn't fainted or in a battle): down while it's Hungry
     * or Tired, and with neither, back up while it isn't at work.
     */
    public static void tick(Pokemon pokemon, WorkerState state) {
        if (!appliesTo(pokemon, state)) {
            return;
        }
        PalworksConfig.MoodConfig config = config();
        boolean hungry = Hunger.isEmpty(pokemon, state);
        boolean tired = state.isTired() && Sleep.appliesTo(pokemon, state);
        ReservationId job = state.getCurrentReservation();
        boolean atWork = job != null && job.getJobType().isWork();
        change(
                state,
                MoodMath.changePerTick(
                        hungry,
                        tired,
                        atWork,
                        config.hungryDrainPerDay(),
                        config.tiredDrainPerDay(),
                        config.recoveryPerDay()));
    }

    private static void change(WorkerState state, double amount) {
        if (amount == 0) {
            return;
        }
        state.setMood(state.getMood() + amount);
        state.setStriking(MoodMath.striking(state.isStriking(), state.getMood(), config().strikeUntil()));
    }
}
