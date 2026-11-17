package dev.palworks.mixin.client;

import com.cobblemon.mod.common.client.gui.summary.Summary;
import com.cobblemon.mod.common.pokemon.Pokemon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The Pokémon Cobblemon's summary screen shows, for WorkSkillsOverlay. */
@Mixin(value = Summary.class, remap = false)
public interface SummaryAccessor {
    @Accessor("selectedPokemon")
    Pokemon palworks$selectedPokemon();
}
