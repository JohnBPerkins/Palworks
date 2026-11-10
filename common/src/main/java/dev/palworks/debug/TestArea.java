package dev.palworks.debug;

import com.cobblemon.mod.common.block.ApricornBlock;
import com.cobblemon.mod.common.block.BerryBlock;
import com.cobblemon.mod.common.block.NutBushBlock;
import com.cobblemon.mod.common.block.entity.BerryBlockEntity;
import dev.palworks.tree.TreeDetector;
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
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Builds a flat test area for trying workers out ({@code /palworks testarea}), from {@code origin}
 * (its north-west corner, at the height plants stand) east and south:
 * <ol>
 *   <li>Smelting: furnaces, blast furnaces and smokers with something to smelt but no fuel, and a
 *       chest per group for the output.</li>
 *   <li>Vanilla crops: a plot of each, fully grown, from wheat to pumpkins, melons, cocoa, nether
 *       wart, sweet berries and glow berries.</li>
 *   <li>Cobblemon crops: every Cobblemon crop block (mints, revival herb, vivichoke, ...) and every
 *       berry bush with its fruit, found from the block registry, so new ones show up by themselves.
 *       Each one's soil is whichever of a few candidates it can survive on.</li>
 *   <li>Trees: every vanilla tree and Cobblemon's apricorn and saccharine trees, grown with the
 *       game's own features (so they're natural trees workers will chop), apricorns ripe.</li>
 * </ol>
 * Crop beds have a water channel down the middle so their farmland stays wet. Sections are
 * separated by stone brick walkways, with a sign at each section and on each plot. Everything in
 * the area is cleared first. What couldn't be placed is reported in the result instead of failing.
 */
public final class TestArea {
    /** What was built, and anything that couldn't be. */
    public record Result(BlockPos min, BlockPos max, int plots, List<String> problems) {}

    /** Plots in a section are cells of this size, {@code columns} to a row. */
    private record Grid(int cellWidth, int cellDepth, int columns) {
        int depth(int cells) {
            return (cells + columns - 1) / columns * cellDepth;
        }
    }

    private static final int WALK = 3;          // walkway width
    private static final int BED = 5;           // a crop bed is BED x 3
    // A crop cell: the bed with a block of margin each side, a sign in front of it, and a gap
    private static final Grid CROPS = new Grid(BED + 2, 5, 8);
    // Trees: logs can reach about 4 blocks from the trunk, so a 9 cell keeps neighbours apart; ones
    // with wide branches or a 2x2 trunk get 12. Checked: a tree with a log on its cell's edge is a
    // problem (see growTree).
    private static final Grid SMALL_TREES = new Grid(9, 9, 6);
    private static final Grid BIG_TREES = new Grid(12, 12, 4);
    private static final int SMELTING_DEPTH = 3;
    private static final int FARM_CLEAR_HEIGHT = 10;
    private static final int SMALL_TREE_CLEAR_HEIGHT = 24;
    private static final int TREE_CLEAR_HEIGHT = 40;
    private static final int BERRIES_PER_PLOT = 2 * BED;
    private static final int TREE_SEEDS = 30;

    private static final int FLAGS = Block.UPDATE_CLIENTS;

    // Soils tried for a crop, in order: the first it can survive on is used
    private static final List<Block> SOILS = List.of(Blocks.FARMLAND, Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.MUD,
            Blocks.SAND, Blocks.GRAVEL, Blocks.SOUL_SAND, Blocks.MOSS_BLOCK, Blocks.WATER);

    private record Crop(String label, BlockState state) {}

    private record Tree(String label, ResourceKey<ConfiguredFeature<?, ?>> feature, Block floor, boolean big) {}

    private interface CellBuilder {
        void build(BlockPos cell, Grid grid);
    }

    private record Plot(String label, CellBuilder builder) {}

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
        List<Plot> vanilla = vanillaCropPlots();
        List<Plot> cobblemon = new ArrayList<>(cobblemonCropPlots());
        cobblemon.addAll(berryPlots());
        List<Plot> smallTrees = trees().stream().filter(t -> !t.big()).map(this::treePlot).toList();
        List<Plot> bigTrees = trees().stream().filter(Tree::big).map(this::treePlot).toList();

