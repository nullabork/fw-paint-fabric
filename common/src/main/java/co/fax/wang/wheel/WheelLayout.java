package co.fax.wang.wheel;

/**
 * Label-driven ring sizing for the selector wheel, solved once when the wheel opens. Unlike
 * icon wheels the slots carry horizontal text labels, so fixed radii would collide as soon as
 * a long label ("Marker corners") appears. The solver takes every label's pixel width and
 * pushes the rings out until no two labels — nor a label and the center text block — can
 * overlap in any expansion state. Longer labels therefore mean a bigger wheel from the start;
 * nothing resizes while the wheel is open.
 *
 * <p>Two labels are "clear" of each other when their boxes are separated horizontally by
 * {@link #LABEL_GAP} or vertically by the line height + gap. Same-ring and label-vs-center
 * distances all scale linearly with the ring radius, so those minimums are closed-form; child
 * labels against the (already fixed) root labels are not monotone in the child radius, so the
 * outer ring scans outward for the first clear radius.
 *
 * <p>Pure math (no Minecraft classes) so it unit-tests headlessly.
 */
public final class WheelLayout {

    /** Radial thickness of each ring, px. */
    public static final int THICKNESS = 24;
    /** Gap between the root ring's outside and the child ring's inside. */
    static final int RING_GAP = 4;
    /** Padding between the center text block and the hole edge. */
    static final int HOLE_PAD = 8;
    /** Minimum clearance between two labels (and between a label and the center text). */
    static final int LABEL_GAP = 8;
    /** The hole never shrinks below this even with tiny center text. */
    static final int MIN_HOLE = 26;
    /** Sanity cap; beyond this we accept overlap rather than fill the screen. */
    static final int MAX_RADIUS = 400;

    public final int r0In, r0Out, r1In, r1Out;

    private WheelLayout(int r0Mid, int r1Mid) {
        this.r0In = r0Mid - THICKNESS / 2;
        this.r0Out = r0Mid + THICKNESS / 2;
        this.r1In = r1Mid - THICKNESS / 2;
        this.r1Out = r1Mid + THICKNESS / 2;
    }

    /**
     * @param lineHeight  font line height, px
     * @param centerW     widest text the donut hole can show
     * @param centerH     total height of the hole text block
     * @param rootWidths  label pixel widths of the root items, in slot order
     * @param childWidths per root slot, that category's child label widths (null/empty for leaves)
     */
    public static WheelLayout compute(int lineHeight, int centerW, int centerH,
                                      int[] rootWidths, int[][] childWidths) {
        int n = rootWidths.length;
        double[] ux = new double[n], uy = new double[n]; // unit position of each root label
        for (int i = 0; i < n; i++) {
            double a = WheelMath.rootSlotCenter(i, n);
            ux[i] = Math.sin(a);
            uy[i] = -Math.cos(a);
        }

        // Root ring: the hole must fit the center text; then push out until no two root labels,
        // nor a root label and the center block, can collide (all closed-form: linear in R).
        double r0 = Math.max(MIN_HOLE, centerW / 2.0 + HOLE_PAD) + THICKNESS / 2.0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                r0 = Math.max(r0, minApart(ux[i] - ux[j], uy[i] - uy[j],
                        (rootWidths[i] + rootWidths[j]) / 2.0 + LABEL_GAP,
                        lineHeight + LABEL_GAP));
            }
            r0 = Math.max(r0, minApart(ux[i], uy[i],
                    (rootWidths[i] + centerW) / 2.0 + LABEL_GAP,
                    (lineHeight + centerH) / 2.0 + LABEL_GAP));
        }
        int r0Mid = (int) Math.ceil(Math.min(r0, MAX_RADIUS));

        // Child ring: same closed-form pass within each category and against the center text …
        double r1 = r0Mid + THICKNESS + RING_GAP;
        for (int i = 0; i < n; i++) {
            int[] widths = childWidths == null || childWidths[i] == null ? new int[0] : childWidths[i];
            int m = widths.length;
            double parent = WheelMath.rootSlotCenter(i, n);
            double[] cx = new double[m], cy = new double[m];
            for (int k = 0; k < m; k++) {
                double a = WheelMath.childSlotCenter(parent, m, k);
                cx[k] = Math.sin(a);
                cy[k] = -Math.cos(a);
            }
            for (int k = 0; k < m; k++) {
                for (int l = k + 1; l < m; l++) {
                    r1 = Math.max(r1, minApart(cx[k] - cx[l], cy[k] - cy[l],
                            (widths[k] + widths[l]) / 2.0 + LABEL_GAP,
                            lineHeight + LABEL_GAP));
                }
                r1 = Math.max(r1, minApart(cx[k], cy[k],
                        (widths[k] + centerW) / 2.0 + LABEL_GAP,
                        (lineHeight + centerH) / 2.0 + LABEL_GAP));
            }
        }

        // … then, against the now-fixed root label positions, scan outward for the first radius
        // where every child label clears every root label (this distance isn't monotone in r1).
        int r1Mid = (int) Math.ceil(Math.min(r1, MAX_RADIUS));
        while (r1Mid < MAX_RADIUS
                && !clearOfRootLabels(r1Mid, r0Mid, lineHeight, rootWidths, childWidths, ux, uy)) {
            r1Mid++;
        }
        return new WheelLayout(r0Mid, r1Mid);
    }

    /**
     * Smallest radius R at which two labels at {@code R*(x1,y1)} and {@code R*(x2,y2)} are
     * separated by {@code needX} horizontally or {@code needY} vertically (either suffices —
     * horizontal text boxes don't collide when one axis is clear). The deltas are per unit
     * radius; a fixed point (the center block) contributes {@code (0, 0)}.
     */
    private static double minApart(double dxPerR, double dyPerR, double needX, double needY) {
        double byX = Math.abs(dxPerR) < 1e-9 ? Double.MAX_VALUE : needX / Math.abs(dxPerR);
        double byY = Math.abs(dyPerR) < 1e-9 ? Double.MAX_VALUE : needY / Math.abs(dyPerR);
        return Math.min(byX, byY);
    }

    /** True when, at child radius {@code r1}, no child label's box collides with a root label's. */
    private static boolean clearOfRootLabels(int r1, int r0Mid, int lineHeight,
                                             int[] rootWidths, int[][] childWidths,
                                             double[] ux, double[] uy) {
        int n = rootWidths.length;
        for (int i = 0; i < n; i++) {
            int[] widths = childWidths == null || childWidths[i] == null ? new int[0] : childWidths[i];
            double parent = WheelMath.rootSlotCenter(i, n);
            for (int k = 0; k < widths.length; k++) {
                double a = WheelMath.childSlotCenter(parent, widths.length, k);
                double x = Math.sin(a) * r1, y = -Math.cos(a) * r1;
                for (int j = 0; j < n; j++) {
                    double dx = Math.abs(x - ux[j] * r0Mid);
                    double dy = Math.abs(y - uy[j] * r0Mid);
                    if (dx < (widths[k] + rootWidths[j]) / 2.0 + LABEL_GAP
                            && dy < lineHeight + LABEL_GAP) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
