package dev.palworks.tree;

import dev.palworks.Palworks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;

import java.util.*;

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
    private static final int MAX_TREE_BLOCKS = 256;      // Max logs to search before considering tree too big
    private static final int MIN_LEAF_COUNT = 4;         // Minimum leaves required for a valid tree
    private static final int LEAF_SEARCH_RADIUS = 2;     // Radius around logs to search for leaves
    // Huge fungus canopies don't decay like leaves, so they're broken with the stem. Same rule as the
    // FallingTree mod: warts connected to the tree, within this x/z distance of the chopped block, and
    // no higher than one above the top stem.
    private static final int WART_BREAK_RADIUS = 4;
    private static final int MAX_WART_BLOCKS = 512;      // Safety cap on canopy blocks broken per tree

    // Ground blocks that a tree base can touch
    private static final Set<Block> VALID_GROUND_BLOCKS = Set.of(
        Blocks.GRASS_BLOCK,
        Blocks.DIRT,
        Blocks.COARSE_DIRT,
        Blocks.ROOTED_DIRT,
        Blocks.PODZOL,
        Blocks.MYCELIUM,
        Blocks.MUD,
        Blocks.MUDDY_MANGROVE_ROOTS,
        Blocks.MOSS_BLOCK,
        // Nether ground for nether trees
        Blocks.NETHERRACK,
        Blocks.WARPED_NYLIUM,
        Blocks.CRIMSON_NYLIUM,
        Blocks.SOUL_SOIL
    );

    // Directions for BFS traversal (6 cardinal directions)
    private static final Direction[] DIRECTIONS = Direction.values();

    // Cobblemon apricorn tags to exclude from tree detection
    private static final TagKey<Block> APRICORN_LOGS = TagKey.create(Registries.BLOCK, ResourceLocation.parse("cobblemon:apricorn_logs"));
    private static final TagKey<Block> APRICORN_LEAVES = TagKey.create(Registries.BLOCK, ResourceLocation.parse("cobblemon:apricorn_leaves"));

    /**
     * Result of tree detection containing all relevant information.
     */
    public static class TreeValidationResult {
        private final boolean valid;
        private final String reason;
        private final Set<BlockPos> logPositions;
        private final int leafCount;
        private final BlockPos basePosition;

        private TreeValidationResult(boolean valid, String reason, Set<BlockPos> logPositions,
                                      int leafCount, BlockPos basePosition) {
            this.valid = valid;
            this.reason = reason;
            this.logPositions = logPositions != null ? logPositions : Collections.emptySet();
            this.leafCount = leafCount;
            this.basePosition = basePosition;
        }

        public static TreeValidationResult invalid(String reason) {
            return new TreeValidationResult(false, reason, null, 0, null);
        }

        public static TreeValidationResult valid(Set<BlockPos> logs, int leafCount, BlockPos base) {
            return new TreeValidationResult(true, "Valid tree", logs, leafCount, base);
        }

        public boolean isValid() { return valid; }
        public String getReason() { return reason; }
        public Set<BlockPos> getLogPositions() { return logPositions; }
        public int getLeafCount() { return leafCount; }
        public BlockPos getBasePosition() { return basePosition; }
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
        Set<BlockPos> leaves = new HashSet<>();
        BlockPos lowestLog = startPos;
        boolean foundGroundConnection = false;

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
                        leaves.add(neighbor);
                    }
                } else if (isValidGroundBlock(neighborState)) {
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
                        Palworks.LOGGER.debug("Tree at {} has invalid adjacent block {} at {}",
                            startPos, invalidBlock, neighbor);
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
                        if (!leaves.contains(checkPos) && isNaturalCanopy(level.getBlockState(checkPos))) {
                            leaves.add(checkPos);
                        }
                    }
                }
            }
        }

        // Validation checks

        // Check 1: Tree must be touching ground
        // Also check the block directly below the lowest log
        BlockPos belowLowest = lowestLog.below();
        if (isValidGroundBlock(level.getBlockState(belowLowest))) {
            foundGroundConnection = true;
        }

        if (!foundGroundConnection) {
            if (DEBUG) {
                Palworks.LOGGER.debug("Tree at {} is not touching valid ground (lowest log at {})",
                    startPos, lowestLog);
            }
            return TreeValidationResult.invalid("Tree is not touching valid ground");
        }

        // Check 2: Minimum leaf count
        if (leaves.size() < MIN_LEAF_COUNT) {
            if (DEBUG) {
                Palworks.LOGGER.debug("Tree at {} has only {} leaves (minimum: {})",
                    startPos, leaves.size(), MIN_LEAF_COUNT);
            }
            return TreeValidationResult.invalid("Not enough leaves (found " + leaves.size() + ", need " + MIN_LEAF_COUNT + ")");
        }

        if (DEBUG) {
            Palworks.LOGGER.debug("Valid tree found at {}: {} logs, {} leaves, base at {}",
                startPos, visitedLogs.size(), leaves.size(), lowestLog);
        }

        return TreeValidationResult.valid(visitedLogs, leaves.size(), lowestLog);
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
     * mangrove roots but has a thin collision box. Player builds are still kept out by solid blocks
     * and the natural-leaves check.
     */
    private static boolean isAllowedTreeNeighbor(Level level, BlockPos pos, BlockState state) {
        return state.getCollisionShape(level, pos).isEmpty()
            || state.is(Blocks.MOSS_CARPET);
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
     * top stem. Like FallingTree, it doesn't check which tree a block "belongs" to, so a neighbouring
     * fungus whose canopy touches this one within that box loses that part.
     *
     * @param treeBlocks the tree's stems (from {@link TreeValidationResult#getLogPositions()})
     * @param origin the block the chop started from (the job position)
     */
    public static Set<BlockPos> findNetherCanopyToBreak(Level level, Set<BlockPos> treeBlocks, BlockPos origin) {
        int topY = Integer.MIN_VALUE;
        for (BlockPos pos : treeBlocks) {
            topY = Math.max(topY, pos.getY());
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
                        if (isNetherCanopy(level.getBlockState(neighbor))) {
                            canopy.add(neighbor);
                            queue.add(neighbor);
                        }
                    }
                }
            }
        }
        return canopy;
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
     * Whether a sapling item is a nether fungus (planting it needs the Nether suitability too).
     */
    public static boolean isNetherSapling(@Nullable Item saplingItem) {
        return saplingItem == Items.CRIMSON_FUNGUS || saplingItem == Items.WARPED_FUNGUS;
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
        return block == Blocks.VINE ||
               block == Blocks.CAVE_VINES ||
               block == Blocks.CAVE_VINES_PLANT ||
               block == Blocks.TWISTING_VINES ||
               block == Blocks.TWISTING_VINES_PLANT ||
               block == Blocks.WEEPING_VINES ||
               block == Blocks.WEEPING_VINES_PLANT;
    }

    /**
     * Check if a block state is valid ground for a tree base.
     */
    public static boolean isValidGroundBlock(BlockState state) {
        return VALID_GROUND_BLOCKS.contains(state.getBlock());
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
     * Get the sapling item that corresponds to a log block.
     * Returns null if the log type is not recognized.
     */
    @Nullable
    public static Item getSaplingForLog(BlockState logState) {
        Block block = logState.getBlock();

        // Overworld trees
        if (block == Blocks.OAK_LOG || block == Blocks.STRIPPED_OAK_LOG) {
            return Items.OAK_SAPLING;
        }
        if (block == Blocks.BIRCH_LOG || block == Blocks.STRIPPED_BIRCH_LOG) {
            return Items.BIRCH_SAPLING;
        }
        if (block == Blocks.SPRUCE_LOG || block == Blocks.STRIPPED_SPRUCE_LOG) {
            return Items.SPRUCE_SAPLING;
        }
        if (block == Blocks.JUNGLE_LOG || block == Blocks.STRIPPED_JUNGLE_LOG) {
            return Items.JUNGLE_SAPLING;
        }
        if (block == Blocks.ACACIA_LOG || block == Blocks.STRIPPED_ACACIA_LOG) {
            return Items.ACACIA_SAPLING;
        }
        if (block == Blocks.DARK_OAK_LOG || block == Blocks.STRIPPED_DARK_OAK_LOG) {
            return Items.DARK_OAK_SAPLING;
        }
        if (block == Blocks.CHERRY_LOG || block == Blocks.STRIPPED_CHERRY_LOG) {
            return Items.CHERRY_SAPLING;
        }
        if (block == Blocks.MANGROVE_LOG || block == Blocks.STRIPPED_MANGROVE_LOG) {
            return Items.MANGROVE_PROPAGULE;
        }

        // Nether "trees" (fungi)
        if (block == Blocks.CRIMSON_STEM || block == Blocks.STRIPPED_CRIMSON_STEM) {
            return Items.CRIMSON_FUNGUS;
        }
        if (block == Blocks.WARPED_STEM || block == Blocks.STRIPPED_WARPED_STEM) {
            return Items.WARPED_FUNGUS;
        }

        return null;
    }

    /**
     * Get the sapling item for a tree at a given position.
     * Returns null if not a valid tree or log type not recognized.
     */
    @Nullable
    public static Item getSaplingForTree(Level level, BlockPos logPos) {
        BlockState state = level.getBlockState(logPos);
        if (isMangroveRoots(state)) {
            return Items.MANGROVE_PROPAGULE;
        }
        if (!isLog(state)) {
            return null;
        }
        return getSaplingForLog(state);
    }

    /**
     * Get the block that should be placed for a sapling item.
     */
    @Nullable
    public static Block getSaplingBlock(Item saplingItem) {
        if (saplingItem == Items.OAK_SAPLING) return Blocks.OAK_SAPLING;
        if (saplingItem == Items.BIRCH_SAPLING) return Blocks.BIRCH_SAPLING;
        if (saplingItem == Items.SPRUCE_SAPLING) return Blocks.SPRUCE_SAPLING;
        if (saplingItem == Items.JUNGLE_SAPLING) return Blocks.JUNGLE_SAPLING;
        if (saplingItem == Items.ACACIA_SAPLING) return Blocks.ACACIA_SAPLING;
        if (saplingItem == Items.DARK_OAK_SAPLING) return Blocks.DARK_OAK_SAPLING;
        if (saplingItem == Items.CHERRY_SAPLING) return Blocks.CHERRY_SAPLING;
        if (saplingItem == Items.MANGROVE_PROPAGULE) return Blocks.MANGROVE_PROPAGULE;
        if (saplingItem == Items.CRIMSON_FUNGUS) return Blocks.CRIMSON_FUNGUS;
        if (saplingItem == Items.WARPED_FUNGUS) return Blocks.WARPED_FUNGUS;
        return null;
    }

    /**
     * Check if a sapling can be planted at this position.
     * Saplings need air (or, for a mangrove propagule, still water) and valid ground below.
     */
    public static boolean canPlantSaplingAt(Level level, BlockPos pos, Item saplingItem) {
        // Position must be air. A mangrove's base is often in water once its roots are chopped, and
        // propagules can be planted underwater (waterlogged), so they also accept a water source.
        BlockState here = level.getBlockState(pos);
        boolean underwater = saplingItem == Items.MANGROVE_PROPAGULE && isWaterSource(here);
        if (!here.isAir() && !underwater) {
            return false;
        }

        // Ground below must be valid for saplings
        BlockState groundState = level.getBlockState(pos.below());
        Block ground = groundState.getBlock();

        // Most saplings can be planted on grass, dirt, etc.
        return ground == Blocks.GRASS_BLOCK ||
               ground == Blocks.DIRT ||
               ground == Blocks.COARSE_DIRT ||
               ground == Blocks.ROOTED_DIRT ||
               ground == Blocks.PODZOL ||
               ground == Blocks.MYCELIUM ||
               ground == Blocks.MOSS_BLOCK ||
               ground == Blocks.MUD ||
               ground == Blocks.MUDDY_MANGROVE_ROOTS ||
               // Nether fungi need nylium
               ground == Blocks.CRIMSON_NYLIUM ||
               ground == Blocks.WARPED_NYLIUM;
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
        if (isWaterSource(level.getBlockState(pos))
                && saplingState.hasProperty(BlockStateProperties.WATERLOGGED)) {
            saplingState = saplingState.setValue(BlockStateProperties.WATERLOGGED, true);
        }
        level.setBlock(pos, saplingState, 3);
        return true;
    }

    private static boolean isWaterSource(BlockState state) {
        return state.is(Blocks.WATER) && state.getFluidState().isSourceOfType(Fluids.WATER);
    }
}
