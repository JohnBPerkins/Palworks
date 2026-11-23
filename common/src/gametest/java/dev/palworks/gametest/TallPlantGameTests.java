package dev.palworks.gametest;

import dev.palworks.crop.TallPlants;
import dev.palworks.job.JobChecks;
import dev.palworks.job.JobType;
import dev.palworks.suitability.Suitability;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Sugar cane, cactus, bamboo, kelp and nether vines (TallPlants): ripe once their second block
 * grows, and cut down to the block they grow from, which stays to grow again.
 */
public class TallPlantGameTests {
    private static final String BULBASAUR = "bulbasaur level=20"; // Grass: Gathering
    private static final BlockPos START = new BlockPos(1, 1, 1);
    private static final BlockPos BASE = new BlockPos(3, 1, 3);

    private static JobType ripeJob(GameTestHelper helper, BlockPos pos) {
        return TallPlants.ripeJobAt(helper.getLevel(), helper.absolutePos(pos));
    }

    private static int harvest(GameTestHelper helper, BlockPos pos) {
        return TallPlants.harvest(helper.getLevel(), helper.absolutePos(pos), null);
    }

    /** A column of {@code blocks} from {@code from}, going up. */
    private static void column(GameTestHelper helper, BlockPos from, Block... blocks) {
        for (int i = 0; i < blocks.length; i++) {
            helper.setBlock(from.above(i), blocks[i]);
        }
    }

    /** Sugar cane on sand, by water. */
    private static void sugarCane(GameTestHelper helper, int height) {
        helper.setBlock(BASE.below(), Blocks.SAND);
        helper.setBlock(BASE.below().east(), Blocks.WATER);
        for (int i = 0; i < height; i++) {
            helper.setBlock(BASE.above(i), Blocks.SUGAR_CANE);
        }
    }

    /**
     * A plant that grows up from {@code BASE}: ripe at its second block only, and harvesting it
     * there leaves the base and drops what was cut.
     */
    private static void assertCutToItsBase(GameTestHelper helper, JobType job, Block base, int cut, Item drop) {
        helper.assertTrue(ripeJob(helper, BASE) == null, "the base is never the job");
        helper.assertTrue(ripeJob(helper, BASE.above()) == job, "its second block is a " + job + " job");
        helper.assertTrue(ripeJob(helper, BASE.above(2)) == null, "nor is any block above it");
        helper.assertTrue(harvest(helper, BASE.above()) == cut, "it cuts every block but the base");
        helper.assertBlockPresent(base, BASE);
        for (int i = 1; i <= cut; i++) {
            helper.assertTrue(TallPlants.jobFor(helper.getBlockState(BASE.above(i))) == null, "block " + i + " is cut");
        }
        if (drop != null) {
            helper.assertItemEntityPresent(drop, BASE.above(), 3.0);
        }
    }

