package dev.palworks.wiki;

import com.google.common.base.Splitter;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityData;
import dev.palworks.wiki.CobblemonSpecies.Entry;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds the wiki: the hand-written pages in {@code wiki/}, with their {@code <!-- generated:... -->}
 * markers filled in, and a page per work skill listing every Pokémon that can do it, at its level,
 * from the shipped suitability files and Cobblemon's species data. Levels come from
 * {@link SuitabilityData}, as in game. Run by {@code ./gradlew :common:generateWiki}; CI publishes
 * the result to the GitHub wiki. It lives with the tests so it isn't in the mod jar.
 */
public final class WikiGenerator {
    /** Cobblemon's types, in its order, as the datapack files name them. */
    static final List<String> TYPES = List.of(
            "normal",
            "fire",
            "water",
            "grass",
            "electric",
            "ice",
            "fighting",
            "poison",
            "ground",
            "flying",
            "psychic",
            "bug",
            "rock",
            "ghost",
            "dragon",
            "dark",
            "steel",
            "fairy");

    static final String BY_POKEMON_PAGE = "Work-Skills-by-Pokemon";

    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    private static final Pattern HEADING = Pattern.compile("^#+ (.+)$", Pattern.MULTILINE);
    private static final Pattern NOT_IN_ANCHOR = Pattern.compile("[^\\w\\- ]", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern MARKER = Pattern.compile("<!-- generated:([a-z-]+) -->");
    private static final String TYPES_KEY = "types";
    private static final String SPECIES_KEY = "species";
    private static final String NONE = "none";

    private final List<Entry> species;
    private final Map<Suitability, JsonObject> files;
    private final Map<String, String> lang;
    private final String cobblemonVersion;
    private final SuitabilityData data;

    WikiGenerator(
            List<Entry> species,
            Map<Suitability, JsonObject> files,
            Map<String, String> lang,
            String cobblemonVersion) {
        this.species = species;
        this.files = files;
        this.lang = lang;
        this.cobblemonVersion = cobblemonVersion;
        Map<String, JsonElement> byId = new HashMap<>();
        files.forEach((job, file) -> byId.put("palworks:" + job.getId(), file));
        this.data = SuitabilityData.fromFiles(byId, warning -> {
            throw new IllegalStateException("Shipped suitability file: " + warning);
        });
    }

    /** Arguments: Cobblemon jar, its version, the hand-written pages' folder, the output folder. */
    public static void main(String[] args) throws IOException {
        Path jar = Path.of(args[0]);
        Path pages = Path.of(args[2]);
        Path out = Path.of(args[3]);
        WikiGenerator generator = new WikiGenerator(
                CobblemonSpecies.entries(CobblemonSpecies.readJar(jar)), shippedFiles(), shippedLang(), args[1]);
        Map<String, String> wiki = new LinkedHashMap<>();
        try (Stream<Path> handWritten = Files.list(pages)) {
            for (Path page :
                    handWritten.filter(p -> p.toString().endsWith(".md")).toList()) {
                String file = page.getFileName().toString();
                String text = Files.readString(page, StandardCharsets.UTF_8);
                wiki.put(file.substring(0, file.length() - ".md".length()), generator.fill(text, file));
            }
        }
        wiki.putAll(generator.generatedPages());
        List<String> broken = brokenLinks(wiki);
        if (!broken.isEmpty()) {
            throw new IllegalStateException("Broken wiki links:\n  " + String.join("\n  ", broken));
        }
        Files.createDirectories(out);
        try (Stream<Path> old = Files.list(out)) {
            for (Path file : old.toList()) {
                Files.delete(file);
            }
        }
        for (Map.Entry<String, String> page : wiki.entrySet()) {
            Files.writeString(out.resolve(page.getKey() + ".md"), page.getValue());
        }
    }

    /**
     * Links between these pages (name to text) that go nowhere: to a page that isn't there, or a
     * heading it doesn't have ({@code Jobs#chop-trees}). Links to other sites aren't checked.
     */
    static List<String> brokenLinks(Map<String, String> pages) {
        List<String> broken = new ArrayList<>();
        pages.forEach((name, text) -> {
            Matcher link = LINK.matcher(text);
            while (link.find()) {
                String target = link.group(1);
                if (target.contains("://")) {
                    continue;
                }
                int hash = target.indexOf('#');
                String page = hash < 0 ? target : target.substring(0, hash);
                String to = page.isEmpty() ? name : page;
                if (!pages.containsKey(to)
                        || (hash >= 0 && !anchors(pages.get(to)).contains(target.substring(hash + 1)))) {
                    broken.add(name + ": " + target);
                }
            }
        });
        return broken;
    }

    /** A page's heading anchors, as GitHub makes them: {@code ## Chop trees} is {@code chop-trees}. */
    private static Set<String> anchors(String text) {
        Set<String> anchors = new TreeSet<>();
        Matcher heading = HEADING.matcher(text);
        while (heading.find()) {
            anchors.add(NOT_IN_ANCHOR
                    .matcher(heading.group(1).toLowerCase(Locale.ROOT))
                    .replaceAll("")
                    .replace(' ', '-'));
        }
        return anchors;
    }

    /** The suitability files the mod ships, from the classpath. */
    static Map<Suitability, JsonObject> shippedFiles() {
        Map<Suitability, JsonObject> files = new EnumMap<>(Suitability.class);
        for (Suitability job : Suitability.values()) {
            files.put(job, readResource("/data/palworks/palworks/suitability/" + job.getId() + ".json"));
        }
        return files;
    }

    /** The mod's English names, from the classpath. */
    static Map<String, String> shippedLang() {
        Map<String, String> lang = new HashMap<>();
        readResource("/assets/palworks/lang/en_us.json")
                .entrySet()
                .forEach(e -> lang.put(e.getKey(), e.getValue().getAsString()));
        return lang;
    }

    private static JsonObject readResource(String path) {
        try (InputStream in = WikiGenerator.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Not on the classpath: " + path);
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A hand-written page with its markers filled in. An unknown marker is an error. */
    String fill(String page, String pageName) {
        Matcher matcher = MARKER.matcher(page);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String table = switch (matcher.group(1)) {
                case "type-table" -> typeTable();
                case "skill-table" -> skillTable();
                default ->
                    throw new IllegalArgumentException("Unknown marker " + matcher.group() + " in " + pageName
                            + "; expected type-table or skill-table");
            };
            matcher.appendReplacement(result, Matcher.quoteReplacement(table.stripTrailing()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** Page name to contents, for each work skill and the table of every Pokémon. */
    Map<String, String> generatedPages() {
        Map<String, String> pages = new LinkedHashMap<>();
        Map<Entry, Map<Suitability, Integer>> levels = levels();
        for (Suitability job : Suitability.values()) {
            pages.put(skillPage(job), skillPageText(job, levels));
        }
        pages.put(BY_POKEMON_PAGE, byPokemonText(levels));
        return pages;
    }

    /** The wiki page listing who can do a job: {@code Lumbering-Workers}. */
    static String skillPage(Suitability job) {
        StringBuilder name = new StringBuilder();
        for (String word : Splitter.on('_').split(job.getId())) {
            name.append(capitalize(word)).append('-');
        }
        return name + "Workers";
    }

    /**
     * Each entry's levels; a form is only kept if its levels differ from its species' standard form,
     * so the tables don't repeat Pikachu's costumes.
     */
    private Map<Entry, Map<Suitability, Integer>> levels() {
        Map<Entry, Map<Suitability, Integer>> levels = new LinkedHashMap<>();
        Map<String, Map<Suitability, Integer>> standard = new HashMap<>();
        for (Entry entry : species) {
            Map<Suitability, Integer> own = data.levels(entry.id(), entry.line());
            if (entry.form() == null) {
                standard.put(entry.id(), own);
                levels.put(entry, own);
            } else if (!own.equals(standard.get(entry.id()))) {
                levels.put(entry, own);
            }
        }
        return levels;
    }

    private String skillPageText(Suitability job, Map<Entry, Map<Suitability, Integer>> levels) {
        StringBuilder page = new StringBuilder(header());
        page.append("# Who can do ")
                .append(skillName(job))
                .append("\n\n**From types:** ")
                .append(typeNames(typesGiving(job)))
                .append(". **Species with an entry of their own:** ")
                .append(speciesEntries(job).size())
                .append(". Levels are explained on [Suitabilities](Suitabilities#work-levels).\n\n");
        List<Map.Entry<Entry, Map<Suitability, Integer>>> rows = levels.entrySet().stream()
                .filter(e -> e.getValue().containsKey(job))
                .toList();
        if (rows.isEmpty()) {
            page.append("No Pokémon can do it.\n");
        } else {
            page.append("| # | Pokémon | Types | Base stats | Level | Why |\n|---|---|---|---|---|---|\n");
        }
        for (Map.Entry<Entry, Map<Suitability, Integer>> row : rows) {
            Entry entry = row.getKey();
            page.append(row(
                    entry.dex(),
                    entry.displayName(),
                    typeList(entry.types()),
                    entry.baseStatTotal(),
                    row.getValue().get(job),
                    why(job, entry)));
        }
        Set<String> listed = new TreeSet<>();
        species.forEach(e -> listed.add(e.id()));
        List<String> missing = speciesEntries(job).keySet().stream()
                .filter(id -> !listed.contains(id))
                .map(id -> capitalize(id.substring(id.indexOf(':') + 1)))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            page.append("\nAlso given it, once Cobblemon adds them: ")
                    .append(String.join(", ", missing))
                    .append(".\n");
        }
        return page.toString();
    }

    private String byPokemonText(Map<Entry, Map<Suitability, Integer>> levels) {
        StringBuilder page = new StringBuilder(header());
        page.append("# Work skills by Pokémon\n\nEvery Pokémon in Cobblemon ")
                .append(cobblemonVersion)
                .append(", with the work skills it has and its level at each. Regional and other forms are listed"
                        + " when their skills differ. Levels are explained on"
                        + " [Suitabilities](Suitabilities#work-levels).\n\n"
                        + "| # | Pokémon | Types | Base stats | Work skills |\n|---|---|---|---|---|\n");
        levels.forEach((entry, own) -> {
            List<String> skills = new ArrayList<>();
            own.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(this::skillName)))
                    .forEach(e -> skills.add(link(e.getKey()) + " " + e.getValue()));
            page.append(row(
                    entry.dex(),
                    entry.displayName(),
                    typeList(entry.types()),
                    entry.baseStatTotal(),
                    skills.isEmpty() ? NONE : String.join(", ", skills)));
        });
        return page.toString();
    }

    /** Type and the work skills it gives, for {@code <!-- generated:type-table -->}. */
    String typeTable() {
        StringBuilder table = new StringBuilder(64 * TYPES.size()).append("| Type | Work skills |\n|---|---|\n");
        for (String type : TYPES) {
            List<String> skills = new ArrayList<>();
            for (Suitability job : Suitability.values()) {
                if (typesGiving(job).contains(type)) {
                    skills.add(link(job));
                }
            }
            table.append(row(capitalize(type), skills.isEmpty() ? NONE : String.join(", ", skills)));
        }
        return table.toString();
    }

    /** Work skill, the types giving it and who can do it, for {@code <!-- generated:skill-table -->}. */
    String skillTable() {
        StringBuilder table = new StringBuilder(96 * Suitability.values().length)
                .append("| Work skill | From types | Species entries | Who |\n|---|---|---|---|\n");
        for (Suitability job : Suitability.values()) {
            table.append(row(
                    skillName(job),
                    typeNames(typesGiving(job)),
                    speciesEntries(job).size(),
                    "[Every Pokémon](" + skillPage(job) + ")"));
        }
        return table.toString();
    }

    /** A Markdown table row. */
    private static String row(Object... cells) {
        StringBuilder row = new StringBuilder(16 * cells.length).append('|');
        for (Object cell : cells) {
            row.append(' ').append(cell).append(" |");
        }
        return row.append('\n').toString();
    }

    private String link(Suitability job) {
        return "[" + skillName(job) + "](" + skillPage(job) + ")";
    }

    /** "Ground, Rock", or "none". */
    private static String typeNames(List<String> types) {
        return types.isEmpty()
                ? NONE
                : String.join(
                        ", ", types.stream().map(WikiGenerator::capitalize).toList());
    }

    private String header() {
        return "<!-- Generated by ./gradlew :common:generateWiki from Palworks' suitability files and Cobblemon "
                + cobblemonVersion + ". Don't edit it here: change the files and the wiki follows. -->\n\n";
    }

    private String why(Suitability job, Entry entry) {
        if (speciesEntries(job).containsKey(entry.id())) {
            return "species";
        }
        List<String> types = typesGiving(job);
        List<String> its = entry.types().stream()
                .filter(types::contains)
                .map(WikiGenerator::capitalize)
                .toList();
        return String.join(", ", its) + " type";
    }

    private List<String> typesGiving(Suitability job) {
        JsonObject file = files.get(job);
        List<String> types = new ArrayList<>();
        if (file != null && file.has(TYPES_KEY)) {
            for (String type : file.getAsJsonObject(TYPES_KEY).keySet()) {
                types.add(type.toLowerCase(Locale.ROOT));
            }
        }
        types.sort(Comparator.comparingInt(TYPES::indexOf));
        return types;
    }

    /** The job's species entries, by full id ({@code cobblemon:scizor}). */
    private Map<String, Integer> speciesEntries(Suitability job) {
        JsonObject file = files.get(job);
        Map<String, Integer> entries = new HashMap<>();
        if (file != null && file.has(SPECIES_KEY)) {
            for (Map.Entry<String, JsonElement> e :
                    file.getAsJsonObject(SPECIES_KEY).entrySet()) {
                String id = e.getKey().toLowerCase(Locale.ROOT);
                entries.put(
                        id.contains(":") ? id : "cobblemon:" + id, e.getValue().getAsInt());
            }
        }
        return entries;
    }

    private String skillName(Suitability job) {
        return lang.getOrDefault("palworks.work_skill." + job.getId(), job.getId());
    }

    private static String typeList(List<String> types) {
        return String.join("/", types.stream().map(WikiGenerator::capitalize).toList());
    }

    private static String capitalize(String word) {
        return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
