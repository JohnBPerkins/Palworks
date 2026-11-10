package dev.palworks.gametest;

import com.cobblemon.mod.common.CobblemonBlocks;
import com.cobblemon.mod.common.CobblemonItems;
import dev.palworks.ai.WorkPositions;
import dev.palworks.crop.CropAdapter;
import dev.palworks.job.JobType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Fruit hanging off the side of a block: cocoa pods on jungle logs, Cobblemon's apricorns on
 * apricorn leaves. They're harvested in place, and picked from the ground beneath them.
 */
public class HangingFruitGameTests {

    private static final BlockPos START = new BlockPos(3, 1, 1);

    private static BlockState ripeApricorn(Direction facing) {
        return CobblemonBlocks.RED_APRICORN.defaultBlockState()
            .setValue(BlockStateProperties.AGE_3, BlockStateProperties.MAX_AGE_3)
            .setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
    }

    private static BlockState cocoa(Direction facing, int age) {
        return Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.FACING, facing).setValue(CocoaBlock.AGE, age);
    }

    /** Persistent, so it doesn't decay with no apricorn log near it during the test. */
    private static final BlockState LEAVES =
        CobblemonBlocks.APRICORN_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);

    // ---- Harvesting ----

    @GameTest(template = Templates.EMPTY)
    public void apricornsArePickedAndStayOnTheirLeaves(GameTestHelper helper) {
        BlockPos fruit = new BlockPos(3, 2, 3);
        helper.setBlock(fruit.north(), LEAVES);
        helper.setBlock(fruit, ripeApricorn(Direction.NORTH));
        var level = helper.getLevel();
        helper.assertTrue(CropAdapter.isFullyGrown(level, helper.absolutePos(fruit)), "a ripe apricorn");

        helper.assertTrue(CropAdapter.harvestCrop(level, helper.absolutePos(fruit)), "it harvests");
        BlockState after = helper.getBlockState(fruit);
        helper.assertTrue(after.is(CobblemonBlocks.RED_APRICORN), "still there, got " + after);
        helper.assertTrue(after.getValue(BlockStateProperties.AGE_3) == 0, "back to its first stage");
        helper.assertTrue(after.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.NORTH, "on the same leaves");
        // It used to go through the crops' "pay one seed for the replant" path, which took the one
        // apricorn it dropped (its pick item is the apricorn)
        helper.assertItemEntityPresent(CobblemonItems.RED_APRICORN, fruit, 3.0);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void unripeApricornsAreLeftAlone(GameTestHelper helper) {
        BlockPos fruit = new BlockPos(3, 2, 3);
        helper.setBlock(fruit.north(), LEAVES);
        BlockState young = ripeApricorn(Direction.NORTH).setValue(BlockStateProperties.AGE_3, 2);
        helper.setBlock(fruit, young);

        helper.assertFalse(CropAdapter.isFullyGrown(helper.getLevel(), helper.absolutePos(fruit)), "not ripe");
        helper.assertFalse(CropAdapter.harvestCrop(helper.getLevel(), helper.absolutePos(fruit)), "not harvested");
        helper.assertTrue(helper.getBlockState(fruit).equals(young), "untouched");
        helper.succeed();
    }

    // ---- Where to stand ----

    @GameTest(template = Templates.EMPTY)
    public void hangingFruitIsPickedFromTheGroundBeneathIt(GameTestHelper helper) {
        // Both face what they hang from, like a furnace faces its front. Taken for stations, a
        // worker was sent to stand "in front" of the pod: inside the log.
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(3, y, 5, Blocks.JUNGLE_LOG);
        }
        BlockPos pod = new BlockPos(3, 3, 4); // two up the trunk, on its north side
        helper.setBlock(pod, cocoa(Direction.SOUTH, 2));
        BlockPos apricorn = new BlockPos(6, 4, 3); // three up, under leaves
        helper.setBlock(apricorn.above(), LEAVES);
        helper.setBlock(apricorn.east(), LEAVES);
        helper.setBlock(apricorn, ripeApricorn(Direction.EAST));
        Mob mob = helper.spawnWithNoFreeWill(EntityType.PIG, START);
        var level = helper.getLevel();

        for (BlockPos fruit : List.of(pod, apricorn)) {
            BlockPos at = helper.absolutePos(fruit);
            helper.assertFalse(WorkPositions.isDirectional(level, at), fruit + " isn't a station");
            BlockPos stand = WorkPositions.standingPosFor(level, at, JobType.HARVEST_CROPS, mob);
            helper.assertTrue(stand != null, "somewhere to stand for " + fruit);
            helper.assertTrue(stand.getY() == helper.absolutePos(START).getY(),
                "on the ground for " + fruit + ", got " + helper.relativePos(stand));
            helper.assertTrue(WorkPositions.fits(level, stand, mob), "a spot it fits on for " + fruit);
        }
        helper.succeed();
    }

    // ---- Real workers ----

    @GameTest(template = Templates.EMPTY, batch = "worker_cocoa", timeoutTicks = 800)
    public void grassTypeHarvestsCocoaUpATrunk(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(3, y, 5, Blocks.JUNGLE_LOG);
        }
        // At foot height, head height and two blocks up: where cocoa grows on a jungle tree
        List<BlockPos> pods = List.of(new BlockPos(3, 1, 4), new BlockPos(2, 2, 5), new BlockPos(4, 3, 5));
        helper.setBlock(pods.get(0), cocoa(Direction.SOUTH, 2));
        helper.setBlock(pods.get(1), cocoa(Direction.EAST, 2));
        helper.setBlock(pods.get(2), cocoa(Direction.WEST, 2));
        Workers.sendOut(helper, "bulbasaur level=20", START);

        helper.succeedWhen(() -> {
            for (BlockPos pod : pods) {
                BlockState state = helper.getBlockState(pod);
                helper.assertTrue(state.is(Blocks.COCOA), "the pod at " + pod + " is still on its log");
                helper.assertTrue(state.getValue(CocoaBlock.AGE) < 2, "the pod at " + pod + " was harvested");
            }
            helper.assertTrue(Scenes.itemsOnGround(helper, Items.COCOA_BEANS) >= pods.size(), "beans dropped");
        });
    }

    @GameTest(template = Templates.ARENA, batch = "worker_apricorn", timeoutTicks = 1200)
    public void grassTypeHarvestsARealApricornTree(GameTestHelper helper) {
        Workers.isolate(helper);
        RealTreeGameTests.arenaFloor(helper, Blocks.GRASS_BLOCK);
        RealTreeGameTests.grow(helper, ResourceKey.create(Registries.CONFIGURED_FEATURE,
            ResourceLocation.fromNamespaceAndPath("cobblemon", "red_apricorn_tree")), 1);
        // A tree grown outside worldgen has unripe apricorns: ripen them
        List<BlockPos> apricorns = new ArrayList<>();
        for (BlockPos rel : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(15, 12, 15))) {
            BlockState state = helper.getBlockState(rel);
            if (CropAdapter.isApricorn(state)) {
                helper.setBlock(rel, state.setValue(BlockStateProperties.AGE_3, BlockStateProperties.MAX_AGE_3));
                apricorns.add(rel.immutable());
            }
        }
        helper.assertTrue(!apricorns.isEmpty(), "the tree grew apricorns");
        Workers.sendOut(helper, "bulbasaur level=20", new BlockPos(8, 1, 2));

        helper.succeedWhen(() -> {
            for (BlockPos rel : apricorns) {
                BlockState state = helper.getBlockState(rel);
                helper.assertTrue(CropAdapter.isApricorn(state), "the apricorn at " + rel + " is still on the tree");
                helper.assertFalse(CropAdapter.isFullyGrown(helper.getLevel(), helper.absolutePos(rel)),
                    "the apricorn at " + rel + " was picked");
            }
            int dropped = itemsInArena(helper, CobblemonItems.RED_APRICORN);
            helper.assertTrue(dropped >= apricorns.size(),
                "one apricorn each (" + apricorns.size() + "), got " + dropped);
        });
    }

    private static int itemsInArena(GameTestHelper helper, Item item) {
        int total = 0;
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds().inflate(2))) {
            if (entity.getItem().is(item)) {
                total += entity.getItem().getCount();
            }
        }
        return total;
    }
}
