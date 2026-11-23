package dev.palworks.boulder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.jetbrains.annotations.Nullable;

/**
 * What a kind of boulder is made of: its stone, laid out in its {@link BoulderPattern}, and the ores
 * that can be in it, each with a weight. Which kind an Unearthing worker raises goes by its types (or
 * its species): see {@link BoulderKinds#kindFor}. Loaded from
 * {@code data/<namespace>/palworks/boulders/<kind>.json}.
 *
 * <p>Only some boulders have ore (see {@link BoulderSize#ORE_BOULDER_CHANCE}). In one that does,
 * each cluster is one ore, picked by weight among those the worker's level has unlocked (see
 * {@link BoulderOreLevels}).
 *
 * @param id the kind's name, its file name ("badlands")
 * @param types the Showdown type ids it's for ("ground"), read as the type a worker has besides Rock
 * @param species the species ids it's for, over their types ("cobblemon:nacli")
 * @param stone block id to weight, in order (which the pattern may go by)
 * @param pattern how the stone is laid out
 * @param ores block id to weight, in order
 */
public record BoulderKind(
        String id,
        Set<String> types,
        Set<String> species,
        Map<String, Integer> stone,
        BoulderPattern pattern,
        Map<String, Integer> ores) {

    public BoulderKind {
        types = Set.copyOf(types);
        species = Set.copyOf(species);
        stone = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(stone));
        ores = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(ores));
    }

    /**
     * An ore for one cluster in a boulder raised by a worker of this Unearthing level: by weight,
     * among those its level has unlocked. Null if it has none unlocked yet.
     */
    @Nullable
    public String pickOre(int level, BoulderOreLevels oreLevels, RandomGenerator random) {
        Map<String, Integer> unlocked = new LinkedHashMap<>();
        ores.forEach((ore, weight) -> {
            if (oreLevels.levelOf(ore) <= level) {
                unlocked.put(ore, weight);
            }
        });
        return unlocked.isEmpty() ? null : weighted(unlocked, random);
    }

    private static String weighted(Map<String, Integer> weights, RandomGenerator random) {
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        int roll = random.nextInt(total);
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            roll -= entry.getValue();
            if (roll < 0) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("weights changed while picking"); // can't: they're unmodifiable
    }
}
