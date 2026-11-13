package dev.palworks.job.handler;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.job.JobManager;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/**
 * A transporter brings seeds it took from a chest to the planter that asked for them, and drops
 * them at its feet (see JobManager.handOverSeeds). The planter carries on working meanwhile: once
 * it's moved off from where the job was set, the job's gone, and the transporter is sent again
 * to where it is now.
 */
final class BringSeedsHandler implements JobHandler {
    // How far (blocks) the planter can move from where the job was set before it's gone after it
    private static final double PLANTER_MOVED = 3.0;

    private final List<Step> steps = List.of(Step.of("hand over seeds", 10, Step.effect(BringSeedsHandler::handOver))
            .thereWhile(BringSeedsHandler::planterStillThere));

    @Override
    public List<Step> steps() {
        return steps;
    }

    private static boolean planterStillThere(JobContext ctx) {
        if (!(ctx.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        PokemonEntity planter =
                JobManager.getInstance(serverLevel).seedsRecipient(ctx.pokemon().getUuid());
        return planter != null
                && planter.isAlive()
                && planter.position()
                        .closerThan(net.minecraft.world.phys.Vec3.atBottomCenterOf(ctx.pos()), PLANTER_MOVED);
    }

    private static void handOver(JobContext ctx) {
        if (ctx.level() instanceof ServerLevel serverLevel) {
            JobManager.getInstance(serverLevel).handOverSeeds(ctx.pokemon());
            ctx.didWork(1);
        }
    }
}
