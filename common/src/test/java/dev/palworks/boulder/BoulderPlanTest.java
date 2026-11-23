package dev.palworks.boulder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class BoulderPlanTest {
    private static final BoulderKind KIND =
            new BoulderKind("test", Set.of(), Set.of(), Map.of("minecraft:stone", 1), Map.of("minecraft:coal_ore", 1));

    @Test
    void aBoulderHasABlockForEveryCell() {
        BoulderPlan plan = BoulderPlan.roll(KIND, 5, BoulderStyle.PEAK, BoulderOreLevels.EMPTY, new Random(3));
        assertEquals(plan.shape().blockCount(), plan.blocks().size());
        assertEquals(BoulderStyle.PEAK, plan.shape().style());
        assertTrue(plan.blocks().stream().allMatch(b -> "minecraft:stone".equals(b) || "minecraft:coal_ore".equals(b)));
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
