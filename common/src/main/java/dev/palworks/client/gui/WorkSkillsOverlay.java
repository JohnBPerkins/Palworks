package dev.palworks.client.gui;

import com.cobblemon.mod.common.client.gui.pc.PCGUI;
import com.cobblemon.mod.common.client.gui.summary.Summary;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.palworks.Palworks;
import dev.palworks.client.ClientWorkerData;
import dev.palworks.mixin.client.PCGUIAccessor;
import dev.palworks.mixin.client.SummaryAccessor;
import dev.palworks.net.packets.RequestPcWorkLevelsPacket;
import dev.palworks.net.packets.WorkerMoodPacket;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Shows a Pokémon's work skills ({@link WorkSkills}) on Cobblemon's PC and summary screens, in the
 * top-left corner of its portrait (the shiny star is in the top-right): each job's icon with its
 * level beside it (if its level changes anything, see WorkSkills.isLevelled), on the PC (for the Pokémon hovered or picked) and on the summary. Hovering them
 * lists them all, by name, in a tooltip, each with a line on what it does and one on what its
 * level gives (every level's, while Shift is held). The levels are the server's (see
 * WorkLevelSync): the party's are kept up to date, and the PC's are asked for as it opens. Nothing is
 * shown for a Pokémon whose levels haven't come.
 *
 * <p>A worker's mood (see dev.palworks.mood.Mood) shows in the portrait's bottom-left corner, on the PC
 * and the summary: a face (happy, content, unhappy or on strike) and the number, with a tooltip saying
 * what it means. Only for a Pokémon that can work, and only once the server has sent it (never while
 * mood is off).
 *
 * <p>Drawn over the screen once it's drawn (Architectury's screen render event, on both loaders),
 * lifted to {@link #OVERLAY_Z}. The layouts are copied from PCGUI and Summary, the same in
 * Cobblemon 1.8.x.
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
    // Summary: its size, and where its portrait is
    private static final int SUMMARY_WIDTH = 331;
    private static final int SUMMARY_HEIGHT = 161;
    private static final float SUMMARY_PORTRAIT_X = 6;
    private static final float SUMMARY_PORTRAIT_Y = 32;
    // Both screens' portraits are this square (PCGUI and Summary's PORTRAIT_SIZE, in 1.8.x)
    private static final float PORTRAIT_SIZE = 66;

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

    // A worker this happy or more has the happy face; below it, content, down to unhappy
    private static final int HAPPY_FROM = 75;

    private WorkSkillsOverlay() {}

    public static void init() {
        ClientGuiEvent.INIT_POST.register((screen, access) -> {
            if (screen instanceof PCGUI) {
                new RequestPcWorkLevelsPacket().sendToServer();
            }
        });
        ClientGuiEvent.RENDER_POST.register(
                (screen, graphics, mouseX, mouseY, delta) -> render(screen, graphics, mouseX, mouseY));
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
                drawMood(graphics, pokemon, left + PC_PORTRAIT_X, top + PC_PORTRAIT_Y, mouseX, mouseY);
            }
        } else if (screen instanceof Summary) {
            Pokemon pokemon = ((SummaryAccessor) (Object) screen).palworks$selectedPokemon();
            int left = (screen.width - SUMMARY_WIDTH) / 2;
            int top = (screen.height - SUMMARY_HEIGHT) / 2;
            drawCorner(graphics, pokemon, left + SUMMARY_PORTRAIT_X, top + SUMMARY_PORTRAIT_Y, mouseX, mouseY);
            drawMood(graphics, pokemon, left + SUMMARY_PORTRAIT_X, top + SUMMARY_PORTRAIT_Y, mouseX, mouseY);
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
            if (WorkSkills.isLevelled(skill.job())) {
                drawText(graphics, font, String.valueOf(skill.level()), x + ICON_SIZE + 1.5F, rowY + TEXT_DROP, WHITE);
            }
        }
        float width = ICON_SIZE + 1.5F + font.width("5") * TEXT_SCALE;
        if (within(mouseX, mouseY, x, y, width, rows * ROW_HEIGHT)) {
            drawTooltip(graphics, font, shown, mouseX, mouseY);
        }
    }

    /** Its mood's face and number, in the portrait's bottom-left corner. */
    private static void drawMood(
            GuiGraphics graphics, Pokemon pokemon, float portraitX, float portraitY, int mouseX, int mouseY) {
        WorkerMoodPacket.Shown mood = ClientWorkerData.mood(pokemon.getUuid());
        Map<Suitability, Integer> levels = ClientWorkerData.workLevels(pokemon.getUuid());
        if (mood == null || levels == null || levels.isEmpty()) {
            return; // not sent, mood off, or it can't work
        }
        Font font = Minecraft.getInstance().font;
        float x = portraitX + CORNER_INSET_X;
        float y = portraitY + PORTRAIT_SIZE - ICON_SIZE - CORNER_INSET_Y;
        drawIcon(graphics, Palworks.id("textures/gui/mood/" + face(mood) + ".png"), x, y);
        String number = String.valueOf(mood.mood());
        drawText(graphics, font, number, x + ICON_SIZE + 1.5F, y + TEXT_DROP, WHITE);
        float width = ICON_SIZE + 1.5F + font.width(number) * TEXT_SCALE;
        if (within(mouseX, mouseY, x, y, width, ICON_SIZE)) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("palworks.ui.mood.title", mood.mood()));
            if (mood.striking()) {
                lines.add(Component.translatable("palworks.ui.mood.striking", ClientWorkerData.strikeUntil()));
            } else if (mood.unhappy()) {
                lines.add(Component.translatable("palworks.ui.mood.unhappy"));
            } else {
                lines.add(Component.translatable(
                        mood.mood() >= HAPPY_FROM ? "palworks.ui.mood.happy" : "palworks.ui.mood.content"));
            }
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    /** Which face a mood shows: its texture's name. */
    private static String face(WorkerMoodPacket.Shown mood) {
        if (mood.striking()) {
            return "striking";
        }
        if (mood.unhappy()) {
            return "unhappy";
        }
        return mood.mood() >= HAPPY_FROM ? "happy" : "content";
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

    /**
     * Every shown skill by name, with its level where it has one that matters ("Watering: level 3"),
     * what it does under it, and what its level gives. With Shift held, what every level gives
     * instead, this Pokémon's in white; without, a last line saying so.
     */
    private static void drawTooltip(
            GuiGraphics graphics, Font font, List<WorkSkills.Shown> shown, int mouseX, int mouseY) {
        boolean everyLevel = Screen.hasShiftDown();
        double fertilizeChance = ClientWorkerData.fertilizeChancePerLevel();
        boolean anyLevelled = false;
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("palworks.ui.work_skills.title"));
        for (WorkSkills.Shown skill : shown) {
            Component name = Component.translatable(WorkSkills.nameKey(skill.job()));
            lines.add(
                    WorkSkills.isLevelled(skill.job())
                            ? Component.translatable("palworks.ui.work_skills.entry", name, skill.level())
                            : name);
            lines.add(Component.translatable(WorkSkills.descriptionKey(skill.job()))
                    .withStyle(ChatFormatting.GRAY));
            if (!WorkSkills.isLevelled(skill.job())) {
                continue;
            }
            anyLevelled = true;
            if (everyLevel) {
                for (int level = 1; level <= SuitabilityData.MAX_LEVEL; level++) {
                    lines.add(Component.translatable(
                                    "palworks.ui.work_skills.every_level",
                                    level,
                                    effectText(skill.job(), level, fertilizeChance))
                            .withStyle(level == skill.level() ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY));
                }
            } else {
                lines.add(
                        effectText(skill.job(), skill.level(), fertilizeChance).withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        if (anyLevelled && !everyLevel) {
            lines.add(Component.translatable("palworks.ui.work_skills.hold_shift")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }
        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    /** What a level of a levelled skill gives, as text. */
    private static MutableComponent effectText(Suitability job, int level, double fertilizeChancePerLevel) {
        // Never null for a levelled skill (WorkSkillsTest)
        WorkSkills.LevelEffect effect =
                Objects.requireNonNull(WorkSkills.levelEffect(job, level, fertilizeChancePerLevel));
        return Component.translatable(effect.key(), effect.args().toArray());
    }

    private static boolean within(int mouseX, int mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
