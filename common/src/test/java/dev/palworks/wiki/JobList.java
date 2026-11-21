package dev.palworks.wiki;

import dev.palworks.job.JobType;
import dev.palworks.suitability.Suitability;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A CSV of every job type in the mod (JobType), with the work skill each needs, to cross-reference
 * with the Pokémon list (its {@code skill} is a column there). Run by {@code :common:generateJobList}
 * into the reference library. Its {@code notes} column is filled in by hand (a job whose skills
 * depend on its block, such as nether wart's, says so there) and kept, by job, when it's regenerated
 * (see ReferenceCsv).
 */
final class JobList {
    static final String NOTES = "notes";
    private static final List<String> KEY = List.of("job");

    private JobList() {}

    /** Argument: the CSV to write (and to keep the notes from). */
    public static void main(String[] args) throws IOException {
        Path csv = Path.of(args[0]);
        ReferenceCsv.write(csv, rows(ReferenceCsv.read(csv, KEY, NOTES::equals)));
    }

    /** The header: the job's id, the skill it needs (blank: any worker), its priority, whether it's work, notes. */
    static List<String> header() {
        return List.of("job", "skill", "priority", "work", NOTES);
    }

    /**
     * The header and a row for every job type, in JobType's order, with its notes from {@code kept}.
     *
     * @throws IllegalStateException if a kept row with notes is a job that's gone
     */
    static List<List<String>> rows(Map<String, Map<String, String>> kept) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(header());
        Set<String> keys = new TreeSet<>();
        for (JobType job : JobType.values()) {
            keys.add(job.getId());
            Suitability skill = job.getRequiredSuitability();
            rows.add(List.of(
                    job.getId(),
                    skill == null ? "" : skill.getId(),
                    String.valueOf(job.priority()),
                    String.valueOf(job.isWork()),
                    kept.getOrDefault(job.getId(), Map.of()).getOrDefault(NOTES, "")));
        }
        ReferenceCsv.checkNoneLost(kept, keys, "job types");
        return rows;
    }
}
