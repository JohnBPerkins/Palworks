package dev.palworks.worker;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.job.JobType;
import dev.palworks.reservation.ReservationId;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * A slowed worker's pace (see {@link WorkSpeed}) on the Pokémon itself: its movement speed, and
 * flying speed, are that much slower, whatever's moving it. Palworks walking it to a job, or
 * Cobblemon walking it round its pasture or after its owner, all go by those attributes. A
 * transient modifier, so it's never saved; taken off again at full pace.
 */
public final class Pace {
    /** The modifier's id on the movement attributes. */
    static final ResourceLocation MODIFIER = Palworks.id("pace");

    /** Full pace: not slowed at all. */
    public static final double FULL = 1.0;

    /** The step a pace is rounded to, so the server and clients (see the pace aspect) agree on it. */
    private static final double STEP = 0.05;

    private Pace() {}

    /**
     * The pace a worker goes at right now: its work speed (see {@link WorkSpeed}), rounded, except on
     * its way to eat or sleep, or heading home for that, which is never slowed: what makes it better
     * isn't held up by what's wrong with it.
     */
    public static double now(Pokemon pokemon, WorkerState state) {
        ReservationId job = state.getCurrentReservation();
        boolean forItsNeeds =
                job != null && (job.getJobType() == JobType.EAT_FOOD || job.getJobType() == JobType.SLEEP);
        if (forItsNeeds || state.getHomeward() != null) {
            return FULL;
        }
        return rounded(WorkSpeed.of(pokemon, state));
    }

    /** A pace rounded to {@link #STEP}: at least one step, at most 1. */
    public static double rounded(double pace) {
        if (pace >= FULL) {
            return FULL;
        }
        return Math.max(STEP, Math.round(pace / STEP) * STEP);
    }

    /** Slow this worker's movement to its pace ({@code pace} rounded), or undo it at full pace. */
    public static void applyTo(Mob entity, double pace) {
        double rounded = rounded(pace);
        apply(entity, Attributes.MOVEMENT_SPEED, rounded);
        apply(entity, Attributes.FLYING_SPEED, rounded);
    }

    private static void apply(Mob entity, Holder<Attribute> attribute, double pace) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return; // it doesn't fly
        }
        AttributeModifier current = instance.getModifier(MODIFIER);
        if (pace >= FULL) {
            if (current != null) {
                instance.removeModifier(MODIFIER);
            }
            return;
        }
        double amount = pace - 1; // the total, times (1 + amount): times pace
        if (current == null || Math.abs(current.amount() - amount) > 1e-6) {
            instance.addOrUpdateTransientModifier(
                    new AttributeModifier(MODIFIER, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }
}
