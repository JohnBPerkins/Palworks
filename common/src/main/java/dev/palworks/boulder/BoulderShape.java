package dev.palworks.boulder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The shape of one boulder: how high it stands on each column of ground it covers. Every column is
 * a stack from the ground up, so nothing overhangs or floats, and its columns join up side by side
 * (it's one piece). Rolled at random for a level (its {@link BoulderSize}) and a {@link BoulderStyle},
 * so no two are alike. Pure, with no Minecraft types, so it's unit-tested.
 *
 * <p>How it's rolled: each style is one or more overlapping rounded lumps (ellipsoids, as vanilla's
 * mossy forest rocks are made), placed, sized and turned at random over a square the level's
 * footprint wide, and roughened by smooth noise. The highest column is set to the boulder's height;
 * then blocks come off the lowest-lying parts, the outskirts first, until it's the size rolled.
 */
public final class BoulderShape {
    /** One block of a boulder, from its bottom corner (0, 0, 0); y is up from the ground. */
    public record Cell(int x, int y, int z) {}

    private final BoulderStyle style;
    private final int width; // along x
    private final int depth; // along z
    private final int[] heights; // x * depth + z

    private BoulderShape(BoulderStyle style, int width, int depth, int... heights) {
        this.style = style;
        this.width = width;
        this.depth = depth;
        this.heights = heights;
    }

    /** A boulder of this level, in a style picked at random. */
    public static BoulderShape roll(int level, RandomGenerator random) {
        BoulderStyle[] styles = BoulderStyle.values();
        return roll(level, styles[random.nextInt(styles.length)], random);
    }

    /** A boulder of this level and style. */
    public static BoulderShape roll(int level, BoulderStyle style, RandomGenerator random) {
        BoulderSize size = BoulderSize.forLevel(level);
        int grid = size.footprint();
        int target = random.nextInt(size.minBlocks(), size.maxBlocks() + 1);
        int height = heightFor(style, size, random);
        List<Blob> blobs = blobs(style, random);
        SmoothNoise noise = new SmoothNoise(random.nextLong());
        // Its lumps spread wider until it holds as many blocks as it's to have (a small twin's two
        // lumps can cover too little of the ground)
        int[] heights = new int[0];
        for (double spread = 1; spread < MAX_SPREAD; spread *= 1.15) {
            heights = fit(profile(blobs, noise, grid, spread), grid, height, target);
            if (Arrays.stream(heights).sum() >= target) {
                break;
            }
        }
        return trimmed(style, grid, heights);
    }

    /** How high a boulder of this style stands: a slab as low as its size goes, a peak as high. */
    private static int heightFor(BoulderStyle style, BoulderSize size, RandomGenerator random) {
        return switch (style) {
            case SLAB -> size.minHeight();
            case PEAK -> size.maxHeight();
            default -> random.nextInt(size.minHeight(), size.maxHeight() + 1);
        };
    }

    /** A shape from its column heights, {@code heights[x][z]} (all one length), for tests and saved boulders. */
    public static BoulderShape of(BoulderStyle style, int[]... heights) {
        int width = heights.length;
        int depth = width == 0 ? 0 : heights[0].length;
        int[] flat = new int[width * depth];
        for (int x = 0; x < width; x++) {
            if (heights[x].length != depth) {
                throw new IllegalArgumentException("columns of different lengths");
            }
            for (int z = 0; z < depth; z++) {
                flat[x * depth + z] = Math.clamp(heights[x][z], 0, BoulderSize.MAX_HEIGHT);
            }
        }
        return new BoulderShape(style, width, depth, flat);
    }

    public BoulderStyle style() {
        return style;
    }

    /** How far it reaches along x. */
    public int width() {
        return width;
    }

    /** How far it reaches along z. */
    public int depth() {
        return depth;
    }

    /** Its highest column. */
    public int height() {
        return Arrays.stream(heights).max().orElse(0);
    }

    /** How high it stands at this column (0 where it doesn't cover the ground, or outside it). */
    public int heightAt(int x, int z) {
        return x < 0 || z < 0 || x >= width || z >= depth ? 0 : heights[x * depth + z];
    }

    /** How many blocks it's made of. */
    public int blockCount() {
        return Arrays.stream(heights).sum();
    }

