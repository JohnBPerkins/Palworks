package dev.palworks.debug;

import com.cobblemon.mod.common.block.ApricornBlock;
import com.cobblemon.mod.common.block.BerryBlock;
import com.cobblemon.mod.common.block.NutBushBlock;
import com.cobblemon.mod.common.block.RootBlock;
import com.cobblemon.mod.common.block.entity.BerryBlockEntity;
import dev.palworks.tree.TreeDetector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CaveVinesBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import org.jetbrains.annotations.Nullable;

/**
 * Builds a flat test area for trying workers out ({@code /palworks testarea}), from {@code origin}
 * (its north-west corner, at the height plants stand) east and south:
 * <ol>
 *   <li>Smelting: a furnace, a blast furnace and a smoker, each with something to smelt but no
 *       fuel, and a chest for the output.</li>
 *   <li>Crops: one of each, fully grown, with a sign naming it: every vanilla crop (pumpkins and
 *       melons with their fruit, cocoa on a log, glow berries hanging from a post), every Cobblemon
 *       crop block and root (big and energy root, hanging under a block), and every berry bush with
 *       its berries. The Cobblemon ones are found from the
 *       block registry, so new ones show up by themselves; each one's soil is whichever of a few
 *       candidates it can survive on. Each row of plants has a water channel behind it, so its
 *       farmland stays wet.</li>
 *   <li>Trees: every vanilla tree and Cobblemon's apricorn and saccharine trees, grown with the
 *       game's own features (so they're natural trees workers will chop), apricorns ripe.</li>
 * </ol>
 * Sections are separated by stone brick walkways, with a sign at each. Everything in the area is
 * cleared first. What couldn't be placed is reported in the result instead of failing.
 */
public final class TestArea {
    private static final String COBBLEMON = "cobblemon"; // Cobblemon's namespace
    /** What was built, and anything that couldn't be. */
    public record Result(BlockPos min, BlockPos max, int plots, List<String> problems) {}

    /** Plots in a section are cells of this size, {@code columns} to a row. */
    private record Grid(int cellWidth, int cellDepth, int columns) {
        int depth(int cells) {
            return (cells + columns - 1) / columns * cellDepth;
        }
    }

    private static final int WALK = 3; // walkway width
    private static final int CONTENT_WIDTH = 56; // between the walkways down each side
    // Crops: a plant every other block (the block between is for a pumpkin, a cocoa pod...), in rows
    // of a water channel, the plants, and their signs
    private static final int PLANT_SPACING = 2;
    private static final int PLANTS_PER_ROW = CONTENT_WIDTH / PLANT_SPACING - 1;
    private static final int CROP_ROW_DEPTH = 3;
    // Trees: logs can reach about 4 blocks from the trunk, so a 9 cell keeps neighbours apart; ones
    // with wide branches or a 2x2 trunk get 12. Checked: a tree with a log on its cell's edge is a
    // problem (see growTree).
    private static final Grid SMALL_TREES = new Grid(9, 9, 6);
    private static final Grid BIG_TREES = new Grid(12, 12, 4);
    private static final int SMELTING_DEPTH = 2;
    private static final int FARM_CLEAR_HEIGHT = 10;
    private static final int SMALL_TREE_CLEAR_HEIGHT = 24;
    private static final int TREE_CLEAR_HEIGHT = 40;
    private static final int TREE_SEEDS = 30;
    // How far past its cell a tree's leaves can reach (and a failed try is undone)
    private static final int CANOPY_MARGIN = 8;

    private static final int FLAGS = Block.UPDATE_CLIENTS;

    // Soils tried for a crop, in order: the first it can survive on is used
    private static final List<Block> SOILS = List.of(
            Blocks.FARMLAND,
            Blocks.GRASS_BLOCK,
            Blocks.DIRT,
            Blocks.MUD,
            Blocks.SAND,
            Blocks.GRAVEL,
            Blocks.SOUL_SAND,
            Blocks.MOSS_BLOCK,
            Blocks.WATER);

    private record Tree(String label, ResourceKey<ConfiguredFeature<?, ?>> feature, Block floor, boolean big) {}

    @FunctionalInterface
    private interface CellBuilder {
        void build(BlockPos cell, Grid grid);
    }

