package dev.palworks.animation;

import com.bedrockk.molang.runtime.MoLangRuntime;
import com.cobblemon.mod.common.api.molang.MoLangFunctions;
import com.cobblemon.mod.common.api.moves.animations.ActionEffectContext;
import com.cobblemon.mod.common.api.moves.animations.ActionEffectTimeline;
import com.cobblemon.mod.common.api.moves.animations.ActionEffects;
import com.cobblemon.mod.common.api.moves.animations.TargetsProvider;
import com.cobblemon.mod.common.api.moves.animations.UsersProvider;
import com.cobblemon.mod.common.entity.generic.GenericBedrockEntity;
import dev.palworks.Palworks;
import dev.palworks.worker.WorkerState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

/**
 * Plays {@link WorkAnimation}s on workers, server-side, and keeps track of the one each worker is
 * playing: it's cut short when the step it belongs to is (the worker is pushed off its spot, pauses
 * for a battle, drops the job), and once the step's work has happened it plays out, with the worker
 * staying put ({@link #playingOutAt}), before it walks off or starts another. Jobs don't call it
 * directly: a step that declares an animation is played and released by WorkerAI's step plumbing.
 *
 * <p>A move's action effect is driven through Cobblemon's {@code ActionEffectTimeline} the way a
 * battle does, minus the battle: {@code run()} sends the animation, particle and sound packets to
 * nearby players itself. Its particles are built around a <em>target entity</em> (Ember's flames
 * travel to it and burst on it), and work targets are blocks, so a tiny invisible, non-saving
 * marker entity stands in at the block, removed once the effect is over.
 *
 * <p>Leans on Cobblemon internals ({@code ActionEffects}, {@code ActionEffectContext}'s shape,
 * {@code GenericBedrockEntity}); guarded so a change there can only lose the visual, never break a
 * worker's tick.
 */
public final class WorkAnimator {

    /** One worker's animation in progress. */
    private static final class Playing {
        final ActionEffectContext context;
        final GenericBedrockEntity marker;
        /** The block it's aimed at, which the worker keeps facing while it plays out. */
        final BlockPos target;
        /** The game time its body animation is over. */
        final long endsAt;
        /** Its step finished (the work happened): it's left to play out, no longer cut short. */
        boolean released;

        Playing(ActionEffectContext context, GenericBedrockEntity marker, BlockPos target, long endsAt) {
            this.context = context;
            this.marker = marker;
            this.target = target;
            this.endsAt = endsAt;
        }

        /** Stop it: its target goes, so it no longer aims at, or lands on, the block. */
        void cut() {
            context.setInterrupted(true);
            marker.discard();
        }
    }

    private static final Map<UUID, Playing> PLAYING = new ConcurrentHashMap<>();

    private WorkAnimator() {}

    /** Play an animation on a worker, aimed at a block. Cuts short one of its own still playing. */
    public static void play(Mob worker, WorkAnimation animation, BlockPos target) {
        play(worker, animation, target, 1.0);
    }

    /**
     * {@link #play(Mob, WorkAnimation, BlockPos)}, by a worker at {@code pace} (see
     * dev.palworks.worker.WorkSpeed): slowed, its body animation lasts longer, so it plays out longer.
     */
    public static void play(Mob worker, WorkAnimation animation, BlockPos target, double pace) {
        if (!(worker.level() instanceof ServerLevel level)) {
            return;
        }
        interrupt(worker);

        ActionEffectTimeline timeline =
                ActionEffects.INSTANCE.getActionEffects().get(animation.moveEffect());
        if (timeline == null) {
            Palworks.LOGGER.debug("No Cobblemon action effect registered for {}", animation.moveEffect());
            return;
        }

        GenericBedrockEntity marker = new GenericBedrockEntity(level);
        try {
            marker.setSavesToWorld(false);
            marker.setNoGravity(true);
            marker.setScale(0.01f); // effectively invisible: only a position for the effect to aim at
            marker.moveTo(target.getX() + 0.5, target.getY() + animation.aimY(), target.getZ() + 0.5, 0.0f, 0.0f);
            level.addFreshEntity(marker);

            MoLangRuntime runtime = new MoLangRuntime();
            MoLangFunctions.INSTANCE.addStandardFunctions(runtime.getEnvironment().query);
            List<Object> providers = List.of(new UsersProvider(worker), new TargetsProvider(marker));
            ActionEffectContext context = new ActionEffectContext(
                    timeline,
                    new HashSet<>(),
                    providers,
                    runtime,
                    /* canBeInterrupted */ true,
                    /* interrupted */ false,
                    new ArrayList<>(),
                    level);

            Playing playing = new Playing(
                    context, marker, target.immutable(), level.getGameTime() + animation.durationTicks(pace));
            UUID id = worker.getUUID();
            PLAYING.put(id, playing);
            // Only the cleanup matters; the completion future itself isn't needed. Kept until its
            // body animation is over too, if the effect's timeline ends first
            var unused = timeline.run(context).whenComplete((result, error) -> {
                marker.discard();
                if (level.getGameTime() >= playing.endsAt) {
                    PLAYING.remove(id, playing);
                }
            });
        } catch (Exception e) { // NOPMD - Cobblemon's effect code can throw anything; it's only a visual
            marker.discard();
            Palworks.LOGGER.warn(
                    "Failed to play {} on {}: {}",
                    animation.moveEffect(),
                    worker.getName().getString(),
                    e.toString());
        }
    }

    /**
     * The step the worker's animation belongs to has finished (its work happened): let the rest of
     * the animation play out (Ember's flames burst on the furnace just after it lights).
     */
    public static void release(Mob worker) {
        Playing playing = PLAYING.get(worker.getUUID());
        if (playing != null) {
            playing.released = true;
        }
    }

    /**
     * If the worker's last animation is playing out after its step's work (the work is done, the
     * animation isn't), the block it's aimed at: the worker should stay put facing it, not walk off
     * or start another animation. Null once it's over, or if it isn't playing one.
     */
    @Nullable
    public static BlockPos playingOutAt(Mob worker) {
        UUID id = worker.getUUID();
        Playing playing = PLAYING.get(id);
        if (playing == null || !playing.released) {
            return null;
        }
        if (worker.level().getGameTime() >= playing.endsAt) {
            PLAYING.remove(id, playing);
            return null;
        }
        return playing.target;
    }

    /** Cut short the worker's animation, unless its step already finished. */
    public static void interrupt(Mob worker) {
        Playing playing = PLAYING.get(worker.getUUID());
        if (playing != null && !playing.released) {
            PLAYING.remove(worker.getUUID(), playing);
            playing.cut();
        }
    }

    /**
     * After each worker tick: an animation whose step started over, or whose job went (the step's
     * time is reset either way, without the step finishing), is cut short.
     */
    public static void update(Mob worker, WorkerState state) {
        if (state.getStepTicks() == 0) {
            interrupt(worker);
        }
    }

    /**
     * Drop everything (server stop). Otherwise an animation is forgotten once it's over (at most
     * one is kept per worker, until it plays another); the markers don't save, so none are left in
     * the world.
     */
    public static void clear() {
        PLAYING.clear();
    }
}
