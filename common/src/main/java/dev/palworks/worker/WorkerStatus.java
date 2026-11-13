package dev.palworks.worker;

import java.util.Locale;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * What a Pokémon shows over its head (see WorkerStatusSync and, on the client, StatusIconRenderer):
 * what holds a worker's work back, and its sleep where nothing else shows it. Each is an aspect on
 * the Pokémon, which Cobblemon syncs to everyone nearby.
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

    /**
     * Asleep (a worker in its bedtime, or a non-worker in a bed: see dev.palworks.sleep), for a
     * species with no sleep animation of its own, which only lies still. A crescent moon: not
     * Tired's "z", which is a worker awake and slow.
     */
    ASLEEP(null, "palworks:status/asleep");

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
