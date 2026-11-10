package dev.palworks.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Where a worker stands, from the edge of its body: one rule for every size of Pokémon. */
class WorkStanceTest {

    @Test
    void nearIsTheDistanceWorkersHaveAlwaysStoodAt() {
        // The rule it replaced: 2 + floor(width / 2), clamped to [2, 4]
        for (int hundredths = 1; hundredths <= 800; hundredths++) {
            double width = hundredths / 100.0;
            int before = Math.max(2, Math.min(4, 2 + (int) Math.floor(width / 2.0)));
            assertEquals(before, WorkStance.NEAR.ringFor(width / 2.0), "width " + width);
        }
    }

    @Test
    void againstIsTheRingLumberjacksHaveAlwaysChoppedFrom() {
        // The rule it replaced: the nearest ring the hitbox fits in without overlapping the trunk
        for (int hundredths = 1; hundredths <= 800; hundredths++) {
            double width = hundredths / 100.0;
            int before = Math.max(1, (int) Math.ceil(width / 2.0 + 0.5 - 1e-3));
            assertEquals(before, WorkStance.AGAINST.ringFor(width / 2.0), "width " + width);
        }
    }

    @Test
    void aBiggerBodyStandsFurtherBackForTheSameGap() {
        // A Pikachu-sized worker against a block, and a Steelix-sized one
        assertEquals(1, WorkStance.AGAINST.ringFor(0.3));
        assertEquals(3, WorkStance.AGAINST.ringFor(2.4));
    }

    @Test
    void itNeverStandsWhereItsBodyWouldOverlapTheBlock() {
        // Even with no gap allowed, a 2-block-wide body can't stand on ring 1 (it would be inside it)
        WorkStance touching = new WorkStance(0.0, 1, 10);
        assertEquals(2, touching.ringFor(1.0));
    }

    @Test
    void itKeepsWithinItsRings() {
        assertEquals(4, WorkStance.NEAR.ringFor(5.0), "the biggest still stand no further than ring 4");
        assertEquals(2, WorkStance.NEAR.ringFor(0.1), "and the smallest no nearer than ring 2");
    }

    @Test
    void aStanceNeedsSensibleRings() {
        assertThrows(IllegalArgumentException.class, () -> new WorkStance(-1, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> new WorkStance(1, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> new WorkStance(1, 3, 2));
    }
}
