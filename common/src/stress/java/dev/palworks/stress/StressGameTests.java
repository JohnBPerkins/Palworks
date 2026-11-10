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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * The stress test: about 50 workers of every kind on a big farm, with their work coming back as
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
    public void fiftyWorkersOnABigFarm(GameTestHelper helper) {
        Workers.isolate(helper);
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                helper.setBlock(x, 0, z, Blocks.GRASS_BLOCK);
            }
        }

        // Four wheat fields, one in each corner: 200 crops
        List<BlockPos> crops = new ArrayList<>();
        for (int[] corner : new int[][] {{3, 3}, {35, 3}, {3, 40}, {35, 40}}) {
            for (int dx = 0; dx < 10; dx++) {
                for (int dz = 0; dz < 5; dz++) {
                    BlockPos crop = new BlockPos(corner[0] + dx, 1, corner[1] + dz);
                    helper.setBlock(crop.below(), Blocks.FARMLAND);
                    helper.setBlock(crop, WHEAT.getStateForAge(7));
                    crops.add(crop);
                }
            }
        }
        // A row of trees across the middle, a row of furnaces in front of them, chests between
        List<BlockPos> trees = new ArrayList<>();
        for (int x = 3; x < SIZE; x += 6) {
            trees.add(new BlockPos(x, 1, 26));
            Scenes.tree(helper, x, 26, TREE_HEIGHT, Blocks.OAK_LOG, Scenes.naturalLeaves(Blocks.OAK_LEAVES));
        }
        List<BlockPos> furnaces = new ArrayList<>();
        for (int x = 8; x <= 36; x += 4) {
            BlockPos furnace = new BlockPos(x, 1, 18);
            helper.setBlock(
                    furnace, Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.NORTH));
            furnaces.add(furnace);
        }
        List<BlockPos> chests = List.of(
                new BlockPos(20, 1, 10), new BlockPos(27, 1, 10), new BlockPos(20, 1, 36), new BlockPos(27, 1, 36));
        for (BlockPos chest : chests) {
            helper.setBlock(chest, Blocks.CHEST);
        }

        // 50 workers, each with a (mock) owner where it's sent out, near the work it does
        sendOutRow(helper, BULBASAUR, 5, 3, 2);
        sendOutRow(helper, BULBASAUR, 5, 35, 2);
        sendOutRow(helper, BULBASAUR, 5, 3, 46);
        sendOutRow(helper, BULBASAUR, 5, 35, 46);
        sendOutRow(helper, MACHOP, 10, 3, 22);
        sendOutRow(helper, CHARMANDER, 8, 8, 15);
        sendOutRow(helper, PIDGEY, 6, 12, 13);
        sendOutRow(helper, PIDGEY, 6, 12, 33);

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
                    report(helper, ticks[0], harvests[0], chops[0]);
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

    /** Send out {@code count} of a species in a row along x from {@code x}, 4 blocks apart (2 for 5 or fewer). */
    private static void sendOutRow(GameTestHelper helper, String species, int count, int x, int z) {
        int step = count <= 5 ? 2 : 4;
        for (int i = 0; i < count; i++) {
            Workers.sendOut(helper, species, new BlockPos(Math.min(SIZE - 2, x + i * step), 1, z));
        }
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
    private static void report(GameTestHelper helper, int ticks, int harvests, int chops) {
        Spark spark = SparkProvider.get();
        DoubleAverageInfo mspt =
                spark.mspt() == null ? null : spark.mspt().poll(StatisticWindow.MillisPerTick.MINUTES_1);
        helper.assertTrue(mspt != null, "spark has no tick times on this loader");
        double tps = spark.tps().poll(StatisticWindow.TicksPerSecond.MINUTES_1);
        String json = String.format(
                Locale.ROOT,
                "{%n  \"scenario\": %d,%n  \"loader\": \"%s\",%n  \"workers\": 50,%n  \"ticks\": %d,%n"
                        + "  \"mspt\": {\"mean\": %.3f, \"median\": %.3f, \"p95\": %.3f, \"max\": %.3f},%n"
                        + "  \"unpaced_tps\": %.1f,%n  \"harvests\": %d,%n  \"chops\": %d%n}%n",
                SCENARIO_VERSION,
                Platform.isFabric() ? "fabric" : "neoforge",
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
