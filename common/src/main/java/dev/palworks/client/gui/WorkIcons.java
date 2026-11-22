package dev.palworks.client.gui;

import dev.palworks.Palworks;
import dev.palworks.job.JobType;
import dev.palworks.suitability.Suitability;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * The picture each work skill and each job shows, all in one place. Pictures are
 * {@code textures/gui/icon/<name>.png} (16x16), named for what they show, and any number of skills
 * and jobs can share one: giving a job its own is a new file, a constant, and a one-line change in
 * {@link #forJob}. Skills show on the PC, summary and Pokédex filter; jobs on the party HUD and the
 * debug overlay. No client-only imports, so it's unit-tested.
 */
public final class WorkIcons {
    /** Work switched off. */
    public static final ResourceLocation OFF = picture("cross");

    /** No job, and the Pokédex filter's "any skill". */
    public static final ResourceLocation NO_JOB = picture("cog");

    private static final ResourceLocation APPLE = picture("apple");
    private static final ResourceLocation AXE = picture("axe");
    private static final ResourceLocation CHEST = picture("chest");
    private static final ResourceLocation FLAME = picture("flame");
    private static final ResourceLocation MOON = picture("moon");
    private static final ResourceLocation NETHER_WART = picture("nether_wart");
    private static final ResourceLocation PICK_UP = picture("pick_up");
    private static final ResourceLocation PICKAXE = picture("pickaxe");
    private static final ResourceLocation SEEDS = picture("seeds");
    private static final ResourceLocation SPROUT = picture("sprout");
    private static final ResourceLocation TROUGH = picture("trough");
    private static final ResourceLocation WATER_DROP = picture("water_drop");
    private static final ResourceLocation WHEAT = picture("wheat");

    private WorkIcons() {}

    /** A skill's picture, or null for a skill with no work yet, which isn't shown anywhere. */
    @Nullable
    public static ResourceLocation forSkill(Suitability skill) {
        return switch (skill) {
            case KINDLING -> FLAME;
            case WATERING -> WATER_DROP;
            case PLANTING -> SPROUT;
            case GATHERING -> WHEAT;
            case LUMBERING -> AXE;
            case MINING -> PICKAXE;
            case TRANSPORTING -> CHEST;
            case NETHER -> NETHER_WART;
            case GENERATING_ELECTRICITY, HANDIWORK, MEDICINE_PRODUCTION, COOLING, FARMING -> null;
        };
    }

    /** A job's picture; {@link #NO_JOB} for none. */
    public static ResourceLocation forJob(@Nullable JobType job) {
        if (job == null) {
            return NO_JOB;
        }
        return switch (job) {
            case FUEL_FURNACE -> FLAME;
            case PLANT_SEEDS -> SPROUT;
            case TAKE_SEEDS -> SPROUT;
            case HARVEST_CROPS -> WHEAT;
            case HARVEST_SUGAR_CANE -> WHEAT;
            case HARVEST_CACTUS -> WHEAT;
            case HARVEST_BAMBOO -> WHEAT;
            case HARVEST_KELP -> WHEAT;
            case HARVEST_NETHER_VINES -> WHEAT;
            case WATER_CROPS -> WATER_DROP;
            case CHOP_TREE -> AXE;
            case MINE_CRYSTAL -> PICKAXE;
            case MINE_RESOURCE -> PICKAXE;
            case COLLECT_DROPS -> PICK_UP;
            case DELIVER_ITEMS -> CHEST;
            case FETCH_FOOD -> TROUGH;
            case GET_SEEDS -> SEEDS;
            case BRING_SEEDS -> SEEDS;
            case EAT_FOOD -> APPLE;
            case SLEEP -> MOON;
        };
    }

    private static ResourceLocation picture(String name) {
        return Palworks.id("textures/gui/icon/" + name + ".png");
    }
}
