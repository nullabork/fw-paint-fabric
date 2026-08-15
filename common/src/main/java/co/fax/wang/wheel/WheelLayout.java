package co.fax.wang.wheel;

import java.util.ArrayList;
import java.util.List;

/**
 * Label-driven ring sizing for the selector wheel, solved once when the wheel opens. Slots are
 * content-sized ({@link WheelMath#slotWidthFor}): adjacent items on a ring can never collide
 * because each slot's arc already spans its ring's widest item — the solver's job is the rest:
 * the hole must fit the center text, and every ring must sit far enough out that its items
 * clear the center block, the inner rings' labels, and each other across the circle, in any
 * expansion state. Longer labels therefore mean a bigger wheel from the start; nothing
 * resizes while the wheel is open.
 *
 * <p>Three rings: the root (evenly divided), the level-1 arc (children of a root category),
 * and the level-2 arc (children of a level-1 category, fanned around that slot's angle). Each
 * level's radius is shared: the max any of its categories needs.
 *
 * <p>Pure math (no Minecraft classes) so it unit-tests headlessly.
 */
public final class WheelLayout {

    /** Radial thickness of each ring, px. */
    public static final int THICKNESS = 24;
    /** Gap between one ring's outside and the next ring's inside. */
    static final int RING_GAP = 4;
    /** Padding between the center text block and the hole edge. */
    static final int HOLE_PAD = 8;
    /** Minimum clearance between two labels (and between a label and the center text). */
    static final int LABEL_GAP = 8;
    /** The hole never shrinks below this even with tiny center text. */
    static final int MIN_HOLE = 26;
    /** Sanity cap; beyond this we accept overlap rather than fill the screen. */
    static final int MAX_RADIUS = 400;

    /** One ring's content summary: how many items, and how wide the widest one is. */
    public record RingSpec(int count, int maxItemWidth) {}

    public final int r0In, r0Out, r1In, r1Out, r2In, r2Out;

    private WheelLayout(int r0Mid, int r1Mid, int r2Mid) {
        this.r0In = r0Mid - THICKNESS / 2;
        this.r0Out = r0Mid + THICKNESS / 2;
        this.r1In = r1Mid - THICKNESS / 2;
        this.r1Out = r1Mid + THICKNESS / 2;
        this.r2In = r2Mid - THICKNESS / 2;
        this.r2Out = r2Mid + THICKNESS / 2;
    }

    /**
     * @param lineHeight font line height, px
     * @param centerW    widest text the donut hole can show
     * @param centerH    total height of the hole text block
     * @param rootWidths label pixel widths of the root items, in slot order
     * @param level1     per root slot, that category's ring content (null for leaves)
     * @param level2     per root slot, per child index, that sub-category's ring content
     *                   (null for leaves / no level-2 ring)
     */
    public static WheelLayout compute(int lineHeight, int centerW, int centerH,
                                      int[] rootWidths, RingSpec[] level1, RingSpec[][] level2) {
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

        // Fixed root-label boxes for the outer rings to clear.
        List<double[]> rootBoxes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rootBoxes.add(new double[] {ux[i] * r0Mid, uy[i] * r0Mid, rootWidths[i] / 2.0});
        }

        // Level-1 ring: each category solves at its own adaptive slot width; radius is shared.
        int r1Mid = r0Mid + THICKNESS + RING_GAP;
        for (int i = 0; i < n; i++) {
            RingSpec spec = level1 == null ? null : level1[i];
            if (spec == null || spec.count() == 0) continue;
            r1Mid = Math.max(r1Mid, solveRing(r0Mid + THICKNESS + RING_GAP, spec,
                    WheelMath.rootSlotCenter(i, n), lineHeight, centerW, centerH, rootBoxes));
        }

