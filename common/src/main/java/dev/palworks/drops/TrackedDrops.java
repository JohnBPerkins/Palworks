package dev.palworks.drops;

import dev.architectury.event.EventResult;
import dev.palworks.job.JobManager;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * The items transporters pick up: what workers' work drops (a harvest, a chopped tree and its
 * decaying leaves) and what players drop (thrown, or from blocks they mine). Nothing else on the
 * ground is touched: a mob farm's drops, a dispenser's, a water-harvested farm's, a player's death
 * drops. They're marked with an entity tag ({@link #TAG}) as they spawn, which is saved with the item,
 * so it still counts after its chunk unloads or the server restarts; every marked item that comes
 * into the world (spawned, or loaded with its chunk) gets a pickup job (see JobManager).
 */
public final class TrackedDrops {
    /** The entity tag on items to pick up. */
    public static final String TAG = "palworks_drop";
    // The entity tag on seeds handed to a planter, followed by its UUID
    private static final String HANDED_TO = "palworks_seeds_for:";

    // Set while a worker or player breaks blocks (or tracked leaves decay), on the server thread:
    // items spawned meanwhile are theirs. A depth, so nested breaks (a player breaking a block
    // whose drops come from another hook) don't clear it early.
    private static int depth;

    @Nullable
    private static UUID breaker;

    private static boolean decaying;

    private TrackedDrops() {}

    /** Whether this item is one to pick up. */
    public static boolean isTracked(ItemEntity item) {
        return item.getTags().contains(TAG);
    }

    /**
     * Mark seeds on the ground as handed to a planter (by a transporter, or thrown it by a
     * player): only it picks them up, and transporters leave them be (see JobChecks), until
     * {@link #stopHandingOver}.
     */
    public static void handOverTo(ItemEntity item, UUID planter) {
        item.addTag(HANDED_TO + planter);
    }

    /** The planter seeds on the ground were handed to, or null if they weren't. */
    @Nullable
    public static UUID handedTo(ItemEntity item) {
        for (String tag : item.getTags()) {
            if (tag.startsWith(HANDED_TO)) {
                try {
                    return UUID.fromString(tag.substring(HANDED_TO.length()));
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Seeds handed to a planter that it never took (it was put away, say): they're an ordinary
     * item to pick up again, for a transporter to put away.
     */
    public static void stopHandingOver(ItemEntity item) {
        item.getTags().removeIf(tag -> tag.startsWith(HANDED_TO));
        item.addTag(TAG);
    }

    /** Whether a player threw this item (see {@link #thrownByPlayer}). */
    public static boolean isThrownByPlayer(ItemEntity item) {
        return thrownByPlayer(item);
    }

    /**
     * Run some work that breaks blocks, with what it drops marked to pick up. {@code worker}, if
     * given, is offered the pickup first (it's the one that made the drops).
     */
    public static boolean breaking(@Nullable UUID worker, BooleanSupplier work) {
        begin(worker, false);
        try {
            return work.getAsBoolean();
        } finally {
            end();
        }
    }

    /** {@link #breaking(UUID, BooleanSupplier)} for work with no result. */
    public static void breaking(@Nullable UUID worker, Runnable work) {
        breaking(worker, () -> {
            work.run();
            return true;
        });
    }

    /**
     * Start marking what spawns as to pick up, until {@link #end}. For mixins, which can't wrap a
     * call (a player breaking a block, leaves decaying); {@code decay} picks them up in batches, as
     * leaves drop a few at a time for minutes.
     */
    public static void begin(@Nullable UUID worker, boolean decay) {
        if (depth == 0) {
            breaker = worker;
            decaying = decay;
        }
        depth++;
    }

    /** Stop marking what spawns (see {@link #begin}). */
    public static void end() {
        if (depth == 0) {
            return;
        }
        depth--;
        if (depth == 0) {
            breaker = null;
            decaying = false;
        }
    }

    /**
     * After each server tick: nothing breaks across ticks, so a begin whose end never came (the
     * break threw) mustn't keep marking everything that spawns from then on.
     */
    public static void resetAfterTick() {
        depth = 0;
        breaker = null;
        decaying = false;
    }

    /**
     * Every entity that comes into a server level (Architectury's EntityEvent.ADD: spawned, or
     * loaded with its chunk). A new item is marked if a worker or player dropped it; a marked one
     * (new, or back from disk) gets a pickup job.
     */
    public static EventResult onEntityAdded(Entity entity, Level level) {
        if (!(entity instanceof ItemEntity item) || !(level instanceof ServerLevel serverLevel)) {
            return EventResult.pass();
        }
        // Seeds left for a planter, back from disk (their chunk unloaded, or the server stopped):
        // who they were for is forgotten by now (see SeedRequests), so they're an ordinary pickup
        if (handedTo(item) != null) {
            stopHandingOver(item);
        }
        if (!isTracked(item) && (depth > 0 || thrownByPlayer(item))) {
            item.addTag(TAG);
        }
        if (isTracked(item)) {
            JobManager.onTrackedItemAdded(serverLevel, item, depth > 0 ? breaker : null, depth > 0 && decaying);
        }
        return EventResult.pass();
    }

    // A player's thrown items (Q, or out of their inventory) have them as thrower; what they drop
    // as they die doesn't (Player.dropAll), and stays where it fell for them to come back for.
    private static boolean thrownByPlayer(ItemEntity item) {
        return item.getOwner() instanceof Player;
    }
}
