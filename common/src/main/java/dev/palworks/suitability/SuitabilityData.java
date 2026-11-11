package dev.palworks.suitability;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Which work each Pokémon type and species can do, loaded from datapack files: one per job (work
 * suitability), {@code data/<namespace>/palworks/suitability/<job>.json}, for example
 * {@code data/palworks/palworks/suitability/lumbering.json}:
 * <pre>{@code
 * {
 *   "types":   { "fighting": 1, "steel": 1 },
 *   "species": { "cobblemon:scizor": 3, "cobblemon:pinsir": 2 }
 * }
 * }</pre>
 * <ul>
 *   <li>The file name is the job's id ({@link Suitability}: {@code kindling}, {@code lumbering}...).</li>
 *   <li>{@code types}: every Pokémon of that type (its Showdown id, {@code fire}) can do the job. Its
 *       number is added to the Pokémon's own level (see below): 1 is the usual, 2 gives one more.</li>
 *   <li>{@code species}: extra species, by id ({@code cobblemon:scizor}; without a namespace it's
 *       Cobblemon's), at exactly that level, whatever its types and stats would give. Level 0 takes
 *       the job away from that species even if one of its types gives it.</li>
 *   <li>Levels go from 1 to {@value #MAX_LEVEL} (0 too, for species).</li>
 * </ul>
 * Files for the same job from different namespaces merge, like vanilla tags, so an addon can add a
 * species without copying ours; where two give the same type or species a level, the higher wins
 * (and a species' 0 wins over any level). A file with {@code "replace": true} replaces the other
 * namespaces' files for its job instead. As with any datapack file, a pack loaded later replaces a
 * file with the same path, so a pack can override ours.
 *
 * <p>A Pokémon's level for a job (its work level) comes from how strong it is: its form's base stat
 * total ({@link #powerLevel}), from 1 (Squirtle, 314) to 5 (Kyogre, 670). A type that gives the job
 * gives that level, plus its own number less 1, less one for each earlier form in its evolution
 * line that didn't have the type: Hisuian Typhlosion is Kindling 3 but Nether 1, as Cyndaquil and
 * Quilava were Fire but not Ghost. Its level is the best of its types', at least 1, unless its
 * species has a level of its own. Pure logic (no Minecraft types), so it's unit-tested;
 * {@link SuitabilityReloadListener} feeds it and {@link DatapackSuitabilityProvider} reads it.
 */
public final class SuitabilityData {
    private static final String REPLACE = "replace";

    public static final int MAX_LEVEL = 5;
    /**
     * The base stat totals at which a Pokémon's power level goes up: below the first it's 1, from
     * the last on it's {@value #MAX_LEVEL}. A first form is usually under 350, a middle one under
     * 450, a final one under 550; pseudo-legendaries and most legendaries are 580 to 600, and the
     * strongest legendaries 650 or more.
     */
    private static final int[] POWER_THRESHOLDS = {350, 450, 550, 650};

    private static final String DEFAULT_SPECIES_NAMESPACE = "cobblemon";

    public static final SuitabilityData EMPTY = new SuitabilityData(Map.of(), Map.of(), 0);

    private static volatile SuitabilityData current = EMPTY;

    /** Type Showdown id -> job levels. */
    private final Map<String, Map<Suitability, Integer>> types;
    /** Species id ("cobblemon:scizor") -> job levels; 0 removes a job. */
    private final Map<String, Map<Suitability, Integer>> species;

    private final int jobCount;

    private SuitabilityData(
            Map<String, Map<Suitability, Integer>> types,
            Map<String, Map<Suitability, Integer>> species,
            int jobCount) {
        this.types = types;
        this.species = species;
        this.jobCount = jobCount;
    }

    /** The data loaded by the last (re)load. Empty until the server has loaded its datapacks. */
    public static SuitabilityData current() {
        return current;
    }

    public static void setCurrent(SuitabilityData data) {
        current = data;
    }

    /** How many jobs have at least one file. */
    public int jobCount() {
        return jobCount;
    }

    /** How many types can do any job. */
    public int typeCount() {
        return types.size();
    }

    /** How many species have a job (or a job taken away) of their own. */
    public int speciesCount() {
        return species.size();
    }

    /**
     * One form in a Pokémon's evolution line: its types (Showdown ids) and its base stat total.
     */
    public record Form(Collection<String> typeIds, int baseStatTotal) {}

    /**
     * A Pokémon's power level, 1 to {@value #MAX_LEVEL}, from its base stat total: Squirtle (314) 1,
     * Wartortle (405) 2, Blastoise (530) 3, Dragonite (600) 4, Kyogre (670) 5.
     */
    public static int powerLevel(int baseStatTotal) {
        int level = 1;
        for (int threshold : POWER_THRESHOLDS) {
            if (baseStatTotal >= threshold) {
                level++;
            }
        }
        return level;
    }

    /**
     * Job levels for a Pokémon of this species, whose evolution line is {@code line}: its own form
     * first, then the one it evolved from, and so on back to the first. Only jobs with a level
     * above 0 are included.
     */
    public Map<Suitability, Integer> levels(String speciesId, List<Form> line) {
        Map<Suitability, Integer> result = new EnumMap<>(Suitability.class);
        if (!line.isEmpty()) {
            Form form = line.get(0);
            List<Form> earlier = line.subList(1, line.size());
            int power = powerLevel(form.baseStatTotal());
            for (String typeId : form.typeIds()) {
                int notThisType = 0;
                for (Form before : earlier) {
                    if (!before.typeIds().contains(typeId)) {
                        notThisType++;
                    }
                }
                for (Map.Entry<Suitability, Integer> e :
                        types.getOrDefault(typeId, Map.of()).entrySet()) {
                    int level = Math.max(1, Math.min(MAX_LEVEL, power + e.getValue() - 1 - notThisType));
                    result.merge(e.getKey(), level, Math::max);
                }
            }
        }
        for (Map.Entry<Suitability, Integer> e :
                species.getOrDefault(speciesId, Map.of()).entrySet()) {
            if (e.getValue() == 0) {
                result.remove(e.getKey());
            } else {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    /** One job file's parsed contents. */
    private record JobFile(boolean replace, Map<String, Integer> types, Map<String, Integer> species) {}

    /**
     * Builds the data from datapack files, keyed by file id {@code "<namespace>:<job>"}. Anything
     * malformed is skipped and reported to {@code warn}, so one bad file or entry doesn't stop the
     * rest loading.
     */
    public static SuitabilityData fromFiles(Map<String, JsonElement> files, Consumer<String> warn) {
        // Group each job's files (one per namespace)
        Map<Suitability, List<JobFile>> byJob = new EnumMap<>(Suitability.class);
        for (Map.Entry<String, JsonElement> file : files.entrySet()) {
            String fileId = file.getKey();
            String jobId = fileId.substring(fileId.indexOf(':') + 1);
            Suitability job = Suitability.fromId(jobId);
            if (job == null) {
                warn.accept("Ignoring suitability file " + fileId + ": \"" + jobId + "\" isn't a job (expected one of "
                        + String.join(", ", allJobIds()) + ")");
                continue;
            }
            JobFile parsed = parseJobFile(fileId, file.getValue(), warn);
            if (parsed != null) {
                byJob.computeIfAbsent(job, j -> new ArrayList<>()).add(parsed);
            }
        }

        Map<String, Map<Suitability, Integer>> types = new HashMap<>();
        Map<String, Map<Suitability, Integer>> species = new HashMap<>();
        byJob.forEach((job, jobFiles) -> {
            List<JobFile> used = jobFiles.stream().anyMatch(JobFile::replace)
                    ? jobFiles.stream().filter(JobFile::replace).toList()
                    : jobFiles;
            for (JobFile jobFile : used) {
                jobFile.types()
                        .forEach((type, level) -> types.computeIfAbsent(type, k -> new EnumMap<>(Suitability.class))
                                .merge(job, level, Math::max));
                jobFile.species()
                        .forEach((id, level) -> species.computeIfAbsent(id, k -> new EnumMap<>(Suitability.class))
                                .merge(job, level, (a, b) -> a == 0 || b == 0 ? 0 : Math.max(a, b)));
            }
        });
        types.replaceAll((k, v) -> Collections.unmodifiableMap(v));
        species.replaceAll((k, v) -> Collections.unmodifiableMap(v));
        return new SuitabilityData(Map.copyOf(types), Map.copyOf(species), byJob.size());
    }

    private static List<String> allJobIds() {
        List<String> ids = new ArrayList<>();
        for (Suitability s : Suitability.values()) {
            ids.add(s.getId());
        }
        return ids;
    }

    private static JobFile parseJobFile(String fileId, JsonElement json, Consumer<String> warn) {
        if (!(json instanceof JsonObject object)) {
            warn.accept("Ignoring suitability file " + fileId
                    + ": expected an object like {\"types\": {...}, \"species\": {...}}");
            return null;
        }
        for (String key : object.keySet()) {
            if (!"types".equals(key) && !"species".equals(key) && !REPLACE.equals(key)) {
                warn.accept(
                        "Ignoring " + quotedIn(key, fileId) + ": only \"types\", \"species\" and \"replace\" are read");
            }
        }
        boolean replace = object.has(REPLACE)
                && object.get(REPLACE).isJsonPrimitive()
                && object.get(REPLACE).getAsJsonPrimitive().isBoolean()
                && object.get(REPLACE).getAsBoolean();
        Map<String, Integer> types = parseLevels(fileId, "types", object.get("types"), 1, warn);
        Map<String, Integer> species = new HashMap<>();
        parseLevels(fileId, "species", object.get("species"), 0, warn)
                .forEach((id, level) ->
                        species.put(id.contains(":") ? id : DEFAULT_SPECIES_NAMESPACE + ":" + id, level));
        return new JobFile(replace, types, species);
    }

    private static Map<String, Integer> parseLevels(
            String fileId, String section, JsonElement json, int min, Consumer<String> warn) {
        Map<String, Integer> levels = new HashMap<>();
        if (json == null) {
            return levels;
        }
        if (!(json instanceof JsonObject object)) {
            warn.accept("Ignoring " + quotedIn(section, fileId) + ": expected an object like {\"name\": 1}");
            return levels;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                warn.accept("Ignoring " + quotedIn(entry.getKey(), fileId) + " " + section
                        + ": the level must be a number");
                continue;
            }
            int level = value.getAsInt();
            if (level < min || level > MAX_LEVEL) {
                int clamped = Math.max(min, Math.min(MAX_LEVEL, level));
                warn.accept("Level " + level + " for " + quotedIn(entry.getKey(), fileId) + " " + section
                        + " is outside " + min + "-" + MAX_LEVEL + "; using " + clamped);
                level = clamped;
            }
            levels.put(name, level);
        }
        return levels;
    }

    /** {@code "name" in fileId}, for warnings. */
    private static String quotedIn(String name, String fileId) {
        return "\"" + name + "\" in " + fileId;
    }
}
