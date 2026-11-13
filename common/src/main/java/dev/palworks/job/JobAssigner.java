package dev.palworks.job;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.ai.WorkPositions;
import dev.palworks.ai.path.WorkerNavigator;
import dev.palworks.ai.path.WorkerPathfinder;
import dev.palworks.ai.path.WorkerPathfinder.Reach;
import dev.palworks.block.WorkerBedBlock;
import dev.palworks.crop.CropAdapter;
import dev.palworks.debug.DebugManager;
import dev.palworks.hunger.Hunger;
import dev.palworks.hunger.TroughFood;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.job.WorkerRegistry.WorkerInfo;
import dev.palworks.mood.Mood;
import dev.palworks.reservation.ReservationId;
import dev.palworks.sleep.Beds;
import dev.palworks.sleep.Sleep;
import dev.palworks.station.ChestHelper;
import dev.palworks.suitability.Suitability;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/**
 * Hands a level's jobs to its idle workers: the best job each can do and reach, deliveries
 * first for a transporter with cargo. Remembers which jobs each worker couldn't reach, so it
 * doesn't keep path-checking them.
 */
final class JobAssigner {

    private static final int ASSIGNMENT_BATCH_SIZE = 10; // Workers assigned per tick
    private static final int MAX_PATH_CHECKS_PER_WORKER = 8; // Cap expensive createPath() calls per assignment
    private static final int DELIVERY_SEARCH_RADIUS = 32; // Horizontal reach for picking a delivery chest
    private static final int DELIVERY_SEARCH_VERTICAL = 4; // Y reach for picking a delivery chest
    // The skills that plant (farmland seeds, nether wart), in the order a worker with both fetches for them
    private static final List<Suitability> PLANTING_SKILLS = List.of(Suitability.PLANTING, Suitability.NETHER);
    // Heading home to a trough or bed it can't get to (see headHome): how far a leg goes, and how
    // near the trough or bed it stops heading home
    private static final int HOME_LEG = 12;
    private static final int HOME_CLOSE_ENOUGH = 8;
    // A worker won't retry a job it couldn't reach until terrain near it changes, it moves
    // UNREACHABLE_FORGET_MOVE_DIST blocks, or this backstop runs out (see recentlyFailedJobs).
    static final int JOB_FAILURE_BLACKLIST_TICKS = 600; // 30s backstop
    private static final int UNREACHABLE_FORGET_MOVE_DIST = 8; // Blocks a worker moves before retrying jobs

    private final ServerLevel level;
    private final JobPool pool;
    private final JobScanner scanner;
    private final WorkerRegistry workers;
    // Beds non-workers have, which no worker is offered (it never turns one out)
    private final NonWorkerBeds nonWorkerBeds;

    // Per-worker memory of job positions the worker couldn't reach, either because the path check
    // failed at assignment or it got stuck walking there (Pokemon UUID -> job position -> game tick
    // the entry expires). Stops idle workers re-pathfinding the same unreachable jobs every tick.
    // Cleared early when collision changes near the worker (JobManager.onBlockStateChange: a gate
    // opened, a block broken) or when the worker moves away from failureAnchors, so it reacts right
    // away when a player clears the way.
    private final Map<UUID, Map<BlockPos, Long>> recentlyFailedJobs = new ConcurrentHashMap<>();
    // Where each worker was when its current failures were recorded (reachability is from there).
    private final Map<UUID, BlockPos> failureAnchors = new ConcurrentHashMap<>();
    // When each worker was last considered for a job (game time): each tick's batch is the idle
    // workers that have waited longest (Turns.longestWaiting).
    private final Map<UUID, Long> lastConsidered = new ConcurrentHashMap<>();

    JobAssigner(
            ServerLevel level, JobPool pool, JobScanner scanner, WorkerRegistry workers, NonWorkerBeds nonWorkerBeds) {
        this.level = level;
        this.pool = pool;
        this.scanner = scanner;
        this.workers = workers;
        this.nonWorkerBeds = nonWorkerBeds;
    }

