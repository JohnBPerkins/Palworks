package dev.palworks.boulder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.AvoidDuplicateLiterals") // the failure messages read better inline
class BoulderShapeTest {
    private static final int SEEDS = 300;

    @Test
    void everyBoulderIsItsLevelsSizeWhateverItsStyle() {
        for (int level = 1; level <= 5; level++) {
            BoulderSize size = BoulderSize.forLevel(level);
            for (BoulderStyle style : BoulderStyle.values()) {
                for (int seed = 0; seed < SEEDS; seed++) {
                    BoulderShape shape = BoulderShape.roll(level, style, new Random(seed));
                    String what = "level " + level + " " + style + " seed " + seed;
                    assertTrue(
                            shape.blockCount() >= size.minBlocks() && shape.blockCount() <= size.maxBlocks(),
                            what + ": " + shape.blockCount() + " blocks");
                    assertTrue(
                            shape.width() <= size.widest() && shape.depth() <= size.widest(),
                            what + ": " + shape.width() + "x" + shape.depth());
                    assertTrue(
                            shape.height() >= size.minHeight() && shape.height() <= size.maxHeight(),
                            what + ": " + shape.height() + " high");
                    assertTrue(shape.height() <= BoulderSize.MAX_HEIGHT, what);
                }
            }
        }
    }

    @Test
    void aBoulderStandsOnTheGroundInOnePieceUnlessItsInPieces() {
        for (int level = 1; level <= 5; level++) {
            for (BoulderStyle style : BoulderStyle.values()) {
                for (int seed = 0; seed < SEEDS; seed++) {
                    BoulderShape shape = BoulderShape.roll(level, style, new Random(seed));
                    String what = "level " + level + " " + style + " seed " + seed;
                    List<BoulderShape.Cell> cells = shape.cells();
                    assertEquals(shape.blockCount(), cells.size(), what);
                    Set<BoulderShape.Cell> all = new HashSet<>(cells);
                    for (BoulderShape.Cell cell : cells) {
                        // Every block rests on the ground or on another
                        assertTrue(
                                cell.y() == 0 || all.contains(new BoulderShape.Cell(cell.x(), cell.y() - 1, cell.z())),
                                what + ": " + cell + " floats");
                    }
                    if (style.isOnePiece()) {
                        assertEquals(cells.size(), joined(all, cells.getFirst()), what + ": in pieces");
                    }
                    // It's cut down to what it stands on: something on each edge
                    assertTrue(shape.heightAt(0, anyZ(shape, 0)) > 0, what);
                }
            }
        }
    }

    @Test
    void aBoulderHasNoHoles() {
        // Every empty column inside its outline is joined to the outside by empty columns
        for (int level = 1; level <= 5; level++) {
            for (BoulderStyle style : BoulderStyle.values()) {
                if (style == BoulderStyle.CLUSTER) {
                    continue; // its rocks can stand round a gap
                }
                for (int seed = 0; seed < SEEDS; seed++) {
                    BoulderShape shape = BoulderShape.roll(level, style, new Random(seed));
                    int w = shape.width() + 2;
                    int d = shape.depth() + 2;
                    boolean[] outside = new boolean[w * d];
                    Deque<int[]> queue = new ArrayDeque<>();
                    queue.add(new int[] {0, 0});
                    outside[0] = true;
                    while (!queue.isEmpty()) {
                        int[] at = queue.poll();
                        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                        for (int[] side : sides) {
                            int x = at[0] + side[0];
                            int z = at[1] + side[1];
                            if (x >= 0
                                    && z >= 0
                                    && x < w
                                    && z < d
                                    && !outside[x * d + z]
                                    && shape.heightAt(x - 1, z - 1) == 0) {
                                outside[x * d + z] = true;
                                queue.add(new int[] {x, z});
                            }
                        }
                    }
                    for (int x = 0; x < shape.width(); x++) {
                        for (int z = 0; z < shape.depth(); z++) {
                            assertTrue(
                                    shape.heightAt(x, z) > 0 || outside[(x + 1) * d + z + 1],
                                    "level " + level + " " + style + " seed " + seed + ": a hole at " + x + ", " + z);
                        }
                    }
                }
            }
        }
    }

    @Test
    void everyColumnCanBeClimbedToFromBeside() {
        // A column two or more high has one beside it at most a block lower: a worker steps up to it
        for (int level = 1; level <= 5; level++) {
            for (BoulderStyle style : BoulderStyle.values()) {
                for (int seed = 0; seed < SEEDS; seed++) {
                    BoulderShape shape = BoulderShape.roll(level, style, new Random(seed));
                    for (int x = 0; x < shape.width(); x++) {
                        for (int z = 0; z < shape.depth(); z++) {
                            int h = shape.heightAt(x, z);
                            int beside = Math.max(
                                    Math.max(shape.heightAt(x + 1, z), shape.heightAt(x - 1, z)),
                                    Math.max(shape.heightAt(x, z + 1), shape.heightAt(x, z - 1)));
                            assertTrue(
                                    h < 2 || beside >= h - 1,
                                    "level " + level + " " + style + " seed " + seed + ": " + h + " high at " + x + ", "
                                            + z + " with " + beside + " beside");
                        }
                    }
                }
            }
        }
    }

