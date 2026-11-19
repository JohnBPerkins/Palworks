package dev.palworks.station;

/**
 * How long a kindler's Ember keeps a furnace burning, by its Kindling level: as fuel does, so many
 * items' smelting a light (1, 2, 4, 8 or 16 in a furnace; twice as many in a blast furnace or a
 * smoker, which cook twice as fast). A level 1 kindler stays at its furnace and lights it again for
 * each item; one of a higher level lights it and moves on, and comes back once it's gone out.
 * Pure logic (no Minecraft types) so it can be unit-tested.
 */
public final class KindlingHeat {
    // A furnace's cook time for one item, and a tick over so the item finishes before it goes out
    private static final int ITEM_TICKS = 200;
    private static final int MAX_LEVEL = 5;

    private KindlingHeat() {}

    /** How many items a furnace smelts on one Ember from a kindler of this level. */
    public static int itemsPerEmber(int kindlingLevel) {
        return 1 << (Math.clamp(kindlingLevel, 1, MAX_LEVEL) - 1);
    }

    /** How long one Ember from a kindler of this level keeps a furnace burning, in ticks. */
    public static int burnTicks(int kindlingLevel) {
        return ITEM_TICKS * itemsPerEmber(kindlingLevel) + 1;
    }

    /** Whether a kindler of this level stays at its furnace to light it again (only level 1's does). */
    public static boolean staysAtItsFurnace(int kindlingLevel) {
        return itemsPerEmber(kindlingLevel) == 1;
    }
}
