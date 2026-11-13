package dev.palworks.job;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.block.TroughBlock;
import dev.palworks.block.TroughBlockEntity;
import dev.palworks.config.PalworksConfig;
import dev.palworks.crop.CropAdapter;
import dev.palworks.crop.Watering;
import dev.palworks.hunger.Hunger;
import dev.palworks.hunger.TroughFood;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.station.ChestHelper;
import dev.palworks.station.FurnaceHelper;
import dev.palworks.suitability.SuitabilityDetector;
import dev.palworks.tree.TreeDetector;
import dev.palworks.worker.WorkerStateManager;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Whether a job is still there in the world, and whether a worker can do it: the checks that
 * jobs are found, handed out and worked by. No state, so WorkerAI's steps ask them too.
 */
public final class JobChecks {
    /**
     * How far round a pickup job's spot its items are looked for (and picked up by the worker): set
     * in {@code config/palworks.json}.
     */
    public static double pickupRadius() {
        return PalworksConfig.current().pickupRadius();
    }

    /**
     * Plain terrain that's never part of any job: not a crop, a soil a seed is planted in, a tree, a
     * chest, a furnace or an ore. A chunk section made only of these and air can't hold a job, so
     * the chunk scan skips it whole (see JobScanner), which is most of the world: solid ground below,
     * open sky above. Only add a block here if no job can involve it; JobGameTests checks every one
     * against the scan's own checks.
     */
    private static final Set<Block> NEVER_WORK = Set.of(
            Blocks.STONE,
            Blocks.DEEPSLATE,
            Blocks.GRANITE,
            Blocks.DIORITE,
            Blocks.ANDESITE,
            Blocks.TUFF,
            Blocks.CALCITE,
            Blocks.DIRT,
            Blocks.GRASS_BLOCK,
            Blocks.COARSE_DIRT,
            Blocks.GRAVEL,
            Blocks.SAND,
            Blocks.RED_SAND,
            Blocks.SANDSTONE,
            Blocks.CLAY,
            Blocks.BEDROCK,
            Blocks.WATER,
            Blocks.LAVA,
            Blocks.NETHERRACK,
            Blocks.END_STONE);

    /** Whether a block is air or plain terrain, never any part of a job (see {@link #NEVER_WORK}). */
    public static boolean canNeverBeWork(BlockState state) {
        return state.isAir() || NEVER_WORK.contains(state.getBlock());
    }

    /** The plain terrain blocks of {@link #canNeverBeWork} (not counting air), for tests. */
    public static Set<Block> neverWorkBlocks() {
        return NEVER_WORK;
    }

    /**
     * Whether a job still exists in the world: the same checks the chunk scan uses to find it.
     * Every step of a job checks it's still there with this, by default (see StepRunner): workers
     * check it on their way to a job, so they drop it instead of finishing the walk when it's gone
     * (crop already harvested, tree chopped, chest emptied...), and as they work, so they stop
     * when it goes, and a step's effect never lands on a job that isn't there. A job changes some
     * things itself as it goes, so a few checks are looser than the scan's: a furnace the worker
     * has lit is still its job (see FurnaceHelper.canUseHeat).
     */
    public static boolean isJobStillThere(Level level, JobType type, BlockPos pos) {
        return switch (type) {
            case FUEL_FURNACE -> FurnaceHelper.canUseHeat(level, pos);
            case HARVEST_CROPS -> CropAdapter.isFullyGrown(level, pos) || CropAdapter.isAttachedFruit(level, pos);
            case PLANT_SEEDS -> level.getBlockState(pos).isAir() && CropAdapter.isPlantableSurface(level, pos.below());
            case WATER_CROPS -> Watering.needsWater(level, pos);
            case CHOP_TREE -> TreeDetector.isTreeBlock(level.getBlockState(pos));
            case GET_SEEDS ->
                isChest(level.getBlockState(pos))
                        && (hasPlantableSeeds(level, pos) || ChestHelper.hasItem(level, pos, Items.NETHER_WART));
            case DELIVER_ITEMS -> level.getBlockEntity(pos) instanceof net.minecraft.world.Container;
            case COLLECT_DROPS -> !itemsToPickUp(level, pos).isEmpty();
            case EAT_FOOD -> level.getBlockEntity(pos) instanceof TroughBlockEntity trough && trough.hasFeed();
            case FETCH_FOOD -> isChest(level.getBlockState(pos)) && hasTroughFood(level, pos);
            default -> true;
        };
    }