    private record Plot(String label, CellBuilder builder) {}

    /** One crop plant: its sign's label, and how to place it at a spot. */
    private record Plant(String label, java.util.function.Consumer<BlockPos> placer) {}

    private final ServerLevel level;
    private final BlockPos origin;
    private final List<String> problems = new ArrayList<>();
    private int plots;

    private TestArea(ServerLevel level, BlockPos origin) {
        this.level = level;
        this.origin = origin;
    }

    public static Result build(ServerLevel level, BlockPos origin) {
        return new TestArea(level, origin).build();
    }

    private Result build() {
        List<List<Plant>> crops = List.of(vanillaPlants(), cobblemonPlants(), berryPlants());
        int cropRows = crops.stream().mapToInt(group -> plantRows(group.size())).sum();
        List<Plot> smallTrees =
                trees().stream().filter(t -> !t.big()).map(this::treePlot).toList();
        List<Plot> bigTrees =
                trees().stream().filter(Tree::big).map(this::treePlot).toList();

        int width = WALK + CONTENT_WIDTH + WALK;
        int smeltingZ = WALK + 1;
        int cropsZ = smeltingZ + SMELTING_DEPTH + WALK;
        int smallTreesZ = cropsZ + cropRows * CROP_ROW_DEPTH + WALK;
        int bigTreesZ = smallTreesZ + SMALL_TREES.depth(smallTrees.size()) + WALK;
        int depth = bigTreesZ + BIG_TREES.depth(bigTrees.size()) + 1 + WALK;

        loadChunks(width, depth);
        clearAndFloor(0, width, 0, smallTreesZ, FARM_CLEAR_HEIGHT);
        clearAndFloor(0, width, smallTreesZ, bigTreesZ, SMALL_TREE_CLEAR_HEIGHT);
        clearAndFloor(0, width, bigTreesZ, depth, TREE_CLEAR_HEIGHT);
        walkways(width, depth, List.of(cropsZ - WALK, smallTreesZ - WALK, bigTreesZ - WALK));

        smelting(smeltingZ);
        crops(cropsZ, crops);
        section("Trees", smallTreesZ, smallTrees, SMALL_TREES);
        section("Big trees", bigTreesZ, bigTrees, BIG_TREES);

        BlockPos max = origin.offset(width - 1, TREE_CLEAR_HEIGHT, depth - 1);
        // Workers already out look round again before taking any of it, so they start nearest first
        dev.palworks.job.JobManager.getInstance(level).rescanArea(origin, max);
        return new Result(origin, max, plots, Collections.unmodifiableList(problems));
    }

    // ---- Ground, walkways, signs ----

    private BlockPos at(int x, int y, int z) {
        return origin.offset(x, y, z);
    }

    /**
     * Set a block, only if it's different: a big build rewriting blocks that are already right gives
     * the lighting thread (which reads chunk sections while we write them) more chances to trip.
     */
    private void set(BlockPos pos, BlockState state) {
        if (!level.getBlockState(pos).equals(state)) {
            level.setBlock(pos, state, FLAGS);
        }
    }

