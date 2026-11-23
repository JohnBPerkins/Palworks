package dev.palworks.boulder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The shape of one boulder: how high it stands on each column of ground it covers. Every column is
 * a stack from the ground up, so nothing overhangs or floats, and every column two or more high has
 * one beside it at most a block lower, so a worker can climb it a step at a time to reach its top.
 * Most styles are one piece; a rock cluster and a split rock are in pieces. Rolled at random for a
 * level (its {@link BoulderSize}) and a {@link BoulderStyle}, so no two are alike. Pure, with no
 * Minecraft types, so it's unit-tested.
 *
 * <p>How it's rolled: each style is one or more overlapping rounded lumps (ellipsoids, as vanilla's
 * mossy forest rocks are made), placed, sized, stretched and turned at random, roughened by smooth
 * noise, on a square a little wider than the level's footprint so a ridge has room to be long. The
 * highest column is set to the boulder's height; then blocks come off the lowest-lying parts, the
 * outskirts first, until it's the size rolled.
 */
public final class BoulderShape {
    /** One block of a boulder, from its bottom corner (0, 0, 0); y is up from the ground. */
    public record Cell(int x, int y, int z) {}

    // The most its lumps are spread to hold enough blocks: about the whole square, for any style
    private static final double MAX_SPREAD = 3;

    // How much the smooth noise raises or lowers a column, as a share of the boulder's height
    private static final double ROUGHNESS = 0.35;

    // The columns beside one, at most (not across corners)
    private static final int SIDES = 4;

    // Half the width of a split rock's crack, in blocks: enough that a slanting one is an unbroken
    // gap, not a row of blocks touching at their corners
    private static final double CRACK = 0.75;

