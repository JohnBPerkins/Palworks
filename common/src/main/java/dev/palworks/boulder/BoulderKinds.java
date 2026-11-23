package dev.palworks.boulder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/**
 * Every kind of boulder (see {@link BoulderKind}), from {@code data/<namespace>/palworks/boulders/<kind>.json}:
 * <pre>
 * { "types": ["ground"], "species": ["cobblemon:groudon"],
 *   "stone": { "minecraft:terracotta": 4 }, "pattern": "strata", "ores": { "minecraft:gold_ore": 4 },
 *   "replace": false }
 * </pre>
 * {@code "pattern"} is how the stone is laid out (see {@link BoulderPattern}; patches if not given);
 * across namespaces, the last file's (by id) that gives one is used.
 * The same kind from several namespaces merges: types and species add up, a block's weight is the
 * highest given, and a weight of 0 takes the block out; {@code "replace": true} uses only the
 * replacing files for that kind. Pure, with no Minecraft types, so it's unit-tested.
 */
public final class BoulderKinds {
    /** The kind for a worker no other kind is for. Shipped, for plain Rock types. */
    public static final String FALLBACK = "stone";

    private static final String REPLACE = "replace";
    private static final String PATTERN = "pattern";
    private static final String IN = " in ";

    public static final BoulderKinds EMPTY = new BoulderKinds(Map.of());

    private static volatile BoulderKinds current = EMPTY;

    private final Map<String, BoulderKind> kinds; // by id, in id order

    private BoulderKinds(Map<String, BoulderKind> kinds) {
        this.kinds = java.util.Collections.unmodifiableMap(new TreeMap<>(kinds));
    }

    public static BoulderKinds current() {
        return current;
    }

    public static void setCurrent(BoulderKinds kinds) {
        current = kinds;
    }

    /** Every kind, in id order. */
    public List<BoulderKind> all() {
        return List.copyOf(kinds.values());
    }

    @Nullable
    public BoulderKind get(String id) {
        return kinds.get(id);
    }

    /**
     * The kind a worker of this species and these types raises: the kind its species is listed in;
     * else the kind for its type besides Rock (the first, if it has two others); else for its first
     * type; else {@link #FALLBACK}. Null only if there are no kinds at all.
     */
    @Nullable
    public BoulderKind kindFor(String speciesId, List<String> types) {
        String species = speciesId(speciesId);
        for (BoulderKind kind : kinds.values()) {
            if (kind.species().contains(species)) {
                return kind;
            }
        }
        List<String> order = new ArrayList<>();
        for (String type : types) {
            if (!"rock".equals(type.toLowerCase(Locale.ROOT))) {
                order.add(type.toLowerCase(Locale.ROOT));
            }
        }
        if (!types.isEmpty()) {
            order.add(types.getFirst().toLowerCase(Locale.ROOT));
        }
        for (String type : order) {
            for (BoulderKind kind : kinds.values()) {
                if (kind.types().contains(type)) {
                    return kind;
                }
            }
        }
        BoulderKind fallback = kinds.get(FALLBACK);
        return fallback != null || kinds.isEmpty()
                ? fallback
                : kinds.values().iterator().next();
    }

    /**
     * Read the kind files, by id ({@code namespace:kind}). Blocks {@code isBlock} doesn't know, and
     * weights that aren't whole numbers from 0 up, are warned about and left out; a kind left with
     * no stone is too.
     */
    public static BoulderKinds fromFiles(
            Map<String, JsonElement> files, Predicate<String> isBlock, Consumer<String> warn) {
        Map<String, List<KindFile>> byKind = new TreeMap<>();
        for (Map.Entry<String, JsonElement> file : new TreeMap<>(files).entrySet()) {
            String fileId = file.getKey();
            String kindId = fileId.substring(fileId.indexOf(':') + 1);
            KindFile parsed = parse(fileId, file.getValue(), isBlock, warn);
            if (parsed != null) {
                byKind.computeIfAbsent(kindId, k -> new ArrayList<>()).add(parsed);
            }
        }
        Map<String, BoulderKind> kinds = new HashMap<>();
        Map<String, String> claimed = new HashMap<>(); // "type:x" / "species:y" -> the kind that has it
        byKind.forEach((id, kindFiles) -> {
            List<KindFile> used = kindFiles.stream().anyMatch(KindFile::replace)
                    ? kindFiles.stream().filter(KindFile::replace).toList()
                    : kindFiles;
            Set<String> types = new HashSet<>();
            Set<String> species = new HashSet<>();
            Map<String, Integer> stone = new LinkedHashMap<>();
            Map<String, Integer> ores = new LinkedHashMap<>();
            BoulderPattern pattern = BoulderPattern.PATCHES;
            for (KindFile kindFile : used) {
                if (kindFile.pattern() != null) {
                    pattern = kindFile.pattern();
                }
                types.addAll(kindFile.types());
                species.addAll(kindFile.species());
                kindFile.stone().forEach((block, weight) -> stone.merge(block, weight, BoulderKinds::mergeWeight));
                kindFile.ores().forEach((block, weight) -> ores.merge(block, weight, BoulderKinds::mergeWeight));
            }
            stone.values().removeIf(weight -> weight == 0);
            ores.values().removeIf(weight -> weight == 0);
            if (stone.isEmpty()) {
                warn.accept("Ignoring boulder kind " + id + ": it has no stone");
                return;
            }
            for (String type : types) {
                warnIfTaken(claimed, "type " + type, id, warn);
            }
            for (String one : species) {
                warnIfTaken(claimed, "species " + one, id, warn);
            }
            kinds.put(id, new BoulderKind(id, types, species, stone, pattern, ores));
        });
        return new BoulderKinds(kinds);
    }

