package dev.palworks.client.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.palworks.job.JobType;
import dev.palworks.net.packets.WorkerDebugPacket;
import dev.palworks.net.packets.WorkerDebugPacket.Details;
import dev.palworks.net.packets.WorkerDebugPacket.PathStatus;
import dev.palworks.worker.WorkActivity;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The text the debug overlay shows above a worker. */
class WorkerDebugTextTest {
    private static final WorkActivity WALKING = new WorkActivity(WorkActivity.Phase.WALKING, JobType.CHOP_TREE);
    private static final WorkActivity WORKING = new WorkActivity(WorkActivity.Phase.WORKING, JobType.CHOP_TREE);

    private static List<WorkerDebugText.Line> lines(WorkActivity activity, Details d, int unreachable) {
        return WorkerDebugText.lines(activity, d, unreachable, 3, 40);
    }

    private static WorkerDebugText.Line line(String text, boolean warning) {
        return new WorkerDebugText.Line(text, warning);
    }

    @Test
    void anIdleWorkerShowsJustItsPhase() {
        assertEquals(List.of(line("idle", false)), lines(WorkActivity.IDLE, Details.NONE, 0));
    }

    @Test
    void aWalkingWorkerShowsItsTimerAndPath() {
        Details d = new Details(246, 354, "", 0, 0, 0, 0, PathStatus.REACHES, 0, 0, 0);
        assertEquals(
                List.of(
                        line("chop_tree · walking", false),
                        line("job 12.3s · gives up in 17.7s", false),
                        line("path reaches", false)),
                lines(WALKING, d, 0));
    }

    @Test
    void aStuckWorkerIsFlagged() {
        Details d = new Details(500, 100, "", 0, 0, 0, 0, PathStatus.PARTIAL, 2, 12, 70);
        assertEquals(
                List.of(
                        line("chop_tree · walking", false),
                        line("job 25.0s · gives up in 5.0s", true),
                        line("partial path · fails 2/3 · still 3.5s · step-in 12/40", true),
                        line("2 unreachable spots", true)),
                lines(WALKING, d, 2));
    }

    @Test
    void noPathWhileWalkingIsAWarning() {
        Details d = new Details(20, 580, "", 0, 0, 0, 0, PathStatus.NONE, 0, 0, 10);
        assertEquals(line("no path", true), lines(WALKING, d, 0).get(2));
    }

    @Test
    void aWorkingWorkerShowsItsStepAndNoCountdown() {
        Details d = new Details(900, -1, "chop", 1, 3, 34, 60, PathStatus.NONE, 0, 0, 0);
        assertEquals(
                List.of(
                        line("chop_tree · working", false),
                        line("job 45.0s", false),
                        line("step 2/3 chop · 34/60t", false)),
                lines(WORKING, d, 0));
    }

    private static Details withFullness(WorkerDebugPacket.Fullness fullness) {
        return new Details(-1, -1, "", 0, 0, 0, 0, PathStatus.NONE, 0, 0, 0, fullness);
    }

    @Test
    void aWorkersFullnessIsShown() {
        assertEquals(
                line("fullness 2/4 · eats below 2 · next 35%", false),
                lines(WorkActivity.IDLE, withFullness(new WorkerDebugPacket.Fullness(2, 4, 2, 35, true)), 0)
                        .get(1));
    }

    @Test
    void anEmptyWorkerIsAWarning() {
        assertEquals(
                line("fullness 0/4 · eats below 2 · next 0%", true),
                lines(WorkActivity.IDLE, withFullness(new WorkerDebugPacket.Fullness(0, 4, 2, 0, true)), 0)
                        .get(1));
    }

    @Test
    void aSwitchedOffWorkersFullnessIsCobblemons() {
        assertEquals(
                line("fullness 0/4 (Cobblemon's)", false),
                lines(WorkActivity.OFF, withFullness(new WorkerDebugPacket.Fullness(0, 4, 2, 0, false)), 0)
                        .get(1));
    }

    @Test
    void oneUnreachableSpotIsSingular() {
        assertEquals(
                line("1 unreachable spot", true),
                lines(WorkActivity.IDLE, Details.NONE, 1).get(1));
    }
}
