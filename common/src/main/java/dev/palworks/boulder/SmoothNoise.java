package dev.palworks.boulder;

/**
 * Smooth random noise (value noise): from 0 to 1, changing gradually from block to block rather
 * than jumping, so a boulder's lumps are rounded and its blocks lie in patches and bands instead of
 * being scattered one by one. Seeded, so one boulder's noise is the same however it's read. Pure,
 * so it's unit-tested.
 */
public final class SmoothNoise {
    private final long seed;

    public SmoothNoise(long seed) {
        this.seed = seed;
    }

    /** The noise at a point, {@code scale} blocks between its random values (bigger is smoother). */
    public double at(double x, double y, double z, double scale) {
        double fx = x / scale;
        double fy = y / scale;
        double fz = z / scale;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        int z0 = (int) Math.floor(fz);
        double tx = fade(fx - x0);
        double ty = fade(fy - y0);
        double tz = fade(fz - z0);
        double x00 = lerp(tx, corner(x0, y0, z0), corner(x0 + 1, y0, z0));
        double x10 = lerp(tx, corner(x0, y0 + 1, z0), corner(x0 + 1, y0 + 1, z0));
        double x01 = lerp(tx, corner(x0, y0, z0 + 1), corner(x0 + 1, y0, z0 + 1));
        double x11 = lerp(tx, corner(x0, y0 + 1, z0 + 1), corner(x0 + 1, y0 + 1, z0 + 1));
        return lerp(tz, lerp(ty, x00, x10), lerp(ty, x01, x11));
    }

    /** The noise on the ground's plane (for a boulder's outline). */
    public double at(double x, double z, double scale) {
        return at(x, 0, z, scale);
    }

    // The random value at a lattice corner, 0 to 1
    private double corner(int x, int y, int z) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= y * 0xC2B2AE3D27D4EB4FL;
        h ^= z * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double fade(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }
}
