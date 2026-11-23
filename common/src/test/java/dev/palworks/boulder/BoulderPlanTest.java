package dev.palworks.boulder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class BoulderPlanTest {
    private static final String STONE = "minecraft:stone";
    private static final String COAL = "minecraft:coal_ore";
    private static final BoulderKind KIND =
            new BoulderKind("test", Set.of(), Set.of(), Map.of(STONE, 1), BoulderPattern.PATCHES, Map.of(COAL, 1));

    @Test
    void aBoulderHasABlockForEveryCell() {
        BoulderPlan plan = BoulderPlan.roll(KIND, 5, BoulderStyle.PEAK, BoulderOreLevels.EMPTY, new Random(3));
        assertEquals(plan.shape().blockCount(), plan.blocks().size());
        assertEquals(BoulderStyle.PEAK, plan.shape().style());
        assertTrue(plan.blocks().stream().allMatch(b -> STONE.equals(b) || COAL.equals(b)));
    }

    @Test
    void aboutOneBoulderInEightHasOre() {
        Random random = new Random(5);
        int withOre = 0;
        int rolls = 2000;
        for (int i = 0; i < rolls; i++) {
            withOre += BoulderPlan.roll(KIND, 3, null, BoulderOreLevels.EMPTY, random)
                            .blocks()
                            .contains(COAL)
                    ? 1
                    : 0;
        }
        assertTrue(withOre > 200 && withOre < 300, withOre + " of " + rolls + " with ore");
    }

    @Test
    void aBoulderWithOreHasItsLevelsAmountOfItAnywhere() {
        int onTop = 0;
        int ore = 0;
        for (int level = 1; level <= 5; level++) {
            BoulderSize size = BoulderSize.forLevel(level);
            Random random = new Random(level);
            for (int i = 0; i < 200; i++) {
                BoulderPlan plan = BoulderPlan.roll(KIND, level, null, BoulderOreLevels.EMPTY, random, true);
                List<BoulderShape.Cell> cells = plan.shape().cells();
                int count = 0;
                for (int c = 0; c < cells.size(); c++) {
                    if (COAL.equals(plan.blocks().get(c))) {
                        count++;
                        BoulderShape.Cell cell = cells.get(c);
                        onTop += plan.shape().heightAt(cell.x(), cell.z()) == cell.y() + 1 ? 1 : 0;
                    }
                }
                ore += count;
                assertTrue(
                        count >= size.minOre() && count <= size.maxOre(),
                        "level " + level + ": " + count + " ore in "
                                + plan.blocks().size());
            }
        }
        // Anywhere: on its outside as well as in it
        assertTrue(onTop > ore / 5, onTop + " of " + ore + " ore on top");
    }

    @Test
    void evenASmallShellBoulderHasBothItsStones() {
        BoulderKind shell = new BoulderKind(
                "shell", Set.of(), Set.of(), Map.of("outer", 1, "inner", 1), BoulderPattern.SHELL, Map.of());
        Random random = new Random(8);
        int both = 0;
        for (int i = 0; i < 200; i++) {
            List<String> blocks = BoulderPlan.roll(shell, 1, null, BoulderOreLevels.EMPTY, random)
                    .blocks();
            both += blocks.contains("outer") && blocks.contains("inner") ? 1 : 0;
        }
        assertTrue(both > 170, both + " of 200 level 1 boulders have both");
    }

    @Test
    void noOreWhereTheLevelHasNoneUnlocked() {
        BoulderOreLevels levels = BoulderOreLevels.fromFiles(
                Map.of("palworks:levels", com.google.gson.JsonParser.parseString("{\"coal_ore\": 3}")),
                id -> true,
                message -> {});
        BoulderPlan plan = BoulderPlan.roll(KIND, 2, null, levels, new Random(1), true);
        assertTrue(plan.blocks().stream().allMatch(STONE::equals));
    }

    @Test
    void aBoulderAndItsSiteComeBackFromTheJobsData() {
        BoulderPlan plan = BoulderPlan.roll(KIND, 3, null, BoulderOreLevels.EMPTY, new Random(4));
        BlockPos corner = new BlockPos(10, 64, -20);
        CompoundTag data = plan.toJobData(corner);
        BoulderPlan back = BoulderPlan.fromJobData(data);
        assertEquals(plan.kind(), back.kind());
        assertEquals(plan.blocks(), back.blocks());
        assertEquals(plan.shape().cells(), back.shape().cells());
        assertEquals(plan.shape().style(), back.shape().style());
        assertEquals(corner, BoulderPlan.cornerIn(data));
    }

    @Test
    void noBoulderInOtherData() {
        assertNull(BoulderPlan.fromJobData(new CompoundTag()));
        assertNull(BoulderPlan.cornerIn(new CompoundTag()));
    }
}
