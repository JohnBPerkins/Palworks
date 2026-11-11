package dev.palworks.mixin.client;

import com.cobblemon.mod.common.client.gui.summary.Summary;
import com.cobblemon.mod.common.pokemon.Pokemon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The Pokémon Cobblemon's summary screen shows, and which of its pages is open, for WorkSkillsOverlay. */
@Mixin(value = Summary.class, remap = false)
public interface SummaryAccessor {
    @Accessor("selectedPokemon")
    Pokemon palworks$selectedPokemon();

    /** 0 for the Info page (then Moves, Stats, Marks). */
    @Accessor("mainScreenIndex")
    int palworks$mainScreenIndex();
}
