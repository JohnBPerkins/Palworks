package dev.palworks.animation;

/**
 * Every animation a job plays, in one place. All are placeholders for now: Cobblemon move effects
 * that suit the work. To swap one, change the move and re-tune its impact ticks: when its target
 * particles start, from its timeline in Cobblemon's
 * {@code data/cobblemon/action_effects/moves/<move>.json} (the delays and pauses before them, at
 * 20 ticks a second). The timelines read here are the same from Cobblemon 1.7.0 to 1.8.x.
 */
public final class WorkAnimations {
    private WorkAnimations() {}

    /**
     * Lighting a furnace: Ember. Its flames leave at 1.25s and burst on the target half a second
     * later; aimed low, at the fire chamber near the furnace's base.
     */
    public static final WorkAnimation IGNITE_FURNACE = WorkAnimation.move("ember", 35, 0.125);

    /**
     * Harvesting a crop: Razor Leaf. Its leaves hit the target at about 1.5s (0.375 + 0.05 + 0.67
     * + 0.4); aimed at the middle of the crop.
     */
    public static final WorkAnimation HARVEST = WorkAnimation.move("razorleaf", 30, 0.5);

    /**
     * Planting a seed: Leech Seed. The seed is thrown at 1.25s; it reaches the ground about half a
     * second later, like Ember's flames (tune in game). Aimed at the ground it's planted in.
     */
    public static final WorkAnimation PLANT = WorkAnimation.move("leechseed", 35, 0.0);

    /** Chopping a tree: Scratch. It hits the target at 1.2s; aimed at the middle of the trunk. */
    public static final WorkAnimation CHOP = WorkAnimation.move("scratch", 24, 0.5);
}
