package dev.palworks.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SaplingNamesTest {

    @Test
    void treeNameStripsVariantsDownToTheTree() {
        assertEquals("oak", SaplingNames.treeName("oak_log"));
        assertEquals("dark_oak", SaplingNames.treeName("stripped_dark_oak_log"));
        assertEquals("birch", SaplingNames.treeName("birch_wood"));
        assertEquals("spruce", SaplingNames.treeName("stripped_spruce_wood"));
        assertEquals("crimson", SaplingNames.treeName("crimson_stem"));
        assertEquals("warped", SaplingNames.treeName("stripped_warped_hyphae"));
        assertEquals("mangrove", SaplingNames.treeName("mangrove_roots"));
        assertEquals("cherry", SaplingNames.treeName("cherry_leaves"));
        // Modded trees named the usual way
        assertEquals("saccharine", SaplingNames.treeName("saccharine_log"));
        assertEquals("redwood", SaplingNames.treeName("redwood_log"));
    }

    @Test
    void everyVanillaTreesSaplingIsACandidate() {
        assertCandidate("oak_log", "oak_sapling");
        assertCandidate("spruce_log", "spruce_sapling");
        assertCandidate("birch_log", "birch_sapling");
        assertCandidate("jungle_log", "jungle_sapling");
        assertCandidate("acacia_log", "acacia_sapling");
        assertCandidate("dark_oak_log", "dark_oak_sapling");
        assertCandidate("cherry_log", "cherry_sapling");
        assertCandidate("mangrove_log", "mangrove_propagule");
        assertCandidate("mangrove_roots", "mangrove_propagule");
        assertCandidate("crimson_stem", "crimson_fungus");
        assertCandidate("warped_stem", "warped_fungus");
        // Also from stripped logs, wood and leaves
        assertCandidate("stripped_oak_log", "oak_sapling");
        assertCandidate("dark_oak_wood", "dark_oak_sapling");
        assertCandidate("jungle_leaves", "jungle_sapling");
    }

    @Test
    void saplingsAreTriedFirst() {
        assertEquals("oak_sapling", SaplingNames.saplingCandidates("oak_log").get(0));
    }

    @Test
    void unrecognisedNamesStillGiveCandidates() {
        // No known suffix: the whole name is used, and the registry check simply finds nothing
        assertEquals(
                "mystery_block_sapling",
                SaplingNames.saplingCandidates("mystery_block").get(0));
    }

    private static void assertCandidate(String blockPath, String sapling) {
        assertTrue(
                SaplingNames.saplingCandidates(blockPath).contains(sapling),
                blockPath + " should give " + sapling + ", got " + SaplingNames.saplingCandidates(blockPath));
    }
}
