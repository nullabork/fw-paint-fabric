package co.fax.wang.wheel;

import java.util.List;

/**
 * Ring sizing for the wheel, solved once when it opens. The rule is containment: every ring is
 * thick enough that each item's box (its wrapped label, or icon, plus any "N missing" note) sits
 * wholly inside the ring band at the item's angle, with {@link #PAD} to spare at both edges. So
 * text never crosses into the gap or a neighbouring ring, and the rings can sit a constant
 * {@link #GAP} apart: the gaps look the same everywhere, in every expansion state.
 *
 * <p>An item box at angle {@code a} reaches {@code hw·|sin a| + hh·|cos a|} out from its centre
 * along the radius (its projection onto the radial direction), so a wide label at 3 o'clock
 * needs a thicker ring than the same label at 12. Each item is measured at the angle it actually
 * lands on (a scrolling carousel's items at every visible slot), and each level's thickness is
 * the most any of its arcs need, never below {@link #MIN_THICKNESS}. Adjacent slots are spaced by
 * the content-driven slot width ({@link WheelMath#slotWidthFor}).
 *
 * <p>Three levels: the root (evenly divided), level 1 (the arcs root categories fan out — one
 * {@link Arc} per variant, e.g. each paint type's Palette ring) and level 2 (arcs a level-1
 * category fans out around its slot). Pure math (no Minecraft classes) so it unit-tests headlessly.
 */
public final class WheelLayout {

    /** Space between one ring's outside and the next ring's inside. Constant everywhere. */
    public static final int GAP = 5;
    /** Clearance between an item's box and its ring's inner and outer edges. */
    public static final int PAD = 3;
    /** Thinnest a ring may be (a 16px icon plus padding). */
    public static final int MIN_THICKNESS = 24;
    /** Padding between the centre text block's corners and the hole edge. */
    static final int HOLE_PAD = 4;
    /** The hole never shrinks below this even with tiny centre text. */
    static final int MIN_HOLE = 24;

    /** One ring's items, in order: each box's width and height. */
    public record RingSpec(int[] widths, int[] heights) {
        public int count() {
            return widths.length;
        }

        public int maxItemWidth() {
            int m = 0;
            for (int w : widths) m = Math.max(m, w);
            return m;
        }

        public int maxItemHeight() {
            int m = 0;
            for (int h : heights) m = Math.max(m, h);
            return m;
        }

        /** What a slot must hold along the circle: the larger side of the biggest box. */
        public int span() {
            return Math.max(maxItemWidth(), maxItemHeight());
        }
    }

    /**
     * One arc the wheel can show: fanned from root slot {@code rootSlot} (level 1, {@code parent}
     * null) or from child {@code childIndex} of the level-1 arc {@code parent} (level 2).
     */
    public record Arc(int rootSlot, RingSpec spec, Arc parent, int childIndex) {
        public static Arc level1(int rootSlot, RingSpec spec) {
            return new Arc(rootSlot, spec, null, -1);
        }

        public static Arc level2(Arc parent, int childIndex, RingSpec spec) {
            return new Arc(parent.rootSlot, spec, parent, childIndex);
        }
    }

    public final int r0In, r0Out, r1In, r1Out, r2In, r2Out;
    /** False when no arc reaches level 2 (the wheel then ends at r1Out). */
    public final boolean hasLevel2;
    /**
     * How far the wheel can reach from its centre up, down, left and right, over every arc it can
     * fan out. Arcs fan towards their parent, so these are usually well under {@link #maxRadius}
     * on most sides — the screen fits this box, not a full circle.
     */
    public final int up, down, left, right;

    private WheelLayout(int r0In, int r0Out, int r1In, int r1Out, int r2In, int r2Out, boolean hasLevel2,
                        int[] extent) {
        this.r0In = r0In;
        this.r0Out = r0Out;
        this.r1In = r1In;
        this.r1Out = r1Out;
        this.r2In = r2In;
        this.r2Out = r2Out;
        this.hasLevel2 = hasLevel2;
        this.up = extent[0];
        this.down = extent[1];
        this.left = extent[2];
        this.right = extent[3];
    }

    /** Outermost radius the wheel can reach in any expansion state. */
    public int maxRadius() {
        return hasLevel2 ? r2Out : r1Out;
    }

