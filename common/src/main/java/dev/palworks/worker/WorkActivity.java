package dev.palworks.worker;

import dev.palworks.job.JobType;
import org.jetbrains.annotations.Nullable;

/**
 * What a worker is doing right now, as shown to its owner (the party HUD's work icons). Pure logic
 * (no Minecraft types), so it's unit-tested.
 *
 * @param phase where it is with its job
 * @param job   the job it has, or null when it has none (OFF or IDLE)
 */
public record WorkActivity(Phase phase, @Nullable JobType job) {

    public enum Phase {
        /** Work switched off (interact wheel toggle or {@code /palworks worker disable}). */
        OFF,
        /** On, but no job right now. */
        IDLE,
        /** Has a job and is on its way there. */
        WALKING,
        /** At the job, doing it. */
        WORKING,
        /** Has a job but is in a battle or evolving; it carries on afterwards. */
        PAUSED;

        /** Safe lookup for a phase sent as an ordinal; unknown values are IDLE. */
        public static Phase fromOrdinal(int ordinal) {
            Phase[] phases = values();
            return ordinal >= 0 && ordinal < phases.length ? phases[ordinal] : IDLE;
        }
    }

    public static final WorkActivity OFF = new WorkActivity(Phase.OFF, null);
    public static final WorkActivity IDLE = new WorkActivity(Phase.IDLE, null);

    /**
     * @param enabled whether its work is switched on
     * @param job     the job it has reserved, or null
     * @param busy    whether it's in a battle or evolving
     * @param started whether it has reached the job and started on it
     */
    public static WorkActivity of(boolean enabled, @Nullable JobType job, boolean busy, boolean started) {
        if (!enabled) {
            return OFF;
        }
        if (job == null) {
            return IDLE;
        }
        if (busy) {
            return new WorkActivity(Phase.PAUSED, job);
        }
        return new WorkActivity(started ? Phase.WORKING : Phase.WALKING, job);
    }
}
