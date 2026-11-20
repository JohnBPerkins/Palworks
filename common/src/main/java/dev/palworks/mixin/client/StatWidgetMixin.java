package dev.palworks.mixin.client;

import com.cobblemon.mod.common.client.gui.summary.featurerenderers.BarSummarySpeciesFeatureRenderer;
import com.cobblemon.mod.common.client.gui.summary.widgets.screens.stats.StatWidget;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.palworks.client.ClientWorkerData;
import dev.palworks.client.gui.MoodBar;
import dev.palworks.net.packets.WorkerMoodPacket;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Adds a worker's mood bar (MoodBar) to the bars the summary's Stats page shows under Other, after
 * Cobblemon's friendship and fullness. It goes into the list as the page builds it, so the page
 * arrows (added there when there are more bars than fit) count it. No bar for a Pokémon whose mood
 * the server hasn't sent: one that can't work, or any while mood is off.
 *
 * <p>The list ({@code listOf(friendship, fullness)}, the only {@code listOf} between the friendship
 * bar's creation and the field) is changed before it's stored, not the store wrapped: the field is
 * final, and a wrapped store moves the write out of the constructor, which the JVM refuses
 * ({@code IllegalAccessError} as the Stats page opens). The same in Cobblemon 1.8.0 to 1.8.4.
 */
@Mixin(value = StatWidget.class, remap = false)
public abstract class StatWidgetMixin {
    @ModifyExpressionValue(
            method = "<init>(IILcom/cobblemon/mod/common/pokemon/Pokemon;I)V",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lkotlin/collections/CollectionsKt;listOf([Ljava/lang/Object;)Ljava/util/List;"),
            slice =
                    @Slice(
                            from =
                                    @At(
                                            value = "NEW",
                                            target =
                                                    "com/cobblemon/mod/common/client/gui/summary/widgets/screens/stats/features/FriendshipFeatureRenderer"),
                            to =
                                    @At(
                                            value = "FIELD",
                                            target =
                                                    "Lcom/cobblemon/mod/common/client/gui/summary/widgets/screens/stats/StatWidget;universalFeatures:Ljava/util/List;",
                                            opcode = Opcodes.PUTFIELD)))
    private List<BarSummarySpeciesFeatureRenderer> palworks$addMoodBar(
            List<BarSummarySpeciesFeatureRenderer> features, @Local(argsOnly = true) Pokemon pokemon) {
        WorkerMoodPacket.Shown mood = ClientWorkerData.mood(pokemon.getUuid());
        if (mood == null) {
            return features;
        }
        List<BarSummarySpeciesFeatureRenderer> withMood = new ArrayList<>(features);
        withMood.add(new MoodBar(pokemon, mood));
        return withMood;
    }
}
