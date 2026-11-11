package dev.palworks.mixin.client;

import com.cobblemon.mod.common.client.gui.pc.PCGUI;
import com.cobblemon.mod.common.pokemon.Pokemon;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The Pokémon Cobblemon's PC screen is showing on its left (the one hovered or picked), for WorkSkillsOverlay. */
@Mixin(value = PCGUI.class, remap = false)
public interface PCGUIAccessor {
    @Accessor("previewPokemon")
    @Nullable
    Pokemon palworks$previewPokemon();
}