    @GameTest(template = Templates.EMPTY)
    public void sugarCaneIsHarvestedOnceItsSecondBlockGrows(GameTestHelper helper) {
        sugarCane(helper, 1);
        helper.assertTrue(ripeJob(helper, BASE) == null, "one block of cane isn't ripe");
        sugarCane(helper, 3);
        assertCutToItsBase(helper, JobType.HARVEST_SUGAR_CANE, Blocks.SUGAR_CANE, 2, Items.SUGAR_CANE);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void cactusIsCutToItsBase(GameTestHelper helper) {
        helper.setBlock(BASE.below(), Blocks.SAND);
        column(helper, BASE, Blocks.CACTUS, Blocks.CACTUS);
        assertCutToItsBase(helper, JobType.HARVEST_CACTUS, Blocks.CACTUS, 1, Items.CACTUS);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void bambooIsCutToItsBase(GameTestHelper helper) {
        helper.setBlock(BASE.below(), Blocks.GRASS_BLOCK);
        column(helper, BASE, Blocks.BAMBOO, Blocks.BAMBOO, Blocks.BAMBOO);
        assertCutToItsBase(helper, JobType.HARVEST_BAMBOO, Blocks.BAMBOO, 2, Items.BAMBOO);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void kelpIsCutToItsBaseAndLeavesWater(GameTestHelper helper) {
        kelpPool(helper);
        assertCutToItsBase(helper, JobType.HARVEST_KELP, Blocks.KELP, 1, Items.KELP);
        helper.assertBlockPresent(Blocks.WATER, BASE.above());
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void twistingVinesAreCutToTheirBase(GameTestHelper helper) {
        helper.setBlock(BASE.below(), Blocks.NETHERRACK);
        column(helper, BASE, Blocks.TWISTING_VINES_PLANT, Blocks.TWISTING_VINES_PLANT, Blocks.TWISTING_VINES);
        // A vine drops itself a third of the time, as by hand: no drop to check for
        assertCutToItsBase(helper, JobType.HARVEST_NETHER_VINES, Blocks.TWISTING_VINES, 2, null);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void weepingVinesAreCutUpToTheirBaseOnTheCeiling(GameTestHelper helper) {
        BlockPos base = weepingVines(helper);
        BlockPos second = base.below();
        helper.assertTrue(ripeJob(helper, base) == null, "the base, on the ceiling, is never the job");
        helper.assertTrue(ripeJob(helper, second) == JobType.HARVEST_NETHER_VINES, "the block under it is");
        helper.assertTrue(harvest(helper, second) == 2, "it cuts everything hanging under the base");
        helper.assertBlockPresent(Blocks.WEEPING_VINES, base);
        helper.assertBlockPresent(Blocks.AIR, second);
        helper.assertBlockPresent(Blocks.AIR, second.below());
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void theirItemsSayGatheringWorksThem(GameTestHelper helper) {
        for (Item item : new Item[] {
            Items.SUGAR_CANE, Items.CACTUS, Items.BAMBOO, Items.KELP, Items.WEEPING_VINES, Items.TWISTING_VINES
        }) {
            helper.assertTrue(
                    JobChecks.workedWith(item).contains(Suitability.GATHERING), item + " is worked by Gathering");
        }
        helper.succeed();
    }

    // ---- A worker at them ----

    @GameTest(template = Templates.EMPTY, timeoutTicks = 600)
    public void aGathererCutsSugarCaneDownToItsBase(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        sugarCane(helper, 3);
        Workers.sendOut(helper, BULBASAUR, START);
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, BASE.above());
            helper.assertBlockPresent(Blocks.SUGAR_CANE, BASE);
        });
    }

    @GameTest(template = Templates.EMPTY, timeoutTicks = 600)
    public void aGathererCutsKelpFromBesideItsPool(GameTestHelper helper) {
        // From the shore two blocks off: the kelp's top is in sight, its middle just below the
        // shore's edge
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        kelpShore(helper);
        Workers.sendOut(helper, BULBASAUR, START.above(2));
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.WATER, BASE.above());
            helper.assertBlockPresent(Blocks.KELP, BASE);
        });
    }

    @GameTest(template = Templates.EMPTY, timeoutTicks = 600)
    public void aGathererCutsWeepingVinesUnderALowCeiling(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        BlockPos base = weepingVines(helper);
        Workers.sendOut(helper, BULBASAUR, START);
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, base.below());
            helper.assertBlockPresent(Blocks.WEEPING_VINES, base);
        });
    }

    /** Kelp two blocks high in a one-block pool walled in by stone, its top at the water's top. */
    private static void kelpPool(GameTestHelper helper) {
        for (int y = 1; y <= 2; y++) {
            helper.setBlock(BASE.atY(y).north(), Blocks.STONE);
            helper.setBlock(BASE.atY(y).south(), Blocks.STONE);
            helper.setBlock(BASE.atY(y).east(), Blocks.STONE);
            helper.setBlock(BASE.atY(y).west(), Blocks.STONE);
        }
        helper.setBlock(BASE.below(), Blocks.STONE);
        column(helper, BASE, Blocks.KELP_PLANT, Blocks.KELP);
    }

    /** Kelp two blocks high in a one-block hole in the ground, its top level with the shore's. */
    private static void kelpShore(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                if (x != BASE.getX() || z != BASE.getZ()) {
                    helper.setBlock(x, 1, z, Blocks.DIRT);
                    helper.setBlock(x, 2, z, Blocks.GRASS_BLOCK);
                }
            }
        }
        helper.setBlock(BASE.below(), Blocks.STONE);
        column(helper, BASE, Blocks.KELP_PLANT, Blocks.KELP);
    }

    /**
     * Weeping vines three blocks long from a netherrack ceiling four blocks up, down to the floor's
     * level (y 1, 2 and 3); returns their base, on the ceiling.
     */
    private static BlockPos weepingVines(GameTestHelper helper) {
        BlockPos ceiling = BASE.above(3);
        helper.setBlock(ceiling, Blocks.NETHERRACK);
        BlockPos base = ceiling.below();
        helper.setBlock(base, Blocks.WEEPING_VINES_PLANT);
        helper.setBlock(base.below(), Blocks.WEEPING_VINES_PLANT);
        helper.setBlock(base.below(2), Blocks.WEEPING_VINES);
        return base;
    }
}
