package dev.palworks.drops;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The leaves of trees workers have chopped, while they decay: what they drop as they go (saplings,
 * sticks, apples) is the worker's, to pick up (see TrackedDrops, LeavesBlockMixin). Leaves take
 * minutes to decay, so each tree's is kept until none of it is left decaying, saved with the
 * level (one per dimension) so a chunk unloading or the server restarting part way doesn't lose
 * it. Unloaded leaves don't decay, so it's only looked at while its chunks are loaded.
 */
public final class DecayingCanopies extends SavedData {
    private static final String NAME = "palworks_decaying_canopies";
    // A leaf decays once it's more than 6 blocks from a log, so a chopped tree's leaves are all
    // within 6 of where its logs were
    private static final int LEAF_REACH = 6;
    /** How often (ticks) each canopy is looked at to see whether it's done decaying. */
    public static final int CHECK_INTERVAL = 100;
    // The leaves' distances take a few ticks to update after the chop, and a canopy left this
    // many checks while loaded (20 minutes) is let go of even with leaves left (modded ones that
    // never decay, say)
    private static final int MIN_CHECKS = 1;
    private static final int MAX_CHECKS = 240;

    // A saved box: min x, y, z, then max x, y, z
    private static final int BOX_INTS = 6;

    private final List<Canopy> canopies = new ArrayList<>();

    /** This dimension's, loaded from its save the first time. */
    public static DecayingCanopies get(ServerLevel level) {
        return level.getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(DecayingCanopies::new, DecayingCanopies::load, null), NAME);
    }

    /** Note a tree about to be chopped: its leaves, round {@code logs}, will decay. */
    public void add(Set<BlockPos> logs) {
        if (logs.isEmpty()) {
            return;
        }
        BoundingBox box = BoundingBox.encapsulatingPositions(logs).orElseThrow().inflatedBy(LEAF_REACH);
        canopies.add(new Canopy(box, 0));
        setDirty();
    }

    /** Whether leaves at {@code pos} are a chopped tree's, decaying. */
    public boolean contains(BlockPos pos) {
        for (Canopy canopy : canopies) {
            if (canopy.box.isInside(pos)) {
                return true;
            }
        }
        return false;
    }

    /** Every {@link #CHECK_INTERVAL} ticks: let go of the canopies that are done decaying. */
    public void tick(ServerLevel level) {
        if (canopies.isEmpty() || level.getGameTime() % CHECK_INTERVAL != 0) {
            return;
        }
        boolean changed = canopies.removeIf(canopy -> {
            BoundingBox box = canopy.box;
            if (!level.hasChunksAt(box.minX(), box.minZ(), box.maxX(), box.maxZ())) {
                return false; // not loaded: its leaves aren't decaying
            }
            canopy.checks++;
            return canopy.checks > MAX_CHECKS || (canopy.checks > MIN_CHECKS && !hasDecayingLeaves(level, box));
        });
        if (changed) {
            setDirty();
        }
    }

    private static boolean hasDecayingLeaves(ServerLevel level, BoundingBox box) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    BlockState state = level.getBlockState(pos.set(x, y, z));
                    if (state.getBlock() instanceof LeavesBlock
                            && !state.getValue(LeavesBlock.PERSISTENT)
                            && state.getValue(LeavesBlock.DISTANCE) == LeavesBlock.DECAY_DISTANCE) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Canopy canopy : canopies) {
            BoundingBox box = canopy.box;
            CompoundTag entry = new CompoundTag();
            entry.putIntArray(
                    "box", new int[] {box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()});
            entry.putInt("checks", canopy.checks);
            list.add(entry);
        }
        tag.put("canopies", list);
        return tag;
    }

    // (The registries are for saved items and the like; boxes don't need them)
    @SuppressWarnings("PMD.UnusedFormalParameter")
    private static DecayingCanopies load(CompoundTag tag, HolderLookup.Provider registries) {
        DecayingCanopies data = new DecayingCanopies();
        for (Tag t : tag.getList("canopies", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) t;
            int[] b = entry.getIntArray("box");
            if (b.length == BOX_INTS) {
                data.canopies.add(
                        new Canopy(new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]), entry.getInt("checks")));
            }
        }
        return data;
    }

    private static final class Canopy {
        final BoundingBox box;
        int checks;

        Canopy(BoundingBox box, int checks) {
            this.box = box;
            this.checks = checks;
        }
    }
}
