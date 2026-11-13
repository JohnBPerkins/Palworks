package dev.palworks.worker;

import java.util.Locale;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Something wrong with a worker that it shows over its head (see WorkerStatusSync and, on the
 * client, StatusIconRenderer): only ever things that hold its work back. Each is an aspect on the
 * Pokémon, which Cobblemon syncs to everyone nearby.
 */
public enum WorkerStatus {
    /**
     * No fullness left: it works at a fraction of its speed until it eats (see
     * dev.palworks.hunger.Hunger). A half hunger shank, as on the hunger bar: the half drawn over
     * the empty outline.
     */
    HUNGRY("minecraft:hud/food_empty", "minecraft:hud/food_half"),

    /**
     * It slept without a bed: it works slower until it next sleeps (see dev.palworks.sleep.Sleep).
     * A sleepy "z".
     */
    TIRED(null, "palworks:status/tired"),

    /** Its mood is low: it works slower until it cheers up (see dev.palworks.mood.Mood). A sad face. */
    UNHAPPY(null, "palworks:status/unhappy"),

    /**
     * Its mood ran out: it does no work until it's back up a way (see dev.palworks.mood.Mood). An
     * anger mark, shown instead of {@link #UNHAPPY}.
     */
    STRIKING(null, "palworks:status/striking");

    // Sprites in the GUI atlas, by id: the background (if any) drawn first, the icon over it
    @Nullable
    private final String background;

    private final String sprite;

    WorkerStatus(@Nullable String background, String sprite) {
        this.background = background;
        this.sprite = sprite;
    }

    /** What the icon is drawn over (the hunger bar's empty outline), or null for nothing. */
    @Nullable
    public ResourceLocation background() {
        return background == null ? null : ResourceLocation.parse(background);
    }

    /** The aspect a Pokémon with this status has: {@code palworks-hungry}. */
    public String aspect() {
        return "palworks-" + name().toLowerCase(Locale.ROOT);
    }

    /** The icon shown over its head, over its {@link #background}: a sprite from the GUI atlas. */
    public ResourceLocation sprite() {
        return ResourceLocation.parse(sprite);
    }
}
