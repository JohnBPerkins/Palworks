package dev.palworks.tree;

import dev.palworks.Palworks;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;

/**
 * Detects valid trees using BFS traversal.
 *
 * A valid tree must:
 * - Have its base log touching grass, dirt, podzol, mud, or similar ground blocks
 * - Consist of blocks tagged as #minecraft:logs, plus mangrove roots (which aren't in that tag)
 * - Have a minimum number of leaves (#minecraft:leaves) within the tree area. For huge fungi
 *   (nether trees) the canopy counts instead: nether/warped wart blocks and shroomlights
 * - Not exceed the maximum block search limit
 * - Not be connected to invalid blocks (only logs, leaves, air, and vines are allowed)
 */
public class TreeDetector {
    private static final boolean DEBUG = Palworks.DEBUG;

    // Configuration constants
    private static final int MAX_TREE_BLOCKS = 256; // Max logs to search before considering tree too big
    private static final int MIN_LEAF_COUNT = 4; // Minimum leaves required for a valid tree
    private static final int LEAF_SEARCH_RADIUS = 2; // Radius around logs to search for leaves
    // Huge fungus canopies don't decay like leaves, so they're broken with the stem. Same rule as the
    // FallingTree mod: warts connected to the tree, within this x/z distance of the chopped block, and
    // no higher than one above the top stem.
    private static final int WART_BREAK_RADIUS = 4;
    private static final int MAX_WART_BLOCKS = 512; // Safety cap on canopy blocks broken per tree
    // How far (x/z) from the chopped block to look for neighbouring fungi's stems: far enough to
    // catch one whose canopy (up to ~4 blocks from its stem) reaches into the wart-break column.
    private static final int NEIGHBOR_STEM_SEARCH_RADIUS = WART_BREAK_RADIUS + 4;

    // Block -> its tree's sapling, found by name (see SaplingNames; empty if none was found).
    // Registries don't change after loading, so this never goes stale.
    private static final Map<Block, Optional<Item>> SAPLING_CACHE = new ConcurrentHashMap<>();

    // Directions for BFS traversal (6 cardinal directions)
    private static final Direction[] DIRECTIONS = Direction.values();

    // Cobblemon apricorn tags to exclude from tree detection
    private static final TagKey<Block> APRICORN_LOGS =
            TagKey.create(Registries.BLOCK, ResourceLocation.parse("cobblemon:apricorn_logs"));
    private static final TagKey<Block> APRICORN_LEAVES =
            TagKey.create(Registries.BLOCK, ResourceLocation.parse("cobblemon:apricorn_leaves"));

    /**
     * Result of tree detection containing all relevant information.
     */
    public static class TreeValidationResult {
        private final boolean valid;
        private final String reason;
        private final Set<BlockPos> logPositions;
        private final int leafCount;
        private final BlockPos basePosition;
        private final Map<Block, Integer> leafBlocks;

        private TreeValidationResult(
                boolean valid,
                String reason,
                Set<BlockPos> logPositions,
                int leafCount,
                BlockPos basePosition,
                Map<Block, Integer> leafBlocks) {
            this.valid = valid;
            this.reason = reason;
            this.logPositions = logPositions != null ? logPositions : Collections.emptySet();
            this.leafCount = leafCount;
            this.basePosition = basePosition;
            this.leafBlocks = leafBlocks != null ? leafBlocks : Collections.emptyMap();
        }

        public static TreeValidationResult invalid(String reason) {
            return new TreeValidationResult(false, reason, null, 0, null, null);
        }

        public static TreeValidationResult valid(
                Set<BlockPos> logs, int leafCount, BlockPos base, Map<Block, Integer> leafBlocks) {
            return new TreeValidationResult(true, "Valid tree", logs, leafCount, base, leafBlocks);
        }

        public boolean isValid() {
            return valid;
        }

        public String getReason() {
            return reason;
        }

