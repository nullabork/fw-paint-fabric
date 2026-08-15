package co.fax.wang.shape;

/**
 * Pure math for the shape markers (ported from the fw-rule tool suite) — no Minecraft
 * classes, so it unit-tests headlessly. Positions are plain ints/doubles; the managers adapt
 * BlockPos/Vec3 at the edge.
 */
public final class ShapeMath {

    private ShapeMath() {}

    /**
     * True when the in-plane offset (du, dv) lies on the ring band [rLo, rHi] using the
     * classic Minecraft circle rule: a cell is on radius r when its distance rounds to r.
     */
    public static boolean onRing(int du, int dv, int rLo, int rHi) {
        int r = (int) Math.round(Math.hypot(du, dv));
        return r >= rLo && r <= rHi;
    }

    /** Square variant of {@link #onRing}: radius is the Chebyshev distance max(|du|, |dv|). */
    public static boolean onSquareRing(int du, int dv, int rLo, int rHi) {
        int r = Math.max(Math.abs(du), Math.abs(dv));
        return r >= rLo && r <= rHi;
    }

    /**
     * {@link #onSquareRing} for a square rotated by {@code theta} around the center: the cell
     * offset is rotated back into the square's local frame, then Chebyshev-tested (rounded —
     * this is what rasterizes a rotated square with jagged edges). Zero rotation matches
     * {@code onSquareRing} exactly.
     */
    public static boolean onRotatedSquareRing(int du, int dv, double theta, int rLo, int rHi) {
        double c = Math.cos(-theta), s = Math.sin(-theta);
        double u = du * c - dv * s;
        double v = du * s + dv * c;
        int r = (int) Math.round(Math.max(Math.abs(u), Math.abs(v)));
        return r >= rLo && r <= rHi;
    }

    /**
     * Slab-method ray/AABB intersection: distance t ≥ 0 along the ray (origin o, direction d)
     * to the box [mn..mx], or -1 when the ray misses or the box is entirely behind. A ray
     * starting inside the box returns 0.
     */
    public static double rayBoxIntersect(double ox, double oy, double oz,
                                         double dx, double dy, double dz,
                                         double mnx, double mny, double mnz,
                                         double mxx, double mxy, double mxz) {
        double tMin = Double.NEGATIVE_INFINITY, tMax = Double.POSITIVE_INFINITY;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz}, mn = {mnx, mny, mnz}, mx = {mxx, mxy, mxz};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-12) {
                if (o[i] < mn[i] || o[i] > mx[i]) return -1;
                continue;
            }
            double t1 = (mn[i] - o[i]) / d[i], t2 = (mx[i] - o[i]) / d[i];
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }
        if (tMax < Math.max(tMin, 0)) return -1;
        return Math.max(tMin, 0);
    }
}
