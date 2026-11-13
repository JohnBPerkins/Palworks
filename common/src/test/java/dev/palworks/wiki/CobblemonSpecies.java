package dev.palworks.wiki;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.palworks.suitability.SuitabilityData.Form;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Cobblemon's species, read from its species data files ({@code data/cobblemon/species/<gen>/<name>.json}
 * in its jar) without a running game, for the wiki's tables. Only implemented species are kept, each
 * with its standard form and its forms that have types or base stats of their own (battle-only forms,
 * such as Megas, are left out, as they never work).
 */
final class CobblemonSpecies {
    private static final String SPECIES_DIR = "data/cobblemon/species/";
    private static final String PRIMARY_TYPE = "primaryType";
    private static final String SECONDARY_TYPE = "secondaryType";
    private static final String BASE_STATS = "baseStats";
    private static final String PRE_EVOLUTION = "preEvolution";
    private static final String FORMS = "forms";
    /** How far back an evolution line is followed, as {@code DatapackSuitabilityProvider} does. */
    private static final int MAX_LINE = 8;

    /**
     * One species, or one of its forms ({@code form} is null for the standard form): its national
     * Pokédex number, id ({@code cobblemon:scizor}), name, form name, types and evolution line (its own
     * form first, as {@link dev.palworks.suitability.SuitabilityData#levels} takes it).
     */
    record Entry(int dex, String id, String name, String form, List<String> types, int baseStatTotal, List<Form> line) {
        String displayName() {
            return form == null ? name : name + " (" + form + ")";
        }
    }

    private CobblemonSpecies() {}

    /** Every species file in a Cobblemon jar, by file name ({@code scizor}). */
    static Map<String, JsonObject> readJar(Path jar) throws IOException {
        Map<String, JsonObject> files = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith(SPECIES_DIR) && name.endsWith(".json")) {
                    try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                        String file = name.substring(name.lastIndexOf('/') + 1, name.length() - ".json".length());
                        files.put(file, JsonParser.parseReader(reader).getAsJsonObject());
                    }
                }
            }
        }
        return files;
    }

    /** The implemented species in these files, and their forms with types or stats of their own, by Pokédex number. */
    static List<Entry> entries(Map<String, JsonObject> files) {
        List<Entry> entries = new ArrayList<>();
        files.forEach((file, species) -> {
            if (!bool(species, "implemented")) {
                return;
            }
            int dex = species.get("nationalPokedexNumber").getAsInt();
            String name = species.get("name").getAsString();
            String id = "cobblemon:" + file;
            entries.add(entry(files, dex, id, name, null, species, null));
            if (species.has(FORMS)) {
                for (JsonElement element : species.getAsJsonArray(FORMS)) {
                    JsonObject form = element.getAsJsonObject();
                    if (!bool(form, "battleOnly") && (form.has(PRIMARY_TYPE) || form.has(BASE_STATS))) {
                        entries.add(entry(files, dex, id, name, form.get("name").getAsString(), species, form));
                    }
                }
            }
        });
        entries.sort(Comparator.comparingInt(Entry::dex)
                .thenComparing(e -> e.form() != null)
                .thenComparing(Entry::displayName));
        return entries;
    }

    private static Entry entry(
            Map<String, JsonObject> files,
            int dex,
            String id,
            String name,
            String formName,
            JsonObject species,
            JsonObject form) {
        List<Form> line = new ArrayList<>();
        JsonObject currentSpecies = species;
        JsonObject currentForm = form;
        while (currentSpecies != null && line.size() < MAX_LINE) {
            line.add(new Form(types(currentSpecies, currentForm), baseStatTotal(currentSpecies, currentForm)));
            String pre = preEvolution(currentSpecies, currentForm);
            if (pre == null) {
                break;
            }
            // "quilava", or "growlithe hisuian": a species and, after it, the aspect naming its form
            String[] parts = pre.trim().toLowerCase(Locale.ROOT).split("\\s+", 2);
            currentSpecies = files.get(parts[0]);
            currentForm = currentSpecies == null || parts.length < 2 ? null : formWithAspect(currentSpecies, parts[1]);
        }
        Form own = line.get(0);
        return new Entry(dex, id, name, formName, List.copyOf(own.typeIds()), own.baseStatTotal(), List.copyOf(line));
    }

    /**
     * A form's types: its own if it gives a primary type (with no secondary type, it has just the
     * one: Galarian Slowpoke is pure Psychic), else the species'.
     */
    private static List<String> types(JsonObject species, JsonObject form) {
        JsonObject from = form != null && form.has(PRIMARY_TYPE) ? form : species;
        List<String> types = new ArrayList<>();
        types.add(from.get(PRIMARY_TYPE).getAsString().toLowerCase(Locale.ROOT));
        if (from.has(SECONDARY_TYPE) && !from.get(SECONDARY_TYPE).isJsonNull()) {
            types.add(from.get(SECONDARY_TYPE).getAsString().toLowerCase(Locale.ROOT));
        }
        return types;
    }

    private static int baseStatTotal(JsonObject species, JsonObject form) {
        JsonObject stats = (form != null && form.has(BASE_STATS) ? form : species).getAsJsonObject(BASE_STATS);
        int total = 0;
        for (Map.Entry<String, JsonElement> stat : stats.entrySet()) {
            total += stat.getValue().getAsInt();
        }
        return total;
    }

    private static String preEvolution(JsonObject species, JsonObject form) {
        JsonObject from = form != null && form.has(PRE_EVOLUTION) ? form : species;
        return from.has(PRE_EVOLUTION) ? from.get(PRE_EVOLUTION).getAsString() : null;
    }

    /** The species' form with this aspect ({@code hisuian}), or null (the standard form) if none has it. */
    private static JsonObject formWithAspect(JsonObject species, String aspect) {
        if (species.has(FORMS)) {
            for (JsonElement element : species.getAsJsonArray(FORMS)) {
                JsonObject form = element.getAsJsonObject();
                if (form.has("aspects")) {
                    for (JsonElement a : form.getAsJsonArray("aspects")) {
                        if (a.getAsString().equalsIgnoreCase(aspect)) {
                            return form;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean bool(JsonObject object, String key) {
        return object.has(key) && object.get(key).getAsBoolean();
    }
}
