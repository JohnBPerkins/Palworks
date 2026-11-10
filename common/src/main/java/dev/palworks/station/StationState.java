package dev.palworks.station;

import dev.palworks.job.JobType;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

/**
 * Persistent state for a work station.
 * Stores: job queues, progress, reserved worker UUID.
 */
public class StationState {
    private final BlockPos pos;
    private final JobType jobType;

    // Job queue: list of pending jobs
    private final List<JobEntry> jobQueue;

    // Current job being processed
    private JobEntry currentJob;

    // Progress on current job (0-100)
    private float progress;

    // Worker currently assigned to this station
    private UUID reservedWorkerUUID;

    // Last tick time for progress updates
    private long lastTickTime;

    // Station enabled state
    private boolean enabled;

    // Maximum queue size
    private static final int MAX_QUEUE_SIZE = 16;

    public StationState(BlockPos pos, JobType jobType) {
        this.pos = pos;
        this.jobType = jobType;
        this.jobQueue = new ArrayList<>();
        this.currentJob = null;
        this.progress = 0.0f;
        this.reservedWorkerUUID = null;
        this.lastTickTime = System.currentTimeMillis();
        this.enabled = true;
    }

    // Basic getters
    public BlockPos getPos() {
        return pos;
    }

    public JobType getJobType() {
        return jobType;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // Queue management
    public boolean addJob(JobEntry job) {
        if (jobQueue.size() >= MAX_QUEUE_SIZE) {
            return false;
        }
        return jobQueue.add(job);
    }

    public boolean hasQueuedJobs() {
        return !jobQueue.isEmpty();
    }

    public int getQueueSize() {
        return jobQueue.size();
    }

    @Nullable
    public JobEntry peekNextJob() {
        return jobQueue.isEmpty() ? null : jobQueue.get(0);
    }

    @Nullable
    public JobEntry pollNextJob() {
        return jobQueue.isEmpty() ? null : jobQueue.remove(0);
    }

    public void clearQueue() {
        jobQueue.clear();
    }

    public List<JobEntry> getJobQueue() {
        return Collections.unmodifiableList(jobQueue);
    }

    // Current job management
    @Nullable
    public JobEntry getCurrentJob() {
        return currentJob;
    }

    public void setCurrentJob(@Nullable JobEntry job) {
        this.currentJob = job;
        this.progress = 0.0f;
    }

    public boolean hasCurrentJob() {
        return currentJob != null;
    }

    public void completeCurrentJob() {
        this.currentJob = null;
        this.progress = 0.0f;
        this.reservedWorkerUUID = null;
    }

    // Progress tracking
    public float getProgress() {
        return progress;
    }

    public void setProgress(float progress) {
        this.progress = Math.max(0.0f, Math.min(100.0f, progress));
    }

    public void addProgress(float amount) {
        setProgress(progress + amount);
    }

    public boolean isComplete() {
        return progress >= 100.0f;
    }

    // Worker reservation
    @Nullable
    public UUID getReservedWorkerUUID() {
        return reservedWorkerUUID;
    }

    public void reserveFor(UUID workerUUID) {
        this.reservedWorkerUUID = workerUUID;
    }

    public void clearReservation() {
        this.reservedWorkerUUID = null;
    }

    public boolean isReserved() {
        return reservedWorkerUUID != null;
    }

    public boolean isReservedBy(UUID workerUUID) {
        return reservedWorkerUUID != null && reservedWorkerUUID.equals(workerUUID);
    }

    // Tick tracking
    public long getLastTickTime() {
        return lastTickTime;
    }

    public void updateTickTime() {
        this.lastTickTime = System.currentTimeMillis();
    }

    // NBT Serialization
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();

        tag.putLong("pos", pos.asLong());
        tag.putString("jobType", jobType.getId());
        tag.putBoolean("enabled", enabled);
        tag.putFloat("progress", progress);

        if (reservedWorkerUUID != null) {
            tag.putUUID("reservedWorker", reservedWorkerUUID);
        }

        if (currentJob != null) {
            tag.put("currentJob", currentJob.serializeNBT());
        }

        // Serialize queue
        ListTag queueTag = new ListTag();
        for (JobEntry job : jobQueue) {
            queueTag.add(job.serializeNBT());
        }
        tag.put("jobQueue", queueTag);

        tag.putLong("lastTickTime", lastTickTime);

        return tag;
    }

