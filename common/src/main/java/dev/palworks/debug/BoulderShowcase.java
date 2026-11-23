package dev.palworks.debug;

import dev.palworks.boulder.BoulderKind;
import dev.palworks.boulder.BoulderKinds;
import dev.palworks.boulder.BoulderOreLevels;
import dev.palworks.boulder.BoulderPlan;
import dev.palworks.boulder.BoulderShape;
import dev.palworks.boulder.BoulderSize;
import dev.palworks.boulder.BoulderStyle;
import dev.palworks.boulder.Boulders;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A grid of boulders to look at (the developer command {@code /palworks boulders}): every kind in
 * every style at one Unearthing level (ore as often as in play, one in eight), or one kind in every
 * style at every level, every one with ore, to see where it goes. Built east and
 * south of a corner on a flat smooth stone floor with the air cleared above it, so the terrain
 * doesn't matter, with a sign heading each row and column. Each build rolls new shapes. The
 * boulders are real ones (with no summoner), so miners can be tried on them.
 */
public final class BoulderShowcase {
    /** Where it was built, and how many boulders it has. */
    public record Result(BlockPos min, BlockPos max, int boulders) {}

    // Each cell holds the widest boulder (level 5's footprint), with a gap round it
    private static final int CELL = BoulderSize.forLevel(5).footprint();
    private static final int GAP = 2;
    private static final int PITCH = CELL + GAP;
    // The signs' row and column, before the first cell
    private static final int MARGIN = 2;
    private static final int CLEAR_HEIGHT = BoulderSize.MAX_HEIGHT + 2;
    private static final int FLAGS = Block.UPDATE_ALL;

    private final ServerLevel level;
    private final BlockPos origin;
    private final Random random = new Random();

    private BoulderShowcase(ServerLevel level, BlockPos origin) {
        this.level = level;
        this.origin = origin;
    }

    /** Every kind (a row each) in every style (a column each), at this level. */
    public static Result everyKind(ServerLevel level, BlockPos origin, int unearthingLevel) {
        BoulderShowcase showcase = new BoulderShowcase(level, origin);
        List<String> rows = new ArrayList<>();
        List<List<BoulderPlan>> plans = new ArrayList<>();
        for (BoulderKind kind : BoulderKinds.current().all()) {
            rows.add(kind.id());
            plans.add(showcase.everyStyle(kind, unearthingLevel, false));
        }
        return showcase.build(rows, plans, "Level " + unearthingLevel);
    }

    /** One kind in every style (a column each), at every level (a row each), all with ore. */
    public static Result oneKind(ServerLevel level, BlockPos origin, BoulderKind kind) {
        BoulderShowcase showcase = new BoulderShowcase(level, origin);
        List<String> rows = new ArrayList<>();
        List<List<BoulderPlan>> plans = new ArrayList<>();
        for (int unearthingLevel = 1; unearthingLevel <= 5; unearthingLevel++) {
            rows.add("Level " + unearthingLevel);
            plans.add(showcase.everyStyle(kind, unearthingLevel, true));
        }
        return showcase.build(rows, plans, kind.id() + ", with ore");
    }

    private List<BoulderPlan> everyStyle(BoulderKind kind, int unearthingLevel, boolean withOre) {
        List<BoulderPlan> row = new ArrayList<>();
        for (BoulderStyle style : BoulderStyle.values()) {
            row.add(BoulderPlan.roll(kind, unearthingLevel, style, BoulderOreLevels.current(), random, withOre));
        }
        return row;
    }

    private Result build(List<String> rows, List<List<BoulderPlan>> plans, String title) {
        int columns = BoulderStyle.values().length;
        int width = MARGIN + columns * PITCH;
        int depth = MARGIN + rows.size() * PITCH;
        loadChunks(width, depth);
        floor(width, depth);
        sign(at(0, 0, 0), title);
        int column = 0;
        for (BoulderStyle style : BoulderStyle.values()) {
            sign(at(MARGIN + column * PITCH + CELL / 2, 0, 0), capitalise(style.id()));
            column++;
        }
        Boulders boulders = Boulders.get(level);
        int count = 0;
        for (int r = 0; r < rows.size(); r++) {
            sign(
                    at(0, 0, MARGIN + r * PITCH + CELL / 2),
                    signLines(capitalise(rows.get(r).replace('_', ' '))));
            for (int c = 0; c < plans.get(r).size(); c++) {
                BoulderPlan plan = plans.get(r).get(c);
                BoulderShape shape = plan.shape();
                // In the middle of its cell
                BlockPos corner = at(
                        MARGIN + c * PITCH + (CELL - shape.width()) / 2,
                        0,
                        MARGIN + r * PITCH + (CELL - shape.depth()) / 2);
                boulders.place(level, corner, plan, null);
                count++;
            }
        }
        BlockPos max = origin.offset(width - 1, CLEAR_HEIGHT - 1, depth - 1);
        // Workers already out look round again, so the new boulders are work at once
        dev.palworks.job.JobManager.getInstance(level).rescanArea(origin, max);
        return new Result(origin, max, count);
    }

    private BlockPos at(int x, int y, int z) {
        return origin.offset(x, y, z);
    }

    /** Load every chunk it covers first, so none is still generating while it's written to. */
    private void loadChunks(int width, int depth) {
        for (int cx = (origin.getX() >> 4) - 1; cx <= ((origin.getX() + width - 1) >> 4) + 1; cx++) {
            for (int cz = (origin.getZ() >> 4) - 1; cz <= ((origin.getZ() + depth - 1) >> 4) + 1; cz++) {
                level.getChunk(cx, cz);
            }
        }
    }

    /** A smooth stone floor under it all, with the air cleared above. */
    private void floor(int width, int depth) {
        BlockState floor = Blocks.SMOOTH_STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                set(at(x, -1, z), floor);
                for (int y = 0; y < CLEAR_HEIGHT; y++) {
                    set(at(x, y, z), air);
                }
            }
        }
    }

    private void set(BlockPos pos, BlockState state) {
        if (!level.getBlockState(pos).equals(state)) {
            level.setBlock(pos, state, FLAGS);
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
            level.sendBlockUpdated(pos, sign.getBlockState(), sign.getBlockState(), FLAGS);
        }
    }

    /** Signs hold about 15 characters a line: a label over two lines if it's longer. */
    private static String[] signLines(String label) {
        int split = label.lastIndexOf(' ', 15);
        return label.length() <= 15 || split <= 0
                ? new String[] {label}
                : new String[] {label.substring(0, split), label.substring(split + 1)};
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }
}
