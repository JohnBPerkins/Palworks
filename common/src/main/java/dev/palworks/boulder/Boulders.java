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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * The boulders standing in a dimension: which blocks are a boulder's (for miners to break; nothing
 * else in the world is mined), and who raised each one (a Stoneshaping worker raises the next once
 * its last is gone). Saved with the level, one per dimension. A block stops being a boulder's as
 * soon as it's anything else (mined, broken by a player, blown up, pushed by a piston: see
 * {@link #onBlockChanged}), and a boulder with no blocks left is gone.
 *
 * <p>Miners take a boulder from the top down: a block is work only with no block of the boulder on
 * it, and only in the top two layers it has left ({@link #isMinable}), so a tall column's top is
 * never left standing out of reach after the blocks round it are gone. Used on the server thread
 * only.
 */
public final class Boulders extends SavedData {
    private static final String NAME = "palworks_boulders";
    // A block is mined only this many layers down from the boulder's top, at most
    private static final int LAYERS_WORKED = 2;

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
        final Map<BlockPos, Block> blocks = new LinkedHashMap<>();

        Boulder(UUID id, @Nullable UUID summoner, String kind) {
            this.id = id;
            this.summoner = summoner;
            this.kind = kind;
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

        /** The height of its highest block left. */
        int top() {
            int top = Integer.MIN_VALUE;
            for (BlockPos pos : blocks.keySet()) {
                top = Math.max(top, pos.getY());
            }
            return top;
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
        Boulder boulder = new Boulder(UUID.randomUUID(), summoner, kind);
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
     * Whether the block at {@code pos} is a boulder's to mine now: still the block placed there,
     * nothing of the boulder on top of it, and in the top {@link #LAYERS_WORKED} layers it has left.
     */
    public boolean isMinable(BlockPos pos, BlockState state) {
        Boulder boulder = boulderAt(pos);
        return boulder != null
                && state.is(boulder.blocks.get(pos))
                && !boulder.blocks.containsKey(pos.above())
                && pos.getY() > boulder.top() - LAYERS_WORKED;
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
     * boulder's now. Returns the boulder's blocks left to look at again if that changed which are
     * work (its top went down a layer), else nothing. Called on every block change on the server, so
     * a map lookup for any other block.
     */
    public List<BlockPos> onBlockChanged(BlockPos pos, BlockState now) {
        UUID id = byPos.get(pos);
        if (id == null) {
            return List.of();
        }
        Boulder boulder = standing.get(id);
        if (boulder == null || now.is(boulder.blocks.get(pos))) {
            return List.of();
        }
        int topBefore = boulder.top();
        forget(id, pos);
        Boulder left = standing.get(id);
        return left != null && left.top() != topBefore ? List.copyOf(left.blocks.keySet()) : List.of();
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
                    entry.getString("kind"));
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
