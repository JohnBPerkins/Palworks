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
 * Draws a work icon just to the right of each sent-out party member on Cobblemon's party HUD:
 * <ul>
 *   <li>the job's icon (axe, flame, wheat...) while it's working on it,</li>
 *   <li>the same icon faded while it's walking there,</li>
 *   <li>greyed out while it's paused (in a battle or evolving),</li>
 *   <li>a red cross when its work is switched off,</li>
 *   <li>nothing while it has no job.</li>
 * </ul>
 * Called by each loader's HUD event (Fabric HudRenderCallback, NeoForge RenderGuiEvent.Post), the
 * same events Cobblemon draws the HUD from. Its layout is copied from {@link PartyOverlay}; the
 * icons sit outside Cobblemon's slots, so the order the two are drawn in doesn't matter.
 */
public final class WorkStatusHud {
    // PartyOverlay's layout (private constants there)
    private static final int SLOT_HEIGHT = 30;
    private static final int SLOT_WIDTH = 62;
    private static final int SLOT_SPACING = 4;
    private static final int SELECTED_OFFSET_X = 6;

    private static final int ICON_SIZE = 10;
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

            int x = SLOT_WIDTH + 2 + (selectedSlot == index ? SELECTED_OFFSET_X : 0);
            int y = startY + (SLOT_HEIGHT + SLOT_SPACING) * index + (SLOT_HEIGHT - ICON_SIZE) / 2;
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
                alpha = 0.5F;
            }
            case PAUSED -> {
                texture = iconFor(activity.job());
                shade = 0.45F;
            }
            default -> texture = iconFor(activity.job());
        }
        RenderSystem.enableBlend();
        graphics.setColor(shade, shade, shade, alpha);
        graphics.blit(texture, x, y, ICON_SIZE, ICON_SIZE, 0, 0,
                ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE);
        graphics.setColor(1F, 1F, 1F, 1F);
        RenderSystem.disableBlend();
    }
}
