package dev.palworks.boulder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.palworks.boulder.BoulderSites.Column;
import org.junit.jupiter.api.Test;

class BoulderSitesTest {
    // A 3 by 2 boulder; its site runs from -2 to 4 across and -2 to 3 down (the margin is 2)
    private static final int WIDTH = 3;
    private static final int DEPTH = 2;

    @Test
    void openFlatGroundAllRoundFits() {
        assertTrue(BoulderSites.fits(WIDTH, DEPTH, (x, z) -> Column.OPEN));
    }

    @Test
    void anythingAtAllWhereTheBoulderGoesStopsIt() {
        // A flower or grass on its footprint: never broken to make room
        assertFalse(BoulderSites.fits(WIDTH, DEPTH, (x, z) -> x == 1 && z == 1 ? Column.CLEAR : Column.OPEN));
        assertFalse(BoulderSites.fits(WIDTH, DEPTH, (x, z) -> x == 0 && z == 0 ? Column.BLOCKED : Column.OPEN));
    }

    @Test
    void aPlantInTheMarginIsAllRightButNothingSolid() {
        assertTrue(BoulderSites.fits(WIDTH, DEPTH, (x, z) -> x < 0 ? Column.CLEAR : Column.OPEN));
        // A wall, a hole or a step two blocks off is still too near
        assertFalse(BoulderSites.fits(WIDTH, DEPTH, (x, z) -> x == -2 && z == 0 ? Column.BLOCKED : Column.OPEN));
        assertFalse(BoulderSites.fits(
                WIDTH, DEPTH, (x, z) -> x == WIDTH + 1 && z == DEPTH + 1 ? Column.BLOCKED : Column.OPEN));
    }

    @Test
    void whatsPastTheMarginDoesntMatter() {
        int margin = BoulderSites.MARGIN;
        assertTrue(BoulderSites.fits(
                WIDTH,
                DEPTH,
                (x, z) -> x < -margin || z < -margin || x >= WIDTH + margin || z >= DEPTH + margin
                        ? Column.BLOCKED
                        : Column.OPEN));
    }
}
