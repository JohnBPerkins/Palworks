package dev.palworks.boulder;

import java.util.Locale;

/**
 * The look of a boulder, picked at random for each one (see {@link BoulderShape}), so a worker's
 * boulders aren't all alike.
 */
public enum BoulderStyle {
    /** Round and squat. */
    DOME,
    /** Narrow and tall, its top off the middle. */
    PEAK,
    /** Wide and low, flat on top, its edge broken in places. */
    SLAB,
    /** Leaning one way: tall on one side, low on the other. */
    LOPSIDED,
    /** Two lumps of different sizes, joined at the bottom. */
    TWIN;

    /** Its name in commands and on signs ("dome"). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
