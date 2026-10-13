package co.fax.wang.shape;

/**
 * The cross-section swept along a line marker to make its tube. NONE = a bare line, which
 * selects just the blocks the curve passes through. Profiles are tested in the line's local
 * (u, v) plane at radius r; the polygon ones (triangle, star) are point-in-polygon tests on their
 * outline scaled to r.
 */
public enum LineProfile {
    NONE, CIRCLE, SQUARE, TRIANGLE, STAR;

    /** Star inner-vertex radius as a fraction of the outer radius. */
    static final double STAR_INNER = 0.45;

    /** True when local point (u, v) lies inside this profile at radius r. */
    public boolean inside(double u, double v, double r) {
        double e = r + 0.5; // cell centres within half a block of the outline count
        return switch (this) {
            case NONE -> false;
            case CIRCLE -> u * u + v * v <= e * e;
            case SQUARE -> Math.abs(u) <= e && Math.abs(v) <= e;
            case TRIANGLE, STAR -> insidePolygon(outline(e), u, v);
        };
    }

    /**
     * The outline as (u, v) vertex pairs at radius r, pointing along +u (the line's normal —
     * world-up for a level line): 3 corners for a
     * triangle, 10 alternating outer/inner points for a 5-point star, 4 for a square, 32 for a
     * circle. Used for the inside test and for drawing the button icons.
     */
    public double[][] outline(double r) {
        int n;
        switch (this) {
            case TRIANGLE -> n = 3;
            case STAR -> n = 10;
            case SQUARE -> {
                return new double[][]{{-r, -r}, {r, -r}, {r, r}, {-r, r}};
            }
            case CIRCLE -> n = 32;
            default -> {
                return new double[0][];
            }
        }
        double[][] pts = new double[n][];
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            double rr = (this == STAR && i % 2 == 1) ? r * STAR_INNER : r;
            pts[i] = new double[]{Math.cos(a) * rr, Math.sin(a) * rr};
        }
        return pts;
    }

    static boolean insidePolygon(double[][] poly, double u, double v) {
        boolean in = false;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            double ui = poly[i][0], vi = poly[i][1], uj = poly[j][0], vj = poly[j][1];
            if ((vi > v) != (vj > v) && u < (uj - ui) * (v - vi) / (vj - vi) + ui) in = !in;
        }
        return in;
    }
}
