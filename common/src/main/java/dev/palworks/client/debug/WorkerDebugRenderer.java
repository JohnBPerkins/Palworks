package dev.palworks.client.debug;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.palworks.ai.WorkerAI;
import dev.palworks.client.hud.WorkStatusHud;
import dev.palworks.net.packets.WorkerDebugPacket;
import dev.palworks.worker.WorkActivity;
import dev.palworks.worker.WorkerState;
import java.util.List;
import java.util.Objects;
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

/**
 * The {@code /palworks debug} overlay, drawn in the world for each worker near the player:
 * <ul>
 *   <li>above its head, its job icon (as on the party HUD) and a few lines of text (see
 *       {@link WorkerDebugText}): its job and phase, how long it has had the job and how long until
 *       it gives it up, the step it's on, whether its path reaches the job, recent path failures,
 *       how long it has stood still, and how many spots it remembers it couldn't reach. Visible
 *       through walls; lines about something going wrong are drawn in red;</li>
 *   <li>the rest of the path it's walking, as a line from its feet (red when it doesn't reach the
 *       job);</li>
 *   <li>a box around its job's block, and a dark red box round each spot it couldn't reach.</li>
 * </ul>
 * Colour follows the phase: yellow walking, green working, grey paused, red off, white idle.
 * Called by each loader's level render event (Fabric WorldRenderEvents.AFTER_ENTITIES, NeoForge
 * RenderLevelStageEvent AFTER_ENTITIES). Draws into its own buffer, so it doesn't change when
 * vanilla's batches are drawn.
 */
public final class WorkerDebugRenderer {
    private static final float LABEL_SCALE = 0.025F;
    private static final int ICON_PX = 16;
    private static final int LINE_HEIGHT = 10;
    private static final int INFO_COLOUR = 0xFFDDDDDD;
    private static final int WARNING_COLOUR = 0xFFFF6A50;
    private static final int UNREACHABLE_COLOUR = 0xFFB02A20;

    private static final MultiBufferSource.BufferSource BUFFERS =
            MultiBufferSource.immediate(new ByteBufferBuilder(1 << 12));

    @Nullable
    private static ClientLevel lastLevel;

    private WorkerDebugRenderer() {}

    public static void render(Camera camera, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        // A new world or server: the entity ids we were sent belong to the old one.
        if (!Objects.equals(level, lastLevel)) {
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
            drawLabel(pose, camera, cam, minecraft.font, entity, feet, entry, colour);
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

    private static void drawPathAndTarget(
            PoseStack pose, Vec3 cam, Vec3 feet, WorkerDebugPacket.Entry entry, int colour) {
        VertexConsumer lines = BUFFERS.getBuffer(RenderType.lines());
        int pathColour = WorkerDebugText.pathFallsShort(entry.details()) ? WARNING_COLOUR : colour;
        Vec3 previous = feet.add(0, 0.1, 0);
        for (BlockPos node : entry.path()) {
            Vec3 next = Vec3.atBottomCenterOf(node).add(0, 0.1, 0);
            line(pose, lines, previous.subtract(cam), next.subtract(cam), pathColour);
            previous = next;
        }
        if (entry.target() != null) {
            box(pose, lines, cam, entry.target(), 0.01, colour);
        }
        for (BlockPos spot : entry.unreachable()) {
            box(pose, lines, cam, spot, 0.03, UNREACHABLE_COLOUR);
        }
    }

    private static void box(PoseStack pose, VertexConsumer lines, Vec3 cam, BlockPos pos, double grow, int colour) {
        AABB box = new AABB(pos).inflate(grow).move(-cam.x, -cam.y, -cam.z);
        LevelRenderer.renderLineBox(
                pose, lines, box, red(colour) / 255F, green(colour) / 255F, blue(colour) / 255F, 1F);
    }

    private static void line(PoseStack pose, VertexConsumer lines, Vec3 from, Vec3 to, int colour) {
        Vec3 normal = to.subtract(from).normalize();
        PoseStack.Pose last = pose.last();
        lines.addVertex(last, (float) from.x, (float) from.y, (float) from.z)
                .setColor(colour)
                .setNormal(last, (float) normal.x, (float) normal.y, (float) normal.z);
        lines.addVertex(last, (float) to.x, (float) to.y, (float) to.z)
                .setColor(colour)
                .setNormal(last, (float) normal.x, (float) normal.y, (float) normal.z);
    }

    private static void drawLabel(
            PoseStack pose,
            Camera camera,
            Vec3 cam,
            Font font,
            Entity entity,
            Vec3 feet,
            WorkerDebugPacket.Entry entry,
            int colour) {
        WorkActivity activity = entry.activity();
        pose.pushPose();
        pose.translate(feet.x - cam.x, feet.y - cam.y + entity.getBbHeight() + 0.5, feet.z - cam.z);
        pose.mulPose(camera.rotation());
        // As a name tag: units are GUI pixels, y down. The label sits at 0, the icon above it.
        pose.scale(LABEL_SCALE, -LABEL_SCALE, LABEL_SCALE);
        Matrix4f matrix = pose.last().pose();

        @Nullable ResourceLocation icon = iconOf(activity);
        if (icon != null) {
            VertexConsumer quad = BUFFERS.getBuffer(RenderType.textSeeThrough(icon));
            float x0 = -ICON_PX / 2F;
            float x1 = ICON_PX / 2F;
            float y0 = -ICON_PX - 2F;
            float y1 = -2F;
            int light = LightTexture.FULL_BRIGHT;
            quad.addVertex(matrix, x0, y0, 0).setColor(0xFFFFFFFF).setUv(0, 0).setLight(light);
            quad.addVertex(matrix, x0, y1, 0).setColor(0xFFFFFFFF).setUv(0, 1).setLight(light);
            quad.addVertex(matrix, x1, y1, 0).setColor(0xFFFFFFFF).setUv(1, 1).setLight(light);
            quad.addVertex(matrix, x1, y0, 0).setColor(0xFFFFFFFF).setUv(1, 0).setLight(light);
        }

        // The first line (job and phase) in the phase's colour; the rest grey, or red for warnings
        List<WorkerDebugText.Line> lines = WorkerDebugText.lines(
                activity,
                entry.details(),
                entry.unreachable().size(),
                WorkerState.STUCK_AFTER_PATH_FAILURES,
                WorkerAI.STEP_IN_TICKS);
        for (int i = 0; i < lines.size(); i++) {
            WorkerDebugText.Line line = lines.get(i);
            int lineColour = i == 0 ? colour : line.warning() ? WARNING_COLOUR : INFO_COLOUR;
            font.drawInBatch(
                    line.text(),
                    -font.width(line.text()) / 2F,
                    i * LINE_HEIGHT,
                    lineColour,
                    false,
                    matrix,
                    BUFFERS,
                    Font.DisplayMode.SEE_THROUGH,
                    0x40000000,
                    LightTexture.FULL_BRIGHT);
        }
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