    // A weight of 0 takes a block out, whatever other files give it
    private static int mergeWeight(int a, int b) {
        return a == 0 || b == 0 ? 0 : Math.max(a, b);
    }

    private static void warnIfTaken(Map<String, String> claimed, String what, String kind, Consumer<String> warn) {
        String earlier = claimed.putIfAbsent(what, kind);
        if (earlier != null) {
            warn.accept("Boulder kinds " + earlier + " and " + kind + " are both for " + what + ": " + earlier
                    + " is used");
        }
    }

    private record KindFile(
            Set<String> types,
            Set<String> species,
            Map<String, Integer> stone,
            @Nullable BoulderPattern pattern,
            Map<String, Integer> ores,
            boolean replace) {}

    @Nullable
    private static KindFile parse(String fileId, JsonElement json, Predicate<String> isBlock, Consumer<String> warn) {
        if (!(json instanceof JsonObject object)) {
            warn.accept("Ignoring boulder kind file " + fileId + ": not an object");
            return null;
        }
        Set<String> types = new HashSet<>();
        for (String type : strings(object, "types", fileId, warn)) {
            types.add(type.toLowerCase(Locale.ROOT));
        }
        Set<String> species = new HashSet<>();
        for (String one : strings(object, "species", fileId, warn)) {
            species.add(speciesId(one));
        }
        Map<String, Integer> stone = weights(object, "stone", fileId, isBlock, warn);
        Map<String, Integer> ores = weights(object, "ores", fileId, isBlock, warn);
        BoulderPattern pattern = null;
        if (object.get(PATTERN) instanceof com.google.gson.JsonPrimitive name && name.isString()) {
            pattern = BoulderPattern.fromId(name.getAsString().toLowerCase(Locale.ROOT));
            if (pattern == null) {
                warn.accept("Ignoring the pattern \"" + name.getAsString() + "\"" + IN + fileId + ": not one of "
                        + java.util.Arrays.stream(BoulderPattern.values())
                                .map(BoulderPattern::id)
                                .toList());
            }
        } else if (object.has(PATTERN)) {
            warn.accept("Ignoring \"pattern\"" + IN + fileId + ": not a name");
        }
        JsonElement replaceField = object.get(REPLACE);
        boolean replace = replaceField != null && replaceField.isJsonPrimitive() && replaceField.getAsBoolean();
        for (String key : object.keySet()) {
            if (!Set.of("types", "species", "stone", PATTERN, "ores", REPLACE).contains(key)) {
                warn.accept("Ignoring \"" + key + "\"" + IN + fileId + ": not a field of a boulder kind");
            }
        }
        return new KindFile(types, species, stone, pattern, ores, replace);
    }

    private static List<String> strings(JsonObject object, String field, String fileId, Consumer<String> warn) {
        List<String> out = new ArrayList<>();
        if (!object.has(field)) {
            return out;
        }
        if (!(object.get(field) instanceof JsonArray array)) {
            warn.accept("Ignoring \"" + field + "\"" + IN + fileId + ": not a list");
            return out;
        }
        for (JsonElement element : array) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                out.add(element.getAsString());
            } else {
                warn.accept("Ignoring " + element + " in \"" + field + "\" of " + fileId + ": not a name");
            }
        }
        return out;
    }

    private static Map<String, Integer> weights(
            JsonObject object, String field, String fileId, Predicate<String> isBlock, Consumer<String> warn) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (!object.has(field)) {
            return out;
        }
        if (!(object.get(field) instanceof JsonObject weights)) {
            warn.accept("Ignoring \"" + field + "\"" + IN + fileId + ": not an object of block ids to weights");
            return out;
        }
        for (Map.Entry<String, JsonElement> entry : weights.entrySet()) {
            String block = blockId(entry.getKey());
            Integer weight = BoulderOreLevels.wholeNumber(entry.getValue());
            if (weight == null || weight < 0) {
                warn.accept("Ignoring " + entry.getKey() + " in \"" + field + "\" of " + fileId
                        + ": its weight isn't a whole number from 0 up");
            } else if (!isBlock.test(block)) {
                warn.accept("Ignoring " + entry.getKey() + " in \"" + field + "\" of " + fileId + ": no such block");
            } else {
                out.put(block, weight);
            }
        }
        return out;
    }

    /** A block id, {@code minecraft:} if it has no namespace. */
    static String blockId(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains(":") ? lower : "minecraft:" + lower;
    }

    /** A species id, {@code cobblemon:} if it has no namespace. */
    static String speciesId(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains(":") ? lower : "cobblemon:" + lower;
    }
}