    /**
     * Assign jobs to idle workers (up to a batch a tick). Every job reaches a worker through
     * {@link #assignFirstReachable}.
     */
    void assign(ServerLevel level) {
        List<WorkerInfo> idle = workers.all().stream()
                .filter(w -> w.idle && w.currentReservation == null)
                .filter(scanner::hasScannedAround)
                .collect(Collectors.toList());
        // The longest waiting first: in the registry's order (the same every tick), workers that
        // stay idle with no job they can do (Water types while there's no watering) had the whole
        // batch every tick, and no other worker in the level was ever given a job
        List<WorkerInfo> idleWorkers = Turns.longestWaiting(
                idle, w -> lastConsidered.getOrDefault(w.pokemonUUID, Long.MIN_VALUE), ASSIGNMENT_BATCH_SIZE);

        if (idleWorkers.isEmpty()) {
            return;
        }

        long now = level.getGameTime();
        int assignedCount = 0;
        for (WorkerInfo worker : idleWorkers) {
            lastConsidered.put(worker.pokemonUUID, now);
            Pokemon pokemon = workers.pokemon(worker.pokemonUUID);

            // Sleep (pastured workers only): one Cobblemon has napping is left to nap; in its bedtime,
            // it's only given a bed (none to be had, and it sleeps where it is: see WorkerAI)
            PokemonEntity entity = workers.entity(worker.pokemonUUID);
            if (pokemon != null && entity != null) {
                WorkerState sleeper = WorkerStateManager.get(pokemon);
                if (Sleep.isBedtimeFor(entity, sleeper)) {
                    // Woken in the night: up a while before it goes back to bed, and no work meanwhile.
                    // No bed it can get to from here, but one by its pasture: it heads home.
                    if (!Sleep.isWoken(entity, sleeper) && (sleeper == null || !sleeper.isAsleep())) {
                        List<JobRecord> beds = bedCandidates(level, worker, entity);
                        if (assignFirstReachable(worker, beds) != null) {
                            assignedCount++;
                        } else {
                            headHome(level, worker, beds);
                        }
                    }
                    continue;
                }
                if (Sleep.appliesTo(pokemon, sleeper) && Sleep.cobblemonHasItAsleep(entity)) {
                    continue;
                }
            }

            // A hungry worker eats before anything else, at the nearest trough with food it can
            // reach. If there's none it can get to from here but one by its pasture, it heads home;
            // with none at all, it carries on working (slower once it's empty).
            if (pokemon != null && Hunger.wantsFood(pokemon)) {
                List<JobRecord> troughs = troughCandidates(level, worker);
                if (assignFirstReachable(worker, troughs) != null) {
                    assignedCount++;
                    continue;
                }
                if (headHome(level, worker, troughs)) {
                    continue;
                }
            }

            // A worker on strike (its mood ran out) does no work until it's back up a way; it still
            // eats and sleeps, above, which is how it gets there (see Mood)
            if (pokemon != null && Mood.isStriking(pokemon, WorkerStateManager.get(pokemon))) {
                continue;
            }

            // A transporter carrying cargo (anything but a planter's seeds) fills up before it
            // delivers: it takes pickups it has room for, nearest first (a free slot takes anything;
            // once every slot is used, only items matching what it carries), and goes to a chest
            // only when none are left. Its other jobs wait until it's delivered.
            if (pokemon != null
                    && worker.suitabilities.contains(Suitability.TRANSPORTING)
                    && WorkerInventoryManager.getOrCreate(pokemon).hasCargo(worker.suitabilities)) {
                List<JobRecord> pickups = poolCandidates(worker).stream()
                        .filter(job -> job.type() == JobType.COLLECT_DROPS)
                        .collect(Collectors.toList());
                if (!pickups.isEmpty()) {
                    // Pickups left that it has room for. If none can be reached right now, they're
                    // remembered as unreachable and drop out of the list, and it delivers once
                    // they're all gone; until then it doesn't go to a chest half full.
                    if (assignFirstReachable(worker, pickups) != null) {
                        assignedCount++;
                    }
                    continue;
                }

                // Nothing left to pick up: deliver. Nearest chests with room first; the first one it
                // can actually reach wins
                if (assignFirstReachable(worker, deliveryCandidates(level, worker, pokemon)) != null) {
                    assignedCount++;
                    continue;
                }
                // No chest it can use: fall through to its other jobs
            }

            // A transporter with room restocks a trough that's running low from a chest of food
            // before other work: the workers eating from it depend on it
            if (pokemon != null && worker.suitabilities.contains(Suitability.TRANSPORTING)) {
                List<JobRecord> restock = restockCandidates(level, worker);
                if (!restock.isEmpty() && assignFirstReachable(worker, restock) != null) {
                    assignedCount++;
                    continue;
                }
            }

            // Best pool jobs first; the first one it can actually reach wins
            if (assignFirstReachable(worker, poolCandidates(worker)) != null) {
                assignedCount++;
            }
        }

        if (assignedCount > 0) {
            Palworks.LOGGER.debug(
                    "Assigned {} jobs to idle workers ({} idle workers available)", assignedCount, idleWorkers.size());
        }
    }

