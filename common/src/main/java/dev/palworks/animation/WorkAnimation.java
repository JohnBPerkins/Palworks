package dev.palworks.animation;

import dev.palworks.ai.WorkStance;
import net.minecraft.resources.ResourceLocation;

/**
 * What a worker plays for one step of a job, as data: which animation, aimed where, when it lands
 * and how long it lasts. Played by {@link WorkAnimator}; a job's step declares one and the step's
 * timing follows from it (see {@link #stepTicks} and {@link #playAt}), so the effect lands on the
 * tick the step's work happens (the furnace lights as the flames reach it, the crop goes as the
 * leaves hit it). After that the worker plays it out ({@link #playOutTicks}) before it walks off
 * or starts another animation.
 *
 * <p>It also says where the worker stands to play it ({@link #stance}): right up against the
 * block for a contact move, further back for a ranged one. The job's worker walks there first.
 *
 * <p>The defaults are placeholders: a Cobblemon move's action effect (its body animation,
 * particles and sounds, with the move's own timing), which every species can play. Swapping the
 * move only means changing it in {@link WorkAnimations} and re-tuning its timing; a datapack can
 * also swap it, or any of the rest, for particular species (see {@link WorkAnimationData}).
 *
 * @param id            which animation this is ({@code harvest}, {@code chop}, ...): what a
 *                      datapack names to change it for a species
 * @param moveEffect    the Cobblemon action effect to play (e.g. {@code cobblemon:ember}), keyed by
 *                      move name in Cobblemon's {@code action_effects} data
 * @param impactTicks   ticks from the start of the effect until it lands on the target: read off
 *                      the move's timeline (the pauses and delays before its target particles)
 * @param durationTicks ticks from the start until the worker's body animation is over, at least
 *                      {@code impactTicks}
 * @param aimY          height within the target block the effect aims at (0 = bottom, 1 = top)
 * @param stance        where the worker stands to play it
 */
public record WorkAnimation(
        String id, ResourceLocation moveEffect, int impactTicks, int durationTicks, double aimY, WorkStance stance) {

    /**
     * Ticks a worker stands still before an animation starts. One started on the tick it stops
     * walking is dropped by the client, as it's still in its walk-to-idle pose transition.
     */
    public static final int SETTLE_TICKS = 8;

    /**
     * How long a move's body animation lasts: 3s, the usual length of Cobblemon's
     * {@code physical}, {@code special} and {@code status} animations (the median across species;
     * they range from 1.75s to 4.5s). Moves play those unless the species has one of its own.
     */
    public static final int MOVE_DURATION_TICKS = 60;

    public WorkAnimation {
        if (id.isEmpty()) {
            throw new IllegalArgumentException("an animation needs an id");
        }
        if (impactTicks < 0) {
            throw new IllegalArgumentException("impactTicks must not be negative: " + impactTicks);
        }
        if (durationTicks < impactTicks) {
            throw new IllegalArgumentException(
                    "durationTicks (" + durationTicks + ") must be at least impactTicks (" + impactTicks + ")");
        }
    }

    /**
     * A Cobblemon move's action effect, by move name (e.g. {@code "ember"}), whose body animation
     * lasts {@link #MOVE_DURATION_TICKS}.
     */
    public static WorkAnimation move(String id, String move, int impactTicks, double aimY, WorkStance stance) {
        return new WorkAnimation(
                id,
                ResourceLocation.fromNamespaceAndPath("cobblemon", move),
                impactTicks,
                MOVE_DURATION_TICKS,
                aimY,
                stance);
    }

    /**
     * How long the animation goes on after it lands (after the step's work has happened): the
     * worker stays put meanwhile, rather than walking off mid-animation.
     */
    public int playOutTicks() {
        return durationTicks - impactTicks;
    }

    /**
     * {@code ticks} at a worker's {@code pace} (see dev.palworks.worker.WorkSpeed): longer for one
     * slowed down (hungry, Tired...), as its animations play slower too. Unchanged at full pace.
     */
    public static int atPace(int ticks, double pace) {
        return pace >= 1 ? ticks : (int) Math.ceil(ticks / Math.max(pace, 0.05));
    }

    /** {@link #playAt}, for a worker at {@code pace}: its slower animation lands later after it starts. */
    public int playAt(int stepTicks, double pace) {
        return Math.max(SETTLE_TICKS, stepTicks - atPace(impactTicks, pace));
    }

    /** How long its body animation lasts, played at {@code pace}. */
    public int durationTicks(double pace) {
        return atPace(durationTicks, pace);
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
