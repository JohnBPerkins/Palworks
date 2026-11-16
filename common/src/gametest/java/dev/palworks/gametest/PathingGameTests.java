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
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
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

    // A sweet berry bush hurts whatever moves through its block once it's past a seedling (age 1
    // on); it's ripe to pick, and so a job, at age 3. It has no collision at any age.
    private static final BlockState YOUNG_BUSH =
            Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 1);
    private static final BlockState RIPE_BUSH =
            Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3);
    /** Where the {@link #hedge} of bushes runs across the arena, and the middle of the gap in it. */
    private static final int HEDGE_X = 8;

    private static final int GAP_Z = 8;

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

    // ---- Tiles it fits on ----

    /** Blocks one on another from y=4 up, at a column of their own well off the ground. */
    private static void column(GameTestHelper helper, int x, int z, BlockState... fromY4) {
        for (int i = 0; i < fromY4.length; i++) {
            helper.setBlock(x, 4 + i, z, fromY4[i]);
        }
    }

    private static void assertFits(GameTestHelper helper, PokemonEntity mob, BlockPos tile, boolean fits, String what) {
        helper.assertTrue(
                WorkerPathfinder.fitsOn(mob).test(helper.absolutePos(tile)) == fits,
                what + (fits ? ": it fits there" : ": it doesn't fit there"));
    }

    /**
     * A tile is somewhere to stand by the floor in it, whatever that's made of. Plain floors (whole
     * blocks, farmland, slabs) are worked out from the blocks themselves and the rest by asking the
     * level for the shapes there: both have to come to the same thing.
     */
    @GameTest(template = Templates.ARENA, batch = "pathing_tiles", timeoutTicks = 200)
    public void aTileIsSomewhereToStandByTheFloorInIt(GameTestHelper helper) {
        Workers.isolate(helper);
        helper.setBlock(START.below(), Blocks.STONE);
        helper.setBlock(GOAL.below(), Blocks.STONE);
        // Bulbasaur: under a block wide and tall. Machamp: 1.2 wide (3 columns), 2.16 tall (3 rows).
        PokemonEntity small = Workers.sendOut(helper, BULBASAUR, START);
        PokemonEntity wide = Workers.sendOut(helper, MACHAMP, GOAL);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState bottomSlab = Blocks.STONE_SLAB.defaultBlockState();
        BlockState topSlab = bottomSlab.setValue(SlabBlock.TYPE, SlabType.TOP);
        BlockState snow = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3);

        column(helper, 1, 8, air, stone);
        column(helper, 3, 8, air, Blocks.FARMLAND.defaultBlockState());
        column(helper, 5, 8, air, bottomSlab);
        column(helper, 7, 8, air, topSlab);
        column(helper, 9, 8, stone, air);
        column(helper, 11, 8, stone, Blocks.WHITE_CARPET.defaultBlockState());
        column(helper, 13, 8, Blocks.OAK_FENCE.defaultBlockState(), air);
        column(helper, 15, 8, air, stone, Blocks.GLASS.defaultBlockState());
        column(helper, 1, 11, air, Blocks.OAK_STAIRS.defaultBlockState());
        column(helper, 3, 11, stone, snow);
        column(helper, 5, 11, air, stone, air, stone);
        column(helper, 9, 11, air, stone, topSlab);
        column(helper, 7, 11, air, stone, Blocks.CHEST.defaultBlockState());
        // For the wide one, 3x3 at y=5: all stone; stone under one edge only; stone with a block
        // at its head's height over one corner
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                helper.setBlock(2 + dx, 5, 14 + dz, stone);
                helper.setBlock(12 + dx, 5, 14 + dz, stone);
            }
        }
        helper.setBlock(8, 5, 14, stone);
        helper.setBlock(13, 8, 15, stone);
        // And one more 3x3, with a top slab over it that its head just clears
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                helper.setBlock(12 + dx, 5, 10 + dz, stone);
            }
        }
        helper.setBlock(12, 8, 10, topSlab);
        helper.setBlock(10, 6, 9, Blocks.OAK_FENCE); // a post beside it, in a column its edge is over
        helper.setBlock(4, 6, 14, stone); // beside the first 3x3, at its body's height

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            assertFits(helper, small, new BlockPos(1, 6, 8), true, "on a whole block");
            assertFits(helper, small, new BlockPos(1, 7, 8), false, "a block above a whole block's top");
            assertFits(helper, small, new BlockPos(1, 5, 8), false, "in a whole block");
            assertFits(helper, small, new BlockPos(3, 5, 8), false, "in farmland");
            assertFits(helper, small, new BlockPos(3, 6, 8), true, "on farmland");
            assertFits(helper, small, new BlockPos(5, 6, 8), true, "on a bottom slab");
            assertFits(helper, small, new BlockPos(7, 6, 8), true, "on a top slab");
            assertFits(helper, small, new BlockPos(9, 6, 8), false, "over a block's gap");
            assertFits(helper, small, new BlockPos(11, 5, 8), true, "on a carpet, in the carpet's block");
            assertFits(helper, small, new BlockPos(11, 6, 8), false, "in the block above a carpet");
            assertFits(helper, small, new BlockPos(13, 6, 8), true, "on a fence post, a block and a half tall");
            assertFits(helper, small, new BlockPos(13, 5, 8), false, "in the block a fence post reaches into");
            assertFits(helper, small, new BlockPos(15, 6, 8), false, "in a block of glass");
            assertFits(helper, small, new BlockPos(1, 6, 11), true, "on stairs");
            assertFits(helper, small, new BlockPos(3, 5, 11), true, "on layers of snow, in the snow's block");
            assertFits(helper, small, new BlockPos(3, 6, 11), false, "in the block above layers of snow");
            assertFits(helper, small, new BlockPos(5, 6, 11), true, "under a block over its head");
            assertFits(helper, small, new BlockPos(9, 6, 11), false, "under a top slab lower than it's tall");
            assertFits(helper, small, new BlockPos(7, 6, 11), false, "in a chest's block");
            assertFits(helper, small, new BlockPos(7, 7, 11), true, "on a chest");

            assertFits(helper, wide, new BlockPos(2, 6, 14), true, "a wide one on a floor under all of it");
            assertFits(helper, wide, new BlockPos(7, 6, 14), true, "a wide one with a ledge under one edge");
            assertFits(helper, wide, new BlockPos(6, 6, 14), false, "a wide one with no floor under any of it");
            assertFits(helper, wide, new BlockPos(12, 6, 14), false, "a wide one with a block where its head goes");
            assertFits(helper, wide, new BlockPos(12, 6, 10), true, "a wide one under a slab its head clears");
            assertFits(helper, wide, new BlockPos(11, 6, 10), true, "a wide one beside a post its body misses");
            assertFits(helper, wide, new BlockPos(3, 6, 14), false, "a wide one beside a block its body would be in");
            helper.succeed();
        });
    }

    // ---- Blocks that hurt ----

    /** A cactus, 2 high, on the sand it needs under it. */
    private static void cactus(GameTestHelper helper, int x, int z) {
        helper.setBlock(x, 0, z, Blocks.SAND);
        helper.setBlock(x, 1, z, Blocks.CACTUS);
        helper.setBlock(x, 2, z, Blocks.CACTUS);
    }

    /**
     * A hedge of young berry bushes across the arena at {@link #HEDGE_X}, running well past it to both
     * sides (further than a search looks round), with the tiles from {@code gapFrom} to
     * {@code gapTo} left open.
     */
    private static void hedge(GameTestHelper helper, int gapFrom, int gapTo) {
        for (int z = -8; z <= 24; z++) {
            if (z < gapFrom || z > gapTo) {
                helper.setBlock(HEDGE_X, 1, z, YOUNG_BUSH);
            }
        }
    }

    /** Fail the test the moment a Pokémon loses health, from a couple of ticks after it's sent out. */
    private static void neverHurt(GameTestHelper helper, PokemonEntity... mobs) {
        float[] health = new float[mobs.length];
        long from = helper.getTick() + 2;
        helper.onEachTick(() -> {
            for (int i = 0; i < mobs.length; i++) {
                if (helper.getTick() > from && mobs[i].getHealth() < health[i]) {
                    var source = mobs[i].getLastDamageSource();
                    helper.fail(mobs[i].getPokemon().getSpecies().getName() + " was hurt by "
                            + (source == null ? "something" : source.getMsgId()) + " at "
                            + mobs[i].position());
                }
                health[i] = mobs[i].getHealth();
            }
        });
    }

    /** Where a path's node {@code i} is, in the test's own coordinates. */
    private static BlockPos nodeRel(GameTestHelper helper, Path path, int i) {
        return path.getNode(i).asBlockPos().subtract(helper.absolutePos(BlockPos.ZERO));
    }

    @GameTest(template = Templates.ARENA, batch = "pathing_hurting_corners", timeoutTicks = 200)
    public void aWalkDoesntSlipBetweenBerryBushesThatTouchAtTheCorners(GameTestHelper helper) {
        // Bushes corner to corner across the arena. Nothing there stops a body, and no tile
        // between two of them has a bush in it, but a body going from one side to the other
        // passes through the corners of both
        Workers.isolate(helper);
        RealTreeGameTests.arenaFloor(helper, Blocks.GRASS_BLOCK);
        for (int i = -8; i <= 24; i++) {
            helper.setBlock(i, 1, i, YOUNG_BUSH);
        }
        helper.setBlock(7, 1, 7, Blocks.AIR);
        PokemonEntity worker = Workers.sendOut(helper, BULBASAUR, new BlockPos(4, 1, 10));
        BlockPos goal = new BlockPos(10, 1, 4);

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            assertReach(helper, worker, goal, Reach.YES, "through the tile with no bush in it");
            helper.setBlock(7, 1, 7, YOUNG_BUSH);
            assertReach(helper, worker, goal, Reach.NO, "between bushes that touch at their corners");
            helper.succeed();
        });
    }

    @GameTest(template = Templates.ARENA, batch = "pathing_hurting_gap", timeoutTicks = 200)
    public void aWalkLinesUpWithAGapBetweenBerryBushesBeforeGoingIn(GameTestHelper helper) {
        // A hedge of bushes with a gap a block wide, come to from off to one side. A mob following
        // a path turns for the next node before it's at this one, so a turn right at the gap's
        // mouth would brush the bush on the inside of it: the walk turns a tile back, and goes
        // through, and a tile on, in a straight line
        Workers.isolate(helper);
        RealTreeGameTests.arenaFloor(helper, Blocks.GRASS_BLOCK);
        hedge(helper, GAP_Z, GAP_Z);
        PokemonEntity worker = Workers.sendOut(helper, BULBASAUR, new BlockPos(3, 1, 4));
        BlockPos goal = new BlockPos(13, 1, 4);

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            WorkerPathfinder.Result result = WorkerPathfinder.find(worker, helper.absolutePos(goal));
            helper.assertTrue(result.reach() == Reach.YES, "it gets through the gap: " + result.reach());
            Path path = result.path();
            int inGap = -1;
            for (int i = 0; i < path.getNodeCount(); i++) {
                BlockPos node = nodeRel(helper, path, i);
                if (node.getX() == HEDGE_X) {
                    helper.assertTrue(
                            node.getZ() == GAP_Z, "it crosses the hedge at the gap, not through a bush at " + node);
                    inGap = i;
                }
            }
            helper.assertTrue(inGap >= 2 && inGap + 2 < path.getNodeCount(), "the path goes through the gap");
            for (int i = inGap - 2; i <= inGap + 2; i++) {
                BlockPos node = nodeRel(helper, path, i);
                helper.assertTrue(
                        node.getZ() == GAP_Z && node.getX() == HEDGE_X + i - inGap,
                        "it's in line with the gap from 2 tiles before it to 2 after, but goes by " + node);
            }
            helper.succeed();
        });
    }

    @GameTest(template = Templates.ARENA, batch = "pathing_hurting_tiles", timeoutTicks = 200)
    public void aTileInABlockThatHurtsIsNowhereToStand(GameTestHelper helper) {
        Workers.isolate(helper);
        RealTreeGameTests.arenaFloor(helper, Blocks.GRASS_BLOCK);
        PokemonEntity small = Workers.sendOut(helper, BULBASAUR, new BlockPos(2, 1, 2));
        PokemonEntity wide = Workers.sendOut(helper, MACHAMP, new BlockPos(2, 1, 13));
        helper.setBlock(8, 1, 8, YOUNG_BUSH);
        cactus(helper, 8, 12);
        // The same, half a block up: the body on a slab next to the bush is in the bush's block,
        // on a whole block next to it it's over the bush
        column(helper, 4, 4, Blocks.STONE.defaultBlockState(), bottomSlab());
        column(helper, 5, 4, Blocks.DIRT.defaultBlockState(), YOUNG_BUSH);
        column(helper, 4, 8, Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState());
        column(helper, 5, 8, Blocks.DIRT.defaultBlockState(), YOUNG_BUSH);
        // And on a floor that isn't a plain one: a carpet
        helper.setBlock(12, 1, 3, YOUNG_BUSH);
        helper.setBlock(13, 1, 3, Blocks.WHITE_CARPET);
        helper.setBlock(13, 1, 5, Blocks.WHITE_CARPET);

        helper.runAfterDelay(2, () -> {
            assertFits(helper, small, new BlockPos(8, 1, 8), false, "in a berry bush");
            assertFits(
                    helper, small, new BlockPos(9, 1, 8), true, "next to the bush, for a body that stays in its tile");
            assertFits(
                    helper,
                    wide,
                    new BlockPos(9, 1, 8),
                    false,
                    "next to the bush, for a 1.2-wide body that reaches into it");
            assertFits(helper, wide, new BlockPos(10, 1, 8), true, "a tile further from the bush");
            assertFits(
                    helper, small, new BlockPos(9, 1, 12), true, "next to a cactus, for a body that stays in its tile");
            assertFits(helper, wide, new BlockPos(9, 1, 12), false, "next to a cactus, for a body that reaches it");
            assertFits(helper, wide, new BlockPos(13, 1, 3), false, "on a carpet next to a bush, reaching into it");
            assertFits(helper, wide, new BlockPos(13, 1, 5), true, "on a carpet a tile further from the bush");
            assertFits(
                    helper,
                    wide,
                    new BlockPos(4, 6, 4),
                    false,
                    "on a slab beside a bush, its feet in the bush's block");
            assertFits(helper, wide, new BlockPos(4, 6, 8), true, "on a whole block beside a bush, over it");
            helper.succeed();
        });
    }

    @GameTest(template = Templates.ARENA, batch = "pathing_hurting_patch", timeoutTicks = 3000)
    public void aWorkerPicksAWholeBerryPatchUnhurt(GameTestHelper helper) {
        // 5x5 ripe bushes. It picks from 2 to 4 tiles off, so the ones in the middle are in reach
        // from outside the patch: it never stands in a bush (a tile in one used to be the nearest
        // "free" tile, which it then couldn't path to, and the middle of a patch was never picked)
        Workers.isolate(helper);
        RealTreeGameTests.arenaFloor(helper, Blocks.GRASS_BLOCK);
        fill(helper, 6, 1, 6, 10, 1, 10, RIPE_BUSH);
        PokemonEntity worker =
                Workers.sendOut(helper, Workers.owner(helper, new BlockPos(0, 1, 0)), BULBASAUR, new BlockPos(2, 1, 8));
        neverHurt(helper, worker);
        helper.succeedWhen(() -> {
            for (int x = 6; x <= 10; x++) {
                for (int z = 6; z <= 10; z++) {
                    BlockState bush = helper.getBlockState(new BlockPos(x, 1, z));
                    helper.assertTrue(
                            bush.is(Blocks.SWEET_BERRY_BUSH) && bush.getValue(SweetBerryBushBlock.AGE) < 3,
                            "the bush at " + x + ", " + z + " picked, but it's " + bush);
                }
            }
        });
    }
}
