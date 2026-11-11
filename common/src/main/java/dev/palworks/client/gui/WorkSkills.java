package dev.palworks.client.gui;

import dev.palworks.Palworks;
import dev.palworks.suitability.Suitability;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
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

    /** How many rows the summary's work box has (see WorkSkillsOverlay). */
    public static final int BOX_ROWS = 2;

    // The icon each shown job has: the party HUD's for its main job, or its own
    private static final Map<Suitability, ResourceLocation> ICONS = new EnumMap<>(Suitability.class);

    static {
        ICONS.put(Suitability.KINDLING, hudIcon("fuel_furnace"));
        ICONS.put(Suitability.WATERING, hudIcon("water_crops"));
        ICONS.put(Suitability.PLANTING, hudIcon("plant_seeds"));
        ICONS.put(Suitability.GATHERING, hudIcon("harvest_crops"));
        ICONS.put(Suitability.LUMBERING, hudIcon("chop_tree"));
        ICONS.put(Suitability.TRANSPORTING, hudIcon("deliver_items"));
        ICONS.put(Suitability.NETHER, Palworks.id("textures/gui/work_skill/nether.png"));
    }

    private WorkSkills() {}

    private static ResourceLocation hudIcon(String job) {
        return Palworks.id("textures/gui/hud/work_" + job + ".png");
    }

    /** The skills to show for these levels: those with work to do, highest level first. */
    public static List<Shown> shown(Map<Suitability, Integer> levels) {
        List<Shown> shown = new ArrayList<>();
        levels.forEach((job, level) -> {
            if (ICONS.containsKey(job) && level > 0) {
                shown.add(new Shown(job, level));
            }
        });
        shown.sort(Comparator.comparingInt(Shown::level).reversed().thenComparing(Shown::job));
        return shown;
    }

    /** A shown job's icon (16x16), or null if it isn't shown. */
    @Nullable
    public static ResourceLocation icon(Suitability job) {
        return ICONS.get(job);
    }

    /** The translation key of a job's name ("Watering"). */
    public static String nameKey(Suitability job) {
        return "palworks.work_skill." + job.getId();
    }

    /**
     * How many of {@code count} skills the summary's box names, one a row: all of them if they fit,
     * otherwise all but the last row, which says how many more there are.
     */
    public static int namedInBox(int count) {
        return count <= BOX_ROWS ? count : BOX_ROWS - 1;
    }
}
