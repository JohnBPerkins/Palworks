package dev.palworks.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Helper for debug visualization rendering.
 * Provides geometry for rendering paths, targets, etc.
 */
public class DebugRenderer {

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
