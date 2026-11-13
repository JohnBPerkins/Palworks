package dev.palworks.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

/** Which chunks the job scan takes next: every one gets its turn, however many there are. */
class JobScannerTest {
    private static final ChunkPos A = new ChunkPos(0, 0);
    private static final ChunkPos B = new ChunkPos(1, 0);
    private static final ChunkPos C = new ChunkPos(2, 0);
    private static final ChunkPos D = new ChunkPos(3, 0);

    @Test
    void neverScannedFirstThenTheOldestScan() {
        long now = 20_000L;
        Map<ChunkPos, Long> lastScan = Map.of(
                A, now - JobScanner.RESCAN_TICKS - 100, // stale
                B, now - JobScanner.RESCAN_TICKS - 150, // staler
                C, JobScanner.NEVER_SCANNED,
                D, now - 10); // fresh
        assertEquals(List.of(C, B, A), JobScanner.dueChunks(List.of(A, B, C, D), lastScan::get, now));
    }

    @Test
    void aChunkScannedWithinItsLifetimeIsNotDue() {
        long now = 1000L;
        long justFresh = now - JobScanner.RESCAN_TICKS + 1;
        long justStale = now - JobScanner.RESCAN_TICKS;
        Map<ChunkPos, Long> lastScan = Map.of(A, justFresh, B, justStale);
        assertEquals(List.of(B), JobScanner.dueChunks(List.of(A, B), lastScan::get, now));
    }

    /**
     * Far more chunks than the scan gets through before they're stale again: each still gets
     * scanned, and no more than (chunks / per tick) ticks apart. Taken in a fixed order instead,
     * the first ones were scanned again every lifetime and the last ones never.
     */
    @Test
    void withMoreChunksThanTheBudgetKeepsFreshEveryOneStillGetsItsTurn() {
        int chunks = 200;
        int perTick = 2; // 80 chunks a lifetime of 40 ticks: well short of 200
        List<ChunkPos> all = new ArrayList<>();
        Map<ChunkPos, Long> lastScan = new HashMap<>();
        for (int i = 0; i < chunks; i++) {
            ChunkPos chunk = new ChunkPos(i, 0);
            all.add(chunk);
            lastScan.put(chunk, JobScanner.NEVER_SCANNED);
        }

        Map<ChunkPos, Long> longestWait = new HashMap<>();
        for (long now = 0; now < 2000; now++) {
            List<ChunkPos> due = JobScanner.dueChunks(all, lastScan::get, now);
            for (ChunkPos chunk : due.subList(0, Math.min(perTick, due.size()))) {
                long since = lastScan.get(chunk) == JobScanner.NEVER_SCANNED ? now : now - lastScan.get(chunk);
                longestWait.merge(chunk, since, Math::max);
                lastScan.put(chunk, now);
            }
        }
        assertEquals(chunks, longestWait.size(), "every chunk got scanned");
        long most =
                longestWait.values().stream().mapToLong(Long::longValue).max().orElseThrow();
        assertTrue(
                most <= chunks / perTick,
                "no chunk waited more than " + chunks / perTick + " ticks, but one waited " + most);
    }
}
