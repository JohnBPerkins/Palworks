package dev.palworks.advancement;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.palworks.Palworks;
import dev.palworks.job.JobChecks;
import dev.palworks.job.JobManager;
import dev.palworks.job.JobType;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityDetector;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Palworks's advancement triggers (the advancements are in {@code data/palworks/advancement}), and
 * what tells them. Each goes to the worker's owner while they're online; one whose owner is offline
 * is let go, as nearly everything that earns one happens again.
 */
public final class WorkerAdvancements {
    private static final DeferredRegister<CriterionTrigger<?>> TRIGGERS =
            DeferredRegister.create(Palworks.MOD_ID, Registries.TRIGGER_TYPE);

    public static final RegistrySupplier<WorkerJobTrigger> JOB = TRIGGERS.register("worker_job", WorkerJobTrigger::new);
    public static final RegistrySupplier<WorkerEventTrigger> EVENT =
            TRIGGERS.register("worker_event", WorkerEventTrigger::new);
    public static final RegistrySupplier<WorkersTrigger> WORKERS = TRIGGERS.register("workers", WorkersTrigger::new);

    private WorkerAdvancements() {}

    /** Register the triggers. Called once, from {@link dev.palworks.PalworksCommon#init}. */
    public static void register() {
        TRIGGERS.register();
    }

    /**
     * A worker did a piece of work on the block at {@code pos}: {@code worked} is that block as it
     * was before (see {@link JobDone}).
     */
    public static void jobDone(
            Pokemon pokemon, Level level, JobType type, BlockPos pos, BlockState worked, int amount) {
        ServerPlayer owner = owner(pokemon);
        if (owner == null) {
            return;
        }
        Map<Suitability, Integer> levels = new EnumMap<>(Suitability.class);
        for (Suitability skill : JobChecks.skillsFor(type, worked, level.getBlockState(pos.below()))) {
            levels.put(skill, SuitabilityDetector.getSuitabilityLevel(pokemon, skill));
        }
        Set<String> labels = pokemon.getSpecies().getLabels();
        JOB.get().trigger(owner, new JobDone(type, levels, worked, amount, labels));
    }

    /** Something happened to a worker (see {@link WorkerEvent}): its owner is told. */
    public static void event(Pokemon pokemon, WorkerEvent event) {
        ServerPlayer owner = owner(pokemon);
        if (owner != null) {
            EVENT.get().trigger(owner, event);
        }
    }

    /** Something the player did to a worker (see {@link WorkerEvent#WOKE_A_WORKER}). */
    public static void event(ServerPlayer player, WorkerEvent event) {
        EVENT.get().trigger(player, event);
    }

    /** Another of its owner's Pokémon has started working: how many are now, in every level. */
    public static void startedWorking(Pokemon pokemon, MinecraftServer server) {
        ServerPlayer owner = owner(pokemon);
        if (owner == null) {
            return;
        }
        UUID ownerUuid = owner.getUUID();
        int working = 0;
        for (ServerLevel level : server.getAllLevels()) {
            working += JobManager.getInstance(level).workersOwnedBy(ownerUuid);
        }
        WORKERS.get().trigger(owner, working);
    }

    @Nullable
    private static ServerPlayer owner(Pokemon pokemon) {
        return pokemon.getOwnerPlayer();
    }
}
