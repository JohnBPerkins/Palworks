package dev.palworks.boulder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * What a kind of boulder is made of: its stone, and the ores in it, each with a weight. Which kind an
 * Unearthing worker raises goes by its types (or its species): see {@link BoulderKinds#kindFor}.
 * Loaded from {@code data/<namespace>/palworks/boulders/<kind>.json}.
 *
 * <p>Each block of a boulder is rolled in two stages: first whether it's ore at all (the
 * {@code oreChance} of the worker's level, see {@link BoulderSize}), then which block, by weight,
 * among only that column: stone against stone, ore against the ores the worker's level has
 * unlocked (see {@link BoulderOreLevels}). So stone 4 and coal 4 at a 10% ore chance is nine stone
 * to one coal: the weights never compete across the two.
 *
 * @param id the kind's name, its file name ("badlands")
 * @param types the Showdown type ids it's for ("ground"), read as the type a worker has besides Rock
 * @param species the species ids it's for, over their types ("cobblemon:nacli")
 * @param stone block id to weight, in order
 * @param ores block id to weight, in order
 */
public record BoulderKind(
        String id, Set<String> types, Set<String> species, Map<String, Integer> stone, Map<String, Integer> ores) {

    public BoulderKind {
        types = Set.copyOf(types);
        species = Set.copyOf(species);
        stone = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(stone));
        ores = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(ores));
    }

    /**
     * The block for one of a boulder's blocks, raised by a worker of this Unearthing level: ore at
     * {@code oreChance} (one of those its level has unlocked, by weight; stone if it has none
     * unlocked yet), else stone, by weight.
     */
    public String pick(int level, double oreChance, BoulderOreLevels oreLevels, RandomGenerator random) {
        if (random.nextDouble() < oreChance) {
            Map<String, Integer> unlocked = new LinkedHashMap<>();
            ores.forEach((ore, weight) -> {
                if (oreLevels.levelOf(ore) <= level) {
                    unlocked.put(ore, weight);
                }
            });
            if (!unlocked.isEmpty()) {
                return weighted(unlocked, random);
            }
        }
        return weighted(stone, random);
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
