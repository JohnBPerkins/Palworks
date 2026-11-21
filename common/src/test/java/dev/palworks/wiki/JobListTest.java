package dev.palworks.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.palworks.job.JobType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The reference library's list of every job type and the skill it needs. */
class JobListTest {

    private static List<String> row(List<List<String>> rows, JobType job) {
        return rows.stream()
                .filter(r -> r.get(0).equals(job.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + job));
    }

    @Test
    void everyJobTypeHasARowWithTheSkillItNeeds() {
        List<List<String>> rows = JobList.rows(Map.of());
        assertEquals(JobList.header(), rows.get(0));
        assertEquals(JobType.values().length + 1, rows.size(), "the header and every job type");
        assertEquals(List.of("chop_tree", "lumbering", "4", "true", ""), row(rows, JobType.CHOP_TREE));
        assertEquals(List.of("eat_food", "", "10", "false", ""), row(rows, JobType.EAT_FOOD), "any worker eats");
        assertEquals("gathering", row(rows, JobType.HARVEST_KELP).get(1));
    }

    @Test
    void notesStayWithTheirJob() {
        List<List<String>> rows = JobList.rows(Map.of("harvest_crops", Map.of("notes", "nether wart: Nether")));
        assertEquals("nether wart: Nether", row(rows, JobType.HARVEST_CROPS).get(4));
        assertThrows(IllegalStateException.class, () -> JobList.rows(Map.of("gone_job", Map.of("notes", "x"))));
    }
}
