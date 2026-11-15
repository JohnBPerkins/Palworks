package dev.palworks.gametest;

import com.cobblemon.mod.common.block.TypeGemClusterBlock;
import dev.palworks.crystal.CrystalAdapter;
import dev.palworks.job.JobChecks;
import dev.palworks.job.JobType;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * Mining crystals (see CrystalAdapter): only grown ones, for what breaking them by hand gives,
 * leaving what grows them; a tumblestone put back from its own drops.
 */
public class CrystalGameTests {
    private static final BlockPos CRYSTAL = new BlockPos(3, 2, 3);
    private static final BlockPos START = new BlockPos(1, 1, 1);
    private static final String IT_MINES = "it mines";
    private static final String TUMBLESTONE = "tumblestone";
    private static final String TUMBLESTONE_CLUSTER = "tumblestone_cluster";

    // Cobblemon's blocks and items by id: some of their fields are Kotlin getters in 1.8.0 and
    // fields in 1.8.4. Looked up when a test runs, after Cobblemon has registered them.
    private static Block block(String path) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("cobblemon", path));
    }

    private static Item item(String path) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("cobblemon", path));
    }

    private static BlockState facing(Block block, Direction face) {
        return block.defaultBlockState().setValue(BlockStateProperties.FACING, face);
    }

    private static BlockState gemCluster(String type, int stage) {
        IntegerProperty stageProperty = TypeGemClusterBlock.Companion.getSTAGE();
        return facing(block(type + "_gem_cluster"), Direction.UP).setValue(stageProperty, stage);
    }

    private static boolean mine(GameTestHelper helper, BlockPos pos) {
        return CrystalAdapter.harvest(helper.getLevel(), helper.absolutePos(pos), null);
    }

    /** What breaking the block at {@code pos} by hand would drop of {@code item}. */
    private static int byHand(GameTestHelper helper, BlockPos pos, Item item) {
        BlockPos abs = helper.absolutePos(pos);
        int count = 0;
        for (ItemStack drop : Block.getDrops(
                helper.getLevel().getBlockState(abs), helper.getLevel(), abs, null, null, ItemStack.EMPTY)) {
            if (drop.is(item)) {
                count += drop.getCount();
            }
        }
        return count;
    }

    @GameTest(template = Templates.EMPTY)
    public void anAmethystClusterGivesItsShardsAndItsBuddingAmethystStays(GameTestHelper helper) {
        helper.setBlock(CRYSTAL.below(), Blocks.BUDDING_AMETHYST);
        helper.setBlock(CRYSTAL, facing(Blocks.AMETHYST_CLUSTER, Direction.UP));
        int shards = byHand(helper, CRYSTAL, Items.AMETHYST_SHARD);

        helper.assertTrue(mine(helper, CRYSTAL), IT_MINES);
        helper.assertBlockPresent(Blocks.AIR, CRYSTAL);
        helper.assertBlockPresent(Blocks.BUDDING_AMETHYST, CRYSTAL.below());
        helper.assertValueEqual(Scenes.itemsOnGround(helper, Items.AMETHYST_SHARD), shards, "shards, as by hand");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void aTumblestoneClusterIsPutBackFromOneOfItsTumblestones(GameTestHelper helper) {
        // Each colour, one on the ground and one on a wall: 4 by hand, one put back the way it faced
        List<String> colours = List.of("", "sky_", "black_");
        for (int i = 0; i < colours.size(); i++) {
            String colour = colours.get(i);
            Item tumblestone = item(colour + TUMBLESTONE);
            Block seedling = block("small_budding_" + colour + TUMBLESTONE);
            BlockPos onGround = new BlockPos(1 + i * 3, 2, 1);
            BlockPos onWall = new BlockPos(1 + i * 3, 2, 5);
            helper.setBlock(onGround.below(), Blocks.STONE);
            helper.setBlock(onWall.west(), Blocks.STONE);
            helper.setBlock(onGround, facing(block(colour + TUMBLESTONE_CLUSTER), Direction.UP));
            helper.setBlock(onWall, facing(block(colour + TUMBLESTONE_CLUSTER), Direction.EAST));
            int byHand = byHand(helper, onGround, tumblestone) + byHand(helper, onWall, tumblestone);

            helper.assertTrue(mine(helper, onGround) && mine(helper, onWall), IT_MINES);
            helper.assertTrue(
                    helper.getBlockState(onGround).equals(facing(seedling, Direction.UP)),
                    "a new one on the ground, got " + helper.getBlockState(onGround));
            helper.assertTrue(
                    helper.getBlockState(onWall).equals(facing(seedling, Direction.EAST)),
                    "a new one on the wall, got " + helper.getBlockState(onWall));
            helper.assertValueEqual(
                    Scenes.itemsOnGround(helper, tumblestone),
                    byHand - 2,
                    colour + TUMBLESTONE + ": by hand, less the two put back");
        }
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void aTumblestoneUnderWaterIsntPutBack(GameTestHelper helper) {
        // Broken by hand it leaves water, and a tumblestone can only be put in air
        helper.setBlock(CRYSTAL.below(), Blocks.STONE);
        helper.setBlock(
                CRYSTAL,
                facing(block(TUMBLESTONE_CLUSTER), Direction.UP).setValue(BlockStateProperties.WATERLOGGED, true));
        int byHand = byHand(helper, CRYSTAL, item(TUMBLESTONE));

        helper.assertTrue(mine(helper, CRYSTAL), IT_MINES);
        helper.assertBlockPresent(Blocks.WATER, CRYSTAL);
        helper.assertValueEqual(Scenes.itemsOnGround(helper, item(TUMBLESTONE)), byHand, "all of them");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void aGrownGemClusterGivesItsGemsAndItsGemBlockStays(GameTestHelper helper) {
        helper.setBlock(CRYSTAL.below(2), block("deepslate_crystal_core"));
        helper.setBlock(CRYSTAL.below(), block("fire_gem_block"));
        IntegerProperty stage = TypeGemClusterBlock.Companion.getSTAGE();
        helper.setBlock(CRYSTAL, gemCluster("fire", Collections.max(stage.getPossibleValues())));

        helper.assertTrue(mine(helper, CRYSTAL), IT_MINES);
        helper.assertBlockPresent(Blocks.AIR, CRYSTAL);
        helper.assertBlockPresent(block("fire_gem_block"), CRYSTAL.below());
        helper.assertBlockPresent(block("deepslate_crystal_core"), CRYSTAL.below(2));
        // Its loot: 2-4 gems, by hand
        int gems = Scenes.itemsOnGround(helper, item("fire_gem"));
        helper.assertTrue(gems >= 2 && gems <= 4, "2-4 fire gems, got " + gems);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void crystalsStillGrowingAreLeftAlone(GameTestHelper helper) {
        var level = helper.getLevel();
        List<BlockState> growing = List.of(
                facing(Blocks.LARGE_AMETHYST_BUD, Direction.UP),
                facing(block("large_budding_tumblestone"), Direction.UP),
                gemCluster("fire", 2));
        for (BlockState state : growing) {
            helper.assertFalse(CrystalAdapter.isRipe(state), state + " isn't grown");
        }
        // Nor what they grow from
        for (Block block : List.of(Blocks.BUDDING_AMETHYST, block("fire_gem_block"), block("deepslate_crystal_core"))) {
            helper.assertFalse(CrystalAdapter.isRipe(block.defaultBlockState()), block + " isn't a crystal to mine");
        }
        helper.setBlock(CRYSTAL.below(), Blocks.STONE);
        helper.setBlock(CRYSTAL, growing.get(1));
        helper.assertFalse(
                JobChecks.isJobStillThere(level, JobType.MINE_CRYSTAL, helper.absolutePos(CRYSTAL)), "no job");
        helper.assertFalse(mine(helper, CRYSTAL), "nothing to mine");
        helper.assertBlockPresent(block("large_budding_tumblestone"), CRYSTAL);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = "crystal_miner", timeoutTicks = 800)
    public void aMiningWorkerMinesGrownCrystalsOnly(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.STONE);
        BlockPos amethyst = new BlockPos(2, 2, 5);
        BlockPos tumblestone = new BlockPos(5, 1, 5);
        BlockPos bud = new BlockPos(5, 1, 2);
        helper.setBlock(amethyst.below(), Blocks.BUDDING_AMETHYST);
        helper.setBlock(amethyst, facing(Blocks.AMETHYST_CLUSTER, Direction.UP));
        helper.setBlock(tumblestone, facing(block(TUMBLESTONE_CLUSTER), Direction.UP));
        helper.setBlock(bud, facing(Blocks.LARGE_AMETHYST_BUD, Direction.UP));
        Workers.sendOut(helper, "geodude level=20", START); // Rock/Ground: Mining

        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, amethyst);
            helper.assertBlockPresent(block("small_budding_tumblestone"), tumblestone);
            helper.assertBlockPresent(Blocks.LARGE_AMETHYST_BUD, bud);
            helper.assertTrue(Scenes.itemsOnGround(helper, Items.AMETHYST_SHARD) > 0, "its shards");
        });
    }
}