        int width = WALK + CROPS.columns() * CROPS.cellWidth() + WALK;
        int smeltingZ = WALK + 1;
        int vanillaZ = smeltingZ + SMELTING_DEPTH + WALK;
        int cobblemonZ = vanillaZ + CROPS.depth(vanilla.size()) + WALK;
        int smallTreesZ = cobblemonZ + CROPS.depth(cobblemon.size()) + WALK;
        int bigTreesZ = smallTreesZ + SMALL_TREES.depth(smallTrees.size()) + WALK;
        int depth = bigTreesZ + BIG_TREES.depth(bigTrees.size()) + 1 + WALK;

        loadChunks(width, depth);
        clearAndFloor(0, width, 0, smallTreesZ, FARM_CLEAR_HEIGHT);
        clearAndFloor(0, width, smallTreesZ, bigTreesZ, SMALL_TREE_CLEAR_HEIGHT);
        clearAndFloor(0, width, bigTreesZ, depth, TREE_CLEAR_HEIGHT);
        walkways(width, depth, List.of(vanillaZ - WALK, cobblemonZ - WALK, smallTreesZ - WALK, bigTreesZ - WALK));

        smelting(smeltingZ);
        section("Vanilla crops", vanillaZ, vanilla, CROPS);
        section("Cobblemon crops", cobblemonZ, cobblemon, CROPS);
        section("Trees", smallTreesZ, smallTrees, SMALL_TREES);
        section("Big trees", bigTreesZ, bigTrees, BIG_TREES);

