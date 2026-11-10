package dev.palworks.command;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.debug.DebugInfo;
import dev.palworks.debug.DebugManager;
import dev.palworks.inventory.ModMenuTypes;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.manager.JobManager;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Implements the /palworks commands: a worker-activity chat toggle, worker inspection/control,
 * inventory access, and the job-state dump.
 */
public class DebugCommand {

    /** Toggle whether this player sees worker job activity in chat. */
    public static void toggleDebug(ServerPlayer player) {
        boolean on = DebugManager.toggleWorkerActivity(player.getUUID());
        player.sendSystemMessage(Component.literal("Worker activity chat: " + (on ? "ON" : "OFF")));
    }

    /** Toggle the in-world worker overlay (job icons above heads, paths) for this player. */
    public static void toggleOverlay(ServerPlayer player) {
        boolean on = DebugManager.toggleWorkerOverlay(player.getUUID());
        player.sendSystemMessage(Component.literal("Worker overlay: " + (on ? "ON" : "OFF")));
    }

    /** Clear all worker reservations (force fresh job assignments). */
    public static void clearAllReservations(ServerPlayer player) {
        int count = 0;
        if (player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            for (Entity entity : serverLevel.getAllEntities()) {
                if (entity instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemonEntity) {
                    Pokemon pokemon = pokemonEntity.getPokemon();
                    if (pokemon != null && pokemon.getOwnerUUID() != null) {
                        WorkerState state = WorkerStateManager.get(pokemon);
                        if (state != null && state.getCurrentReservation() != null) {
                            state.clearReservation();
                            WorkerStateManager.save(pokemon, state);
                            count++;
                        }
                    }
                }
            }
        }
        player.sendSystemMessage(Component.literal("Cleared reservations for " + count + " workers"));
    }

