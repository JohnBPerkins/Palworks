package dev.palworks.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.palworks.ai.WorkStance;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The worker roster: species' own work animations, from datapack files. */
class WorkAnimationDataTest {
    private static final String SCIZOR = "cobblemon:scizor";

    private final List<String> warnings = new ArrayList<>();

    private WorkAnimationData load(String... idThenJson) {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        for (int i = 0; i < idThenJson.length; i += 2) {
            files.put(idThenJson[i], JsonParser.parseString(idThenJson[i + 1]));
        }
        return WorkAnimationData.fromFiles(files, WorkAnimations.ALL, warnings::add);
    }

    @Test
    void aSpeciesCanPlayItsOwnMoveWithItsOwnTiming() {
        WorkAnimationData data = load("addon:scizor", """
                {"species": "scizor", "animations": {"chop": {
                    "move": "furycutter", "impact_ticks": 18, "duration_ticks": 45, "aim_y": 0.6}}}
                """);
        WorkAnimation chop = data.forSpecies(WorkAnimations.CHOP, SCIZOR);
        assertEquals("cobblemon:furycutter", chop.moveEffect().toString());
        assertEquals(18, chop.impactTicks());
        assertEquals(45, chop.durationTicks());
        assertEquals(0.6, chop.aimY(), 1e-9);
        assertEquals(WorkStance.AGAINST, chop.stance(), "what it doesn't change stays the default");
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void everyOtherSpeciesAndAnimationKeepsTheDefault() {
        WorkAnimationData data =
                load("addon:scizor", "{\"species\": \"scizor\", \"animations\": {\"chop\": {\"aim_y\": 0.9}}}");
        assertSame(WorkAnimations.CHOP, data.forSpecies(WorkAnimations.CHOP, "cobblemon:machop"));
        assertSame(WorkAnimations.HARVEST, data.forSpecies(WorkAnimations.HARVEST, SCIZOR));
    }

    @Test
    void aStanceIsNamedOrGivenPartByPart() {
        WorkAnimationData data = load("addon:scizor", """
                {"species": ["scizor", "myaddon:robot"], "animations": {
                    "chop": {"stance": "near"},
                    "harvest": {"stance": {"gap": 2.5, "max_ring": 5}}}}
                """);
        assertEquals(
                WorkStance.NEAR, data.forSpecies(WorkAnimations.CHOP, SCIZOR).stance());
        assertEquals(
                new WorkStance(2.5, 2, 5, 2.0),
                data.forSpecies(WorkAnimations.HARVEST, "myaddon:robot").stance(),
                "the rest from the default stance");
    }

    @Test
    void laterFilesWinWhereTheyChangeTheSameThing() {
        WorkAnimationData data = load(
                "b:second", "{\"species\": \"scizor\", \"animations\": {\"chop\": {\"aim_y\": 0.9}}}",
                "a:first",
                        "{\"species\": \"scizor\", \"animations\": {\"chop\": {\"aim_y\": 0.1, \"impact_ticks\": 20}}}");
        WorkAnimation chop = data.forSpecies(WorkAnimations.CHOP, SCIZOR);
        assertEquals(0.9, chop.aimY(), 1e-9, "b:second sorts last");
        assertEquals(20, chop.impactTicks(), "and what only the first changes is kept");
    }

    @Test
    void mistakesAreSkippedWithAWarningAndTheRestStillLoads() {
        WorkAnimationData data = load("addon:bad", """
                {"species": "scizor", "animations": {
                    "dance": {"aim_y": 1},
                    "harvest": {"impact_ticks": 90},
                    "plant": {"stance": "sideways"},
                    "chop": {"aim_y": 0.7, "colour": "red"}}}
                """, "addon:nothing", "[]");
        assertEquals(0.7, data.forSpecies(WorkAnimations.CHOP, SCIZOR).aimY(), 1e-9);
        assertSame(WorkAnimations.HARVEST, data.forSpecies(WorkAnimations.HARVEST, SCIZOR), "lands after it ends");
        assertSame(WorkAnimations.PLANT, data.forSpecies(WorkAnimations.PLANT, SCIZOR));
        assertEquals(5, warnings.size(), warnings.toString());
    }
}
