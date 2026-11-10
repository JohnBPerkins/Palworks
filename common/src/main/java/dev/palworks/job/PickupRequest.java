package dev.palworks.job;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * A pickup job waiting to be created: for the items lying round {@code pos}, once they've had
 * time to spawn ({@code dueTime}, game time), offered first to {@code suggestedWorker} if given.
 */
record PickupRequest(BlockPos pos, @Nullable UUID suggestedWorker, long dueTime) {}
