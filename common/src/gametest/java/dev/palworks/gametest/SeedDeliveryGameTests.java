package dev.palworks.gametest;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.drops.TrackedDrops;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * Seeds for planters (see SeedRequests): a planter can't take seeds out of a chest, so one that's
 * run out asks, and a transporter brings it some and drops them at its feet; seeds a player throws
 * by a planter are its too. A worker that transports and plants still fetches its own.
 */
public class SeedDeliveryGameTests {
    private static final String BULBASAUR = "bulbasaur level=20"; // Grass: Planting, Gathering
    private static final String PIDGEY = "pidgey level=20"; // Flying: Transporting
    private static final CropBlock WHEAT = (CropBlock) Blocks.WHEAT;
    private static final BlockPos PLANTER = new BlockPos(3, 1, 1);
    private static final BlockPos TRANSPORTER = new BlockPos(1, 1, 1);
    private static final BlockPos CHEST = new BlockPos(6, 1, 2);

    /** Empty farmland along z=5, and a chest holding these. */
    private static ChestBlockEntity farmlandAndChest(GameTestHelper helper, ItemStack... contents) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        for (int x = 2; x <= 4; x++) {
            helper.setBlock(x, 0, 5, Blocks.FARMLAND);
        }
        helper.setBlock(CHEST, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
        for (int i = 0; i < contents.length; i++) {
            chest.setItem(i, contents[i]);
        }
        return chest;
    }

    private static void assertPlanted(GameTestHelper helper) {
        for (int x = 2; x <= 4; x++) {
            helper.assertTrue(
                    helper.getBlockState(new BlockPos(x, 1, 5)).equals(WHEAT.getStateForAge(0)),
                    "wheat should be planted at x=" + x);
        }
    }

