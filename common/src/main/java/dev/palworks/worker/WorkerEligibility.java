package dev.palworks.worker;

import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.job.WorkRange;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Checks if a Pokémon is eligible to be a worker.
 * Only owned Pokémon that are sent out in the world can work.
 */
public class WorkerEligibility {

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

    /**
     * Check if a Pokémon is eligible to work.
     * Must be:
     * 1. Owned by a player
     * 2. Currently in the world (sent out from party or pasture)
     *
     * @param pokemon The Pokémon to check
     * @return true if eligible to work
     */
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
     * Where the owner of a party Pokémon is, if they're in {@code level}: a party Pokémon only
     * works near its owner. Null if they aren't (or it isn't from a party).
     */
    @Nullable
    public static BlockPos partyOwnerPosition(Pokemon pokemon, Level level) {
        ServerPlayer owner = pokemon.getOwnerPlayer();
        return owner != null && owner.level() == level ? owner.blockPosition() : null;
    }

    /**
     * Whether a worker should keep going to a job at {@code pos} as far as its owner goes: one from
     * a party only while it's within {@link WorkRange#OWNER_LEASH} of its owner (past that, Cobblemon
     * teleports it back to them), one from a pasture always (it works round its pasture).
     */
    public static boolean withinOwnerLeash(Pokemon pokemon, Level level, BlockPos pos) {
        return !isFromParty(pokemon) || WorkRange.withinOwnerLeash(partyOwnerPosition(pokemon, level), pos);
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
}
