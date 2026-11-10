package dev.palworks.station;

import dev.palworks.Palworks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Helper for interacting with furnaces, blast furnaces, and smokers.
 * Handles detection of work opportunities and ignition.
 */
public class FurnaceHelper {
    private static final boolean DEBUG = Palworks.DEBUG;

    /**
     * Check if a furnace has items in the input slot and needs fuel.
     * Returns true if there's work to be done (items to smelt but not currently burning).
     */
    public static boolean needsFuel(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
            return false;
        }

        // Check if there's an item in the input slot
        boolean hasInput = !furnace.getItem(0).isEmpty();
        if (!hasInput) {
            return false;
        }

        // Only worth igniting if the input can actually be smelted in this furnace type.
        if (!hasSmeltableInput(level, furnace)) {
            return false;
        }

        // If the furnace already has its own fuel, leave it alone - a worker's heat is meant for
        // furnaces that LACK fuel. (An already-lit furnace is skipped by the LIT check below.)
        if (hasOwnFuel(furnace)) {
            return false;
        }

        // Check if not currently lit
        BlockState state = level.getBlockState(pos);
        boolean isLit = false;
        if (state.hasProperty(BlockStateProperties.LIT)) {
            isLit = state.getValue(BlockStateProperties.LIT);
        }

        boolean needsFuel = !isLit; // hasInput is known true: returned early above

        if (DEBUG && needsFuel) {
            Palworks.LOGGER.debug("Furnace at {} needs fuel (hasInput: {}, isLit: {})", pos, hasInput, isLit);
        }

        return needsFuel;
    }

    /**
     * Whether a worker's heat is doing a furnace any good: it has something it can smelt, no fuel
     * of its own, and isn't burning for longer than a worker's heat keeps it going (it's unlit, lit
     * by a worker, or its own last fuel is about to burn out). Unlike {@link #needsFuel} it can be
     * lit: a worker keeping it lit checks this while it does, and stops once it's emptied (or given
     * fuel), instead of seeing out a smelt with nothing in it.
     */
    public static boolean canUseHeat(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace
                && hasSmeltableInput(level, furnace)
                && !hasOwnFuel(furnace)
                && furnace.litTime <= getBurnDuration();
    }

    /**
     * Check that the furnace's input can actually be smelted in this furnace type, so workers
     * don't waste heat igniting a furnace full of non-smeltable junk. Picks the recipe type from
     * the block entity (smelting / blasting / smoking).
     */
    private static boolean hasSmeltableInput(Level level, AbstractFurnaceBlockEntity furnace) {
        ItemStack input = furnace.getItem(0);
        if (input.isEmpty()) {
            return false;
        }

        SingleRecipeInput recipeInput = new SingleRecipeInput(input);
        RecipeManager recipes = level.getRecipeManager();

        if (furnace instanceof BlastFurnaceBlockEntity) {
            return recipes.getRecipeFor(RecipeType.BLASTING, recipeInput, level).isPresent();
        } else if (furnace instanceof SmokerBlockEntity) {
            return recipes.getRecipeFor(RecipeType.SMOKING, recipeInput, level).isPresent();
        }
        return recipes.getRecipeFor(RecipeType.SMELTING, recipeInput, level).isPresent();
    }

    /**
     * Whether the furnace's fuel slot (slot 1) holds a valid fuel item, i.e. the furnace can
     * burn on its own without a worker's heat.
     */
    private static boolean hasOwnFuel(AbstractFurnaceBlockEntity furnace) {
        ItemStack fuel = furnace.getItem(1);
        return !fuel.isEmpty() && AbstractFurnaceBlockEntity.getFuel().containsKey(fuel.getItem());
    }

    /**
     * Ignite a furnace by setting its burn time.
     * This simulates a Kindling worker providing heat without consuming fuel items.
     *
     * @param level The world
     * @param pos The furnace position
     * @param burnDuration How long the furnace should burn (in ticks)
     * @return true if successfully ignited
     */
    public static boolean igniteFurnace(Level level, BlockPos pos, int burnDuration) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
            return false;
        }

        // Check if there's something to smelt
        if (furnace.getItem(0).isEmpty()) {
            return false;
        }

        // Set the burn time directly. The litTime/litDuration fields are exposed via the
        // palworks access widener (see palworks.accesswidener) - no reflection needed.
        furnace.litTime = burnDuration;
        furnace.litDuration = burnDuration;

        // Update the lit state
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(BlockStateProperties.LIT) && !state.getValue(BlockStateProperties.LIT)) {
            level.setBlock(pos, state.setValue(BlockStateProperties.LIT, true), 3);
        }

        furnace.setChanged();
        return true;
    }

    /**
     * Get the burn duration for a Kindling worker.
     * Slightly more than 200 ticks to ensure complete smelting.
     *
     * @return Burn duration in ticks
     */
    public static int getBurnDuration() {
        // 201 ticks to ensure the item completes (200 ticks + small buffer)
        return 201;
    }

    /**
     * Check if a furnace is currently lit and burning.
     */
    public static boolean isLit(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(BlockStateProperties.LIT)) {
            return state.getValue(BlockStateProperties.LIT);
        }

        return false;
    }

    /**
     * Get the remaining burn time for a furnace.
     * Reads the access-widened litTime field directly.
     */
    public static int getRemainingBurnTime(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof AbstractFurnaceBlockEntity furnace) {
            return furnace.litTime;
        }
        return 0;
    }

    /**
     * Check if a furnace has finished smelting (output slot is ready).
     */
    public static boolean hasOutput(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof AbstractFurnaceBlockEntity furnace) {
            return !furnace.getItem(2).isEmpty(); // Slot 2 is output
        }
        return false;
    }

    /**
     * Check if a furnace can accept more work.
     * Returns true if input has items and output isn't full.
     */
    public static boolean canWork(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
            return false;
        }

        // Has input
        if (furnace.getItem(0).isEmpty()) {
            return false;
        }

        // Output not full (vanilla logic allows 64 max)
        if (!furnace.getItem(2).isEmpty() && furnace.getItem(2).getCount() >= 64) {
            return false;
        }

        return true;
    }

    /**
     * Check if a furnace has MORE items to smelt after the current one.
     * Returns true if input slot has 2+ items (meaning there's a queue).
     */
    public static boolean hasQueuedItems(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
            return false;
        }

        // Check if input slot has MORE than 1 item (meaning items are queued)
        return furnace.getItem(0).getCount() > 1;
    }
}
