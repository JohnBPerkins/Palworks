package dev.palworks.gametest;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.boulder.BoulderKind;
import dev.palworks.boulder.BoulderKinds;
import dev.palworks.boulder.BoulderOreLevels;
import dev.palworks.boulder.BoulderPlan;
import dev.palworks.boulder.BoulderRise;
import dev.palworks.boulder.BoulderShape;
import dev.palworks.boulder.BoulderSites;
import dev.palworks.boulder.BoulderStyle;
import dev.palworks.boulder.Boulders;
import dev.palworks.crystal.MiningTool;
import dev.palworks.debug.BoulderShowcase;
import dev.palworks.job.JobChecks;
import dev.palworks.job.JobType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * Boulders (see dev.palworks.boulder): raised by Unearthing workers only on open, flat ground,
 * never over anything; rising as displays that leave nothing behind; mined from the top down for
 * what the miner's pickaxe gets, a level 1 miner leaving what its wooden one gets nothing from.
 */
public class BoulderGameTests {
    private static final BlockPos START = new BlockPos(1, 1, 1);
    private static final BlockPos CORNER = new BlockPos(3, 1, 3);
    private static final String STONE = "minecraft:stone";
    private static final String IRON_ORE = "minecraft:iron_ore";
    private static final String A_BOULDER = "a boulder";

    private static ServerLevel level(GameTestHelper helper) {
        return helper.getLevel();
    }

    /** A boulder of these blocks (cells bottom layer first, as BoulderShape.cells), its columns {@code heights[x][z]}. */
    private static BoulderPlan plan(List<String> blocks, int[]... heights) {
        return new BoulderPlan("test", BoulderShape.of(BoulderStyle.DOME, heights), blocks);
    }

    @Nullable
    private static Boulders.Boulder raisedBy(GameTestHelper helper, UUID summoner) {
        for (Boulders.Boulder boulder : Boulders.get(level(helper)).all()) {
            if (summoner.equals(boulder.summoner())) {
                return boulder;
            }
        }
        return null;
    }

    /** The rising boulders' displays round the test. */
    private static int risingDisplays(GameTestHelper helper) {
        AABB around = helper.getBounds().inflate(40);
        return level(helper)
                .getEntitiesOfClass(
                        Display.BlockDisplay.class,
                        around,
                        display -> display.getTags().contains(BoulderRise.TAG))
                .size();
    }

    // ---- Raising them ----

    @GameTest(template = Templates.EMPTY, batch = "boulder_pastured", timeoutTicks = 1200)
    public void aPasturedRockTypeRaisesABoulderWithoutBreakingAPlant(GameTestHelper helper) {
        raisesABoulderWithoutBreakingAPlant(helper, true);
    }

    @GameTest(template = Templates.EMPTY, batch = "boulder_party", timeoutTicks = 1200)
    public void aPartyRockTypeRaisesABoulderNearItsOwner(GameTestHelper helper) {
        raisesABoulderWithoutBreakingAPlant(helper, false);
    }