    /**
     * DELIVER_ITEMS candidates for a worker with items to store: the chests (that the chunk scan
     * already found) in range with room for them, nearest first. Assignment then picks the first
     * one the worker can reach, so an unreachable nearest chest no longer traps it in a loop.
     */
    private List<JobRecord> deliveryCandidates(ServerLevel level, WorkerInfo worker, Pokemon pokemon) {
        BlockPos workerPos = worker.position;

        WorkerInventory inventory = WorkerInventoryManager.getOrCreate(pokemon);
        net.minecraft.world.item.ItemStack firstItem = inventory.firstCargo(worker.suitabilities);

        // From the chests the chunk scan already found (this used to scan ~38k blocks itself)
        List<BlockPos> chests = new ArrayList<>();
        for (BlockPos chestPos : scanner.chestsNear(workerPos, (DELIVERY_SEARCH_RADIUS >> 4) + 1)) {
            if (Math.abs(chestPos.getX() - workerPos.getX()) > DELIVERY_SEARCH_RADIUS
                    || Math.abs(chestPos.getZ() - workerPos.getZ()) > DELIVERY_SEARCH_RADIUS
                    || Math.abs(chestPos.getY() - workerPos.getY()) > DELIVERY_SEARCH_VERTICAL) {
                continue;
            }
            if (!nearEnoughToOwner(worker, chestPos)) {
                continue;
            }
            // The cached position may be up to one scan old, so re-check it's still a chest
            if (JobChecks.isChest(level.getBlockState(chestPos))
                    && ChestHelper.canAcceptItems(level, chestPos, firstItem)) {
                chests.add(chestPos);
            }
        }
        chests.sort(Comparator.comparingDouble(workerPos::distSqr));

        // Food a trough takes goes to the nearest trough with room for it first, so a berry farm
        // keeps its workers fed; whatever else it carries (and food the troughs have no room for)
        // goes to a chest after
        List<BlockPos> troughs = new ArrayList<>();
        net.minecraft.world.item.ItemStack food = carriedTroughFood(inventory, worker);
        if (!food.isEmpty()) {
            for (BlockPos troughPos : troughsInReach(worker)) {
                if (nearEnoughToOwner(worker, troughPos)
                        && JobChecks.isTrough(level.getBlockState(troughPos))
                        && ChestHelper.canAcceptItems(level, troughPos, food)) {
                    troughs.add(troughPos);
                }
            }
            troughs.sort(Comparator.comparingDouble(workerPos::distSqr));
        }

        List<JobRecord> candidates = new ArrayList<>(troughs.size() + chests.size());
        for (BlockPos troughPos : troughs) {
            candidates.add(JobRecord.of(JobType.DELIVER_ITEMS, troughPos, level.getGameTime()));
        }
        for (BlockPos chestPos : chests) {
            candidates.add(JobRecord.of(JobType.DELIVER_ITEMS, chestPos, level.getGameTime()));
        }

        if (Palworks.LOGGER.isTraceEnabled() && candidates.isEmpty()) {
            Palworks.LOGGER.trace(
                    "No chest found for {} to deliver {} items!",
                    pokemon.getSpecies().getName(),
                    WorkerInventoryManager.getTotalItemCount(pokemon));
        }
        return candidates;
    }

