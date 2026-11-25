package dev.palworks.gametest;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.drops.TrackedDrops;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.job.JobManager;
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
 * run out asks, and a transporter brings it some and throws them to its feet; seeds a player throws
 * by a planter are its too. A worker that transports and plants still fetches its own.
 */
public class SeedDeliveryGameTests {
    private static final String BULBASAUR = "bulbasaur level=20"; // Grass: Planting, Gathering
    private static final String PIDGEY = "pidgey level=20"; // Flying: Transporting
    private static final CropBlock WHEAT = (CropBlock) Blocks.WHEAT;
    private static final BlockPos PLANTER = new BlockPos(3, 1, 1);
    private static final BlockPos TRANSPORTER = new BlockPos(1, 1, 1);
    private static final BlockPos CHEST = new BlockPos(6, 1, 2);

    // Where the workers' owner stands: off the field, out of every way they walk. A mock player
    // never moves, so one in a worker's way (as one stood where each worker was sent out) holds it
    // there for good, pushing it back as it walks into it.
    private static final BlockPos OWNER = new BlockPos(-2, 1, -2);

    /** Send out a worker here, owned by a player standing at {@link #OWNER}. */
    private static PokemonEntity sendOut(GameTestHelper helper, String properties, BlockPos rel) {
        return Workers.sendOut(helper, Workers.owner(helper, OWNER), properties, rel);
    }

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
        PokemonEntity planter = sendOut(helper, BULBASAUR, PLANTER);

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
        sendOut(helper, "hoppip level=20", PLANTER); // Grass/Flying: Planting and Transporting

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
        sendOut(helper, BULBASAUR, PLANTER);
        sendOut(helper, PIDGEY, TRANSPORTER);
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

