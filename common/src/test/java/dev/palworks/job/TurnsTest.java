package dev.palworks.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which idle workers are considered for a job each tick (JobAssigner): every one gets its turn. */
class TurnsTest {
    @Test
    void allOfThemWhenThereAreNoMoreThanTheBatch() {
        List<String> idle = List.of("a", "b", "c");
        assertEquals(idle, Turns.longestWaiting(idle, w -> 0L, 3));
    }

    @Test
    void neverConsideredFirstThenTheLongestWaiting() {
        Map<String, Long> last = Map.of("a", 30L, "b", 10L, "c", Long.MIN_VALUE, "d", 20L);
        assertEquals(List.of("c", "b"), Turns.longestWaiting(List.of("a", "b", "c", "d"), last::get, 2));
    }

    /**
     * Far more idle workers than a tick's batch, most of them with nothing they can ever do (so
     * idle for good): every worker is still considered, no more than (idle / batch) ticks apart.
     * In a fixed order, the ones that never find a job took the whole batch every tick.
     */
    @Test
    void workersThatNeverFindAJobDontKeepTheOthersWaiting() {
        int batch = 10;
        List<String> idle = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            idle.add("worker" + i);
        }
        Map<String, Long> last = new HashMap<>();
        Map<String, Long> longestWait = new HashMap<>();
        for (long now = 0; now < 100; now++) {
            for (String worker : Turns.longestWaiting(idle, w -> last.getOrDefault(w, Long.MIN_VALUE), batch)) {
                long since = last.containsKey(worker) ? now - last.get(worker) : now;
                longestWait.merge(worker, since, Math::max);
                last.put(worker, now);
            }
        }
        assertEquals(idle.size(), longestWait.size(), "every worker was considered");
        long most =
                longestWait.values().stream().mapToLong(Long::longValue).max().orElseThrow();
        int turns = (idle.size() + batch - 1) / batch;
        assertTrue(most <= turns, "no worker waited more than " + turns + " ticks, but one waited " + most);
    }
}