    private static int count(WorkerInventory inventory, Item item) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(item)) {
                total += inventory.getItem(i).getCount();
            }
        }
        return total;
    }

    private static int inChest(ChestBlockEntity chest, Item item) {
        int total = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            if (chest.getItem(i).is(item)) {
                total += chest.getItem(i).getCount();
            }
        }
        return total;
    }

    /** Whether seeds have been left on the ground for a planter (see TrackedDrops.handOverTo). */
    private static boolean seedsLeftForAPlanter(GameTestHelper helper) {
        for (ItemEntity item : helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, helper.getBounds().inflate(4))) {
            if (TrackedDrops.handedTo(item) != null) {
                return true;
            }
        }
        return false;
    }

    @GameTest(template = Templates.EMPTY, batch = "seeds_planter_alone", timeoutTicks = 400)
    public void aPlanterDoesntTakeSeedsOutOfAChestItself(GameTestHelper helper) {
        ChestBlockEntity chest = farmlandAndChest(helper, new ItemStack(Items.WHEAT_SEEDS, 8));
        PokemonEntity planter = Workers.sendOut(helper, BULBASAUR, PLANTER);

        helper.runAfterDelay(300, () -> {
            helper.assertValueEqual(inChest(chest, Items.WHEAT_SEEDS), 8, "seeds left in the chest");
            helper.assertValueEqual(
                    WorkerInventoryManager.getOrCreate(planter.getPokemon()).getTotalItemCount(),
                    0,
                    "seeds in its pouch");
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(3, 1, 5));
            helper.succeed();
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "seeds_own", timeoutTicks = 1000)
    public void aTransporterThatPlantsFetchesItsOwnSeeds(GameTestHelper helper) {
        farmlandAndChest(helper, new ItemStack(Items.WHEAT_SEEDS, 8));
        Workers.sendOut(helper, "hoppip level=20", PLANTER); // Grass/Flying: Planting and Transporting

        helper.succeedWhen(() -> assertPlanted(helper));
    }

    @GameTest(template = Templates.EMPTY, batch = "seeds_keep_working", timeoutTicks = 2000)
    public void aPlanterKeepsWorkingWhileItsSeedsAreBrought(GameTestHelper helper) {
        farmlandAndChest(helper, new ItemStack(Items.WHEAT_SEEDS, 8));
        // Ripe wheat along z=2 for it to harvest (walking up and down the row) while it waits
        for (int x = 1; x <= 5; x++) {
            helper.setBlock(x, 0, 2, Blocks.FARMLAND);
            helper.setBlock(x, 1, 2, WHEAT.getStateForAge(7));
        }
        Workers.sendOut(helper, BULBASAUR, PLANTER);
        Workers.sendOut(helper, PIDGEY, TRANSPORTER);
        boolean[] handedOver = {false};
        helper.onEachTick(() -> {
            if (!handedOver[0] && seedsLeftForAPlanter(helper)) {
                handedOver[0] = true;
            }
        });

        helper.succeedWhen(() -> {
            for (int x = 1; x <= 5; x++) {
                helper.assertTrue(
                        helper.getBlockState(new BlockPos(x, 1, 2)).equals(WHEAT.getStateForAge(0)),
                        "wheat at x=" + x + " should be harvested");
            }
            assertPlanted(helper);
            // (Not "fewer seeds in the chest": the harvest's seeds are put in it too)
            helper.assertTrue(handedOver[0], "seeds were brought to the planter");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "seeds_recalled", timeoutTicks = 2600)
    public void seedsLeftForAPlanterThatsPutAwayGoBackInAChest(GameTestHelper helper) {
        ChestBlockEntity chest = farmlandAndChest(helper, new ItemStack(Items.WHEAT_SEEDS, 8));
        PokemonEntity planter = Workers.sendOut(helper, BULBASAUR, PLANTER);
        Workers.sendOut(helper, PIDGEY, TRANSPORTER);

        // Put away the moment the seeds are left for it, before it can pick them up
        boolean[] recalled = {false};
        helper.onEachTick(() -> {
            if (recalled[0]) {
                return;
            }
            for (ItemEntity item : helper.getLevel()
                    .getEntitiesOfClass(ItemEntity.class, helper.getBounds().inflate(4))) {
                if (TrackedDrops.handedTo(item) != null) {
                    recalled[0] = true;
                    planter.getPokemon().recall();
                    return;
                }
            }
        });

        // Nobody takes them for a while (a minute), then they're an ordinary pickup, and the
        // transporter puts them back
        helper.succeedWhen(() -> {
            helper.assertTrue(recalled[0], "seeds were left for the planter");
            helper.assertValueEqual(inChest(chest, Items.WHEAT_SEEDS), 8, "seeds back in the chest");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "seeds_thrown", timeoutTicks = 800)
    public void seedsAPlayerThrowsByAPlanterAreItsToPlant(GameTestHelper helper) {
        farmlandAndChest(helper);
        Workers.sendOut(helper, BULBASAUR, new BlockPos(3, 1, 3));
        PokemonEntity pidgey = Workers.sendOut(helper, PIDGEY, new BlockPos(6, 1, 6));
        // Thrown as Q does, ahead of the player (south, the way a new player faces), by the planter
        ServerPlayer player = Workers.owner(helper, new BlockPos(3, 1, 1));
        player.drop(new ItemStack(Items.WHEAT_SEEDS, 6), false, true);

        // The transporter right there never takes them: they're the planter's
        boolean[] transporterTookSome = {false};
        helper.onEachTick(() -> {
            if (count(WorkerInventoryManager.getOrCreate(pidgey.getPokemon()), Items.WHEAT_SEEDS) > 0) {
                transporterTookSome[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            assertPlanted(helper);
            helper.assertFalse(transporterTookSome[0], "the transporter took the planter's seeds");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "seeds_thrown_nobody", timeoutTicks = 400)
    public void seedsAPlayerThrowsWithNoPlanterByThemArePickedUp(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        PokemonEntity pidgey = Workers.sendOut(helper, PIDGEY, TRANSPORTER);
        ServerPlayer player = Workers.owner(helper, new BlockPos(4, 1, 2));
        player.drop(new ItemStack(Items.WHEAT_SEEDS, 6), false, true);

        helper.succeedWhen(() -> helper.assertValueEqual(
                count(WorkerInventoryManager.getOrCreate(pidgey.getPokemon()), Items.WHEAT_SEEDS), 6, "seeds carried"));
    }
}
