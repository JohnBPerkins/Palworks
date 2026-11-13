package dev.palworks.job;

import dev.palworks.Palworks;
import dev.palworks.drops.TrackedDrops;
import dev.palworks.job.WorkerRegistry.WorkerInfo;
import dev.palworks.suitability.Suitability;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Seeds on their way to planters. A planter can't take seeds out of a chest (only a worker that
 * transports can), so one that's run out of seeds for the open farmland (or soul sand) round it
 * asks for some: a transporter takes them from a chest, brings them to wherever the planter is
 * now and drops them at its feet, and the planter picks them up (TAKE_SEEDS). Seeds a player throws
 * by a planter with room for them are handed to it the same way (see PickupQueue). In memory
 * only: after a restart a transporter still carrying seeds puts them away as any cargo, and the
 * planter asks again.
 */
final class SeedRequests {
    /** How long (ticks) seeds handed to a planter wait for it before they're an ordinary pickup. */
    static final long HANDOVER_TIMEOUT_TICKS = 60 * 20;
    /** How often (ticks) an idle planter with no seeds looks for a chest with some to ask for. */
    static final long RECHECK_TICKS = 40;

    private final JobPool pool;
    private final WorkerRegistry workers;

    // By planter: the transporter bringing them, once one has taken it on
    private final Map<UUID, Request> requests = new HashMap<>();
    // When each idle planter with no seeds last looked for some to ask for
    private final Map<UUID, Long> lastChecked = new HashMap<>();
    private final List<Handover> handovers = new ArrayList<>();

    SeedRequests(JobPool pool, WorkerRegistry workers) {
        this.pool = pool;
        this.workers = workers;
    }

    /** A planter's request for seeds, and the transporter bringing them (null till one takes it on). */
    static final class Request {
        final UUID planter;

        @Nullable
        UUID transporter;

        Request(UUID planter) {
            this.planter = planter;
        }
    }

    /** Seeds dropped for a planter at {@code pos}, at game time {@code at}. */
    private record Handover(UUID planter, BlockPos pos, long at) {}

    /** Whether this planter has asked for seeds (and isn't holding seeds handed to it yet). */
    boolean hasAsked(UUID planter) {
        return requests.containsKey(planter);
    }

    /** Whether a transporter has taken on bringing this planter seeds. */
    boolean isTakenOn(UUID planter) {
        Request request = requests.get(planter);
        return request != null && request.transporter != null;
    }

    /** Whether seeds have been left for this planter to pick up (it doesn't ask for more meanwhile). */
    boolean isHandedTo(UUID planter) {
        for (Handover handover : handovers) {
            if (handover.planter().equals(planter)) {
                return true;
            }
        }
        return false;
    }

    /** Whether it's time this planter looked again for seeds to ask for (see {@link #RECHECK_TICKS}). */
    boolean dueToLook(UUID planter, long now) {
        Long last = lastChecked.get(planter);
        if (last != null && now - last < RECHECK_TICKS) {
            return false;
        }
        lastChecked.put(planter, now);
        return true;
    }

    /** Ask for seeds for this planter. */
    void ask(UUID planter) {
        requests.computeIfAbsent(planter, Request::new);
        Palworks.LOGGER.debug("Planter {} asked for seeds", planter);
    }

    /** The requests no transporter has taken on yet. */
    List<Request> waiting() {
        List<Request> waiting = new ArrayList<>();
        for (Request request : requests.values()) {
            if (request.transporter == null) {
                waiting.add(request);
            }
        }
        return waiting;
    }

    /** The request this transporter is bringing seeds for, if any. */
    @Nullable
    Request takenOnBy(UUID transporter) {
        for (Request request : requests.values()) {
            if (transporter.equals(request.transporter)) {
                return request;
            }
        }
        return null;
    }

    /** A transporter takes on a request: it's fetching the seeds. */
    void takeOn(Request request, UUID transporter) {
        request.transporter = transporter;
        Palworks.LOGGER.debug("Transporter {} is bringing seeds to planter {}", transporter, request.planter);
    }

    /** A transporter gives up the request it took on (it couldn't get the seeds there): it waits again. */
    void giveUp(UUID transporter) {
        Request request = takenOnBy(transporter);
        if (request != null) {
            request.transporter = null;
        }
    }

    /** A planter's request is done (it has seeds), or it isn't a planter that needs any. */
    void forget(UUID planter) {
        requests.remove(planter);
    }

    /**
     * Seeds are on the ground at {@code pos} for {@code planter} (already marked as handed to it,
     * see TrackedDrops): it gets a job to pick them up, offered to it straight away if it's idle.
     * Only it can take them (JobChecks: room for seeds handed to it).
     */
    void handOver(JobAssigner assigner, WorkerInfo planter, BlockPos pos, long now) {
        // Its request (if it had one: thrown seeds come unasked) is answered
        requests.remove(planter.pokemonUUID);
        handovers.add(new Handover(planter.pokemonUUID, pos.immutable(), now));
        // The planter's skill that plants them: what a pickup job asks of it
        Suitability skill =
                planter.suitabilities.contains(Suitability.PLANTING) ? Suitability.PLANTING : Suitability.NETHER;
        JobRecord job = JobRecord.followUp(JobType.TAKE_SEEDS, pos, now, planter.pokemonUUID, EnumSet.of(skill));
        if (!planter.idle || assigner.assignFirstReachable(planter, List.of(job)) == null) {
            pool.offer(job);
        }
    }

    /** The planter has picked up the seeds handed to it: nothing's on its way to it any more. */
    void taken(UUID planter) {
        handovers.removeIf(handover -> handover.planter().equals(planter));
        requests.remove(planter);
    }

    /**
     * Every so often: forget the requests of planters that have gone (put away, or out of loaded
     * chunks) and let go of transporters that have; seeds handed to a planter that hasn't taken them
     * in {@link #HANDOVER_TIMEOUT_TICKS} become an ordinary pickup, for a transporter to put away.
     */
    void cleanUp(ServerLevel level, PickupQueue pickups) {
        long now = level.getGameTime();
        requests.values().removeIf(request -> workers.get(request.planter) == null);
        for (Request request : requests.values()) {
            if (request.transporter != null && workers.get(request.transporter) == null) {
                request.transporter = null;
            }
        }
        lastChecked.keySet().removeIf(planter -> workers.get(planter) == null);

        for (Iterator<Handover> it = handovers.iterator(); it.hasNext(); ) {
            Handover handover = it.next();
            if (now - handover.at() < HANDOVER_TIMEOUT_TICKS) {
                continue;
            }
            it.remove();
            requests.remove(handover.planter());
            pool.remove(new JobPool.JobKey(handover.pos(), JobType.TAKE_SEEDS));
            for (ItemEntity item : JobChecks.seedsHandedTo(level, handover.pos(), handover.planter())) {
                TrackedDrops.stopHandingOver(item);
            }
            pickups.add(handover.pos(), null, now);
            Palworks.LOGGER.debug(
                    "Planter {} never took the seeds left for it at {}: a pickup now",
                    handover.planter(),
                    handover.pos());
        }
    }
}
