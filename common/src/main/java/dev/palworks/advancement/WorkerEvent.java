package dev.palworks.advancement;

import java.util.Locale;
import net.minecraft.util.StringRepresentable;

/** What the {@code palworks:worker_event} trigger is told of (see {@link WorkerEventTrigger}). */
public enum WorkerEvent implements StringRepresentable {
    /** A worker ate from a trough. */
    ATE,
    /** A worker lay down in a bed at its bedtime. */
    SLEPT_IN_BED,
    /** A Tired worker lay down in a bed for a nap in the day. */
    NAPPED_IN_BED,
    /** A worker went on strike (its mood reached 0). */
    WENT_ON_STRIKE,
    /** A striking worker's mood came back up, and it works again. */
    BACK_TO_WORK,
    /** The player hit a sleeping worker and woke it (given to the player who hit it, not its owner). */
    WOKE_A_WORKER;

    public static final StringRepresentable.EnumCodec<WorkerEvent> CODEC =
            StringRepresentable.fromEnum(WorkerEvent::values);

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
