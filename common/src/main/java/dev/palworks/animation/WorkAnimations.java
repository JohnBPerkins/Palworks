package dev.palworks.animation;

import dev.palworks.ai.WorkStance;
import java.util.List;

/**
 * Every animation a job plays, in one place. All are placeholders for now: Cobblemon move effects
 * that suit the work, each played from the stance its move needs (contact moves right up against
 * the block, ranged ones from a little way off). To swap one, change the move and re-tune its
 * impact ticks: when its target particles start, from its timeline in Cobblemon's
 * {@code data/cobblemon/action_effects/moves/<move>.json} (the delays and pauses before them, at
 * 20 ticks a second). The timelines read here are the same from Cobblemon 1.7.0 to 1.8.x.
 *
 * <p>These are the defaults: a datapack can change any of them for particular species, by id
 * ({@link WorkAnimationData}), and {@link #forSpecies} gives a species its own.
 */
public final class WorkAnimations {
    private WorkAnimations() {}

    /**
     * Lighting a furnace: Ember, a ranged move. Its flames leave at 1.25s and burst on the target
     * half a second later; aimed low, at the fire chamber near the furnace's base.
     */
    public static final WorkAnimation IGNITE_FURNACE =
            WorkAnimation.move("ignite_furnace", "ember", 35, 0.125, WorkStance.NEAR);

    /**
     * Harvesting a crop: Razor Leaf, a ranged move. Its leaves hit the target at about 1.5s (0.375
     * + 0.05 + 0.67 + 0.4); aimed at the middle of the crop.
     */
    public static final WorkAnimation HARVEST = WorkAnimation.move("harvest", "razorleaf", 30, 0.5, WorkStance.NEAR);

    /**
     * Planting a seed: Leech Seed, a ranged move. Its seeds are thrown from 1.25s, over 0.6s, and
     * each takes 0.6s to land and sprout, so the last lands at about 2.45s: the seed goes in then,
     * as the sprouts appear, towards the end of the body animation. Aimed at the ground it's
     * planted in.
     */
    public static final WorkAnimation PLANT = WorkAnimation.move("plant", "leechseed", 48, 0.0, WorkStance.NEAR);

    /**
     * Chopping a tree: Scratch, a contact move, so the lumberjack stands right up against the
     * trunk. Its slash appears on the target at 1.2s and throws sparks for half a second; the tree
     * comes down at 1.6s, as they fall, rather than the instant the slash appears. Aimed at the
     * middle of the trunk.
     */
    public static final WorkAnimation CHOP = WorkAnimation.move("chop", "scratch", 32, 0.5, WorkStance.AGAINST);

    /** All of them. */
    public static final List<WorkAnimation> ALL = List.of(IGNITE_FURNACE, HARVEST, PLANT, CHOP);

    /**
     * The animation a species plays in place of {@code animation}: the default, with whatever the
     * loaded datapacks change for that species.
     *
     * @param speciesId the species' id, e.g. {@code cobblemon:scizor}; null for a worker that isn't
     *                  a Pokémon, which plays the default
     */
    public static WorkAnimation forSpecies(
            WorkAnimation animation, @org.jetbrains.annotations.Nullable String speciesId) {
        return speciesId == null ? animation : WorkAnimationData.current().forSpecies(animation, speciesId);
    }
}
