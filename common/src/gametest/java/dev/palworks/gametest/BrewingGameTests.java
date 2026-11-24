package dev.palworks.gametest;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.job.JobChecks;
import dev.palworks.job.JobType;
import dev.palworks.reservation.ReservationId;
import dev.palworks.station.BrewingStandHelper;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityOverrides;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import java.io.IOException;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;

/** Kindlers lighting brewing stands in place of blaze powder (Kindling). */
public class BrewingGameTests {
    private static final BlockPos START = new BlockPos(3, 1, 1);
    // Halfway through a brew (they take 400 ticks)
    private static final int MID_BREW = 200;

    private static boolean there(GameTestHelper helper, BlockPos rel) {
        return JobChecks.isJobStillThere(helper.getLevel(), JobType.FUEL_BREWING_STAND, helper.absolutePos(rel));
    }

    private static boolean found(GameTestHelper helper, BlockPos rel) {
        return BrewingStandHelper.needsCharge(helper.getLevel(), helper.absolutePos(rel));
    }

    private static BrewingStandBlockEntity stand(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, Blocks.BREWING_STAND);
        return (BrewingStandBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
    }

    private static ItemStack waterBottle() {
        return PotionContents.createItemStack(Items.POTION, Potions.WATER);
    }

    // Cobblemon items by id: its item fields differ across versions (see CrystalGameTests)
    private static Item cobblemon(String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("cobblemon", id));
    }

    private static ItemStack leek() {
        return new ItemStack(cobblemon("medicinal_leek"));
    }

    @GameTest(template = Templates.EMPTY)
    public void standsAreWorkWhileTheyCanBrewAndHaveNoChargeOfTheirOwn(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3, 1, 3);
        BrewingStandBlockEntity stand = stand(helper, pos);

        helper.assertFalse(found(helper, pos), "empty: nothing to brew");
        stand.setItem(0, waterBottle());
        helper.assertFalse(found(helper, pos), "a bottle and no ingredient");
        stand.setItem(3, new ItemStack(Items.NETHER_WART));
        helper.assertTrue(found(helper, pos), "water and nether wart: an awkward potion");
        stand.setItem(4, new ItemStack(Items.BLAZE_POWDER));
        helper.assertFalse(found(helper, pos), "it has blaze powder of its own");
        stand.setItem(4, ItemStack.EMPTY);
        stand.fuel = 3;
        helper.assertFalse(found(helper, pos), "it has charges left");
        stand.fuel = 0;
        stand.setItem(3, new ItemStack(Items.DIAMOND));
        helper.assertFalse(found(helper, pos), "diamonds don't brew");
        // Cobblemon's recipes, which vanilla's check doesn't see: a leek isn't a vanilla ingredient
        stand.setItem(3, leek());
        helper.assertTrue(found(helper, pos), "water and a medicinal leek: Cobblemon's medicinal brew");
        stand.setItem(0, new ItemStack(cobblemon("medicinal_brew")));
        helper.assertFalse(found(helper, pos), "a medicinal brew and another leek make nothing");
        stand.setItem(3, new ItemStack(cobblemon("oran_berry")));
        helper.assertTrue(found(helper, pos), "a medicinal brew and an Oran Berry: a potion");
        stand.brewTime = MID_BREW;
        helper.assertFalse(found(helper, pos), "brewing already");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void aStandAWorkerLitIsStillItsJobUntilItsEmptied(GameTestHelper helper) {
        // A level 1 kindler waits out the brew it lit, checking the job is still there as it goes:
        // the stand brewing on its charge mustn't count as the job having gone, but being emptied must
        BlockPos pos = new BlockPos(3, 1, 3);
        BrewingStandBlockEntity stand = stand(helper, pos);
        stand.setItem(0, waterBottle());
        stand.setItem(3, leek());
        stand.brewTime = MID_BREW; // brewing on the charge it was given, used up as the brew began

        helper.assertTrue(there(helper, pos), "lit by a worker, brewing");
        stand.setItem(3, ItemStack.EMPTY);
        helper.assertFalse(there(helper, pos), "emptied: nothing left to brew");
        stand.setItem(3, leek());
        stand.setItem(4, new ItemStack(Items.BLAZE_POWDER));
        helper.assertFalse(there(helper, pos), "given blaze powder: no need for a worker");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_brewing_stand", timeoutTicks = 1200)
    public void aLevelOneKindlerBrewsCobblemonMedicineAndStaysTillItsDone(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.STONE);
        BlockPos pos = new BlockPos(3, 1, 6);
        BrewingStandBlockEntity stand = stand(helper, pos);
        for (int slot = 0; slot < 3; slot++) {
            stand.setItem(slot, waterBottle());
        }
        stand.setItem(3, leek());
        PokemonEntity worker = Workers.sendOut(helper, "charmander level=20", START); // Kindling 1

        boolean[] stayedMidBrew = {false};
        helper.onEachTick(() -> {
            if (stand.brewTime == MID_BREW) {
                WorkerState state = WorkerStateManager.get(worker.getPokemon());
                ReservationId job = state == null ? null : state.getCurrentReservation();
                stayedMidBrew[0] = job != null && job.getJobType() == JobType.FUEL_BREWING_STAND;
            }
        });
        Item brew = cobblemon("medicinal_brew");
        helper.succeedWhen(() -> {
            for (int slot = 0; slot < 3; slot++) {
                helper.assertTrue(stand.getItem(slot).is(brew), "bottle " + slot + " brewed");
            }
            helper.assertTrue(stand.getItem(3).isEmpty(), "the leek used");
            helper.assertValueEqual(stand.fuel, 0, "charges left: level 1's Ember is one brew");
            helper.assertTrue(stayedMidBrew[0], "it stayed at its stand while it brewed");
        });
    }

    @BeforeBatch(batch = "brewing_kindler_level_three")
    public void beforeKindlerLevelThree(ServerLevel level) throws IOException {
        SuitabilityOverrides.set("cobblemon:charmander", Suitability.KINDLING, 3);
    }

    @AfterBatch(batch = "brewing_kindler_level_three")
    public void afterKindlerLevelThree(ServerLevel level) throws IOException {
        SuitabilityOverrides.resetAll();
    }

    @GameTest(template = Templates.EMPTY, batch = "brewing_kindler_level_three", timeoutTicks = 1200)
    public void aKindlerAboveLevelOneLightsAStandAndGoesOnToTheNext(GameTestHelper helper) {
        // Kindling 3: an Ember gives 4 brews, so it lights each and moves on, and both brew at once.
        // A level 1 kindler stays at its stand until the brew is done, so never has two going.
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.STONE);
        List<BlockPos> stands = List.of(new BlockPos(2, 1, 6), new BlockPos(6, 1, 6));
        for (BlockPos pos : stands) {
            BrewingStandBlockEntity stand = stand(helper, pos);
            stand.setItem(0, waterBottle());
            stand.setItem(3, new ItemStack(Items.NETHER_WART)); // vanilla's awkward potion
        }
        Workers.sendOut(helper, "charmander level=20", START);

        boolean[] bothBrewing = {false};
        helper.onEachTick(() -> {
            if (stands.stream().allMatch(pos -> standAt(helper, pos).brewTime > 0)) {
                bothBrewing[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            for (BlockPos pos : stands) {
                BrewingStandBlockEntity stand = standAt(helper, pos);
                PotionContents brewed =
                        stand.getItem(0).getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
                helper.assertTrue(brewed.is(Potions.AWKWARD), "awkward potion brewed at " + pos);
                helper.assertValueEqual(stand.fuel, 3, "charges left at " + pos + " (4, one used)");
            }
            helper.assertTrue(bothBrewing[0], "both stands brewed at once");
        });
    }

    private static BrewingStandBlockEntity standAt(GameTestHelper helper, BlockPos rel) {
        return (BrewingStandBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
    }
}
