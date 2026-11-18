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
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** Which work skills the PC and summary screens show, in what order, with what icon and name. */
class WorkSkillsTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets");

    @Test
    void highestLevelFirstThenInJobOrder() {
        assertEquals(
                List.of(
                        new Shown(Suitability.WATERING, 3),
                        new Shown(Suitability.PLANTING, 1),
                        new Shown(Suitability.GATHERING, 1)),
                WorkSkills.shown(Map.of(Suitability.GATHERING, 1, Suitability.WATERING, 3, Suitability.PLANTING, 1)));
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
    void everyJobsWorkHasAnIconThatExists() {
        // Every suitability some job needs, and Nether work (needed by nether wart and stems' jobs)
        Set<Suitability> worked = EnumSet.of(Suitability.NETHER);
        for (JobType job : JobType.values()) {
            if (job != JobType.MINE_RESOURCE) { // not scanned for yet
                worked.addAll(job.requiredSuitabilities()); // none for eating
            }
        }
        for (Suitability job : Suitability.values()) {
            ResourceLocation icon = WorkSkills.icon(job);
            if (worked.contains(job)) {
                assertNotNull(icon, job + " is worked, so it's shown");
                Path file = ASSETS.resolve(icon.getNamespace()).resolve(icon.getPath());
                assertTrue(Files.isRegularFile(file), "missing texture " + file);
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
        for (Suitability job : List.of(Suitability.LUMBERING, Suitability.TRANSPORTING, Suitability.MINING)) {
            for (int level = 1; level <= 5; level++) {
                WorkSkills.LevelEffect effect = WorkSkills.levelEffect(job, level);
                assertNotNull(effect, job + " at level " + level);
                assertTrue(lang.has(effect.key()), "no text for " + effect.key());
            }
        }
        assertEquals(
                List.of(32), WorkSkills.levelEffect(Suitability.LUMBERING, 2).args(), "a level 2's trees");
        assertEquals(
                List.of(4), WorkSkills.levelEffect(Suitability.TRANSPORTING, 4).args(), "a level 4's slots");
        assertEquals(
                "palworks.work_skill.mining.level.5",
                WorkSkills.levelEffect(Suitability.MINING, 5).key());
        // Watering's chance is the server's config; the rest have no level effect yet
        assertNull(WorkSkills.levelEffect(Suitability.WATERING, 3));
        assertNull(WorkSkills.levelEffect(Suitability.KINDLING, 3));
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
