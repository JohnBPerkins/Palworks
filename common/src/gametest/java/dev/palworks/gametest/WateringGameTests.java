package dev.palworks.gametest;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.config.PalworksConfig;
import dev.palworks.crop.Watering;
import dev.palworks.job.JobChecks;
import dev.palworks.job.JobType;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityDetector;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;

/**
 * Watering: which crops want it, what it does (hydrates the farmland, may fertilize the crop as
 * bone meal would), the cooldown between waterings, real Pokémon's work levels, and Water types
 * doing it. Tests that change {@code config/palworks.json}'s settings have their own batch, as
 * every test in a batch shares them.
 */
public class WateringGameTests {
    private static final CropBlock WHEAT = (CropBlock) Blocks.WHEAT;
    private static final BlockPos CROP = new BlockPos(3, 1, 3);

    private static void dryWheat(GameTestHelper helper, BlockPos crop, int age) {
        helper.setBlock(crop.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 0));
        helper.setBlock(crop, WHEAT.getStateForAge(age));
    }

    private static boolean wantsWater(GameTestHelper helper, BlockPos crop) {
        return JobChecks.isJobStillThere(helper.getLevel(), JobType.WATER_CROPS, helper.absolutePos(crop));
    }

    private static int moisture(GameTestHelper helper, BlockPos soil) {
        return helper.getBlockState(soil).getValue(FarmBlock.MOISTURE);
    }

    private static int wheatAge(GameTestHelper helper, BlockPos crop) {
        return WHEAT.getAge(helper.getBlockState(crop));
    }

    // ---- Settings, per batch: set before its tests, and put back after them ----

    private static final String COOLDOWN_BATCH = "watering_cooldown";
    private static final String FERTILIZE_BATCH = "worker_fertilize";
    private static PalworksConfig before = PalworksConfig.DEFAULTS;

    private static void use(PalworksConfig config) {
        before = PalworksConfig.current();
        PalworksConfig.setCurrent(config);
    }

    @BeforeBatch(batch = COOLDOWN_BATCH)
    public void shortCooldown(ServerLevel level) {
        use(config(0.0, 5));
    }

    @AfterBatch(batch = COOLDOWN_BATCH)
    public void afterShortCooldown(ServerLevel level) {
        PalworksConfig.setCurrent(before);
    }

    @BeforeBatch(batch = FERTILIZE_BATCH)
    public void alwaysFertilize(ServerLevel level) {
        // More than a third a level, so level 3 always fertilizes (past the config file's 0.2 most,
        // which this skips): each watering grows wheat 2 to 5 stages, so it's ripe within 4. By
        // chance (60%, the most the file allows), too few of the waterings in the time could
        // fertilize. With a crop watered again every 5s
        use(config(0.34, 5));
    }

    @AfterBatch(batch = FERTILIZE_BATCH)
    public void afterOftenFertilize(ServerLevel level) {
        PalworksConfig.setCurrent(before);
    }

    private static PalworksConfig config(double fertilizeChancePerLevel, int cooldownSeconds) {
        PalworksConfig d = PalworksConfig.DEFAULTS;
        return new PalworksConfig(
                d.workRangeHorizontal(),
                d.workRangeVertical(),
                d.ownerRange(),
                d.pickupRadius(),
                fertilizeChancePerLevel,
                cooldownSeconds);
    }

    // ---- Which crops want watering ----

    @GameTest(template = Templates.EMPTY)
    public void growingCropsWantWaterRipeOnesDont(GameTestHelper helper) {
        dryWheat(helper, CROP, 3);
        helper.assertTrue(wantsWater(helper, CROP), "growing wheat");

        helper.setBlock(CROP, WHEAT.getStateForAge(7));
        helper.assertFalse(wantsWater(helper, CROP), "ripe wheat is harvested, not watered");

        helper.setBlock(CROP, Blocks.MELON_STEM.defaultBlockState().setValue(StemBlock.AGE, 2));
        helper.assertTrue(wantsWater(helper, CROP), "a growing melon stem");

        helper.setBlock(CROP.below(), Blocks.SOUL_SAND);
        helper.setBlock(CROP, Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, 1));
        helper.assertFalse(wantsWater(helper, CROP), "nether wart: no watering in the Nether");

        helper.setBlock(CROP.below(), Blocks.GRASS_BLOCK);
        helper.setBlock(CROP, Blocks.OAK_SAPLING);
        helper.assertFalse(wantsWater(helper, CROP), "a sapling isn't a crop");
        helper.succeed();
    }

    // ---- What watering does ----

    @GameTest(template = Templates.EMPTY)
    public void wateringHydratesTheFarmland(GameTestHelper helper) {
        dryWheat(helper, CROP, 0);
        boolean fertilized = Watering.water(helper.getLevel(), helper.absolutePos(CROP), 0.0);

        helper.assertFalse(fertilized, "no chance, no fertilizing");
        helper.assertTrue(moisture(helper, CROP.below()) == FarmBlock.MAX_MOISTURE, "the farmland is wet");
        helper.assertTrue(wheatAge(helper, CROP) == 0, "the wheat hasn't grown");
        helper.assertFalse(wantsWater(helper, CROP), "it isn't watered again straight away");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void fertilizingGrowsTheCropAsBoneMealWould(GameTestHelper helper) {
        dryWheat(helper, CROP, 0);
        boolean fertilized = Watering.water(helper.getLevel(), helper.absolutePos(CROP), 1.0);

        helper.assertTrue(fertilized, "a certain chance fertilizes it");
        // Bone meal grows wheat 2 to 5 stages
        int age = wheatAge(helper, CROP);
        helper.assertTrue(age >= 2 && age <= 5, "bone meal grows wheat 2 to 5 stages, but it's at " + age);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void aRipeCropIsntFertilized(GameTestHelper helper) {
        dryWheat(helper, CROP, 7);
        helper.assertFalse(
                Watering.fertilize(helper.getLevel(), helper.absolutePos(CROP)),
                "bone meal does nothing to ripe wheat");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = COOLDOWN_BATCH, timeoutTicks = 300)
    public void aWateredCropWantsWaterAgainAfterTheCooldown(GameTestHelper helper) {
        dryWheat(helper, CROP, 0);
        Watering.water(helper.getLevel(), helper.absolutePos(CROP), 0.0);
        helper.assertFalse(wantsWater(helper, CROP), "just watered");

        helper.runAfterDelay(80, () -> helper.assertFalse(wantsWater(helper, CROP), "4 of its 5 seconds in"));
        helper.runAfterDelay(110, () -> {
            helper.assertTrue(wantsWater(helper, CROP), "its 5 seconds are up");
            helper.succeed();
        });
    }

    @GameTest(template = Templates.EMPTY)
    public void aBrokenOrReplacedCropForgetsItWasWatered(GameTestHelper helper) {
        dryWheat(helper, CROP, 0);
        Watering.water(helper.getLevel(), helper.absolutePos(CROP), 0.0);
        helper.setBlock(CROP, WHEAT.getStateForAge(1));
        helper.assertFalse(wantsWater(helper, CROP), "growing a stage keeps its cooldown");

        helper.setBlock(CROP, Blocks.AIR);
        helper.setBlock(CROP, WHEAT.getStateForAge(0));
        helper.assertTrue(wantsWater(helper, CROP), "wheat broken and replanted wants water");

        Watering.water(helper.getLevel(), helper.absolutePos(CROP), 0.0);
        helper.setBlock(CROP, Blocks.CARROTS.defaultBlockState());
        helper.assertTrue(wantsWater(helper, CROP), "carrots in the watered wheat's place want water");
        helper.succeed();
    }

    // ---- Work levels of real Pokémon ----

    private static int watering(String properties) {
        return level(properties, Suitability.WATERING);
    }

    private static int level(String properties, Suitability suitability) {
        Pokemon pokemon = PokemonProperties.Companion.parse(properties).create();
        return SuitabilityDetector.getSuitabilityLevel(pokemon, suitability);
    }

    private static void assertLevel(GameTestHelper helper, String pokemon, int expected, int actual) {
        helper.assertTrue(actual == expected, pokemon + " should be " + expected + ", but is " + actual);
    }

    @GameTest(template = Templates.EMPTY)
    public void workLevelsComeFromBaseStatsAndTheEvolutionLine(GameTestHelper helper) {
        assertLevel(helper, "Squirtle's Watering", 1, watering("squirtle"));
        assertLevel(helper, "Wartortle's Watering", 2, watering("wartortle"));
        assertLevel(helper, "Blastoise's Watering", 3, watering("blastoise"));
        assertLevel(helper, "Vaporeon's Watering (Eevee was Normal)", 2, watering("vaporeon"));
        assertLevel(helper, "Gyarados' Watering (Magikarp was Water)", 3, watering("gyarados"));
        assertLevel(helper, "Lapras' Watering", 3, watering("lapras"));
        assertLevel(helper, "Kyogre's Watering", 5, watering("kyogre"));
        assertLevel(helper, "Charmander's Watering", 0, watering("charmander"));

        Pokemon typhlosion =
                PokemonProperties.Companion.parse("typhlosion hisuian").create();
        List<String> types = new ArrayList<>();
        for (ElementalType type : typhlosion.getTypes()) {
            types.add(type.getShowdownId());
        }
        helper.assertTrue(types.contains("ghost"), "should be Hisuian Typhlosion (Fire/Ghost), but it's " + types);
        assertLevel(
                helper,
                "Hisuian Typhlosion's Kindling",
                3,
                SuitabilityDetector.getSuitabilityLevel(typhlosion, Suitability.KINDLING));
        assertLevel(
                helper,
                "Hisuian Typhlosion's Nether work (Cyndaquil and Quilava weren't Ghost)",
                1,
                SuitabilityDetector.getSuitabilityLevel(typhlosion, Suitability.NETHER));
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void speciesEntriesFollowBaseStats(GameTestHelper helper) {
        assertLevel(helper, "Kingambit's Lumbering (550)", 4, level("kingambit", Suitability.LUMBERING));
        assertLevel(helper, "Pawniard's Lumbering (340)", 1, level("pawniard", Suitability.LUMBERING));
        assertLevel(helper, "Lucario's Handiwork (525)", 3, level("lucario", Suitability.HANDIWORK));
        assertLevel(helper, "Magnemite's Lumbering (Steel isn't)", 0, level("magnemite", Suitability.LUMBERING));
        helper.succeed();
    }

    // ---- Water types at work ----

    @GameTest(template = Templates.EMPTY, batch = "worker_water", timeoutTicks = 600)
    public void aWaterTypeWatersDryFarmland(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        dryWheat(helper, CROP, 0);
        Workers.sendOut(helper, "squirtle level=20", new BlockPos(1, 1, 1));

        helper.succeedWhen(() -> helper.assertTrue(
                moisture(helper, CROP.below()) == FarmBlock.MAX_MOISTURE,
                "the farmland should be watered, but its moisture is " + moisture(helper, CROP.below())));
    }

    @GameTest(template = Templates.EMPTY, batch = FERTILIZE_BATCH, timeoutTicks = 1200)
    public void aStrongWaterTypeFertilizesItsCropsAsItWatersThem(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        dryWheat(helper, CROP, 0);
        PokemonEntity blastoise = Workers.sendOut(helper, "blastoise level=50", new BlockPos(1, 1, 1));
        int[] waterings = {0};
        helper.onEachTick(() -> {
            WorkerState state = WorkerStateManager.get(blastoise.getPokemon());
            if (state != null
                    && state.getCurrentReservation() != null
                    && state.getCurrentReservation().getJobType() == JobType.WATER_CROPS) {
                waterings[0]++;
            }
        });

        helper.succeedWhen(() -> helper.assertTrue(
                WHEAT.isMaxAge(helper.getBlockState(CROP)),
                "the wheat should be grown by now, but it's at age " + wheatAge(helper, CROP)
                        + " (ticks spent watering: " + waterings[0] + ")"));
    }
}
