package dev.palworks.worker;

import dev.palworks.job.JobType;
import dev.palworks.reservation.ReservationId;
import dev.palworks.suitability.Suitability;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;
import java.util.*;

/**
 * Persistent data for a Pokémon worker.
 * Stores: enabled state, assigned job, cooldowns, skill XP, base link.
 */
public class WorkerState {
    private boolean enabled;
    private JobType assignedJob;
    private ReservationId currentReservation;
    private BlockPos basePos; // "base" link

    // Cached suitabilities (calculated once, stored for performance)
    private Set<Suitability> cachedSuitabilities;
    private boolean suitabilitiesDirty;

    // Cooldowns per suitability type
    private final Map<Suitability, Long> cooldowns;

    // Path failure tracking for stuck detection
    private int pathFailCount;
    private long lastPathFailTime;

    // Work session tracking
    private long lastWorkTime;
    private int consecutiveWorkTicks;

    // Work inventory - separate from Cobblemon's held item, used for job items
    // Single slot that can hold a full stack (up to 64 items)
    private ItemStack workInventory;

    public WorkerState() {
        this.enabled = false;
        this.assignedJob = null;
        this.currentReservation = null;
        this.basePos = null;
        this.cachedSuitabilities = null;
        this.suitabilitiesDirty = true;
        this.cooldowns = new HashMap<>();
        this.pathFailCount = 0;
        this.lastPathFailTime = 0;
        this.lastWorkTime = 0;
        this.consecutiveWorkTicks = 0;
        this.workInventory = ItemStack.EMPTY;
    }

    // Enabled state
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // Job assignment
    @Nullable
    public JobType getAssignedJob() {
        return assignedJob;
    }

    public void setAssignedJob(@Nullable JobType job) {
        this.assignedJob = job;
    }

    public boolean hasJob() {
        return assignedJob != null;
    }

    // Reservation
    @Nullable
    public ReservationId getCurrentReservation() {
        return currentReservation;
    }

    public void setCurrentReservation(@Nullable ReservationId reservation) {
        this.currentReservation = reservation;
    }

    public void clearReservation() {
        this.currentReservation = null;
    }

    // Base link
    @Nullable
    public BlockPos getBasePos() {
        return basePos;
    }

    public void setBasePos(@Nullable BlockPos pos) {
        this.basePos = pos;
    }

    public boolean isLinkedToBase() {
        return basePos != null;
    }

    // Suitability caching (call this ONCE when worker is initialized)
    public void initializeSuitabilities(com.cobblemon.mod.common.pokemon.Pokemon pokemon) {
        this.cachedSuitabilities = dev.palworks.suitability.SuitabilityDetector.getSuitabilities(pokemon);
        this.suitabilitiesDirty = false;
    }

