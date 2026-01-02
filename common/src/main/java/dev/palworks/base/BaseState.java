package dev.palworks.base;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.*;

/**
 * Persistent data for a Palworks base.
 * Stores: radius, registered stations, item routing policies, linked workers.
 */
public class BaseState {
    private BlockPos basePos;
    private int workRadius;
    private final Set<BlockPos> registeredStations;
    private final Set<UUID> linkedWorkers;

    // Item routing policies (simplified for now)
    private BlockPos primaryStoragePos;
    private final Map<String, BlockPos> itemTypeRouting; // item type -> chest pos

    // Base settings
    private boolean autoAssignJobs;
    private int maxActiveWorkers;

    public BaseState() {
        this.basePos = null;
        this.workRadius = 32; // Default radius
        this.registeredStations = new HashSet<>();
        this.linkedWorkers = new HashSet<>();
        this.primaryStoragePos = null;
        this.itemTypeRouting = new HashMap<>();
        this.autoAssignJobs = true;
        this.maxActiveWorkers = 15; // Default cap
    }

    // Base position
    public BlockPos getBasePos() {
        return basePos;
    }

    public void setBasePos(BlockPos pos) {
        this.basePos = pos;
    }

    // Work radius
    public int getWorkRadius() {
        return workRadius;
    }

    public void setWorkRadius(int radius) {
        this.workRadius = Math.max(8, Math.min(128, radius)); // Clamp between 8-128
    }

    public boolean isInRange(BlockPos pos) {
        if (basePos == null) return false;
        return basePos.distSqr(pos) <= (workRadius * workRadius);
    }

    // Station registration
    public Set<BlockPos> getRegisteredStations() {
        return Collections.unmodifiableSet(registeredStations);
    }

    public void registerStation(BlockPos pos) {
        registeredStations.add(pos.immutable());
    }

    public void unregisterStation(BlockPos pos) {
        registeredStations.remove(pos);
    }

    public boolean isStationRegistered(BlockPos pos) {
        return registeredStations.contains(pos);
    }

    public int getStationCount() {
        return registeredStations.size();
    }

    // Worker linking
    public Set<UUID> getLinkedWorkers() {
        return Collections.unmodifiableSet(linkedWorkers);
    }

    public void linkWorker(UUID workerUUID) {
        linkedWorkers.add(workerUUID);
    }

    public void unlinkWorker(UUID workerUUID) {
        linkedWorkers.remove(workerUUID);
    }

    public boolean isWorkerLinked(UUID workerUUID) {
        return linkedWorkers.contains(workerUUID);
    }

    public int getLinkedWorkerCount() {
        return linkedWorkers.size();
    }

    public boolean canAcceptMoreWorkers() {
        return linkedWorkers.size() < maxActiveWorkers;
    }

    // Item routing
    public BlockPos getPrimaryStoragePos() {
        return primaryStoragePos;
    }

    public void setPrimaryStoragePos(BlockPos pos) {
        this.primaryStoragePos = pos != null ? pos.immutable() : null;
    }

    public void setItemTypeRoute(String itemType, BlockPos pos) {
        if (pos == null) {
            itemTypeRouting.remove(itemType);
        } else {
            itemTypeRouting.put(itemType, pos.immutable());
        }
    }

    public BlockPos getItemTypeRoute(String itemType) {
        return itemTypeRouting.get(itemType);
    }

    public Map<String, BlockPos> getItemRouting() {
        return Collections.unmodifiableMap(itemTypeRouting);
    }

    // Settings
    public boolean isAutoAssignJobs() {
        return autoAssignJobs;
    }

    public void setAutoAssignJobs(boolean autoAssign) {
        this.autoAssignJobs = autoAssign;
    }

    public int getMaxActiveWorkers() {
        return maxActiveWorkers;
    }

    public void setMaxActiveWorkers(int max) {
        this.maxActiveWorkers = Math.max(1, Math.min(50, max)); // Clamp between 1-50
    }

    // NBT Serialization
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();

        if (basePos != null) {
            tag.putLong("basePos", basePos.asLong());
        }

        tag.putInt("workRadius", workRadius);
        tag.putBoolean("autoAssignJobs", autoAssignJobs);
        tag.putInt("maxActiveWorkers", maxActiveWorkers);

        if (primaryStoragePos != null) {
            tag.putLong("primaryStorage", primaryStoragePos.asLong());
        }

        // Serialize registered stations
        ListTag stationsTag = new ListTag();
        for (BlockPos pos : registeredStations) {
            CompoundTag stationTag = new CompoundTag();
            stationTag.putLong("pos", pos.asLong());
            stationsTag.add(stationTag);
        }
        tag.put("stations", stationsTag);

        // Serialize linked workers
        ListTag workersTag = new ListTag();
        for (UUID uuid : linkedWorkers) {
            CompoundTag workerTag = new CompoundTag();
            workerTag.putUUID("uuid", uuid);
            workersTag.add(workerTag);
        }
        tag.put("workers", workersTag);

        // Serialize item routing
        CompoundTag routingTag = new CompoundTag();
        for (Map.Entry<String, BlockPos> entry : itemTypeRouting.entrySet()) {
            routingTag.putLong(entry.getKey(), entry.getValue().asLong());
        }
        tag.put("routing", routingTag);

        return tag;
    }

    public void deserializeNBT(CompoundTag tag) {
        if (tag.contains("basePos")) {
            basePos = BlockPos.of(tag.getLong("basePos"));
        }

        workRadius = tag.getInt("workRadius");
        autoAssignJobs = tag.getBoolean("autoAssignJobs");
        maxActiveWorkers = tag.getInt("maxActiveWorkers");

        if (tag.contains("primaryStorage")) {
            primaryStoragePos = BlockPos.of(tag.getLong("primaryStorage"));
        }

        // Deserialize registered stations
        registeredStations.clear();
        ListTag stationsTag = tag.getList("stations", Tag.TAG_COMPOUND);
        for (int i = 0; i < stationsTag.size(); i++) {
            CompoundTag stationTag = stationsTag.getCompound(i);
            registeredStations.add(BlockPos.of(stationTag.getLong("pos")));
        }

        // Deserialize linked workers
        linkedWorkers.clear();
        ListTag workersTag = tag.getList("workers", Tag.TAG_COMPOUND);
        for (int i = 0; i < workersTag.size(); i++) {
            CompoundTag workerTag = workersTag.getCompound(i);
            linkedWorkers.add(workerTag.getUUID("uuid"));
        }

        // Deserialize item routing
        itemTypeRouting.clear();
        CompoundTag routingTag = tag.getCompound("routing");
        for (String key : routingTag.getAllKeys()) {
            itemTypeRouting.put(key, BlockPos.of(routingTag.getLong(key)));
        }
    }

    public static BaseState fromNBT(CompoundTag tag) {
        BaseState state = new BaseState();
        state.deserializeNBT(tag);
        return state;
    }
}