    private static void raisesABoulderWithoutBreakingAPlant(GameTestHelper helper, boolean pastured) {
        Workers.isolate(helper);
        // Its own area all flowers and grass: never broken, and nothing raised on them. The ground
        // round the test is open.
        List<BlockPos> plants = new ArrayList<>();
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(x, 0, z, Blocks.GRASS_BLOCK);
                BlockPos plant = new BlockPos(x, 1, z);
                if (!plant.equals(START)) {
                    helper.setBlock(plant, (x + z) % 2 == 0 ? Blocks.POPPY : Blocks.SHORT_GRASS);
                    plants.add(plant);
                }
            }
        }
        // Geodude: Rock/Ground, Unearthing 1, so a small badlands boulder
        PokemonEntity worker;
        if (pastured) {
            worker = Workers.sendOutPastured(helper, "geodude level=20", START);
            Workers.tetherTo(helper, worker, START);
        } else {
            worker = Workers.sendOut(helper, "geodude level=20", START);
        }
        UUID summoner = worker.getPokemon().getUuid();
        int fed = worker.getPokemon().getCurrentFullness();
        BoulderKind badlands = BoulderKinds.current().get("badlands");
        Set<String> allowed = new HashSet<>(badlands.stone().keySet());
        allowed.addAll(badlands.ores().keySet());

        helper.succeedWhen(() -> {
            Boulders.Boulder boulder = raisedBy(helper, summoner);
            helper.assertTrue(boulder != null, A_BOULDER);
            helper.assertValueEqual(boulder.kind(), "badlands", "its kind, by its type besides Rock");
            for (BlockPos pos : boulder.positions()) {
                String block = BuiltInRegistries.BLOCK
                        .getKey(level(helper).getBlockState(pos).getBlock())
                        .toString();
                helper.assertTrue(allowed.contains(block), block + " isn't a badlands block");
                helper.assertFalse(
                        helper.getBounds().contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5),
                        "raised on the plants at " + pos);
            }
            for (BlockPos plant : plants) {
                helper.assertFalse(helper.getBlockState(plant).isAir(), "the plant at " + plant + " was broken");
            }
            // Hungry work: at least a point of fullness for a boulder
            helper.assertTrue(
                    worker.getPokemon().getCurrentFullness() <= fed - 1,
                    "fullness " + worker.getPokemon().getCurrentFullness() + " of " + fed);
        });
    }

    @GameTest(template = Templates.EMPTY)
    public void aSiteNeedsFlatOpenGroundWithRoomRoundIt(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.STONE);
        ServerLevel level = level(helper);
        BoulderShape shape = BoulderShape.of(BoulderStyle.DOME, new int[] {1, 1}, new int[] {1, 1});
        BlockPos corner = helper.absolutePos(CORNER);
        helper.assertTrue(BoulderSites.isOpen(level, corner, shape), "open, flat stone");

        // A flower on its footprint: it isn't broken to make room
        assertClosedWith(helper, shape, new BlockPos(4, 1, 4), Blocks.POPPY.defaultBlockState(), "a flower on it");
        // A flower beside it: nothing's done to it, so that's all right
        helper.setBlock(new BlockPos(1, 1, 3), Blocks.POPPY);
        helper.assertTrue(BoulderSites.isOpen(level, corner, shape), "a flower in the margin");
        helper.setBlock(new BlockPos(1, 1, 3), Blocks.AIR);
        // Anything solid within the margin, a step, a hole or water in the ground
        assertClosedWith(helper, shape, new BlockPos(6, 1, 6), Blocks.STONE.defaultBlockState(), "a block 2 off");
        assertClosedWith(helper, shape, new BlockPos(5, 0, 1), Blocks.AIR.defaultBlockState(), "a hole");
        assertClosedWith(helper, shape, new BlockPos(4, 0, 4), Blocks.WATER.defaultBlockState(), "water");
        helper.assertTrue(BoulderSites.isOpen(level, corner, shape), "open again");
        // On another boulder
        BlockPos onTop = corner.above();
        Boulders.get(level)
                .place(
                        level,
                        corner,
                        plan(List.of(STONE, STONE, STONE, STONE), new int[] {1, 1}, new int[] {1, 1}),
                        null);
        helper.assertFalse(BoulderSites.isOpen(level, onTop, shape), "on a boulder");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void theOneOpenPatchAmongFlowersIsFound(GameTestHelper helper) {
        // Flowers everywhere but a 2 by 2 patch: every spot is looked at, so it's found
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                boolean patch = x >= 3 && x <= 4 && z >= 3 && z <= 4;
                helper.setBlock(new BlockPos(x, 1, z), patch ? Blocks.AIR : Blocks.POPPY);
            }
        }
        BoulderShape shape = BoulderShape.of(BoulderStyle.DOME, new int[] {1, 1}, new int[] {1, 1});
        AABB bounds = helper.getBounds();
        List<BlockPos> sites = BoulderSites.find(
                level(helper),
                shape,
                helper.absolutePos(new BlockPos(1, 1, 1)),
                6,
                pos -> bounds.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5),
                new Random(1),
                3);
        helper.assertValueEqual(sites, List.of(helper.absolutePos(CORNER)), "the patch, and only it");
        helper.succeed();
    }

    private static void assertClosedWith(
            GameTestHelper helper, BoulderShape shape, BlockPos at, BlockState state, String what) {
        BlockState was = helper.getBlockState(at);
        helper.setBlock(at, state);
        helper.assertFalse(BoulderSites.isOpen(level(helper), helper.absolutePos(CORNER), shape), what);
        helper.setBlock(at, was);
    }

    @GameTest(template = Templates.EMPTY, timeoutTicks = 200)
    public void aBoulderRisesAndIsThenRealBlocks(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.STONE);
        ServerLevel level = level(helper);
        UUID summoner = UUID.randomUUID();
        BoulderPlan plan = plan(List.of(STONE, IRON_ORE, STONE), new int[] {2}, new int[] {1});
        BlockPos corner = helper.absolutePos(CORNER);
        BoulderRise.start(level, summoner, corner, plan);
        helper.assertValueEqual(risingDisplays(helper), 3, "a display a block");
        helper.assertBlockPresent(Blocks.AIR, CORNER);

        helper.runAfterDelay(BoulderRise.RISE_TICKS + 3, () -> {
            Map<BlockPos, BlockState> placed = BoulderRise.finish(summoner);
            helper.assertValueEqual(placed.size(), 3, "blocks placed");
            helper.assertBlockPresent(Blocks.STONE, CORNER);
            helper.assertBlockPresent(Blocks.IRON_ORE, CORNER.east());
            helper.assertBlockPresent(Blocks.STONE, CORNER.above());
            helper.assertTrue(Boulders.get(level).hasStanding(summoner), "its summoner's");
            // The displays stay over the new blocks a moment, so clients never see a gap
            helper.assertValueEqual(risingDisplays(helper), 3, "displays over the blocks");
            helper.runAfterDelay(BoulderRise.LINGER_TICKS + 1, () -> {
                helper.assertValueEqual(risingDisplays(helper), 0, "displays left");
                helper.succeed();
            });
        });
    }

    @GameTest(template = Templates.EMPTY, timeoutTicks = 100)
    public void aRiseThatsStoppedOrGotInTheWayOfLeavesNothing(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.STONE);
        ServerLevel level = level(helper);
        BoulderPlan plan = plan(List.of(STONE, STONE), new int[] {1}, new int[] {1});
        BlockPos corner = helper.absolutePos(CORNER);

        UUID stopped = UUID.randomUUID();
        BoulderRise.start(level, stopped, corner, plan);
        BoulderRise.cancel(stopped);
        helper.assertValueEqual(risingDisplays(helper), 0, "displays left after it's stopped");

        // A block put where it was going: nothing replaced, nothing placed
        UUID blocked = UUID.randomUUID();
        BoulderRise.start(level, blocked, corner, plan);
        helper.setBlock(CORNER.east(), Blocks.OAK_PLANKS);
        helper.assertTrue(BoulderRise.finish(blocked).isEmpty(), "nothing placed");
        helper.assertBlockPresent(Blocks.AIR, CORNER);
        helper.assertBlockPresent(Blocks.OAK_PLANKS, CORNER.east());
        helper.assertFalse(Boulders.get(level).hasStanding(blocked), "no boulder");
        helper.assertValueEqual(risingDisplays(helper), 0, "displays left after it was in the way");
        helper.succeed();
    }

    // ---- Mining them ----

    @GameTest(template = Templates.EMPTY)
    public void aBoulderIsMinedFromItsTopTwoLayersDown(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.STONE);
        ServerLevel level = level(helper);
        // A 4-high column beside a 1-high one
        Boulders.get(level)
                .place(
                        level,
                        helper.absolutePos(CORNER),
                        plan(List.of(STONE, STONE, STONE, STONE, STONE), new int[] {1}, new int[] {4}),
                        null);
        BlockPos low = CORNER;
        BlockPos column = CORNER.east();
        helper.assertTrue(minable(helper, column.above(3)), "its top");
        helper.assertFalse(minable(helper, column.above(2)), "one with a block on it");
        helper.assertFalse(minable(helper, low), "the low one, three layers down");
        helper.destroyBlock(column.above(3));
        helper.assertTrue(minable(helper, column.above(2)), "the next down, once the top's gone");
        helper.assertFalse(minable(helper, low), "the low one, still too far down");
        helper.destroyBlock(column.above(2));
        helper.assertTrue(minable(helper, low), "the low one, in the top two layers now");
        helper.succeed();
    }

    private static boolean minable(GameTestHelper helper, BlockPos rel) {
        return JobChecks.isJobStillThere(level(helper), JobType.MINE_BOULDER, helper.absolutePos(rel));
    }

    @GameTest(template = Templates.EMPTY)
    public void aBoulderIsGoneOnceItsBlocksAreBrokenWhoeverBreaksThem(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.STONE);
        ServerLevel level = level(helper);
        UUID summoner = UUID.randomUUID();
        Boulders.get(level)
                .place(
                        level,
                        helper.absolutePos(CORNER),
                        plan(List.of(STONE, STONE), new int[] {1}, new int[] {1}),
                        summoner);
        helper.assertTrue(Boulders.get(level).hasStanding(summoner), A_BOULDER);
        helper.destroyBlock(CORNER); // as a player would
        helper.assertTrue(
                Boulders.get(level).boulderAt(helper.absolutePos(CORNER)) == null, "the broken block isn't its");
        helper.assertTrue(Boulders.get(level).hasStanding(summoner), "one block left");
        helper.setBlock(CORNER.east(), Blocks.DIRT); // replaced
        helper.assertFalse(Boulders.get(level).hasStanding(summoner), "nothing left of it");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = "boulder_miner_1", timeoutTicks = 900)
    public void aLevelOneMinerLeavesOreItsWoodenPickaxeGetsNothingFrom(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.STONE);
        ServerLevel level = level(helper);
        Boulders.get(level)
                .place(
                        level,
                        helper.absolutePos(CORNER),
                        plan(List.of(STONE, IRON_ORE), new int[] {1}, new int[] {1}),
                        null);
        Workers.sendOut(helper, "geodude level=20", START); // Mining 1: a wooden pickaxe

        helper.runAfterDelay(700, () -> {
            helper.assertBlockPresent(Blocks.AIR, CORNER);
            // As a wooden pickaxe breaks stone: one cobblestone
            helper.assertValueEqual(Scenes.itemsOnGround(helper, Items.COBBLESTONE), 1, "cobblestone");
            helper.assertBlockPresent(Blocks.IRON_ORE, CORNER.east());
            helper.assertTrue(
                    Boulders.get(level).boulderAt(helper.absolutePos(CORNER.east())) != null, "still a boulder's");
            helper.succeed();
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "boulder_miner_2", timeoutTicks = 900)
    public void aLevelTwoMinerTakesTheOre(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.STONE);
        ServerLevel level = level(helper);
        Boulders.get(level).place(level, helper.absolutePos(CORNER), plan(List.of(IRON_ORE), new int[] {1}), null);
        Workers.sendOut(helper, "graveler level=30", START); // Mining 2: a diamond pickaxe

        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, CORNER);
            helper.assertValueEqual(Scenes.itemsOnGround(helper, Items.RAW_IRON), 1, "raw iron");
        });
    }

    @GameTest(template = Templates.EMPTY)
    public void iceInABoulderIsMinedAsWithSilkTouch(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.STONE);
        ServerLevel level = level(helper);
        BlockPos at = helper.absolutePos(CORNER);
        for (Block ice : List.of(Blocks.PACKED_ICE, Blocks.BLUE_ICE)) {
            helper.setBlock(CORNER, ice);
            // Even a level 1 miner's wooden pickaxe, which by hand gets nothing from it
            MiningTool.breakWith(
                    level, at, null, MiningTool.forBoulderBlock(level.registryAccess(), 1, ice.defaultBlockState()));
            helper.assertBlockPresent(Blocks.AIR, CORNER);
            helper.assertValueEqual(Scenes.itemsOnGround(helper, ice.asItem()), 1, ice + " dropped");
        }
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = "boulder_big_miner", timeoutTicks = 2400)
    public void aBigMinerReachesATallBouldersTopFromTheGround(GameTestHelper helper) {
        // A single column five high: no steps to stand on, and a Rhyperior wouldn't fit one anyway.
        // Nearly three high itself, it reaches the top from the ground and mines it down.
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.STONE);
        ServerLevel level = level(helper);
        Boulders.get(level)
                .place(
                        level,
                        helper.absolutePos(CORNER),
                        plan(List.of(STONE, STONE, STONE, STONE, STONE), new int[] {5}),
                        null);
        Workers.sendOut(helper, "rhyperior level=50", START); // Ground/Rock: it mines

        helper.succeedWhen(() -> {
            for (int up = 0; up < 5; up++) {
                helper.assertBlockPresent(Blocks.AIR, CORNER.above(up));
            }
        });
    }

    @GameTest(template = Templates.EMPTY)
    public void everyBlockOfEveryKindDropsSomethingForADiamondPickaxe(GameTestHelper helper) {
        ServerLevel level = level(helper);
        BlockPos pos = helper.absolutePos(CORNER);
        List<String> nothing = new ArrayList<>();
        Set<String> blocks = new HashSet<>(BoulderOreLevels.current().all().keySet());
        for (BoulderKind kind : BoulderKinds.current().all()) {
            blocks.addAll(kind.stone().keySet());
            blocks.addAll(kind.ores().keySet());
        }
        for (String id : blocks) {
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
            // As a level 2 miner breaks it (ice with Silk Touch)
            ItemStack pickaxe = MiningTool.forBoulderBlock(level.registryAccess(), 2, block.defaultBlockState());
            List<ItemStack> drops = Block.getDrops(block.defaultBlockState(), level, pos, null, null, pickaxe);
            if (drops.stream().allMatch(ItemStack::isEmpty)) {
                nothing.add(id);
            }
        }
        helper.assertTrue(nothing.isEmpty(), "drop nothing: " + nothing);
        helper.assertValueEqual(BoulderKinds.current().all().size(), 19, "kinds loaded");
        helper.succeed();
    }

    // ---- The showcase ----

    @GameTest(template = Templates.EMPTY, batch = "boulder_showcase", timeoutTicks = 200)
    public void theShowcaseHasABoulderOfEveryKindInEveryStyle(GameTestHelper helper) {
        ServerLevel level = level(helper);
        // Well away from the other tests' areas, and the test area's (at 600)
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0)).offset(900, 0, 0);
        int before = Boulders.get(level).all().size();
        BoulderShowcase.Result result = BoulderShowcase.everyKind(level, origin, 5);
        int expected = BoulderKinds.current().all().size() * BoulderStyle.values().length;
        helper.assertValueEqual(result.boulders(), expected, "boulders");
        helper.assertTrue(Boulders.get(level).all().size() >= before + expected, "all of them boulders");
        for (Boulders.Boulder boulder : Boulders.get(level).all()) {
            for (BlockPos pos : boulder.positions()) {
                if (pos.getX() >= result.min().getX()
                        && pos.getX() <= result.max().getX()) {
                    helper.assertTrue(
                            pos.getZ() >= result.min().getZ()
                                    && pos.getZ() <= result.max().getZ(),
                            "inside the showcase: " + pos);
                    helper.assertTrue(pos.getY() >= result.min().getY(), "on its floor: " + pos);
                }
            }
        }
        // And one kind at every level
        BoulderShowcase.Result one = BoulderShowcase.oneKind(
                level, origin.offset(0, 0, 200), BoulderKinds.current().get("stone"));
        helper.assertValueEqual(one.boulders(), 5 * BoulderStyle.values().length, "one kind's boulders");
        helper.succeed();
    }

    // A boulder rolled from a shipped kind, placed: each of its blocks where its shape says
    @GameTest(template = Templates.EMPTY)
    public void aRolledBoulderPlacesItsOwnBlocks(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.STONE);
        ServerLevel level = level(helper);
        BoulderPlan plan = BoulderPlan.roll(
                BoulderKinds.current().get("volcanic"),
                1,
                BoulderStyle.SLAB,
                BoulderOreLevels.current(),
                new Random(5));
        Map<BlockPos, BlockState> placed =
                Boulders.get(level).place(level, helper.absolutePos(new BlockPos(2, 1, 2)), plan, null);
        helper.assertValueEqual(placed.size(), plan.shape().blockCount(), "every block placed");
        for (Map.Entry<BlockPos, BlockState> block : placed.entrySet()) {
            helper.assertTrue(
                    level.getBlockState(block.getKey()).equals(block.getValue()), "placed at " + block.getKey());
        }
        helper.succeed();
    }
}
