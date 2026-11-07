package dev.palworks.tree;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding a tree's sapling by name against the real registries, and the block checks that don't
 * need tags. Anything that checks a block tag (logs, leaves, ground) needs a loaded world, so it's
 * covered by the GameTests instead.
 */
class SaplingLookupTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyVanillaTreeBlockFindsItsSapling() {
        Map<Block, Item> expected = Map.ofEntries(
            Map.entry(Blocks.OAK_LOG, Items.OAK_SAPLING),
            Map.entry(Blocks.STRIPPED_OAK_LOG, Items.OAK_SAPLING),
            Map.entry(Blocks.OAK_WOOD, Items.OAK_SAPLING),
            Map.entry(Blocks.OAK_LEAVES, Items.OAK_SAPLING),
            Map.entry(Blocks.SPRUCE_LOG, Items.SPRUCE_SAPLING),
            Map.entry(Blocks.BIRCH_LOG, Items.BIRCH_SAPLING),
            Map.entry(Blocks.JUNGLE_LOG, Items.JUNGLE_SAPLING),
            Map.entry(Blocks.ACACIA_LOG, Items.ACACIA_SAPLING),
            Map.entry(Blocks.DARK_OAK_LOG, Items.DARK_OAK_SAPLING),
            Map.entry(Blocks.STRIPPED_DARK_OAK_WOOD, Items.DARK_OAK_SAPLING),
            Map.entry(Blocks.CHERRY_LOG, Items.CHERRY_SAPLING),
            Map.entry(Blocks.CHERRY_LEAVES, Items.CHERRY_SAPLING),
            Map.entry(Blocks.MANGROVE_LOG, Items.MANGROVE_PROPAGULE),
            Map.entry(Blocks.MANGROVE_ROOTS, Items.MANGROVE_PROPAGULE),
            Map.entry(Blocks.MANGROVE_LEAVES, Items.MANGROVE_PROPAGULE),
            Map.entry(Blocks.CRIMSON_STEM, Items.CRIMSON_FUNGUS),
            Map.entry(Blocks.STRIPPED_CRIMSON_HYPHAE, Items.CRIMSON_FUNGUS),
            Map.entry(Blocks.WARPED_STEM, Items.WARPED_FUNGUS),
            Map.entry(Blocks.WARPED_HYPHAE, Items.WARPED_FUNGUS));

        expected.forEach((block, sapling) ->
            assertSame(sapling, TreeDetector.saplingFor(block), block + " should find " + sapling));
    }

    @Test
    void blocksWithNoMatchingSaplingFindNothing() {
        assertNull(TreeDetector.saplingFor(Blocks.STONE));
        assertNull(TreeDetector.saplingFor(Blocks.OAK_PLANKS), "oak_planks_sapling doesn't exist");
        assertNull(TreeDetector.saplingFor(Blocks.NETHER_WART_BLOCK));
    }

    @Test
    void theSaplingItemPlacesItsSaplingBlock() {
        assertSame(Blocks.OAK_SAPLING, TreeDetector.getSaplingBlock(Items.OAK_SAPLING));
        assertSame(Blocks.MANGROVE_PROPAGULE, TreeDetector.getSaplingBlock(Items.MANGROVE_PROPAGULE));
        assertSame(Blocks.CRIMSON_FUNGUS, TreeDetector.getSaplingBlock(Items.CRIMSON_FUNGUS));
        assertNull(TreeDetector.getSaplingBlock(Items.STICK), "not a block item");
        assertNull(TreeDetector.getSaplingBlock(null));
    }

    @Test
    void mangroveRootsArePartOfTheTreeButMuddyRootsAreNot() {
        assertTrue(TreeDetector.isMangroveRoots(Blocks.MANGROVE_ROOTS.defaultBlockState()));
        assertFalse(TreeDetector.isMangroveRoots(Blocks.MUDDY_MANGROVE_ROOTS.defaultBlockState()),
            "muddy roots are ground, not tree");
    }

    @Test
    void sameBlockAlwaysFindsTheSameSapling() {
        // The lookup is cached per block; a second call must agree with the first
        assertEquals(TreeDetector.saplingFor(Blocks.BIRCH_LOG), TreeDetector.saplingFor(Blocks.BIRCH_LOG));
    }
}
