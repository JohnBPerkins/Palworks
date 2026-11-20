package dev.palworks.job;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.block.TroughBlock;
import dev.palworks.block.TroughBlockEntity;
import dev.palworks.block.WorkerBedBlock;
import dev.palworks.config.PalworksConfig;
import dev.palworks.crop.CropAdapter;
import dev.palworks.crop.TallPlants;
import dev.palworks.crop.Watering;
import dev.palworks.crystal.CrystalAdapter;
import dev.palworks.drops.TrackedDrops;
import dev.palworks.hunger.Hunger;
import dev.palworks.hunger.TroughFood;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.sleep.Sleep;
import dev.palworks.station.ChestHelper;
import dev.palworks.station.FurnaceHelper;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityDetector;
import dev.palworks.tree.TreeDetector;
import dev.palworks.worker.WorkerStateManager;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

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
            case HARVEST_SUGAR_CANE, HARVEST_CACTUS, HARVEST_BAMBOO, HARVEST_KELP, HARVEST_NETHER_VINES ->
                TallPlants.ripeJobAt(level, pos) == type;
            case PLANT_SEEDS -> level.getBlockState(pos).isAir() && CropAdapter.isPlantableSurface(level, pos.below());
            case WATER_CROPS -> Watering.needsWater(level, pos);
            case CHOP_TREE -> TreeDetector.isTreeBlock(level.getBlockState(pos));
            case MINE_CRYSTAL -> CrystalAdapter.isRipe(level, pos, level.getBlockState(pos));
            case GET_SEEDS ->
                isChest(level.getBlockState(pos))
                        && (hasPlantableSeeds(level, pos) || ChestHelper.hasItem(level, pos, Items.NETHER_WART));
            case DELIVER_ITEMS -> level.getBlockEntity(pos) instanceof net.minecraft.world.Container;
            case COLLECT_DROPS -> !itemsToPickUp(level, pos).isEmpty();
            // Seeds left there for a planter (it's only the one they were left for that can take
            // them: canWorkerDo)
            case TAKE_SEEDS -> !seedsHandedTo(level, pos, null).isEmpty();
            case EAT_FOOD -> level.getBlockEntity(pos) instanceof TroughBlockEntity trough && trough.hasFeed();
            case FETCH_FOOD -> isChest(level.getBlockState(pos)) && hasTroughFood(level, pos);
            case SLEEP -> isBed(level.getBlockState(pos));
            default -> true;
        };
    }

    /**
     * The skills the job of this type at {@code pos} takes: its type's, except Nether work, which
     * is known by what's there. Ripe nether wart is harvested with Nether alone (Gathering alone let
     * any Grass type harvest it), soul sand is planted with it, and a nether tree takes it as well
     * as Lumbering. The one place that says so: for the jobs the scan finds, and for one a worker
     * still has after a restart (see JobManager.registerWorker).
     */
    static Set<Suitability> skillsFor(Level level, JobType type, BlockPos pos) {
        return skillsFor(type, level.getBlockState(pos), level.getBlockState(pos.below()));
    }

    /** {@link #skillsFor(Level, JobType, BlockPos)}, by the block at the job and the one under it. */
    private static Set<Suitability> skillsFor(JobType type, BlockState at, BlockState below) {
        return switch (type) {
            case HARVEST_CROPS ->
                at.is(Blocks.NETHER_WART) ? EnumSet.of(Suitability.NETHER) : type.requiredSuitabilities();
            case PLANT_SEEDS ->
                below.is(Blocks.SOUL_SAND) ? EnumSet.of(Suitability.NETHER) : type.requiredSuitabilities();
            case CHOP_TREE ->
                TreeDetector.isNetherStem(at)
                        ? EnumSet.of(Suitability.LUMBERING, Suitability.NETHER)
                        : type.requiredSuitabilities();
            default -> type.requiredSuitabilities();
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
            // Seeds for the planter it's bringing them to (see SeedRequests)
            case BRING_SEEDS ->
                level instanceof ServerLevel serverLevel
                        && JobManager.getInstance(serverLevel).hasSeedsToBring(pokemon);
            // Room for seeds left there for it, once they can be picked up
            case TAKE_SEEDS -> hasRoomForAny(inventory, seedsToTake(level, pos, pokemon.getUuid()));
            // Something to deliver
            case DELIVER_ITEMS -> inventory.hasCargo(SuitabilityDetector.getSuitabilities(pokemon));
            // Hungry, with its work on: eating isn't work, but only a worker eats from troughs
            case EAT_FOOD -> Hunger.wantsFood(pokemon) && Hunger.appliesTo(pokemon, WorkerStateManager.get(pokemon));
            // Room for the food it fetches
            case FETCH_FOOD -> inventory.hasSpace();
            // Its bedtime, or a nap it's Tired for (it's woken up once that's over)
            case SLEEP ->
                pokemon.getEntity() != null && Sleep.wantsBed(pokemon.getEntity(), WorkerStateManager.get(pokemon));
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

    /**
     * The items to pick up lying round a pickup job's spot: only what workers and players dropped
     * (see TrackedDrops), never a farm's or a mob's, and not those waiting after no worker could
     * get to them (see PickupQueue#outOfReach).
     */
    public static List<ItemEntity> itemsToPickUp(Level level, BlockPos pos) {
        return level.getEntitiesOfClass(
                ItemEntity.class, new AABB(pos).inflate(pickupRadius()), item -> isToPickUp(level, item));
    }

    /**
     * The items to pick up within reach of a worker, as a player picks them up: a block either side
     * of its body, and half a block above and below it.
     */
    public static List<ItemEntity> itemsInReach(Level level, Entity worker) {
        return level.getEntitiesOfClass(
                ItemEntity.class,
                worker.getBoundingBox().inflate(REACH_ACROSS, REACH_UP, REACH_ACROSS),
                item -> isToPickUp(level, item));
    }

    private static final double REACH_ACROSS = 1.0;
    private static final double REACH_UP = 0.5;

    /** Whether this is an item to pick up (see {@link #itemsToPickUp}). */
    public static boolean isToPickUp(Level level, ItemEntity item) {
        return item.isAlive()
                && !item.getItem().isEmpty()
                && TrackedDrops.isTracked(item)
                // Seeds left for a planter are its own to take
                && TrackedDrops.handedTo(item) == null
                && !JobManager.isWaitingForPickup(level, item);
    }

    /**
     * Where an item lies: the block it's in. Not the one under it: an item lying on farmland (or a
     * slab, a path) is within the top of that block, which is solid, with nowhere to stand.
     */
    public static BlockPos spotOf(ItemEntity item) {
        return BlockPos.containing(item.getX(), item.getY() + 0.5, item.getZ());
    }

    /**
     * The seeds left lying round {@code pos} for {@code planter} to take (see SeedRequests), or for
     * any planter if null.
     */
    public static List<ItemEntity> seedsHandedTo(Level level, BlockPos pos, @Nullable UUID planter) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(pickupRadius()), item -> {
            if (!item.isAlive() || item.getItem().isEmpty()) {
                return false;
            }
            UUID handedTo = TrackedDrops.handedTo(item);
            return handedTo != null && (planter == null || planter.equals(handedTo));
        });
    }

    /**
     * The seeds left round {@code pos} for {@code planter} that it can take now: landed, and past
     * their pickup delay, as a player picks up what another threw it.
     */
    public static List<ItemEntity> seedsToTake(Level level, BlockPos pos, UUID planter) {
        List<ItemEntity> seeds = new ArrayList<>(seedsHandedTo(level, pos, planter));
        seeds.removeIf(item -> !item.onGround() || item.hasPickUpDelay());
        return seeds;
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

    /**
     * Whether a block is a bed, which a pastured worker sleeps in: its corner block, which
     * stands for the whole bed (a bigger one's other blocks don't count again).
     */
    public static boolean isBed(BlockState state) {
        return state.getBlock() instanceof WorkerBedBlock && WorkerBedBlock.isOrigin(state);
    }

    /** Whether a block is a trough (of any wood), which hungry workers eat from. */
    public static boolean isTrough(BlockState state) {
        return state.getBlock() instanceof TroughBlock;
    }

    static boolean hasPlantableSeeds(Level level, BlockPos pos) {
        return !ChestHelper.itemsMatching(level, pos, JobChecks::isFarmlandSeed).isEmpty();
    }

    /**
     * The work skills an item's block is worked with: the checks the scan finds jobs by
     * (JobScanner.scanPositionForJob), and the chests and troughs transporters deliver to, asked of
     * the kind of block only, not of one in the world. A furnace is lit (Kindling) whether it has
     * anything to smelt now or not, a crop is harvested (Gathering) once it's ripe, a log is chopped
     * (Lumbering) when it's part of a tree. Also the seeds planters plant and the saplings
     * lumberjacks replant. For the "Worked by" tooltip (see client.gui.WorkedByTooltip); nothing
     * for an item that's none of these.
     */
    public static Set<Suitability> workedWith(Item item) {
        Set<Suitability> skills = EnumSet.noneOf(Suitability.class);
        BlockState air = Blocks.AIR.defaultBlockState();
        if (isFarmlandSeed(item)) {
            skills.addAll(JobType.PLANT_SEEDS.requiredSuitabilities());
        }
        Block tree = TreeDetector.treeBlockFor(item); // it's a sapling, replanted where that tree was
        if (tree != null) {
            skills.addAll(skillsFor(JobType.CHOP_TREE, tree.defaultBlockState(), air));
        }
        if (!(item instanceof BlockItem blockItem)) {
            return skills;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        if (state.is(JobBlockTags.FURNACES)) {
            skills.addAll(JobType.FUEL_FURNACE.requiredSuitabilities());
        }
        // A stem's own fruit is harvested (the scan finds it from the stem)
        if (CropAdapter.isHarvestable(state) || state.getBlock() instanceof StemBlock) {
            skills.addAll(skillsFor(JobType.HARVEST_CROPS, state, air));
        }
        JobType tallPlant = TallPlants.jobFor(state);
        if (tallPlant != null) {
            skills.addAll(tallPlant.requiredSuitabilities());
        }
        if (CropAdapter.isWatered(state)) {
            skills.addAll(JobType.WATER_CROPS.requiredSuitabilities());
        }
        if (CropAdapter.isPlantableSurface(state)) {
            skills.addAll(skillsFor(JobType.PLANT_SEEDS, air, state));
        }
        if (isChest(state) || isTrough(state)) {
            skills.addAll(JobType.DELIVER_ITEMS.requiredSuitabilities());
        }
        if (TreeDetector.isTreeBlock(state)) {
            skills.addAll(skillsFor(JobType.CHOP_TREE, state, air));
        }
        if (CrystalAdapter.isCrystal(state)) {
            skills.addAll(JobType.MINE_CRYSTAL.requiredSuitabilities());
        }
        return skills; // not ores: mining them isn't scanned for yet (JobScanner.MINING_JOBS_ENABLED)
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
