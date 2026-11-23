package dev.palworks.boulder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * One boulder, rolled and ready to place: its kind, its shape, and the block for each of its cells
 * (in {@link BoulderShape#cells()} order). Rolled before a site is looked for, so the site fits it,
 * and kept in the summoning job's data until it's raised.
 *
 * @param kind the id of its {@link BoulderKind}
 * @param shape where its blocks are
 * @param blocks the block id of each cell, in {@code shape.cells()} order
 */
public record BoulderPlan(String kind, BoulderShape shape, List<String> blocks) {

    public BoulderPlan {
        blocks = List.copyOf(blocks);
        if (blocks.size() != shape.blockCount()) {
            throw new IllegalArgumentException(
                    blocks.size() + " blocks for a shape of " + shape.blockCount() + " cells");
        }
    }

    /**
     * A boulder of this kind, raised by an Unearthing worker of this level: its size from the level,
     * its style from {@code style} (or picked at random, if null), its stone laid out in the kind's
     * pattern, and, about one time in ten ({@link BoulderSize#ORE_BOULDER_CHANCE}), clusters of ore
     * inside it.
     */
    public static BoulderPlan roll(
            BoulderKind kind,
            int level,
            @Nullable BoulderStyle style,
            BoulderOreLevels oreLevels,
            RandomGenerator random) {
        return roll(kind, level, style, oreLevels, random, false);
    }

    /**
     * {@link #roll(BoulderKind, int, BoulderStyle, BoulderOreLevels, RandomGenerator)}, with ore for
     * certain if {@code alwaysOre} (the showcase, to show where ore goes).
     */
    public static BoulderPlan roll(
            BoulderKind kind,
            int level,
            @Nullable BoulderStyle style,
            BoulderOreLevels oreLevels,
            RandomGenerator random,
            boolean alwaysOre) {
        BoulderShape shape = style == null ? BoulderShape.roll(level, random) : BoulderShape.roll(level, style, random);
        List<BoulderShape.Cell> cells = shape.cells();
        Map<BoulderShape.Cell, Integer> depths = depths(shape, cells);
        SmoothNoise noise = new SmoothNoise(random.nextLong());
        // Strata lean a little, a different way in each boulder
        double tiltX = (random.nextDouble() - 0.5) * 0.4;
        double tiltZ = (random.nextDouble() - 0.5) * 0.4;
        double shift = random.nextDouble();
        Map<BoulderShape.Cell, String> blocks = new LinkedHashMap<>();
        for (BoulderShape.Cell cell : cells) {
            boolean openToSky = shape.heightAt(cell.x(), cell.z()) == cell.y() + 1;
            blocks.put(
                    cell,
                    kind.pattern()
                            .pick(
                                    kind.stone(),
                                    new BoulderPattern.Spot(
                                            cell,
                                            depths.get(cell),
                                            openToSky,
                                            shape.height(),
                                            noise,
                                            tiltX,
                                            tiltZ,
                                            shift)));
        }
        if (alwaysOre || random.nextDouble() < BoulderSize.ORE_BOULDER_CHANCE) {
            addOre(kind, level, oreLevels, random, depths, blocks);
        }
        return new BoulderPlan(kind.id(), shape, new ArrayList<>(blocks.values()));
    }

    /**
     * Clusters of ore, each one ore the level has unlocked, grown from a block as deep inside the
     * boulder as there is, through the blocks round it, the deepest first: found as the boulder is
     * mined into, not lying on its outside. Nothing if the level has no ore unlocked yet.
     */
    private static void addOre(
            BoulderKind kind,
            int level,
            BoulderOreLevels oreLevels,
            RandomGenerator random,
            Map<BoulderShape.Cell, Integer> depths,
            Map<BoulderShape.Cell, String> blocks) {
        BoulderSize size = BoulderSize.forLevel(level);
        Set<BoulderShape.Cell> ore = new HashSet<>();
        int clusters = random.nextInt(size.minClusters(), size.maxClusters() + 1);
        for (int c = 0; c < clusters; c++) {
            String block = kind.pickOre(level, oreLevels, random);
            if (block == null) {
                return;
            }
            int clusterSize = random.nextInt(size.minClusterSize(), size.maxClusterSize() + 1);
            List<BoulderShape.Cell> cluster = new ArrayList<>();
            for (int i = 0; i < clusterSize; i++) {
                // The first from anywhere, the rest beside the cluster so far: the deepest, ties at random
                Set<BoulderShape.Cell> choices = new HashSet<>();
                if (cluster.isEmpty()) {
                    choices.addAll(depths.keySet());
                } else {
                    for (BoulderShape.Cell grown : cluster) {
                        choices.addAll(beside(grown));
                    }
                }
                choices.removeIf(cell -> !depths.containsKey(cell) || ore.contains(cell));
                if (choices.isEmpty()) {
                    break;
                }
                int deepest = choices.stream().mapToInt(depths::get).max().orElseThrow();
                List<BoulderShape.Cell> best = choices.stream()
                        .filter(cell -> depths.get(cell) == deepest)
                        .sorted(java.util.Comparator.comparingInt(BoulderShape.Cell::y)
                                .thenComparingInt(BoulderShape.Cell::x)
                                .thenComparingInt(BoulderShape.Cell::z))
                        .toList();
                BoulderShape.Cell next = best.get(random.nextInt(best.size()));
                cluster.add(next);
                ore.add(next);
                blocks.put(next, block);
            }
        }
    }

    /**
     * How deep inside the boulder each block is: 1 for one with a side open to the air (or the sky),
     * 2 for one beside those, and so on. The ground under it doesn't count as open.
     */
    static Map<BoulderShape.Cell, Integer> depths(BoulderShape shape, List<BoulderShape.Cell> cells) {
        Set<BoulderShape.Cell> all = new HashSet<>(cells);
        Map<BoulderShape.Cell, Integer> depths = new HashMap<>();
        Deque<BoulderShape.Cell> queue = new ArrayDeque<>();
        for (BoulderShape.Cell cell : cells) {
            for (BoulderShape.Cell side : beside(cell)) {
                if (side.y() >= 0 && !all.contains(side)) {
                    depths.put(cell, 1);
                    queue.add(cell);
                    break;
                }
            }
        }
        while (!queue.isEmpty()) {
            BoulderShape.Cell cell = queue.poll();
            for (BoulderShape.Cell side : beside(cell)) {
                if (all.contains(side) && !depths.containsKey(side)) {
                    depths.put(side, depths.get(cell) + 1);
                    queue.add(side);
                }
            }
        }
        return depths;
    }

    private static List<BoulderShape.Cell> beside(BoulderShape.Cell cell) {
        return List.of(
                new BoulderShape.Cell(cell.x() + 1, cell.y(), cell.z()),
                new BoulderShape.Cell(cell.x() - 1, cell.y(), cell.z()),
                new BoulderShape.Cell(cell.x(), cell.y() + 1, cell.z()),
                new BoulderShape.Cell(cell.x(), cell.y() - 1, cell.z()),
                new BoulderShape.Cell(cell.x(), cell.y(), cell.z() + 1),
                new BoulderShape.Cell(cell.x(), cell.y(), cell.z() - 1));
    }

    /**
     * Its blocks in the level, with the corner of its footprint at {@code corner} (its lowest blocks
     * at {@code corner}'s height), bottom layer first. A block id no longer known (a datapack
     * changed) is stone.
     */
    public Map<BlockPos, BlockState> blocksAt(BlockPos corner) {
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        List<BoulderShape.Cell> cells = shape.cells();
        for (int i = 0; i < cells.size(); i++) {
            BoulderShape.Cell cell = cells.get(i);
            ResourceLocation id = ResourceLocation.tryParse(blocks.get(i));
            BlockState state = id != null && BuiltInRegistries.BLOCK.containsKey(id)
                    ? BuiltInRegistries.BLOCK.get(id).defaultBlockState()
                    : Blocks.STONE.defaultBlockState();
            placed.put(corner.offset(cell.x(), cell.y(), cell.z()), state);
        }
        return placed;
    }

    /**
     * The data of the job that raises it at a site, its footprint's corner at {@code corner} (see
     * {@link #fromJobData}, {@link #cornerIn}).
     */
    public CompoundTag toJobData(BlockPos corner) {
        CompoundTag data = new CompoundTag();
        data.put("boulder", save());
        data.put("corner", NbtUtils.writeBlockPos(corner));
        return data;
    }

    /** The boulder in a summoning job's data ({@link #toJobData}), or null if there's none. */
    @Nullable
    public static BoulderPlan fromJobData(CompoundTag data) {
        return load(data.getCompound("boulder"));
    }

    /** Where the boulder in a summoning job's data goes: its footprint's corner, or null. */
    @Nullable
    public static BlockPos cornerIn(CompoundTag data) {
        return NbtUtils.readBlockPos(data, "corner").orElse(null);
    }

    /** It as data, to save. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", kind);
        tag.putString("style", shape.style().id());
        ListTag columns = new ListTag();
        for (int[] column : shape.heights()) {
            columns.add(new IntArrayTag(column));
        }
        tag.put("heights", columns);
        ListTag ids = new ListTag();
        for (String block : blocks) {
            ids.add(StringTag.valueOf(block));
        }
        tag.put("blocks", ids);
        return tag;
    }

    /** One saved by {@link #save}, or null if there isn't one there (or it doesn't add up). */
    @Nullable
    public static BoulderPlan load(CompoundTag tag) {
        if (!tag.contains("heights", Tag.TAG_LIST) || !tag.contains("blocks", Tag.TAG_LIST)) {
            return null;
        }
        BoulderStyle style = BoulderStyle.DOME;
        for (BoulderStyle one : BoulderStyle.values()) {
            if (one.id().equals(tag.getString("style"))) {
                style = one;
            }
        }
        ListTag columns = tag.getList("heights", Tag.TAG_INT_ARRAY);
        int[][] heights = new int[columns.size()][];
        for (int x = 0; x < columns.size(); x++) {
            heights[x] = columns.getIntArray(x);
        }
        ListTag ids = tag.getList("blocks", Tag.TAG_STRING);
        List<String> blocks = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            blocks.add(ids.getString(i));
        }
        try {
            return new BoulderPlan(tag.getString("kind"), BoulderShape.of(style, heights), blocks);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
