package dev.palworks.worker;

import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.config.PalworksConfig;
import dev.palworks.job.WorkRange;
import dev.palworks.suitability.SuitabilityDetector;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
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
     * Whether it has any work it can do (a work suitability). One without isn't a worker: Palworks
     * leaves it entirely to Cobblemon, as it does one whose work is off. It can gain or lose its
     * last one (an evolution, a form change, a datapack or an admin's change).
     */
    public static boolean canWork(Pokemon pokemon) {
        return !SuitabilityDetector.getSuitabilities(pokemon).isEmpty();
    }

    /**
     * Whether Palworks runs this Pokémon as a worker now: its work is on and it has work it can do.
     * Its work is on or off where it is now, in its owner's party or in a pasture (a switch for
     * each). {@code state} is its worker state, or null if it has none yet (then it's as the
     * config's {@code party_work_on_by_default} or {@code pasture_work_on_by_default} says).
     */
    public static boolean works(Pokemon pokemon, @Nullable WorkerState state) {
        boolean inParty = isFromParty(pokemon);
        boolean on = state == null ? PalworksConfig.current().workOnByDefault(inParty) : state.isEnabled(inParty);
        return on && canWork(pokemon);
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

        // Check if the Pokemon's store is a PlayerPartyStore
        var storeCoords = pokemon.getStoreCoordinates().get();
        return storeCoords != null && storeCoords.getStore() instanceof PlayerPartyStore;
    }

    /**
     * Where the owner of a party Pokémon is, if they're in {@code level}: a party Pokémon only
     * works near its owner. Null if they aren't (or it isn't from a party).
     */
    @Nullable
    public static BlockPos partyOwnerPosition(Pokemon pokemon, Level level) {
        ServerPlayer owner = pokemon.getOwnerPlayer();
        return owner != null && level.equals(owner.level()) ? owner.blockPosition() : null;
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
     * Where a pastured Pokémon's pasture lets it roam, or null if it isn't tethered (or isn't a
     * Pokémon). Cobblemon checks it every tick: one that steps outside is taken back to its pasture
     * block, or recalled if there's no room there (PokemonEntity.checkPastureTether).
     */
    @Nullable
    public static AABB roamOf(Entity entity) {
        return entity instanceof PokemonEntity pokemon && pokemon.getTethering() != null
                ? pokemon.getTethering().getBox()
                : null;
    }

    /** The pasture block a pastured Pokémon is tethered to, or null if it isn't (or isn't a Pokémon). */
    @Nullable
    public static BlockPos pastureOf(Entity entity) {
        return entity instanceof PokemonEntity pokemon && pokemon.getTethering() != null
                ? pokemon.getTethering().getPasturePos()
                : null;
    }
}
