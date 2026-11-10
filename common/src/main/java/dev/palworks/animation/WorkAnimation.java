package dev.palworks.animation;

import net.minecraft.resources.ResourceLocation;

/**
 * What a worker plays for one step of a job, as data: which animation, aimed where, and when it
 * lands. Played by {@link WorkAnimator}; a job's step declares one and the step's timing follows
 * from it (see {@link #stepTicks} and {@link #playAt}), so the effect lands on the tick the step's
 * work happens (the furnace lights as the flames reach it, the crop goes as the leaves hit it).
 *
 * <p>For now every animation is a placeholder: a Cobblemon move's action effect (its body
 * animation, particles and sounds, with the move's own timing), which every species can play.
 * Swapping the move only means changing it here and re-tuning {@code impactTicks}.
 *
 * @param moveEffect  the Cobblemon action effect to play (e.g. {@code cobblemon:ember}), keyed by
 *                    move name in Cobblemon's {@code action_effects} data
 * @param impactTicks ticks from the start of the effect until it lands on the target: read off the
 *                    move's timeline (the pauses and delays before its target particles)
 * @param aimY        height within the target block the effect aims at (0 = bottom, 1 = top)
 */
public record WorkAnimation(ResourceLocation moveEffect, int impactTicks, double aimY) {

    /**
     * Ticks a worker stands still before an animation starts. One started on the tick it stops
     * walking is dropped by the client, as it's still in its walk-to-idle pose transition.
     */
    public static final int SETTLE_TICKS = 8;

    public WorkAnimation {
        if (impactTicks < 0) {
            throw new IllegalArgumentException("impactTicks must not be negative: " + impactTicks);
        }
    }

    /** A Cobblemon move's action effect, by move name (e.g. {@code "ember"}). */
    public static WorkAnimation move(String move, int impactTicks, double aimY) {
        return new WorkAnimation(ResourceLocation.fromNamespaceAndPath("cobblemon", move), impactTicks, aimY);
    }

    /**
     * How long a step with this animation takes, given the least time its work takes: long enough
     * for the worker to settle and the animation to land.
     */
    public int stepTicks(int minTicks) {
        return Math.max(minTicks, SETTLE_TICKS + impactTicks);
    }

    /**
     * How many ticks into a step of {@code stepTicks} the animation starts, so it lands on the
     * step's last tick (where its work happens), but never before the worker has settled.
     */
    public int playAt(int stepTicks) {
        return Math.max(SETTLE_TICKS, stepTicks - impactTicks);
    }
}
