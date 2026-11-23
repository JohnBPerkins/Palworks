package dev.palworks.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;

/** Draws a {@link WorkIcons.Icon} on screen. A colour set with {@link GuiGraphics#setColor} tints it. */
public final class WorkIconDrawer {
    // Pictures, like inventory items, are drawn from 16x16
    private static final int TEXTURE_SIZE = 16;

    private WorkIconDrawer() {}

    /** Draws an icon {@code size} GUI pixels square with its top-left corner at x, y. */
    public static void draw(GuiGraphics graphics, WorkIcons.Icon icon, float x, float y, float size) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(size / TEXTURE_SIZE, size / TEXTURE_SIZE, 1);
        switch (icon) {
            case WorkIcons.Picture picture -> {
                RenderSystem.enableBlend();
                graphics.blit(picture.texture(), 0, 0, 0, 0, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);
                RenderSystem.disableBlend();
            }
            case WorkIcons.GuiSprite sprite -> {
                RenderSystem.enableBlend();
                if (sprite.under() != null) {
                    graphics.blitSprite(sprite.under(), 0, 0, TEXTURE_SIZE, TEXTURE_SIZE);
                }
                graphics.blitSprite(sprite.sprite(), 0, 0, TEXTURE_SIZE, TEXTURE_SIZE);
                RenderSystem.disableBlend();
            }
            case WorkIcons.OfItem item -> graphics.renderItem(item.stack(), 0, 0);
        }
        pose.popPose();
    }
}
