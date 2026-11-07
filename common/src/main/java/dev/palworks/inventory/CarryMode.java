package dev.palworks.inventory;

import dev.palworks.suitability.Suitability;

import java.util.Set;

/**
 * What a worker can carry, decided by its suitabilities. "What you see is what it carries": only
 * Transporting Pokémon have a real inventory; planters carry one visible stack of seeds as work
 * supplies; everyone else carries nothing (their harvests and drops stay on the ground for
 * transporters).
 *
 * <p>Pure logic (no Minecraft types) so it can be unit-tested. Which items fit a mode is checked in
 * {@link WorkerInventory}.
 */
public enum CarryMode {
    /** Carries nothing. No inventory button on the interact wheel. */
    NONE,
    /** One slot, seeds and other plantable items only: a planter's supply for new farmland. */
    SEED_POUCH,
    /** The full inventory: any item, every slot. */
    FULL;

    /** The worker inventory slot a seed pouch uses: the middle one, so the GUI shows it centred. */
    public static final int POUCH_SLOT = 1;

    /**
     * Transporting gets the full inventory (and plants from it if it can also plant). Planting, or
     * Nether (which plants nether wart), gets a seed pouch. Anything else carries nothing.
     */
    public static CarryMode forSuitabilities(Set<Suitability> suitabilities) {
        if (suitabilities.contains(Suitability.TRANSPORTING)) {
            return FULL;
        }
        if (suitabilities.contains(Suitability.PLANTING) || suitabilities.contains(Suitability.NETHER)) {
            return SEED_POUCH;
        }
        return NONE;
    }

    /** Whether this mode uses a given worker inventory slot. */
    public boolean usesSlot(int slot) {
        return switch (this) {
            case FULL -> slot >= 0 && slot < WorkerInventory.INVENTORY_SIZE;
            case SEED_POUCH -> slot == POUCH_SLOT;
            case NONE -> false;
        };
    }

    /** Safe lookup for a mode synced as an ordinal (the GUI's data slot); unknown values are NONE. */
    public static CarryMode fromOrdinal(int ordinal) {
        CarryMode[] modes = values();
        return ordinal >= 0 && ordinal < modes.length ? modes[ordinal] : NONE;
    }
}
