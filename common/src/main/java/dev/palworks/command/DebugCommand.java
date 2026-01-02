package dev.palworks.command;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.base.BaseState;
import dev.palworks.base.BaseStateManager;
import dev.palworks.debug.DebugInfo;
import dev.palworks.debug.DebugManager;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Debug command handler for Palworks.
 * Provides commands to view and toggle debug information.
 */
public class DebugCommand {

    /**
     * Toggle a debug flag for a player.
     */
    public static void toggleDebug(ServerPlayer player, DebugManager.DebugFlag flag) {
        UUID playerUUID = player.getUUID();
        DebugManager.toggle(playerUUID, flag);

        DebugManager.DebugFlags flags = DebugManager.getFlags(playerUUID);
        player.sendSystemMessage(Component.literal(flags.getStatusString()));
    }

    /**
     * Show worker info for the nearest Pokémon worker to the player.
     */
    public static void showNearestWorkerInfo(ServerPlayer player) {
        Vec3 playerPos = player.position();

        // Find nearest entity within 10 blocks
        AABB searchBox = new AABB(playerPos.x - 10, playerPos.y - 10, playerPos.z - 10,
                                  playerPos.x + 10, playerPos.y + 10, playerPos.z + 10);

        List<Entity> nearbyEntities = player.level().getEntities(player, searchBox);

        // Try to find a Cobblemon entity with worker state
        for (Entity entity : nearbyEntities) {
            // Platform-specific: This will need to check for Cobblemon's PokemonEntity
            // For now, we'll use a placeholder
            Pokemon pokemon = getPokemonFromEntity(entity);
            if (pokemon != null && WorkerStateManager.hasWorkerState(pokemon)) {
                showWorkerInfo(player, pokemon);
                return;
            }
        }

        player.sendSystemMessage(Component.literal("No workers nearby"));
    }

    /**
     * Show detailed worker info.
     */
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
     * Show base info for the nearest base or the player's linked base.
     */
    public static void showNearestBaseInfo(ServerPlayer player) {
        BlockPos playerPos = player.blockPosition();
        DebugInfo.BaseDebugInfo info = DebugInfo.getNearestBaseInfo(player.level(), playerPos, 128);

        if (info == null) {
            player.sendSystemMessage(Component.literal("No bases nearby"));
            return;
        }

        for (String line : info.toStringList()) {
            player.sendSystemMessage(Component.literal(line));
        }
    }

    /**
     * Show info for a station at the player's target block.
     */
    public static void showTargetStationInfo(ServerPlayer player, BlockPos targetPos) {
        if (targetPos == null) {
            player.sendSystemMessage(Component.literal("No target block"));
            return;
        }

        DebugInfo.StationDebugInfo info = DebugInfo.getStationInfo(player.level(), targetPos);
        if (info == null) {
            player.sendSystemMessage(Component.literal("Not a station"));
            return;
        }

        for (String line : info.toStringList()) {
            player.sendSystemMessage(Component.literal(line));
        }
    }

