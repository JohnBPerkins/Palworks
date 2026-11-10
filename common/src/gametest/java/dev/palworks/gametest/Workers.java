package dev.palworks.gametest;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.job.JobType;
import dev.palworks.reservation.ReservationId;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import java.util.HashMap;
import java.util.Map;
import kotlin.Unit;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Real Cobblemon Pokémon for worker tests: owned by a (mock) player and sent out into the test. */
final class Workers {
    private Workers() {}

    /** How far around a worker test to clear: past the 32-block range workers look for jobs in. */
    private static final int ISOLATION_RADIUS = 40;

    /**
     * Clear everything earlier tests left around this test's area, so its workers only find the
     * jobs it sets up. Batches keep tests running at the same time apart, but earlier tests' blocks
     * (farmland, trees, chests...), dropped items and Pokémon stay in the world, and workers look
     * for jobs 32 blocks around them. In the superflat test world everything from the test floor
     * up was put there by a test.
     */
    static void isolate(GameTestHelper helper) {
        var level = helper.getLevel();
        AABB own = helper.getBounds();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = -ISOLATION_RADIUS; x <= ISOLATION_RADIUS + 16; x++) {
            for (int z = -ISOLATION_RADIUS; z <= ISOLATION_RADIUS + 16; z++) {
                for (int y = 0; y < 26; y++) {
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (own.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
                        continue;
                    }
                    // A grass floor level with the test's own, so a worker that wanders off the
                    // edge isn't left straddling a ledge; everything above it cleared
                    var want = y == 0 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    if (!level.getBlockState(pos).equals(want)) {
                        level.setBlock(pos, want, 2);
                    }
                }
            }
        }
        AABB around = own.inflate(ISOLATION_RADIUS);
        level.getEntitiesOfClass(ItemEntity.class, around).forEach(ItemEntity::discard);
        level.getEntitiesOfClass(PokemonEntity.class, around).forEach(PokemonEntity::discard);

        // The test server only force-loads the chunks the test's own structure is in, and only
        // those tick entities. A worker that steps a block past the edge into the next chunk froze
        // there (and, no longer ticking, was unregistered and lost its job), so a test failed
        // whenever its area happened to sit on a chunk boundary. Keep a margin around it ticking.
        int minChunkX = ((int) Math.floor(own.minX) - TICKING_MARGIN) >> 4;
        int maxChunkX = ((int) Math.floor(own.maxX) + TICKING_MARGIN) >> 4;
        int minChunkZ = ((int) Math.floor(own.minZ) - TICKING_MARGIN) >> 4;
        int maxChunkZ = ((int) Math.floor(own.maxZ) + TICKING_MARGIN) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
    }

    /** Blocks around a worker test kept entity-ticking, for workers that wander past its edge. */
    private static final int TICKING_MARGIN = 16;

    /**
     * Send out an owned Pokémon at a spot in the test (relative coordinates). Its owner is a mock
     * player standing in the test area: a party Pokémon needs its owner online to stay out and work.
     * (On NeoForge the test mod lets Cobblemon's packets through to the mock player's fake
     * connection, which never negotiated them.)
     */
    @SuppressWarnings("removal") // makeMockServerPlayerInLevel: vanilla's only mock player in 1.21.1
    static PokemonEntity sendOut(GameTestHelper helper, String properties, BlockPos rel) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        BlockPos abs = helper.absolutePos(rel);
        owner.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);

        Pokemon pokemon = PokemonProperties.Companion.parse(properties).create();
        Cobblemon.INSTANCE.getStorage().getParty(owner).add(pokemon);
        PokemonEntity entity = pokemon.sendOut(helper.getLevel(), Vec3.atBottomCenterOf(abs), null, e -> Unit.INSTANCE);
        if (entity == null) {
            helper.fail("Cobblemon didn't send out " + properties);
        }
        return entity;
    }

    /**
     * Fail the test the moment two of these workers hold the same job at once: the same block and
     * the same kind of job (a job's identity, see ReservationId). Checked every tick, from each
     * worker's own state, which is what it acts on. Deliveries are left out: each is a worker
     * emptying its own cargo, and two can unload into one chest at once.
     */
    static void neverShareAJob(GameTestHelper helper, PokemonEntity... workers) {
        helper.onEachTick(() -> {
            Map<ReservationId, PokemonEntity> held = new HashMap<>();
            for (PokemonEntity worker : workers) {
                WorkerState state = WorkerStateManager.get(worker.getPokemon());
                ReservationId job = state == null ? null : state.getCurrentReservation();
                if (job == null || job.getJobType() == JobType.DELIVER_ITEMS) {
                    continue;
                }
                PokemonEntity other = held.put(job, worker);
                if (other != null) {
                    helper.fail(job.getJobType() + " at " + job.getStationPos() + " is held by two workers at once ("
                            + other.getPokemon().getSpecies().getName() + " and "
                            + worker.getPokemon().getSpecies().getName() + ")");
                }
            }
        });
    }
}
