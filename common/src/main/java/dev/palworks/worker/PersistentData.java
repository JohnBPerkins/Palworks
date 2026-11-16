package dev.palworks.worker;

import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** What Palworks keeps in a Pokémon's own data (Cobblemon's persistent data), which goes where it goes. */
public final class PersistentData {
    private PersistentData() {}

    /**
     * Put {@code tag} under {@code key} in the Pokémon's data, and tell Cobblemon it changed.
     * Writing to the data isn't enough: Cobblemon only saves a party or PC that was told one of its
     * Pokémon changed (see its note on {@code Pokemon.persistentData}), so without this a worker's
     * state or inventory could be back as it was after a restart, unless something else about it
     * (its fullness, an aspect) happened to change too. Nothing is done if {@code tag} is what's
     * there already, so saving an unchanged worker over and over doesn't have its PC written out
     * each time.
     */
    public static void put(Pokemon pokemon, String key, Tag tag) {
        CompoundTag data = pokemon.getPersistentData();
        if (tag.equals(data.get(key))) {
            return;
        }
        data.put(key, tag);
        pokemon.onChange(null);
    }
}
