package dev.palworks.stress;

import dev.architectury.platform.Platform;
import dev.palworks.Palworks;
import dev.palworks.gametest.Scenes;
import dev.palworks.gametest.Workers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * The stress test: 200 workers of every kind on a big farm, with their work coming back as
 * fast as they do it, and the server's tick times measured with spark. Run on its own
 * ({@code ./gradlew :fabric:runStressTest} / {@code :neoforge:runStressTest}), never with the
 * in-game tests, so nothing else adds to the load.
 *
 * <p>After {@link #WARMUP_TICKS} to settle (workers sent out, the first scans done), it measures for
 * a minute of real time, with spark profiling, then writes spark's milliseconds per tick (MSPT) for
 * that minute, and how much work got done, to the JSON file in {@code -Dpalworks.stress.report}.
 * A GameTest server doesn't wait between ticks (it runs them back to back), so the minute is
 * thousands of ticks, and its "TPS" is how many it can manage: headroom, not the 20 a real server
 * aims for. MSPT, the time each tick takes, means the same either way.
 * CI compares that with the same run on main (.github/scripts/compare_stress.py). The test only
 * fails if the workers barely worked: then the timings would say nothing about Palworks.
 */
public class StressGameTests {
    /** Bump when the scenario changes, so CI doesn't compare runs of different scenarios. */
    static final int SCENARIO_VERSION = 1;

    private static final int SIZE = 48; // the farm template's width and depth
    private static final int WARMUP_TICKS = 600; // 30 s
    private static final long MEASURE_NANOS = 60_000_000_000L; // a minute: spark's one-minute window
    private static final int REGROW_INTERVAL = 100; // how often harvested, chopped or emptied work comes back
    private static final int MIN_HARVESTS = 50; // fewer while measuring, and the workers are broken
    private static final long PROFILE_SAVE_WAIT_NANOS = 30_000_000_000L; // most to wait for spark to save

    private static final String BULBASAUR = "bulbasaur level=20"; // Grass: harvest, plant
    private static final String MACHOP = "machop level=20"; // Fighting: chop
    private static final String CHARMANDER = "charmander level=20"; // Fire: light furnaces
    private static final String PIDGEY = "pidgey level=20"; // Flying: pick up, deliver

    private static final CropBlock WHEAT = (CropBlock) Blocks.WHEAT;
    private static final int TREE_HEIGHT = 4;

    // Ends itself after the measured minute; the timeout only catches a hang
    @GameTest(template = "palworks_stress:farm", batch = "stress", timeoutTicks = 200_000)
    public void twoHundredWorkersOnABigFarm(GameTestHelper helper) {
        Workers.isolate(helper);
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                helper.setBlock(x, 0, z, Blocks.GRASS_BLOCK);
            }
        }

        // Four wheat fields, one in each corner: 560 crops
        List<BlockPos> crops = new ArrayList<>();
        for (int[] corner : new int[][] {{2, 2}, {32, 2}, {2, 36}, {32, 36}}) {
            for (int dx = 0; dx < 14; dx++) {
                for (int dz = 0; dz < 10; dz++) {
                    BlockPos crop = new BlockPos(corner[0] + dx, 1, corner[1] + dz);
                    helper.setBlock(crop.below(), Blocks.FARMLAND);
                    helper.setBlock(crop, WHEAT.getStateForAge(7));
                    crops.add(crop);
                }
            }
        }
        // Between the fields: chests, a row of furnaces, two rows of trees, chests
        List<BlockPos> chests = new ArrayList<>();
        for (int x = 18; x <= 30; x += 4) {
            chests.add(new BlockPos(x, 1, 13));
            chests.add(new BlockPos(x, 1, 34));
        }
        for (BlockPos chest : chests) {
            helper.setBlock(chest, Blocks.CHEST);
        }
        List<BlockPos> furnaces = new ArrayList<>();
        for (int x = 4; x <= 43; x += 3) {
            BlockPos furnace = new BlockPos(x, 1, 17);
            helper.setBlock(
                    furnace, Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.NORTH));
            furnaces.add(furnace);
        }
        List<BlockPos> trees = new ArrayList<>();
        for (int z : new int[] {22, 29}) {
            for (int x = 3; x < SIZE; x += 6) {
                trees.add(new BlockPos(x, 1, z));
                Scenes.tree(helper, x, z, TREE_HEIGHT, Blocks.OAK_LOG, Scenes.naturalLeaves(Blocks.OAK_LEAVES));
            }
        }

        // 200 workers in parties of 5 or 6 (Cobblemon's most), each owner standing by its party's work
        int workers = 0;
        for (int[] spot : new int[][] {
            {4, 12}, {8, 12}, {12, 12}, {15, 12}, {33, 12}, {37, 12}, {41, 12}, {44, 12}, {4, 35}, {8, 35}, {12, 35},
            {15, 35}, {33, 35}, {37, 35}, {41, 35}, {44, 35}
        }) {
            workers += sendOutParty(helper, BULBASAUR, 5, spot[0], spot[1]); // 80, by the fields
        }
        for (int x = 3; x < SIZE; x += 6) {
            workers += sendOutParty(helper, MACHOP, 5, x, 25); // 40, between the trees
        }
        for (int x = 6; x <= 38; x += 8) {
            workers += sendOutParty(helper, CHARMANDER, 6, x, 15); // 30, by the furnaces
            workers += sendOutParty(helper, PIDGEY, 5, x, 19); // 50, all over
            workers += sendOutParty(helper, PIDGEY, 5, x, 32);
        }
        int workerCount = workers;

        int[] harvests = {0};
        int[] chops = {0};
        int[] ticks = {0};
        boolean[] measuring = {false};
        long[] measureStart = {0};
        long[] stopping = {0};
        boolean[] wasRipe = new boolean[crops.size()];
        boolean[] hadTrunk = new boolean[trees.size()];
        helper.onEachTick(() -> {
            // Count the work done while measuring: a crop going from ripe to replanted, a trunk going
            for (int i = 0; i < crops.size(); i++) {
                boolean ripe = helper.getBlockState(crops.get(i)).equals(WHEAT.getStateForAge(7));
                if (measuring[0] && wasRipe[i] && !ripe) {
                    harvests[0]++;
                }
                wasRipe[i] = ripe;
            }
            for (int i = 0; i < trees.size(); i++) {
                boolean trunk = helper.getBlockState(trees.get(i)).is(Blocks.OAK_LOG);
                if (measuring[0] && hadTrunk[i] && !trunk) {
                    chops[0]++;
                }
                hadTrunk[i] = trunk;
            }
            if (helper.getLevel().getGameTime() % REGROW_INTERVAL == 0) {
                regrow(helper, crops, trees, furnaces, chests);
            }
            if (measuring[0]) {
                ticks[0]++;
                if (System.nanoTime() - measureStart[0] >= MEASURE_NANOS) {
                    measuring[0] = false;
                    report(helper, workerCount, ticks[0], harvests[0], chops[0]);
                    helper.assertTrue(
                            harvests[0] >= MIN_HARVESTS,
                            "the workers barely worked (" + harvests[0] + " harvests in " + ticks[0]
                                    + " ticks), so the timings mean nothing");
                    // Saved to config/spark in the run directory, for CI to keep (never uploaded:
                    // the run sets spark's upload address to nowhere)
                    command(helper, "spark profiler stop --save-to-file");
                    stopping[0] = System.nanoTime();
                }
            } else if (stopping[0] != 0
                    && (profileSaved() || System.nanoTime() - stopping[0] > PROFILE_SAVE_WAIT_NANOS)) {
                // Done once the profile's on disk (spark writes it in the background)
                stopping[0] = 0;
                helper.succeed();
            }
        });

        helper.runAfterDelay(WARMUP_TICKS, () -> {
            measuring[0] = true;
            measureStart[0] = System.nanoTime();
            command(helper, "spark profiler start");
        });
    }

    /** Whether spark has written a profile to its folder in the run directory. */
    private static boolean profileSaved() {
        Path folder = Path.of("config", "spark");
        if (!Files.isDirectory(folder)) {
            return false;
        }
        try (var files = Files.walk(folder)) {
            return files.anyMatch(file -> file.toString().endsWith(".sparkprofile"));
        } catch (IOException e) {
            return false;
        }
    }

    /** Send out {@code count} of a species, one owner's party, at a spot. Returns how many. */
    private static int sendOutParty(GameTestHelper helper, String species, int count, int x, int z) {
        BlockPos at = new BlockPos(x, 1, z);
        ServerPlayer owner = Workers.owner(helper, at);
        for (int i = 0; i < count; i++) {
            Workers.sendOut(helper, owner, species, at);
        }
        return count;
    }

    /** Bring back the work that's been done, so there's always plenty. */
    private static void regrow(
            GameTestHelper helper,
            List<BlockPos> crops,
            List<BlockPos> trees,
            List<BlockPos> furnaces,
            List<BlockPos> chests) {
        for (BlockPos crop : crops) {
            if (!helper.getBlockState(crop).equals(WHEAT.getStateForAge(7))) {
                helper.setBlock(crop.below(), Blocks.FARMLAND);
                helper.setBlock(crop, WHEAT.getStateForAge(7));
            }
        }
        for (BlockPos tree : trees) {
            if (!helper.getBlockState(tree).is(Blocks.OAK_LOG)) {
                Scenes.tree(
                        helper,
                        tree.getX(),
                        tree.getZ(),
                        TREE_HEIGHT,
                        Blocks.OAK_LOG,
                        Scenes.naturalLeaves(Blocks.OAK_LEAVES));
            }
        }
        for (BlockPos pos : furnaces) {
            if (helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof AbstractFurnaceBlockEntity furnace
                    && furnace.getItem(0).isEmpty()) {
                furnace.setItem(0, new ItemStack(Items.RAW_IRON, 1));
            }
        }
        for (BlockPos pos : chests) {
            if (helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof ChestBlockEntity chest) {
                chest.clearContent();
            }
        }
    }

    private static void command(GameTestHelper helper, String command) {
        MinecraftServer server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    /** Write spark's timings for the measured minute, and the work done, to the report file. */
    private static void report(GameTestHelper helper, int workers, int ticks, int harvests, int chops) {
        Spark spark = SparkProvider.get();
        DoubleAverageInfo mspt =
                spark.mspt() == null ? null : spark.mspt().poll(StatisticWindow.MillisPerTick.MINUTES_1);
        helper.assertTrue(mspt != null, "spark has no tick times on this loader");
        double tps = spark.tps().poll(StatisticWindow.TicksPerSecond.MINUTES_1);
        String json = String.format(
                Locale.ROOT,
                "{%n  \"scenario\": %d,%n  \"loader\": \"%s\",%n  \"workers\": %d,%n  \"ticks\": %d,%n"
                        + "  \"mspt\": {\"mean\": %.3f, \"median\": %.3f, \"p95\": %.3f, \"max\": %.3f},%n"
                        + "  \"unpaced_tps\": %.1f,%n  \"harvests\": %d,%n  \"chops\": %d%n}%n",
                SCENARIO_VERSION,
                Platform.isFabric() ? "fabric" : "neoforge",
                workers,
                ticks,
                mspt.mean(),
                mspt.median(),
                mspt.percentile95th(),
                mspt.max(),
                tps,
                harvests,
                chops);
        Palworks.LOGGER.info("Stress test results:\n{}", json);
        String path = System.getProperty("palworks.stress.report");
        if (path != null) {
            try {
                Path file = Path.of(path);
                Files.createDirectories(file.getParent());
                Files.writeString(file, json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("couldn't write the stress report to " + path, e);
            }
        }
    }
}
