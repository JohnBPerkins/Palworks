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
                WorkSkills.shown(Map.of(Suitability.COOLING, 4, Suitability.MINING, 3, Suitability.TRANSPORTING, 2)));
        assertEquals(List.of(), WorkSkills.shown(Map.of()));
    }

    @Test
    void everyJobsWorkHasAnIconThatExists() {
        // Every suitability some job needs, and Nether work (needed by nether wart and stems' jobs)
        Set<Suitability> worked = EnumSet.of(Suitability.NETHER);
        for (JobType job : JobType.values()) {
            if (job != JobType.MINE_RESOURCE) { // not scanned for yet
                worked.add(job.getRequiredSuitability());
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
    void theBoxNamesAllThatFitOrAllButTheLastRow() {
        assertEquals(0, WorkSkills.namedInBox(0));
        assertEquals(1, WorkSkills.namedInBox(1));
        assertEquals(2, WorkSkills.namedInBox(2));
        // 3 or more: the first, then "+2 more"
        assertEquals(1, WorkSkills.namedInBox(3));
        assertEquals(1, WorkSkills.namedInBox(5));
    }
}
