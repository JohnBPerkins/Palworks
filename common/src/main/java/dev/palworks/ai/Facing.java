package dev.palworks.ai;

import net.minecraft.util.Mth;

/**
 * Turning a worker to face its work: a little each tick, as a Pokémon turns, rather than snapping
 * round in one. Its animation waits until it's facing the block ({@link #faces}), so the move
 * doesn't fire off sideways. Pure maths on yaws (Minecraft's: degrees, 0 = south, 90 = west).
 */
public final class Facing {
    /** How far a worker turns in a tick: half a turn in 12 ticks, a little over half a second. */
    public static final float TURN_DEGREES_PER_TICK = 15.0f;
    /** How near the block's direction counts as facing it, to start an animation. */
    public static final float FACING_DEGREES = 10.0f;

    private Facing() {}

    /** The yaw that looks along {@code (dx, dz)}. */
    public static float yawTowards(double dx, double dz) {
        return (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0f;
    }

    /**
     * One tick's turn from {@code yaw} towards {@code wanted}, the short way round, by at most
     * {@code maxStep} degrees: {@code wanted} itself once it's that near.
     */
    public static float turn(float yaw, float wanted, float maxStep) {
        float off = Mth.wrapDegrees(wanted - yaw);
        if (Math.abs(off) <= maxStep) {
            return wanted;
        }
        return yaw + Math.copySign(maxStep, off);
    }

    /** Whether {@code yaw} is within {@link #FACING_DEGREES} of {@code wanted}. */
    public static boolean faces(float yaw, float wanted) {
        return Math.abs(Mth.wrapDegrees(wanted - yaw)) <= FACING_DEGREES;
    }
}
