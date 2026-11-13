package dev.palworks.client.debug;

import dev.palworks.net.packets.WorkerDebugPacket;
import dev.palworks.net.packets.WorkerDebugPacket.Details;
import dev.palworks.net.packets.WorkerDebugPacket.PathStatus;
import dev.palworks.worker.WorkActivity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The lines of text the debug overlay shows above a worker, for example:
 * <pre>
 * chop_tree · walking
 * job 12.3s · gives up in 17.7s
 * partial path · fails 1/3 · still 2.5s
 * 2 unreachable spots
 * </pre>
 * Lines that would say nothing are left out. Lines about something going wrong (a path that doesn't
 * reach, failures, standing still, a job about to be given up) are flagged as warnings. Pure logic,
 * so it's unit-tested.
 */
public final class WorkerDebugText {
    // Standing still for this long shows on the label (in red after STILL_WARNING_TICKS)
    static final int STILL_SHOWN_TICKS = 20;

    /** Below this many ticks left, the "gives up in" countdown is a warning. */
    static final int EXPIRY_WARNING_TICKS = 10 * 20;
    /** Standing still while meant to be walking for this long is a warning. */
    static final int STILL_WARNING_TICKS = 2 * 20;

    public record Line(String text, boolean warning) {}

    private WorkerDebugText() {}

    public static List<Line> lines(
            WorkActivity activity, Details d, int unreachableSpots, int stuckAfterPathFailures, int maxStepInTicks) {
        List<Line> lines = new ArrayList<>();
        String phase = activity.phase().name().toLowerCase(Locale.ROOT);
        lines.add(new Line(activity.job() == null ? phase : activity.job().getId() + " · " + phase, false));

        if (d.jobAgeTicks() >= 0) {
            String text = "job " + seconds(d.jobAgeTicks());
            boolean warning = false;
            if (d.expiresInTicks() >= 0) {
                text += " · gives up in " + seconds(d.expiresInTicks());
                warning = d.expiresInTicks() < EXPIRY_WARNING_TICKS;
            }
            lines.add(new Line(text, warning));
        }

        if (!d.stepName().isEmpty()) {
            lines.add(new Line(
                    String.format(
                            Locale.ROOT,
                            "step %d/%d %s · %d/%dt",
                            d.stepIndex() + 1,
                            d.stepCount(),
                            d.stepName(),
                            d.stepTicks(),
                            d.stepLength()),
                    false));
        }

        if (activity.phase() == WorkActivity.Phase.WALKING) {
            List<String> parts = new ArrayList<>();
            boolean warning = false;
            switch (d.pathStatus()) {
                case REACHES -> parts.add("path reaches");
                case PARTIAL -> {
                    parts.add("partial path");
                    warning = true;
                }
                case NONE -> {
                    parts.add("no path");
                    warning = true;
                }
            }
            if (d.pathFailures() > 0) {
                parts.add("fails " + d.pathFailures() + "/" + stuckAfterPathFailures);
                warning = true;
            }
            if (d.stillTicks() >= STILL_SHOWN_TICKS) {
                parts.add("still " + seconds(d.stillTicks()));
                warning |= d.stillTicks() >= STILL_WARNING_TICKS;
            }
            if (d.stepInTicks() > 0) {
                parts.add("step-in " + d.stepInTicks() + "/" + maxStepInTicks);
            }
            lines.add(new Line(String.join(" · ", parts), warning));
        }

        WorkerDebugPacket.Fullness fullness = d.fullness();
        if (fullness.max() > 0) {
            String text = fullness.palworks()
                    ? String.format(
                            Locale.ROOT,
                            "fullness %d/%d · eats below %d · next %d%%",
                            fullness.current(),
                            fullness.max(),
                            fullness.troughCap(),
                            fullness.owedPercent())
                    : String.format(Locale.ROOT, "fullness %d/%d (Cobblemon's)", fullness.current(), fullness.max());
            // Empty: it works slower until it eats
            lines.add(new Line(text, fullness.palworks() && fullness.current() == 0));
        }

        String sleep = sleepLine(d.rest());
        if (sleep != null) {
            // Tired is a warning: it works slower until it sleeps in a bed
            lines.add(new Line(sleep, d.rest().tired()));
        }

        String mood = moodLine(d.mood());
        if (mood != null) {
            // Unhappy or on strike is a warning: it works slower, or not at all
            lines.add(new Line(mood, d.mood().unhappy() || d.mood().striking()));
        }

        if (unreachableSpots > 0) {
            lines.add(new Line(
                    unreachableSpots + (unreachableSpots == 1 ? " unreachable spot" : " unreachable spots"), true));
        }
        return lines;
    }

    /** "asleep · in bed", "bedtime · no bed yet", "Tired"... or null for a rested worker awake in the day. */
    @org.jetbrains.annotations.Nullable
    static String sleepLine(WorkerDebugPacket.Rest rest) {
        if (!rest.sleeps()) {
            return null;
        }
        String state;
        if (rest.asleep()) {
            state = rest.inBed() ? "asleep · in bed" : "asleep · no bed";
        } else if (rest.bedtime()) {
            state = "bedtime";
        } else {
            state = null;
        }
        if (rest.tired()) {
            return state == null ? "Tired" : state + " · Tired";
        }
        return state;
    }

    /** "mood 72", "mood 31 · unhappy", "mood 0 · on strike", or null when mood doesn't apply. */
    @org.jetbrains.annotations.Nullable
    static String moodLine(WorkerDebugPacket.Mood mood) {
        if (!mood.shown()) {
            return null;
        }
        String line = "mood " + mood.mood();
        if (mood.striking()) {
            return line + " · on strike";
        }
        return mood.unhappy() ? line + " · unhappy" : line;
    }

    static String seconds(int ticks) {
        return String.format(Locale.ROOT, "%.1fs", ticks / 20.0);
    }

    /** Whether the path line should be drawn as a warning: it doesn't reach the job. */
    public static boolean pathFallsShort(Details d) {
        return d.pathStatus() == PathStatus.PARTIAL;
    }
}
