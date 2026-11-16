package dev.palworks.gametest;

import com.cobblemon.mod.common.block.BerryBlock;
import com.cobblemon.mod.common.block.NutBushBlock;
import com.cobblemon.mod.common.block.RootBlock;
import com.cobblemon.mod.common.block.TypeGemClusterBlock;
import com.cobblemon.mod.common.block.entity.BerryBlockEntity;
import dev.palworks.block.TroughBlock;
import dev.palworks.block.TroughBlockEntity;
import dev.palworks.block.WorkerBedBlock;
import dev.palworks.crystal.CrystalAdapter;
import dev.palworks.debug.TestArea;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

/** The /palworks testarea build: everything placed, fully grown, and still there a moment later. */
public class TestAreaGameTests {
    // The furthest a natural leaf may be from a log (through leaves) before it decays
    private static final int MAX_LEAF_DISTANCE = 6;

    /** Whether a plant's "age" (if it has one) is its highest. */
    private static boolean fullyGrown(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty age && "age".equals(property.getName())) {
                return state.getValue(age).equals(Collections.max(age.getPossibleValues()));
            }
        }
        return true;
    }

    private record Survey(int stations, int stationsReady, Set<Block> plants, Set<String> notGrown) {}

    private static Survey survey(GameTestHelper helper, TestArea.Result area) {
        var level = helper.getLevel();
        int stations = 0;
        int ready = 0;
        Set<Block> plants = new HashSet<>();
        Set<String> notGrown = new HashSet<>();
        for (BlockPos pos :
                BlockPos.betweenClosed(area.min(), area.max().atY(area.min().getY() + 5))) {
            BlockState state = level.getBlockState(pos);
            Block block = state.getBlock();
            if (level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace) {
                stations++;
                if (!furnace.getItem(0).isEmpty() && furnace.getItem(1).isEmpty()) {
                    ready++;
                }
            }
            boolean plant = block instanceof CropBlock
                    || block instanceof BerryBlock
                    || block instanceof NutBushBlock
                    || block instanceof RootBlock
                    || state.is(Blocks.NETHER_WART)
                    || state.is(Blocks.COCOA)
                    || state.is(Blocks.SWEET_BERRY_BUSH)
                    || state.is(Blocks.PITCHER_CROP)
                    || state.is(Blocks.TORCHFLOWER)
                    || state.is(Blocks.CAVE_VINES)
                    || state.is(Blocks.ATTACHED_PUMPKIN_STEM)
                    || state.is(Blocks.ATTACHED_MELON_STEM);
            if (plant) {
                plants.add(block);
                if (!fullyGrown(state)) {
                    notGrown.add(BuiltInRegistries.BLOCK.getKey(block) + " at " + pos.toShortString());
                }
            }
        }
        return new Survey(stations, ready, plants, notGrown);
    }

    private static void check(GameTestHelper helper, TestArea.Result area, Set<Block> expected, String when) {
        Survey survey = survey(helper, area);
        helper.assertValueEqual(survey.stations(), 3, "smelting stations (one of each) " + when);
        helper.assertValueEqual(survey.stationsReady(), 3, "stations with input and no fuel " + when);
        helper.assertTrue(survey.notGrown().isEmpty(), "not fully grown " + when + ": " + survey.notGrown());
        Set<Block> missing = new HashSet<>(expected);
        missing.removeAll(survey.plants());
        helper.assertTrue(missing.isEmpty(), "missing " + when + ": " + missing);
    }

    /** The berry bushes that give nothing when harvested. */
    private static List<String> bareBushes(GameTestHelper helper, TestArea.Result area) {
        var level = helper.getLevel();
        List<String> bare = new ArrayList<>();
        for (BlockPos pos :
                BlockPos.betweenClosed(area.min(), area.max().atY(area.min().getY() + 2))) {
            if (level.getBlockEntity(pos) instanceof BerryBlockEntity bush
                    && bush.harvest(level, level.getBlockState(pos), pos.immutable())
                            .isEmpty()) {
                bare.add(BuiltInRegistries.BLOCK
                        .getKey(level.getBlockState(pos).getBlock())
                        .getPath());
            }
        }
        return bare;
    }

    /**
     * Natural leaves that aren't attached to a log the way the game requires (within 6 steps of one
     * through leaves; any further and they decay): leftovers that would float in the air.
     */
    private static List<String> floatingLeaves(GameTestHelper helper, TestArea.Result area) {
        var level = helper.getLevel();
        BlockPos min = area.min().below(2);
        BlockPos max = area.max();
        Map<BlockPos, Integer> distance = new HashMap<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> leaves = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.LEAVES)
                    && state.hasProperty(LeavesBlock.PERSISTENT)
                    && !state.getValue(LeavesBlock.PERSISTENT)) {
                leaves.add(pos.immutable());
            } else if (state.is(BlockTags.LOGS)) {
                distance.put(pos.immutable(), 0);
                queue.add(pos.immutable());
            }
        }
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            int next = distance.get(pos) + 1;
            if (next > MAX_LEAF_DISTANCE) {
                continue;
            }
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = pos.relative(direction);
                if (!distance.containsKey(neighbour)
                        && level.getBlockState(neighbour).is(BlockTags.LEAVES)) {
                    distance.put(neighbour, next);
                    queue.add(neighbour);
                }
            }
        }
        List<String> floating = new ArrayList<>();
        for (BlockPos leaf : leaves) {
            if (!distance.containsKey(leaf)) {
                floating.add(leaf.toShortString());
            }
        }
        return floating;
    }

    /**
     * The ranch's blocks (whole: the pasture and PC with their tops) and the grown crystals: amethyst,
     * each colour of tumblestone, and type gems.
     */
    private static void assertRanchAndCrystals(GameTestHelper helper, TestArea.Result area) {
        var level = helper.getLevel();
        Map<String, Integer> found = new HashMap<>();
        for (BlockPos pos :
                BlockPos.betweenClosed(area.min(), area.max().atY(area.min().getY() + 3))) {
            BlockState state = level.getBlockState(pos);
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            if (CrystalAdapter.isRipe(level, pos, state)) {
                found.merge(state.getBlock() instanceof TypeGemClusterBlock ? "gem cluster" : id, 1, Integer::sum);
            } else if (state.getBlock() instanceof WorkerBedBlock) {
                found.merge("bed", 1, Integer::sum);
            } else if (state.getBlock() instanceof TroughBlock
                    && level.getBlockEntity(pos) instanceof TroughBlockEntity trough
                    && trough.hasFeed()) {
                found.merge("trough with feed", 1, Integer::sum);
            } else if (List.of("healing_machine", "pasture", "pc").contains(id)) {
                found.merge(id, 1, Integer::sum);
            }
            if (!state.getFluidState().isEmpty()
                    && List.of("healing_machine", "pasture", "pc").contains(id)) {
                found.merge("waterlogged " + id, 1, Integer::sum);
            }
        }
        Map<String, Integer> expected = Map.of(
                "healing_machine", 1,
                "pasture", 2, // its two halves
                "pc", 2,
                "bed", 1,
                "trough with feed", 1,
                "amethyst_cluster", 4,
                "tumblestone_cluster", 1,
                "sky_tumblestone_cluster", 1,
                "black_tumblestone_cluster", 1,
                "gem cluster", 4);
        helper.assertValueEqual(found, expected, "the ranch and crystals");
    }

    @GameTest(template = Templates.EMPTY, batch = "test_area", timeoutTicks = 200)
    public void theTestAreaHasEverythingFullyGrown(GameTestHelper helper) {
        // Well away from the other tests' areas
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0)).offset(600, 0, 0);
        TestArea.Result area = TestArea.build(helper.getLevel(), origin, null);
        helper.assertTrue(area.problems().isEmpty(), "problems: " + area.problems());
        List<String> floating = floatingLeaves(helper, area);
        helper.assertTrue(
                floating.isEmpty(),
                floating.size() + " leaves not attached to a tree, e.g. "
                        + floating.subList(0, Math.min(5, floating.size())));

        // Every vanilla crop, and every Cobblemon crop, root and berry bush in the registry
        Set<Block> expected = new HashSet<>(Set.of(
                Blocks.WHEAT,
                Blocks.CARROTS,
                Blocks.POTATOES,
                Blocks.BEETROOTS,
                Blocks.TORCHFLOWER,
                Blocks.PITCHER_CROP,
                Blocks.ATTACHED_PUMPKIN_STEM,
                Blocks.ATTACHED_MELON_STEM,
                Blocks.NETHER_WART,
                Blocks.COCOA,
                Blocks.SWEET_BERRY_BUSH,
                Blocks.CAVE_VINES));
        for (Block block : BuiltInRegistries.BLOCK) {
            if ("cobblemon".equals(BuiltInRegistries.BLOCK.getKey(block).getNamespace())
                    && (block instanceof CropBlock
                            || block instanceof BerryBlock
                            || block instanceof NutBushBlock
                            || block instanceof RootBlock)) {
                expected.add(block);
            }
        }
        check(helper, area, expected, "when built");
        assertRanchAndCrystals(helper, area);
        // Nothing pops off, dries out or washes away a moment later
        helper.runAfterDelay(40, () -> {
            check(helper, area, expected, "40 ticks later");
            // Last, as it takes their berries: every bush has berries to harvest
            helper.assertValueEqual(bareBushes(helper, area), List.of(), "berry bushes with no berries");
            helper.succeed();
        });
    }
}