    @GameTest(template = Templates.EMPTY, batch = "seeds_thrown_over", timeoutTicks = 1200)
    public void broughtSeedsLieOnTheGroundAMomentBeforeThePlanterTakesThem(GameTestHelper helper) {
        farmlandAndChest(helper, new ItemStack(Items.WHEAT_SEEDS, 8));
        sendOut(helper, BULBASAUR, PLANTER);
        sendOut(helper, PIDGEY, TRANSPORTER);
        // When the seeds were thrown, when they landed, and when they were taken
        long[] thrown = {-1};
        long[] landed = {-1};
        long[] taken = {-1};
        helper.onEachTick(() -> {
            ItemEntity seeds = null;
            for (ItemEntity item : helper.getLevel()
                    .getEntitiesOfClass(ItemEntity.class, helper.getBounds().inflate(4))) {
                if (TrackedDrops.handedTo(item) != null) {
                    seeds = item;
                }
            }
            long now = helper.getTick();
            if (seeds != null && thrown[0] < 0) {
                thrown[0] = now;
            }
            if (seeds != null && seeds.onGround() && landed[0] < 0) {
                landed[0] = now;
            }
            if (seeds == null && thrown[0] >= 0 && taken[0] < 0) {
                taken[0] = now;
            }
        });

        helper.succeedWhen(() -> {
            assertPlanted(helper);
            helper.assertTrue(landed[0] > thrown[0], "the seeds were thrown, and landed after");
            // A player's throw can be picked up after 2 seconds
            helper.assertTrue(taken[0] - thrown[0] >= 40, "the seeds lay there a moment before they were taken");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "seeds_thrown_short", timeoutTicks = 1000)
    public void seedsThatLandShortOfThePlanterAreStillItsToTake(GameTestHelper helper) {
        farmlandAndChest(helper, new ItemStack(Items.WHEAT_SEEDS, 8));
        PokemonEntity planter = sendOut(helper, BULBASAUR, PLANTER);
        PokemonEntity pidgey = sendOut(helper, PIDGEY, TRANSPORTER);

        // Thrown the moment it has them, from 6 blocks off: about as far as a transporter is
        // from a planter that's stepped away since it asked. They come down 4 blocks short of
        // it, outside the pickup radius round where it stands. (From 4 blocks, as it usually
        // is, they only start outside it: the job was lost if it was checked just then, which
        // failed the tests above one run in a dozen.)
        boolean[] thrown = {false};
        helper.onEachTick(() -> {
            if (!thrown[0] && count(WorkerInventoryManager.getOrCreate(pidgey.getPokemon()), Items.WHEAT_SEEDS) > 0) {
                thrown[0] = true;
                pidgey.moveTo(planter.getX() + 6, planter.getY(), planter.getZ());
                JobManager.getInstance(helper.getLevel()).handOverSeeds(pidgey.getPokemon());
            }
        });

        helper.succeedWhen(() -> assertPlanted(helper));
    }

    @GameTest(template = Templates.EMPTY, batch = "seeds_recalled", timeoutTicks = 2600)
    public void seedsLeftForAPlanterThatsPutAwayGoBackInAChest(GameTestHelper helper) {
        ChestBlockEntity chest = farmlandAndChest(helper, new ItemStack(Items.WHEAT_SEEDS, 8));
        PokemonEntity planter = sendOut(helper, BULBASAUR, PLANTER);
        sendOut(helper, PIDGEY, TRANSPORTER);

        // Put away the moment the seeds are left for it, before it can pick them up
        ItemEntity[] left = {null};
        helper.onEachTick(() -> {
            if (left[0] != null) {
                return;
            }
            for (ItemEntity item : helper.getLevel()
                    .getEntitiesOfClass(ItemEntity.class, helper.getBounds().inflate(4))) {
                if (TrackedDrops.handedTo(item) != null) {
                    left[0] = item;
                    planter.getPokemon().recall();
                    return;
                }
            }
        });

        // Nobody takes them for a while (a minute), then they're an ordinary pickup, and the
        // transporter puts them back
        helper.succeedWhen(() -> {
            helper.assertTrue(left[0] != null, "seeds were left for the planter");
            helper.assertValueEqual(
                    inChest(chest, Items.WHEAT_SEEDS),
                    8,
                    "seeds back in the chest (" + whatBecameOf(helper, left[0]) + ")");
        });
    }

    /**
     * What's become of seeds left for a planter, for a failure to say: this test has failed in CI
     * three times (November 2026) with the seeds never made a pickup, and never in a local run,
     * where it could be looked at.
     */
    private static String whatBecameOf(GameTestHelper helper, ItemEntity seeds) {
        return "the seeds left: " + seeds.getItem()
                + (seeds.isAlive() ? ", alive" : ", removed: " + seeds.getRemovalReason())
                + ", at " + seeds.position()
                + (seeds.onGround() ? ", landed" : ", not landed")
                + ", " + seeds.tickCount + " ticks old"
                + ", tags " + seeds.getTags()
                + (helper.getLevel().getEntity(seeds.getUUID()) != null ? "" : ", not found in the level by its id");
    }

    @GameTest(template = Templates.EMPTY, batch = "seeds_thrown", timeoutTicks = 800)
    public void seedsAPlayerThrowsByAPlanterAreItsToPlant(GameTestHelper helper) {
        farmlandAndChest(helper);
        sendOut(helper, BULBASAUR, new BlockPos(3, 1, 3));
        PokemonEntity pidgey = sendOut(helper, PIDGEY, new BlockPos(6, 1, 6));
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
        PokemonEntity pidgey = sendOut(helper, PIDGEY, TRANSPORTER);
        ServerPlayer player = Workers.owner(helper, new BlockPos(4, 1, 2));
        player.drop(new ItemStack(Items.WHEAT_SEEDS, 6), false, true);

        helper.succeedWhen(() -> helper.assertValueEqual(
                count(WorkerInventoryManager.getOrCreate(pidgey.getPokemon()), Items.WHEAT_SEEDS), 6, "seeds carried"));
    }

    @GameTest(template = Templates.EMPTY, batch = "seeds_no_room", timeoutTicks = 500)
    public void seedsArentFetchedForAPlanterWithNoRoomForThem(GameTestHelper helper) {
        farmlandAndChest(helper, new ItemStack(Items.WHEAT_SEEDS, 8));
        PokemonEntity bulbasaur = sendOut(helper, BULBASAUR, PLANTER);
        // Its pouch is full of seeds this farmland doesn't take
        WorkerInventory pouch = WorkerInventoryManager.getOrCreate(bulbasaur.getPokemon());
        for (int slot = 0; slot < pouch.getContainerSize(); slot++) {
            pouch.setItem(slot, new ItemStack(Items.NETHER_WART, 64));
        }
        PokemonEntity pidgey = sendOut(helper, PIDGEY, TRANSPORTER);
        boolean[] fetched = {false};
        helper.onEachTick(() -> {
            if (count(WorkerInventoryManager.getOrCreate(pidgey.getPokemon()), Items.WHEAT_SEEDS) > 0) {
                fetched[0] = true;
            }
        });

        helper.runAfterDelay(400, () -> {
            helper.assertFalse(fetched[0], "seeds were fetched for a planter with no room for them");
            helper.succeed();
        });
    }

    @GameTest(template = Templates.ARENA, batch = "seeds_unreachable_planter", timeoutTicks = 700)
    public void seedsArentFetchedOverAndOverForAPlanterOutOfReach(GameTestHelper helper) {
        Workers.isolate(helper);
        // A planter with farmland to plant, in a walled pen: too high to jump, too wide to throw
        // seeds over
        for (int i = 1; i <= 14; i++) {
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(i, y, 1, Blocks.STONE);
                helper.setBlock(i, y, 14, Blocks.STONE);
                helper.setBlock(1, y, i, Blocks.STONE);
                helper.setBlock(14, y, i, Blocks.STONE);
            }
        }
        for (int x = 6; x <= 8; x++) {
            helper.setBlock(x, 0, 6, Blocks.FARMLAND);
        }
        Workers.sendOut(helper, BULBASAUR, new BlockPos(8, 1, 8));
        // Outside it: a chest of seeds, and a transporter that can't fly in
        BlockPos chestPos = new BlockPos(-3, 1, 7);
        helper.setBlock(chestPos, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(chestPos));
        chest.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
        PokemonEntity doduo = Workers.sendOut(helper, "doduo level=20", new BlockPos(-5, 1, 7));
        int[] fetches = {0};
        boolean[] carrying = {false};
        helper.onEachTick(() -> {
            boolean has = count(WorkerInventoryManager.getOrCreate(doduo.getPokemon()), Items.WHEAT_SEEDS) > 0;
            if (has && !carrying[0]) {
                fetches[0]++;
            }
            carrying[0] = has;
        });

        // Once it's found it can't get them there, it leaves that planter's seeds for a while
        // (30 seconds), rather than fetch them and put them back for as long as it asks
        helper.runAfterDelay(550, () -> {
            helper.assertTrue(fetches[0] <= 1, "seeds fetched " + fetches[0] + " times for a planter out of reach");
            helper.succeed();
        });
    }
}
