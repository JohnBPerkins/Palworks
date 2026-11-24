package dev.palworks.boulder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * The boulders standing in a dimension: which blocks are a boulder's (for miners to break; nothing
 * else in the world is mined), and who raised each one (an Unearthing worker raises the next once
 * its last is gone). Saved with the level, one per dimension. A block stops being a boulder's as
 * soon as it's anything else (mined, broken by a player, blown up, pushed by a piston: see
 * {@link #onBlockChanged}), and a boulder with no blocks left is gone.
 *
 * <p>Miners work a boulder only as high as one on the ground reaches: its bottom three layers
 * ({@link #REACH}). In each column the block worked is the highest of those (see
 * {@link #isMinable}): a short column's top, so they're taken from the top down; in a taller one,
 * its third block, and what's above it then drops a block ({@link #settleAbove}), as a stack would,
 * so nothing is left hanging. A tall column comes down a block at a time until its top is in
 * reach, and no miner ever needs to climb the boulder. Used on the server thread only.
 */
public final class Boulders extends SavedData {
    private static final String NAME = "palworks_boulders";
    /** How many of a boulder's layers, from the bottom, a miner on the ground reaches. */
    public static final int REACH = 3;

    // While blocks are being moved down (see settleAbove): their changes aren't anything leaving
    private boolean settling;

    private final Map<UUID, Boulder> standing = new LinkedHashMap<>();
    private final Map<BlockPos, UUID> byPos = new HashMap<>();
    // The same blocks by chunk, for the chunk scan
    private final Map<Long, Set<BlockPos>> byChunk = new HashMap<>();

    /** One boulder: who raised it (none for one a command put there), its kind, and its blocks left. */
    public static final class Boulder {
        final UUID id;

        @Nullable
        final UUID summoner;

        final String kind;
        // The height of its bottom layer, on the ground
        final int floor;
        final Map<BlockPos, Block> blocks = new LinkedHashMap<>();

        Boulder(UUID id, @Nullable UUID summoner, String kind, int floor) {
            this.id = id;
            this.summoner = summoner;
            this.kind = kind;
            this.floor = floor;
        }

        @Nullable
        public UUID summoner() {
            return summoner;
        }

        public String kind() {
            return kind;
        }

        /** Its blocks left, bottom up as placed. */
        public Set<BlockPos> positions() {
            return java.util.Collections.unmodifiableSet(blocks.keySet());
        }
    }

    /** This dimension's, loaded from its save the first time. */
    public static Boulders get(ServerLevel level) {
        return level.getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(Boulders::new, Boulders::load, null), NAME);
    }

    /**
     * A boulder just placed: these blocks (as they are in the level now) are its, raised by
     * {@code summoner} (null for one a command placed).
     */
    public void add(@Nullable UUID summoner, String kind, Map<BlockPos, BlockState> placed) {
        int floor = placed.keySet().stream().mapToInt(BlockPos::getY).min().orElse(0);
        Boulder boulder = new Boulder(UUID.randomUUID(), summoner, kind, floor);
        placed.forEach((pos, state) -> {
            BlockPos at = pos.immutable();
            UUID earlier = byPos.get(at);
            if (earlier != null) {
                forget(earlier, at); // can't happen: boulders go only where there's air
            }
            track(at, boulder.id);
            boulder.blocks.put(at, state.getBlock());
        });
        if (!boulder.blocks.isEmpty()) {
            standing.put(boulder.id, boulder);
            setDirty();
        }
    }

    /**
     * Put a boulder's blocks in the level, with its footprint's corner at {@code corner} (see
     * {@link BoulderPlan#blocksAt}), and note them as its: raised by {@code summoner}, or null for
     * one a command put there. Whatever was there is replaced; the summoning job only places one
     * where there's air.
     */
    public Map<BlockPos, BlockState> place(
            ServerLevel level, BlockPos corner, BoulderPlan plan, @Nullable UUID summoner) {
        Map<BlockPos, BlockState> placed = plan.blocksAt(corner);
        placed.forEach((pos, state) -> level.setBlock(pos, state, Block.UPDATE_ALL));
        add(summoner, plan.kind(), placed);
        return placed;
    }

    /** The boulder this block is part of, or null. */
    @Nullable
    public Boulder boulderAt(BlockPos pos) {
        UUID id = byPos.get(pos);
        return id == null ? null : standing.get(id);
    }

    /** Whether a boulder this worker raised is still standing (with any block left at all). */
    public boolean hasStanding(UUID summoner) {
        for (Boulder boulder : standing.values()) {
            if (summoner.equals(boulder.summoner)) {
                return true;
            }
        }
        return false;
    }

    /** Every boulder, oldest first. */
    public List<Boulder> all() {
        return List.copyOf(standing.values());
    }

    /**
     * Whether the block at {@code pos} is a boulder's to mine now: still the block placed there, in
     * the bottom {@link #REACH} layers, and the highest of its column there. A short column's top;
     * a tall one's third block, with what's above it dropping once it's mined.
     */
    public boolean isMinable(BlockPos pos, BlockState state) {
        Boulder boulder = boulderAt(pos);
        if (boulder == null || !state.is(boulder.blocks.get(pos))) {
            return false;
        }
        int layer = pos.getY() - boulder.floor;
        if (layer >= REACH) {
            return false;
        }
        for (int up = 1; layer + up < REACH; up++) {
            if (boulder.blocks.containsKey(pos.above(up))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A boulder's block at {@code mined} is gone: the blocks of its column above it (if any) drop a
     * block, as a stack would, so nothing's left hanging. Call it once the block's broken.
     */
    public void settleAbove(ServerLevel level, BlockPos mined) {
        Boulder boulder = boulderAt(mined.above());
        if (boulder == null) {
            return;
        }
        List<BlockPos> above = new ArrayList<>();
        for (BlockPos pos = mined.above(); boulder.blocks.containsKey(pos); pos = pos.above()) {
            above.add(pos);
        }
        settling = true;
        try {
            BlockPos into = mined.immutable();
            for (BlockPos from : above) {
                level.setBlock(into, level.getBlockState(from), Block.UPDATE_ALL);
                boulder.blocks.put(into, boulder.blocks.get(from));
                track(into, boulder.id);
                into = from;
            }
            level.setBlock(into, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            forget(boulder.id, into);
        } finally {
            settling = false;
        }
        level.playSound(null, mined, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1.0f, 0.6f);
    }

    /** The boulder blocks in a chunk, between {@code minY} (inclusive) and {@code maxY} (exclusive). */
    public List<BlockPos> blocksIn(ChunkPos chunk, int minY, int maxY) {
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : byChunk.getOrDefault(chunk.toLong(), Set.of())) {
            if (pos.getY() >= minY && pos.getY() < maxY) {
                found.add(pos);
            }
        }
        return found;
    }

    /**
     * A block changed: if it was a boulder's and isn't the block placed there any more, it isn't the
     * boulder's now. Called on every block change on the server, so a map lookup for any other block.
     */
    public void onBlockChanged(BlockPos pos, BlockState now) {
        UUID id = byPos.get(pos);
        if (id == null || settling) {
            return;
        }
        Boulder boulder = standing.get(id);
        if (boulder != null && !now.is(boulder.blocks.get(pos))) {
            forget(id, pos);
        }
    }

    private void track(BlockPos pos, UUID id) {
        byPos.put(pos, id);
        byChunk.computeIfAbsent(ChunkPos.asLong(pos), k -> new java.util.HashSet<>())
                .add(pos);
    }

    private void forget(UUID id, BlockPos pos) {
        byPos.remove(pos);
        Set<BlockPos> inChunk = byChunk.get(ChunkPos.asLong(pos));
        if (inChunk != null) {
            inChunk.remove(pos);
            if (inChunk.isEmpty()) {
                byChunk.remove(ChunkPos.asLong(pos));
            }
        }
        Boulder boulder = standing.get(id);
        if (boulder != null) {
            boulder.blocks.remove(pos);
            if (boulder.blocks.isEmpty()) {
                standing.remove(id);
            }
        }
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Boulder boulder : standing.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", boulder.id);
            if (boulder.summoner != null) {
                entry.putUUID("summoner", boulder.summoner);
            }
            entry.putString("kind", boulder.kind);
            entry.putInt("floor", boulder.floor);
            ListTag blocks = new ListTag();
            boulder.blocks.forEach((pos, block) -> {
                CompoundTag one = new CompoundTag();
                one.put("pos", NbtUtils.writeBlockPos(pos));
                one.putString("block", BuiltInRegistries.BLOCK.getKey(block).toString());
                blocks.add(one);
            });
            entry.put("blocks", blocks);
            list.add(entry);
        }
        tag.put("boulders", list);
        return tag;
    }

    // (The registries are for saved items and the like; blocks are looked up by id)
    @SuppressWarnings("PMD.UnusedFormalParameter")
    private static Boulders load(CompoundTag tag, HolderLookup.Provider registries) {
        Boulders data = new Boulders();
        for (Tag t : tag.getList("boulders", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) t;
            Boulder boulder = new Boulder(
                    entry.getUUID("id"),
                    entry.hasUUID("summoner") ? entry.getUUID("summoner") : null,
                    entry.getString("kind"),
                    entry.getInt("floor"));
            for (Tag b : entry.getList("blocks", Tag.TAG_COMPOUND)) {
                CompoundTag one = (CompoundTag) b;
                BlockPos pos = NbtUtils.readBlockPos(one, "pos").orElse(null);
                ResourceLocation id = ResourceLocation.tryParse(one.getString("block"));
                if (pos != null && id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
                    boulder.blocks.put(pos, BuiltInRegistries.BLOCK.get(id));
                    data.track(pos, boulder.id);
                }
            }
            if (!boulder.blocks.isEmpty()) {
                data.standing.put(boulder.id, boulder);
            }
        }
        return data;
    }
}
