package dev.palworks.boulder;

import java.util.Locale;

/**
 * The look of a boulder, picked at random for each one (see {@link BoulderShape}), so a worker's
 * boulders aren't all alike.
 */
public enum BoulderStyle {
    /** One rounded lump, round or oval. */
    DOME,
    /** Narrow and tall, its top off the middle. */
    PEAK,
    /** Wide and low, flat on top, round or long. */
    SLAB,
    /** Leaning one way: tall on one side, low on the other. */
    LOPSIDED,
    /** Two lumps of different sizes, joined at the bottom. */
    TWIN,
    /** Long and narrow, with a spine along it. */
    RIDGE,
    /** Three to five small rocks of different sizes, in a group but apart. */
    CLUSTER,
    /** One big rock, cracked in two. */
    SPLIT;

    /** Whether a boulder of this style is all one piece (a rock cluster and a split rock aren't). */
    public boolean isOnePiece() {
        return this != CLUSTER && this != SPLIT;
    }

    /** Its name in commands and on signs ("dome"). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
