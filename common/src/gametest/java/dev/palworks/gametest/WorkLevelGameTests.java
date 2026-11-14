package dev.palworks.gametest;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityDetector;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Real Pokémon's work levels from the shipped datapack files and Cobblemon's species data. */
public class WorkLevelGameTests {

    private static int level(String properties, Suitability suitability) {
        Pokemon pokemon = PokemonProperties.Companion.parse(properties).create();
        return SuitabilityDetector.getSuitabilityLevel(pokemon, suitability);
    }

    private static void assertLevel(GameTestHelper helper, String what, int expected, int actual) {
        helper.assertTrue(actual == expected, what + " should be " + expected + ", but is " + actual);
    }

    @GameTest(template = Templates.EMPTY)
    public void speciesEntriesFollowBaseStats(GameTestHelper helper) {
        assertLevel(helper, "Kingambit's Lumbering (550)", 4, level("kingambit", Suitability.LUMBERING));
        assertLevel(helper, "Pawniard's Lumbering (340)", 1, level("pawniard", Suitability.LUMBERING));
        assertLevel(helper, "Lucario's Handiwork (525)", 3, level("lucario", Suitability.HANDIWORK));
        assertLevel(helper, "Magnemite's Lumbering (Steel isn't)", 0, level("magnemite", Suitability.LUMBERING));
        helper.succeed();
    }
}