    public Set<Suitability> getSuitabilities() {
        if (cachedSuitabilities == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(cachedSuitabilities);
    }

    public boolean hasSuitability(Suitability suitability) {
        return cachedSuitabilities != null && cachedSuitabilities.contains(suitability);
    }

    public void markSuitabilitiesDirty() {
        this.suitabilitiesDirty = true;
    }

    public boolean areSuitabilitiesDirty() {
        return suitabilitiesDirty;
    }

    // Cooldowns
    public void setCooldown(Suitability suitability, long cooldownTicks) {
        cooldowns.put(suitability, System.currentTimeMillis() + (cooldownTicks * 50)); // Convert ticks to ms
    }

    public boolean isOnCooldown(Suitability suitability) {
        Long cooldownEnd = cooldowns.get(suitability);
        if (cooldownEnd == null) return false;

        if (System.currentTimeMillis() < cooldownEnd) {
            return true;
        } else {
            cooldowns.remove(suitability);
            return false;
        }
    }

    public long getRemainingCooldown(Suitability suitability) {
        Long cooldownEnd = cooldowns.get(suitability);
        if (cooldownEnd == null) return 0;

        long remaining = cooldownEnd - System.currentTimeMillis();
        return Math.max(0, remaining / 50); // Convert ms to ticks
    }

    // Path failure tracking
    public void recordPathFailure() {
        long now = System.currentTimeMillis();
        if (now - lastPathFailTime > 5000) { // Reset if > 5 seconds since last failure
            pathFailCount = 0;
        }
        pathFailCount++;
        lastPathFailTime = now;
    }

    public void resetPathFailures() {
        pathFailCount = 0;
        lastPathFailTime = 0;
    }

    public int getPathFailCount() {
        return pathFailCount;
    }

    public boolean isStuck() {
        return pathFailCount >= 3; // Stuck if 3+ path failures in 5 seconds
    }

    // Work session tracking
    public void recordWorkTick() {
        long now = System.currentTimeMillis();
        if (now - lastWorkTime > 1000) { // Reset if > 1 second gap
            consecutiveWorkTicks = 0;
        }
        consecutiveWorkTicks++;
        lastWorkTime = now;
    }

    public void resetWorkSession() {
        consecutiveWorkTicks = 0;
        lastWorkTime = 0;
    }

    public int getConsecutiveWorkTicks() {
        return consecutiveWorkTicks;
    }

    // Work Inventory Management
    public ItemStack getWorkInventory() {
        return workInventory;
    }

    public void setWorkInventory(ItemStack stack) {
        this.workInventory = stack.copy();
    }

    public boolean hasWorkInventorySpace() {
        return workInventory.isEmpty() || workInventory.getCount() < workInventory.getMaxStackSize();
    }

    public boolean hasItemInWorkInventory(net.minecraft.world.item.Item item) {
        return !workInventory.isEmpty() && workInventory.is(item);
    }

    public int getWorkInventoryCount() {
        return workInventory.getCount();
    }

    /**
     * Add items to work inventory. Returns the number of items that couldn't fit.
     */
    public int addToWorkInventory(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }

        if (workInventory.isEmpty()) {
            int toAdd = Math.min(stack.getCount(), stack.getMaxStackSize());
            workInventory = stack.copy();
            workInventory.setCount(toAdd);
            return stack.getCount() - toAdd;
        }

        // Only add if same item type
        if (ItemStack.isSameItemSameComponents(workInventory, stack)) {
            int space = workInventory.getMaxStackSize() - workInventory.getCount();
            int toAdd = Math.min(space, stack.getCount());
            workInventory.grow(toAdd);
            return stack.getCount() - toAdd;
        }

        return stack.getCount(); // Couldn't add any
    }

    /**
     * Remove items from work inventory. Returns true if successful.
     */
    public boolean removeFromWorkInventory(int count) {
        if (workInventory.isEmpty() || workInventory.getCount() < count) {
            return false;
        }

        workInventory.shrink(count);
        if (workInventory.isEmpty()) {
            workInventory = ItemStack.EMPTY;
        }
        return true;
    }

    public void clearWorkInventory() {
        workInventory = ItemStack.EMPTY;
    }

    /**
     * Sync work inventory to the Pokemon's held item for visual display.
     * This allows players to see what the worker is carrying.
     */
    public void syncWorkInventoryToHeldItem(com.cobblemon.mod.common.pokemon.Pokemon pokemon) {
        if (!workInventory.isEmpty()) {
            // Set the Pokemon's held item to match work inventory
            // Parameters: itemStack, sendUpdate, playSoundEffect
            pokemon.swapHeldItem(workInventory.copy(), false, false);
        } else {
            // Clear the held item
            pokemon.swapHeldItem(ItemStack.EMPTY, false, false);
        }
    }

    // NBT Serialization
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();

        tag.putBoolean("enabled", enabled);
        if (assignedJob != null) {
            tag.putString("assignedJob", assignedJob.getId());
        }
        if (basePos != null) {
            tag.putLong("basePos", basePos.asLong());
        }

        // Serialize cached suitabilities
        if (cachedSuitabilities != null && !cachedSuitabilities.isEmpty()) {
            CompoundTag suitsTag = new CompoundTag();
            int i = 0;
            for (Suitability suit : cachedSuitabilities) {
                suitsTag.putString("suit_" + i, suit.getId());
                i++;
            }
            tag.put("suitabilities", suitsTag);
        }

