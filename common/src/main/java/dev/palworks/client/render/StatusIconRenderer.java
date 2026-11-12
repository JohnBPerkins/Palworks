package dev.palworks.client.render;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.palworks.config.PalworksClientConfig;
import dev.palworks.worker.WorkerStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

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
            drawIcons(minecraft, pose.last().pose(), statuses);
            pose.popPose();
            drew = true;
        }
        if (drew) {
            BUFFERS.endBatch();
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

    /** Side by side, centred over its head. */
    private static void drawIcons(Minecraft minecraft, Matrix4f matrix, List<WorkerStatus> statuses) {
        float width = statuses.size() * ICON_PX + (statuses.size() - 1) * GAP_PX;
        float x = -width / 2F;
        for (WorkerStatus status : statuses) {
            // The outline, then the icon over it
            drawSprite(minecraft, matrix, status.background(), x);
            drawSprite(minecraft, matrix, status.sprite(), x);
            x += ICON_PX + GAP_PX;
        }
    }

    private static void drawSprite(Minecraft minecraft, Matrix4f matrix, ResourceLocation id, float x) {
        TextureAtlasSprite sprite = minecraft.getGuiSprites().getSprite(id);
        VertexConsumer quad = BUFFERS.getBuffer(RenderType.text(sprite.atlasLocation()));
        float x1 = x + ICON_PX;
        float y0 = -ICON_PX;
        float y1 = 0F;
        int light = LightTexture.FULL_BRIGHT;
        quad.addVertex(matrix, x, y0, 0)
                .setColor(0xFFFFFFFF)
                .setUv(sprite.getU0(), sprite.getV0())
                .setLight(light);
        quad.addVertex(matrix, x, y1, 0)
                .setColor(0xFFFFFFFF)
                .setUv(sprite.getU0(), sprite.getV1())
                .setLight(light);
        quad.addVertex(matrix, x1, y1, 0)
                .setColor(0xFFFFFFFF)
                .setUv(sprite.getU1(), sprite.getV1())
                .setLight(light);
        quad.addVertex(matrix, x1, y0, 0)
                .setColor(0xFFFFFFFF)
                .setUv(sprite.getU1(), sprite.getV0())
                .setLight(light);
    }
}
