package dev.palworks.tree;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds a tree's sapling by name: strip a tree block's id path down to the tree's name
 * ("stripped_dark_oak_log" becomes "dark_oak"), then the sapling is that name plus "_sapling",
 * "_propagule" or "_fungus" in the same namespace. Covers every vanilla tree and most modded ones,
 * since mods name their trees the same way ("somemod:redwood_log", "somemod:redwood_sapling").
 *
 * <p>Pure string logic (no Minecraft types) so it can be unit-tested; {@link TreeDetector} checks
 * which candidate actually exists in the item registry.
 */
public final class SaplingNames {
    private SaplingNames() {}

    private static final String[] PREFIXES = {"stripped_"};
    private static final String[] SUFFIXES = {"_log", "_wood", "_stem", "_hyphae", "_roots", "_leaves"};
    private static final String[] SAPLING_SUFFIXES = {"_sapling", "_propagule", "_fungus"};

    /** The tree's name from a block's id path: "stripped_dark_oak_log" -> "dark_oak". */
    public static String treeName(String blockPath) {
        String name = blockPath;
        for (String prefix : PREFIXES) {
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length());
            }
        }
        for (String suffix : SUFFIXES) {
            if (name.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    /** Sapling id paths to look for, in order: "dark_oak_log" -> dark_oak_sapling, _propagule, _fungus. */
    public static List<String> saplingCandidates(String blockPath) {
        String name = treeName(blockPath);
        List<String> candidates = new ArrayList<>(SAPLING_SUFFIXES.length);
        for (String suffix : SAPLING_SUFFIXES) {
            candidates.add(name + suffix);
        }
        return candidates;
    }
}
