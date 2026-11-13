package dev.palworks.gametest;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.ai.path.WorkerPathfinder;
import dev.palworks.ai.path.WorkerPathfinder.Reach;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Workers' paths fit their whole body: its real width and height against the blocks' real shapes,
 * room overhead to jump up, and Pokémon too big for Cobblemon's own pathfinder to walk-check
 * getting where they're going.
 *
 * <p>Most of these build two rooms joined by a passage in a block of solid stone, so the passage is
 * the only way between them, and ask the pathfinder whether the worker can get from one to the
 * other: then change the passage and ask again.
 */
public class PathingGameTests {
    // Machoke: 0.8 wide, 1.7 tall. Machamp: 1.2 wide, 2.16 tall. Venusaur: 2.64 wide, 2.4 tall.
    private static final String MACHOKE = "machoke level=30";
    private static final String MACHAMP = "machamp level=40";
    private static final String VENUSAUR = "venusaur level=40";
    private static final String BULBASAUR = "bulbasaur level=20";

    /** Where the worker starts, in the middle of the first room. */
    private static final BlockPos START = new BlockPos(3, 1, 3);
    /** The tile it's asked to get to, in the middle of the second room. */
    private static final BlockPos GOAL = new BlockPos(11, 1, 3);
    /** Ticks to let a sent-out worker land before planning. */
    private static final int SETTLE_TICKS = 10;

    private static final CropBlock WHEAT = (CropBlock) Blocks.WHEAT;

    // ---- Scenes ----

