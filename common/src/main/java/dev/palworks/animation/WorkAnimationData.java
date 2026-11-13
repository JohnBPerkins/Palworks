package dev.palworks.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.palworks.ai.WorkStance;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Work animations of a species' own, loaded from datapack files: the worker roster. A species
 * that ships real work animations (a resource pack's poser, see {@link WorkAspects}) can also play
 * its own move effect, with its own timing, from where suits its body and move, in place of the
 * placeholder every species plays ({@link WorkAnimations}). Each file,
 * {@code data/<namespace>/palworks/work_animations/<name>.json}, gives some species changes to
 * some animations:
 * <pre>{@code
 * {
 *   "species": ["cobblemon:scizor", "scyther"],
 *   "animations": {
 *     "chop": { "move": "furycutter", "impact_ticks": 18, "duration_ticks": 45, "stance": "against" },
 *     "harvest": { "stance": { "gap": 2.5, "max_ring": 5 } }
 *   }
 * }
 * }</pre>
 * <ul>
 *   <li>{@code species}: a species id, or a list of them; without a namespace it's Cobblemon's.</li>
 *   <li>{@code animations}: by id ({@code ignite_furnace}, {@code harvest}, {@code plant},
 *       {@code water}, {@code chop}), what changes. Anything left out stays as the default has it.</li>
 *   <li>{@code move}: the action effect to play, by move name ({@code furycutter}, Cobblemon's)
 *       or id ({@code myaddon:chop}); {@code impact_ticks}: when it lands on the block;
 *       {@code duration_ticks}: when the body animation is over; {@code aim_y}: where in the block
 *       it aims (0 bottom, 1 top).</li>
 *   <li>{@code stance}: where to stand, {@code "near"} (a ranged move) or {@code "against"} (a
 *       contact move), or an object of {@code gap}, {@code min_ring}, {@code max_ring} and
 *       {@code slack} (see {@link WorkStance}), any left out as the default stance has them.</li>
 * </ul>
 * Files merge: where two change the same thing for the same species, the one whose id sorts last
 * wins. Pure logic (no world), so it's unit-tested; {@link WorkAnimationReloadListener} feeds it.
 */
public final class WorkAnimationData {
    private static final String DEFAULT_SPECIES_NAMESPACE = "cobblemon";
    private static final String STANCE = "stance";
    private static final String MOVE = "move";
    private static final String IMPACT_TICKS = "impact_ticks";
    private static final String DURATION_TICKS = "duration_ticks";
    private static final String AIM_Y = "aim_y";
    private static final String GAP = "gap";
    private static final String MIN_RING = "min_ring";
    private static final String MAX_RING = "max_ring";
    private static final String SLACK = "slack";
    private static final Set<String> FILE_KEYS = Set.of("species", "animations");
    private static final Set<String> ANIMATION_KEYS = Set.of(MOVE, IMPACT_TICKS, DURATION_TICKS, AIM_Y, STANCE);
    private static final Set<String> STANCE_KEYS = Set.of(GAP, MIN_RING, MAX_RING, SLACK);

    public static final WorkAnimationData EMPTY = new WorkAnimationData(Map.of());

    private static volatile WorkAnimationData current = EMPTY;

    /** Species id -> animation id -> that species' animation. */
    private final Map<String, Map<String, WorkAnimation>> bySpecies;

    private WorkAnimationData(Map<String, Map<String, WorkAnimation>> bySpecies) {
        this.bySpecies = bySpecies;
    }

    /** The data loaded by the last (re)load. Empty until the server has loaded its datapacks. */
    public static WorkAnimationData current() {
        return current;
    }

    public static void setCurrent(WorkAnimationData data) {
        current = data;
    }

    /** How many species have an animation of their own. */
    public int speciesCount() {
        return bySpecies.size();
    }

    /** The animation this species plays in place of {@code animation}: its own, or the default. */
    public WorkAnimation forSpecies(WorkAnimation animation, String speciesId) {
        Map<String, WorkAnimation> own = bySpecies.get(speciesId);
        return own == null ? animation : own.getOrDefault(animation.id(), animation);
    }

    /**
     * Builds the data from datapack files, keyed by file id ({@code "<namespace>:<name>"}), changing
     * {@code defaults} (by id). Anything malformed is skipped and reported to {@code warn}, so one
     * bad file or entry doesn't stop the rest loading.
     */
    public static WorkAnimationData fromFiles(
            Map<String, JsonElement> files, List<WorkAnimation> defaults, Consumer<String> warn) {
        Map<String, WorkAnimation> defaultsById = new LinkedHashMap<>();
        defaults.forEach(animation -> defaultsById.put(animation.id(), animation));

        // Species -> animation id -> its changes, merged across files in id order
        Map<String, Map<String, JsonObject>> changes = new HashMap<>();
        for (Map.Entry<String, JsonElement> file : new TreeMap<>(files).entrySet()) {
            readFile(file.getKey(), file.getValue(), defaultsById, changes, warn);
        }

        Map<String, Map<String, WorkAnimation>> bySpecies = new HashMap<>();
        changes.forEach((species, byAnimation) -> byAnimation.forEach((id, change) -> {
            WorkAnimation animation = apply(defaultsById.get(id), change, species, warn);
            if (animation != null) {
                bySpecies.computeIfAbsent(species, s -> new HashMap<>()).put(id, animation);
            }
        }));
        bySpecies.replaceAll((species, byAnimation) -> Map.copyOf(byAnimation));
        return new WorkAnimationData(Map.copyOf(bySpecies));
    }

    private static void readFile(
            String fileId,
            JsonElement json,
            Map<String, WorkAnimation> defaults,
            Map<String, Map<String, JsonObject>> changes,
            Consumer<String> warn) {
        if (!(json instanceof JsonObject file)) {
            warn.accept("Ignoring work animation file " + fileId
                    + ": expected an object like {\"species\": [...], \"animations\": {...}}");
            return;
        }
        warnUnknownKeys(file, FILE_KEYS, fileId, warn);
        List<String> species = speciesIn(file.get("species"), fileId, warn);
        if (species.isEmpty()) {
            warn.accept("Ignoring work animation file " + fileId + ": it names no species");
            return;
        }
        if (!(file.get("animations") instanceof JsonObject animations)) {
            warn.accept("Ignoring work animation file " + fileId + ": \"animations\" must be an object, by id");
            return;
        }
        for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
            String id = entry.getKey();
            if (!defaults.containsKey(id)) {
                warn.accept("Ignoring \"" + id + "\" in " + fileId + ": not a work animation (expected one of "
                        + String.join(", ", defaults.keySet()) + ")");
                continue;
            }
            if (!(entry.getValue() instanceof JsonObject change)) {
                warn.accept("Ignoring \"" + id + "\" in " + fileId + ": expected an object of what changes");
                continue;
            }
            warnUnknownKeys(change, ANIMATION_KEYS, fileId + " (" + id + ")", warn);
            for (String speciesId : species) {
                JsonObject merged = changes.computeIfAbsent(speciesId, s -> new HashMap<>())
                        .computeIfAbsent(id, i -> new JsonObject());
                change.entrySet().forEach(field -> merged.add(field.getKey(), field.getValue()));
            }
        }
    }

    private static List<String> speciesIn(@Nullable JsonElement json, String fileId, Consumer<String> warn) {
        List<JsonElement> entries = new ArrayList<>();
        if (json instanceof JsonArray array) {
            array.forEach(entries::add);
        } else if (json != null) {
            entries.add(json);
        }
        List<String> species = new ArrayList<>();
        for (JsonElement entry : entries) {
            if (entry instanceof JsonPrimitive primitive && primitive.isString()) {
                String id = primitive.getAsString().toLowerCase(Locale.ROOT);
                species.add(id.contains(":") ? id : DEFAULT_SPECIES_NAMESPACE + ":" + id);
            } else {
                warn.accept("Ignoring species " + entry + " in " + fileId + ": a species is its id, as a string");
            }
        }
        return species;
    }

    /** The default with a species' changes made, or null (and a warning) if they don't make sense. */
    @Nullable
    private static WorkAnimation apply(WorkAnimation base, JsonObject change, String species, Consumer<String> warn) {
        String where = "\"" + base.id() + "\" for " + species;
        try {
            ResourceLocation move = base.moveEffect();
            if (change.has(MOVE)) {
                String name = string(change.get(MOVE), MOVE);
                move = ResourceLocation.tryParse(name.contains(":") ? name : DEFAULT_SPECIES_NAMESPACE + ":" + name);
                if (move == null) {
                    throw new IllegalArgumentException("\"" + name + "\" isn't a valid move or effect id");
                }
            }
            int impact =
                    change.has(IMPACT_TICKS) ? integer(change.get(IMPACT_TICKS), IMPACT_TICKS) : base.impactTicks();
            int duration = change.has(DURATION_TICKS)
                    ? integer(change.get(DURATION_TICKS), DURATION_TICKS)
                    : base.durationTicks();
            double aimY = change.has(AIM_Y) ? number(change.get(AIM_Y), AIM_Y) : base.aimY();
            WorkStance stance =
                    change.has(STANCE) ? stance(change.get(STANCE), base.stance(), where, warn) : base.stance();
            return new WorkAnimation(base.id(), move, impact, duration, aimY, stance);
        } catch (IllegalArgumentException e) {
            warn.accept("Ignoring the changes to " + where + ": " + e.getMessage());
            return null;
        }
    }

    private static WorkStance stance(JsonElement json, WorkStance base, String where, Consumer<String> warn) {
        if (json instanceof JsonPrimitive primitive && primitive.isString()) {
            return switch (primitive.getAsString().toLowerCase(Locale.ROOT)) {
                case "near" -> WorkStance.NEAR;
                case "against" -> WorkStance.AGAINST;
                default ->
                    throw new IllegalArgumentException(
                            "stance \"" + primitive.getAsString() + "\" isn't one: \"near\", \"against\" or an object");
            };
        }
        if (!(json instanceof JsonObject object)) {
            throw new IllegalArgumentException("stance must be \"near\", \"against\" or an object");
        }
        warnUnknownKeys(object, STANCE_KEYS, where + " stance", warn);
        return new WorkStance(
                object.has(GAP) ? number(object.get(GAP), GAP) : base.gap(),
                object.has(MIN_RING) ? integer(object.get(MIN_RING), MIN_RING) : base.minRing(),
                object.has(MAX_RING) ? integer(object.get(MAX_RING), MAX_RING) : base.maxRing(),
                object.has(SLACK) ? number(object.get(SLACK), SLACK) : base.slack());
    }

    private static String string(JsonElement json, String name) {
        if (json instanceof JsonPrimitive primitive && primitive.isString()) {
            return primitive.getAsString();
        }
        throw new IllegalArgumentException(name + " must be a string");
    }

    private static double number(JsonElement json, String name) {
        if (json instanceof JsonPrimitive primitive && primitive.isNumber()) {
            return primitive.getAsDouble();
        }
        throw new IllegalArgumentException(name + " must be a number");
    }

    private static int integer(JsonElement json, String name) {
        double value = number(json, name);
        if (value != Math.rint(value)) {
            throw new IllegalArgumentException(name + " must be a whole number of ticks or rings");
        }
        return (int) value;
    }

    private static void warnUnknownKeys(JsonObject object, Set<String> known, String where, Consumer<String> warn) {
        for (String key : object.keySet()) {
            if (!known.contains(key)) {
                warn.accept("Ignoring \"" + key + "\" in " + where + ": only "
                        + String.join(", ", known.stream().sorted().toList()) + " are read");
            }
        }
    }
}
