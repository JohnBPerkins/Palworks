package dev.palworks.suitability;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.palworks.Palworks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * Admins' changes to species' work levels, made in game ({@code /palworks suitability}) and put over
 * the datapacks' ({@link SuitabilityData#withSpecies}). The datapacks are never changed: the changes
 * are saved with the world, in {@code <world>/palworks/suitability_overrides.json}, and put over the
 * datapacks again after each {@code /reload}. Resetting a change brings back the datapacks' level.
 * <pre>{@code
 * { "species": { "cobblemon:bulbasaur": { "watering": 3, "planting": 0 } } }
 * }</pre>
 * A level is 1 to {@value SuitabilityData#MAX_LEVEL}, or 0 to take the job away. Each change
 * replaces {@link SuitabilityData#current()}, and players are sent their Pokémon's new levels as
 * after a reload ({@code WorkLevelSync}). Only changed on the server thread (commands, reloads,
 * server start and stop).
 */
public final class SuitabilityOverrides {
    public static final String FILE_NAME = "suitability_overrides.json";
    private static final String SPECIES = "species";
    private static final String DEFAULT_SPECIES_NAMESPACE = "cobblemon";

    private static volatile SuitabilityData datapack = SuitabilityData.EMPTY;
    // Species id -> job -> level. Replaced on each change, never changed, so all() can hand it out
    private static volatile Map<String, Map<Suitability, Integer>> overrides = Map.of();
    // Where they're saved: none until a world loads
    @Nullable
    private static volatile Path file;

    private SuitabilityOverrides() {}

    /** The datapacks have (re)loaded: put the overrides over their data. */
    public static void setDatapack(SuitabilityData data) {
        datapack = data;
        apply();
    }

    /** As a world loads: use the overrides saved in {@code dir}, its Palworks folder, if it has any. */
    public static void load(Path dir) {
        file = dir.resolve(FILE_NAME);
        overrides = Map.of();
        if (Files.exists(file)) {
            Consumer<String> warn = message -> Palworks.LOGGER.warn("[suitability] Ignoring {}: {}", file, message);
            try {
                overrides = parse(Files.readString(file, StandardCharsets.UTF_8), warn);
            } catch (IOException e) {
                warn.accept("couldn't read it (" + e.getMessage() + ")");
            }
            Palworks.LOGGER.info("Loaded in-game work suitability changes for {} species", overrides.size());
        }
        apply();
    }

    /** As the world closes: forget its overrides. */
    public static void clear() {
        file = null;
        overrides = Map.of();
        apply();
    }

    /** Every override: species id -> job -> level. */
    public static Map<String, Map<Suitability, Integer>> all() {
        return overrides;
    }

    /**
     * Give this species this level for this job (0 takes it away), and save. If it can't be saved,
     * nothing changes.
     */
    public static void set(String speciesId, Suitability job, int level) throws IOException {
        Map<String, Map<Suitability, Integer>> changed = copy(overrides);
        changed.computeIfAbsent(speciesId, k -> new EnumMap<>(Suitability.class))
                .put(job, level);
        save(changed);
    }

    /**
     * Drop this species' override for this job, or all its overrides if {@code job} is null, and
     * save; false if it had none.
     */
    public static boolean reset(String speciesId, @Nullable Suitability job) throws IOException {
        Map<Suitability, Integer> jobs = overrides.get(speciesId);
        if (jobs == null || (job != null && !jobs.containsKey(job))) {
            return false;
        }
        Map<String, Map<Suitability, Integer>> changed = copy(overrides);
        if (job == null) {
            changed.remove(speciesId);
        } else {
            changed.get(speciesId).remove(job);
        }
        save(changed);
        return true;
    }

    /** Drop every override, and save; how many species had any. */
    public static int resetAll() throws IOException {
        int species = overrides.size();
        if (species > 0) {
            save(Map.of());
        }
        return species;
    }

    private static void save(Map<String, Map<Suitability, Integer>> changed) throws IOException {
        Map<String, Map<Suitability, Integer>> frozen = freeze(changed);
        if (file != null) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, toJson(frozen), StandardCharsets.UTF_8);
        }
        overrides = frozen;
        apply();
    }

    private static void apply() {
        SuitabilityData.setCurrent(datapack.withSpecies(overrides));
    }

    /** The overrides in a file's text. Anything malformed is skipped and reported to {@code warn}. */
    static Map<String, Map<Suitability, Integer>> parse(String json, Consumer<String> warn) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            warn.accept("it isn't valid JSON (" + e.getMessage() + ")");
            return Map.of();
        }
        if (!(root instanceof JsonObject object) || !(object.get(SPECIES) instanceof JsonObject species)) {
            warn.accept("expected {\"species\": {\"cobblemon:bulbasaur\": {\"watering\": 3}}}");
            return Map.of();
        }
        Map<String, Map<Suitability, Integer>> result = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : species.entrySet()) {
            String id = entry.getKey().toLowerCase(Locale.ROOT);
            String speciesId = id.contains(":") ? id : DEFAULT_SPECIES_NAMESPACE + ":" + id;
            if (!(entry.getValue() instanceof JsonObject jobs)) {
                warn.accept("\"" + id + "\" should be an object like {\"watering\": 3}");
                continue;
            }
            for (Map.Entry<String, JsonElement> level : jobs.entrySet()) {
                Suitability job = Suitability.fromId(level.getKey());
                JsonElement value = level.getValue();
                if (job == null) {
                    warn.accept("\"" + level.getKey() + "\" for " + id + " isn't a job");
                } else if (!value.isJsonPrimitive()
                        || !value.getAsJsonPrimitive().isNumber()
                        || value.getAsDouble() != value.getAsInt()
                        || value.getAsInt() < 0
                        || value.getAsInt() > SuitabilityData.MAX_LEVEL) {
                    warn.accept(id + "'s " + job.getId() + " should be a level from 0 to " + SuitabilityData.MAX_LEVEL);
                } else {
                    result.computeIfAbsent(speciesId, k -> new EnumMap<>(Suitability.class))
                            .put(job, value.getAsInt());
                }
            }
        }
        return freeze(result);
    }

    /** The overrides as the file holds them: species in order, each one's jobs in {@link Suitability}'s order. */
    static String toJson(Map<String, Map<Suitability, Integer>> overrides) {
        JsonObject species = new JsonObject();
        new TreeMap<>(overrides).forEach((id, jobs) -> {
            JsonObject levels = new JsonObject();
            for (Suitability job : Suitability.values()) {
                Integer level = jobs.get(job);
                if (level != null) {
                    levels.addProperty(job.getId(), level);
                }
            }
            species.add(id, levels);
        });
        JsonObject root = new JsonObject();
        root.add(SPECIES, species);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n";
    }

    private static Map<String, Map<Suitability, Integer>> copy(Map<String, Map<Suitability, Integer>> overrides) {
        Map<String, Map<Suitability, Integer>> copy = new HashMap<>();
        overrides.forEach((id, jobs) -> {
            Map<Suitability, Integer> levels = new EnumMap<>(Suitability.class);
            levels.putAll(jobs);
            copy.put(id, levels);
        });
        return copy;
    }

    /** An unchangeable copy, without species left with no overrides. */
    private static Map<String, Map<Suitability, Integer>> freeze(Map<String, Map<Suitability, Integer>> overrides) {
        Map<String, Map<Suitability, Integer>> frozen = new HashMap<>();
        copy(overrides).forEach((id, jobs) -> {
            if (!jobs.isEmpty()) {
                frozen.put(id, Collections.unmodifiableMap(jobs));
            }
        });
        return Map.copyOf(frozen);
    }
}