    /** The first food a trough takes that this worker carries as cargo, or empty. */
    private static net.minecraft.world.item.ItemStack carriedTroughFood(WorkerInventory inventory, WorkerInfo worker) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(i);
            if (WorkerInventory.isCargo(stack, worker.suitabilities) && TroughFood.accepts(stack)) {
                return stack;
            }
        }
        return net.minecraft.world.item.ItemStack.EMPTY;
    }

    /** The troughs the chunk scan found in reach of the worker or its home (may be a scan old). */
    private List<BlockPos> troughsInReach(WorkerInfo worker) {
        return nearWorkerOrHome(worker, scanner::troughsNear);
    }

    /**
     * What {@code found} (the scanner's troughs or beds round a spot) has within reach of the worker
     * or of its home ({@link WorkerInfo#home}): {@link #DELIVERY_SEARCH_RADIUS} out each way, and up
     * and down as far as it works. Round its home too, so a worker that's wandered off for work
     * still finds the troughs and beds it lives by. Each once.
     */
    private List<BlockPos> nearWorkerOrHome(
            WorkerInfo worker, java.util.function.BiFunction<BlockPos, Integer, List<BlockPos>> found) {
        List<BlockPos> centers = worker.home == null || worker.home.equals(worker.position)
                ? List.of(worker.position)
                : List.of(worker.position, worker.home);
        java.util.Set<BlockPos> inReach = new java.util.LinkedHashSet<>();
        for (BlockPos center : centers) {
            for (BlockPos pos : found.apply(center, (DELIVERY_SEARCH_RADIUS >> 4) + 1)) {
                if (Math.abs(pos.getX() - center.getX()) <= DELIVERY_SEARCH_RADIUS
                        && Math.abs(pos.getZ() - center.getZ()) <= DELIVERY_SEARCH_RADIUS
                        && Math.abs(pos.getY() - center.getY()) <= WorkRange.vertical()) {
                    inReach.add(pos);
                }
            }
        }
        return new ArrayList<>(inReach);
    }

    /**
     * FETCH_FOOD candidates for a transporter with room: if a trough in reach is running low (see
     * TroughBlockEntity#isLow), the chests in reach with food in them, nearest first. Like
     * deliveries, they aren't in the pool. Once it has the food, its delivery takes it to the trough.
     */
    private List<JobRecord> restockCandidates(ServerLevel level, WorkerInfo worker) {
        Pokemon pokemon = workers.pokemon(worker.pokemonUUID);
        if (pokemon == null || !WorkerInventoryManager.getOrCreate(pokemon).hasSpace()) {
            return List.of();
        }
        BlockPos workerPos = worker.position;
        boolean troughLow = false;
        for (BlockPos troughPos : troughsInReach(worker)) {
            if (nearEnoughToOwner(worker, troughPos)
                    && level.getBlockEntity(troughPos) instanceof dev.palworks.block.TroughBlockEntity trough
                    && trough.isLow()) {
                troughLow = true;
                break;
            }
        }
        if (!troughLow) {
            return List.of();
        }
        List<BlockPos> chests = new ArrayList<>();
        for (BlockPos chestPos : scanner.chestsNear(workerPos, (DELIVERY_SEARCH_RADIUS >> 4) + 1)) {
            if (Math.abs(chestPos.getX() - workerPos.getX()) <= DELIVERY_SEARCH_RADIUS
                    && Math.abs(chestPos.getZ() - workerPos.getZ()) <= DELIVERY_SEARCH_RADIUS
                    && Math.abs(chestPos.getY() - workerPos.getY()) <= DELIVERY_SEARCH_VERTICAL
                    && JobChecks.isJobStillThere(level, JobType.FETCH_FOOD, chestPos)) {
                chests.add(chestPos);
            }
        }
        chests.sort(Comparator.comparingDouble(workerPos::distSqr));
        List<JobRecord> candidates = new ArrayList<>(chests.size());
        for (BlockPos chestPos : chests) {
            candidates.add(JobRecord.of(JobType.FETCH_FOOD, chestPos, level.getGameTime()));
        }
        return candidates;
    }

    /**
     * SLEEP candidates for a pastured worker at its bedtime: the beds in reach (that the chunk
     * scan found) that no other worker has and that its body fits in (see Beds), the smallest first,
     * then the nearest. Like
     * deliveries, they aren't in the pool; the one it takes is reserved, so it's its own for the night.
     */
    private List<JobRecord> bedCandidates(ServerLevel level, WorkerInfo worker, PokemonEntity entity) {
        BlockPos workerPos = worker.position;
        List<BlockPos> beds = new ArrayList<>();
        for (BlockPos bedPos : nearWorkerOrHome(worker, scanner::bedsNear)) {
            if (JobChecks.isBed(level.getBlockState(bedPos))
                    && level.getBlockState(bedPos).getBlock() instanceof WorkerBedBlock bed
                    && !pool.isReserved(new JobPool.JobKey(bedPos, JobType.SLEEP))
                    && !nonWorkerBeds.isClaimed(bedPos)
                    && Beds.fits(level, bedPos, bed, entity)) {
                beds.add(bedPos);
            }
        }
        // The smallest it fits first, so big beds are left for big Pokémon; nearest of those
        beds.sort(Comparator.<BlockPos>comparingInt(
                        bedPos -> ((WorkerBedBlock) level.getBlockState(bedPos).getBlock()).size())
                .thenComparingDouble(workerPos::distSqr));
        List<JobRecord> candidates = new ArrayList<>(beds.size());
        for (BlockPos bedPos : beds) {
            candidates.add(JobRecord.of(JobType.SLEEP, bedPos, level.getGameTime()));
        }
        return candidates;
    }

    /**
     * EAT_FOOD candidates for a hungry worker: the troughs (that the chunk scan already found) in
     * range with food in them, nearest first. Like deliveries, they aren't in the pool: each worker
     * gets its own, and several can eat at one trough.
     */
    private List<JobRecord> troughCandidates(ServerLevel level, WorkerInfo worker) {
        BlockPos workerPos = worker.position;
        List<BlockPos> troughs = new ArrayList<>();
        for (BlockPos troughPos : troughsInReach(worker)) {
            // The cached position may be up to one scan old: re-check it's a trough, with food
            if (JobChecks.isJobStillThere(level, JobType.EAT_FOOD, troughPos)) {
                troughs.add(troughPos);
            }
        }
        troughs.sort(Comparator.comparingDouble(workerPos::distSqr));

        List<JobRecord> candidates = new ArrayList<>(troughs.size());
        for (BlockPos troughPos : troughs) {
            candidates.add(JobRecord.of(JobType.EAT_FOOD, troughPos, level.getGameTime()));
        }
        return candidates;
    }

    /**
     * Pool jobs this worker could take, best first (scored by distance and priority). Only the
     * job-specific filters live here; the checks every job gets (suitability, unreachable memory,
     * stand position, path) are in {@link #assignFirstReachable}.
     */
    private List<JobRecord> poolCandidates(WorkerInfo worker) {
        Pokemon pokemon = workers.pokemon(worker.pokemonUUID);
        WorkerInventory inventory = pokemon != null ? WorkerInventoryManager.getOrCreate(pokemon) : null;

        // Open planting spots in its range, by the skill that plants them, worked out only if
        // there's a seed chest to consider
        Map<Suitability, List<BlockPos>> openSpots = new EnumMap<>(Suitability.class);

        // Collect viable candidates and score them cheaply (NO pathfinding in this pass). Only the
        // jobs it has the suitabilities for: the pool keeps them grouped that way.
        List<ScoredJob> candidates = new ArrayList<>();
        for (Collection<JobRecord> group : pool.availableFor(worker.suitabilities)) {
            for (JobRecord job : group) {
                // Cheap pre-filter so the list (and the sort) stays small; checked again on assignment
                if (!inWorkRange(worker, job.position())
                        || isRecentlyUnreachable(worker, job.position())
                        || !canWorkerDo(worker, job)) {
                    continue;
                }

                // A seed chest only for a worker with something to take from it (see seedToFetch):
                // it fetches once it's run out, then plants until the stack is gone. Room to carry
                // them is canWorkerDo's.
                if (job.type() == JobType.GET_SEEDS
                        && (inventory == null
                                || seedToFetch(
                                                job.position(),
                                                worker.suitabilities,
                                                inventory,
                                                skill -> openSpots.computeIfAbsent(
                                                        skill, s -> openPlantingSpots(worker.position, s)))
                                        == null)) {
                    continue;
                }

                candidates.add(new ScoredJob(job, scoreJob(worker, job)));
            }
        }

        // Harvest everything first, then plant: while this worker has any harvest it could take,
        // planting jobs wait. Unreachable harvests drop out of candidates (recentlyFailedJobs), so
        // they can't hold planting back.
        if (candidates.stream().anyMatch(c -> c.job().type() == JobType.HARVEST_CROPS)) {
            candidates.removeIf(c -> c.job().type() == JobType.PLANT_SEEDS);
        }

        // Best score (lowest) first, so only the most promising jobs get path-checked. Each job is
        // scored once, not on every comparison the sort makes.
        candidates.sort(Comparator.comparingDouble(ScoredJob::score));
        List<JobRecord> jobs = new ArrayList<>(candidates.size());
        for (ScoredJob candidate : candidates) {
            jobs.add(candidate.job());
        }
        return jobs;
    }

    private record ScoredJob(JobRecord job, double score) {}

    /**
     * Assign the worker the first job in {@code candidates} (best first) that it can actually do
     * and reach. This is the only way a job is handed to a worker: pool jobs, deliveries and
     * follow-up jobs all come through here, so they all get the same checks, in this order:
     * <ol>
     *   <li>the worker has every suitability the job needs;</li>
     *   <li>it hasn't recently failed to reach that spot (see recentlyFailedJobs);</li>
     *   <li>there's somewhere to stand to do it (WorkPositions);</li>
     *   <li>there's a real path to that spot.</li>
     * </ol>
     * Candidates that fail 3 or 4 are remembered as unreachable, so the next call moves on to
     * others. Path checks are expensive, so at most MAX_PATH_CHECKS_PER_WORKER run per call.
     *
     * @return the job assigned, or null if none of the candidates worked out
     */
    @Nullable
    JobRecord assignFirstReachable(WorkerInfo worker, List<JobRecord> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        PokemonEntity entity = workers.entity(worker.pokemonUUID);
        if (entity == null) {
            return null; // can't check reachability without the entity; try again next tick
        }
        refreshUnreachableMemory(worker);
        long now = level.getGameTime();

        int pathChecks = 0;
        for (JobRecord job : candidates) {
            // Only jobs within its work range: a far one (found round some other worker, or left
            // over from one) would send it off across the world, and checking a path to it loads
            // chunks out there. A party Pokémon's must be near its owner too.
            if (!canDoJob(worker, job)
                    || !(inWorkRange(worker, job.position()) || isNeedNearHome(worker, job))
                    || isRecentlyUnreachable(worker, job.position())
                    || !canWorkerDo(worker, job)) {
                continue;
            }
            BlockPos standPos = WorkPositions.standingPosFor(level, job.position(), job.type(), entity);
            WorkerPathfinder.Result path = standPos == null
                    ? new WorkerPathfinder.Result(Reach.NO, null)
                    : canPathTo(entity, standPos, isNeed(job.type()));
            Reach reach = path.reach();
            if (reach == Reach.UNKNOWN) {
                // The worker can't pathfind at all right now (e.g. it was just sent out and is still
                // dropping to the ground, or is mid-step): that says nothing about the jobs, so
                // don't remember any as unreachable. Try again next tick.
                return null;
            }
            if (reach == Reach.YES) {
                // Take it out of the pool first so no other worker gets it too (a delivery isn't
                // in the pool, so this does nothing for one)
                pool.take(job);
                assignJobToWorker(level, worker, job);
                if (path.path() != null) {
                    WorkerNavigator.offer(entity, standPos, path.path()); // its walk starts on it
                }
                return job;
            }
            // Remember it, so next time this worker checks other candidates instead of
            // pathfinding to the same unreachable ones again.
            rememberUnreachable(worker.pokemonUUID, worker.position, job.position(), now);
            pathChecks++;
            if (pathChecks >= MAX_PATH_CHECKS_PER_WORKER) {
                break;
            }
        }
        return null;
    }

    /**
     * Whether a worker has every suitability a job needs (e.g. nether trees need Nether as well as
     * Lumbering).
     */
    static boolean canDoJob(WorkerInfo worker, JobRecord job) {
        return job.canBeDoneWith(worker.suitabilities);
    }

    /** {@link JobChecks#canWorkerDo} for a worker being offered a job. One that isn't loaded can't do any. */
    private boolean canWorkerDo(WorkerInfo worker, JobRecord job) {
        Pokemon pokemon = workers.pokemon(worker.pokemonUUID);
        return pokemon != null && JobChecks.canWorkerDo(level, pokemon, job.type(), job.position());
    }

    /**
     * Whether this is a trough or bed by the worker's home (see {@link #nearWorkerOrHome}), which it
     * goes back to however far it's wandered for work.
     */
    private static boolean isNeedNearHome(WorkerInfo worker, JobRecord job) {
        return isNeed(job.type()) && isNearHome(worker, job.position());
    }

    /** Whether {@code pos} is within reach of the worker's home (its pasture block), if it has one. */
    private static boolean isNearHome(WorkerInfo worker, BlockPos pos) {
        return worker.home != null
                && Math.abs(pos.getX() - worker.home.getX()) <= DELIVERY_SEARCH_RADIUS
                && Math.abs(pos.getZ() - worker.home.getZ()) <= DELIVERY_SEARCH_RADIUS
                && Math.abs(pos.getY() - worker.home.getY()) <= WorkRange.vertical();
    }

    /**
     * A pastured worker that needs a trough or bed (in {@code needs}) and can't get to one from
     * where it is, though there's one by its pasture: it heads home, a leg of {@link #HOME_LEG}
     * blocks at a time towards the nearest of them (see WorkerAI.walkHome), and between legs it
     * looks for a way to them again (a worker that's moved forgets what it couldn't reach). Once it's
     * within {@link #HOME_CLOSE_ENOUGH} of one, heading home can't help any more. Returns whether
     * it's on its way.
     */
    private boolean headHome(ServerLevel level, WorkerInfo worker, List<JobRecord> needs) {
        Pokemon pokemon = workers.pokemon(worker.pokemonUUID);
        PokemonEntity entity = workers.entity(worker.pokemonUUID);
        WorkerState state = pokemon == null ? null : WorkerStateManager.get(pokemon);
        if (entity == null || state == null || worker.home == null) {
            return false;
        }
        BlockPos target = needs.stream()
                .map(JobRecord::position)
                .filter(pos -> isNearHome(worker, pos))
                .min(Comparator.comparingDouble(worker.position::distSqr))
                .orElse(null);
        if (target == null || worker.position.closerThan(target, HOME_CLOSE_ENOUGH)) {
            state.setHomeward(null, level.getGameTime());
            return false;
        }
        if (state.getHomeward() != null) {
            return true; // on its way
        }
        BlockPos leg = legToward(entity, target);
        if (leg == null) {
            return false;
        }
        state.setHomeward(leg, level.getGameTime());
        return true;
    }

    /** The tile it could stand on in this column, nearest its own height (up to 4 up or down), or null. */
    @Nullable
    private BlockPos standableNear(PokemonEntity entity, int x, int z, int headroom) {
        for (int dy : new int[] {0, 1, -1, 2, -2, 3, -3, 4, -4}) {
            BlockPos tile = new BlockPos(x, entity.getBlockY() + dy, z);
            if (WorkPositions.isStandable(level, tile, headroom)) {
                return tile;
            }
        }
        return null;
    }

    /**
     * The end of the worker's next leg home: a tile it can stand on, and get to, about
     * {@link #HOME_LEG} blocks (or, failing that, half as far) along the way to {@code target}.
     * Null if it can't find one.
     */
    @Nullable
    private BlockPos legToward(PokemonEntity entity, BlockPos target) {
        double dx = target.getX() + 0.5 - entity.getX();
        double dz = target.getZ() + 0.5 - entity.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        int headroom = WorkPositions.headroomFor(entity);
        for (int along : new int[] {HOME_LEG, HOME_LEG / 2}) {
            double step = Math.min(along, distance);
            int x = net.minecraft.util.Mth.floor(entity.getX() + dx / distance * step);
            int z = net.minecraft.util.Mth.floor(entity.getZ() + dz / distance * step);
            BlockPos tile = standableNear(entity, x, z, headroom);
            if (tile == null) {
                continue;
            }
            WorkerPathfinder.Result path = canPathTo(entity, tile, true);
            if (path.reach() == Reach.YES) {
                if (path.path() != null) {
                    WorkerNavigator.offer(entity, tile, path.path());
                }
                return tile;
            }
        }
        return null;
    }

    /**
     * Whether {@code pos} is somewhere this worker may be sent: within its own work range and,
     * for one from a party, within work range of its owner too (see {@link #nearEnoughToOwner}).
     */
    private static boolean inWorkRange(WorkerInfo worker, BlockPos pos) {
        return WorkRange.contains(worker.position, pos) && nearEnoughToOwner(worker, pos);
    }

    /**
     * A party Pokémon only works near its owner ({@link WorkRange#ownerRange}), so it doesn't head
     * off to (or path-check) jobs far from them, which Cobblemon would teleport it back from part
     * way; with its owner not in this level, nowhere is. Pasture Pokémon aren't limited.
     */
    private static boolean nearEnoughToOwner(WorkerInfo worker, BlockPos pos) {
        return !worker.fromParty || WorkRange.nearOwner(worker.ownerPosition, pos);
    }

    /**
     * Assignment score for a (worker, job) pair. Lower is better: nearer + higher priority,
     * with a large discount for consecutive jobs hinted to this worker.
     */
    private double scoreJob(WorkerInfo worker, JobRecord job) {
        double distance = worker.position.distSqr(job.position());
        double priorityPenalty = (10 - job.priority()) * 100; // higher priority = lower penalty
        double score = distance + priorityPenalty;
        if (job.isFollowUp() && job.isReservedBy(worker.pokemonUUID)) {
            score *= 0.1; // 90% discount for consecutive jobs
        }
        return score;
    }

    /**
     * Record that a worker has a job (its reservation). Low-level: only
     * {@link #assignFirstReachable} calls this, after the checks every job gets. Don't call it
     * directly, or the job skips those checks.
     */
    private void assignJobToWorker(ServerLevel level, WorkerInfo worker, JobRecord job) {
        Pokemon pokemon = workers.pokemon(worker.pokemonUUID);
        if (pokemon == null) {
            // Pokemon entity not found - put job back in available pool
            Palworks.LOGGER.debug(
                    "Failed to assign {} job at {} - Pokemon entity not found for UUID {}",
                    job.type(),
                    job.position(),
                    worker.pokemonUUID);
            pool.offer(job);
            return;
        }

        ReservationId reservation = new ReservationId(job.position(), job.type(), level.getGameTime());

        // WorkerState (NBT) is the single source of truth for the reservation.
        WorkerState state = WorkerStateManager.getOrCreate(pokemon);
        state.setCurrentReservation(reservation);
        state.setHomeward(null, level.getGameTime()); // it's got where it can get one
        state.setAssignedJob(job.type());
        // Start from the job's first step, or from where the previous worker left it (handed over)
        state.startJob(job.resumeStep(), job.resumeData());
        WorkerStateManager.save(pokemon, state);

        // Mirror into the in-memory caches; registerWorker re-derives these from
        // WorkerState each tick, so they stay reconciled with the source of truth.
        worker.currentReservation = reservation;
        worker.idle = false;
        job.reserveFor(worker.pokemonUUID);
        pool.reserve(job);

        String dist = String.format("%.1f", Math.sqrt(worker.position.distSqr(job.position())));
        Palworks.LOGGER.debug(
                "Assigned {} job at {} to worker {} (distance: {})",
                job.type(),
                job.position(),
                pokemon.getSpecies().getName(),
                dist);
        // Send chat message to nearby players
        DebugManager.broadcastActivity(
                level,
                String.format(
                        "§a%s§f assigned §e%s§f at [%d, %d, %d] (dist: %s)",
                        pokemon.getSpecies().getName(),
                        job.type().name(),
                        job.position().getX(),
                        job.position().getY(),
                        job.position().getZ(),
                        dist));
    }

    /**
     * Whether an entity can walk to a spot, and the path there if it can. {@link Reach#UNKNOWN}
     * when no path can be planned at the moment: it's in the air (just sent out and still dropping,
     * mid-jump, ...). That isn't "unreachable"; treating it as such made workers skip the jobs right
     * next to them for up to 30 seconds.
     *
     * <p>Walking workers are planned by {@link WorkerPathfinder}, which checks their whole body all
     * the way there. No "close enough" shortcut: a job can be one block away yet blocked by a fence
     * or ledge (common on farms). A flying or swimming one is left to its own navigation, which must
     * reach the tile (standPos is a standable tile, not the worked block, so canReach() is right).
     */
    WorkerPathfinder.Result canPathTo(Mob entity, BlockPos standPos) {
        return canPathTo(entity, standPos, false);
    }

    /** A trough or bed: what a worker goes to for itself, searched for further round (a far trip). */
    private static boolean isNeed(JobType type) {
        return type == JobType.EAT_FOOD || type == JobType.SLEEP;
    }

    /** {@link #canPathTo(Mob, BlockPos)}, searching further round if it's a {@code farTrip}. */
    WorkerPathfinder.Result canPathTo(Mob entity, BlockPos standPos, boolean farTrip) {
        if (entity == null) {
            return new WorkerPathfinder.Result(Reach.UNKNOWN, null);
        }
        if (WorkerPathfinder.handles(entity)) {
            return WorkerPathfinder.find(entity, standPos, farTrip);
        }
        net.minecraft.world.level.pathfinder.Path path = entity.getNavigation().createPath(standPos, 1);
        if (path == null) {
            return new WorkerPathfinder.Result(Reach.UNKNOWN, null);
        }
        return new WorkerPathfinder.Result(path.canReach() ? Reach.YES : Reach.NO, null);
    }

    /**
     * The open planting spots needing {@code skill} (Planting: farmland; Nether: soul sand) within
     * work range of {@code workerPos} (anywhere, if null): where seeds are wanted right now.
     */
    private List<BlockPos> openPlantingSpots(@Nullable BlockPos workerPos, Suitability skill) {
        List<BlockPos> spots = new ArrayList<>();
        for (JobRecord job : pool.available()) {
            if (job.type() == JobType.PLANT_SEEDS
                    && job.requiredSuitabilities().contains(skill)
                    && (workerPos == null || WorkRange.contains(workerPos, job.position()))) {
                spots.add(job.position());
            }
        }
        return spots;
    }

    /** Whether {@code seed} can be planted on one of {@code spots} (planting spots, above farmland). */
    private boolean plantableOnAny(Item seed, List<BlockPos> spots) {
        for (BlockPos spot : spots) {
            if (CropAdapter.canPlantSeed(seed, level, spot.below())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The seed a worker at {@code workerPos} should take from the chest at {@code chestPos}, or null
     * if none. Each planting skill counts on its own, so a worker with both fetches whichever it's
     * out of: with Planting, a farmland seed that open farmland in its range can take, if it carries
     * none; with Nether, nether wart, if open soul sand in its range is waiting and it carries none.
     * Seeds that grow somewhere else (medicinal leek grows in water), or that nothing in range is
     * waiting for, are left in the chest: carried, they'd count as the seeds it has, and it would
     * never go back for ones it can plant.
     */
    @Nullable
    Item seedToFetch(
            BlockPos chestPos, @Nullable BlockPos workerPos, Set<Suitability> skills, WorkerInventory inventory) {
        return seedToFetch(chestPos, skills, inventory, skill -> openPlantingSpots(workerPos, skill));
    }

    @Nullable
    private Item seedToFetch(
            BlockPos chestPos,
            Set<Suitability> skills,
            WorkerInventory inventory,
            Function<Suitability, List<BlockPos>> openSpots) {
        for (Suitability skill : PLANTING_SKILLS) {
            if (!skills.contains(skill)) {
                continue;
            }
            List<BlockPos> spots = openSpots.apply(skill);
            if (spots.isEmpty() || carriesSeedForAny(inventory, spots)) {
                continue;
            }
            Predicate<Item> seeds = skill == Suitability.NETHER ? Items.NETHER_WART::equals : JobChecks::isFarmlandSeed;
            for (Item seed : ChestHelper.itemsMatching(level, chestPos, seeds)) {
                if (plantableOnAny(seed, spots)) {
                    return seed;
                }
            }
        }
        return null;
    }

    /** Whether the worker carries a seed one of {@code openSpots} can take. */
    private boolean carriesSeedForAny(WorkerInventory inventory, List<BlockPos> openSpots) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && plantableOnAny(stack.getItem(), openSpots)) {
                return true;
            }
        }
        return false;
    }

    /** The spots this worker currently remembers it couldn't reach (for the debug overlay). */
    List<BlockPos> unreachableFor(UUID workerUUID, long now) {
        Map<BlockPos, Long> failed = recentlyFailedJobs.get(workerUUID);
        if (failed == null) {
            return List.of();
        }
        List<BlockPos> spots = new ArrayList<>();
        failed.forEach((pos, expiry) -> {
            if (expiry > now) {
                spots.add(pos);
            }
        });
        return spots;
    }

    /** Whether the worker recently failed to reach this spot (and nothing has changed since). */
    private boolean isRecentlyUnreachable(WorkerInfo worker, BlockPos pos) {
        Map<BlockPos, Long> failed = recentlyFailedJobs.get(worker.pokemonUUID);
        if (failed == null) {
            return false;
        }
        Long expiry = failed.get(pos);
        return expiry != null && expiry > level.getGameTime();
    }

    /** Reachability was judged from where the worker stood; once it has moved away, judge again. */
    private void refreshUnreachableMemory(WorkerInfo worker) {
        BlockPos anchor = failureAnchors.get(worker.pokemonUUID);
        if (anchor != null
                && anchor.distSqr(worker.position) > UNREACHABLE_FORGET_MOVE_DIST * UNREACHABLE_FORGET_MOVE_DIST) {
            forgetUnreachable(worker.pokemonUUID);
        }
    }

    void forgetUnreachableNear(BlockPos pos) {
        for (WorkerInfo worker : workers.all()) {
            if (!WorkRange.contains(worker.position, pos)) {
                continue;
            }
            forgetUnreachable(worker.pokemonUUID);
        }
    }

    /** Drop everything remembered about a worker that's gone. */
    void forget(UUID workerUUID) {
        forgetUnreachable(workerUUID);
        lastConsidered.remove(workerUUID);
    }

    void forgetUnreachable(UUID workerUUID) {
        recentlyFailedJobs.remove(workerUUID);
        failureAnchors.remove(workerUUID);
    }

    void rememberUnreachable(UUID workerUUID, @Nullable BlockPos workerPos, BlockPos jobPos, long now) {
        recentlyFailedJobs
                .computeIfAbsent(workerUUID, k -> new ConcurrentHashMap<>())
                .put(jobPos, now + JOB_FAILURE_BLACKLIST_TICKS);
        if (workerPos != null) {
            failureAnchors.putIfAbsent(workerUUID, workerPos);
        }
    }

    /** Whether any worker remembers a job it couldn't reach. */
    boolean hasUnreachable() {
        return !recentlyFailedJobs.isEmpty();
    }

    /** Expire per-worker job-failure blacklist entries (stored as game ticks). */
    void expireUnreachable(long gameTime) {
        recentlyFailedJobs.values().forEach(failed -> failed.entrySet().removeIf(e -> e.getValue() <= gameTime));
        recentlyFailedJobs.entrySet().removeIf(e -> e.getValue().isEmpty());
        failureAnchors.keySet().retainAll(recentlyFailedJobs.keySet());
    }
}