        // Level-2 ring: cleared against the root labels AND its own category's level-1 labels
        // (a level-2 group only ever shows alongside those).
        int r2Mid = r1Mid + THICKNESS + RING_GAP;
        for (int i = 0; i < n; i++) {
            RingSpec parentSpec = level1 == null ? null : level1[i];
            RingSpec[] subs = level2 == null ? null : level2[i];
            if (parentSpec == null || subs == null) continue;
            double rootA = WheelMath.rootSlotCenter(i, n);
            List<double[]> fixed = new ArrayList<>(rootBoxes);
            fixed.addAll(arcBoxes(parentSpec, rootA, r1Mid));
            for (int j = 0; j < subs.length; j++) {
                RingSpec sub = subs[j];
                if (sub == null || sub.count() == 0) continue;
                double parent = arcAngleOf(parentSpec, rootA, r1Mid, j);
                r2Mid = Math.max(r2Mid, solveRing(r1Mid + THICKNESS + RING_GAP, sub,
                        parent, lineHeight, centerW, centerH, fixed));
            }
        }
        return new WheelLayout(r0Mid, r1Mid, r2Mid);
    }

    /** The slot-center angle of child {@code j} of an arc with {@code spec} at {@code radius}. */
    private static double arcAngleOf(RingSpec spec, double parentAngle, int radius, int j) {
        double w = WheelMath.slotWidthFor(spec.maxItemWidth(), radius);
        int slots = Math.min(spec.count(), WheelMath.arcCapacity(w));
        return WheelMath.arcSlotCenter(parentAngle, slots, w, Math.min(j, slots - 1));
    }

    /** {@code {x, y, halfWidth}} boxes of an arc's items at {@code radius}. */
    private static List<double[]> arcBoxes(RingSpec spec, double parentAngle, int radius) {
        List<double[]> out = new ArrayList<>();
        double w = WheelMath.slotWidthFor(spec.maxItemWidth(), radius);
        int slots = Math.min(spec.count(), WheelMath.arcCapacity(w));
        for (int k = 0; k < slots; k++) {
            double a = WheelMath.arcSlotCenter(parentAngle, slots, w, k);
            out.add(new double[] {Math.sin(a) * radius, -Math.cos(a) * radius,
                    spec.maxItemWidth() / 2.0});
        }
        return out;
    }

    /**
     * Smallest radius ≥ {@code rLo} where the arc's items (at their content-driven slot width
     * for that radius) clear the center block and the fixed inner labels. Items on the SAME
     * ring never need checking: content-sized slots space adjacent centers by the widest item
     * plus {@link WheelMath#SLOT_ARC_PAD} along the circle (and non-adjacent pairs sit even
     * further apart), which no two item boxes can bridge in both axes at once.
     */
    private static int solveRing(int rLo, RingSpec spec, double parentAngle,
                                 int lineHeight, int centerW, int centerH, List<double[]> fixed) {
        double halfW = spec.maxItemWidth() / 2.0;
        for (int r = rLo; r < MAX_RADIUS; r++) {
            double w = WheelMath.slotWidthFor(spec.maxItemWidth(), r);
            int slots = Math.min(spec.count(), WheelMath.arcCapacity(w));
            boolean ok = true;
            for (int k = 0; k < slots && ok; k++) {
                double a = WheelMath.arcSlotCenter(parentAngle, slots, w, k);
                double x = Math.sin(a) * r, y = -Math.cos(a) * r;
                if (Math.abs(x) < halfW + centerW / 2.0 + LABEL_GAP
                        && Math.abs(y) < (lineHeight + centerH) / 2.0 + LABEL_GAP) {
                    ok = false;
                    break;
                }
                for (double[] f : fixed) {
                    if (Math.abs(x - f[0]) < halfW + f[2] + LABEL_GAP
                            && Math.abs(y - f[1]) < lineHeight + LABEL_GAP) {
                        ok = false;
                        break;
                    }
                }
            }
            if (ok) return r;
        }
        return MAX_RADIUS;
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
}
