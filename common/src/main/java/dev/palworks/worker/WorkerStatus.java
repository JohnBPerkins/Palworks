package dev.palworks.worker;

import java.util.Locale;

/**
 * What a Pokémon shows over its head (see WorkerStatusSync and, on the client, StatusIconRenderer;
 * the icon for each is in WorkIcons#forStatus): what holds a worker's work back, and its sleep where
 * nothing else shows it. Each is an aspect on the Pokémon, which Cobblemon syncs to everyone nearby.
 */
public enum WorkerStatus {
    /** No fullness left: it works at a fraction of its speed until it eats (see dev.palworks.hunger.Hunger). */
    HUNGRY,

    /** It slept without a bed: it works slower until it next sleeps (see dev.palworks.sleep.Sleep). */
    TIRED,

    /** Its mood is low: a warning that it's on its way to a strike (see dev.palworks.mood.Mood). */
    UNHAPPY,

    /**
     * Its mood ran out: it does no work until it's back up a way (see dev.palworks.mood.Mood). Shown
     * instead of {@link #UNHAPPY}.
     */
    STRIKING,

    /** Idle because every job it would do is somewhere it can't get to (see JobAssigner). */
    CANT_REACH,

    /**
     * Carrying items with nowhere to take them: no chest in reach with room, or none it can get to
     * (see JobAssigner).
     */
    CANT_DELIVER,

    /**
     * Asleep (a worker in its bedtime, or a non-worker in a bed: see dev.palworks.sleep), for a
     * species with no sleep animation of its own, which only lies still. Not {@link #TIRED}, which is
     * a worker awake and slow.
     */
    ASLEEP;

    /** Whether it's one that slows a worker (see WorkSpeed): its icon pulses while it pauses for it. */
    public boolean slows() {
        return this == HUNGRY || this == TIRED;
    }

    /** The aspect a Pokémon with this status has: {@code palworks-hungry}. */
    public String aspect() {
        return "palworks-" + name().toLowerCase(Locale.ROOT);
    }
}
