package dev.palworks.station;

import com.cobblemon.mod.common.CobblemonRecipeTypes;
import com.cobblemon.mod.common.item.crafting.brewingstand.BrewingStandInput;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;

/**
 * A kindler lighting a brewing stand: its Ember does what blaze powder does, giving the stand
 * charges, one brew each (see {@link KindlingHeat#brewsPerEmber}). The stand then brews by itself,
 * so what comes out is what the game makes: vanilla's potions, Cobblemon's medicine, other mods'
 * recipes. Workers only light stands; what goes in and comes out is the player's.
 */
public final class BrewingStandHelper {
    // A brewing stand's slots: three bottles, then the ingredient, then blaze powder
    private static final int BOTTLE_SLOTS = 3;
    private static final int INGREDIENT_SLOT = 3;
    private static final int FUEL_SLOT = 4;

    private BrewingStandHelper() {}

    /**
     * Whether a stand wants lighting: it can brew what's in it, has no charge or blaze powder of its
     * own, and isn't brewing. The scan finds stands by this.
     */
    public static boolean needsCharge(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof BrewingStandBlockEntity stand
                && canUseCharge(level, stand)
                && stand.brewTime == 0;
    }

    /**
     * Whether a kindler's charge is doing a stand any good: as {@link #needsCharge}, but it can be
     * brewing (on a charge it gave). A level 1 kindler waiting out the brew checks this, and stops
     * once the stand is emptied or given blaze powder.
     */
    public static boolean canUseCharge(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof BrewingStandBlockEntity stand && canUseCharge(level, stand);
    }

    private static boolean canUseCharge(Level level, BrewingStandBlockEntity stand) {
        return stand.fuel <= 0 && !stand.getItem(FUEL_SLOT).is(Items.BLAZE_POWDER) && canBrew(level, stand);
    }

    /**
     * Whether the stand can brew what's in it, as it asks itself each tick: by vanilla's mixes (and,
     * on NeoForge, its brewing recipes), or by a Cobblemon brewing stand recipe. Cobblemon adds its
     * recipes where the stand's tick calls {@code isBrewable}, not inside it, so calling that would
     * miss them; this asks both, as the tick does.
     */
    private static boolean canBrew(Level level, BrewingStandBlockEntity stand) {
        ItemStack ingredient = stand.getItem(INGREDIENT_SLOT);
        if (ingredient.isEmpty()) {
            return false;
        }
        List<ItemStack> bottles = List.of(stand.getItem(0), stand.getItem(1), stand.getItem(2));
        if (bottles.stream().allMatch(ItemStack::isEmpty)) {
            return false;
        }
        return hasVanillaMix(level.potionBrewing(), ingredient, bottles)
                || level.getRecipeManager()
                        .getRecipeFor(
                                CobblemonRecipeTypes.INSTANCE.getBREWING_STAND(),
                                new BrewingStandInput(ingredient, bottles),
                                level)
                        .isPresent();
    }

    // BrewingStandBlockEntity.isBrewable, which is private
    private static boolean hasVanillaMix(PotionBrewing brewing, ItemStack ingredient, List<ItemStack> bottles) {
        if (!brewing.isIngredient(ingredient)) {
            return false;
        }
        for (int slot = 0; slot < BOTTLE_SLOTS; slot++) {
            ItemStack bottle = bottles.get(slot);
            if (!bottle.isEmpty() && brewing.hasMix(bottle, ingredient)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Light a stand: give it charges for this many brews, as blaze powder gives 20 (the fields are
     * opened by the access lists). It starts brewing on its own next tick, using one.
     *
     * @return whether it was a brewing stand
     */
    public static boolean charge(Level level, BlockPos pos, int brews) {
        if (!(level.getBlockEntity(pos) instanceof BrewingStandBlockEntity stand)) {
            return false;
        }
        stand.fuel = brews;
        stand.setChanged();
        return true;
    }
}
