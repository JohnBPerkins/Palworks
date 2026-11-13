package dev.palworks.worker;

import java.util.Locale;
import net.minecraft.resources.ResourceLocation;

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
    HUNGRY("hud/food_empty", "hud/food_half");

    // Vanilla sprites in the GUI atlas, by path: drawn one over the other
    private final String background;
    private final String sprite;

    WorkerStatus(String background, String sprite) {
        this.background = background;
        this.sprite = sprite;
    }

    /** What the icon is drawn over (the hunger bar's empty outline). */
    public ResourceLocation background() {
        return ResourceLocation.withDefaultNamespace(background);
    }

    /** The aspect a Pokémon with this status has: {@code palworks-hungry}. */
    public String aspect() {
        return "palworks-" + name().toLowerCase(Locale.ROOT);
    }

    /** The icon shown over its head, over its {@link #background}: a sprite from the GUI atlas. */
    public ResourceLocation sprite() {
        return ResourceLocation.withDefaultNamespace(sprite);
    }
}
