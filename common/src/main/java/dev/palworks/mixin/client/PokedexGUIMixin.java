package dev.palworks.mixin.client;

import com.cobblemon.mod.common.api.pokedex.filter.EntryFilter;
import com.cobblemon.mod.common.client.gui.pokedex.PokedexGUI;
import dev.palworks.client.gui.DexWorkSkillFilter;
import java.util.Collection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds the work skill filter (see DexWorkSkillFilter) to the filters Cobblemon's Pokédex screen
 * lists its entries by, beside its own category filter and search: the list it returns is a new
 * one each time, made to be added to.
 */
@Mixin(value = PokedexGUI.class, remap = false)
public abstract class PokedexGUIMixin {
    @Inject(method = "getFilters", at = @At("RETURN"))
    private void palworks$addWorkSkillFilter(CallbackInfoReturnable<Collection<EntryFilter>> cir) {
        EntryFilter filter = DexWorkSkillFilter.filterFor((PokedexGUI) (Object) this);
        if (filter != null) {
            cir.getReturnValue().add(filter);
        }
    }
}