    public void deserializeNBT(CompoundTag tag) {
        enabled = tag.getBoolean("enabled");
        progress = tag.getFloat("progress");

        if (tag.hasUUID("reservedWorker")) {
            reservedWorkerUUID = tag.getUUID("reservedWorker");
        }

        if (tag.contains("currentJob")) {
            currentJob = JobEntry.fromNBT(tag.getCompound("currentJob"));
        }

        // Deserialize queue
        jobQueue.clear();
        if (tag.contains("jobQueue")) {
            ListTag queueTag = tag.getList("jobQueue", Tag.TAG_COMPOUND);
            for (int i = 0; i < queueTag.size(); i++) {
                jobQueue.add(JobEntry.fromNBT(queueTag.getCompound(i)));
            }
        }

        lastTickTime = tag.getLong("lastTickTime");
    }

    public static StationState fromNBT(CompoundTag tag) {
        BlockPos pos = BlockPos.of(tag.getLong("pos"));
        JobType jobType;
        try {
            jobType = JobType.valueOf(tag.getString("jobType").toUpperCase());
        } catch (IllegalArgumentException e) {
            jobType = JobType.CRAFT_ITEM; // Default fallback
        }

        StationState state = new StationState(pos, jobType);
        state.deserializeNBT(tag);
        return state;
    }

    /**
     * Represents a single job in the queue
     */
    public static class JobEntry {
        private final UUID jobId;
        private final Map<String, Object> parameters;
        private final long creationTime;

        public JobEntry(UUID jobId, Map<String, Object> parameters) {
            this.jobId = jobId;
            this.parameters = new HashMap<>(parameters);
            this.creationTime = System.currentTimeMillis();
        }

        public JobEntry(Map<String, Object> parameters) {
            this(UUID.randomUUID(), parameters);
        }

        public UUID getJobId() {
            return jobId;
        }

        public Map<String, Object> getParameters() {
            return Collections.unmodifiableMap(parameters);
        }

        @Nullable
        public Object getParameter(String key) {
            return parameters.get(key);
        }

        public long getCreationTime() {
            return creationTime;
        }

        public long getAge() {
            return System.currentTimeMillis() - creationTime;
        }

        public CompoundTag serializeNBT() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("jobId", jobId);
            tag.putLong("creationTime", creationTime);

            // Serialize parameters (simplified - only supports strings for now)
            CompoundTag paramsTag = new CompoundTag();
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                if (entry.getValue() instanceof String) {
                    paramsTag.putString(entry.getKey(), (String) entry.getValue());
                } else if (entry.getValue() instanceof Integer) {
                    paramsTag.putInt(entry.getKey(), (Integer) entry.getValue());
                } else if (entry.getValue() instanceof Boolean) {
                    paramsTag.putBoolean(entry.getKey(), (Boolean) entry.getValue());
                }
            }
            tag.put("parameters", paramsTag);

            return tag;
        }

        public static JobEntry fromNBT(CompoundTag tag) {
            UUID jobId = tag.getUUID("jobId");
            Map<String, Object> parameters = new HashMap<>();

            if (tag.contains("parameters")) {
                CompoundTag paramsTag = tag.getCompound("parameters");
                for (String key : paramsTag.getAllKeys()) {
                    Tag valueTag = paramsTag.get(key);
                    if (valueTag != null) {
                        switch (valueTag.getId()) {
                            case Tag.TAG_STRING -> parameters.put(key, paramsTag.getString(key));
                            case Tag.TAG_INT -> parameters.put(key, paramsTag.getInt(key));
                            case Tag.TAG_BYTE -> parameters.put(key, paramsTag.getBoolean(key));
                        }
                    }
                }
            }

            JobEntry entry = new JobEntry(jobId, parameters);
            return entry;
        }
    }
}
