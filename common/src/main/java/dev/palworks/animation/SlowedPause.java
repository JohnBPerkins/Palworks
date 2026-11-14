package dev.palworks.animation;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.hunger.Hunger;
import dev.palworks.sleep.Sleep;
import dev.palworks.worker.Pace;
import dev.palworks.worker.WorkerState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;

/**
 * How a slowed worker (hungry, Tired: see dev.palworks.worker.WorkSpeed) takes longer over its
 * work. Its moves play at their usual speed (their particles and sounds can't be slowed), so it
 * doesn't spread the time it loses over them, where it wouldn't show: it saves it up, and pauses for
 * it a few seconds at a time, just before a move, showing why. A Tired worker shows Sleep's Z's
 * (Cobblemon's battle effect); a hungry one cries (its species' cry, as when it's sent out), as if
 * calling for food. Either way the icons over its head pulse meanwhile (see WorkerStatusSync#PAUSING).
 *
 * <p>It pauses first and then works off the time (what it owes is below 0 after a pause), so even
 * its first job shows it's slowed; over a few jobs it works at its pace. At full pace it owes
 * nothing.
 */
public final class SlowedPause {
    /** Cobblemon's Sleep status effect: Z's rising from it. */
    public static final ResourceLocation TIRED = ResourceLocation.fromNamespaceAndPath("cobblemon", "sleep");

    /** How long a pause is: 4.5s, long enough for the Z's to come and go (each lasts 2.25s, for 2s). */
    public static final int PAUSE_TICKS = 90;

    private SlowedPause() {}

    /**
     * Its step's move has landed: a step of {@code ticks} that a worker at {@code pace} takes
     * longer over, and the time it saved is owed.
     */
    public static void owe(WorkerState state, int ticks, double pace) {
        int saved = WorkAnimation.atPace(ticks, pace) - ticks;
        if (saved > 0) {
            state.setSlowedTicksOwed(state.getSlowedTicksOwed() + saved);
        }
    }

    /**
     * Each tick a worker is about to start a move: whether it pauses instead, and shows why when a
     * pause starts. It holds where it is while it does.
     */
    public static boolean holds(Mob entity, Pokemon pokemon, WorkerState state) {
        boolean wasPausing = state.getPauseTicksLeft() > 0;
        if (!holds(state, Pace.now(pokemon, state))) {
            return false;
        }
        if (!wasPausing) {
            if (Sleep.workSpeed(pokemon, state) < Pace.FULL) {
                WorkAnimator.cue(entity, TIRED);
            }
            if (Hunger.workSpeed(pokemon, state) < Pace.FULL && entity instanceof PokemonEntity pokemonEntity) {
                pokemonEntity.cry();
            }
        }
        return true;
    }

    /**
     * {@link #holds(Mob, Pokemon, WorkerState)} without showing anything, for a worker at
     * {@code pace}: it goes on with a pause it's in, or starts one if it owes anything (or nothing).
     */
    static boolean holds(WorkerState state, double pace) {
        if (state.getPauseTicksLeft() == 0) {
            if (pace >= Pace.FULL) {
                state.setSlowedTicksOwed(0); // it's back up to speed: nothing's owed
                return false;
            }
            if (state.getSlowedTicksOwed() < 0) {
                return false;
            }
            state.setSlowedTicksOwed(state.getSlowedTicksOwed() - PAUSE_TICKS);
            state.setPauseTicksLeft(PAUSE_TICKS);
        }
        state.setPauseTicksLeft(state.getPauseTicksLeft() - 1);
        return true;
    }

    /** After each worker tick: a pause ends if its step started over (it left its spot, its job went). */
    public static void update(WorkerState state) {
        if (state.getStepTicks() == 0) {
            state.setPauseTicksLeft(0);
        }
    }
}
