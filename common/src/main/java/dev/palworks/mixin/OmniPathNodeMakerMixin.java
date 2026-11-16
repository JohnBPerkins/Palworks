package dev.palworks.mixin;

import com.cobblemon.mod.common.pokemon.ai.OmniPathNodeMaker;
import dev.palworks.ai.path.HurtingBlocks;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the walks Cobblemon plans for owned Pokémon (following their owner, wandering in a
 * pasture) out of the blocks that hurt them: its pathfinder only minds a cactus or a sweet berry
 * bush underfoot, so it plans straight through the bushes and past a cactus close enough to touch
 * (see {@link HurtingBlocks}). Listed in palworks-common.mixins.json.
 */
@Mixin(OmniPathNodeMaker.class)
public abstract class OmniPathNodeMakerMixin {

    /** How far round the mob's body the walk being planned keeps clear of them. */
    @Unique
    private double palworks$clearance = HurtingBlocks.NONE;

    /** Whether it may pass closer than that where there's no other way: it starts closer. */
    @Unique
    private boolean palworks$mayPassClose;

    // Once for each walk it plans
    @Inject(method = "prepare", at = @At("TAIL"))
    private void palworks$howClearToKeep(PathNavigationRegion level, Mob mob, CallbackInfo ci) {
        palworks$clearance = HurtingBlocks.clearanceFor(mob);
        palworks$mayPassClose = palworks$clearance > 0.0 && HurtingBlocks.isWithin(level, mob, palworks$clearance);
        // Flying and swimming, its pathfinder takes nothing that's close to a block that hurts
        // for a way through, so there the walk keeps only its body out of them
        if (palworks$mayPassClose && (((OmniPathNodeMaker) (Object) this).canFly() || mob.isInWater())) {
            palworks$clearance = 0.0;
        }
    }

    // What a node is for this mob's whole body: every node its pathfinder accepts is asked here
    @Inject(method = "getPathTypeOfMob", at = @At("RETURN"), cancellable = true)
    private void palworks$notInBlocksThatHurt(
            PathfindingContext context, int x, int y, int z, Mob mob, CallbackInfoReturnable<PathType> cir) {
        // (A node it can't stand at anyway is left as it is)
        if (palworks$clearance == HurtingBlocks.NONE || mob.getPathfindingMalus(cir.getReturnValue()) < 0.0F) {
            return;
        }
        PathType hurting = HurtingBlocks.whereOwnedPokemonWouldBe(
                context, x, y, z, mob, palworks$clearance, palworks$mayPassClose);
        if (hurting != null) {
            cir.setReturnValue(hurting);
        }
    }
}
