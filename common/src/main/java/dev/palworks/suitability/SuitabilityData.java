package dev.palworks.suitability;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Which work each Pokémon type and species can do, loaded from datapack files under
 * {@code data/<namespace>/palworks/suitability/}:
 * <ul>
 *   <li>{@code types/<type>.json}: what every Pokémon of that type can do, e.g.
 *       {@code data/palworks/palworks/suitability/types/fire.json} = {@code {"kindling": 1}}. The
 *       file name is the type's Showdown id ({@code fire}, {@code fighting}); the namespace doesn't
 *       matter, and files for the same type from different namespaces add together (an addon can
 *       give Fire another skill without replacing ours).</li>
 *   <li>{@code species/<species>.json}: extra work for one species, e.g.
 *       {@code data/cobblemon/palworks/suitability/species/scizor.json} = {@code {"lumbering": 3}}.
 *       The species is the file's namespace and name ({@code cobblemon:scizor}). Level 0 takes a
 *       skill away even if a type gives it.</li>
 * </ul>
 * Each file maps suitability ids (see {@link Suitability}) to a level from 1 to {@value #MAX_LEVEL}.
 * As with any datapack file, a pack loaded later replaces a file with the same path, so a pack can
 * override ours file by file.
 *
 * <p>A Pokémon's level for a skill is the highest its types give, +1 if both of its types give it,
 * raised to its species' level if that's higher. Pure logic (no Minecraft types), so it's
 * unit-tested; {@link SuitabilityReloadListener} feeds it and {@link DatapackSuitabilityProvider}
 * reads it.
 */
public final class SuitabilityData {
    public static final int MAX_LEVEL = 5;

    public static final SuitabilityData EMPTY = new SuitabilityData(Map.of(), Map.of());

    private static volatile SuitabilityData current = EMPTY;

    /** Type Showdown id -> levels. */
    private final Map<String, Map<Suitability, Integer>> types;
    /** Species id ("cobblemon:scizor") -> levels; 0 removes a skill. */
    private final Map<String, Map<Suitability, Integer>> species;

    private SuitabilityData(Map<String, Map<Suitability, Integer>> types,
                            Map<String, Map<Suitability, Integer>> species) {
        this.types = types;
        this.species = species;
    }

    /** The data loaded by the last (re)load. Empty until the server has loaded its datapacks. */
    public static SuitabilityData current() {
        return current;
    }

    public static void setCurrent(SuitabilityData data) {
        current = data;
    }

    public int typeCount() {
        return types.size();
    }

    public int speciesCount() {
        return species.size();
    }

    /**
     * Skill levels for a Pokémon with these types (Showdown ids) and species id. Only skills with
     * a level above 0 are included.
     */
    public Map<Suitability, Integer> levels(Collection<String> typeIds, String speciesId) {
        Map<Suitability, Integer> result = new EnumMap<>(Suitability.class);
        Map<Suitability, Integer> typesGiving = new EnumMap<>(Suitability.class);
        for (String typeId : typeIds) {
            for (Map.Entry<Suitability, Integer> e : types.getOrDefault(typeId, Map.of()).entrySet()) {
                result.merge(e.getKey(), e.getValue(), Math::max);
                typesGiving.merge(e.getKey(), 1, Integer::sum);
            }
        }
        // Both types give it: a dual-type bonus.
        typesGiving.forEach((suitability, count) -> {
            if (count >= 2) {
                result.computeIfPresent(suitability, (s, level) -> Math.min(MAX_LEVEL, level + 1));
            }
        });
        for (Map.Entry<Suitability, Integer> e : species.getOrDefault(speciesId, Map.of()).entrySet()) {
            if (e.getValue() == 0) {
                result.remove(e.getKey());
            } else {
                result.merge(e.getKey(), e.getValue(), Math::max);
            }
        }
        return result;
    }

    /**
     * Builds the data from datapack files, keyed by file id: {@code "<namespace>:types/<type>"} or
     * {@code "<namespace>:species/<species>"}. Anything malformed is skipped and reported to
     * {@code warn}, so one bad file doesn't stop the rest loading.
     */
    public static SuitabilityData fromFiles(Map<String, JsonElement> files, Consumer<String> warn) {
        Map<String, Map<Suitability, Integer>> types = new HashMap<>();
        Map<String, Map<Suitability, Integer>> species = new HashMap<>();
        for (Map.Entry<String, JsonElement> file : files.entrySet()) {
            String fileId = file.getKey();
            int colon = fileId.indexOf(':');
            String namespace = colon < 0 ? "minecraft" : fileId.substring(0, colon);
            String path = fileId.substring(colon + 1);

            if (path.startsWith("types/")) {
                String typeId = path.substring("types/".length());
                Map<Suitability, Integer> levels = parseLevels(fileId, file.getValue(), false, warn);
                Map<Suitability, Integer> merged = types.computeIfAbsent(typeId, k -> new EnumMap<>(Suitability.class));
                levels.forEach((s, level) -> merged.merge(s, level, Math::max));
            } else if (path.startsWith("species/")) {
                String speciesId = namespace + ":" + path.substring("species/".length());
                species.put(speciesId, parseLevels(fileId, file.getValue(), true, warn));
            } else {
                warn.accept("Ignoring suitability file " + fileId + ": it must be under types/ or species/");
            }
        }
        types.replaceAll((k, v) -> Collections.unmodifiableMap(v));
        species.replaceAll((k, v) -> Collections.unmodifiableMap(v));
        return new SuitabilityData(Map.copyOf(types), Map.copyOf(species));
    }

    private static Map<Suitability, Integer> parseLevels(String fileId, JsonElement json, boolean allowZero,
                                                        Consumer<String> warn) {
        Map<Suitability, Integer> levels = new EnumMap<>(Suitability.class);
        if (!(json instanceof JsonObject object)) {
            warn.accept("Ignoring suitability file " + fileId + ": expected an object like {\"kindling\": 1}");
            return levels;
        }
        int min = allowZero ? 0 : 1;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            Suitability suitability = Suitability.fromId(entry.getKey());
            if (suitability == null) {
                warn.accept("Ignoring unknown suitability \"" + entry.getKey() + "\" in " + fileId);
                continue;
            }
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                warn.accept("Ignoring \"" + entry.getKey() + "\" in " + fileId + ": the level must be a number");
                continue;
            }
            int level = value.getAsInt();
            if (level < min || level > MAX_LEVEL) {
                int clamped = Math.max(min, Math.min(MAX_LEVEL, level));
                warn.accept("Level " + level + " for \"" + entry.getKey() + "\" in " + fileId
                        + " is outside " + min + "-" + MAX_LEVEL + "; using " + clamped);
                level = clamped;
            }
            levels.put(suitability, level);
        }
        return levels;
    }
}
