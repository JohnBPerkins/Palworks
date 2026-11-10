package dev.palworks.client.debug;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.palworks.client.hud.WorkStatusHud;
import dev.palworks.net.packets.WorkerDebugPacket;
import dev.palworks.worker.WorkActivity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.List;

/**
 * The {@code /palworks debug overlay} view, drawn in the world for each worker near the player:
 * <ul>
 *   <li>above its head, its job icon (as on the party HUD) and a label such as
 *       "chop_tree · walking", visible through walls;</li>
 *   <li>the rest of the path it's walking, as a line from its feet;</li>
 *   <li>a box around its job's block.</li>
 * </ul>
 * Colour follows the phase: yellow walking, green working, grey paused, red off, white idle.
 * Called by each loader's level render event (Fabric WorldRenderEvents.AFTER_ENTITIES, NeoForge
 * RenderLevelStageEvent AFTER_ENTITIES). Draws into its own buffer, so it doesn't change when
 * vanilla's batches are drawn.
 */
public final class WorkerDebugRenderer {
    private static final float LABEL_SCALE = 0.025F;
    private static final int ICON_PX = 16;

    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 12));

    @Nullable
    private static ClientLevel lastLevel;

    private WorkerDebugRenderer() {}

    public static void render(Camera camera, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        // A new world or server: the entity ids we were sent belong to the old one.
        if (level != lastLevel) {
            lastLevel = level;
            WorkerDebugCache.set(List.of());
        }
        List<WorkerDebugPacket.Entry> entries = WorkerDebugCache.get();
        if (level == null || entries.isEmpty()) {
            return;
        }
        Vec3 cam = camera.getPosition();
        PoseStack pose = new PoseStack();

        for (WorkerDebugPacket.Entry entry : entries) {
            Entity entity = level.getEntity(entry.entityId());
            if (entity == null) {
                continue;
            }
            int colour = colourOf(entry.activity().phase());
            Vec3 feet = entity.getPosition(partialTick);
            drawPathAndTarget(pose, cam, feet, entry, colour);
            drawLabel(pose, camera, cam, minecraft.font, entity, feet, entry.activity(), colour);
        }
        BUFFERS.endBatch();
    }

    private static int colourOf(WorkActivity.Phase phase) {
        return switch (phase) {
            case WALKING -> 0xFFFFD84A;
            case WORKING -> 0xFF5BE05B;
            case PAUSED -> 0xFF9A9A9A;
            case OFF -> 0xFFE04545;
            case IDLE -> 0xFFFFFFFF;
        };
    }

    private static void drawPathAndTarget(PoseStack pose, Vec3 cam, Vec3 feet, WorkerDebugPacket.Entry entry, int colour) {
        VertexConsumer lines = BUFFERS.getBuffer(RenderType.lines());
        Vec3 previous = feet.add(0, 0.1, 0);
        for (BlockPos node : entry.path()) {
            Vec3 next = Vec3.atBottomCenterOf(node).add(0, 0.1, 0);
            line(pose, lines, previous.subtract(cam), next.subtract(cam), colour);
            previous = next;
        }
        if (entry.target() != null) {
            AABB box = new AABB(entry.target()).inflate(0.01).move(-cam.x, -cam.y, -cam.z);
            LevelRenderer.renderLineBox(pose, lines, box,
                    red(colour) / 255F, green(colour) / 255F, blue(colour) / 255F, 1F);
        }
    }

    private static void line(PoseStack pose, VertexConsumer lines, Vec3 from, Vec3 to, int colour) {
        Vec3 normal = to.subtract(from).normalize();
        PoseStack.Pose last = pose.last();
        lines.addVertex(last, (float) from.x, (float) from.y, (float) from.z)
                .setColor(colour).setNormal(last, (float) normal.x, (float) normal.y, (float) normal.z);
        lines.addVertex(last, (float) to.x, (float) to.y, (float) to.z)
                .setColor(colour).setNormal(last, (float) normal.x, (float) normal.y, (float) normal.z);
    }

    private static void drawLabel(PoseStack pose, Camera camera, Vec3 cam, Font font, Entity entity,
                                  Vec3 feet, WorkActivity activity, int colour) {
        pose.pushPose();
        pose.translate(feet.x - cam.x, feet.y - cam.y + entity.getBbHeight() + 0.5, feet.z - cam.z);
        pose.mulPose(camera.rotation());
        // As a name tag: units are GUI pixels, y down. The label sits at 0, the icon above it.
        pose.scale(LABEL_SCALE, -LABEL_SCALE, LABEL_SCALE);
        Matrix4f matrix = pose.last().pose();

        @Nullable ResourceLocation icon = iconOf(activity);
        if (icon != null) {
            VertexConsumer quad = BUFFERS.getBuffer(RenderType.textSeeThrough(icon));
            float x0 = -ICON_PX / 2F, x1 = ICON_PX / 2F, y0 = -ICON_PX - 2F, y1 = -2F;
            int light = LightTexture.FULL_BRIGHT;
            quad.addVertex(matrix, x0, y0, 0).setColor(0xFFFFFFFF).setUv(0, 0).setLight(light);
            quad.addVertex(matrix, x0, y1, 0).setColor(0xFFFFFFFF).setUv(0, 1).setLight(light);
            quad.addVertex(matrix, x1, y1, 0).setColor(0xFFFFFFFF).setUv(1, 1).setLight(light);
            quad.addVertex(matrix, x1, y0, 0).setColor(0xFFFFFFFF).setUv(1, 0).setLight(light);
        }

        String text = activity.job() == null
                ? activity.phase().name().toLowerCase()
                : activity.job().getId() + " · " + activity.phase().name().toLowerCase();
        font.drawInBatch(text, -font.width(text) / 2F, 0, colour, false, matrix, BUFFERS,
                Font.DisplayMode.SEE_THROUGH, 0x40000000, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }

    @Nullable
    private static ResourceLocation iconOf(WorkActivity activity) {
        return switch (activity.phase()) {
            case IDLE -> null;
            case OFF -> WorkStatusHud.OFF_ICON;
            default -> WorkStatusHud.iconFor(activity.job());
        };
    }

    private static int red(int argb) {
        return (argb >> 16) & 0xFF;
    }

    private static int green(int argb) {
        return (argb >> 8) & 0xFF;
    }

    private static int blue(int argb) {
        return argb & 0xFF;
    }
}
