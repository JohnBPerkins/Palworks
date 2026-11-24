package dev.palworks.advancement;

import dev.palworks.job.JobType;
import dev.palworks.suitability.Suitability;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A piece of work a worker has just done, as the {@code palworks:worker_job} trigger sees it (see
 * {@link WorkerJobTrigger}).
 *
 * @param type the job
 * @param levels the work skills the job took (nether wart is Nether work, a nether tree Lumbering and
 *     Nether), each with the worker's level in it
 * @param block the block it worked, as it was before the work (a mined ore, the felled tree's trunk)
 * @param amount how much of it at once: a felled tree's logs, otherwise 1
 * @param labels the worker's species' labels (Cobblemon's: {@code legendary}, {@code mythical}...)
 */
public record JobDone(
        JobType type, Map<Suitability, Integer> levels, BlockState block, int amount, Set<String> labels) {}
