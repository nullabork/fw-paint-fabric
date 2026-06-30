package co.fax.wang;

/**
 * Pure, seedable 3D noise (no Minecraft deps, so it's unit-testable). All sample methods return a
 * value in [0,1] and are deterministic for a given seed + coordinate — so the same world position
 * always maps to the same gradient step.
 */
public final class Noise {

    private Noise() {}

    /**
     * Sample the given noise type at a world position, with per-axis feature sizes, returning [0,1].
     * The raw noise clusters near the middle, so the result is <b>equalised</b> to spread roughly
     * uniformly across [0,1] — that way the full gradient block list is used, not just the middle.
     */
    public static double sample(NoiseType type, double x, double y, double z, long seed,
                                double scaleX, double scaleY, double scaleZ) {
        double sx = x / Math.max(0.001, scaleX);
        double sy = y / Math.max(0.001, scaleY);
        double sz = z / Math.max(0.001, scaleZ);
        return switch (type) {
            case SMOOTH -> equalize(valueNoise(sx, sy, sz, seed), 0.5, 0.19);
            case PERLIN -> equalize(perlin01(sx, sy, sz, seed), 0.5, 0.12);
            case FRACTAL -> equalize(fbm(sx, sy, sz, seed, 4), 0.5, 0.10);
        };
    }

    /** Map an (approximately Gaussian) value through its normal CDF → roughly uniform on [0,1]. */
    static double equalize(double v, double mean, double sigma) {
        double z = (v - mean) / sigma;
        // tanh approximation of the standard normal CDF.
        return clamp01(0.5 * (1.0 + Math.tanh(0.7978845608 * (z + 0.044715 * z * z * z))));
    }

    // ---- value noise (smooth) -------------------------------------------------------------------

    /** Interpolated value noise in [0,1]. */
    public static double valueNoise(double x, double y, double z, long seed) {
        int x0 = floor(x), y0 = floor(y), z0 = floor(z);
        double fx = fade(x - x0), fy = fade(y - y0), fz = fade(z - z0);

        double c000 = lattice(x0, y0, z0, seed),     c100 = lattice(x0 + 1, y0, z0, seed);
        double c010 = lattice(x0, y0 + 1, z0, seed), c110 = lattice(x0 + 1, y0 + 1, z0, seed);
        double c001 = lattice(x0, y0, z0 + 1, seed),     c101 = lattice(x0 + 1, y0, z0 + 1, seed);
        double c011 = lattice(x0, y0 + 1, z0 + 1, seed), c111 = lattice(x0 + 1, y0 + 1, z0 + 1, seed);

        double x00 = lerp(c000, c100, fx), x10 = lerp(c010, c110, fx);
        double x01 = lerp(c001, c101, fx), x11 = lerp(c011, c111, fx);
        double y0v = lerp(x00, x10, fy), y1v = lerp(x01, x11, fy);
        return lerp(y0v, y1v, fz);
    }

    // ---- perlin (gradient) noise ----------------------------------------------------------------

    /** Perlin gradient noise remapped to [0,1]. */
    public static double perlin01(double x, double y, double z, long seed) {
        int x0 = floor(x), y0 = floor(y), z0 = floor(z);
        double dx = x - x0, dy = y - y0, dz = z - z0;
        double fx = fade(dx), fy = fade(dy), fz = fade(dz);

        double n000 = grad(x0, y0, z0, seed, dx, dy, dz);
        double n100 = grad(x0 + 1, y0, z0, seed, dx - 1, dy, dz);
        double n010 = grad(x0, y0 + 1, z0, seed, dx, dy - 1, dz);
        double n110 = grad(x0 + 1, y0 + 1, z0, seed, dx - 1, dy - 1, dz);
        double n001 = grad(x0, y0, z0 + 1, seed, dx, dy, dz - 1);
        double n101 = grad(x0 + 1, y0, z0 + 1, seed, dx - 1, dy, dz - 1);
        double n011 = grad(x0, y0 + 1, z0 + 1, seed, dx, dy - 1, dz - 1);
        double n111 = grad(x0 + 1, y0 + 1, z0 + 1, seed, dx - 1, dy - 1, dz - 1);

        double x00 = lerp(n000, n100, fx), x10 = lerp(n010, n110, fx);
        double x01 = lerp(n001, n101, fx), x11 = lerp(n011, n111, fx);
        double y0v = lerp(x00, x10, fy), y1v = lerp(x01, x11, fy);
        double v = lerp(y0v, y1v, fz);  // ~[-1,1]
        return clamp01(0.5 + 0.5 * v);  // centre on 0.5; equalise() spreads it
    }

    // ---- fractal Brownian motion (summed signed octaves) ----------------------------------------

    /** Multi-octave perlin (fBm) → [0,1]; roughly Gaussian around 0.5, so it equalises cleanly. */
    public static double fbm(double x, double y, double z, long seed, int octaves) {
        double sum = 0, amp = 1, freq = 1, norm = 0;
        for (int o = 0; o < octaves; o++) {
            sum += amp * (perlin01(x * freq, y * freq, z * freq, seed + o) * 2 - 1);
            norm += amp;
            amp *= 0.5;
            freq *= 2;
        }
        return clamp01(0.5 + 0.5 * (sum / norm));
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Pseudo-random [0,1] value at a lattice point. */
    private static double lattice(int x, int y, int z, long seed) {
        return (hash(x, y, z, seed) >>> 11) * (1.0 / (1L << 53));
    }

    /** Gradient dot-product for Perlin, using a hashed direction. */
    private static double grad(int x, int y, int z, long seed, double dx, double dy, double dz) {
        long h = hash(x, y, z, seed);
        // 12 edge directions of a cube (Ken Perlin's improved gradient set).
        switch ((int) (h % 12)) {
            case 0: return dx + dy;
            case 1: return -dx + dy;
            case 2: return dx - dy;
            case 3: return -dx - dy;
            case 4: return dx + dz;
            case 5: return -dx + dz;
            case 6: return dx - dz;
            case 7: return -dx - dz;
            case 8: return dy + dz;
            case 9: return -dy + dz;
            case 10: return dy - dz;
            default: return -dy - dz;
        }
    }

    /** SplitMix64-style hash of (x,y,z,seed) → 64-bit. */
    private static long hash(int x, int y, int z, long seed) {
        long h = seed;
        h = mix(h ^ (x * 0x9E3779B97F4A7C15L));
        h = mix(h ^ (y * 0xC2B2AE3D27D4EB4FL));
        h = mix(h ^ (z * 0x165667B19E3779F9L));
        return h;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static int floor(double v) { return (v >= 0) ? (int) v : (int) v - 1; }
    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
