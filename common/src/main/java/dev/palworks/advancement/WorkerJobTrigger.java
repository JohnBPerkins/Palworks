package dev.palworks.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.palworks.job.JobType;
import dev.palworks.suitability.Suitability;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;

/**
 * {@code palworks:worker_job}: one of the player's workers did a piece of work (see {@link JobDone}).
 * Every condition is optional; with none, any work counts:
 *
 * <ul>
 *   <li>{@code jobs}: job type ids ({@code chop_tree}...), any of them
 *   <li>{@code skill}: a work skill id the job took ({@code nether}...)
 *   <li>{@code min_level}: the worker's level in {@code skill}, or with no skill in every skill the
 *       job took
 *   <li>{@code blocks}: the block worked (a block id, a list, or a {@code #tag})
 *   <li>{@code min_amount}: at least this much at once (a tree's logs)
 *   <li>{@code labels}: the species has any of these labels ({@code legendary}...)
 * </ul>
 */
public final class WorkerJobTrigger extends SimpleCriterionTrigger<WorkerJobTrigger.TriggerInstance> {
    private static final Codec<JobType> JOB_TYPE = Codec.STRING.comapFlatMap(
            id -> {
                try {
                    return DataResult.success(JobType.valueOf(id.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    return DataResult.error(() -> "Unknown job type: " + id);
                }
            },
            JobType::getId);

    private static final Codec<Suitability> SKILL = Codec.STRING.comapFlatMap(
            id -> {
                Suitability skill = Suitability.fromId(id);
                return skill != null ? DataResult.success(skill) : DataResult.error(() -> "Unknown work skill: " + id);
            },
            Suitability::getId);

    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    public void trigger(ServerPlayer player, JobDone job) {
        trigger(player, instance -> instance.matches(job));
    }

    public record TriggerInstance(
            Optional<ContextAwarePredicate> player,
            Optional<List<JobType>> jobs,
            Optional<Suitability> skill,
            Optional<Integer> minLevel,
            Optional<HolderSet<Block>> blocks,
            Optional<Integer> minAmount,
            Optional<List<String>> labels)
            implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        EntityPredicate.ADVANCEMENT_CODEC
                                .optionalFieldOf("player")
                                .forGetter(TriggerInstance::player),
                        JOB_TYPE.listOf().optionalFieldOf("jobs").forGetter(TriggerInstance::jobs),
                        SKILL.optionalFieldOf("skill").forGetter(TriggerInstance::skill),
                        Codec.INT.optionalFieldOf("min_level").forGetter(TriggerInstance::minLevel),
                        RegistryCodecs.homogeneousList(Registries.BLOCK)
                                .optionalFieldOf("blocks")
                                .forGetter(TriggerInstance::blocks),
                        Codec.INT.optionalFieldOf("min_amount").forGetter(TriggerInstance::minAmount),
                        Codec.STRING.listOf().optionalFieldOf("labels").forGetter(TriggerInstance::labels))
                .apply(instance, TriggerInstance::new));

        public boolean matches(JobDone job) {
            if (jobs.isPresent() && !jobs.get().contains(job.type())) {
                return false;
            }
            if (skill.isPresent() && !job.levels().containsKey(skill.get())) {
                return false;
            }
            if (minLevel.isPresent() && !hasLevel(job, minLevel.get())) {
                return false;
            }
            if (blocks.isPresent() && !job.block().is(blocks.get())) {
                return false;
            }
            if (minAmount.isPresent() && job.amount() < minAmount.get()) {
                return false;
            }
            return labels.isEmpty() || labels.get().stream().anyMatch(job.labels()::contains);
        }

        private boolean hasLevel(JobDone job, int min) {
            if (skill.isPresent()) {
                return job.levels().getOrDefault(skill.get(), 0) >= min;
            }
            return !job.levels().isEmpty() && job.levels().values().stream().allMatch(level -> level >= min);
        }
    }
}
