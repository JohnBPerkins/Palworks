package dev.palworks.mixin;

import dev.palworks.station.WorkerHeldLid;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * A worker at a chest counts as having it open, as a player with its screen up does, so the chest
 * does what it does for a player: lifts its lid, makes its sounds, and gives a redstone signal if
 * it's a trapped one. Vanilla recounts the players every few ticks while a container is open and
 * closes it when none are left; the worker is added to that count until a set time, so the chest
 * closes by itself even if the worker is called away or recalled meanwhile. Listed in
 * palworks-common.mixins.json.
 */
@Mixin(ContainerOpenersCounter.class)
public abstract class ContainerOpenersCounterMixin implements WorkerHeldLid {
    @Unique
    private long palworks$workerUntil;

    @Override
    public void palworks$holdOpenUntil(long gameTime) {
        palworks$workerUntil = Math.max(palworks$workerUntil, gameTime);
    }

    /** The recount's {@code int i = list.size()}: the players with it open, plus the worker. */
    @ModifyVariable(method = "recheckOpeners", at = @At("STORE"), ordinal = 0)
    private int palworks$countWorker(int players, Level level) {
        return level.getGameTime() < palworks$workerUntil ? players + 1 : players;
    }
}