    /**
     * @param centerW widest text the donut hole shows
     * @param centerH height of the hole's text block
     * @param rootW   root item box widths, in slot order
     * @param rootH   root item box heights
     * @param arcs    every arc the wheel can fan out, level 1 and level 2
     */
    public static WheelLayout compute(int centerW, int centerH, int[] rootW, int[] rootH, List<Arc> arcs) {
        int n = rootW.length;
        int r0In = (int) Math.ceil(Math.max(MIN_HOLE, Math.hypot(centerW / 2.0, centerH / 2.0) + HOLE_PAD));
        double reach0 = 0;
        for (int i = 0; i < n; i++) {
            reach0 = Math.max(reach0, reach(WheelMath.rootSlotCenter(i, n), rootW[i], rootH[i]));
        }
        int r0Out = r0In + thickness(reach0);

        // Each level: slot angles depend on the ring's radius, which depends on its thickness —
        // iterate to a fixed point (it settles in a few rounds).
        int r1In = r0Out + GAP;
        int t1 = solveThickness(r1In, arcs, n, false, 0);
        int r1Out = r1In + t1;
        double mid1 = (r1In + r1Out) / 2.0;
        boolean any2 = arcs.stream().anyMatch(a -> a.parent() != null && a.spec().count() > 0);
        int r2In = r1Out + GAP;
        int t2 = any2 ? solveThickness(r2In, arcs, n, true, mid1) : MIN_THICKNESS;
        int r2Out = r2In + t2;

        // Reach in each direction: the root circle, then every arc's outer edge.
        double[] ext = {r0Out, r0Out, r0Out, r0Out}; // up, down, left, right
        double mid2 = (r2In + r2Out) / 2.0;
        for (Arc arc : arcs) {
            if (arc.spec().count() == 0) continue;
            boolean two = arc.parent() != null;
            double mid = two ? mid2 : mid1;
            double w = WheelMath.slotWidthFor(arc.spec().span(), mid);
            int slots = Math.min(arc.spec().count(), WheelMath.arcCapacity(w));
            double span = Math.min(WheelMath.TAU, slots * w);
            double start = parentAngle(arc, n, mid1) - span / 2;
            int rOut = two ? r2Out : r1Out;
            for (int k = 0; k <= 48; k++) {
                double a = start + span * k / 48;
                double x = Math.sin(a) * rOut, y = -Math.cos(a) * rOut;
                ext[0] = Math.max(ext[0], -y);
                ext[1] = Math.max(ext[1], y);
                ext[2] = Math.max(ext[2], -x);
                ext[3] = Math.max(ext[3], x);
            }
        }
        int[] extent = new int[4];
        for (int i = 0; i < 4; i++) extent[i] = (int) Math.ceil(ext[i]);
        return new WheelLayout(r0In, r0Out, r1In, r1Out, r2In, r2Out, any2, extent);
    }

    private static int solveThickness(int rIn, List<Arc> arcs, int n, boolean level2, double mid1) {
        int t = MIN_THICKNESS;
        for (int round = 0; round < 20; round++) {
            double mid = rIn + t / 2.0;
            double reach = 0;
            for (Arc arc : arcs) {
                if ((arc.parent() != null) != level2 || arc.spec().count() == 0) continue;
                reach = Math.max(reach, arcReach(arc.spec(), parentAngle(arc, n, mid1), mid));
            }
            int need = thickness(reach);
            if (need <= t) return t;
            t = need;
        }
        return t;
    }

    /** The angle an arc fans around: its root slot, or its parent arc's child slot. */
    static double parentAngle(Arc arc, int rootCount, double mid1) {
        double rootA = WheelMath.rootSlotCenter(arc.rootSlot(), rootCount);
        if (arc.parent() == null) return rootA;
        return arcAngleOf(arc.parent().spec(), rootA, mid1, arc.childIndex());
    }

    /** Ring thickness that holds items reaching {@code reach} either side of the mid radius. */
    private static int thickness(double reach) {
        return Math.max(MIN_THICKNESS, (int) Math.ceil(2 * (reach + PAD)));
    }

    /** How far a {@code w}×{@code h} box centred on the ring reaches along the radius at {@code angle}. */
    static double reach(double angle, double w, double h) {
        return w / 2.0 * Math.abs(Math.sin(angle)) + h / 2.0 * Math.abs(Math.cos(angle));
    }

    /**
     * The furthest any item of an arc reaches along the radius. A fixed arc measures each item at
     * its own slot; a carousel (items scroll through the slots) measures every item at every slot.
     */
    private static double arcReach(RingSpec spec, double parentAngle, double radius) {
        double w = WheelMath.slotWidthFor(spec.span(), radius);
        int capacity = WheelMath.arcCapacity(w);
        boolean carousel = spec.count() > capacity;
        int slots = carousel ? capacity : spec.count();
        double out = 0;
        for (int k = 0; k < slots; k++) {
            double a = WheelMath.arcSlotCenter(parentAngle, slots, w, k);
            out = carousel ? Math.max(out, reach(a, spec.maxItemWidth(), spec.maxItemHeight()))
                    : Math.max(out, reach(a, spec.widths()[k], spec.heights()[k]));
        }
        return out;
    }

    /** Slot-centre angle of child {@code j} of an arc with {@code spec} at {@code radius}. */
    static double arcAngleOf(RingSpec spec, double parentAngle, double radius, int j) {
        double w = WheelMath.slotWidthFor(spec.span(), radius);
        int capacity = WheelMath.arcCapacity(w);
        boolean carousel = spec.count() > capacity;
        int slots = carousel ? capacity : spec.count();
        int slot = carousel ? Math.min(j + 1, slots - 2) : Math.min(j, slots - 1);
        return WheelMath.arcSlotCenter(parentAngle, slots, w, slot);
    }
}