    /**
     * Create a new base at the player's position.
     */
    public static void createBase(ServerPlayer player) {
        BlockPos pos = player.blockPosition();
        BaseState base = BaseStateManager.getOrCreate(player.level(), pos);

        player.sendSystemMessage(Component.literal("Created base at " +
                String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ())));
    }

    /**
     * Set work radius for the nearest base.
     */
    public static void setBaseRadius(ServerPlayer player, int radius) {
        BlockPos playerPos = player.blockPosition();
        BaseState base = BaseStateManager.findNearestBase(player.level(), playerPos, 128);

        if (base == null) {
            player.sendSystemMessage(Component.literal("No base nearby"));
            return;
        }

        base.setWorkRadius(radius);
        BaseStateManager.save(player.level(), base.getBasePos(), base);

        player.sendSystemMessage(Component.literal("Set base radius to " + radius));
    }

    /**
     * List all debug info in range.
     */
    public static void listAllInfo(ServerPlayer player) {
        // Workers
        int workerCount = DebugManager.getAllWorkerInfo().size();
        player.sendSystemMessage(Component.literal("Workers: " + workerCount));

        // Stations
        int stationCount = DebugManager.getAllStationInfo().size();
        player.sendSystemMessage(Component.literal("Stations: " + stationCount));

        // Bases
        int baseCount = DebugManager.getAllBaseInfo().size();
        player.sendSystemMessage(Component.literal("Bases: " + baseCount));
    }

    /**
     * Test tick the nearest Pokemon worker.
     */
    public static void testTickWorker(ServerPlayer player) {
        Pokemon pokemon = findNearestOwnedPokemon(player);
        if (pokemon == null) {
            player.sendSystemMessage(Component.literal("No owned Pokemon nearby"));
            return;
        }

        // Get the entity
        net.minecraft.world.entity.Entity entity = pokemon.getEntity();
        if (entity == null) {
            player.sendSystemMessage(Component.literal("Pokemon has no entity!"));
            return;
        }

        if (!(entity instanceof net.minecraft.world.entity.Mob mob)) {
            player.sendSystemMessage(Component.literal("Pokemon entity is not a Mob! Class: " + entity.getClass().getName()));
            return;
        }

        player.sendSystemMessage(Component.literal("Manually ticking " + pokemon.getSpecies().getName() + "..."));

        // Call WorkerAI.tick() manually
        boolean result = dev.palworks.ai.WorkerAI.tick(pokemon, mob, player.level());

        player.sendSystemMessage(Component.literal("Tick result: " + result));
        player.sendSystemMessage(Component.literal("Check console/logs for debug output!"));
    }

    /**
     * Enable the nearest Pokemon as a worker.
     */
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

        // Enable the worker
        state.setEnabled(true);

        // Link to nearest base or create one
        BlockPos playerPos = player.blockPosition();
        BaseState base = BaseStateManager.findNearestBase(player.level(), playerPos, 128);

        if (base == null) {
            // Create a base at player position
            base = BaseStateManager.getOrCreate(player.level(), playerPos);
            player.sendSystemMessage(Component.literal("Created base at your position"));
        }

        state.setBasePos(base.getBasePos());
        base.linkWorker(pokemon.getUuid());

        WorkerStateManager.save(pokemon, state);
        BaseStateManager.save(player.level(), base.getBasePos(), base);

        player.sendSystemMessage(Component.literal("Enabled " + pokemon.getSpecies().getName() + " as a worker!"));
    }

    /**
     * Disable the nearest Pokemon worker.
     */
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

        // Unlink from base
        BlockPos basePos = state.getBasePos();
        if (basePos != null) {
            BaseState base = BaseStateManager.get(player.level(), basePos);
            if (base != null) {
                base.unlinkWorker(pokemon.getUuid());
                BaseStateManager.save(player.level(), basePos, base);
            }
        }

        // Disable the worker
        state.setEnabled(false);
        state.clearReservation();
        WorkerStateManager.save(pokemon, state);

        player.sendSystemMessage(Component.literal("Disabled " + pokemon.getSpecies().getName() + " as a worker"));
    }

    /**
     * Find the nearest owned Pokemon to the player.
     */
    @Nullable
    private static Pokemon findNearestOwnedPokemon(ServerPlayer player) {
        Vec3 playerPos = player.position();
        double searchRadius = 10.0;

        // Search for nearby entities
        for (Entity entity : player.level().getEntities(player,
                player.getBoundingBox().inflate(searchRadius))) {
            Pokemon pokemon = getPokemonFromEntity(entity);
            if (pokemon != null && pokemon.getOwnerUUID() != null
                    && pokemon.getOwnerUUID().equals(player.getUUID())) {
                return pokemon;
            }
        }

        return null;
    }

    /**
     * Platform-specific: Get Pokemon from an entity.
     * This will need to be implemented in the platform modules.
     */
    private static Pokemon getPokemonFromEntity(Entity entity) {
        // Check if entity is a Cobblemon PokemonEntity
        if (entity instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemonEntity) {
            return pokemonEntity.getPokemon();
        }
        return null;
    }
}
