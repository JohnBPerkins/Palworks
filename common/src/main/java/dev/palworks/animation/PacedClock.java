package dev.palworks.animation;

/**
 * The clock a Pokémon's animations run on, kept at its pace (see {@code worker.Pace}): it goes up
 * by the pace for each tick of the Pokémon's age, so a slowed worker's walk plays slower, and it
 * doesn't jump when its pace changes. {@code PosableStateClockMixin} runs Cobblemon's animation
 * clock on one of these.
 *
 * <p>Once a Pokémon's been slowed, this is behind its age for good. So whatever is timed against an
 * animation has to be read off this clock too, and that goes for an animation's sounds and
 * particles: Cobblemon times those from when the animation started, which it reads off this clock,
 * so the stretch of time each tick covers ({@link #secondsBeforeLastStep} to {@link #secondsNow})
 * is this clock's, not the age's. By the age, every animation started after a worker was first
 * slowed was already past its sounds and particles: its hungry cry was silent.
 *
 * <p>No Minecraft or Cobblemon classes, so it can be unit tested.
 */
public final class PacedClock {
    private static final float TICKS_PER_SECOND = 20f;

    // In ticks; NaN until it's first brought up to an age
    private float ticks = Float.NaN;
    private int age;
    // How far the last tick (or ticks) of age took it
    private float lastStep;

    /**
     * Bring it up to this age, at this pace for the ticks since it was last. The first time, or if
     * the age has gone back (a new entity), it starts from the age.
     */
    public void advanceTo(int toAge, float pace) {
        int passed = toAge - age;
        if (Float.isNaN(ticks) || passed < 0) {
            ticks = toAge;
            lastStep = 0f;
        } else if (passed > 0) {
            lastStep = passed * pace;
            ticks += lastStep;
        }
        age = toAge;
    }

    /** What it reads this far into the tick it's at, at this pace. */
    public float seconds(float partialTicks, float pace) {
        return (ticks + partialTicks * pace) / TICKS_PER_SECOND;
    }

    /** What it read before the age last went up: where the stretch of time that covered starts. */
    public float secondsBeforeLastStep() {
        return (ticks - lastStep) / TICKS_PER_SECOND;
    }

    /** What it reads at the start of the tick it's at: where that stretch ends. */
    public float secondsNow() {
        return ticks / TICKS_PER_SECOND;
    }
}
