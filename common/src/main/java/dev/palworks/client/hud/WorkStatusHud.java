package dev.palworks.client.hud;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.gui.PartyOverlay;
import com.cobblemon.mod.common.client.storage.ClientParty;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.activestate.SentOutState;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.palworks.job.JobType;
import dev.palworks.net.PalworksResource;
import dev.palworks.worker.WorkActivity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Draws a work icon on each sent-out party member's card on Cobblemon's party HUD, as a badge in
 * the bottom-right corner of its portrait:
 * <ul>
 *   <li>the job's icon (axe, flame, wheat...) while it's working on it,</li>
 *   <li>the same icon faded while it's walking there,</li>
 *   <li>greyed out while it's paused (in a battle or evolving),</li>
 *   <li>a red cross when its work is switched off,</li>
 *   <li>nothing while it has no job.</li>
 * </ul>
 * The rest of the card is taken (state icon, level, held item, HP/EXP/status bars, ball, name).
 * Called by each loader's HUD event (Fabric HudRenderCallback, NeoForge RenderGuiEvent.Post), the
 * same events Cobblemon draws the HUD from, registered to run after Cobblemon's so the badge is drawn
 * over the portrait. Its layout is copied from {@link PartyOverlay}.
 */
public final class WorkStatusHud {
    // PartyOverlay's layout (private constants there)
    private static final int SLOT_HEIGHT = 30;
    private static final int SLOT_SPACING = 4;
    private static final int SELECTED_OFFSET_X = 6;
    // The portrait spans x 22-43, y 2-23 of the card: the badge sits in its bottom-right corner
    private static final int ICON_X = 34;
    private static final int ICON_Y = 13;
    // Above the portrait's 3D model, which is drawn with depth
    private static final float ICON_Z = 300F;

    private static final int ICON_SIZE = 8;
    private static final int ICON_TEXTURE_SIZE = 16;

    private static final ResourceLocation OFF_ICON = icon("off");

    @Nullable
    private static ClientLevel lastLevel;

    private WorkStatusHud() {}

    private static ResourceLocation icon(String name) {
        return PalworksResource.of("textures/gui/hud/work_" + name + ".png");
    }

    /** The icon for a job, by its id (e.g. {@code work_chop_tree.png}); a generic one for jobs without their own. */
    static ResourceLocation iconFor(@Nullable JobType job) {
        if (job == null) {
            return icon("generic");
        }
        return switch (job) {
            case CHOP_TREE, HARVEST_CROPS, PLANT_SEEDS, GET_SEEDS, FUEL_FURNACE,
                 COLLECT_DROPS, DELIVER_ITEMS, MINE_RESOURCE -> icon(job.getId());
            default -> icon("generic");
        };
    }

    public static void render(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        // A new world or server: what we were told about the old one's workers no longer applies.
        if (minecraft.level != lastLevel) {
            lastLevel = minecraft.level;
            WorkActivityCache.clear();
        }
        if (minecraft.level == null || !PartyOverlay.Companion.canRender()) {
            return;
        }

        ClientParty party = CobblemonClient.INSTANCE.getStorage().getParty();
        List<Pokemon> slots = party.getSlots();
        if (slots.stream().allMatch(p -> p == null)) {
            return;
        }
        int selectedSlot = CobblemonClient.INSTANCE.getStorage().getSelectedSlot();
        int totalHeight = slots.size() * SLOT_HEIGHT;
        int midY = minecraft.getWindow().getGuiScaledHeight() / 2;
        int startY = (midY - totalHeight / 2) - ((SLOT_SPACING * 5) / 2);

        for (int index = 0; index < slots.size(); index++) {
            Pokemon pokemon = slots.get(index);
            // Only sent-out Pokemon work; anything we were told about one that's back in its ball is old.
            if (pokemon == null || !(pokemon.getState() instanceof SentOutState)) {
                continue;
            }
            WorkActivity activity = WorkActivityCache.get(pokemon.getUuid());
            if (activity == null || activity.phase() == WorkActivity.Phase.IDLE) {
                continue;
            }

            int x = ICON_X + (selectedSlot == index ? SELECTED_OFFSET_X : 0);
            int y = startY + (SLOT_HEIGHT + SLOT_SPACING) * index + ICON_Y;
            drawIcon(graphics, activity, x, y);
        }
    }

    private static void drawIcon(GuiGraphics graphics, WorkActivity activity, int x, int y) {
        ResourceLocation texture;
        float shade = 1F;
        float alpha = 1F;
        switch (activity.phase()) {
            case OFF -> texture = OFF_ICON;
            case WALKING -> {
                texture = iconFor(activity.job());
                alpha = 0.6F;
            }
            case PAUSED -> {
                texture = iconFor(activity.job());
                shade = 0.45F;
            }
            default -> texture = iconFor(activity.job());
        }
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, ICON_Z);
        RenderSystem.enableBlend();
        graphics.setColor(shade, shade, shade, alpha);
        graphics.blit(texture, x, y, ICON_SIZE, ICON_SIZE, 0, 0,
                ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE);
        graphics.setColor(1F, 1F, 1F, 1F);
        RenderSystem.disableBlend();
        graphics.pose().popPose();
    }
}
