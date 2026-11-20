package dev.palworks.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TreeSizeTest {

    @Test
    void aBiggerTreeNeedsAHigherLevel() {
        int[] most = {12, 32, 64, 128, 256};
        for (int level = 1; level <= most.length; level++) {
            assertEquals(most[level - 1], TreeSize.maxLogs(level));
            assertEquals(level, TreeSize.levelNeeded(most[level - 1]), "the most a level " + level + " chops");
            if (level < most.length) {
                assertEquals(level + 1, TreeSize.levelNeeded(most[level - 1] + 1), "one log more");
            }
        }
        assertEquals(1, TreeSize.levelNeeded(1));
        assertEquals(1, TreeSize.levelNeeded(0), "a tree that's gone (its replant is left) is anyone's");
    }

    @Test
    void levelsOutsideOneToFiveCountAsTheNearest() {
        assertEquals(12, TreeSize.maxLogs(0));
        assertEquals(256, TreeSize.maxLogs(9));
    }
}
