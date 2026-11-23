package dev.palworks.client.render;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.palworks.client.gui.WorkIcons;
import dev.palworks.config.PalworksClientConfig;
import dev.palworks.worker.WorkerStatus;
import dev.palworks.worker.WorkerStatusSync;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Shows what's holding a worker's work back (see {@link WorkerStatus}: hungry...) as icons over its
 * head, for everyone nearby, as a name tag shows. Hidden behind walls, like the Pokémon itself.
 * Players can turn it off ({@code show_status_icons} in {@code config/palworks-client.json}).
 *
 * <p>Called by each loader's world render event, after entities (Fabric's WorldRenderEvents,
 * NeoForge's RenderLevelStageEvent), as the debug overlay is.
 */
public final class StatusIconRenderer {
    /** How far off workers' icons show, in blocks. */
    private static final double RANGE = 32;
    // As a name tag: a GUI pixel is this many blocks
    private static final float SCALE = 0.025F;
    private static final float ICON_PX = 9F;
    private static final float GAP_PX = 1F;
    // How far over the top of its hitbox the icons sit, in blocks
    private static final double ABOVE = 0.35;
    // How much bigger a pausing worker's icons grow at most
    private static final float PULSE = 0.35F;

    private static final MultiBufferSource.BufferSource BUFFERS =
            MultiBufferSource.immediate(new ByteBufferBuilder(1 << 10));

    private StatusIconRenderer() {}

    public static void render(Camera camera, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || !PalworksClientConfig.current().showStatusIcons()) {
            return;
        }
        Vec3 cam = camera.getPosition();
        PoseStack pose = new PoseStack();
        boolean drew = false;
        // Items are lit as in an inventory: by the way they face the camera (either way round), not
        // by the world's light directions, which would leave a flat item dim from most angles
        Vector3f toCamera = new Vector3f(camera.getLookVector());
        RenderSystem.setShaderLights(toCamera, new Vector3f(toCamera).negate());
        for (PokemonEntity entity : level.getEntitiesOfClass(PokemonEntity.class, new AABB(cam, cam).inflate(RANGE))) {
            List<WorkerStatus> statuses = statusesOf(entity.getAspects());
            if (statuses.isEmpty() || entity.isInvisible()) {
                continue;
            }
            Vec3 feet = entity.getPosition(partialTick);
            pose.pushPose();
            pose.translate(feet.x - cam.x, feet.y - cam.y + entity.getBbHeight() + ABOVE, feet.z - cam.z);
            pose.mulPose(camera.rotation());
            pose.scale(SCALE, -SCALE, SCALE); // GUI pixels, y down
            boolean pausing = entity.getAspects().contains(WorkerStatusSync.PAUSING);
            drawIcons(minecraft, level, pose, statuses, pausing ? pulse(level, partialTick) : 1F);
            pose.popPose();
            drew = true;
        }
        if (drew) {
            BUFFERS.endBatch();
        }
        // The world's light directions back, as GameRenderer sets them
        if (level.effects().constantAmbientLight()) {
            Lighting.setupNetherLevel();
        } else {
            Lighting.setupLevel();
        }
    }

    /** The statuses in a Pokémon's aspects, in {@link WorkerStatus}'s order. */
    static List<WorkerStatus> statusesOf(Set<String> aspects) {
        List<WorkerStatus> statuses = new ArrayList<>();
        for (WorkerStatus status : WorkerStatus.values()) {
            if (aspects.contains(status.aspect())) {
                statuses.add(status);
            }
        }
        return statuses;
    }

    /**
     * How big a pausing worker's icons are drawn now (see WorkerStatusSync#PAUSING): growing and
     * shrinking back, twice a second.
     */
    static float pulse(ClientLevel level, float partialTick) {
        double seconds = (level.getGameTime() + partialTick) / 20.0;
        return 1F + PULSE * (float) Math.abs(Math.sin(seconds * Math.PI * 2));
    }

    /**
     * Side by side, centred over its head. The ones slowing it (hungry, Tired) at {@code pulse}
     * times their size, from their bottom middle.
     */
    private static void drawIcons(
            Minecraft minecraft, ClientLevel level, PoseStack pose, List<WorkerStatus> statuses, float pulse) {
        float width = statuses.size() * ICON_PX + (statuses.size() - 1) * GAP_PX;
        float x = -width / 2F;
        Matrix4f matrix = pose.last().pose();
        for (WorkerStatus status : statuses) {
            float size = status.slows() ? ICON_PX * pulse : ICON_PX;
            float left = x + (ICON_PX - size) / 2F;
            switch (WorkIcons.forStatus(status)) {
                case WorkIcons.GuiSprite sprite -> {
                    if (sprite.under() != null) {
                        drawSprite(minecraft, matrix, sprite.under(), left, size);
                    }
                    drawSprite(minecraft, matrix, sprite.sprite(), left, size);
                }
                case WorkIcons.Picture picture -> drawQuad(matrix, picture.texture(), 0, 0, 1, 1, left, size);
                case WorkIcons.OfItem item -> {
                    // As GuiGraphics#renderItem: the model's 1x1 square scaled up, y up
                    pose.pushPose();
                    pose.translate(left + size / 2F, -size / 2F, 0);
                    pose.scale(size, -size, size);
                    minecraft
                            .getItemRenderer()
                            .renderStatic(
                                    item.stack(),
                                    ItemDisplayContext.GUI,
                                    LightTexture.FULL_BRIGHT,
                                    OverlayTexture.NO_OVERLAY,
                                    pose,
                                    BUFFERS,
                                    level,
                                    0);
                    pose.popPose();
                }
            }
            x += ICON_PX + GAP_PX;
        }
    }

    private static void drawSprite(Minecraft minecraft, Matrix4f matrix, ResourceLocation id, float x, float size) {
        TextureAtlasSprite sprite = minecraft.getGuiSprites().getSprite(id);
        drawQuad(
                matrix,
                sprite.atlasLocation(),
                sprite.getU0(),
                sprite.getV0(),
                sprite.getU1(),
                sprite.getV1(),
                x,
                size);
    }

    /** A {@code size} square, its bottom-left corner at x, 0, showing the texture from u0, v0 to u1, v1. */
    private static void drawQuad(
            Matrix4f matrix, ResourceLocation texture, float u0, float v0, float u1, float v1, float x, float size) {
        VertexConsumer quad = BUFFERS.getBuffer(RenderType.text(texture));
        float x1 = x + size;
        float y0 = -size;
        float y1 = 0F;
        int light = LightTexture.FULL_BRIGHT;
        quad.addVertex(matrix, x, y0, 0).setColor(0xFFFFFFFF).setUv(u0, v0).setLight(light);
        quad.addVertex(matrix, x, y1, 0).setColor(0xFFFFFFFF).setUv(u0, v1).setLight(light);
        quad.addVertex(matrix, x1, y1, 0).setColor(0xFFFFFFFF).setUv(u1, v1).setLight(light);
        quad.addVertex(matrix, x1, y0, 0).setColor(0xFFFFFFFF).setUv(u1, v0).setLight(light);
    }
}