    /**
     * Whether this worker can do this job: it has what the job takes (a seed for the soil, room for
     * the drops...). The worker's side of a job, as {@link #isJobStillThere} is the world's. The one
     * check of it, so the two places it's asked agree: when jobs are handed out (a worker is only
     * offered jobs it can do), and while a worker has one (on its way and as it works, see
     * StepRunner), so a worker that can't do its job any more gives it back for one that can,
     * instead of finishing it for nothing. A job type that needs something of its worker goes here.
     *
     * <p>Suitabilities aren't asked here: a job's (which can depend on its block: nether wart is
     * Nether work) are on its record, checked as it's handed out (see JobAssigner.canDoJob).
     */
    public static boolean canWorkerDo(Level level, Pokemon pokemon, JobType type, BlockPos pos) {
        WorkerInventory inventory = WorkerInventoryManager.getOrCreate(pokemon);
        return switch (type) {
            // A seed it carries that can be planted there (wart on soul sand, the rest on farmland)
            case PLANT_SEEDS -> carriesSeedFor(level, inventory, pos);
            // Room for some of what's lying there
            case COLLECT_DROPS -> hasRoomForAny(inventory, itemsToPickUp(level, pos));
            // Room for the seeds it fetches
            case GET_SEEDS -> inventory.hasSpace();
            // Something to deliver
            case DELIVER_ITEMS -> inventory.hasCargo(SuitabilityDetector.getSuitabilities(pokemon));
            // Hungry, with its work on: eating isn't work, but only a worker eats from troughs
            case EAT_FOOD -> Hunger.wantsFood(pokemon) && Hunger.appliesTo(pokemon, WorkerStateManager.get(pokemon));
            // Room for the food it fetches
            case FETCH_FOOD -> inventory.hasSpace();
            default -> true;
        };
    }

    private static boolean hasRoomForAny(WorkerInventory inventory, List<ItemEntity> items) {
        for (ItemEntity item : items) {
            if (inventory.roomFor(item.getItem()) > 0) {
                return true;
            }
        }
        return false;
    }

    /** The items lying round a pickup job's spot. */
    public static List<ItemEntity> itemsToPickUp(Level level, BlockPos pos) {
        return level.getEntitiesOfClass(
                ItemEntity.class,
                new AABB(pos).inflate(pickupRadius()),
                item -> item.isAlive() && !item.getItem().isEmpty());
    }

    /** Whether the worker carries a seed that can be planted at {@code plantPos}. */
    private static boolean carriesSeedFor(Level level, WorkerInventory inventory, BlockPos plantPos) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && CropAdapter.canPlantSeed(stack.getItem(), level, plantPos.below())) {
                return true;
            }
        }
        return false;
    }

    // Helper methods for job detection
    public static boolean isChest(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST);
    }

    /** Whether the chest (or other container) at {@code pos} holds any food a trough takes. */
    public static boolean hasTroughFood(Level level, BlockPos pos) {
        return !ChestHelper.itemsMatching(level, pos, TroughFood::accepts).isEmpty();
    }

    /** Whether a block is a trough (of any wood), which hungry workers eat from. */
    public static boolean isTrough(BlockState state) {
        return state.getBlock() instanceof TroughBlock;
    }

    static boolean hasPlantableSeeds(Level level, BlockPos pos) {
        return !ChestHelper.itemsMatching(level, pos, JobChecks::isFarmlandSeed).isEmpty();
    }

    /**
     * A seed a planter (Planting) plants on farmland: any item that plants a field crop (see
     * CropAdapter.fieldCropOf), vanilla or modded, except nether wart, which is Nether work.
     */
    static boolean isFarmlandSeed(Item item) {
        return !item.equals(Items.NETHER_WART) && CropAdapter.fieldCropOf(item) != null;
    }

    private JobChecks() {}
}
