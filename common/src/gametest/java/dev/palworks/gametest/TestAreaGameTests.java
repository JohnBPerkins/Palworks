package dev.palworks.gametest;

import com.cobblemon.mod.common.block.BerryBlock;
import com.cobblemon.mod.common.block.NutBushBlock;
import com.cobblemon.mod.common.block.entity.BerryBlockEntity;
import dev.palworks.debug.TestArea;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The /palworks testarea build: everything placed, fully grown, and still there a moment later. */
public class TestAreaGameTests {

    /** Whether a plant's "age" (if it has one) is its highest. */
    private static boolean fullyGrown(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty age && property.getName().equals("age")) {
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
        for (BlockPos pos : BlockPos.betweenClosed(area.min(), area.max().atY(area.min().getY() + 5))) {
            BlockState state = level.getBlockState(pos);
            Block block = state.getBlock();
            if (level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace) {
                stations++;
                if (!furnace.getItem(0).isEmpty() && furnace.getItem(1).isEmpty()) {
                    ready++;
                }
            }
            boolean plant = block instanceof CropBlock || block instanceof BerryBlock || block instanceof NutBushBlock
                    || block == Blocks.NETHER_WART || block == Blocks.COCOA || block == Blocks.SWEET_BERRY_BUSH
                    || block == Blocks.PITCHER_CROP || block == Blocks.TORCHFLOWER || block == Blocks.CAVE_VINES
                    || block == Blocks.ATTACHED_PUMPKIN_STEM || block == Blocks.ATTACHED_MELON_STEM;
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
        for (BlockPos pos : BlockPos.betweenClosed(area.min(), area.max().atY(area.min().getY() + 2))) {
            if (level.getBlockEntity(pos) instanceof BerryBlockEntity bush
                    && bush.harvest(level, level.getBlockState(pos), pos.immutable()).isEmpty()) {
                bare.add(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath());
            }
        }
        return bare;
    }

    @GameTest(template = Templates.EMPTY, batch = "test_area", timeoutTicks = 200)
    public void theTestAreaHasEverythingFullyGrown(GameTestHelper helper) {
        // Well away from the other tests' areas
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0)).offset(600, 0, 0);
        TestArea.Result area = TestArea.build(helper.getLevel(), origin);
        helper.assertTrue(area.problems().isEmpty(), "problems: " + area.problems());

        // Every vanilla crop, and every Cobblemon crop and berry bush in the registry
        Set<Block> expected = new HashSet<>(Set.of(Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS,
                Blocks.TORCHFLOWER, Blocks.PITCHER_CROP, Blocks.ATTACHED_PUMPKIN_STEM, Blocks.ATTACHED_MELON_STEM,
                Blocks.NETHER_WART, Blocks.COCOA, Blocks.SWEET_BERRY_BUSH, Blocks.CAVE_VINES));
        for (Block block : BuiltInRegistries.BLOCK) {
            if (BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals("cobblemon")
                    && (block instanceof CropBlock || block instanceof BerryBlock || block instanceof NutBushBlock)) {
                expected.add(block);
            }
        }
        check(helper, area, expected, "when built");
        // Nothing pops off, dries out or washes away a moment later
        helper.runAfterDelay(40, () -> {
            check(helper, area, expected, "40 ticks later");
            // Last, as it takes their berries: every bush has berries to harvest
            helper.assertValueEqual(bareBushes(helper, area), List.of(), "berry bushes with no berries");
            helper.succeed();
        });
    }
}