        // Serialize cooldowns
        CompoundTag cooldownsTag = new CompoundTag();
        for (Map.Entry<Suitability, Long> entry : cooldowns.entrySet()) {
            cooldownsTag.putLong(entry.getKey().getId(), entry.getValue());
        }
        tag.put("cooldowns", cooldownsTag);

        tag.putInt("pathFailCount", pathFailCount);
        tag.putLong("lastPathFailTime", lastPathFailTime);
        tag.putLong("lastWorkTime", lastWorkTime);
        tag.putInt("consecutiveWorkTicks", consecutiveWorkTicks);

        // Serialize current reservation
        if (currentReservation != null) {
            CompoundTag reservationTag = new CompoundTag();
            reservationTag.putLong("stationPos", currentReservation.getStationPos().asLong());
            reservationTag.putString("jobType", currentReservation.getJobType().getId());
            reservationTag.putLong("timestamp", currentReservation.getTimestamp());
            tag.put("currentReservation", reservationTag);
        }

        // Serialize work inventory
        if (!workInventory.isEmpty()) {
            CompoundTag inventoryTag = (CompoundTag) workInventory.save(net.minecraft.core.RegistryAccess.EMPTY);
            tag.put("workInventory", inventoryTag);
        }

        return tag;
    }

    public void deserializeNBT(CompoundTag tag) {
        enabled = tag.getBoolean("enabled");

        if (tag.contains("assignedJob")) {
            String jobId = tag.getString("assignedJob");
            try {
                assignedJob = JobType.valueOf(jobId.toUpperCase());
            } catch (IllegalArgumentException e) {
                assignedJob = null;
            }
        }

        if (tag.contains("basePos")) {
            basePos = BlockPos.of(tag.getLong("basePos"));
        }

        // Deserialize cached suitabilities
        if (tag.contains("suitabilities")) {
            cachedSuitabilities = new HashSet<>();
            CompoundTag suitsTag = tag.getCompound("suitabilities");
            for (String key : suitsTag.getAllKeys()) {
                Suitability suit = Suitability.fromId(suitsTag.getString(key));
                if (suit != null) {
                    cachedSuitabilities.add(suit);
                }
            }
            suitabilitiesDirty = false;
        }

        // Deserialize cooldowns
        cooldowns.clear();
        if (tag.contains("cooldowns")) {
            CompoundTag cooldownsTag = tag.getCompound("cooldowns");
            for (String key : cooldownsTag.getAllKeys()) {
                Suitability suitability = Suitability.fromId(key);
                if (suitability != null) {
                    cooldowns.put(suitability, cooldownsTag.getLong(key));
                }
            }
        }

        pathFailCount = tag.getInt("pathFailCount");
        lastPathFailTime = tag.getLong("lastPathFailTime");
        lastWorkTime = tag.getLong("lastWorkTime");
        consecutiveWorkTicks = tag.getInt("consecutiveWorkTicks");

        // Deserialize current reservation
        if (tag.contains("currentReservation")) {
            CompoundTag reservationTag = tag.getCompound("currentReservation");
            BlockPos stationPos = BlockPos.of(reservationTag.getLong("stationPos"));
            String jobTypeId = reservationTag.getString("jobType");
            long timestamp = reservationTag.getLong("timestamp");

            try {
                JobType jobType = JobType.valueOf(jobTypeId.toUpperCase());
                currentReservation = new ReservationId(stationPos, jobType, timestamp);
            } catch (IllegalArgumentException e) {
                currentReservation = null;
            }
        } else {
            currentReservation = null;
        }

        // Deserialize work inventory
        if (tag.contains("workInventory")) {
            CompoundTag inventoryTag = tag.getCompound("workInventory");
            workInventory = ItemStack.parse(net.minecraft.core.RegistryAccess.EMPTY, inventoryTag).orElse(ItemStack.EMPTY);
        } else {
            workInventory = ItemStack.EMPTY;
        }
    }

    public static WorkerState fromNBT(CompoundTag tag) {
        WorkerState state = new WorkerState();
        state.deserializeNBT(tag);
        return state;
    }
}
