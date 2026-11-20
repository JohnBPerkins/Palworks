package dev.palworks.client.gui;

import com.cobblemon.mod.common.client.gui.summary.featurerenderers.BarSummarySpeciesFeatureRenderer;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.net.packets.WorkerMoodPacket;
import net.minecraft.client.gui.GuiGraphics;
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

    // Fullness's bar's three colours (Cobblemon's FullnessFeatureRenderer): green, yellow, red. In
    // 0 to 255: the bar divides each by 255 (0 to 1 drew it black)
    private static final Vec3 HAPPY = new Vec3(120, 200, 80);
    private static final Vec3 CONTENT = new Vec3(240, 200, 65);
    private static final Vec3 UNHAPPY = new Vec3(230, 80, 65);

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

    /**
     * Draws the bar, as Cobblemon's fullness and friendship bars' {@code render} do; StatWidgetMixin
     * calls this in place of {@code render}. The inherited {@code render} draws a bar only for a
     * species feature of the bar's name, and a Pokémon has none called this, so it drew nothing. It
     * can't be overridden here instead: Kotlin compiled it as a bridge method, and javac always warns
     * on overriding one (no lint category, so no suppressing it under -Werror).
     */
    public void draw(GuiGraphics graphics, float x, float y, Pokemon pokemon) {
        renderElement(graphics, x, y, pokemon, getValue());
    }

    private static Vec3 colour(WorkerMoodPacket.Shown mood) {
        return switch (WorkSkillsOverlay.face(mood)) {
            case "happy" -> HAPPY;
            case "content" -> CONTENT;
            default -> UNHAPPY; // unhappy, or on strike
        };
    }
}
