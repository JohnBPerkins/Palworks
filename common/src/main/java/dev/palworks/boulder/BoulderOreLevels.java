package dev.palworks.boulder;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The Unearthing level that unlocks each ore in boulders: one table for every kind of boulder, so
 * gold takes the same level in a badlands boulder as in a plain stone one (a kind that's rich in
 * gold has it weighted higher instead: see {@link BoulderKind}). An ore that isn't listed is
 * unlocked from level 1. Loaded from {@code data/<namespace>/palworks/boulder_ores/<name>.json},
 * {@code {"minecraft:gold_ore": 3, ...}}; the same block in several files takes the highest level.
 * Pure, with no Minecraft types, so it's unit-tested.
 */
public final class BoulderOreLevels {
    public static final BoulderOreLevels EMPTY = new BoulderOreLevels(Map.of());

    private static volatile BoulderOreLevels current = EMPTY;

    private final Map<String, Integer> levels;

    private BoulderOreLevels(Map<String, Integer> levels) {
        this.levels = Map.copyOf(levels);
    }

    public static BoulderOreLevels current() {
        return current;
    }

    public static void setCurrent(BoulderOreLevels levels) {
        current = levels;
    }

    /** The level that unlocks this block in boulders: 1 if it isn't listed. */
    public int levelOf(String blockId) {
        return levels.getOrDefault(blockId, 1);
    }

    /** How many blocks are listed. */
    public int size() {
        return levels.size();
    }

    /** Every listed block and its level, by id. */
    public Map<String, Integer> all() {
        return new TreeMap<>(levels);
    }

    /**
     * Read the files, by id ({@code namespace:name}). Entries that aren't a block {@code isBlock}
     * knows, or whose level isn't a whole number, are warned about and left out; levels are kept
     * within 1 to 5.
     */
    public static BoulderOreLevels fromFiles(
            Map<String, JsonElement> files, java.util.function.Predicate<String> isBlock, Consumer<String> warn) {
        Map<String, Integer> levels = new HashMap<>();
        for (Map.Entry<String, JsonElement> file : new TreeMap<>(files).entrySet()) {
            if (!(file.getValue() instanceof JsonObject object)) {
                warn.accept("Ignoring boulder ore file " + file.getKey() + ": not an object of block ids to levels");
                continue;
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                String block = BoulderKinds.blockId(entry.getKey());
                Integer level = wholeNumber(entry.getValue());
                if (level == null) {
                    warn.accept(
                            "Ignoring " + entry.getKey() + " in " + file.getKey() + ": its level isn't a whole number");
                } else if (!isBlock.test(block)) {
                    warn.accept("Ignoring " + entry.getKey() + " in " + file.getKey() + ": no such block");
                } else {
                    levels.merge(block, Math.clamp(level, 1, 5), Math::max);
                }
            }
        }
        return new BoulderOreLevels(levels);
    }

    /** The number as a whole number, or null if it isn't one. */
    static Integer wholeNumber(JsonElement value) {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            return null;
        }
        double number = primitive.getAsDouble();
        return number == Math.rint(number) && Math.abs(number) < Integer.MAX_VALUE ? (int) number : null;
    }
}