        public Set<BlockPos> getLogPositions() {
            return logPositions;
        }

        public int getLeafCount() {
            return leafCount;
        }

        public BlockPos getBasePosition() {
            return basePosition;
        }
        /** The kinds of leaf (canopy) block the tree has, for finding its sapling by name. */
        public Set<Block> getLeafBlocks() {
            return leafBlocks.keySet();
        }
        /** How many of the tree's natural leaves (canopy blocks) are of this kind. */
        public int getLeafCount(Block leaf) {
            return leafBlocks.getOrDefault(leaf, 0);
        }
    }

    /**
     * Check if a position contains a valid tree that can be chopped.
     *
     * @param level The world
     * @param startPos Position of a log block to start detection from
     * @return TreeValidationResult containing validation status and tree data
     */
    public static TreeValidationResult validateTree(Level level, BlockPos startPos) {
        BlockState startState = level.getBlockState(startPos);

        // First check: is this even part of a tree?
        if (!isTreeBlock(startState)) {
            return TreeValidationResult.invalid("Starting position is not a log");
        }

        // BFS to find all connected logs and validate the tree structure
        Set<BlockPos> visitedLogs = new HashSet<>();
        Set<BlockPos> visitedForValidation = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();
        Map<BlockPos, Block> leaves = new HashMap<>();
        BlockPos lowestLog = startPos;
        boolean foundGroundConnection = false;
        // Anything this tree's sapling can grow on counts as ground (modded soils), as well as the
        // usual ground tags. Null if no sapling was found; then only the tags count.
        @Nullable Item sapling = saplingFor(startState.getBlock());

        queue.add(startPos);
        visitedLogs.add(startPos);

        while (!queue.isEmpty()) {
            // Check if tree is too big
            if (visitedLogs.size() > MAX_TREE_BLOCKS) {
                if (DEBUG) {
                    Palworks.LOGGER.debug("Tree at {} exceeds max size of {} blocks", startPos, MAX_TREE_BLOCKS);
                }
                return TreeValidationResult.invalid("Tree exceeds maximum size of " + MAX_TREE_BLOCKS + " blocks");
            }

            BlockPos current = queue.poll();

            // Track lowest log for ground check
            if (current.getY() < lowestLog.getY()) {
                lowestLog = current;
            }

            // Logs touching only at an edge or corner are part of the tree too: big oaks' branches
            // and acacias' bent trunks are joined diagonally. (Only face neighbours are checked for
            // what the tree may touch, below.)
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int faces = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
                        if (faces < 2) {
                            continue; // itself, or a face neighbour (handled below)
                        }
                        BlockPos diagonal = current.offset(dx, dy, dz);
                        if (!visitedLogs.contains(diagonal) && isTreeBlock(level.getBlockState(diagonal))) {
                            visitedLogs.add(diagonal);
                            queue.add(diagonal);
                        }
                    }
                }
            }

            // Check all 6 directions for connected blocks
            for (Direction dir : DIRECTIONS) {
                BlockPos neighbor = current.relative(dir);

                // Skip if already processed for validation
                if (visitedForValidation.contains(neighbor)) {
                    continue;
                }
                visitedForValidation.add(neighbor);

                BlockState neighborState = level.getBlockState(neighbor);

                // Check what type of block this is
                if (isTreeBlock(neighborState)) {
                    // Connected log (or mangrove root) - add to BFS if not visited
                    if (!visitedLogs.contains(neighbor)) {
                        visitedLogs.add(neighbor);
                        queue.add(neighbor);
                    }
                } else if (isLeaf(neighborState) || isNetherCanopy(neighborState)) {
                    // Leaf (or huge fungus canopy) block - allowed touching the tree, but only natural
                    // ones count toward MIN_LEAF_COUNT, so a log pillar dressed with player-placed
                    // leaves isn't a tree.
                    if (isNaturalCanopy(neighborState)) {
                        leaves.put(neighbor, neighborState.getBlock());
                    }
                } else if (isValidGroundBlock(neighborState) || isSoilFor(sapling, level, neighbor)) {
                    // Ground block - valid connection point
                    foundGroundConnection = true;
                } else if (neighborState.isAir()) {
                    // Air is fine
                } else if (isVine(neighborState)) {
                    // Vines are allowed (swamp trees have vines)
                } else if (isAllowedTreeNeighbor(level, neighbor, neighborState)) {
                    // Plants and other no-collision blocks, water, moss carpet
                } else {
                    // Invalid block touching the tree!
                    Block invalidBlock = neighborState.getBlock();
                    if (DEBUG) {
                        Palworks.LOGGER.debug(
                                "Tree at {} has invalid adjacent block {} at {}", startPos, invalidBlock, neighbor);
                    }
                    return TreeValidationResult.invalid("Tree is touching invalid block: " + invalidBlock);
                }
            }
        }

        // Also search for leaves in a radius around the logs (leaves might not be directly adjacent).
        // Mangrove roots are skipped: they're at the bottom of the tree, away from the canopy.
        for (BlockPos logPos : visitedLogs) {
            if (isMangroveRoots(level.getBlockState(logPos))) {
                continue;
            }
            for (int dx = -LEAF_SEARCH_RADIUS; dx <= LEAF_SEARCH_RADIUS; dx++) {
                for (int dy = -LEAF_SEARCH_RADIUS; dy <= LEAF_SEARCH_RADIUS; dy++) {
                    for (int dz = -LEAF_SEARCH_RADIUS; dz <= LEAF_SEARCH_RADIUS; dz++) {
                        BlockPos checkPos = logPos.offset(dx, dy, dz);
                        if (leaves.containsKey(checkPos)) {
                            continue;
                        }
                        BlockState checkState = level.getBlockState(checkPos);
                        if (isNaturalCanopy(checkState)) {
                            leaves.put(checkPos, checkState.getBlock());
                        }
                    }
                }
            }
        }

        // Validation checks

        // Check 1: Tree must be touching ground
        // Also check the block directly below the lowest log
        BlockPos belowLowest = lowestLog.below();
        if (isValidGroundBlock(level.getBlockState(belowLowest)) || isSoilFor(sapling, level, belowLowest)) {
            foundGroundConnection = true;
        }

        if (!foundGroundConnection) {
            if (DEBUG) {
                Palworks.LOGGER.debug(
                        "Tree at {} is not touching valid ground (lowest log at {})", startPos, lowestLog);
            }
            return TreeValidationResult.invalid("Tree is not touching valid ground");
        }

        // Check 2: Minimum leaf count
        if (leaves.size() < MIN_LEAF_COUNT) {
            if (DEBUG) {
                Palworks.LOGGER.debug(
                        "Tree at {} has only {} leaves (minimum: {})", startPos, leaves.size(), MIN_LEAF_COUNT);
            }
            return TreeValidationResult.invalid(
                    "Not enough leaves (found " + leaves.size() + ", need " + MIN_LEAF_COUNT + ")");
        }

        if (DEBUG) {
            Palworks.LOGGER.debug(
                    "Valid tree found at {}: {} logs, {} leaves, base at {}",
                    startPos,
                    visitedLogs.size(),
                    leaves.size(),
                    lowestLog);
        }

        Map<Block, Integer> leafBlocks = new HashMap<>();
        for (Block leaf : leaves.values()) {
            leafBlocks.merge(leaf, 1, Integer::sum);
        }
        return TreeValidationResult.valid(visitedLogs, leaves.size(), lowestLog, leafBlocks);
    }

    /**
     * Simple check if a position is part of a valid tree (for job scanning).
     */
    public static boolean isValidTree(Level level, BlockPos pos) {
        return validateTree(level, pos).isValid();
    }

    /**
     * Check if a block state is a log (uses #minecraft:logs tag).
     * Excludes Cobblemon apricorn logs.
     */
    public static boolean isLog(BlockState state) {
        return state.is(BlockTags.LOGS) && !state.is(APRICORN_LOGS);
    }

    /**
     * Mangrove roots: part of a mangrove (chopped with it, and how it reaches the ground), but not
     * in #minecraft:logs. Muddy mangrove roots count as ground instead (they're in #minecraft:dirt).
     */
    public static boolean isMangroveRoots(BlockState state) {
        return state.is(Blocks.MANGROVE_ROOTS);
    }

    /**
     * A block that makes up a tree and is chopped with it: a log, or a mangrove root.
     */
    public static boolean isTreeBlock(BlockState state) {
        return isLog(state) || isMangroveRoots(state);
    }

    /**
     * Blocks that may touch a tree without disqualifying it, besides air, leaves, vines and ground.
     * Anything you can walk through: short grass, ferns and flowers next to a trunk, snow layers,
     * water around mangrove roots, hanging mangrove propagules. Plus moss carpet, which grows on
     * mangrove roots but has a thin collision box, and cocoa pods, which grow on jungle trunks (and
     * pop off when it's chopped). Player builds are still kept out by solid blocks and the
     * natural-leaves check. (Bee nests still get a tree skipped; they're rare.)
     */
    private static boolean isAllowedTreeNeighbor(Level level, BlockPos pos, BlockState state) {
        return state.getCollisionShape(level, pos).isEmpty()
                || state.is(Blocks.MOSS_CARPET)
                || state.getBlock() instanceof CocoaBlock;
    }

    /**
     * Check if a block state is a leaf (uses #minecraft:leaves tag).
     * Excludes Cobblemon apricorn leaves.
     */
    public static boolean isLeaf(BlockState state) {
        return state.is(BlockTags.LEAVES) && !state.is(APRICORN_LEAVES);
    }

    /**
     * Check if a block state is a leaf that grew naturally (from worldgen or a sapling), as opposed
     * to one a player placed. Vanilla marks player-placed leaves with {@code persistent=true}. Leaves
     * without that property (some modded leaves) can't be told apart, so they count as natural.
     */
    public static boolean isNaturalLeaf(BlockState state) {
        if (!isLeaf(state)) {
            return false;
        }
        return !state.hasProperty(LeavesBlock.PERSISTENT) || !state.getValue(LeavesBlock.PERSISTENT);
    }

    /**
     * The canopy of a huge fungus (nether tree): nether wart blocks, warped wart blocks and
     * shroomlights. These play the part leaves do for overworld trees.
     */
    public static boolean isNetherCanopy(BlockState state) {
        return state.is(BlockTags.WART_BLOCKS) || state.is(Blocks.SHROOMLIGHT);
    }

    /**
     * The huge fungus canopy to break along with a chopped nether tree, since wart blocks and
     * shroomlights never decay the way leaves do. Copies the FallingTree mod's rule: canopy blocks
     * reachable from the tree's stems through other canopy blocks (diagonals included), within
     * {@link #WART_BREAK_RADIUS} blocks of {@code origin} on x and z, and at most one block above the
     * top stem.
     *
     * <p>Unlike FallingTree, neighbouring fungi keep their canopy: each canopy block belongs to the
     * nearest stem (by x/z distance; ties go to the tree being chopped), and blocks that belong to a
     * neighbour are neither broken nor searched through. So where two canopies touch, each fungus
     * keeps roughly its own half.
     *
     * @param treeBlocks the tree's stems (from {@link TreeValidationResult#getLogPositions()})
     * @param origin the block the chop started from (the job position)
     */
    public static Set<BlockPos> findNetherCanopyToBreak(Level level, Set<BlockPos> treeBlocks, BlockPos origin) {
        int topY = Integer.MIN_VALUE;
        int bottomY = Integer.MAX_VALUE;
        Set<Long> ownColumns = new HashSet<>();
        for (BlockPos pos : treeBlocks) {
            topY = Math.max(topY, pos.getY());
            bottomY = Math.min(bottomY, pos.getY());
            ownColumns.add(columnKey(pos.getX(), pos.getZ()));
        }

        // Stems of neighbouring fungi near enough for their canopy to reach into the break column
        Set<Long> neighborColumns = new HashSet<>();
        BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos();
        for (int x = origin.getX() - NEIGHBOR_STEM_SEARCH_RADIUS;
                x <= origin.getX() + NEIGHBOR_STEM_SEARCH_RADIUS;
                x++) {
            for (int z = origin.getZ() - NEIGHBOR_STEM_SEARCH_RADIUS;
                    z <= origin.getZ() + NEIGHBOR_STEM_SEARCH_RADIUS;
                    z++) {
                long column = columnKey(x, z);
                if (ownColumns.contains(column) || neighborColumns.contains(column)) {
                    continue;
                }
                for (int y = bottomY; y <= topY + 1; y++) {
                    if (isNetherStem(level.getBlockState(scan.set(x, y, z)))) {
                        neighborColumns.add(column);
                        break;
                    }
                }
            }
        }

        Set<BlockPos> canopy = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>(treeBlocks);
        Queue<BlockPos> queue = new ArrayDeque<>(treeBlocks);
        while (!queue.isEmpty() && canopy.size() < MAX_WART_BLOCKS) {
            BlockPos current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos neighbor = current.offset(dx, dy, dz);
                        if (!visited.add(neighbor)
                                || Math.abs(neighbor.getX() - origin.getX()) > WART_BREAK_RADIUS
                                || Math.abs(neighbor.getZ() - origin.getZ()) > WART_BREAK_RADIUS
                                || neighbor.getY() > topY + 1) {
                            continue;
                        }
                        if (isNetherCanopy(level.getBlockState(neighbor))
                                && !isCloserToNeighbor(neighbor, ownColumns, neighborColumns)) {
                            canopy.add(neighbor);
                            queue.add(neighbor);
                        }
                    }
                }
            }
        }
        return canopy;
    }

    /** Whether a canopy block is horizontally closer to a neighbouring fungus's stem than to ours. */
    private static boolean isCloserToNeighbor(BlockPos pos, Set<Long> ownColumns, Set<Long> neighborColumns) {
        if (neighborColumns.isEmpty()) {
            return false;
        }
        return nearestColumnDistSq(pos, neighborColumns) < nearestColumnDistSq(pos, ownColumns);
    }

    private static long nearestColumnDistSq(BlockPos pos, Set<Long> columns) {
        long best = Long.MAX_VALUE;
        for (long column : columns) {
            long dx = pos.getX() - columnX(column);
            long dz = pos.getZ() - columnZ(column);
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best;
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int columnX(long key) {
        return (int) (key >> 32);
    }

    private static int columnZ(long key) {
        return (int) key;
    }

    /**
     * A canopy block that counts toward MIN_LEAF_COUNT: a natural leaf, or a huge fungus canopy
     * block. Unlike leaves, wart blocks and shroomlights have no "player placed" flag, so they always
     * count; few builds use them, and solid blocks around a "trunk" still rule most builds out.
     */
    private static boolean isNaturalCanopy(BlockState state) {
        return isNaturalLeaf(state) || isNetherCanopy(state);
    }

    /**
     * A crimson or warped stem (or hyphae, stripped or not): the "log" of a nether tree. Chopping a
     * nether tree needs the Nether suitability as well as Lumbering.
     */
    public static boolean isNetherStem(BlockState state) {
        return state.is(BlockTags.CRIMSON_STEMS) || state.is(BlockTags.WARPED_STEMS);
    }

    /**
     * Check if a block state is a vine.
     */
    public static boolean isVine(BlockState state) {
        // Check for vanilla vines
        if (state.getBlock() instanceof VineBlock) {
            return true;
        }
        // Also check the block directly
        Block block = state.getBlock();
        return block == Blocks.VINE
                || block == Blocks.CAVE_VINES
                || block == Blocks.CAVE_VINES_PLANT
                || block == Blocks.TWISTING_VINES
                || block == Blocks.TWISTING_VINES_PLANT
                || block == Blocks.WEEPING_VINES
                || block == Blocks.WEEPING_VINES_PLANT;
    }

    /**
     * Check if a block state is valid ground for a tree base.
     */
    public static boolean isValidGroundBlock(BlockState state) {
        // #minecraft:dirt is what vanilla saplings grow on (grass, dirt, podzol, mycelium, moss, mud,
        // muddy mangrove roots...), and tree mods add their own soils to it so vanilla saplings work
        // there too. Plus the nether: nylium, netherrack and soul soil.
        return state.is(BlockTags.DIRT)
                || state.is(BlockTags.NYLIUM)
                || state.is(Blocks.NETHERRACK)
                || state.is(Blocks.SOUL_SOIL);
    }

    /**
     * Whether a tree block stands on ground, making it a possible base for the tree (job discovery
     * looks for these). Ground is {@link #isValidGroundBlock} or anything the tree's sapling can
     * grow on, which covers modded soils that aren't tagged.
     */
    public static boolean standsOnGround(Level level, BlockPos pos, BlockState state) {
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        if (isValidGroundBlock(belowState)) {
            return true;
        }
        // Cheap reject first: most tree blocks stand on another log, or on air or leaves (branches)
        if (belowState.isAir() || isTreeBlock(belowState) || isLeaf(belowState)) {
            return false;
        }
        return isSoilFor(saplingFor(state.getBlock()), level, below);
    }

    /** Whether a sapling (item) could grow on the block at {@code soilPos}. False if it's null. */
    private static boolean isSoilFor(@Nullable Item sapling, Level level, BlockPos soilPos) {
        Block saplingBlock = getSaplingBlock(sapling);
        // Saplings check the block below them, so ask as if it were planted on top of the soil
        return saplingBlock != null && saplingBlock.defaultBlockState().canSurvive(level, soilPos.above());
    }

    /**
     * Get all log positions in a valid tree (for chopping).
     * Returns empty set if tree is invalid.
     */
    public static Set<BlockPos> getTreeLogs(Level level, BlockPos startPos) {
        TreeValidationResult result = validateTree(level, startPos);
        return result.isValid() ? result.getLogPositions() : Collections.emptySet();
    }

    /**
     * The sapling for a tree, found by name from its blocks: first its logs (or roots), then its
     * leaves. E.g. "minecraft:dark_oak_log" -> "minecraft:dark_oak_sapling", "minecraft:mangrove_roots"
     * -> "minecraft:mangrove_propagule", "minecraft:crimson_stem" -> "minecraft:crimson_fungus", and
     * the same for a mod's "somemod:redwood_log" -> "somemod:redwood_sapling". Null if none is found:
     * the tree is still chopped, just not replanted.
     */
    @Nullable
    public static Item getSaplingForTree(Level level, TreeValidationResult tree) {
        // Azalea trees grow oak logs, so their logs would name an oak sapling: an azalea grows them.
        // Only when most of the canopy is azalea leaves, so an oak whose leaves happen to reach an
        // azalea's isn't replanted as one.
        int azalea = tree.getLeafCount(Blocks.AZALEA_LEAVES);
        int flowering = tree.getLeafCount(Blocks.FLOWERING_AZALEA_LEAVES);
        if ((azalea + flowering) * 2 > tree.getLeafCount()) {
            return azalea >= flowering ? Items.AZALEA : Items.FLOWERING_AZALEA;
        }

        Set<Block> tried = new HashSet<>();
        for (BlockPos pos : tree.getLogPositions()) {
            Block block = level.getBlockState(pos).getBlock();
            if (tried.add(block)) {
                Item sapling = saplingFor(block);
                if (sapling != null) {
                    return sapling;
                }
            }
        }
        for (Block leaf : tree.getLeafBlocks()) {
            Item sapling = saplingFor(leaf);
            if (sapling != null) {
                return sapling;
            }
        }
        return null;
    }

    /** The sapling for a log, root or leaf block, found by name (cached); null if none. */
    @Nullable
    public static Item saplingFor(Block block) {
        return SAPLING_CACHE
                .computeIfAbsent(block, TreeDetector::findSaplingByName)
                .orElse(null);
    }

    private static Optional<Item> findSaplingByName(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        for (String path : SaplingNames.saplingCandidates(id.getPath())) {
            ResourceLocation candidate = ResourceLocation.tryBuild(id.getNamespace(), path);
            if (candidate == null) {
                continue;
            }
            Optional<Item> item = BuiltInRegistries.ITEM.getOptional(candidate);
            if (item.isPresent() && item.get() instanceof BlockItem) {
                return item;
            }
        }
        return Optional.empty();
    }

    /**
     * The block a sapling item places (saplings are block items), or null if it isn't one.
     */
    @Nullable
    public static Block getSaplingBlock(@Nullable Item saplingItem) {
        return saplingItem instanceof BlockItem blockItem ? blockItem.getBlock() : null;
    }

    /**
     * Where to replant a chopped tree, worked out before it's chopped. A tree whose trunk stands on
     * a 2x2 of logs (dark oak, which only grows from a 2x2 of saplings, and giant spruce and jungle)
     * is replanted as a 2x2 in the same four spots; any other tree gets one sapling at its base.
     * Based on the trunk's shape rather than a list of tree types, so it covers any 2x2 tree.
     * Mangroves stand on roots, not logs, so they always get one propagule, under the trunk (see
     * {@link #mangrovePropagulePos}).
     *
     * @param treeBlocks the tree's blocks (from {@link TreeValidationResult#getLogPositions()})
     * @param basePos the tree's lowest block (from {@link TreeValidationResult#getBasePosition()})
     */
    public static List<BlockPos> saplingPositions(Level level, Set<BlockPos> treeBlocks, BlockPos basePos) {
        BlockPos propagule = mangrovePropagulePos(level, treeBlocks);
        if (propagule != null) {
            return List.of(propagule);
        }
        int bottomY = Integer.MAX_VALUE;
        for (BlockPos pos : treeBlocks) {
            bottomY = Math.min(bottomY, pos.getY());
        }
        List<BlockPos> bottom = new ArrayList<>();
        for (BlockPos pos : treeBlocks) {
            if (pos.getY() == bottomY) {
                bottom.add(pos);
            }
        }
        if (bottom.size() == 4
                && isTwoByTwo(bottom)
                && bottom.stream().allMatch(pos -> isLog(level.getBlockState(pos)))) {
            return bottom;
        }
        return List.of(basePos);
    }

    /** How far below a mangrove's trunk to look for the ground its propagule grew on. */
    private static final int MAX_PROPAGULE_DROP = 8;

    /**
     * Where a mangrove's propagule grew: under its trunk, on the ground. A mangrove's lowest block is
     * one of its roots, which spread out a few blocks round the trunk, so replanting there put the
     * new tree off to one side. Its trunk (its lowest log: branches only go up from it) stands on
     * roots that go down to where the propagule was; the spot is the one above the first solid block
     * below the trunk that isn't a root. Null for a tree with no roots (not a mangrove), or
     * if no ground is found close under the trunk.
     */
    @Nullable
    private static BlockPos mangrovePropagulePos(Level level, Set<BlockPos> treeBlocks) {
        BlockPos trunkBottom = null;
        boolean hasRoots = false;
        for (BlockPos pos : treeBlocks) {
            BlockState state = level.getBlockState(pos);
            if (isMangroveRoots(state)) {
                hasRoots = true;
            } else if (isLog(state) && (trunkBottom == null || pos.getY() < trunkBottom.getY())) {
                trunkBottom = pos;
            }
        }
        if (!hasRoots || trunkBottom == null) {
            return null;
        }
        BlockPos.MutableBlockPos below = trunkBottom.mutable();
        for (int i = 0; i < MAX_PROPAGULE_DROP; i++) {
            below.move(Direction.DOWN);
            BlockState state = level.getBlockState(below);
            // Roots, and anything the propagule could have stood in: air, water, seagrass...
            if (!isMangroveRoots(state)
                    && !state.getCollisionShape(level, below).isEmpty()) {
                return below.above();
            }
        }
        return null;
    }

    /**
     * The position a tree's chop job goes at: its lowest block standing on ground (lowest, then by
     * x, then by z, so it's the same whichever of the tree's blocks the scan found it from). A tree
     * with several bases (a mangrove's roots, a 2x2 trunk) seen from more than one chunk, or from
     * scans of different heights, used to get a job at each base found.
     */
    public static BlockPos chopPosition(Level level, TreeValidationResult tree) {
        BlockPos best = null;
        for (BlockPos pos : tree.getLogPositions()) {
            if (best != null && !isBefore(pos, best)) {
                continue;
            }
            if (standsOnGround(level, pos, level.getBlockState(pos))) {
                best = pos;
            }
        }
        return best != null ? best : tree.getBasePosition();
    }

    /** Lower first, then lower x, then lower z. */
    private static boolean isBefore(BlockPos a, BlockPos b) {
        if (a.getY() != b.getY()) {
            return a.getY() < b.getY();
        }
        if (a.getX() != b.getX()) {
            return a.getX() < b.getX();
        }
        return a.getZ() < b.getZ();
    }

    /** Whether four positions (on one Y level) form a 2x2 square. */
    private static boolean isTwoByTwo(List<BlockPos> positions) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : positions) {
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        // Four distinct positions within a 2x2 box fill it
        return maxX - minX == 1 && maxZ - minZ == 1 && new HashSet<>(positions).size() == 4;
    }

    /**
     * Check if a sapling can be planted at this position: the spot is air (or a water source, for a
     * sapling that can be waterlogged, like a mangrove propagule), and the sapling itself says it
     * can grow there. Asking the sapling covers every soil rule, vanilla or modded: grass and dirt
     * for most saplings, mud and clay for propagules, nylium for fungi.
     */
    public static boolean canPlantSaplingAt(Level level, BlockPos pos, Item saplingItem) {
        Block saplingBlock = getSaplingBlock(saplingItem);
        if (saplingBlock == null) {
            return false;
        }
        BlockState saplingState = saplingBlock.defaultBlockState();
        BlockState here = level.getBlockState(pos);
        boolean underwater = isWaterSource(here) && saplingState.hasProperty(BlockStateProperties.WATERLOGGED);
        if (!here.isAir() && !underwater) {
            return false;
        }
        return saplingState.canSurvive(level, pos);
    }

    /**
     * Plant a sapling at the given position.
     * Returns true if successful.
     */
    public static boolean plantSapling(Level level, BlockPos pos, Item saplingItem) {
        if (!canPlantSaplingAt(level, pos, saplingItem)) {
            return false;
        }

        Block saplingBlock = getSaplingBlock(saplingItem);
        if (saplingBlock == null) {
            return false;
        }

        BlockState saplingState = saplingBlock.defaultBlockState();
        if (isWaterSource(level.getBlockState(pos)) && saplingState.hasProperty(BlockStateProperties.WATERLOGGED)) {
            saplingState = saplingState.setValue(BlockStateProperties.WATERLOGGED, true);
        }
        level.setBlock(pos, saplingState, 3);
        return true;
    }

    private static boolean isWaterSource(BlockState state) {
        return state.is(Blocks.WATER) && state.getFluidState().isSourceOfType(Fluids.WATER);
    }
}