    /** Its column heights, {@code [x][z]}, as {@link #of} takes them. */
    public int[][] heights() {
        int[][] copy = new int[width][depth];
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                copy[x][z] = heightAt(x, z);
            }
        }
        return copy;
    }

    /** Its blocks, the bottom layer first. */
    public List<Cell> cells() {
        List<Cell> cells = new ArrayList<>(blockCount());
        int top = height();
        for (int y = 0; y < top; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    if (heightAt(x, z) > y) {
                        cells.add(new Cell(x, y, z));
                    }
                }
            }
        }
        return cells;
    }

    /**
     * One rounded lump of a boulder, on a square from -1 to 1 each way: an ellipse on the ground
     * (half-widths {@code ru}, {@code rv}, turned by {@code angle}) rising to {@code top} (a share of
     * the boulder's height) in its middle. {@code flatness} 1 is a dome; higher is flatter on top.
     */
    private record Blob(double cu, double cv, double ru, double rv, double angle, double top, double flatness) {
        double heightAt(double u, double v, double spread) {
            double du = u - cu;
            double dv = v - cv;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double a = (du * cos + dv * sin) / (ru * spread);
            double b = (-du * sin + dv * cos) / (rv * spread);
            double d = a * a + b * b;
            return d >= 1 ? 0 : top * Math.sqrt(1 - Math.pow(d, flatness));
        }
    }

    /**
     * How high each column of a {@code grid}-wide square would like to be, as a share of the
     * boulder's height (0 to about 1): its style's lumps (one or several overlapping ellipsoids, as
     * vanilla's mossy forest rocks are made), whichever is highest at each column, roughened by
     * smooth noise so its edges and top are lumpy rather than jagged.
     */
    private static double[] profile(List<Blob> blobs, SmoothNoise noise, int grid, double spread) {
        double noiseScale = Math.max(1.5, grid / 2.0);
        double[] profile = new double[grid * grid];
        for (int x = 0; x < grid; x++) {
            for (int z = 0; z < grid; z++) {
                // From -1 to 1 across the square, at the middle of the column
                double u = (x + 0.5) / grid * 2 - 1;
                double v = (z + 0.5) / grid * 2 - 1;
                double f = 0;
                for (Blob blob : blobs) {
                    f = Math.max(f, blob.heightAt(u, v, spread));
                }
                if (f > 0) {
                    f = Math.max(0.01, f + (noise.at(x, z, noiseScale) - 0.5) * ROUGHNESS);
                }
                profile[x * grid + z] = f;
            }
        }
        return profile;
    }

    // The most its lumps are spread to hold enough blocks: about the whole square, for any style
    private static final double MAX_SPREAD = 3;

    // How much the smooth noise raises or lowers a column, as a share of the boulder's height
    private static final double ROUGHNESS = 0.35;

    /** A style's lumps, each placed, sized and turned at random. */
    private static List<Blob> blobs(BoulderStyle style, RandomGenerator random) {
        double angle = random.nextDouble() * Math.PI;
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return switch (style) {
            // One rounded lump
            case DOME ->
                List.of(new Blob(
                        jitter(random, 0.15),
                        jitter(random, 0.15),
                        between(random, 0.8, 1.05),
                        between(random, 0.8, 1.05),
                        angle,
                        1,
                        1));
            // A broad base with a smaller, taller lump on it, off the middle
            case PEAK ->
                List.of(
                        new Blob(
                                jitter(random, 0.1),
                                jitter(random, 0.1),
                                between(random, 0.75, 0.95),
                                between(random, 0.75, 0.95),
                                angle,
                                0.5,
                                1),
                        new Blob(
                                jitter(random, 0.3),
                                jitter(random, 0.3),
                                between(random, 0.35, 0.5),
                                between(random, 0.35, 0.5),
                                angle,
                                1,
                                1));
            // Wide and flat on top
            case SLAB ->
                List.of(new Blob(
                        jitter(random, 0.1),
                        jitter(random, 0.1),
                        between(random, 0.9, 1.1),
                        between(random, 0.85, 1.05),
                        angle,
                        1,
                        3));
            // Three lumps in a row, each lower than the one before: it leans away from the first
            case LOPSIDED ->
                List.of(
                        new Blob(
                                -0.45 * cos,
                                -0.45 * sin,
                                between(random, 0.5, 0.6),
                                between(random, 0.5, 0.6),
                                angle,
                                1,
                                1),
                        new Blob(
                                jitter(random, 0.1),
                                jitter(random, 0.1),
                                between(random, 0.5, 0.6),
                                between(random, 0.45, 0.55),
                                angle,
                                0.7,
                                1),
                        new Blob(
                                0.45 * cos,
                                0.45 * sin,
                                between(random, 0.4, 0.5),
                                between(random, 0.4, 0.5),
                                angle,
                                0.45,
                                1));
            // A big lump and a smaller one either side, a low saddle between
            case TWIN ->
                List.of(
                        new Blob(
                                -0.45 * cos,
                                -0.45 * sin,
                                between(random, 0.5, 0.6),
                                between(random, 0.5, 0.6),
                                angle,
                                1,
                                1),
                        new Blob(
                                0.5 * cos,
                                0.5 * sin,
                                between(random, 0.4, 0.5),
                                between(random, 0.4, 0.5),
                                angle,
                                0.75,
                                1),
                        new Blob(0, 0, 0.45, 0.35, angle, 0.35, 1));
        };
    }

    private static double jitter(RandomGenerator random, double most) {
        return (random.nextDouble() * 2 - 1) * most;
    }

    private static double between(RandomGenerator random, double low, double high) {
        return low + random.nextDouble() * (high - low);
    }

    /**
     * Column heights from a profile: the highest column the full {@code height}, the rest in
     * proportion (higher still if that's too few blocks), then blocks taken off the lowest-lying
     * columns until there are {@code target}. Only the columns joined to the highest one are kept,
     * so it's one piece.
     */
    private static int[] fit(double[] profile, int grid, int height, int target) {
        int top = 0;
        for (int i = 1; i < profile.length; i++) {
            if (profile[i] > profile[top]) {
                top = i;
            }
        }
        double peak = Math.max(profile[top], 1e-6);
        // Scale the profile so its top is the boulder's height, and up from there until it holds enough
        double scale = height / peak;
        int[] heights = scaledAndJoined(profile, scale, height, grid, top);
        while (Arrays.stream(heights).sum() < target && scale < height / peak * 6) {
            scale *= 1.05;
            heights = scaledAndJoined(profile, scale, height, grid, top);
        }
        // Too many: off the lowest-lying columns, never the top one, never so it falls in two
        int total = Arrays.stream(heights).sum();
        while (total > target) {
            int lowest = lowestRemovable(heights, profile, grid, top);
            if (lowest < 0) {
                break;
            }
            heights[lowest]--;
            total--;
        }
        return heights;
    }

    /** The profile scaled up, each column at most {@code height}, the top one that, and only what's joined to it. */
    private static int[] scaledAndJoined(double[] profile, double scale, int height, int grid, int top) {
        int[] heights = new int[profile.length];
        for (int i = 0; i < profile.length; i++) {
            heights[i] = Math.clamp(Math.round(profile[i] * scale), 0, height);
        }
        heights[top] = height;
        keepJoinedTo(heights, grid, top);
        return heights;
    }

    /**
     * The column to take a block off next: the one lowest in the profile, but not the top column,
     * and not one whose going would leave a hole or split the boulder in two.
     */
    private static int lowestRemovable(int[] heights, double[] profile, int grid, int top) {
        int best = -1;
        for (int i = 0; i < heights.length; i++) {
            if (i == top || heights[i] == 0 || (best >= 0 && profile[i] >= profile[best])) {
                continue;
            }
            // A column goes altogether only from the edge, so there's never a hole in the middle
            if (heights[i] == 1 && (!onEdge(heights, grid, i) || !staysJoinedWithout(heights, grid, i, top))) {
                continue;
            }
            best = i;
        }
        return best;
    }

    /** Whether a column is on the boulder's outline: at the square's edge, or beside an empty column. */
    private static boolean onEdge(int[] heights, int grid, int i) {
        int x = i / grid;
        int z = i % grid;
        return x == 0
                || z == 0
                || x == grid - 1
                || z == grid - 1
                || heights[i - grid] == 0
                || heights[i + grid] == 0
                || heights[i - 1] == 0
                || heights[i + 1] == 0;
    }

    /** Whether every column still standing is joined to {@code top} once {@code gone} is gone. */
    private static boolean staysJoinedWithout(int[] heights, int grid, int gone, int top) {
        int[] without = heights.clone();
        without[gone] = 0;
        int standing = (int) Arrays.stream(without).filter(h -> h > 0).count();
        return joinedTo(without, grid, top).length == standing;
    }

    /** Keep only the columns joined (side by side) to {@code top}. */
    private static void keepJoinedTo(int[] heights, int grid, int top) {
        boolean[] keep = new boolean[heights.length];
        for (int i : joinedTo(heights, grid, top)) {
            keep[i] = true;
        }
        for (int i = 0; i < heights.length; i++) {
            if (!keep[i]) {
                heights[i] = 0;
            }
        }
    }

    /** The standing columns joined side by side to {@code from}, it included. */
    private static int[] joinedTo(int[] heights, int grid, int from) {
        boolean[] seen = new boolean[heights.length];
        Deque<Integer> queue = new ArrayDeque<>();
        List<Integer> found = new ArrayList<>();
        queue.add(from);
        seen[from] = true;
        while (!queue.isEmpty()) {
            int i = queue.poll();
            found.add(i);
            int x = i / grid;
            int z = i % grid;
            int[][] sides = {{x + 1, z}, {x - 1, z}, {x, z + 1}, {x, z - 1}};
            for (int[] side : sides) {
                if (side[0] < 0 || side[1] < 0 || side[0] >= grid || side[1] >= grid) {
                    continue;
                }
                int next = side[0] * grid + side[1];
                if (!seen[next] && heights[next] > 0) {
                    seen[next] = true;
                    queue.add(next);
                }
            }
        }
        return found.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The shape cut down to the columns it stands on, from (0, 0). */
    private static BoulderShape trimmed(BoulderStyle style, int grid, int... heights) {
        int minX = grid;
        int minZ = grid;
        int maxX = -1;
        int maxZ = -1;
        for (int x = 0; x < grid; x++) {
            for (int z = 0; z < grid; z++) {
                if (heights[x * grid + z] > 0) {
                    minX = Math.min(minX, x);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxZ = Math.max(maxZ, z);
                }
            }
        }
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        int[] out = new int[width * depth];
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                out[x * depth + z] = heights[(x + minX) * grid + (z + minZ)];
            }
        }
        return new BoulderShape(style, width, depth, out);
    }
}
