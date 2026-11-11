package dev.palworks.client.gui;

import com.cobblemon.mod.common.client.gui.pc.PCGUI;
import com.cobblemon.mod.common.client.gui.summary.Summary;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientPlayerEvent;
import dev.palworks.client.ClientWorkerData;
import dev.palworks.mixin.client.PCGUIAccessor;
import dev.palworks.mixin.client.SummaryAccessor;
import dev.palworks.net.packets.RequestPcWorkLevelsPacket;
import dev.palworks.suitability.Suitability;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Shows a Pokémon's work skills ({@link WorkSkills}) on Cobblemon's PC and summary screens:
 * <ul>
 *   <li>at a glance, in the top-left corner of its portrait (the shiny star is in the top-right):
 *       each job's icon with its level beside it, on the PC (for the Pokémon hovered or picked) and
 *       on the summary;</li>
 *   <li>by name, on the summary's Info page, in the empty box left of the experience box:
 *       "Watering 3", a row each, or the first and how many more if they don't all fit.</li>
 * </ul>
 * Hovering either lists them all, by name, in a tooltip. The levels are the server's (see
 * WorkLevelSync): the party's are kept up to date, and the PC's are asked for as it opens. Nothing is
 * shown for a Pokémon whose levels haven't come.
 *
 * <p>Drawn over the screen once it's drawn (Architectury's screen render event, on both loaders),
 * lifted to {@link #OVERLAY_Z}. The layouts are copied from PCGUI and Summary, the same in
 * Cobblemon 1.7 and 1.8.
 */
public final class WorkSkillsOverlay {
    /**
     * How far towards the viewer everything here is drawn. The summary draws its pages, party and
     * model 1000 forward ("so they aren't overlapped"), and the PC its boxes; drawn last but at 0,
     * anything of ours under them was hidden, tooltip and all. This is in front of them and the
     * model (which reaches a little either side of where it's drawn), and behind Cobblemon's own
     * tooltips (another 400 forward). Ours is 400 in front of this, so it's in front of everything.
     */
    private static final float OVERLAY_Z = 1200;

    // PCGUI: its size, and where its portrait is
    private static final int PC_WIDTH = 349;
    private static final int PC_HEIGHT = 205;
    private static final float PC_PORTRAIT_X = 6;
    private static final float PC_PORTRAIT_Y = 27;
    // Summary: its size, where its portrait is, and where its Info page's empty box is
    private static final int SUMMARY_WIDTH = 331;
    private static final int SUMMARY_HEIGHT = 161;
    private static final float SUMMARY_PORTRAIT_X = 6;
    private static final float SUMMARY_PORTRAIT_Y = 32;
    private static final int SUMMARY_INFO_PAGE = 0;
    private static final float BOX_X = 77 + 5; // the Info page is at 77, 12; the box at 5, 123 in it
    private static final float BOX_Y = 12 + 123;
    private static final float BOX_WIDTH = 61;
    private static final float BOX_HEIGHT = 19;

    // As far in from the portrait's top-left as the shiny star is from its top-right
    private static final float CORNER_INSET_X = 2;
    private static final float CORNER_INSET_Y = 1.5F;
    private static final int MAX_CORNER_ICONS = 6;

    private static final float ICON_SIZE = 8;
    private static final int ICON_TEXTURE_SIZE = 16;
    private static final float ROW_HEIGHT = 9;
    private static final float TEXT_SCALE = 0.5F;
    private static final float TEXT_DROP = 2; // half-size text sits this far down an 8-high row
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFA0A0A0;

    private WorkSkillsOverlay() {}

    public static void init() {
        ClientGuiEvent.INIT_POST.register((screen, access) -> {
            if (screen instanceof PCGUI) {
                new RequestPcWorkLevelsPacket().sendToServer();
            }
        });
        ClientGuiEvent.RENDER_POST.register(
                (screen, graphics, mouseX, mouseY, delta) -> render(screen, graphics, mouseX, mouseY));
        // Another world or server: these were its Pokémon
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(player -> ClientWorkerData.clearWorkLevels());
    }

    private static void render(Screen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        if (!(screen instanceof PCGUI) && !(screen instanceof Summary)) {
            return;
        }
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0, 0, OVERLAY_Z);
        renderLifted(screen, graphics, mouseX, mouseY);
        pose.popPose();
    }

    private static void renderLifted(Screen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        if (screen instanceof PCGUI) {
            // The accessor mixin makes every PCGUI one
            Pokemon pokemon = ((PCGUIAccessor) (Object) screen).palworks$previewPokemon();
            if (pokemon != null) {
                // Centred in whole pixels, as PCGUI does
                int left = (screen.width - PC_WIDTH) / 2;
                int top = (screen.height - PC_HEIGHT) / 2;
                drawCorner(graphics, pokemon, left + PC_PORTRAIT_X, top + PC_PORTRAIT_Y, mouseX, mouseY);
            }
        } else if (screen instanceof Summary) {
            SummaryAccessor summary = (SummaryAccessor) (Object) screen;
            Pokemon pokemon = summary.palworks$selectedPokemon();
            int left = (screen.width - SUMMARY_WIDTH) / 2;
            int top = (screen.height - SUMMARY_HEIGHT) / 2;
            drawCorner(graphics, pokemon, left + SUMMARY_PORTRAIT_X, top + SUMMARY_PORTRAIT_Y, mouseX, mouseY);
            if (summary.palworks$mainScreenIndex() == SUMMARY_INFO_PAGE) {
                drawBox(graphics, pokemon, left + BOX_X, top + BOX_Y, mouseX, mouseY);
            }
        }
    }

    /** Each job's icon and level, a row each, from the portrait's top-left corner. */
    private static void drawCorner(
            GuiGraphics graphics, Pokemon pokemon, float portraitX, float portraitY, int mouseX, int mouseY) {
        Map<Suitability, Integer> levels = ClientWorkerData.workLevels(pokemon.getUuid());
        List<WorkSkills.Shown> shown = levels == null ? List.of() : WorkSkills.shown(levels);
        if (shown.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        float x = portraitX + CORNER_INSET_X;
        float y = portraitY + CORNER_INSET_Y;
        int rows = Math.min(shown.size(), MAX_CORNER_ICONS);
        for (int i = 0; i < rows; i++) {
            WorkSkills.Shown skill = shown.get(i);
            float rowY = y + i * ROW_HEIGHT;
            drawIcon(graphics, WorkSkills.icon(skill.job()), x, rowY);
            drawText(graphics, font, String.valueOf(skill.level()), x + ICON_SIZE + 1.5F, rowY + TEXT_DROP, WHITE);
        }
        float width = ICON_SIZE + 1.5F + font.width("5") * TEXT_SCALE;
        if (within(mouseX, mouseY, x, y, width, rows * ROW_HEIGHT)) {
            drawTooltip(graphics, font, shown, mouseX, mouseY);
        }
    }

    /** The summary's work box: each job by name with its level, or as many as fit. */
    private static void drawBox(GuiGraphics graphics, Pokemon pokemon, float boxX, float boxY, int mouseX, int mouseY) {
        Map<Suitability, Integer> levels = ClientWorkerData.workLevels(pokemon.getUuid());
        if (levels == null) {
            return; // not sent yet
        }
        List<WorkSkills.Shown> shown = WorkSkills.shown(levels);
        Font font = Minecraft.getInstance().font;
        float x = boxX + 2;
        float y = boxY + (BOX_HEIGHT - WorkSkills.BOX_ROWS * ROW_HEIGHT) / 2 + 0.5F;
        if (shown.isEmpty()) {
            Component none = Component.translatable("palworks.ui.work_skills.none");
            drawText(
                    graphics,
                    font,
                    none.getString(),
                    boxX + (BOX_WIDTH - font.width(none) * TEXT_SCALE) / 2,
                    boxY + (BOX_HEIGHT - font.lineHeight * TEXT_SCALE) / 2,
                    GREY);
            return;
        }
        int named = WorkSkills.namedInBox(shown.size());
        for (int i = 0; i < named; i++) {
            WorkSkills.Shown skill = shown.get(i);
            float rowY = y + i * ROW_HEIGHT;
            drawIcon(graphics, WorkSkills.icon(skill.job()), x, rowY);
            String name =
                    Component.translatable(WorkSkills.nameKey(skill.job())).getString();
            drawText(graphics, font, name, x + ICON_SIZE + 2, rowY + TEXT_DROP, WHITE);
            String level = String.valueOf(skill.level());
            drawText(
                    graphics,
                    font,
                    level,
                    boxX + BOX_WIDTH - 3 - font.width(level) * TEXT_SCALE,
                    rowY + TEXT_DROP,
                    WHITE);
        }
        if (named < shown.size()) {
            String more = Component.translatable("palworks.ui.work_skills.more", shown.size() - named)
                    .getString();
            drawText(graphics, font, more, x + ICON_SIZE + 2, y + named * ROW_HEIGHT + TEXT_DROP, GREY);
        }
        if (within(mouseX, mouseY, boxX, boxY, BOX_WIDTH, BOX_HEIGHT)) {
            drawTooltip(graphics, font, shown, mouseX, mouseY);
        }
    }

    private static void drawIcon(GuiGraphics graphics, @Nullable ResourceLocation icon, float x, float y) {
        if (icon == null) {
            return;
        }
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(ICON_SIZE / ICON_TEXTURE_SIZE, ICON_SIZE / ICON_TEXTURE_SIZE, 1);
        RenderSystem.enableBlend();
        graphics.blit(icon, 0, 0, 0, 0, ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE);
        RenderSystem.disableBlend();
        pose.popPose();
    }

    /** Half-size text with a shadow, as Cobblemon draws its small labels. */
    private static void drawText(GuiGraphics graphics, Font font, String text, float x, float y, int colour) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(TEXT_SCALE, TEXT_SCALE, 1);
        graphics.drawString(font, text, 0, 0, colour, true);
        pose.popPose();
    }

    /** Every shown skill by name, with its level: "Watering 3". */
    private static void drawTooltip(
            GuiGraphics graphics, Font font, List<WorkSkills.Shown> shown, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("palworks.ui.work_skills.title"));
        for (WorkSkills.Shown skill : shown) {
            lines.add(Component.translatable(
                    "palworks.ui.work_skills.entry",
                    Component.translatable(WorkSkills.nameKey(skill.job())),
                    skill.level()));
        }
        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    private static boolean within(int mouseX, int mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
