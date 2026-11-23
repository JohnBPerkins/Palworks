package dev.palworks.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.palworks.client.gui.WorkSkills.Shown;
import dev.palworks.job.JobType;
import dev.palworks.suitability.Suitability;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Which work skills the PC and summary screens show, in what order, with what icon and name. */
class WorkSkillsTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets");

    @Test
    void levelledSkillsFirstHighestLevelFirstThenTheRestInJobOrder() {
        assertEquals(
                List.of(
                        new Shown(Suitability.WATERING, 3),
                        new Shown(Suitability.TRANSPORTING, 3),
                        new Shown(Suitability.KINDLING, 1),
                        new Shown(Suitability.PLANTING, 1),
                        new Shown(Suitability.GATHERING, 4)),
                WorkSkills.shown(Map.of(
                        Suitability.GATHERING, 4,
                        Suitability.WATERING, 3,
                        Suitability.PLANTING, 1,
                        Suitability.TRANSPORTING, 3,
                        Suitability.KINDLING, 1)));
    }

    @Test
    void onlySkillsWhoseLevelChangesSomethingShowALevel() {
        for (Suitability job : Suitability.values()) {
            if (WorkSkills.icon(job) == null) {
                continue;
            }
            assertEquals(WorkSkills.levelEffect(job, 3, 0.1) != null, WorkSkills.isLevelled(job), job.toString());
        }
    }

    @Test
    void workThereIsNothingToDoOnYetIsntShown() {
        assertEquals(
                List.of(new Shown(Suitability.TRANSPORTING, 2)),
                WorkSkills.shown(
                        Map.of(Suitability.COOLING, 4, Suitability.HANDIWORK, 3, Suitability.TRANSPORTING, 2)));
        assertEquals(List.of(), WorkSkills.shown(Map.of()));
    }

    @Test
    void everyJobsWorkHasAnIcon() {
        // Every suitability some job needs, and Nether work (needed by nether wart and stems' jobs)
        Set<Suitability> worked = EnumSet.of(Suitability.NETHER);
        for (JobType job : JobType.values()) {
            if (job != JobType.MINE_RESOURCE) { // not scanned for yet
                worked.addAll(job.requiredSuitabilities()); // none for eating
            }
        }
        for (Suitability job : Suitability.values()) {
            WorkIcons.Icon icon = WorkSkills.icon(job); // that it exists: WorkIconsTest, WorkStatusGameTests
            if (worked.contains(job)) {
                assertNotNull(icon, job + " is worked, so it's shown");
            } else {
                assertNull(icon, job + " has no work yet, so it isn't shown");
            }
        }
    }

    @Test
    void everyJobHasAName() throws IOException {
        JsonObject lang = JsonParser.parseString(Files.readString(ASSETS.resolve("palworks/lang/en_us.json")))
                .getAsJsonObject();
        for (Suitability job : Suitability.values()) {
            assertTrue(lang.has(WorkSkills.nameKey(job)), "no name for " + job);
        }
    }

    @Test
    void everyShownJobSaysWhatItDoes() throws IOException {
        JsonObject lang = JsonParser.parseString(Files.readString(ASSETS.resolve("palworks/lang/en_us.json")))
                .getAsJsonObject();
        for (Suitability job : Suitability.values()) {
            if (WorkSkills.icon(job) != null) {
                assertTrue(lang.has(WorkSkills.descriptionKey(job)), "no description for " + job);
            }
        }
    }

    @Test
    void theSkillsWhoseLevelChangesSomethingSayWhatEachLevelGives() throws IOException {
        JsonObject lang = JsonParser.parseString(Files.readString(ASSETS.resolve("palworks/lang/en_us.json")))
                .getAsJsonObject();
        for (Suitability job : List.of(
                Suitability.KINDLING,
                Suitability.WATERING,
                Suitability.LUMBERING,
                Suitability.TRANSPORTING,
                Suitability.MINING)) {
            for (int level = 1; level <= 5; level++) {
                WorkSkills.LevelEffect effect = WorkSkills.levelEffect(job, level, 0.1);
                assertNotNull(effect, job + " at level " + level);
                assertTrue(lang.has(effect.key()), "no text for " + effect.key());
            }
        }
        assertEquals(
                List.of(32),
                WorkSkills.levelEffect(Suitability.LUMBERING, 2, 0.1).args(),
                "a level 2's trees");
        assertEquals(
                List.of(4),
                WorkSkills.levelEffect(Suitability.TRANSPORTING, 4, 0.1).args(),
                "a level 4's slots");
        assertEquals(
                "palworks.work_skill.mining.level.5",
                WorkSkills.levelEffect(Suitability.MINING, 5, 0.1).key());
        assertEquals(
                "palworks.work_skill.kindling.level.one",
                WorkSkills.levelEffect(Suitability.KINDLING, 1, 0.1).key());
        assertEquals(
                List.of(4), WorkSkills.levelEffect(Suitability.KINDLING, 3, 0.1).args(), "a level 3's items an Ember");
        // Watering's from the server's chance per level, never past 100%
        assertEquals(
                List.of("30"),
                WorkSkills.levelEffect(Suitability.WATERING, 3, 0.1).args());
        assertEquals(
                List.of("12.5"),
                WorkSkills.levelEffect(Suitability.WATERING, 5, 0.025).args());
        assertEquals(
                List.of("100"),
                WorkSkills.levelEffect(Suitability.WATERING, 5, 0.2).args());
        assertNull(WorkSkills.levelEffect(Suitability.GATHERING, 3, 0.1), "no level effect yet");
    }

    @Test
    void thePokedexFilterGoesFromNoneThroughEveryShownSkillAndBackToNone() {
        List<Suitability> picked = new ArrayList<>();
        for (Suitability skill = WorkSkills.nextFilter(null); skill != null; skill = WorkSkills.nextFilter(skill)) {
            picked.add(skill);
        }
        List<Suitability> shown = new ArrayList<>();
        for (Suitability skill : Suitability.values()) {
            if (WorkSkills.icon(skill) != null) {
                shown.add(skill);
            }
        }
        assertEquals(shown, picked);
    }
}
