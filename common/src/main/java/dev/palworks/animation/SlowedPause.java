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
 * it just before a move, all of it in one go, showing why. A Tired worker shows Sleep's Z's
 * (Cobblemon's battle effect), rising all through the pause; a hungry one cries once as it starts
 * (its species' cry, as when it's sent out), as if calling for food. Either way the icons over its
 * head pulse meanwhile (see WorkerStatusSync#PAUSING).
 *
 * <p>It pauses first and then works off the time (what it owes is below 0 after a pause), so even
 * its first job shows it's slowed; over a few jobs it works at its pace. A pause is never shorter
 * than {@link #SHORTEST_PAUSE_TICKS}: at three quarters pace one comes every few jobs, and the
 * slower it is the longer each is. At full pace it owes nothing.
 */
public final class SlowedPause {
    /** Cobblemon's Sleep status effect: Z's rising from it for 2s, each lasting 2.25s. */
    public static final ResourceLocation TIRED = ResourceLocation.fromNamespaceAndPath("cobblemon", "sleep");

    /** The shortest pause: 4.5s, long enough for one round of Z's to come and go. */
    public static final int SHORTEST_PAUSE_TICKS = 90;

    /** How often the Z's start again in a longer pause: as each round stops rising, so they never stop. */
    static final int ZS_EVERY_TICKS = 40;

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
     * Each tick a worker is about to start a move: whether it pauses instead, and shows why. It
     * holds where it is while it does.
     */
    public static boolean holds(Mob entity, Pokemon pokemon, WorkerState state) {
        if (!holds(state, Pace.now(pokemon, state))) {
            return false;
        }
        // This tick's place in the pause: how far in, and how much is left (counting this tick)
        int left = state.getPauseTicksLeft() + 1;
        int into = state.getPauseTicks() - left;
        if (zsDue(into, left) && Sleep.workSpeed(pokemon, state) < Pace.FULL) {
            WorkAnimator.cue(entity, TIRED);
        }
        if (into == 0
                && Hunger.workSpeed(pokemon, state) < Pace.FULL
                && entity instanceof PokemonEntity pokemonEntity) {
            pokemonEntity.cry();
        }
        return true;
    }

    /**
     * Whether a round of Z's starts {@code into} ticks into a pause with {@code left} to go: at its
     * start, then each time the last stops rising, but not so late they'd still be up as its move
     * starts.
     */
    static boolean zsDue(int into, int left) {
        return into % ZS_EVERY_TICKS == 0 && left >= SHORTEST_PAUSE_TICKS;
    }

    /**
     * {@link #holds(Mob, Pokemon, WorkerState)} without showing anything, for a worker at
     * {@code pace}: it goes on with the pause it's in, or, if it hasn't paused for this move yet,
     * starts one for all it owes (or for the shortest pause, paying ahead, if it owes nothing).
     */
    static boolean holds(WorkerState state, double pace) {
        if (state.getPauseTicksLeft() == 0) {
            if (pace >= Pace.FULL) {
                state.setSlowedTicksOwed(0); // it's back up to speed: nothing's owed
                return false;
            }
            if (state.getPauseTicks() > 0 || state.getSlowedTicksOwed() < 0) {
                return false; // it has paused for this move already, or it's paused ahead
            }
            int pause = Math.max(SHORTEST_PAUSE_TICKS, state.getSlowedTicksOwed());
            state.setSlowedTicksOwed(state.getSlowedTicksOwed() - pause);
            state.setPauseTicks(pause);
            state.setPauseTicksLeft(pause);
        }
        state.setPauseTicksLeft(state.getPauseTicksLeft() - 1);
        return true;
    }

    /**
     * After each worker tick: at the start of a step (a new one, or the last started over: it left
     * its spot, its job went), it hasn't paused for its move yet. The rest of a pause it was cut
     * out of is owed again.
     */
    public static void update(WorkerState state) {
        if (state.getStepTicks() == 0) {
            state.setSlowedTicksOwed(state.getSlowedTicksOwed() + state.getPauseTicksLeft());
            state.setPauseTicksLeft(0);
            state.setPauseTicks(0);
        }
    }
}
