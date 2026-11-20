package dev.palworks.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MiningToolTest {

    @Test
    void woodThenDiamondThenFortuneOneToThree() {
        assertFalse(MiningTool.isDiamond(1));
        assertEquals(0, MiningTool.fortune(1));
        assertTrue(MiningTool.isDiamond(2));
        assertEquals(0, MiningTool.fortune(2));
        for (int level = 3; level <= 5; level++) {
            assertTrue(MiningTool.isDiamond(level));
            assertEquals(level - 2, MiningTool.fortune(level));
        }
        assertEquals(3, MiningTool.fortune(9), "never past Fortune III");
    }
}
