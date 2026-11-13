package dev.palworks.hunger;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.config.PalworksConfig;
import dev.palworks.worker.WorkerEligibility;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import org.jetbrains.annotations.Nullable;

/**
 * Worker hunger. Hunger is the Pokémon's own Cobblemon fullness (shown on its summary): Palworks
 * adds no stat of its own.
 *
 * <ul>
 *   <li>While a sent-out Pokémon's work is on, Cobblemon's own drain of its fullness is paused
 *       (see {@link #pausesCobblemonDrain}, used by PokemonMetabolismMixin), and Palworks drains it
 *       instead: by the work it does ({@link #didWork}), plus a little all the time
 *       ({@link #tick}). Cobblemon's drain goes by Speed, so a fast species would be hungry again
 *       within a minute.</li>
 *   <li>It eats from troughs (see EatFoodHandler) up to {@link #troughCap}, half its max fullness
 *       by default, which leaves room for hand-feeding: a full Pokémon refuses Mochi and berries.</li>
 *   <li>An empty one works and walks slower ({@link #workSpeed}), but never stops.</li>
 * </ul>
 *
 * <p>With its work off, no work it can do, or hunger off in the config, none of this applies: the
 * Pokémon is left entirely to Cobblemon.
 */
public final class Hunger {
    private Hunger() {}

    private static PalworksConfig.HungerConfig config() {
        return PalworksConfig.current().hunger();
    }

    /** Whether workers get hungry at all (the config's {@code hunger.enabled}). */
    public static boolean enabled() {
        return config().enabled();
    }

    /**
     * Whether Palworks' hunger applies to this Pokémon now: hunger is on, it's owned and out in the
     * world (sent out, or in a pasture), and it works ({@link WorkerEligibility#works}: its work is on
     * and it has work it can do). {@code state} is its worker state, or null if it has none yet.
     */
    public static boolean appliesTo(Pokemon pokemon, @Nullable WorkerState state) {
        return enabled()
                && pokemon.getOwnerUUID() != null
                && pokemon.getEntity() != null
                && WorkerEligibility.works(pokemon, state);
    }

    /**
     * Whether Cobblemon's own fullness drain is paused for this Pokémon: while Palworks' hunger
     * applies to it. In its ball, or with its work off, Cobblemon drains it as usual.
     */
    public static boolean pausesCobblemonDrain(Pokemon pokemon) {
        return pokemon.getEntity() != null && appliesTo(pokemon, WorkerStateManager.get(pokemon));
    }

    /** The fullness troughs fill this Pokémon up to (see {@link HungerMath#troughCap}). */
    public static int troughCap(Pokemon pokemon) {
        return HungerMath.troughCap(pokemon.getMaxFullness(), config().troughFillFraction());
    }

    /** Whether this worker should go and eat: it's below what troughs fill it up to. */
    public static boolean wantsFood(Pokemon pokemon) {
        return enabled() && pokemon.getCurrentFullness() < troughCap(pokemon);
    }

    /** Whether this worker has no fullness left, so works slower (see {@link #workSpeed}). */
    public static boolean isEmpty(Pokemon pokemon, @Nullable WorkerState state) {
        return appliesTo(pokemon, state) && pokemon.getCurrentFullness() <= 0;
    }

    /** How fast this worker works and walks, as a share of its usual speed: slower when it's empty. */
    public static double workSpeed(Pokemon pokemon, @Nullable WorkerState state) {
        return isEmpty(pokemon, state) ? config().emptyWorkSpeed() : 1.0;
    }

    /**
     * The worker has done {@code units} of work (a crop harvested, a seed planted, a trip made...):
     * use up its fullness for it. Call it where the work's effect happens, only when it happened.
     */
    public static void didWork(Pokemon pokemon, WorkerState state, int units) {
        if (appliesTo(pokemon, state)) {
            drain(pokemon, state, HungerMath.workDrain(units, config().workPerFullness()));
        }
    }

    /** Each tick a worker's work is on (and it isn't fainted or in a battle): its slow, steady drain. */
    public static void tick(Pokemon pokemon, WorkerState state) {
        if (appliesTo(pokemon, state)) {
            drain(pokemon, state, HungerMath.idleDrainPerTick(pokemon.getMaxFullness(), config().idleDrainPerDay()));
        }
    }

    /**
     * Feed the worker {@code points} of fullness from a trough, but not past what troughs fill it up
     * to, so food worth several points doesn't leave it too full to hand-feed. Only fullness: the food's own
     * effects (EVs, friendship, healing) don't happen. Plays Cobblemon's eating sound.
     */
    public static void feed(Pokemon pokemon, int points) {
        int room = troughCap(pokemon) - pokemon.getCurrentFullness();
        if (points > 0 && room > 0) {
            pokemon.feedPokemon(Math.min(points, room), true);
        }
    }

    private static void drain(Pokemon pokemon, WorkerState state, double amount) {
        if (amount <= 0) {
            return;
        }
        HungerMath.Drained drained = HungerMath.drain(state.getFullnessOwed(), amount, pokemon.getCurrentFullness());
        if (drained.points() > 0) {
            pokemon.loseFullness(drained.points());
        }
        state.setFullnessOwed(drained.owed());
    }
}