    /**
     * Load every chunk the area covers, and a chunk round it, all the way first, so none is still
     * generating or being lit while the build writes to it (or to the one beside it).
     */
    private void loadChunks(int width, int depth) {
        int minX = (origin.getX() >> 4) - 1;
        int maxX = ((origin.getX() + width - 1) >> 4) + 1;
        int minZ = (origin.getZ() >> 4) - 1;
        int maxZ = ((origin.getZ() + depth - 1) >> 4) + 1;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                level.getChunk(cx, cz); // blocks until the chunk is fully generated and lit
            }
        }
    }

    private void set(int x, int y, int z, Block block) {
        set(at(x, y, z), block.defaultBlockState());
    }

    /** Clear [x0,x1) x [z0,z1) up to {@code height}, on a grass floor over dirt. */
    private void clearAndFloor(int x0, int x1, int z0, int z1, int height) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                set(at(x, -3, z), Blocks.DIRT.defaultBlockState());
                set(at(x, -2, z), Blocks.DIRT.defaultBlockState());
                set(at(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState());
                for (int y = 0; y < height; y++) {
                    set(at(x, y, z), air);
                }
            }
        }
    }

    /** Stone brick walkways round the edge and across the area between sections, lanterns along the west. */
    private void walkways(int width, int depth, List<Integer> crossings) {
        for (int x = 0; x < width; x++) {
            for (int w = 0; w < WALK; w++) {
                set(x, -1, w, Blocks.STONE_BRICKS);
                set(x, -1, depth - 1 - w, Blocks.STONE_BRICKS);
                for (int z : crossings) {
                    set(x, -1, z + w, Blocks.STONE_BRICKS);
                }
            }
        }
        for (int z = 0; z < depth; z++) {
            for (int w = 0; w < WALK; w++) {
                set(w, -1, z, Blocks.STONE_BRICKS);
                set(width - 1 - w, -1, z, Blocks.STONE_BRICKS);
            }
        }
        for (int z = 4; z < depth - 2; z += 8) {
            set(0, 0, z, Blocks.SPRUCE_FENCE);
            set(0, 1, z, Blocks.LANTERN);
        }
    }

    /** A standing sign facing south with up to four lines. */
    private void sign(BlockPos pos, String... lines) {
        set(pos, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 0));
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            SignText text = new SignText();
            for (int i = 0; i < Math.min(4, lines.length); i++) {
                text = text.setMessage(i, Component.literal(lines[i]));
            }
            sign.setText(text, true);
            sign.setChanged();
            level.sendBlockUpdated(pos, sign.getBlockState(), sign.getBlockState(), Block.UPDATE_ALL);
        }
    }

    /** Signs hold about 15 characters a line: split a label over up to two lines. */
    private static String[] signLines(String label) {
        if (label.length() <= 15 || !label.contains(" ")) {
            return new String[] {label};
        }
        int split = label.lastIndexOf(' ', 15);
        if (split <= 0) {
            split = label.indexOf(' ');
        }
        return new String[] {label.substring(0, split), label.substring(split + 1)};
    }

    /** A section: its header sign on the walkway, then its plots in the grid's rows. */
    private void section(String title, int z, List<Plot> sectionPlots, Grid grid) {
        sign(at(1, 0, z - 1), "== " + title + " ==");
        for (int i = 0; i < sectionPlots.size(); i++) {
            BlockPos cell =
                    at(WALK + (i % grid.columns()) * grid.cellWidth(), 0, z + (i / grid.columns()) * grid.cellDepth());
            Plot plot = sectionPlots.get(i);
            try {
                plot.builder().build(cell, grid);
                plots++;
            } catch (RuntimeException e) { // NOPMD - one plot failing is reported, not the whole build
                problems.add(plot.label() + ": " + e.getMessage());
            }
        }
    }

    // ---- Smelting ----

    /** A furnace, a blast furnace and a smoker facing south, each with 32 of an input and no fuel, and a chest. */
    private void smelting(int z) {
        sign(at(1, 0, z - 1), "== Smelting ==");
        for (int x = WALK + 1; x <= WALK + 9; x++) {
            for (int dz = 0; dz < SMELTING_DEPTH; dz++) {
                set(x, -1, z + dz, Blocks.SMOOTH_STONE);
            }
        }
        station(at(WALK + 2, 0, z), Blocks.FURNACE, Items.RAW_IRON, "Furnace");
        station(at(WALK + 4, 0, z), Blocks.BLAST_FURNACE, Items.RAW_GOLD, "Blast furnace");
        station(at(WALK + 6, 0, z), Blocks.SMOKER, Items.BEEF, "Smoker");
        set(
                at(WALK + 8, 0, z),
                Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        sign(at(WALK + 8, 0, z + 1), "Output");
    }

    private void station(BlockPos pos, Block station, Item input, String label) {
        set(pos, station.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        if (level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace) {
            furnace.setItem(0, new ItemStack(input, 32)); // slot 0 = input; slot 1 (fuel) left empty
            furnace.setChanged();
        } else {
            problems.add(label + ": no block entity at " + pos.toShortString());
        }
        sign(pos.south(), label, "(no fuel)");
        plots++;
    }

    // ---- Crops ----

    private static int plantRows(int plants) {
        return (plants + PLANTS_PER_ROW - 1) / PLANTS_PER_ROW;
    }

    /** Each group of plants in rows of its own: a water channel, the plants, a sign in front of each. */
    private void crops(int z, List<List<Plant>> groups) {
        sign(at(1, 0, z - 1), "== Crops ==");
        int rowZ = z;
        for (List<Plant> group : groups) {
            for (int i = 0; i < group.size(); i++) {
                if (i % PLANTS_PER_ROW == 0) {
                    if (i > 0) {
                        rowZ += CROP_ROW_DEPTH;
                    }
                    for (int x = WALK; x < WALK + CONTENT_WIDTH; x++) {
                        set(at(x, -1, rowZ), Blocks.WATER.defaultBlockState());
                    }
                }
                BlockPos pos = at(WALK + 1 + (i % PLANTS_PER_ROW) * PLANT_SPACING, 0, rowZ + 1);
                Plant plant = group.get(i);
                try {
                    plant.placer().accept(pos);
                    plots++;
                } catch (RuntimeException e) { // NOPMD - one plant failing is reported, not the whole build
                    problems.add(plant.label() + ": " + e.getMessage());
                }
                sign(pos.south(), signLines(plant.label()));
            }
            rowZ += CROP_ROW_DEPTH;
        }
    }

    /** Fully grown: the crop's own last stage (a torchflower crop's is the flower), else max "age". */
    private static BlockState grown(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.getStateForAge(crop.getMaxAge());
        }
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty age && "age".equals(property.getName())) {
                return state.setValue(age, Collections.max(age.getPossibleValues()));
            }
        }
        return state;
    }

    private List<Plant> vanillaPlants() {
        List<Plant> list = new ArrayList<>();
        for (Block block : List.of(
                Blocks.WHEAT,
                Blocks.CARROTS,
                Blocks.POTATOES,
                Blocks.BEETROOTS,
                Blocks.TORCHFLOWER_CROP,
                Blocks.PITCHER_CROP)) {
            list.add(cropPlant(block));
        }
        list.add(new Plant("Pumpkin", pos -> stem(pos, Blocks.PUMPKIN, Blocks.ATTACHED_PUMPKIN_STEM)));
        list.add(new Plant("Melon", pos -> stem(pos, Blocks.MELON, Blocks.ATTACHED_MELON_STEM)));
        list.add(new Plant("Nether wart", pos -> {
            set(pos.below(), Blocks.SOUL_SAND.defaultBlockState());
            set(pos, grown(Blocks.NETHER_WART.defaultBlockState()));
        }));
        list.add(new Plant("Cocoa", this::cocoa));
        list.add(new Plant("Sweet berries", pos -> set(pos, grown(Blocks.SWEET_BERRY_BUSH.defaultBlockState()))));
        list.add(new Plant("Glow berries", this::glowBerries));
        return list;
    }

    /**
     * Every Cobblemon crop block other than berry bushes (which get rows of their own), and its
     * roots (big root, energy root), which hang under a block.
     */
    private List<Plant> cobblemonPlants() {
        List<Plant> list = new ArrayList<>();
        for (Block block : cobblemonBlocks()) {
            if (block instanceof CropBlock || block instanceof NutBushBlock) {
                list.add(cropPlant(block));
            } else if (block instanceof RootBlock) {
                list.add(new Plant(blockName(block), pos -> hangingRoot(pos, block)));
            }
        }
        return list;
    }

    /** A little rooted dirt arch, the root hanging from it. */
    private void hangingRoot(BlockPos pos, Block root) {
        for (int y = 0; y < 3; y++) {
            set(pos.east().above(y), Blocks.ROOTED_DIRT.defaultBlockState());
        }
        set(pos.above(2), Blocks.ROOTED_DIRT.defaultBlockState());
        set(pos.above(), root.defaultBlockState());
        if (!level.getBlockState(pos.above()).canSurvive(level, pos.above())) {
            throw new IllegalStateException("can't hang there");
        }
    }

    private List<Plant> berryPlants() {
        List<Plant> list = new ArrayList<>();
        for (Block berry : cobblemonBlocks()) {
            if (berry instanceof BerryBlock) {
                list.add(new Plant(shortName(berry), pos -> berryBush(pos, berry)));
            }
        }
        return list;
    }

    private static List<Block> cobblemonBlocks() {
        List<Block> blocks = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (COBBLEMON.equals(BuiltInRegistries.BLOCK.getKey(block).getNamespace())) {
                blocks.add(block);
            }
        }
        blocks.sort(Comparator.comparing(b -> BuiltInRegistries.BLOCK.getKey(b).getPath()));
        return blocks;
    }

    private static String blockName(Block block) {
        return block.getName().getString();
    }

    /** "Aguav Berry" -> "Aguav" */
    private static String shortName(Block berry) {
        String name = blockName(berry);
        return name.endsWith(" Berry") ? name.substring(0, name.length() - " Berry".length()) : name;
    }

    /** A crop on the first soil it can survive on. */
    private Plant cropPlant(Block block) {
        BlockState state = grown(block.defaultBlockState());
        String label = blockName(block);
        return new Plant(label, pos -> {
            Block soil = soilFor(pos, state);
            if (soil == null) {
                throw new IllegalStateException("no soil it can grow on");
            }
            soil(pos.below(), soil);
            if (!plant(pos, state)) {
                throw new IllegalStateException("can't stay where it was put");
            }
        });
    }

    /** Place a plant (and its top half, for a two-tall one); whether it can stay there. */
    private boolean plant(BlockPos pos, BlockState state) {
        BlockState placed = state.hasProperty(BlockStateProperties.WATERLOGGED)
                ? state.setValue(BlockStateProperties.WATERLOGGED, false)
                : state;
        set(pos, placed);
        if (placed.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            set(pos.above(), placed.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        }
        return placed.canSurvive(level, pos);
    }

    private void soil(BlockPos pos, Block soil) {
        BlockState state = soil.equals(Blocks.FARMLAND)
                ? Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE)
                : soil.defaultBlockState();
        set(pos, state);
    }

    /** The first soil the crop can survive on, tried at {@code pos}; null if none. */
    @Nullable
    private Block soilFor(BlockPos pos, BlockState crop) {
        for (Block soil : SOILS) {
            soil(pos.below(), soil);
            boolean ok = plant(pos, crop);
            set(pos, Blocks.AIR.defaultBlockState());
            set(pos.above(), Blocks.AIR.defaultBlockState());
            if (ok) {
                return soil;
            }
        }
        set(pos.below(), Blocks.GRASS_BLOCK.defaultBlockState());
        return null;
    }

    /** A stem on farmland, attached to its fruit beside it. */
    private void stem(BlockPos pos, Block fruit, Block attachedStem) {
        soil(pos.below(), Blocks.FARMLAND);
        set(pos, attachedStem.defaultBlockState().setValue(AttachedStemBlock.FACING, Direction.EAST));
        set(pos.east(), fruit.defaultBlockState());
    }

    /** A two-high jungle log with a ripe pod on its east side. */
    private void cocoa(BlockPos pos) {
        set(pos, Blocks.JUNGLE_LOG.defaultBlockState());
        set(pos.above(), Blocks.JUNGLE_LOG.defaultBlockState());
        // A pod faces the log it hangs on
        set(
                pos.east(),
                Blocks.COCOA
                        .defaultBlockState()
                        .setValue(CocoaBlock.FACING, Direction.WEST)
                        .setValue(CocoaBlock.AGE, CocoaBlock.MAX_AGE));
    }

    /** A post with an arm over the spot, glow berry vines hanging from it to the ground. */
    private void glowBerries(BlockPos pos) {
        for (int y = 0; y < 3; y++) {
            set(pos.east().above(y), Blocks.DARK_OAK_LOG.defaultBlockState());
        }
        set(pos.above(2), Blocks.DARK_OAK_PLANKS.defaultBlockState());
        set(pos.above(), Blocks.CAVE_VINES_PLANT.defaultBlockState().setValue(CaveVines.BERRIES, true));
        set(
                pos,
                Blocks.CAVE_VINES
                        .defaultBlockState()
                        .setValue(CaveVines.BERRIES, true)
                        .setValue(CaveVinesBlock.AGE, CaveVinesBlock.MAX_AGE));
    }

    /** A berry bush on farmland, with berries on it (as on a wild bush). */
    private void berryBush(BlockPos pos, Block berry) {
        soil(pos.below(), Blocks.FARMLAND);
        set(pos, grown(berry.defaultBlockState()));
        if (!level.getBlockState(pos).canSurvive(level, pos)) {
            throw new IllegalStateException("can't stay on farmland");
        }
        if (!(level.getBlockEntity(pos) instanceof BerryBlockEntity bush)) {
            throw new IllegalStateException("no berry block entity");
        }
        bush.generateSimpleYields();
        bush.setChanged();
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), Block.UPDATE_ALL);
    }

    // ---- Trees ----

    private List<Tree> trees() {
        List<Tree> list = new ArrayList<>(List.of(
                new Tree("Oak", TreeFeatures.OAK, Blocks.GRASS_BLOCK, false),
                new Tree("Big oak", TreeFeatures.FANCY_OAK, Blocks.GRASS_BLOCK, true),
                new Tree("Swamp oak", TreeFeatures.SWAMP_OAK, Blocks.GRASS_BLOCK, false),
                new Tree("Birch", TreeFeatures.BIRCH, Blocks.GRASS_BLOCK, false),
                new Tree("Tall birch", TreeFeatures.SUPER_BIRCH_BEES_0002, Blocks.GRASS_BLOCK, false),
                new Tree("Spruce", TreeFeatures.SPRUCE, Blocks.GRASS_BLOCK, false),
                new Tree("Pine", TreeFeatures.PINE, Blocks.GRASS_BLOCK, false),
                new Tree("Mega spruce", TreeFeatures.MEGA_SPRUCE, Blocks.PODZOL, true),
                new Tree("Mega pine", TreeFeatures.MEGA_PINE, Blocks.PODZOL, true),
                new Tree("Jungle", TreeFeatures.JUNGLE_TREE, Blocks.GRASS_BLOCK, false),
                new Tree("Mega jungle", TreeFeatures.MEGA_JUNGLE_TREE, Blocks.GRASS_BLOCK, true),
                new Tree("Acacia", TreeFeatures.ACACIA, Blocks.GRASS_BLOCK, false),
                new Tree("Dark oak", TreeFeatures.DARK_OAK, Blocks.GRASS_BLOCK, true),
                new Tree("Cherry", TreeFeatures.CHERRY, Blocks.GRASS_BLOCK, false),
                new Tree("Mangrove", TreeFeatures.MANGROVE, Blocks.MUD, true),
                new Tree("Tall mangrove", TreeFeatures.TALL_MANGROVE, Blocks.MUD, true),
                new Tree("Azalea", TreeFeatures.AZALEA_TREE, Blocks.GRASS_BLOCK, false),
                new Tree("Crimson fungus", TreeFeatures.CRIMSON_FUNGUS_PLANTED, Blocks.CRIMSON_NYLIUM, false),
                new Tree("Warped fungus", TreeFeatures.WARPED_FUNGUS_PLANTED, Blocks.WARPED_NYLIUM, false)));
        for (String colour : List.of("black", "blue", "green", "pink", "red", "white", "yellow")) {
            list.add(new Tree(
                    capitalise(colour) + " apricorn",
                    cobblemonFeature(colour + "_apricorn_tree"),
                    Blocks.GRASS_BLOCK,
                    false));
        }
        list.add(new Tree("Saccharine", cobblemonFeature("saccharine_tree"), Blocks.GRASS_BLOCK, false));
        return list;
    }

    private static ResourceKey<ConfiguredFeature<?, ?>> cobblemonFeature(String path) {
        return ResourceKey.create(
                Registries.CONFIGURED_FEATURE, ResourceLocation.fromNamespaceAndPath("cobblemon", path));
    }

    private static String capitalise(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    private Plot treePlot(Tree tree) {
        return new Plot(tree.label(), (cell, grid) -> growTree(cell, grid, tree));
    }

    /**
     * Grow a tree in the middle of its cell, apricorns ripe. A tree's shape depends on a seed: seeds
     * are tried until one grows a tree that fits its cell, with no log on the cell's edge (where it
     * could join its neighbour's). A try that doesn't fit is undone block for block, wherever it
     * reached: its leaves can spread past the cell, and clearing only the cell left them floating
     * (and cut into the neighbours' leaves). Leaves the game marks as too far from a log to stay
     * (azalea and mangrove scatter a few) are taken away, as they'd decay soon anyway.
     */
    private void growTree(BlockPos cell, Grid grid, Tree tree) {
        Optional<ConfiguredFeature<?, ?>> feature = level.registryAccess()
                .registryOrThrow(Registries.CONFIGURED_FEATURE)
                .getOptional(tree.feature());
        if (feature.isEmpty()) {
            throw new IllegalStateException("no feature " + tree.feature().location());
        }
        int w = grid.cellWidth();
        int d = grid.cellDepth();
        BlockPos trunk = cell.offset((w - 1) / 2, 0, (d - 1) / 2);
        BlockPos top = cell.offset(w - 1, TREE_CLEAR_HEIGHT - 1, d - 1);
        layTreeFloor(cell, top, tree.floor());

        boolean fits = false;
        for (long seed = 1; seed <= TREE_SEEDS && !fits; seed++) {
            Snapshot before = new Snapshot(
                    cell.offset(-CANOPY_MARGIN, -2, -CANOPY_MARGIN), top.offset(CANOPY_MARGIN, 0, CANOPY_MARGIN));
            fits = feature.get().place(level, level.getChunkSource().getGenerator(), RandomSource.create(seed), trunk)
                    && !reachesEdge(cell, top);
            if (!fits) {
                before.restore();
            }
        }
        if (!fits) {
            throw new IllegalStateException(
                    "no seed of " + TREE_SEEDS + " grew one that fits its " + w + "x" + d + " plot");
        }
        for (BlockPos pos : BlockPos.betweenClosed(
                cell.offset(-CANOPY_MARGIN, 0, -CANOPY_MARGIN), top.offset(CANOPY_MARGIN, 0, CANOPY_MARGIN))) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof ApricornBlock) {
                set(pos.immutable(), grown(state));
            } else if (state.getBlock() instanceof LeavesBlock
                    && !state.getValue(LeavesBlock.PERSISTENT)
                    && state.getValue(LeavesBlock.DISTANCE) == LeavesBlock.DECAY_DISTANCE) {
                // Too far from a log to stay: azalea and mangrove trees scatter a few leaves like this,
                // which decay soon after. Take them away now, so nothing floats.
                set(pos.immutable(), Blocks.AIR.defaultBlockState());
            }
        }
        sign(cell.offset((w - 1) / 2, 0, d - 1), signLines(tree.label()));
    }

    /** The tree's floor inside the cell, grass round its edge. */
    private void layTreeFloor(BlockPos cell, BlockPos top, Block floor) {
        for (int x = cell.getX(); x <= top.getX(); x++) {
            for (int z = cell.getZ(); z <= top.getZ(); z++) {
                boolean ring = x == cell.getX() || x == top.getX() || z == cell.getZ() || z == top.getZ();
                set(new BlockPos(x, cell.getY() - 1, z), (ring ? Blocks.GRASS_BLOCK : floor).defaultBlockState());
            }
        }
    }

    /** The blocks in a box, to put back exactly as they were. */
    private final class Snapshot {
        private final BlockPos min;
        private final BlockPos max;
        private final List<BlockState> states = new ArrayList<>();

        Snapshot(BlockPos min, BlockPos max) {
            this.min = min;
            this.max = max;
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                states.add(level.getBlockState(pos));
            }
        }

        void restore() {
            int i = 0;
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                set(pos.immutable(), states.get(i));
                i++;
            }
        }
    }

    /** Whether any log (or other tree block) is on the outer ring of the cell, at any height. */
    private boolean reachesEdge(BlockPos cell, BlockPos top) {
        for (BlockPos pos : BlockPos.betweenClosed(cell.below(1), top)) {
            boolean ring = pos.getX() == cell.getX()
                    || pos.getX() == top.getX()
                    || pos.getZ() == cell.getZ()
                    || pos.getZ() == top.getZ();
            if (ring && TreeDetector.isTreeBlock(level.getBlockState(pos))) {
                return true;
            }
        }
        return false;
    }
}
