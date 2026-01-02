package dev.palworks.debug;

import dev.palworks.base.BaseState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper for debug visualization rendering.
 * Provides geometry for rendering base radius, paths, etc.
 */
public class DebugRenderer {

    /**
     * Get the bounding box for a base's work radius.
     * Used for rendering the sphere/cylinder of the base range.
     */
    public static AABB getBaseRadiusBox(BaseState base) {
        BlockPos pos = base.getBasePos();
        int radius = base.getWorkRadius();

        return new AABB(
                pos.getX() - radius, pos.getY() - 16, pos.getZ() - radius,
                pos.getX() + radius, pos.getY() + 16, pos.getZ() + radius
        );
    }

    /**
     * Get points for rendering a circular base radius at ground level.
     * Returns a list of points forming a circle.
     */
    public static List<Vec3> getBaseRadiusCircle(BaseState base, int segments) {
        List<Vec3> points = new ArrayList<>();
        BlockPos pos = base.getBasePos();
        int radius = base.getWorkRadius();

        for (int i = 0; i <= segments; i++) {
            double angle = 2 * Math.PI * i / segments;
            double x = pos.getX() + 0.5 + radius * Math.cos(angle);
            double z = pos.getZ() + 0.5 + radius * Math.sin(angle);
            points.add(new Vec3(x, pos.getY() + 0.1, z));
        }

        return points;
    }

    /**
     * Get a line from a worker to their job target.
     */
    public static Line getWorkerPathLine(BlockPos workerPos, BlockPos targetPos) {
        Vec3 start = new Vec3(workerPos.getX() + 0.5, workerPos.getY() + 1, workerPos.getZ() + 0.5);
        Vec3 end = new Vec3(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
        return new Line(start, end);
    }

    /**
     * Get a bounding box for highlighting a station.
     */
    public static AABB getStationHighlightBox(BlockPos pos) {
        return new AABB(pos).inflate(0.01);
    }

    /**
     * Get a bounding box for highlighting a job target.
     */
    public static AABB getJobTargetBox(BlockPos pos) {
        return new AABB(pos).inflate(0.05);
    }

    /**
     * Represents a line for rendering.
     */
    public static class Line {
        public final Vec3 start;
        public final Vec3 end;

        public Line(Vec3 start, Vec3 end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * Color constants for debug rendering.
     */
    public static class Colors {
        // Base radius
        public static final int BASE_RADIUS = 0x4040FF40; // Green with alpha

        // Worker states
        public static final int WORKER_IDLE = 0x40FFFFFF; // White
        public static final int WORKER_WORKING = 0x4000FF00; // Green
        public static final int WORKER_STUCK = 0x40FF0000; // Red
        public static final int WORKER_PATH = 0x40FFFF00; // Yellow

        // Stations
        public static final int STATION_AVAILABLE = 0x4000FFFF; // Cyan
        public static final int STATION_RESERVED = 0x40FF8800; // Orange
        public static final int STATION_BUSY = 0x40FF00FF; // Magenta

        // Job targets
        public static final int JOB_TARGET = 0x40FFFF00; // Yellow
    }
}
