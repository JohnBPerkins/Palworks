package dev.palworks.client.gui;

import dev.palworks.Palworks;
import dev.palworks.job.JobType;
import dev.palworks.suitability.Suitability;
import dev.palworks.worker.WorkerStatus;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * The icon each work skill, each job and each status shows, all in one place: an item, drawn as in
 * an inventory (the game's own, or ours, so it follows resource packs), one of Palworks' pictures,
 * {@code textures/gui/icon/<name>.png} (16x16, named for what it shows), where no item fits, or a
 * sprite from the game's GUI atlas (over heads, 9x9 like vanilla's hunger shank). Any number can share one: giving a job its own
 * is a constant and a one-line change in {@link #forJob}. Skills show on the PC, summary and
 * Pokédex filter; jobs on the party HUD and the debug overlay; statuses over a Pokémon's head. No
 * client-only imports, so it's unit-tested.
 */
public final class WorkIcons {
    /** A work icon. */
    public sealed interface Icon {}

    /** One of Palworks' pictures. */
    public record Picture(ResourceLocation texture) implements Icon {}

    /**
     * A sprite from the game's GUI atlas ({@code textures/gui/sprites/}), drawn over another one, or
     * over nothing.
     */
    public record GuiSprite(
            ResourceLocation sprite, @Nullable ResourceLocation under) implements Icon {}

    /** An item, by its id. */
    public record OfItem(ResourceLocation item) implements Icon {
        /** The item to draw (air, which draws nothing, if no such item is registered). */
        public ItemStack stack() {
            return new ItemStack(BuiltInRegistries.ITEM.get(item));
        }
    }

    /** Work switched off. */
    public static final Picture OFF = picture("cross");

    /** The Pokédex filter while it filters nothing. */
    public static final Picture ANY_SKILL = picture("cog");

    private static final Icon APPLE = item("apple");
    private static final Icon BAMBOO = item("bamboo");
    private static final Icon BARRIER = item("barrier");
    private static final Icon CACTUS = item("cactus");
    private static final Icon CHEST = item("chest");
    private static final Icon FIRE_CHARGE = item("fire_charge");
    private static final Icon IRON_AXE = item("iron_axe");
    private static final Icon IRON_PICKAXE = item("iron_pickaxe");
    private static final Icon KELP = item("kelp");
    private static final Icon NETHER_WART = item("nether_wart");
    private static final Icon OAK_SAPLING = item("oak_sapling");
    private static final Icon OAK_TROUGH = new OfItem(Palworks.id("oak_trough"));
    private static final Icon SUGAR_CANE = item("sugar_cane");
    private static final Icon WEEPING_VINES = item("weeping_vines");
    private static final Icon WHEAT = item("wheat");
    private static final Icon WHEAT_SEEDS = item("wheat_seeds");

    private static final Icon MOON = picture("moon");
    private static final Icon PICK_UP = picture("pick_up");
    private static final Icon WATER_DROP = picture("water_drop");

    // A half hunger shank over the empty outline, as on the hunger bar
    private static final Icon HALF_SHANK = new GuiSprite(
            ResourceLocation.withDefaultNamespace("hud/food_half"),
            ResourceLocation.withDefaultNamespace("hud/food_empty"));
    // Over heads, 9x9 as vanilla's HUD sprites (the shank) are: the mood faces and the moon again,
    // small, so their pixels are the shank's size
    private static final Icon SLEEPY_Z = statusSprite("tired");
    private static final Icon SMALL_UNHAPPY_FACE = statusSprite("unhappy");
    private static final Icon SMALL_STRIKING_FACE = statusSprite("striking");
    private static final Icon SMALL_MOON = statusSprite("asleep");

    private WorkIcons() {}

    /** A skill's icon, or null for a skill with no work yet, which isn't shown anywhere. */
    @Nullable
    public static Icon forSkill(Suitability skill) {
        return switch (skill) {
            case KINDLING -> FIRE_CHARGE;
            case WATERING -> WATER_DROP;
            case PLANTING -> OAK_SAPLING;
            case GATHERING -> WHEAT;
            case LUMBERING -> IRON_AXE;
            case MINING -> IRON_PICKAXE;
            case TRANSPORTING -> CHEST;
            case NETHER -> NETHER_WART;
            case STONESHAPING, GENERATING_ELECTRICITY, HANDIWORK, MEDICINE_PRODUCTION, COOLING, FARMING -> null;
        };
    }

    /** A job's icon. */
    public static Icon forJob(JobType job) {
        return switch (job) {
            case FUEL_FURNACE -> FIRE_CHARGE;
            case PLANT_SEEDS -> OAK_SAPLING;
            case TAKE_SEEDS -> OAK_SAPLING;
            case HARVEST_CROPS -> WHEAT;
            case HARVEST_SUGAR_CANE -> SUGAR_CANE;
            case HARVEST_CACTUS -> CACTUS;
            case HARVEST_BAMBOO -> BAMBOO;
            case HARVEST_KELP -> KELP;
            case HARVEST_NETHER_VINES -> WEEPING_VINES;
            case WATER_CROPS -> WATER_DROP;
            case CHOP_TREE -> IRON_AXE;
            case MINE_CRYSTAL -> IRON_PICKAXE;
            case MINE_BOULDER -> IRON_PICKAXE;
            case COLLECT_DROPS -> PICK_UP;
            case DELIVER_ITEMS -> CHEST;
            case FETCH_FOOD -> OAK_TROUGH;
            case GET_SEEDS -> WHEAT_SEEDS;
            case BRING_SEEDS -> WHEAT_SEEDS;
            case EAT_FOOD -> APPLE;
            case SLEEP -> MOON;
        };
    }

    /** What a Pokémon shows over its head for a status. */
    public static Icon forStatus(WorkerStatus status) {
        return switch (status) {
            case HUNGRY -> HALF_SHANK;
            case TIRED -> SLEEPY_Z;
            case UNHAPPY -> SMALL_UNHAPPY_FACE;
            case STRIKING -> SMALL_STRIKING_FACE;
            case CANT_REACH -> BARRIER;
            case CANT_DELIVER -> CHEST;
            case ASLEEP -> SMALL_MOON;
        };
    }

    private static Picture picture(String name) {
        return new Picture(Palworks.id("textures/gui/icon/" + name + ".png"));
    }

    private static GuiSprite statusSprite(String name) {
        return new GuiSprite(Palworks.id("status/" + name), null);
    }

    private static Icon item(String minecraftItem) {
        return new OfItem(ResourceLocation.withDefaultNamespace(minecraftItem));
    }
}