        return new Result(origin, origin.offset(width - 1, TREE_CLEAR_HEIGHT, depth - 1), plots,
                Collections.unmodifiableList(problems));
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
        if (level.getBlockState(pos) != state) {
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
            BlockPos cell = at(WALK + (i % grid.columns()) * grid.cellWidth(), 0,
                    z + (i / grid.columns()) * grid.cellDepth());
            Plot plot = sectionPlots.get(i);
            try {
                plot.builder().build(cell, grid);
                plots++;
            } catch (RuntimeException e) {
                problems.add(plot.label() + ": " + e.getMessage());
            }
        }
    }

    // ---- Smelting ----

    private void smelting(int z) {
        sign(at(1, 0, z - 1), "== Smelting ==");
        stations(WALK + 2, z, Blocks.FURNACE, "Furnaces", Items.RAW_IRON, Items.SAND, Items.COBBLESTONE);
        stations(WALK + 2 + 8, z, Blocks.BLAST_FURNACE, "Blast furnaces", Items.RAW_IRON, Items.RAW_GOLD, Items.RAW_COPPER);
        stations(WALK + 2 + 16, z, Blocks.SMOKER, "Smokers", Items.BEEF, Items.PORKCHOP, Items.CHICKEN);
    }

    /** Three stations facing south, each with 32 of an input and no fuel, a chest, and a sign. */
    private void stations(int x, int z, Block station, String label, Item... inputs) {
        for (int dx = -1; dx <= inputs.length + 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                set(x + dx, -1, z + dz, Blocks.SMOOTH_STONE);
            }
        }
        for (int i = 0; i < inputs.length; i++) {
            BlockPos pos = at(x + i, 0, z);
            set(pos, station.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
            if (level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace) {
                furnace.setItem(0, new ItemStack(inputs[i], 32)); // slot 0 = input; slot 1 (fuel) left empty
                furnace.setChanged();
            } else {
                problems.add(label + ": no block entity at " + pos.toShortString());
            }
        }
        set(at(x + inputs.length, 0, z), Blocks.CHEST.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        sign(at(x + 1, 0, z + 1), label, "(no fuel)");
        plots++;
    }

    // ---- Crops ----

    /** Fully grown: the crop's own last stage (a torchflower crop's is the flower), else max "age". */
    private static BlockState grown(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.getStateForAge(crop.getMaxAge());
        }
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty age && property.getName().equals("age")) {
                return state.setValue(age, Collections.max(age.getPossibleValues()));
            }
        }
        return state;
    }

    private List<Plot> vanillaCropPlots() {
        List<Plot> list = new ArrayList<>();
        for (Block block : List.of(Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS,
                Blocks.TORCHFLOWER_CROP, Blocks.PITCHER_CROP)) {
            Crop crop = new Crop(blockName(block), grown(block.defaultBlockState()));
            list.add(new Plot(crop.label(), (cell, grid) -> bed(cell, crop)));
        }
        list.add(new Plot("Pumpkins", (cell, grid) -> stemBed(cell, Blocks.PUMPKIN, Blocks.ATTACHED_PUMPKIN_STEM, "Pumpkins")));
        list.add(new Plot("Melons", (cell, grid) -> stemBed(cell, Blocks.MELON, Blocks.ATTACHED_MELON_STEM, "Melons")));
        list.add(new Plot("Nether wart", (cell, grid) -> netherWart(cell)));
        list.add(new Plot("Cocoa", (cell, grid) -> cocoa(cell)));
        list.add(new Plot("Sweet berries", (cell, grid) -> sweetBerries(cell)));
        list.add(new Plot("Glow berries", (cell, grid) -> glowBerries(cell)));
        return list;
    }

    /** Every Cobblemon crop block other than berry bushes (which get their own section). */
    private List<Plot> cobblemonCropPlots() {
        List<Plot> list = new ArrayList<>();
        for (Block block : cobblemonBlocks()) {
            if (block instanceof CropBlock || block instanceof NutBushBlock) {
                Crop crop = new Crop(blockName(block), grown(block.defaultBlockState()));
                list.add(new Plot(crop.label(), (cell, grid) -> bed(cell, crop)));
            }
        }
        return list;
    }

    private List<Plot> berryPlots() {
        List<Block> berries = cobblemonBlocks().stream().filter(b -> b instanceof BerryBlock).toList();
        List<Plot> list = new ArrayList<>();
        for (int i = 0; i < berries.size(); i += BERRIES_PER_PLOT) {
            List<Block> batch = berries.subList(i, Math.min(berries.size(), i + BERRIES_PER_PLOT));
            String label = shortName(batch.get(0)) + " to " + shortName(batch.get(batch.size() - 1));
            list.add(new Plot("Berries " + label, (cell, grid) -> berryBed(cell, batch, label)));
        }
        return list;
    }

    private static List<Block> cobblemonBlocks() {
        List<Block> blocks = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals("cobblemon")) {
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

    /** Place a plant (and its top half, for a two-tall one); whether it can stay there. */
    private boolean plant(BlockPos pos, BlockState state) {
        BlockState placed = state.hasProperty(BlockStateProperties.WATERLOGGED)
                ? state.setValue(BlockStateProperties.WATERLOGGED, false) : state;
        set(pos, placed);
        if (placed.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            set(pos.above(), placed.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        }
        return placed.canSurvive(level, pos);
    }

    private void soil(BlockPos pos, Block soil) {
        BlockState state = soil == Blocks.FARMLAND
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

    /**
     * A 5x3 bed of a crop with a sign in front. On farmland: two rows of crop either side of a water
     * channel. In water (medicinal leek): a pond with the crop on every block. Any other soil: three
     * rows of it.
     */
    private void bed(BlockPos cell, Crop crop) {
        BlockPos first = cell.offset(1, 0, 0);
        Block soil = soilFor(first, crop.state());
        if (soil == null) {
            throw new IllegalStateException("no soil it can grow on");
        }
        int failed = 0;
        for (int dx = 0; dx < BED; dx++) {
            for (int row = 0; row < 3; row++) {
                BlockPos pos = first.offset(dx, 0, row);
                if (soil == Blocks.FARMLAND && row == 1) {
                    set(pos.below(), Blocks.WATER.defaultBlockState());
                    continue;
                }
                soil(pos.below(), soil);
                if (!plant(pos, crop.state())) {
                    failed++;
                }
            }
        }
        if (failed > 0) {
            problems.add(crop.label() + ": " + failed + " plants can't stay where they were put");
        }
        sign(cell.offset(3, 0, 3), signLines(crop.label()));
    }

    /** Stems on farmland (water behind them), each attached to its fruit in front. */
    private void stemBed(BlockPos cell, Block fruit, Block attachedStem, String label) {
        BlockPos first = cell.offset(1, 0, 0);
        for (int dx = 0; dx < BED; dx++) {
            BlockPos fruitPos = first.offset(dx, 0, 0);
            BlockPos stemPos = fruitPos.south();
            set(fruitPos.below(), Blocks.GRASS_BLOCK.defaultBlockState());
            set(fruitPos, fruit.defaultBlockState());
            soil(stemPos.below(), Blocks.FARMLAND);
            set(stemPos, attachedStem.defaultBlockState().setValue(AttachedStemBlock.FACING, Direction.NORTH));
            set(stemPos.south().below(), Blocks.WATER.defaultBlockState());
        }
        sign(cell.offset(3, 0, 3), label);
    }

    private void netherWart(BlockPos cell) {
        BlockPos first = cell.offset(1, 0, 0);
        BlockState wart = grown(Blocks.NETHER_WART.defaultBlockState());
        for (int dx = 0; dx < BED; dx++) {
            for (int row = 0; row < 3; row++) {
                BlockPos pos = first.offset(dx, 0, row);
                set(pos.below(), Blocks.SOUL_SAND.defaultBlockState());
                set(pos, wart.setValue(NetherWartBlock.AGE, NetherWartBlock.MAX_AGE));
            }
        }
        sign(cell.offset(3, 0, 3), "Nether wart");
    }

    /** Two jungle log pillars, ripe cocoa on every side. */
    private void cocoa(BlockPos cell) {
        for (int i = 0; i < 2; i++) {
            BlockPos base = cell.offset(2 + 3 * i, 0, 1);
            for (int y = 0; y < 3; y++) {
                set(base.above(y), Blocks.JUNGLE_LOG.defaultBlockState());
            }
            for (int y = 0; y < 2; y++) {
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    // A pod faces the log it hangs on
                    set(base.above(y).relative(side), Blocks.COCOA.defaultBlockState()
                            .setValue(CocoaBlock.FACING, side.getOpposite()).setValue(CocoaBlock.AGE, CocoaBlock.MAX_AGE));
                }
            }
        }
        sign(cell.offset(3, 0, 3), "Cocoa");
    }

    private void sweetBerries(BlockPos cell) {
        BlockPos first = cell.offset(1, 0, 0);
        for (int dx = 0; dx < BED; dx++) {
            for (int row = 0; row < 3; row++) {
                set(first.offset(dx, 0, row), Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                        .setValue(SweetBerryBushBlock.AGE, SweetBerryBushBlock.MAX_AGE));
            }
        }
        sign(cell.offset(3, 0, 3), "Sweet berries");
    }

    /** A little pergola: two log posts, a plank beam, vines with berries hanging from it. */
    private void glowBerries(BlockPos cell) {
        BlockPos west = cell.offset(0, 0, 1);
        for (int y = 0; y < 5; y++) {
            set(west.above(y), Blocks.DARK_OAK_LOG.defaultBlockState());
            set(west.offset(BED + 1, y, 0), Blocks.DARK_OAK_LOG.defaultBlockState());
        }
        for (int dx = 1; dx <= BED; dx++) {
            BlockPos column = west.offset(dx, 0, 0);
            set(column.above(4), Blocks.DARK_OAK_PLANKS.defaultBlockState());
            set(column.above(3), Blocks.CAVE_VINES_PLANT.defaultBlockState().setValue(CaveVines.BERRIES, true));
            set(column.above(2), Blocks.CAVE_VINES.defaultBlockState()
                    .setValue(CaveVines.BERRIES, true).setValue(CaveVinesBlock.AGE, CaveVinesBlock.MAX_AGE));
        }
        sign(cell.offset(3, 0, 3), "Glow berries");
    }

    /** Berry bushes on farmland, two rows of them either side of a water channel. */
    private void berryBed(BlockPos cell, List<Block> berries, String label) {
        BlockPos first = cell.offset(1, 0, 0);
        for (int dx = 0; dx < BED; dx++) {
            soil(first.offset(dx, -1, 0), Blocks.FARMLAND);
            set(first.offset(dx, -1, 1), Blocks.WATER.defaultBlockState());
            soil(first.offset(dx, -1, 2), Blocks.FARMLAND);
        }
        for (int i = 0; i < berries.size(); i++) {
            BlockPos pos = first.offset(i % BED, 0, (i / BED) * 2);
            Block berry = berries.get(i);
            set(pos, grown(berry.defaultBlockState()));
            if (!level.getBlockState(pos).canSurvive(level, pos)) {
                problems.add(blockName(berry) + ": can't stay on farmland");
            }
            if (level.getBlockEntity(pos) instanceof BerryBlockEntity bush) {
                bush.generateSimpleYields(); // the berries on it, as on a wild bush
                bush.setChanged();
                level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), Block.UPDATE_ALL);
            } else {
                problems.add(blockName(berry) + ": no berry block entity");
            }
        }
        sign(cell.offset(3, 0, 3), "Berries", label);
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
            list.add(new Tree(capitalise(colour) + " apricorn", cobblemonFeature(colour + "_apricorn_tree"),
                    Blocks.GRASS_BLOCK, false));
        }
        list.add(new Tree("Saccharine", cobblemonFeature("saccharine_tree"), Blocks.GRASS_BLOCK, false));
        return list;
    }

    private static ResourceKey<ConfiguredFeature<?, ?>> cobblemonFeature(String path) {
        return ResourceKey.create(Registries.CONFIGURED_FEATURE, ResourceLocation.fromNamespaceAndPath("cobblemon", path));
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
     * could join its neighbour's), clearing the cell between tries.
     */
    private void growTree(BlockPos cell, Grid grid, Tree tree) {
        Optional<ConfiguredFeature<?, ?>> feature = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .getOptional(tree.feature());
        if (feature.isEmpty()) {
            throw new IllegalStateException("no feature " + tree.feature().location());
        }
        int w = grid.cellWidth();
        int d = grid.cellDepth();
        BlockPos trunk = cell.offset((w - 1) / 2, 0, (d - 1) / 2);
        BlockPos top = cell.offset(w - 1, TREE_CLEAR_HEIGHT - 1, d - 1);
        boolean fits = false;
        for (long seed = 1; seed <= TREE_SEEDS && !fits; seed++) {
            clearCell(cell, top, tree.floor());
            fits = feature.get().place(level, level.getChunkSource().getGenerator(), RandomSource.create(seed), trunk)
                    && !reachesEdge(cell, top);
        }
        if (!fits) {
            throw new IllegalStateException("no seed of " + TREE_SEEDS + " grew one that fits its "
                    + w + "x" + d + " plot");
        }
        for (BlockPos pos : BlockPos.betweenClosed(cell, top)) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof ApricornBlock) {
                set(pos.immutable(), grown(state));
            }
        }
        sign(cell.offset((w - 1) / 2, 0, d - 1), signLines(tree.label()));
    }

    /** Empty the cell above ground (and just below it, where roots go) and lay its floor. */
    private void clearCell(BlockPos cell, BlockPos top, Block floor) {
        for (BlockPos pos : BlockPos.betweenClosed(cell.below(2), top)) {
            Block block;
            if (pos.getY() >= cell.getY()) {
                block = Blocks.AIR;
            } else if (pos.getY() < cell.getY() - 1) {
                block = Blocks.DIRT;
            } else {
                boolean ring = pos.getX() == cell.getX() || pos.getX() == top.getX()
                        || pos.getZ() == cell.getZ() || pos.getZ() == top.getZ();
                block = ring ? Blocks.GRASS_BLOCK : floor;
            }
            set(pos.immutable(), block.defaultBlockState());
        }
    }

    /** Whether any log (or other tree block) is on the outer ring of the cell, at any height. */
    private boolean reachesEdge(BlockPos cell, BlockPos top) {
        for (BlockPos pos : BlockPos.betweenClosed(cell.below(1), top)) {
            boolean ring = pos.getX() == cell.getX() || pos.getX() == top.getX()
                    || pos.getZ() == cell.getZ() || pos.getZ() == top.getZ();
            if (ring && TreeDetector.isTreeBlock(level.getBlockState(pos))) {
                return true;
            }
        }
        return false;
    }
}