    /** Solid stone from the floor up to y=4 over the whole arena, for rooms to be carved out of. */
    private static void solid(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y <= 4; y++) {
                    helper.setBlock(x, y, z, Blocks.STONE);
                }
            }
        }
    }

    /** Fill a box (relative, inclusive) with one block. */
    private static void fill(GameTestHelper helper, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    helper.setBlock(x, y, z, state);
                }
            }
        }
    }

    private static void carve(GameTestHelper helper, int x0, int y0, int z0, int x1, int y1, int z1) {
        fill(helper, x0, y0, z0, x1, y1, z1, Blocks.AIR.defaultBlockState());
    }

    /** The two 3x3 rooms round START and GOAL, from y=1 up to {@code top}. */
    private static void rooms(GameTestHelper helper, int top) {
        carve(helper, 2, 1, 2, 4, top, 4);
        carve(helper, 10, 1, 2, 12, top, 4);
    }

    private static BlockState bottomSlab() {
        return Blocks.STONE_SLAB.defaultBlockState();
    }

    private static BlockState topSlab() {
        return Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
    }

    // ---- Checks ----

    private static void assertReach(
            GameTestHelper helper, PokemonEntity worker, BlockPos goal, Reach expected, String why) {
        WorkerPathfinder.Result result = WorkerPathfinder.find(worker, helper.absolutePos(goal));
        helper.assertTrue(
                result.reach() == expected,
                why + ": expected " + expected + " but the pathfinder said " + result.reach());
        if (result.reach() == Reach.YES) {
            assertEndsOnTile(helper, worker, result.path(), goal);
        }
    }

    /** The path ends with the worker's middle on the tile (or one of its corners, if 1-2 wide). */
    private static void assertEndsOnTile(GameTestHelper helper, PokemonEntity worker, Path path, BlockPos goal) {
        helper.assertTrue(path != null && path.getNodeCount() > 0, "a path that reaches has nodes");
        BlockPos abs = helper.absolutePos(goal);
        Node end = path.getEndNode();
        var at = path.getEntityPosAtNode(worker, path.getNodeCount() - 1);
        helper.assertTrue(
                Math.abs(at.x - (abs.getX() + 0.5)) <= 0.51
                        && Math.abs(at.z - (abs.getZ() + 0.5)) <= 0.51
                        && end.y == abs.getY(),
                "the path ends on the tile " + abs + ", not at " + at);
    }

    // ---- Width ----

    @GameTest(template = Templates.ARENA, batch = "pathing_wide_gap", timeoutTicks = 200)
    public void aWideBodyNeedsAGapItFitsThrough(GameTestHelper helper) {
        // Machamp is 1.2 blocks wide: it can't get through a gap a block wide
        Workers.isolate(helper);
        solid(helper);
        rooms(helper, 3);
        carve(helper, 5, 1, 3, 9, 3, 3); // a passage 1 wide
        PokemonEntity worker = Workers.sendOut(helper, MACHAMP, START);

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            assertReach(helper, worker, GOAL, Reach.NO, "a 1.2-wide Machamp through a 1-wide gap");
            carve(helper, 5, 1, 4, 9, 3, 4); // now 2 wide
            assertReach(helper, worker, GOAL, Reach.YES, "a 1.2-wide Machamp through a 2-wide gap");
            helper.succeed();
        });
    }

    // ---- How far round it looks ----

    /**
     * A wall across the way, 3 high, reaching further to each side than an ordinary search looks
     * round (8 blocks past the start and goal): from (2, 10) to (30, 10), the wall at x 16 runs from
     * z -6 to 26.
     */
    private static void longWall(GameTestHelper helper) {
        for (int z = -6; z <= 26; z++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(16, y, z), Blocks.STONE);
            }
        }
    }

    @GameTest(template = Templates.EMPTY, batch = "pathing_far_trip", timeoutTicks = 200)
    public void aFarTripLooksFurtherRoundAWall(GameTestHelper helper) {
        Workers.isolate(helper);
        Runnable unload = Scenes.keepLoaded(helper, new BlockPos(-40, 0, -40), new BlockPos(60, 0, 50));
        longWall(helper);
        PokemonEntity worker = Workers.sendOut(helper, BULBASAUR, new BlockPos(2, 1, 10));
        BlockPos goal = helper.absolutePos(new BlockPos(30, 1, 10));
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            helper.assertTrue(
                    WorkerPathfinder.find(worker, goal).reach() == Reach.NO,
                    "an ordinary search gives up: the way round is further out than it looks");
            helper.assertTrue(
                    WorkerPathfinder.find(worker, goal, true).reach() == Reach.YES,
                    "a far trip's (to a trough or bed) finds the way round");
            unload.run();
            helper.succeed();
        });
    }

    // ---- Each Pokémon's own size ----

    @GameTest(template = Templates.ARENA, batch = "pathing_own_size", timeoutTicks = 200)
    public void eachPokemonsPathFitsItsOwnSize(GameTestHelper helper) {
        // Cobblemon sizes each Pokémon on its own (its scale modifier), so two of a species can need
        // different ways through: a normal Machoke (0.8 wide, 1.7 tall) fits a passage 1 wide and 2
        // high, one scaled up by half (1.2 wide, 2.55 tall) doesn't. And a Pokémon resized after it
        // was sent out is planned for at its new size.
        Workers.isolate(helper);
        solid(helper);
        rooms(helper, 3);
        carve(helper, 5, 1, 3, 9, 2, 3);
        PokemonEntity small = Workers.sendOut(helper, MACHOKE, START);
        PokemonEntity big = Workers.sendOut(helper, MACHOKE + " scale_modifier=1.5", START);

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            helper.assertTrue(
                    big.getBbWidth() > small.getBbWidth() * 1.4,
                    "the scaled-up Machoke is bigger: " + big.getBbWidth() + " wide, against " + small.getBbWidth());
            assertReach(helper, small, GOAL, Reach.YES, "a normal Machoke through a 1x2 passage");
            assertReach(helper, big, GOAL, Reach.NO, "a Machoke half as big again through a 1x2 passage");

            small.getPokemon().setScaleModifier(1.5F);
            small.refreshDimensions();
            assertReach(helper, small, GOAL, Reach.NO, "a Machoke grown by half since it was sent out");

            carve(helper, 5, 1, 3, 9, 3, 4); // 2 wide, 3 high
            assertReach(helper, big, GOAL, Reach.YES, "the big Machoke through a 2x3 passage");
            helper.succeed();
        });
    }

    @GameTest(template = Templates.ARENA, batch = "pathing_huge_detour", timeoutTicks = 200)
    public void aHugePokemonGetsAWideEnoughBerthToGoRoundThings(GameTestHelper helper) {
        // A Venusaur at double size is over 5 blocks wide. Round the end of a long wall, its middle
        // has to go well past the wall's end, further out than a small Pokémon ever needs to: the
        // search used to stop 8 blocks out whatever the size, which wasn't far enough.
        Workers.isolate(helper);
        RealTreeGameTests.arenaFloor(helper, Blocks.GRASS_BLOCK);
        fill(helper, 8, 1, -12, 8, 2, 18, Blocks.STONE.defaultBlockState());
        PokemonEntity worker = Workers.sendOut(helper, VENUSAUR + " scale_modifier=2", new BlockPos(2, 1, 8));

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            helper.assertTrue(worker.getBbWidth() > 5.0F, "the Venusaur is over 5 wide: " + worker.getBbWidth());
            assertReach(helper, worker, new BlockPos(14, 1, 8), Reach.YES, "round the end of the wall");
            helper.succeed();
        });
    }

    // ---- Height ----

    @GameTest(template = Templates.ARENA, batch = "pathing_tall_slabs", timeoutTicks = 200)
    public void aTallBodyNeedsHeadroomOverTheFloorItsOn(GameTestHelper helper) {
        // Machamp is 2.16 tall. A passage with a ceiling of top slabs has 2.5 blocks of room, which
        // it fits under (Cobblemon's pathfinder counts whole blocks, so calls that 2); bottom slabs
        // on its floor leave 2, which it doesn't.
        Workers.isolate(helper);
        solid(helper);
        rooms(helper, 3);
        carve(helper, 5, 1, 3, 9, 3, 4);
        fill(helper, 5, 3, 3, 9, 3, 4, topSlab());
        PokemonEntity worker = Workers.sendOut(helper, MACHAMP, START);

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            assertReach(helper, worker, GOAL, Reach.YES, "2.16 tall under 2.5 blocks of room");
            fill(helper, 5, 1, 3, 9, 1, 4, bottomSlab());
            assertReach(helper, worker, GOAL, Reach.NO, "2.16 tall on slabs, under 2 blocks of room");
            helper.succeed();
        });
    }

    @GameTest(template = Templates.ARENA, batch = "pathing_jump_headroom", timeoutTicks = 200)
    public void aJumpUpNeedsRoomOverWhereItTakesOff(GameTestHelper helper) {
        // Machoke (1.7 tall) stands fine in a room 2 high, and the ledge it wants is 2 high too, but
        // jumping up onto the ledge takes 2.7 blocks of room where it jumps from
        Workers.isolate(helper);
        solid(helper);
        rooms(helper, 2);
        // The passage and the far room are a block up: their floor is y=2, with room to y=4
        carve(helper, 5, 2, 3, 9, 3, 3);
        fill(helper, 10, 1, 2, 12, 1, 4, Blocks.STONE.defaultBlockState());
        carve(helper, 10, 2, 2, 12, 3, 4);
        PokemonEntity worker = Workers.sendOut(helper, MACHOKE, START);
        BlockPos up = GOAL.above();

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            assertReach(helper, worker, up, Reach.NO, "jumping up with its head against the ceiling");
            carve(helper, 2, 3, 2, 4, 3, 4); // room over the first room too
            assertReach(helper, worker, up, Reach.YES, "jumping up with room overhead");
            helper.succeed();
        });
    }

    // ---- Big Pokémon, end to end ----

    @GameTest(template = Templates.ARENA, batch = "pathing_big_step_up", timeoutTicks = 1200)
    public void aBigPokemonStepsUpToItsWork(GameTestHelper helper) {
        // Venusaur's hitbox is too big for Cobblemon's pathfinder to plan walking for (it can't
        // plan a step up at all), so wheat on a terrace a block up was out of its reach
        Workers.isolate(helper);
        RealTreeGameTests.arenaFloor(helper, Blocks.GRASS_BLOCK);
        fill(helper, 7, 1, 2, 15, 1, 14, Blocks.STONE.defaultBlockState());
        for (int x = 10; x <= 12; x++) {
            helper.setBlock(x, 1, 8, Blocks.FARMLAND);
            helper.setBlock(x, 2, 8, WHEAT.getStateForAge(7));
        }
        Workers.sendOut(helper, VENUSAUR, new BlockPos(2, 1, 8));

        helper.succeedWhen(() -> {
            for (int x = 10; x <= 12; x++) {
                BlockPos crop = new BlockPos(x, 2, 8);
                helper.assertTrue(
                        helper.getBlockState(crop).equals(WHEAT.getStateForAge(0)),
                        "wheat at x=" + x + " harvested and reset, but it's " + helper.getBlockState(crop));
            }
        });
    }

    // ---- Changes on the way ----

    @GameTest(template = Templates.ARENA, batch = "pathing_blocked_on_the_way", timeoutTicks = 1200)
    public void aWorkerGoesRoundAWallPutInItsWay(GameTestHelper helper) {
        // A wall goes up across its way once it's walking: it finds the way round, and doesn't
        // walk into the wall (or give up)
        Workers.isolate(helper);
        RealTreeGameTests.arenaFloor(helper, Blocks.GRASS_BLOCK);
        for (int x = 12; x <= 14; x++) {
            helper.setBlock(x, 0, 8, Blocks.FARMLAND);
            helper.setBlock(x, 1, 8, WHEAT.getStateForAge(7));
        }
        PokemonEntity worker = Workers.sendOut(helper, BULBASAUR, new BlockPos(2, 1, 8));

        boolean[] walled = {false};
        helper.onEachTick(() -> {
            if (!walled[0]
                    && worker.getNavigation().getPath() != null
                    && worker.getX() > helper.absolutePos(new BlockPos(4, 0, 0)).getX()) {
                walled[0] = true;
                fill(helper, 8, 1, 5, 8, 2, 11, Blocks.STONE.defaultBlockState());
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(walled[0], "the wall went up while it was walking");
            for (int x = 12; x <= 14; x++) {
                BlockPos crop = new BlockPos(x, 1, 8);
                helper.assertTrue(
                        helper.getBlockState(crop).equals(WHEAT.getStateForAge(0)),
                        "wheat at x=" + x + " harvested and reset, but it's " + helper.getBlockState(crop));
            }
        });
    }
}
