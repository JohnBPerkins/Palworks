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

    // Cached suitabilities (calculated once, stored for performance)
    private Set<Suitability> cachedSuitabilities;
    private boolean suitabilitiesDirty;

    // Cooldowns per suitability type
    private final Map<Suitability, Long> cooldowns;

    // Path failure tracking for stuck detection
    private int pathFailCount;
    private long lastPathFailTime;

    // Progress through the current job's steps (see WorkerAI's JobHandler.steps). The step index
    // and its data are saved, so a paused or reloaded job carries on from the step it was on; ticks
    // within the step aren't, so that step restarts (with its animation) when the worker resumes.
    private int jobStep;
    private int stepTicks;
    private CompoundTag jobData;
    // Whether it has started walking to its current job (not saved). A walk that ends short of the
    // stand tile but within reach of a crop or tree still lets it work; before any walk it mustn't.
    private boolean walkedToJob;
    // Ticks spent stepping the last bit onto its stand tile after its walk ended just short (not saved)
    private int stepInTicks;

    // Work inventory - separate from Cobblemon's held item, used for job items
    // Single slot that can hold a full stack (up to 64 items)
    private ItemStack workInventory;

    public WorkerState() {
        this.enabled = true; // Owned Pokemon work by default; only an explicit disable turns this off
        this.assignedJob = null;
        this.currentReservation = null;
        this.cachedSuitabilities = null;
        this.suitabilitiesDirty = true;
        this.cooldowns = new HashMap<>();
        this.pathFailCount = 0;
        this.lastPathFailTime = 0;
        this.jobStep = 0;
        this.stepTicks = 0;
        this.jobData = new CompoundTag();
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

    /** Drop the current job, and any progress through it (capture that first if it's handed over). */
    public void clearReservation() {
        this.currentReservation = null;
        resetJobProgress();
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

    // Job progress (steps)

    /** Start a job: from its first step, or from where another worker left it (handed over). */
    public void startJob(int step, @Nullable CompoundTag data) {
        this.jobStep = Math.max(0, step);
        this.stepTicks = 0;
        this.jobData = data != null ? data.copy() : new CompoundTag();
        this.walkedToJob = false;
        this.stepInTicks = 0;
    }

    public boolean hasWalkedToJob() {
        return walkedToJob;
    }

    public void setWalkedToJob(boolean walkedToJob) {
        this.walkedToJob = walkedToJob;
    }

    public int getStepInTicks() {
        return stepInTicks;
    }

    public void addStepInTick() {
        stepInTicks++;
    }

    public void resetStepIn() {
        stepInTicks = 0;
    }

    public void resetJobProgress() {
        startJob(0, null);
    }

    /** Index of the step the job is on (0 = the first). */
    public int getJobStep() {
        return jobStep;
    }

    /** Ticks spent on the current step so far. */
    public int getStepTicks() {
        return stepTicks;
    }

    public void addStepTick() {
        stepTicks++;
    }

    /** Restart the current step (e.g. the worker left its spot): its effect hasn't happened yet. */
    public void restartStep() {
        stepTicks = 0;
    }

    public void nextStep() {
        jobStep++;
        stepTicks = 0;
    }

    /** Whether the job is past its first step, i.e. something has already been done. */
    public boolean hasJobProgress() {
        return jobStep > 0;
    }

    /** Whether the worker is partway through the job, including the current step. */
    public boolean isWorking() {
        return jobStep > 0 || stepTicks > 0;
    }

    /** Data the job's steps leave for later steps (e.g. where to replant after a chop). */
    public CompoundTag getJobData() {
        return jobData;
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

    // NBT Serialization
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();

        tag.putBoolean("enabled", enabled);
        if (assignedJob != null) {
            tag.putString("assignedJob", assignedJob.getId());
        }
        tag.putInt("jobStep", jobStep);
        tag.put("jobData", jobData.copy());

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

        // Serialize current reservation
        if (currentReservation != null) {
            CompoundTag reservationTag = new CompoundTag();
            BlockPos pos = currentReservation.getStationPos();
            // CRITICAL FIX: Store X, Y, Z separately instead of using asLong()
            // This avoids BlockPos.asLong() bugs with negative Y coordinates
            reservationTag.putInt("posX", pos.getX());
            reservationTag.putInt("posY", pos.getY());
            reservationTag.putInt("posZ", pos.getZ());
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
        // Missing key means the state predates this field: treat as enabled (the default).
        enabled = !tag.contains("enabled") || tag.getBoolean("enabled");

        if (tag.contains("assignedJob")) {
            String jobId = tag.getString("assignedJob");
            try {
                assignedJob = JobType.valueOf(jobId.toUpperCase());
            } catch (IllegalArgumentException e) {
                assignedJob = null;
            }
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

        // Deserialize current reservation
        if (tag.contains("currentReservation")) {
            CompoundTag reservationTag = tag.getCompound("currentReservation");

            BlockPos stationPos;
            // CRITICAL FIX: Read X, Y, Z separately (new format) or fall back to old asLong format
            if (reservationTag.contains("posX")) {
                // New format: separate X, Y, Z integers
                int x = reservationTag.getInt("posX");
                int y = reservationTag.getInt("posY");
                int z = reservationTag.getInt("posZ");
                stationPos = new BlockPos(x, y, z);
            } else {
                // Old format: packed long (may be corrupted)
                stationPos = BlockPos.of(reservationTag.getLong("stationPos"));
                dev.palworks.Palworks.LOGGER.warn("Loaded reservation from OLD format (asLong): {} - this may be corrupted!", stationPos);
                // Clear corrupted reservations immediately during migration
                if (Math.abs(stationPos.getY()) > 300) {
                    dev.palworks.Palworks.LOGGER.warn("Old reservation has suspicious Y coordinate {}, clearing it!", stationPos.getY());
                    currentReservation = null;
                    return; // Skip rest of deserialization
                }
            }

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

        // Job progress (missing in older saves: start of the job)
        jobStep = tag.contains("jobStep") ? Math.max(0, tag.getInt("jobStep")) : 0;
        jobData = tag.contains("jobData") ? tag.getCompound("jobData").copy() : new CompoundTag();
        stepTicks = 0;

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

    /**
     * Get a debug status string for rendering above the Pokemon.
     */
    public String getDebugStatusString() {
        if (!enabled) {
            return "§7DISABLED";
        }

        if (currentReservation != null) {
            BlockPos pos = currentReservation.getStationPos();
            return String.format("§e%s §7[%d, %d, %d]",
                currentReservation.getJobType().name(),
                pos.getX(), pos.getY(), pos.getZ());
        }

        return "§aIDLE";
    }
}
