package dev.palworks.boulder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * How a kind of boulder lays out its stone (its {@code "pattern"}), so its blocks mostly lie in
 * patches, bands or layers as real rock does, with their edges wavering and about one block in five
 * ({@link #SPECKLE}) any of its stone at random, so it isn't stiff. Each reads the kind's stone list
 * in its own way:
 * <ul>
 *   <li>{@link #PATCHES}: smooth patches of each, the weights their shares.</li>
 *   <li>{@link #STRATA}: level bands of each stone in turn, the weights their thickness in blocks,
 *       tilted a little and starting at a different band in each boulder.</li>
 *   <li>{@link #GRADED}: from the first at the bottom to the last at the top, the weights their
 *       shares of the height, blended where they meet.</li>
 *   <li>{@link #SHELL}: layers from the outside in, by how far each block is from the middle of
 *       the boulder's base, the weights their shares, like a geode.</li>
 *   <li>{@link #CAPPED}: the first on every block open to the sky, the rest in patches below.</li>
 * </ul>
 * Pure, so it's unit-tested.
 */
public enum BoulderPattern {
    PATCHES,
    STRATA,
    GRADED,
    SHELL,
    CAPPED;

    // How many blocks across a patch is, roughly
    private static final double PATCH_SCALE = 1.8;

    /** The share of blocks that are any of the stone at random (by weight), whatever the pattern. */
    public static final double SPECKLE = 0.2;

    // How far bands and grades waver, in blocks up and down, and shells, as a share of the way out
    private static final double STRATA_WAVER = 1.2;
    private static final double GRADE_WAVER = 2.0;
    private static final double SHELL_WAVER = 0.5;

    /** What a pattern needs to know of one block of a boulder. */
    public record Spot(
            BoulderShape.Cell cell,
            boolean openToSky,
            int height,
            double fromMiddle,
            SmoothNoise noise,
            double tiltX,
            double tiltZ,
            double shift,
            double speckle,
            double anyOf) {}

    /** Its name in a kind's file ("patches"). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The pattern of this name, or null if there's none. */
    @Nullable
    public static BoulderPattern fromId(String id) {
        for (BoulderPattern pattern : values()) {
            if (pattern.id().equals(id)) {
                return pattern;
            }
        }
        return null;
    }

    /** The stone for one block of a boulder, from the kind's stone (block id to weight, in order). */
    public String pick(Map<String, Integer> stone, Spot spot) {
        List<String> blocks = new ArrayList<>(stone.keySet());
        BoulderShape.Cell cell = spot.cell();
        // A capped boulder's cap is only ever on top; its speckles are of the stone under it
        Map<String, Integer> speckles = this == CAPPED && stone.size() > 1 ? without(stone, blocks.getFirst()) : stone;
        if (spot.speckle() < SPECKLE) {
            return byShare(speckles, spot.anyOf());
        }
        double waver = spot.noise().at(cell.x() + 31.7, cell.y(), cell.z() - 17.3, PATCH_SCALE) - 0.5;
        return switch (this) {
            case PATCHES -> patch(stone, spot);
            case STRATA -> {
                int total = stone.values().stream().mapToInt(Integer::intValue).sum();
                double layer = cell.y()
                        + cell.x() * spot.tiltX()
                        + cell.z() * spot.tiltZ()
                        + spot.shift() * total
                        + waver * STRATA_WAVER;
                yield byShare(stone, (Math.floorMod((int) Math.floor(layer), total) + 0.5) / total);
            }
            // Up the boulder from 0 to 1, wavering so the bands aren't dead level
            case GRADED -> byShare(stone, (cell.y() + 0.5 + waver * GRADE_WAVER) / Math.max(1, spot.height()));
            // In from the edge, 0 to 1
            case SHELL -> byShare(stone, 1 - spot.fromMiddle() + waver * SHELL_WAVER);
            case CAPPED -> spot.openToSky() || stone.size() == 1 ? blocks.getFirst() : patch(speckles, spot);
        };
    }

    private static Map<String, Integer> without(Map<String, Integer> stone, String block) {
        Map<String, Integer> rest = new java.util.LinkedHashMap<>(stone);
        rest.remove(block);
        return rest;
    }

    /** The stone whose patch this spot is in: smooth noise, by the weights' shares. */
    private static String patch(Map<String, Integer> stone, Spot spot) {
        BoulderShape.Cell cell = spot.cell();
        double n = spot.noise().at(cell.x(), cell.y(), cell.z(), PATCH_SCALE);
        // Smooth noise bunches round the middle: spread it so the shares come out near their weights
        return byShare(stone, Math.clamp((n - 0.5) * 2.2 + 0.5, 0, 0.999));
    }

    /** The block whose share of 0 to 1 (by weight, in order) {@code at} falls in. */
    private static String byShare(Map<String, Integer> stone, double at) {
        int total = stone.values().stream().mapToInt(Integer::intValue).sum();
        double target = Math.clamp(at, 0, 0.999) * total;
        String last = null;
        for (Map.Entry<String, Integer> entry : stone.entrySet()) {
            last = entry.getKey();
            target -= entry.getValue();
            if (target < 0) {
                return last;
            }
        }
        return last;
    }
}
