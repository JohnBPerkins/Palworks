package dev.palworks.client.gui;

import com.cobblemon.mod.common.client.gui.summary.featurerenderers.BarSummarySpeciesFeatureRenderer;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.net.packets.WorkerMoodPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * A worker's mood (0 to 100) as a bar on the summary's Stats page, under Other, beside Cobblemon's
 * friendship and fullness bars (added by StatWidgetMixin). Cobblemon's own bar, its generic overlay,
 * coloured as the mood's face in the portrait corner is (see WorkSkillsOverlay.face). The value is
 * the one sent when the page opened, as fullness's is.
 */
public final class MoodBar extends BarSummarySpeciesFeatureRenderer {
    private static final ResourceLocation UNDERLAY =
            ResourceLocation.fromNamespaceAndPath("cobblemon", "textures/gui/summary/summary_stats_other_bar.png");
    private static final ResourceLocation OVERLAY = ResourceLocation.fromNamespaceAndPath(
            "cobblemon", "textures/gui/summary/summary_stats_generic_overlay.png");

    // Fullness's bar's three colours (Cobblemon's FullnessFeatureRenderer): green, yellow, red
    private static final Vec3 HAPPY = new Vec3(120 / 255.0, 200 / 255.0, 80 / 255.0);
    private static final Vec3 CONTENT = new Vec3(240 / 255.0, 200 / 255.0, 65 / 255.0);
    private static final Vec3 UNHAPPY = new Vec3(230 / 255.0, 80 / 255.0, 65 / 255.0);

    public MoodBar(Pokemon pokemon, WorkerMoodPacket.Shown mood) {
        super(
                Palworks.MOD_ID + "_mood",
                Component.translatable("palworks.ui.mood.bar"),
                UNDERLAY,
                OVERLAY,
                pokemon,
                0,
                100,
                mood.mood(),
                colour(mood));
    }

    private static Vec3 colour(WorkerMoodPacket.Shown mood) {
        return switch (WorkSkillsOverlay.face(mood)) {
            case "happy" -> HAPPY;
            case "content" -> CONTENT;
            default -> UNHAPPY; // unhappy, or on strike
        };
    }
}