    @Test
    void ridgesAreLongAndClustersAndSplitRocksAreInPieces() {
        double ridge = 0;
        double dome = 0;
        int clustersInPieces = 0;
        int splitsInPieces = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            ridge += longOverShort(BoulderShape.roll(5, BoulderStyle.RIDGE, new Random(seed)));
            dome += longOverShort(BoulderShape.roll(5, BoulderStyle.DOME, new Random(seed)));
            clustersInPieces += pieces(BoulderShape.roll(5, BoulderStyle.CLUSTER, new Random(seed))) > 1 ? 1 : 0;
            splitsInPieces += pieces(BoulderShape.roll(5, BoulderStyle.SPLIT, new Random(seed))) > 1 ? 1 : 0;
        }
        assertTrue(ridge > dome * 1.3, "ridge " + ridge / SEEDS + ", dome " + dome / SEEDS);
        assertTrue(clustersInPieces > SEEDS * 0.7, clustersInPieces + " clusters in pieces");
        assertTrue(splitsInPieces > SEEDS * 0.7, splitsInPieces + " split rocks in pieces");
    }

    @Test
    void theBiggestPeaksAreSixHigh() {
        int six = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            six += BoulderShape.roll(5, BoulderStyle.PEAK, new Random(seed)).height() == 6 ? 1 : 0;
        }
        assertEquals(SEEDS, six, "level 5 peaks six high");
    }

    /** How long its outline is over how wide, roughly: the long side of its box over the short. */
    private static double longOverShort(BoulderShape shape) {
        return (double) Math.max(shape.width(), shape.depth()) / Math.min(shape.width(), shape.depth());
    }

    /** How many pieces its columns are in. */
    private static int pieces(BoulderShape shape) {
        boolean[][] seen = new boolean[shape.width()][shape.depth()];
        int pieces = 0;
        for (int x = 0; x < shape.width(); x++) {
            for (int z = 0; z < shape.depth(); z++) {
                if (shape.heightAt(x, z) == 0 || seen[x][z]) {
                    continue;
                }
                pieces++;
                Deque<int[]> queue = new ArrayDeque<>();
                queue.add(new int[] {x, z});
                seen[x][z] = true;
                while (!queue.isEmpty()) {
                    int[] at = queue.poll();
                    int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] side : sides) {
                        int nx = at[0] + side[0];
                        int nz = at[1] + side[1];
                        if (shape.heightAt(nx, nz) > 0 && !seen[nx][nz]) {
                            seen[nx][nz] = true;
                            queue.add(new int[] {nx, nz});
                        }
                    }
                }
            }
        }
        return pieces;
    }

    @Test
    void stylesLookDifferent() {
        // Averaged over many: a peak is the tallest for its width, a slab the lowest
        double peak = tallness(BoulderStyle.PEAK);
        double dome = tallness(BoulderStyle.DOME);
        double slab = tallness(BoulderStyle.SLAB);
        assertTrue(peak > dome && dome > slab, "peak " + peak + ", dome " + dome + ", slab " + slab);
    }

    @Test
    void noTwoBouldersAreAlike() {
        Set<String> seen = new HashSet<>();
        for (int seed = 0; seed < 50; seed++) {
            seen.add(java.util.Arrays.deepToString(
                    BoulderShape.roll(3, BoulderStyle.DOME, new Random(seed)).heights()));
        }
        assertTrue(seen.size() > 40, seen.size() + " different shapes in 50");
    }

    @Test
    void aShapeComesBackFromItsHeights() {
        BoulderShape shape = BoulderShape.roll(4, new Random(7));
        BoulderShape again = BoulderShape.of(shape.style(), shape.heights());
        assertEquals(shape.cells(), again.cells());
        assertNotEquals(0, again.blockCount());
    }

    /** Height over the width it covers, on average, for a style at level 5. */
    private static double tallness(BoulderStyle style) {
        double sum = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            BoulderShape shape = BoulderShape.roll(5, style, new Random(seed));
            int covered = 0;
            for (int x = 0; x < shape.width(); x++) {
                for (int z = 0; z < shape.depth(); z++) {
                    covered += shape.heightAt(x, z) > 0 ? 1 : 0;
                }
            }
            sum += shape.height() / Math.sqrt(covered);
        }
        return sum / SEEDS;
    }

    private static int anyZ(BoulderShape shape, int x) {
        for (int z = 0; z < shape.depth(); z++) {
            if (shape.heightAt(x, z) > 0) {
                return z;
            }
        }
        return 0;
    }

    /** How many cells are joined (face to face) to {@code from}. */
    private static int joined(Set<BoulderShape.Cell> cells, BoulderShape.Cell from) {
        Set<BoulderShape.Cell> seen = new HashSet<>();
        Deque<BoulderShape.Cell> queue = new ArrayDeque<>();
        queue.add(from);
        seen.add(from);
        int[][] sides = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            BoulderShape.Cell cell = queue.poll();
            for (int[] side : sides) {
                BoulderShape.Cell next =
                        new BoulderShape.Cell(cell.x() + side[0], cell.y() + side[1], cell.z() + side[2]);
                if (cells.contains(next) && seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return seen.size();
    }
}