    /** Show inventory of nearest worker. */
    public static void showWorkerInventory(ServerPlayer player) {
        Pokemon pokemon = findNearestOwnedPokemon(player);
        if (pokemon == null) {
            player.sendSystemMessage(Component.literal("No owned Pokemon nearby"));
            return;
        }

        WorkerState state = WorkerStateManager.get(pokemon);
        if (state == null) {
            player.sendSystemMessage(Component.literal(pokemon.getSpecies().getName() + " is not a worker"));
            return;
        }

        player.sendSystemMessage(Component.literal("=== " + pokemon.getSpecies().getName() + " Inventory ==="));

        WorkerInventory inventory = WorkerInventoryManager.getOrCreate(pokemon);
        if (inventory.isEmpty()) {
            player.sendSystemMessage(Component.literal("Inventory: EMPTY"));
        } else {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    player.sendSystemMessage(Component.literal(String.format("  Slot %d: %dx %s",
                        i, stack.getCount(), stack.getItem().toString())));
                }
            }
            player.sendSystemMessage(Component.literal("Total: " + inventory.getTotalItemCount() + " items"));
        }

        if (state.getCurrentReservation() != null) {
            player.sendSystemMessage(Component.literal("Current Job: " + state.getCurrentReservation().getJobType()));
            player.sendSystemMessage(Component.literal("Job Step: " + state.getJobStep() + " (" + state.getStepTicks() + " ticks in)"));
        } else {
            player.sendSystemMessage(Component.literal("Current Job: IDLE"));
        }
    }

    /** Clear inventory of nearest worker. */
    public static void clearWorkerInventory(ServerPlayer player) {
        Pokemon pokemon = findNearestOwnedPokemon(player);
        if (pokemon == null) {
            player.sendSystemMessage(Component.literal("No owned Pokemon nearby"));
            return;
        }

        WorkerState state = WorkerStateManager.get(pokemon);
        if (state == null) {
            player.sendSystemMessage(Component.literal(pokemon.getSpecies().getName() + " is not a worker"));
            return;
        }

        WorkerInventory inventory = WorkerInventoryManager.getOrCreate(pokemon);
        inventory.clearContent();

        player.sendSystemMessage(Component.literal("Cleared " + pokemon.getSpecies().getName() + "'s inventory"));
    }

    /** Open the inventory GUI for the nearest worker. */
    public static void openWorkerInventoryGUI(ServerPlayer player) {
        Pokemon pokemon = findNearestOwnedPokemon(player);
        if (pokemon == null) {
            player.sendSystemMessage(Component.literal("No owned Pokemon nearby"));
            return;
        }
        ModMenuTypes.openWorkerInventory(player, pokemon);
    }

    /** Show worker info for the nearest Pokémon worker to the player. */
    public static void showNearestWorkerInfo(ServerPlayer player) {
        Vec3 playerPos = player.position();
        AABB searchBox = new AABB(playerPos.x - 10, playerPos.y - 10, playerPos.z - 10,
                                  playerPos.x + 10, playerPos.y + 10, playerPos.z + 10);

        for (Entity entity : player.level().getEntities(player, searchBox)) {
            Pokemon pokemon = getPokemonFromEntity(entity);
            if (pokemon != null && WorkerStateManager.hasWorkerState(pokemon)) {
                showWorkerInfo(player, pokemon);
                return;
            }
        }

        player.sendSystemMessage(Component.literal("No workers nearby"));
    }

    /** Show detailed worker info. */
    public static void showWorkerInfo(ServerPlayer player, Pokemon pokemon) {
        DebugInfo.WorkerDebugInfo info = DebugInfo.getWorkerInfo(pokemon, null);
        if (info == null) {
            player.sendSystemMessage(Component.literal("Not a worker"));
            return;
        }

        for (String line : info.toStringList()) {
            player.sendSystemMessage(Component.literal(line));
        }
    }

    /**
     * Show detailed job debug information: available jobs, reserved jobs, workers, and the
     * per-worker assignment analysis.
     */
    public static void showJobsInfo(ServerPlayer player) {
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            player.sendSystemMessage(Component.literal("Must be run on server"));
            return;
        }

        JobManager jobManager = JobManager.getInstance(serverLevel);
        for (String line : jobManager.getJobsDebugInfo(serverLevel)) {
            player.sendSystemMessage(Component.literal(line));
        }
    }

    /** Enable the nearest Pokemon as a worker. */
    public static void enableNearestWorker(ServerPlayer player) {
        Pokemon pokemon = findNearestOwnedPokemon(player);
        if (pokemon == null) {
            player.sendSystemMessage(Component.literal("No owned Pokemon nearby"));
            return;
        }

        WorkerState state = WorkerStateManager.getOrCreate(pokemon);
        if (state.isEnabled()) {
            player.sendSystemMessage(Component.literal(pokemon.getSpecies().getName() + " is already a worker"));
            return;
        }

        WorkerStateManager.setEnabled(pokemon, true);
        player.sendSystemMessage(Component.literal("Enabled " + pokemon.getSpecies().getName() + " as a worker!"));
    }

    /** Disable the nearest Pokemon worker. */
    public static void disableNearestWorker(ServerPlayer player) {
        Pokemon pokemon = findNearestOwnedPokemon(player);
        if (pokemon == null) {
            player.sendSystemMessage(Component.literal("No owned Pokemon nearby"));
            return;
        }

        WorkerState state = WorkerStateManager.get(pokemon);
        if (state == null || !state.isEnabled()) {
            player.sendSystemMessage(Component.literal(pokemon.getSpecies().getName() + " is not a worker"));
            return;
        }

        WorkerStateManager.setEnabled(pokemon, false);
        player.sendSystemMessage(Component.literal("Disabled " + pokemon.getSpecies().getName() + " as a worker"));
    }

    /** Find the nearest owned Pokemon to the player. */
    @Nullable
    private static Pokemon findNearestOwnedPokemon(ServerPlayer player) {
        for (Entity entity : player.level().getEntities(player,
                player.getBoundingBox().inflate(10.0))) {
            Pokemon pokemon = getPokemonFromEntity(entity);
            if (pokemon != null && pokemon.getOwnerUUID() != null
                    && pokemon.getOwnerUUID().equals(player.getUUID())) {
                return pokemon;
            }
        }
        return null;
    }

    private static Pokemon getPokemonFromEntity(Entity entity) {
        if (entity instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemonEntity) {
            return pokemonEntity.getPokemon();
        }
        return null;
    }
}