    // The profile of a column in a split rock's crack: below any other, and never raised
    private static final double IN_CRACK = -1;

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
        int grid = size.widest();
        int target = random.nextInt(size.minBlocks(), size.maxBlocks() + 1);
        int height = heightFor(style, size, random);
        List<Blob> blobs = blobs(style, random);
        SmoothNoise noise = new SmoothNoise(random.nextLong());
        // A split rock's crack, through its middle at a random angle
        double crackAngle = random.nextDouble() * Math.PI;
        Field field =
                new Field(blobs, noise, grid, size.footprint(), style == BoulderStyle.SPLIT ? crackAngle : Double.NaN);
        boolean onePiece = style.isOnePiece();
        // Its lumps spread wider until it holds as many blocks as it's to have (a small twin's two
        // lumps can cover too little of the ground)
        int[] heights = new int[0];
        for (double spread = 1; spread < MAX_SPREAD; spread *= 1.15) {
            heights = fit(field.profile(spread), grid, height, target, onePiece);
            if (Arrays.stream(heights).sum() >= target) {
                break;
            }
        }
        return trimmed(style, grid, heights);
    }

    /**
     * How high a boulder of this style stands: a slab or a rock cluster as low as its size goes, a
     * peak as high, the rest anywhere between.
     */
    private static int heightFor(BoulderStyle style, BoulderSize size, RandomGenerator random) {
        return switch (style) {
            case SLAB, CLUSTER -> size.minHeight();
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
     * One rounded lump of a boulder, measured in half the level's footprint (so a lump of radius 1
     * is about the footprint wide): an ellipse on the ground (half-widths {@code ru}, {@code rv},
     * turned by {@code angle}) rising to {@code top} (a share of the boulder's height) in its
     * middle. {@code flatness} 1 is a dome; higher is flatter on top.
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
     * A style's lumps over a {@code grid}-wide square, and how high each column would like to be
     * from them: a share of the boulder's height (0 to about 1), whichever lump is highest there,
     * roughened by smooth noise so its edges and top are lumpy rather than jagged, and, for a split
     * rock, nothing along its crack.
     */
    private record Field(List<Blob> blobs, SmoothNoise noise, int grid, int footprint, double crackAngle) {
        double[] profile(double spread) {
            double noiseScale = Math.max(1.5, footprint / 2.0);
            double half = footprint / 2.0;
            Blob first = blobs.getFirst();
            double[] profile = new double[grid * grid];
            for (int x = 0; x < grid; x++) {
                for (int z = 0; z < grid; z++) {
                    // From the middle of the square, in half-footprints, at the middle of the column
                    double u = (x + 0.5 - grid / 2.0) / half;
                    double v = (z + 0.5 - grid / 2.0) / half;
                    double f = 0;
                    for (Blob blob : blobs) {
                        f = Math.max(f, blob.heightAt(u, v, spread));
                    }
                    if (f > 0) {
                        f = Math.max(0.01, f + (noise.at(x, z, noiseScale) - 0.5) * ROUGHNESS);
                    }
                    if (!Double.isNaN(crackAngle)) {
                        // How far, in blocks, from the line through the first lump's middle
                        double across =
                                ((u - first.cu()) * Math.sin(crackAngle) - (v - first.cv()) * Math.cos(crackAngle))
                                        * half;
                        if (Math.abs(across) < CRACK) {
                            f = IN_CRACK;
                        }
                    }
                    profile[x * grid + z] = f;
                }
            }
            return profile;
        }
    }

    /** A style's lumps, each placed, sized, stretched and turned at random. */
    private static List<Blob> blobs(BoulderStyle style, RandomGenerator random) {
        double angle = random.nextDouble() * Math.PI;
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return switch (style) {
            // One rounded lump, round or oval
            case DOME ->
                List.of(new Blob(
                        jitter(random, 0.15),
                        jitter(random, 0.15),
                        between(random, 0.8, 1.25),
                        between(random, 0.6, 1.0),
                        angle,
                        1,
                        1));
            // A broad base with a small, tall lump on it, off the middle
            case PEAK ->
                List.of(
                        new Blob(
                                jitter(random, 0.1),
                                jitter(random, 0.1),
                                between(random, 0.7, 1.1),
                                between(random, 0.55, 0.9),
                                angle,
                                0.45,
                                1),
                        new Blob(
                                jitter(random, 0.3),
                                jitter(random, 0.3),
                                between(random, 0.25, 0.4),
                                between(random, 0.25, 0.4),
                                angle,
                                1,
                                1));
            // Wide and flat on top, round or long
            case SLAB ->
                List.of(new Blob(
                        jitter(random, 0.1),
                        jitter(random, 0.1),
                        between(random, 0.95, 1.35),
                        between(random, 0.6, 1.0),
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
            // Long and narrow, with a spine along it and a bump on the spine
            case RIDGE -> {
                double along = jitter(random, 0.6);
                yield List.of(
                        new Blob(
                                jitter(random, 0.1),
                                jitter(random, 0.1),
                                between(random, 1.3, 1.6),
                                between(random, 0.3, 0.42),
                                angle,
                                0.85,
                                1),
                        new Blob(along * cos, along * sin, between(random, 0.35, 0.5), 0.3, angle, 1, 1));
            }
            // Three to five small rocks of different sizes, apart
            case CLUSTER -> {
                int rocks = random.nextInt(3, 6);
                List<Blob> cluster = new ArrayList<>(rocks);
                for (int i = 0; i < rocks; i++) {
                    double r = between(random, 0.25, 0.45);
                    // Somewhere not touching the rocks so far, if a few tries find it
                    double cu = 0;
                    double cv = 0;
                    for (int tries = 0; tries < CLUSTER_TRIES; tries++) {
                        cu = jitter(random, 1.1);
                        cv = jitter(random, 1.1);
                        if (apart(cluster, cu, cv, r)) {
                            break;
                        }
                    }
                    cluster.add(new Blob(
                            cu,
                            cv,
                            r,
                            r * between(random, 0.7, 1),
                            random.nextDouble() * Math.PI,
                            i == 0 ? 1 : between(random, 0.4, 1),
                            1));
                }
                yield cluster;
            }
            // One big rock, cracked in two (the crack is cut by Field)
            case SPLIT ->
                List.of(new Blob(
                        jitter(random, 0.1),
                        jitter(random, 0.1),
                        between(random, 0.9, 1.2),
                        between(random, 0.75, 1.0),
                        angle,
                        1,
                        1.5));
        };
    }

    // How many places a cluster's rock is tried in to keep it apart from the others
    private static final int CLUSTER_TRIES = 20;

    /** Whether a rock of radius {@code r} at ({@code cu}, {@code cv}) is clear of these, with a gap. */
    private static boolean apart(List<Blob> rocks, double cu, double cv, double r) {
        for (Blob rock : rocks) {
            if (Math.hypot(rock.cu() - cu, rock.cv() - cv) < rock.ru() + r + 0.35) {
                return false;
            }
        }
        return true;
    }

    private static double jitter(RandomGenerator random, double most) {
        return (random.nextDouble() * 2 - 1) * most;
    }

    private static double between(RandomGenerator random, double low, double high) {
        return low + random.nextDouble() * (high - low);
    }

    /**
     * Column heights from a profile: the highest column the full {@code height}, the rest in
     * proportion (higher still if that's too few blocks), stepped so it can be climbed, then blocks
     * taken off the lowest-lying columns until there are {@code target}. If it's to be
     * {@code onePiece}, only the columns joined to the highest one are kept.
     */
    private static int[] fit(double[] profile, int grid, int height, int target, boolean onePiece) {
        int top = 0;
        for (int i = 1; i < profile.length; i++) {
            if (profile[i] > profile[top]) {
                top = i;
            }
        }
        double peak = Math.max(profile[top], 1e-6);
        // Scale the profile so its top is the boulder's height, and up from there until it holds enough
        double scale = height / peak;
        int[] heights = scaled(profile, scale, height, grid, top, onePiece);
        while (Arrays.stream(heights).sum() < target && scale < height / peak * 6) {
            scale *= 1.05;
            heights = scaled(profile, scale, height, grid, top, onePiece);
        }
        // Too many: off the lowest-lying columns, never the top one, never so it can't be climbed,
        // has a hole, or (if it's one piece) falls in two
        int total = Arrays.stream(heights).sum();
        while (total > target) {
            int lowest = lowestRemovable(heights, profile, grid, top, onePiece);
            if (lowest < 0) {
                break;
            }
            heights[lowest]--;
            total--;
        }
        return heights;
    }

    /**
     * The profile scaled up, each column at most {@code height}, the top one that, stepped so it can
     * be climbed (see {@link #climbable}), and only what's joined to the top if it's one piece.
     */
    private static int[] scaled(double[] profile, double scale, int height, int grid, int top, boolean onePiece) {
        int[] heights = new int[profile.length];
        for (int i = 0; i < profile.length; i++) {
            heights[i] = Math.clamp(Math.round(profile[i] * scale), 0, height);
        }
        heights[top] = height;
        climbable(heights, profile, grid);
        if (onePiece) {
            keepJoinedTo(heights, grid, top);
        }
        return heights;
    }

    /**
     * Raise columns until every one two or more high has one beside it at most a block lower: the
     * highest-lying of its neighbours (never one in a split rock's crack) is raised to a block under
     * it, or with none it comes down, as often as it takes. So a worker can climb from the ground, a block at a time,
     * to any column's top.
     */
    private static void climbable(int[] heights, double[] profile, int grid) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < heights.length; i++) {
                if (heights[i] >= 2 && !hasStepBeside(heights, grid, i, heights[i])) {
                    // Never a column in a split rock's crack; with only those beside it, this one
                    // comes down instead
                    int best = -1;
                    int beside = 0;
                    for (int side : sides(grid, i)) {
                        beside = Math.max(beside, heights[side]);
                        if (profile[side] > IN_CRACK && (best < 0 || profile[side] > profile[best])) {
                            best = side;
                        }
                    }
                    if (best >= 0) {
                        heights[best] = heights[i] - 1;
                    } else {
                        heights[i] = beside + 1;
                    }
                    changed = true;
                }
            }
        }
    }

    /** Whether a column {@code height} high at {@code i} has one beside it at most a block lower. */
    private static boolean hasStepBeside(int[] heights, int grid, int i, int height) {
        for (int side : sides(grid, i)) {
            if (heights[side] >= height - 1) {
                return true;
            }
        }
        return false;
    }

    /** The columns beside {@code i} (not across corners) inside the square. */
    private static int[] sides(int grid, int i) {
        int x = i / grid;
        int z = i % grid;
        int[] out = new int[SIDES];
        int n = 0;
        if (x > 0) {
            out[n] = i - grid;
            n++;
        }
        if (x < grid - 1) {
            out[n] = i + grid;
            n++;
        }
        if (z > 0) {
            out[n] = i - 1;
            n++;
        }
        if (z < grid - 1) {
            out[n] = i + 1;
            n++;
        }
        return Arrays.copyOf(out, n);
    }

    /**
     * The column to take a block off next: the one lowest in the profile, but not the top column,
     * and not one whose lowering would leave a column that can't be climbed, a hole, or (if it's
     * one piece) the boulder split in two.
     */
    private static int lowestRemovable(int[] heights, double[] profile, int grid, int top, boolean onePiece) {
        int best = -1;
        for (int i = 0; i < heights.length; i++) {
            if (i == top || heights[i] == 0 || (best >= 0 && profile[i] >= profile[best])) {
                continue;
            }
            // A column goes altogether only from the edge, so there's never a hole in the middle
            if (heights[i] == 1
                    && (!onEdge(heights, grid, i) || (onePiece && !staysJoinedWithout(heights, grid, i, top)))) {
                continue;
            }
            if (!staysClimbableLowering(heights, grid, i)) {
                continue;
            }
            best = i;
        }
        return best;
    }

    /** Whether every column beside {@code i} can still be climbed once {@code i} is a block lower. */
    private static boolean staysClimbableLowering(int[] heights, int grid, int i) {
        heights[i]--;
        boolean fine = true;
        for (int side : sides(grid, i)) {
            if (heights[side] >= 2 && !hasStepBeside(heights, grid, side, heights[side])) {
                fine = false;
                break;
            }
        }
        heights[i]++;
        return fine;
    }

    /** Whether a column is on the boulder's outline: at the square's edge, or beside an empty column. */
    private static boolean onEdge(int[] heights, int grid, int i) {
        int[] sides = sides(grid, i);
        if (sides.length < SIDES) {
            return true;
        }
        for (int side : sides) {
            if (heights[side] == 0) {
                return true;
            }
        }
        return false;
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
            for (int next : sides(grid, i)) {
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
