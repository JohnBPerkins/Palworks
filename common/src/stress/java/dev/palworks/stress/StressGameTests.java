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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The stress test: 200 workers of many species and sizes, with their work coming back as fast as
 * they do it, and the server's tick times measured with spark. Two scenarios, each its own run
 * ({@code -Dpalworks.stress.scenario}; {@code ./gradlew :fabric:runStressTest} runs both, one after
 * the other), never with the in-game tests, so nothing else adds to the load:
 * <ul>
 *   <li>{@code dense}: all 200 on one packed 48x48 farm, every worker in range of every job: the
 *       most work per job handed out, and the most Pokémon around each one;</li>
 *   <li>{@code spread}: 40 small bases of 5, each its own player's, 96 blocks apart (out of each
 *       other's range): a server's many players, each with a base, across hundreds of chunks.</li>
 * </ul>
 * Each party mixes species, from Sunkern (0.4 blocks wide) to Torterra (2.25), walkers and flyers,
 * as a real farm would: anything worked out per species or per body size gets no easy ride.
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
    /** Bump when a scenario changes, so CI doesn't compare runs of different scenarios. */
    static final int SCENARIO_VERSION = 2;

    static final String DENSE = "dense";
    static final String SPREAD = "spread";

    private static final int WARMUP_TICKS = 600; // 30 s
    private static final long MEASURE_NANOS = 60_000_000_000L; // a minute: spark's one-minute window
    private static final int REGROW_INTERVAL = 100; // how often harvested, chopped or emptied work comes back
    // How often the work done is counted. Done work stays done until it's regrown, so counting just
    // before each regrow (this divides REGROW_INTERVAL) misses none, and costs less than every tick.
    private static final int COUNT_INTERVAL = 20;
    private static final int MIN_HARVESTS = 50; // fewer while measuring, and the workers are broken
    private static final long PROFILE_SAVE_WAIT_NANOS = 30_000_000_000L; // most to wait for spark to save

    // Every job's workers, small to large (hitbox width): each party takes the next ones round
    private static final List<String> HARVESTERS = List.of( // Grass: harvest, plant
            "sunkern", // 0.4
            "oddish", // 0.6
            "bulbasaur", // 0.7
            "chikorita", // 0.7
            "exeggutor", // 1.8, 3 tall
            "venusaur", // 2.2
            "tropius", // 2.2, 5 tall, flies
            "torterra"); // 2.25
    private static final List<String> CHOPPERS = List.of( // Lumbering, by type or species, and no other work
            "tyrogue", // 0.3
            "riolu", // 0.6
            "machop", // 0.7
            "lucario", // 0.8
            "machamp", // 1.0
            "scyther", // 1.2, flies
            "bewear", // 1.2, 2.8 tall
            "conkeldurr"); // 1.5
    private static final List<String> KINDLERS = List.of( // Fire: light furnaces
            "torchic", // 0.7
            "magmar", // 0.7
            "charmander", // 0.8
            "cyndaquil", // 0.8
            "ponyta", // 0.8
            "growlithe", // 1.1
            "arcanine", // 1.2
            "charizard"); // 1.5, flies
    private static final List<String> CARRIERS = List.of( // Flying: pick up, deliver
            "pidgey", // 0.5
            "starly", // 0.6
            "natu", // 0.6, walks
            "hoothoot", // 0.7
            "xatu", // 0.7
            "pidgeot", // 0.75
            "staravia", // 1.0
            "fearow"); // 1.35

    private static final CropBlock WHEAT = (CropBlock) Blocks.WHEAT;
    private static final int TREE_HEIGHT = 4;

    // Dense: the farm template's width and depth
    private static final int SIZE = 48;

    // Spread: a grid of bases, far enough apart that no worker is in range of another base's work
    // (32 blocks, and the bases are 20 across)
    private static final int BASES_X = 8;
    private static final int BASES_Z = 5;
    private static final int BASE_SPACING = 96;
    private static final int BASE_SIZE = 20;
    /** Flat ground cleared around each base, so a worker stepping off its edge isn't on a ledge. */
    private static final int BASE_MARGIN = 6;
    /** Blocks around each base kept entity-ticking (only the test's own area is, otherwise). */
    private static final int TICKING_MARGIN = 16;

    private static final int CLEAR_HEIGHT = 12;

    /** What a scenario built: where its work is (relative positions), and how many workers it has. */
    private record Farm(
            String scenario,
            int workers,
            List<BlockPos> crops,
            List<BlockPos> trees,
            List<BlockPos> furnaces,
            List<BlockPos> chests) {}

    /** Hands out a job's species in turn, so parties and neighbours mix them. */
    private static final class Roster {
        private final List<String> species;
        private int next;

        Roster(List<String> species) {
            this.species = species;
        }

        /** The next {@code count} workers' properties. */
        List<String> take(int count) {
            List<String> taken = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                taken.add(species.get(next % species.size()) + " level=20");
                next++;
            }
            return taken;
        }
    }

    // Ends itself after the measured minute; the timeout only catches a hang
    @GameTest(template = "palworks_stress:farm", batch = "stress", timeoutTicks = 200_000)
    public void stressTest(GameTestHelper helper) {
        String scenario = System.getProperty("palworks.stress.scenario", DENSE);
        // No wild Pokémon or mobs spawning round the mock players: load that differs run to run
        command(helper, "gamerule doPokemonSpawning false");
        command(helper, "gamerule doMobSpawning false");
        Workers.isolate(helper);
        Farm farm = switch (scenario) {
            case DENSE -> denseFarm(helper);
            case SPREAD -> spreadOutBases(helper);
            default -> throw new IllegalArgumentException("there's no stress scenario called " + scenario);
        };
        measure(helper, farm);
    }

    /** 200 workers on one 48x48 farm: four 14x10 wheat fields (560 crops), 16 trees, 14 furnaces, 8 chests. */
    private static Farm denseFarm(GameTestHelper helper) {
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                helper.setBlock(x, 0, z, Blocks.GRASS_BLOCK);
            }
        }

        // Four wheat fields, one in each corner
        List<BlockPos> crops = new ArrayList<>();
        for (int[] corner : new int[][] {{2, 2}, {32, 2}, {2, 36}, {32, 36}}) {
            for (int dx = 0; dx < 14; dx++) {
                for (int dz = 0; dz < 10; dz++) {
                    crops.add(crop(helper, corner[0] + dx, corner[1] + dz));
                }
            }
        }
        // Between the fields: chests, a row of furnaces, two rows of trees, chests
        List<BlockPos> chests = new ArrayList<>();
        for (int x = 18; x <= 30; x += 4) {
            chests.add(chest(helper, x, 13));
            chests.add(chest(helper, x, 34));
        }
        List<BlockPos> furnaces = new ArrayList<>();
        for (int x = 4; x <= 43; x += 3) {
            furnaces.add(furnace(helper, x, 17));
        }
        List<BlockPos> trees = new ArrayList<>();
        for (int z : new int[] {22, 29}) {
            for (int x = 3; x < SIZE; x += 6) {
                trees.add(tree(helper, x, z));
            }
        }

        // 200 workers in parties of 5 or 6 (Cobblemon's most), each owner standing by its party's work
        Roster harvesters = new Roster(HARVESTERS);
        Roster choppers = new Roster(CHOPPERS);
        Roster kindlers = new Roster(KINDLERS);
        Roster carriers = new Roster(CARRIERS);
        int workers = 0;
        for (int[] spot : new int[][] {
            {4, 12}, {8, 12}, {12, 12}, {15, 12}, {33, 12}, {37, 12}, {41, 12}, {44, 12}, {4, 35}, {8, 35}, {12, 35},
            {15, 35}, {33, 35}, {37, 35}, {41, 35}, {44, 35}
        }) {
            workers += sendOutParty(helper, harvesters.take(5), spot[0], spot[1]); // 80, by the fields
        }
        for (int x = 3; x < SIZE; x += 6) {
            workers += sendOutParty(helper, choppers.take(5), x, 25); // 40, between the trees
        }
        for (int x = 6; x <= 38; x += 8) {
            workers += sendOutParty(helper, kindlers.take(6), x, 15); // 30, by the furnaces
            workers += sendOutParty(helper, carriers.take(5), x, 19); // 50, all over
            workers += sendOutParty(helper, carriers.take(5), x, 32);
        }
        return new Farm(DENSE, workers, crops, trees, furnaces, chests);
    }

    /**
     * 40 bases of 5 workers, one player's each: an 8x6 wheat field (48 crops), 2 trees, a furnace and
     * a chest, with 2 harvesters, a chopper, a kindler and a carrier. 200 workers, 1920 crops.
     */
    private static Farm spreadOutBases(GameTestHelper helper) {
        Roster harvesters = new Roster(HARVESTERS);
        Roster choppers = new Roster(CHOPPERS);
        Roster kindlers = new Roster(KINDLERS);
        Roster carriers = new Roster(CARRIERS);
        List<BlockPos> crops = new ArrayList<>();
        List<BlockPos> trees = new ArrayList<>();
        List<BlockPos> furnaces = new ArrayList<>();
        List<BlockPos> chests = new ArrayList<>();
        int workers = 0;
        for (int bx = 0; bx < BASES_X; bx++) {
            for (int bz = 0; bz < BASES_Z; bz++) {
                int ox = bx * BASE_SPACING;
                int oz = bz * BASE_SPACING;
                clearBase(helper, ox, oz);
                for (int dx = 0; dx < 8; dx++) {
                    for (int dz = 0; dz < 6; dz++) {
                        crops.add(crop(helper, ox + 2 + dx, oz + 2 + dz));
                    }
                }
                trees.add(tree(helper, ox + 15, oz + 4));
                trees.add(tree(helper, ox + 15, oz + 12));
                furnaces.add(furnace(helper, ox + 4, oz + 11));
                chests.add(chest(helper, ox + 8, oz + 11));

                List<String> party = new ArrayList<>(harvesters.take(2));
                party.addAll(choppers.take(1));
                party.addAll(kindlers.take(1));
                party.addAll(carriers.take(1));
                workers += sendOutParty(helper, party, ox + 6, oz + 9);
            }
        }
        return new Farm(SPREAD, workers, crops, trees, furnaces, chests);
    }

    /**
     * Flat grass and nothing above it where a base goes, and its chunks kept ticking: the test
     * server only does that for the test's own area, and mock players don't load chunks.
     */
    private static void clearBase(GameTestHelper helper, int ox, int oz) {
        ServerLevel level = helper.getLevel();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = -BASE_MARGIN; x < BASE_SIZE + BASE_MARGIN; x++) {
            for (int z = -BASE_MARGIN; z < BASE_SIZE + BASE_MARGIN; z++) {
                for (int y = 0; y <= CLEAR_HEIGHT; y++) {
                    pos.set(helper.absolutePos(new BlockPos(ox + x, y, oz + z)));
                    BlockState want = y == 0 ? grass : air;
                    if (!level.getBlockState(pos).equals(want)) {
                        level.setBlock(pos, want, 2);
                    }
                }
            }
        }
        BlockPos min = helper.absolutePos(new BlockPos(ox - TICKING_MARGIN, 0, oz - TICKING_MARGIN));
        BlockPos max =
                helper.absolutePos(new BlockPos(ox + BASE_SIZE + TICKING_MARGIN, 0, oz + BASE_SIZE + TICKING_MARGIN));
        for (int cx = Math.min(min.getX(), max.getX()) >> 4; cx <= Math.max(min.getX(), max.getX()) >> 4; cx++) {
            for (int cz = Math.min(min.getZ(), max.getZ()) >> 4; cz <= Math.max(min.getZ(), max.getZ()) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
    }

    private static BlockPos crop(GameTestHelper helper, int x, int z) {
        BlockPos crop = new BlockPos(x, 1, z);
        helper.setBlock(crop.below(), Blocks.FARMLAND);
        helper.setBlock(crop, WHEAT.getStateForAge(7));
        return crop;
    }

    private static BlockPos chest(GameTestHelper helper, int x, int z) {
        BlockPos chest = new BlockPos(x, 1, z);
        helper.setBlock(chest, Blocks.CHEST);
        return chest;
    }

    private static BlockPos furnace(GameTestHelper helper, int x, int z) {
        BlockPos furnace = new BlockPos(x, 1, z);
        helper.setBlock(
                furnace, Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.NORTH));
        return furnace;
    }

    private static BlockPos tree(GameTestHelper helper, int x, int z) {
        Scenes.tree(helper, x, z, TREE_HEIGHT, Blocks.OAK_LOG, Scenes.naturalLeaves(Blocks.OAK_LEAVES));
        return new BlockPos(x, 1, z);
    }

    /** Measure a minute of the farm at work, after the warmup, and report it. */
    private static void measure(GameTestHelper helper, Farm farm) {
        List<BlockPos> crops = farm.crops();
        List<BlockPos> trees = farm.trees();
        int[] harvests = {0};
        int[] chops = {0};
        int[] ticks = {0};
        boolean[] measuring = {false};
        long[] measureStart = {0};
        long[] stopping = {0};
        boolean[] wasRipe = new boolean[crops.size()];
        boolean[] hadTrunk = new boolean[trees.size()];
        helper.onEachTick(() -> {
            long time = helper.getLevel().getGameTime();
            // Count the work done while measuring: a crop going from ripe to replanted, a trunk going
            if (time % COUNT_INTERVAL == 0) {
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
            }
            if (time % REGROW_INTERVAL == 0) {
                regrow(helper, farm);
            }
            if (measuring[0]) {
                ticks[0]++;
                if (System.nanoTime() - measureStart[0] >= MEASURE_NANOS) {
                    measuring[0] = false;
                    report(helper, farm, ticks[0], harvests[0], chops[0]);
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

    /** Send out one owner's party (6 at most, as in Cobblemon) at a spot. Returns how many. */
    private static int sendOutParty(GameTestHelper helper, List<String> party, int x, int z) {
        BlockPos at = new BlockPos(x, 1, z);
        ServerPlayer owner = Workers.owner(helper, at);
        for (String properties : party) {
            Workers.sendOut(helper, owner, properties, at);
        }
        return party.size();
    }

    /** Bring back the work that's been done, so there's always plenty. */
    private static void regrow(GameTestHelper helper, Farm farm) {
        for (BlockPos crop : farm.crops()) {
            if (!helper.getBlockState(crop).equals(WHEAT.getStateForAge(7))) {
                helper.setBlock(crop.below(), Blocks.FARMLAND);
                helper.setBlock(crop, WHEAT.getStateForAge(7));
            }
        }
        for (BlockPos tree : farm.trees()) {
            if (!helper.getBlockState(tree).is(Blocks.OAK_LOG)) {
                tree(helper, tree.getX(), tree.getZ());
            }
        }
        for (BlockPos pos : farm.furnaces()) {
            if (helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof AbstractFurnaceBlockEntity furnace
                    && furnace.getItem(0).isEmpty()) {
                furnace.setItem(0, new ItemStack(Items.RAW_IRON, 1));
            }
        }
        for (BlockPos pos : farm.chests()) {
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
    private static void report(GameTestHelper helper, Farm farm, int ticks, int harvests, int chops) {
        Spark spark = SparkProvider.get();
        DoubleAverageInfo mspt =
                spark.mspt() == null ? null : spark.mspt().poll(StatisticWindow.MillisPerTick.MINUTES_1);
        helper.assertTrue(mspt != null, "spark has no tick times on this loader");
        double tps = spark.tps().poll(StatisticWindow.TicksPerSecond.MINUTES_1);
        String json = String.format(
                Locale.ROOT,
                "{%n  \"scenario\": \"%s\",%n  \"version\": %d,%n  \"loader\": \"%s\",%n  \"workers\": %d,%n"
                        + "  \"ticks\": %d,%n"
                        + "  \"mspt\": {\"mean\": %.3f, \"median\": %.3f, \"p95\": %.3f, \"max\": %.3f},%n"
                        + "  \"unpaced_tps\": %.1f,%n  \"harvests\": %d,%n  \"chops\": %d%n}%n",
                farm.scenario(),
                SCENARIO_VERSION,
                Platform.isFabric() ? "fabric" : "neoforge",
                farm.workers(),
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
