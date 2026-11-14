package dev.palworks.animation;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.hunger.Hunger;
import dev.palworks.sleep.Sleep;
import dev.palworks.worker.Pace;
import dev.palworks.worker.WorkerState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;

/**
 * What a slowed worker shows while it waits at its job for its move (it takes longer over its work,
 * but its move plays at its usual speed: see WorkAnimation#atPace): Cobblemon's own battle effects,
 * so players see it's struggling, and why. Out of food: the stats-fell arrows. Tired: Sleep's Z's.
 * Both are particles over it, so every species shows them, whatever its model has.
 */
public final class SlowedCues {
    /** Cobblemon's stat-drop effect: blue arrows falling over it. */
    public static final ResourceLocation HUNGRY = ResourceLocation.fromNamespaceAndPath("cobblemon", "unboost");

    /** Cobblemon's Sleep status effect: Z's rising from it. */
    public static final ResourceLocation TIRED = ResourceLocation.fromNamespaceAndPath("cobblemon", "sleep");

    /** How often it shows them while it waits: 2s. */
    static final int EVERY_TICKS = 40;

    private SlowedCues() {}

    /** Whether it shows them {@code stepTicks} into a step whose move starts {@code startTick} in. */
    static boolean dueAt(int stepTicks, int startTick) {
        int waited = stepTicks - WorkAnimation.SETTLE_TICKS;
        return waited >= 0 && stepTicks < startTick && waited % EVERY_TICKS == 0;
    }

    /**
     * Each tick of a step with an animation that starts {@code startTick} ticks in, {@code stepTicks}
     * in: once the worker has settled, and every {@link #EVERY_TICKS} after till its move starts,
     * what's slowing it, if anything. A fed, rested worker shows nothing.
     */
    public static void whileWaiting(Mob entity, Pokemon pokemon, WorkerState state, int stepTicks, int startTick) {
        if (!dueAt(stepTicks, startTick)) {
            return;
        }
        if (Hunger.workSpeed(pokemon, state) < Pace.FULL) {
            WorkAnimator.cue(entity, HUNGRY);
        }
        if (Sleep.workSpeed(pokemon, state) < Pace.FULL) {
            WorkAnimator.cue(entity, TIRED);
        }
    }
}
