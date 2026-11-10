package dev.palworks.inventory;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * Client-side screen for the worker inventory GUI.
 * Renders the inventory UI with worker and player slots.
 */
public class WorkerInventoryScreen extends AbstractContainerScreen<WorkerInventoryMenu> {
    // Use the hopper texture as base - it has a similar compact layout
    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/gui/container/hopper.png");

    public WorkerInventoryScreen(WorkerInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);

        // Match hopper dimensions: 176x133
        this.imageWidth = 176;
        this.imageHeight = 133;
    }

    @Override
    protected void init() {
        super.init();

        // Adjust label positions to match vanilla style
        this.titleLabelY = 6;
        this.inventoryLabelY = this.imageHeight - 94; // 39 for 133 height
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;

        // Draw hopper texture as background (it handles the player inventory section)
        graphics.blit(TEXTURE, x, y, 0, 0, this.imageWidth, this.imageHeight);

        // The hopper has 5 slots starting at x=44, y=20
        // We only have 3 slots, so we need to cover the extra slots
        // Cover slots 4 and 5 (at x=44+3*18=98 and x=44+4*18=116) with the background color
        // Actually, let's just draw our 3 slot backgrounds over the hopper's 5 slots

        // Draw background to cover the hopper's 5 slot area, then draw our 3 slots
        // Hopper slot area is at y=20, 18px tall
        graphics.fill(x + 43, y + 19, x + 133, y + 38, 0xFFC6C6C6); // Cover slot area

        // Draw slot backgrounds for the slots this worker can use (at x=62 to match menu slot
        // positions): all 3 for Transporting, just the middle one for a planter's seed pouch.
        for (int i = 0; i < WorkerInventory.INVENTORY_SIZE; i++) {
            if (!this.menu.getCarryMode().usesSlot(i)) {
                continue;
            }
            int slotX = x + 61 + i * 18;
            int slotY = y + 19;

            // Vanilla-style slot rendering
            // Dark border (top and left)
            graphics.fill(slotX, slotY, slotX + 18, slotY + 1, 0xFF373737);
            graphics.fill(slotX, slotY, slotX + 1, slotY + 18, 0xFF373737);
            // Light border (bottom and right)
            graphics.fill(slotX, slotY + 17, slotX + 18, slotY + 18, 0xFFFFFFFF);
            graphics.fill(slotX + 17, slotY, slotX + 18, slotY + 18, 0xFFFFFFFF);
            // Slot inner (dark gray)
            graphics.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, 0xFF8B8B8B);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Draw title centered
        graphics.drawString(
                this.font,
                this.title,
                (this.imageWidth - this.font.width(this.title)) / 2,
                this.titleLabelY,
                0x404040,
                false);

        // Draw player inventory label
        graphics.drawString(this.font, this.playerInventoryTitle, 8, this.inventoryLabelY, 0x404040, false);
    }
}
