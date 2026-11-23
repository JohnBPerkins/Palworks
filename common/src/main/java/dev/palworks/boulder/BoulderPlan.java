package dev.palworks.boulder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * pattern, and, about one time in ten ({@link BoulderSize#ORE_BOULDER_CHANCE}), ore here and
     * there in it.
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
        SmoothNoise noise = new SmoothNoise(random.nextLong());
        // Strata lean a little, a different way in each boulder
        double tiltX = (random.nextDouble() - 0.5) * 0.4;
        double tiltZ = (random.nextDouble() - 0.5) * 0.4;
        double shift = random.nextDouble();
        double[] out = fromMiddle(shape, cells);
        List<String> blocks = new ArrayList<>(cells.size());
        for (int i = 0; i < cells.size(); i++) {
            BoulderShape.Cell cell = cells.get(i);
            blocks.add(kind.pattern()
                    .pick(
                            kind.stone(),
                            new BoulderPattern.Spot(
                                    cell,
                                    shape.heightAt(cell.x(), cell.z()) == cell.y() + 1,
                                    shape.height(),
                                    out[i],
                                    noise,
                                    tiltX,
                                    tiltZ,
                                    shift,
                                    random.nextDouble(),
                                    random.nextDouble())));
        }
        if (alwaysOre || random.nextDouble() < BoulderSize.ORE_BOULDER_CHANCE) {
            addOre(kind, level, oreLevels, random, blocks);
        }
        return new BoulderPlan(kind.id(), shape, blocks);
    }

    /**
     * The ore in a boulder with ore: the level's number of ore blocks (see {@link BoulderSize}),
     * each anywhere in it at random, each one of the ores the level has unlocked, by weight. Nothing
     * if the level has none unlocked yet.
     */
    private static void addOre(
            BoulderKind kind, int level, BoulderOreLevels oreLevels, RandomGenerator random, List<String> blocks) {
        BoulderSize size = BoulderSize.forLevel(level);
        int count = Math.min(blocks.size(), random.nextInt(size.minOre(), size.maxOre() + 1));
        List<Integer> spots = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            spots.add(i);
        }
        for (int i = 0; i < count; i++) {
            String ore = kind.pickOre(level, oreLevels, random);
            if (ore == null) {
                return;
            }
            // A spot not taken yet: the one picked swapped for the last, which goes
            int pick = random.nextInt(spots.size());
            blocks.set(spots.get(pick), ore);
            spots.set(pick, spots.getLast());
            spots.removeLast();
        }
    }

    /**
     * How far each block is from the middle of the boulder's base, from 0 for its nearest block to 1
     * for its furthest: what a shell's layers go by, so every boulder, however small, has an
     * outside and a middle.
     */
    static double[] fromMiddle(BoulderShape shape, List<BoulderShape.Cell> cells) {
        double[] out = new double[cells.size()];
        double nearest = Double.MAX_VALUE;
        double furthest = 0;
        for (int i = 0; i < cells.size(); i++) {
            BoulderShape.Cell cell = cells.get(i);
            double x = (cell.x() + 0.5) / shape.width() * 2 - 1;
            double z = (cell.z() + 0.5) / shape.depth() * 2 - 1;
            double y = (cell.y() + 0.5) / Math.max(1, shape.height());
            out[i] = Math.sqrt(x * x + y * y + z * z);
            nearest = Math.min(nearest, out[i]);
            furthest = Math.max(furthest, out[i]);
        }
        double span = Math.max(1e-6, furthest - nearest);
        for (int i = 0; i < out.length; i++) {
            out[i] = (out[i] - nearest) / span;
        }
        return out;
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
