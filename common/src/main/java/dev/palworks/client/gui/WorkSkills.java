package dev.palworks.client.gui;

import dev.palworks.boulder.BoulderSize;
import dev.palworks.crop.Watering;
import dev.palworks.inventory.CarryMode;
import dev.palworks.station.KindlingHeat;
import dev.palworks.suitability.Suitability;
import dev.palworks.tree.TreeSize;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Which of a Pokémon's work skills (its jobs and levels) the PC and summary screens show, in what
 * order, and with what icon and name. Only work Palworks does so far is shown: a Pokémon that can
 * Cool or Generate electricity has nothing to do it on yet. No client-only imports, so it's
 * unit-tested.
 */
public final class WorkSkills {
    /** One work skill to show. */
    public record Shown(Suitability job, int level) {}

    /** What a skill's level gives: a translation key, and the numbers that go in it. */
    public record LevelEffect(String key, List<Object> args) {}

    // The skills whose level changes how a worker works (those levelEffect has a line for); the rest show no level,
    // which would promise a difference there isn't
    private static final Set<Suitability> LEVELLED = EnumSet.of(
            Suitability.KINDLING,
            Suitability.WATERING,
            Suitability.LUMBERING,
            Suitability.TRANSPORTING,
            Suitability.MINING,
            Suitability.UNEARTHING);

    private WorkSkills() {}

    /**
     * The skills to show for these levels: those with work to do, the levelled ones first, highest
     * level first, then the rest in job order.
     */
    public static List<Shown> shown(Map<Suitability, Integer> levels) {
        List<Shown> shown = new ArrayList<>();
        levels.forEach((job, level) -> {
            if (icon(job) != null && level > 0) {
                shown.add(new Shown(job, level));
            }
        });
        shown.sort(Comparator.comparing((Shown skill) -> !isLevelled(skill.job()))
                .thenComparingInt(skill -> isLevelled(skill.job()) ? -skill.level() : 0)
                .thenComparing(Shown::job));
        return shown;
    }

    /** Whether a skill's level changes how a worker works, so it's shown. */
    public static boolean isLevelled(Suitability job) {
        return LEVELLED.contains(job);
    }

    /**
     * The skill after {@code current} as the Pokédex's work skill filter is clicked through: from
     * none (null) to each shown skill in turn, then back to none.
     */
    @Nullable
    public static Suitability nextFilter(@Nullable Suitability current) {
        List<Suitability> all = Arrays.stream(Suitability.values())
                .filter(skill -> icon(skill) != null)
                .toList();
        int next = current == null ? 0 : all.indexOf(current) + 1;
        return next < all.size() ? all.get(next) : null;
    }

    /** A shown job's icon (from {@link WorkIcons}), or null if it isn't shown. */
    @Nullable
    public static WorkIcons.Icon icon(Suitability job) {
        return WorkIcons.forSkill(job);
    }

    /** The translation key of a job's name ("Watering"). */
    public static String nameKey(Suitability job) {
        return "palworks.work_skill." + job.getId();
    }

    /** The translation key of what a shown job does ("Waters growing crops..."). */
    public static String descriptionKey(Suitability job) {
        return nameKey(job) + ".description";
    }

    /**
     * What this level of a skill gives ("Chops trees of up to 32 logs"), or null for a skill whose
     * level changes nothing. Watering's comes from the server's fertilize chance per level.
     */
    @Nullable
    public static LevelEffect levelEffect(Suitability job, int level, double fertilizeChancePerLevel) {
        String key = nameKey(job) + ".level";
        return switch (job) {
            case WATERING ->
                new LevelEffect(key, List.of(percent(Watering.fertilizeChance(level, fertilizeChancePerLevel))));
            // A level 1 kindler's Ember smelts one item, and it stays to light it again
            case KINDLING ->
                KindlingHeat.staysAtItsFurnace(level)
                        ? new LevelEffect(key + ".one", List.of())
                        : new LevelEffect(key, List.of(KindlingHeat.itemsPerEmber(level)));
            case LUMBERING -> new LevelEffect(key, List.of(TreeSize.maxLogs(level)));
            case TRANSPORTING -> new LevelEffect(key, List.of(CarryMode.slotsForLevel(level)));
            // Its pickaxe (see MiningTool), one line a level
            case MINING -> new LevelEffect(key + "." + Math.clamp(level, 1, 5), List.of());
            // How big its boulders are, and how much ore the one in eight with ore holds (see BoulderSize)
            case UNEARTHING -> {
                BoulderSize size = BoulderSize.forLevel(level);
                yield new LevelEffect(key, List.of(size.minBlocks(), size.maxBlocks(), size.minOre(), size.maxOre()));
            }
            default -> null;
        };
    }

    /** A chance (0 to 1) as a percentage to a tenth at most: 0.3 is "30", 0.025 is "2.5". */
    static String percent(double chance) {
        long tenths = Math.round(chance * 1000);
        return tenths % 10 == 0 ? Long.toString(tenths / 10) : (tenths / 10) + "." + (tenths % 10);
    }
}
