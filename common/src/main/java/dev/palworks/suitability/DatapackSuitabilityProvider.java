package dev.palworks.suitability;

import com.cobblemon.mod.common.api.pokemon.evolution.PreEvolution;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Suitabilities from the datapack files ({@link SuitabilityData}), by the Pokémon's current form
 * and types, so they follow it through evolution and form changes. Its levels also depend on its
 * form's base stats and on the forms it evolved from (see {@link SuitabilityData#levels}).
 */
public class DatapackSuitabilityProvider implements SuitabilityProvider {
    // A line longer than this is a datapack's loop (A evolves from B evolves from A): no real one is
    private static final int MAX_LINE_LENGTH = 8;

    // Each form and types' levels, worked out once for the data loaded: workers ask for their
    // suitabilities several times a tick each. A /reload loads new data, and with it a new cache.
    private volatile Cache cache = new Cache(SuitabilityData.EMPTY);

    @Override
    public Set<Suitability> getSuitabilities(Pokemon pokemon) {
        return levels(pokemon).suitabilities();
    }

    @Override
    public int getSuitabilityLevel(Pokemon pokemon, Suitability suitability) {
        return levels(pokemon).levels().getOrDefault(suitability, 0);
    }

    private Levels levels(Pokemon pokemon) {
        List<ElementalType> types = new ArrayList<>(2);
        for (ElementalType type : pokemon.getTypes()) {
            types.add(type);
        }
        Key key = new Key(pokemon.getForm(), types);

        SuitabilityData data = SuitabilityData.current();
        Cache current = cache;
        if (current.data() != data) {
            current = new Cache(data);
            cache = current;
        }
        return current.levels().computeIfAbsent(key, k -> compute(data, k));
    }

    /** The job levels of a Pokémon of this form, with the form's own types (for commands, which have no Pokémon). */
    public static Map<Suitability, Integer> levels(FormData form) {
        List<ElementalType> types = new ArrayList<>(2);
        for (ElementalType type : form.getTypes()) {
            types.add(type);
        }
        return compute(SuitabilityData.current(), new Key(form, types)).levels();
    }

    private static Levels compute(SuitabilityData data, Key key) {
        String speciesId = key.form().getSpecies().getResourceIdentifier().toString();
        Map<Suitability, Integer> levels = data.levels(speciesId, line(key));
        Set<Suitability> suitabilities = EnumSet.noneOf(Suitability.class);
        suitabilities.addAll(levels.keySet());
        return new Levels(Collections.unmodifiableMap(levels), Collections.unmodifiableSet(suitabilities));
    }

    /** The Pokémon's evolution line: its own form (with its types as they are), then those it evolved from. */
    private static List<SuitabilityData.Form> line(Key key) {
        List<SuitabilityData.Form> line = new ArrayList<>();
        line.add(new SuitabilityData.Form(showdownIds(key.types()), baseStatTotal(key.form())));
        PreEvolution before = key.form().getPreEvolution();
        while (before != null && line.size() < MAX_LINE_LENGTH) {
            FormData form = before.getForm();
            line.add(new SuitabilityData.Form(showdownIds(form.getTypes()), baseStatTotal(form)));
            before = form.getPreEvolution();
        }
        return line;
    }

    private static List<String> showdownIds(Iterable<ElementalType> types) {
        List<String> ids = new ArrayList<>(2);
        for (ElementalType type : types) {
            ids.add(type.getShowdownId());
        }
        return ids;
    }

    private static int baseStatTotal(FormData form) {
        int total = 0;
        for (int stat : form.getBaseStats().values()) {
            total += stat;
        }
        return total;
    }

    /** A form and types' job levels, and the jobs themselves (as an EnumSet, for quick checks). */
    private record Levels(Map<Suitability, Integer> levels, Set<Suitability> suitabilities) {}

    /**
     * What a Pokémon's levels depend on: its form (its species, base stats and the forms it evolved
     * from) and its types (its form's, or changed ones). The form is compared by identity: its own
     * equals builds a string each time, and this is looked up several times a tick per worker. A
     * species reload makes new forms, so their levels are worked out again, from their new stats.
     */
    private record Key(FormData form, List<ElementalType> types) {
        @Override
        @SuppressWarnings("ReferenceEquality") // identity on purpose, see above
        public boolean equals(Object other) {
            return other instanceof Key key && key.form == form && key.types.equals(types);
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(form) + types.hashCode();
        }
    }

    private record Cache(SuitabilityData data, Map<Key, Levels> levels) {
        Cache(SuitabilityData data) {
            this(data, new ConcurrentHashMap<>());
        }
    }
}
