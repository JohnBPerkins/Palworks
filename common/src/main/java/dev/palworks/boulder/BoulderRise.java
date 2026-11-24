package dev.palworks.boulder;

import dev.architectury.event.EventResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * A boulder rising out of the ground as an Unearthing worker raises it: its blocks, as block
 * display entities (vanilla's, so clients need nothing of ours), start below the ground under where
 * they'll stand and slide up into place over {@link #RISE_TICKS}, the ground under them hiding
 * what's still below it, with the ground's dust thrown up round the edge and a low grinding. Then
 * they're swapped for the real blocks ({@link #finish}). The displays are never saved: one loaded
 * back from disk (the server stopped mid-rise) is dropped as it's added ({@link #onEntityAdded}),
 * and one whose worker gave up (or whose rise went on far too long) is taken away.
 */
public final class BoulderRise {
    /** How long the rise takes, in ticks: a display's movement is smoothed over at most 59. */
    public static final int RISE_TICKS = 40;

    /** The tag on a rising boulder's displays. */
    public static final String TAG = "palworks_rising_boulder";

    // How long after they're added the displays are moved up: a client smooths the move only once
    // it has the display where it started
    private static final int MOVE_AFTER_TICKS = 2;
    // A rise its worker never finished (it went, or its job did) is taken away after this
    private static final int GIVE_UP_TICKS = RISE_TICKS * 4;
    private static final int DUST_EVERY_TICKS = 3;
    private static final int GRIND_EVERY_TICKS = 10;

    private static final Map<UUID, Rise> RISING = new HashMap<>();

    // How long a finished rise's displays stay over the real blocks that took their place: a client
    // draws a changed block only once it has rebuilt that part of the world, a frame or more later,
    // and with the displays gone first the boulder blinked out meanwhile
    public static final int LINGER_TICKS = 5;

    private record Lingering(ServerLevel level, List<Entity> displays, long until) {}

    private static final List<Lingering> LINGERING = new ArrayList<>();

    private BoulderRise() {}

    private static final class Rise {
        final ServerLevel level;
        final BlockPos corner;
        final BoulderPlan plan;
        final long started;
        final List<Entity> displays = new ArrayList<>();

        Rise(ServerLevel level, BlockPos corner, BoulderPlan plan, long started) {
            this.level = level;
            this.corner = corner;
            this.plan = plan;
            this.started = started;
        }
    }

    /**
     * Start {@code plan} rising for {@code worker}, its footprint's corner at {@code corner} (at the
     * height of its lowest blocks, just above the ground). One the worker already had rising goes.
     */
    public static void start(ServerLevel level, UUID worker, BlockPos corner, BoulderPlan plan) {
        cancel(worker);
        Rise rise = new Rise(level, corner, plan, level.getGameTime());
        RISING.put(worker, rise);
        int depth = plan.shape().height() + 1;
        plan.blocksAt(corner).forEach((pos, state) -> {
            Entity display = display(level, pos, state, depth);
            if (display != null) {
                rise.displays.add(display);
                level.addFreshEntity(display);
            }
        });
        level.playSound(null, center(rise), SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 1.5f, 0.5f);
    }

    /** Whether this worker has a boulder rising. */
    public static boolean isRising(UUID worker) {
        return RISING.containsKey(worker);
    }

    /**
     * The worker's boulder is up: the real blocks take the displays' place, if the spots are still
     * empty, and the displays go {@link #LINGER_TICKS} later; anything standing there is lifted on
     * top. The blocks placed: none if it had none rising, or something was put in the way meanwhile.
     */
    public static Map<BlockPos, BlockState> finish(UUID worker) {
        Rise rise = RISING.remove(worker);
        if (rise == null) {
            return Map.of();
        }
        Map<BlockPos, BlockState> blocks = rise.plan.blocksAt(rise.corner);
        for (BlockPos pos : blocks.keySet()) {
            if (!rise.level.getBlockState(pos).isAir()) {
                discard(rise);
                return Map.of();
            }
        }
        liftOut(rise);
        Map<BlockPos, BlockState> placed = Boulders.get(rise.level).place(rise.level, rise.corner, rise.plan, worker);
        LINGERING.add(new Lingering(rise.level, List.copyOf(rise.displays), rise.level.getGameTime() + LINGER_TICKS));
        rise.displays.clear();
        return placed;
    }

    /** Stop the worker's rise, if it has one: its displays go, nothing's placed. */
    public static void cancel(UUID worker) {
        Rise rise = RISING.remove(worker);
        if (rise != null) {
            discard(rise);
        }
    }

    /** Each tick, for each level: dust and grinding while boulders rise, and their displays moved up. */
    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        LINGERING.removeIf(lingering -> {
            if (!lingering.level().equals(level) || now < lingering.until()) {
                return false;
            }
            lingering.displays().forEach(Entity::discard);
            return true;
        });
        RISING.entrySet().removeIf(entry -> {
            Rise rise = entry.getValue();
            if (!rise.level.equals(level)) {
                return false;
            }
            long age = now - rise.started;
            if (age > GIVE_UP_TICKS) {
                discard(rise);
                return true;
            }
            if (age == MOVE_AFTER_TICKS) {
                int depth = rise.plan.shape().height() + 1;
                for (Entity display : rise.displays) {
                    display.teleportTo(display.getX(), display.getY() + depth, display.getZ());
                }
            }
            if (age <= RISE_TICKS + MOVE_AFTER_TICKS && age % DUST_EVERY_TICKS == 0) {
                dust(rise);
            }
            if (age <= RISE_TICKS && age % GRIND_EVERY_TICKS == 0) {
                level.playSound(null, center(rise), SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1.0f, 0.5f);
            }
            return false;
        });
    }

    /** All rises stopped (the server is stopping): their displays go. */
    public static void clearAll() {
        RISING.values().forEach(BoulderRise::discard);
        RISING.clear();
        LINGERING.forEach(lingering -> lingering.displays().forEach(Entity::discard));
        LINGERING.clear();
    }

    /**
     * A rising boulder's display being added that isn't one of a rise going on now (it's back from
     * disk, the server having stopped mid-rise): it isn't added.
     */
    public static EventResult onEntityAdded(Entity entity, Level level) {
        if (!(entity instanceof Display.BlockDisplay) || !entity.getTags().contains(TAG)) {
            return EventResult.pass();
        }
        for (Rise rise : RISING.values()) {
            if (rise.displays.contains(entity)) {
                return EventResult.pass();
            }
        }
        return EventResult.interruptFalse();
    }

    /**
     * A display of {@code state}, {@code depth} blocks below {@code pos} (where it ends up), moving
     * smoothly over the rise, lit as the spot it ends up in is (not the dark underground it starts in).
     */
    @Nullable
    private static Entity display(ServerLevel level, BlockPos pos, BlockState state, int depth) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:block_display");
        tag.put("block_state", NbtUtils.writeBlockState(state));
        tag.putInt("teleport_duration", RISE_TICKS);
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", level.getBrightness(LightLayer.BLOCK, pos));
        brightness.putInt("sky", level.getBrightness(LightLayer.SKY, pos));
        tag.put("brightness", brightness);
        // Its size for culling: with none (the default), a client could skip drawing it whenever its
        // corner alone was out of view
        tag.putFloat("width", 1.0f);
        tag.putFloat("height", 1.0f);
        Entity display = EntityType.loadEntityRecursive(tag, level, entity -> {
            entity.moveTo(pos.getX(), pos.getY() - depth, pos.getZ(), 0, 0);
            return entity;
        });
        if (display != null) {
            display.addTag(TAG);
        }
        return display;
    }

    private static void discard(Rise rise) {
        rise.displays.forEach(Entity::discard);
        rise.displays.clear();
    }

    /**
     * The ground's dust thrown up right round what's rising: round each piece of it, in the gaps
     * between a cluster's rocks and along a split rock's crack too.
     */
    private static void dust(Rise rise) {
        for (BoulderShape.Cell beside : rise.plan.shape().outline()) {
            BlockPos ground = rise.corner.offset(beside.x(), -1, beside.z());
            BlockState state = rise.level.getBlockState(ground);
            if (!state.isAir()) {
                rise.level.sendParticles(
                        new BlockParticleOption(ParticleTypes.BLOCK, state),
                        ground.getX() + 0.5,
                        ground.getY() + 1.05,
                        ground.getZ() + 0.5,
                        2,
                        0.3,
                        0.1,
                        0.3,
                        0.1);
            }
        }
    }

    /** Anything standing where the boulder goes is put on top of it, not inside it. */
    private static void liftOut(Rise rise) {
        BoulderShape shape = rise.plan.shape();
        AABB area = new AABB(
                rise.corner.getX(),
                rise.corner.getY(),
                rise.corner.getZ(),
                rise.corner.getX() + shape.width(),
                rise.corner.getY() + shape.height(),
                rise.corner.getZ() + shape.depth());
        for (Entity entity : rise.level.getEntities((Entity) null, area, entity -> !(entity instanceof Display))) {
            int x = (int) Math.floor(entity.getX()) - rise.corner.getX();
            int z = (int) Math.floor(entity.getZ()) - rise.corner.getZ();
            int top = shape.heightAt(x, z);
            if (top > 0) {
                entity.teleportTo(entity.getX(), rise.corner.getY() + top, entity.getZ());
            }
        }
    }

    private static BlockPos center(Rise rise) {
        BoulderShape shape = rise.plan.shape();
        return rise.corner.offset(shape.width() / 2, 0, shape.depth() / 2);
    }
}
