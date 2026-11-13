package dev.palworks.block;

import dev.palworks.hunger.TroughFood;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * A trough's feed. Food put in it (by hand, hopper or transporter) becomes feed, worth what the food
 * gives a worker (see {@link TroughFood}): it isn't kept as items, much as a composter keeps compost
 * rather than what went into it. Workers eat it a point at a time ({@link #takeBite}).
 *
 * <p>Troughs joined together (side by side, see {@link TroughBlock}) share their feed evenly: food
 * put in any of them spreads along all of them, workers can eat at any of them, and they always
 * show the same level. Each still holds its own share, so breaking one only loses that share.
 *
 * <p>For hoppers it's a container with one slot that's always empty and only takes food it has room
 * for: what goes in becomes feed straight away, and nothing can be taken out.
 */
public class TroughBlockEntity extends BlockEntity implements WorldlyContainer {
    /**
     * Below this much feed, transporters restock it from chests (see FetchFoodHandler): a few meals'
     * worth, so it's topped up before it runs out, but not after every bite.
     */
    public static final int LOW_FEED = 16;

    /** The most troughs that share feed as one: far more than anyone builds in a line. */
    static final int MAX_JOINED = 64;

    private static final int[] SLOTS = {0};

    private int feed;
    // The last food put in: what a worker eating from it is shown holding
    private ItemStack lastFood = ItemStack.EMPTY;

    public TroughBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.TROUGH_ENTITY.get(), pos, state);
    }

    /** Its share of the feed. */
    public int feed() {
        return feed;
    }

    /** The last food put in it, for a worker eating from it to hold (empty if none ever was). */
    public ItemStack lastFood() {
        return lastFood;
    }

    /** Whether there's any feed in it. */
    public boolean hasFeed() {
        return feed > 0;
    }

    /** Whether it's running low on feed, so transporters restock it. */
    public boolean isLow() {
        return feed < LOW_FEED;
    }

    // ---- The troughs joined to this one, which share its feed ----

    /** This trough and those joined to it (side by side, all the way along), nearest first. */
    public List<TroughBlockEntity> joined() {
        List<TroughBlockEntity> troughs = new ArrayList<>();
        if (level == null) {
            troughs.add(this);
            return troughs;
        }
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> next = new ArrayDeque<>();
        next.add(worldPosition);
        seen.add(worldPosition);
        while (!next.isEmpty() && troughs.size() < MAX_JOINED) {
            BlockPos pos = next.poll();
            if (!(level.getBlockEntity(pos) instanceof TroughBlockEntity trough)) {
                continue;
            }
            troughs.add(trough);
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos neighbour = pos.relative(side);
                if (level.getBlockState(neighbour).getBlock() instanceof TroughBlock && seen.add(neighbour)) {
                    next.add(neighbour);
                }
            }
        }
        return troughs;
    }

    private static int totalFeed(List<TroughBlockEntity> troughs) {
        int total = 0;
        for (TroughBlockEntity trough : troughs) {
            total += trough.feed;
        }
        return total;
    }

    /**
     * The feed in this trough and those joined to it, and how much they hold, all told.
     *
     * @param feed     the feed in them
     * @param capacity the most they hold
     */
    public record Stock(int feed, int capacity) {}

    /** How much feed this trough and those joined to it have, and hold. */
    public Stock stock() {
        List<TroughBlockEntity> troughs = joined();
        return new Stock(totalFeed(troughs), troughs.size() * TroughFeed.CAPACITY);
    }

    /** Share {@code total} feed out evenly between these troughs, and show how full they are. */
    private static void share(List<TroughBlockEntity> troughs, int total) {
        int[] shares = TroughFeed.share(total, troughs.size());
        int fill = TroughFeed.fillLevel(total, (long) troughs.size() * TroughFeed.CAPACITY);
        for (int i = 0; i < troughs.size(); i++) {
            troughs.get(i).set(shares[i], fill);
        }
    }

    private void set(int newFeed, int fill) {
        if (feed != newFeed) {
            feed = newFeed;
            setChanged();
        }
        BlockState state = getBlockState();
        if (level != null
                && !level.isClientSide
                && state.hasProperty(TroughBlock.FILL)
                && state.getValue(TroughBlock.FILL) != fill) {
            level.setBlock(worldPosition, state.setValue(TroughBlock.FILL, fill), Block.UPDATE_CLIENTS);
        }
    }

    /** Even out the feed of this trough and those joined to it (a trough was placed next to them). */
    public void shareOut() {
        List<TroughBlockEntity> troughs = joined();
        share(troughs, totalFeed(troughs));
    }

    // ---- Food in, feed out ----

    /** How much feed one of these is worth in a trough, or 0 if a trough doesn't take it. */
    private static int worth(ItemStack food) {
        return TroughFood.accepts(food) ? TroughFood.current().fullnessOf(food) : 0;
    }

    /** Whether this trough and those joined to it have room for (one of) this food. */
    public boolean canAccept(ItemStack food) {
        int worth = worth(food);
        if (worth <= 0) {
            return false;
        }
        Stock stock = stock();
        return stock.capacity() - stock.feed() >= worth;
    }

    /**
     * Put as much of this food in as there's room for, along this trough and those joined to it, as
     * feed. Returns how many of the items didn't fit (all of them, if it isn't trough food).
     * {@code food} itself isn't changed.
     */
    public int addFood(ItemStack food) {
        int worth = worth(food);
        if (worth <= 0 || food.isEmpty()) {
            return food.getCount();
        }
        List<TroughBlockEntity> troughs = joined();
        int total = totalFeed(troughs);
        int room = troughs.size() * TroughFeed.CAPACITY - total;
        int fits = Math.min(food.getCount(), room / worth);
        if (fits <= 0) {
            return food.getCount();
        }
        ItemStack shown = food.copyWithCount(1);
        for (TroughBlockEntity trough : troughs) {
            trough.lastFood = shown;
        }
        share(troughs, total + fits * worth);
        return food.getCount() - fits;
    }

    /** A worker eats a point of feed from this trough (and those joined to it). False if there's none. */
    public boolean takeBite() {
        List<TroughBlockEntity> troughs = joined();
        int total = totalFeed(troughs);
        if (total <= 0) {
            return false;
        }
        share(troughs, total - 1);
        return true;
    }

    // ---- For hoppers: one slot that's always empty, and only takes food there's room for ----

    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ItemStack.EMPTY;
    }

    /** What goes in becomes feed straight away (a hopper only puts in what {@link #canPlaceItem} allows). */
    @Override
    public void setItem(int slot, ItemStack stack) {
        addFood(stack);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return canAccept(stack);
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return SLOTS.clone();
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return canAccept(stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        return false; // there's nothing to open
    }

    @Override
    public void clearContent() {
        // Nothing kept as items
    }

    // ---- Saving ----

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        feed = Math.max(0, tag.getInt("Feed"));
        lastFood = tag.contains("LastFood")
                ? ItemStack.parseOptional(registries, tag.getCompound("LastFood"))
                : ItemStack.EMPTY;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Feed", feed);
        if (!lastFood.isEmpty()) {
            tag.put("LastFood", lastFood.save(registries));
        }
    }
}
