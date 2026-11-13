package dev.palworks.job;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.block.WorkerBedBlock;
import dev.palworks.sleep.Beds;
import dev.palworks.worker.WorkerEligibility;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * A level's beds taken by Pokémon that aren't workers (pastured ones with no work skills): the
 * moment Cobblemon makes one drowsy it takes a free bed by its pasture, and goes there to sleep
 * instead of where it is (see {@code sleep.NonWorkerSleep}). It has to be then: Cobblemon puts a
 * drowsy Pokémon to sleep in the same brain tick, unless it has somewhere to walk. Workers come
 * first: a non-worker only takes a bed no worker has, and a worker is never offered one a
 * non-worker has, so it's never turned out of it either. In memory only: after a reload, one asleep
 * just carries on where it is.
 */
final class NonWorkerBeds {
    // How far round its pasture block (or itself, if it isn't tethered to one) a non-worker looks for
    // a bed: about where it roams
    static final int SEARCH_RADIUS = 16;
    static final int SEARCH_VERTICAL = 4;
    // A claim whose Pokémon stops reporting in (recalled, unloaded) for longer than this is dropped
    private static final long STALE_TICKS = 5;
    // After it couldn't get to a bed, how long before it asks again (30s)
    private static final long RETRY_TICKS = 600;

    /** A bed a non-worker has, since when, and when it last reported in. */
    record Claim(BlockPos bed, long since, long lastSeen) {}

    private final JobPool pool;
    private final Map<UUID, Claim> claims = new HashMap<>();
    private final Map<UUID, Long> retryAfter = new HashMap<>();

    NonWorkerBeds(JobPool pool) {
        this.pool = pool;
    }

    /** The bed this Pokémon has, if any; asking counts as it reporting in. */
    @Nullable
    Claim claimOf(UUID pokemon, long now) {
        Claim claim = claims.get(pokemon);
        if (claim == null) {
            return null;
        }
        Claim seen = new Claim(claim.bed(), claim.since(), now);
        claims.put(pokemon, seen);
        return seen;
    }

    boolean isClaimed(BlockPos bed) {
        for (Claim claim : claims.values()) {
            if (claim.bed().equals(bed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * It wants a bed: it takes a free one by its pasture (or by itself, if it isn't tethered to
     * one), if there is one and it hasn't just failed to get to one. Null if it gets none.
     */
    @Nullable
    Claim claim(ServerLevel level, PokemonEntity entity) {
        UUID uuid = entity.getPokemon().getUuid();
        long now = level.getGameTime();
        Claim had = claims.get(uuid);
        if (had != null || now < retryAfter.getOrDefault(uuid, 0L)) {
            return had;
        }
        BlockPos pasture = WorkerEligibility.pastureOf(entity);
        BlockPos bed = freeBedFor(level, entity, pasture != null ? pasture : entity.blockPosition());
        if (bed == null) {
            return null;
        }
        Claim claim = new Claim(bed, now, now);
        claims.put(uuid, claim);
        return claim;
    }

    /** It's done with its bed (woke, recalled, got there and found it gone...). */
    void release(UUID pokemon) {
        claims.remove(pokemon);
    }

    /** It couldn't get to its bed: let it go, and don't ask again for a while. */
    void giveUp(UUID pokemon, long now) {
        claims.remove(pokemon);
        retryAfter.put(pokemon, now + RETRY_TICKS);
    }

    /**
     * The bed for it near {@code home}: one no worker or other non-worker has, that it fits, where
     * Cobblemon would let it sleep (its species' light and ground rules: {@code canSleepAt}, of the
     * ground under it, as Cobblemon's own go-to-sleep asks); the smallest, then the nearest.
     */
    @Nullable
    private BlockPos freeBedFor(ServerLevel level, PokemonEntity entity, BlockPos home) {
        BlockPos best = null;
        int bestSize = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                home.offset(-SEARCH_RADIUS, -SEARCH_VERTICAL, -SEARCH_RADIUS),
                home.offset(SEARCH_RADIUS, SEARCH_VERTICAL, SEARCH_RADIUS))) {
            BlockState state = level.getBlockState(pos);
            if (!JobChecks.isBed(state) || !(state.getBlock() instanceof WorkerBedBlock bed)) {
                continue;
            }
            BlockPos origin = pos.immutable();
            double dist = entity.blockPosition().distSqr(origin);
            if (bed.size() > bestSize || (bed.size() == bestSize && dist >= bestDist)) {
                continue;
            }
            if (!pool.isReserved(new JobPool.JobKey(origin, JobType.SLEEP))
                    && !isClaimed(origin)
                    && Beds.fits(level, origin, bed, entity)
                    && entity.canSleepAt(Beds.standTile(origin, bed).below())) {
                best = origin;
                bestSize = bed.size();
                bestDist = dist;
            }
        }
        return best;
    }

    /** Drop the claims of Pokémon that stopped reporting in (recalled, unloaded, now workers). */
    void dropStale(long now) {
        claims.values().removeIf(claim -> now - claim.lastSeen() > STALE_TICKS);
        retryAfter.values().removeIf(until -> until <= now);
    }
}
