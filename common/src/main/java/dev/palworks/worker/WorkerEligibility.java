package dev.palworks.worker;

import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Checks if a Pokémon is eligible to be a worker.
 * Only owned Pokémon that are sent out in the world can work.
 */
public class WorkerEligibility {

    /**
     * Check if a Pokémon is eligible to work.
     * Must be:
     * 1. Owned by a player
     * 2. Currently in the world (sent out from party or pasture)
     *
     * @param pokemon The Pokémon to check
     * @return true if eligible to work
     */
    /**
     * Whether the Pokemon is busy with something that should pause its work: in a battle, or
     * evolving. Uses Cobblemon's own busy flag (PokemonEntity.isBusy, set by its battle and
     * evolution locks), so anything else Cobblemon marks as busy pauses work too.
     */
    public static boolean isBusy(Entity entity) {
        return entity instanceof PokemonEntity pokemonEntity && pokemonEntity.isBusy();
    }

    /** Whether the Pokemon has fainted (0 HP): it can't work until it's healed. */
    public static boolean isFainted(Pokemon pokemon) {
        return pokemon.getCurrentHealth() <= 0;
    }

    public static boolean isEligible(Pokemon pokemon) {
        // Must be owned
        if (!isOwned(pokemon)) {
            return false;
        }

        // Must be in the world (sent out)
        // This is checked by whether the Pokemon has an entity
        return pokemon.getEntity() != null;
    }

    /**
     * Check if a Pokémon is owned by a player.
     */
    public static boolean isOwned(Pokemon pokemon) {
        return pokemon.getOwnerUUID() != null;
    }

    /**
     * Get the owner UUID of a Pokémon.
     */
    @Nullable
    public static UUID getOwnerUUID(Pokemon pokemon) {
        return pokemon.getOwnerUUID();
    }

    /**
     * Check if a Pokémon is from the player's active party.
     * This checks if the Pokemon is stored in the player's party storage.
     */
    public static boolean isFromParty(Pokemon pokemon) {
        if (!isOwned(pokemon)) {
            return false;
        }

        try {
            // Check if the Pokemon's store is a PlayerPartyStore
            var storeCoords = pokemon.getStoreCoordinates().get();
            return storeCoords != null && storeCoords.getStore() instanceof PlayerPartyStore;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if a Pokémon is from a pasture.
     * Pasture Pokémon have store coordinates but are not in party.
     */
    public static boolean isFromPasture(Pokemon pokemon) {
        if (!isOwned(pokemon)) {
            return false;
        }

        try {
            // Has store coordinates but not from party
            var storeCoords = pokemon.getStoreCoordinates().get();
            return storeCoords != null && !isFromParty(pokemon);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get a description of where this Pokemon is from.
     */
    public static String getSourceDescription(Pokemon pokemon) {
        if (!isOwned(pokemon)) {
            return "Wild";
        }

        if (isFromParty(pokemon)) {
            return "Party";
        }

        if (isFromPasture(pokemon)) {
            return "Pasture";
        }

        return "Unknown";
    }

    /**
     * Check if a Pokémon entity is eligible to work.
     * Convenience method that extracts the Pokemon from the entity.
     */
    public static boolean isEligible(Entity entity) {
        Pokemon pokemon = getPokemonFromEntity(entity);
        return pokemon != null && isEligible(pokemon);
    }

    /**
     * Platform-specific: Get Pokemon from entity.
     * This needs to be implemented per platform or updated when entity is known.
     */
    @Nullable
    private static Pokemon getPokemonFromEntity(Entity entity) {
        // This would check if entity instanceof PokemonEntity
        // and return entity.getPokemon()
        // For now, return null - platform modules will need to implement this
        return null;
    }
}
