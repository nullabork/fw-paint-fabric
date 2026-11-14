package co.fax.wang.wheel;

import co.fax.wang.wheel.WheelLayout.Arc;
import co.fax.wang.wheel.WheelLayout.RingSpec;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layout's contract: the rings sit exactly {@link WheelLayout#GAP} apart, every item box lies
 * wholly inside its ring band (with padding) at its slot angle, adjacent boxes on a ring don't
 * overlap, and the hole fits the centre text — in every expansion state. Slot positions are
 * rebuilt with the same content-driven slot-width rule the wheel renders with.
 */
class WheelLayoutTest {

    private static final int CENTER_W = 84;
    private static final int CENTER_H = 20;

    /** Roughly the real wheel: Paint / Palette / Place, wrapped two-line labels, an icon ring. */
    private static final int[] ROOT_W = {30, 42, 33};
    private static final int[] ROOT_H = {9, 9, 9};

    private static RingSpec ring(int count, int w, int h) {
        int[] ws = new int[count], hs = new int[count];
        java.util.Arrays.fill(ws, w);
        java.util.Arrays.fill(hs, h);
        return new RingSpec(ws, hs);
    }

    private static final Arc PAINTS = Arc.level1(0, ring(5, 48, 9));
    private static final Arc PALETTES = Arc.level1(1, ring(6, 48, 27));   // two lines plus "2 missing"
    private static final Arc SOLID = Arc.level1(1, ring(5, 54, 19));
    private static final Arc PLACE = Arc.level1(2, ring(6, 48, 19));      // Blocks … Off, More…
    private static final List<Arc> ARCS = List.of(PAINTS, PALETTES, SOLID, PLACE,
            Arc.level2(SOLID, 0, ring(30, 16, 25)),
            Arc.level2(PLACE, 0, ring(5, 48, 19)),
            Arc.level2(PLACE, 1, ring(2, 48, 9)),
            Arc.level2(PLACE, 2, ring(3, 40, 9)),
            Arc.level2(PLACE, 3, ring(4, 48, 19)));

    private static WheelLayout layout() {
        return WheelLayout.compute(CENTER_W, CENTER_H, ROOT_W, ROOT_H, ARCS);
    }

    @Test
    void ringsSitAConstantGapApart() {
        WheelLayout l = layout();
        assertTrue(l.r0In > 0);
        assertTrue(l.r0Out - l.r0In >= WheelLayout.MIN_THICKNESS);
        assertEquals(WheelLayout.GAP, l.r1In - l.r0Out);
        assertEquals(WheelLayout.GAP, l.r2In - l.r1Out);
        assertTrue(l.r1Out - l.r1In >= WheelLayout.MIN_THICKNESS);
        assertTrue(l.r2Out - l.r2In >= WheelLayout.MIN_THICKNESS);
        assertTrue(l.hasLevel2);
        assertEquals(l.r2Out, l.maxRadius());
    }

    @Test
    void reachBoxCoversTheRootAndStaysInsideTheOuterRing() {
        WheelLayout l = layout();
        for (int e : new int[] {l.up, l.down, l.left, l.right}) {
            assertTrue(e >= l.r0Out, "the root ring shows in every direction");
            assertTrue(e <= l.maxRadius(), "nothing reaches past the outermost ring");
        }
    }

    @Test
    void holeFitsCenterText() {
        WheelLayout l = layout();
        assertTrue(l.r0In >= Math.hypot(CENTER_W / 2.0, CENTER_H / 2.0),
                "the centre text's corners must sit inside the hole");
    }

    @Test
    void widerLabelsMakeThickerRings() {
        WheelLayout narrow = WheelLayout.compute(CENTER_W, CENTER_H, ROOT_W, ROOT_H,
                List.of(Arc.level1(1, ring(4, 30, 9))));
        WheelLayout wide = WheelLayout.compute(CENTER_W, CENTER_H, ROOT_W, ROOT_H,
                List.of(Arc.level1(1, ring(4, 64, 19))));
        assertTrue(wide.r1Out - wide.r1In > narrow.r1Out - narrow.r1In);
        assertEquals(narrow.r1In, wide.r1In, "the inner rings don't move");
    }

    @Test
    void iconRingStaysThin() {
        WheelLayout l = WheelLayout.compute(CENTER_W, CENTER_H, ROOT_W, ROOT_H,
                List.of(Arc.level1(1, ring(30, 16, 16))));
        assertTrue(l.r1Out - l.r1In <= 16 + 2 * WheelLayout.PAD + 12,
                "a ring of 16px icons should stay thin, was " + (l.r1Out - l.r1In));
    }

    @Test
    void everyBoxSitsInsideItsBandWithoutTouchingItsNeighbours() {
        WheelLayout l = layout();
        double mid0 = (l.r0In + l.r0Out) / 2.0, mid1 = (l.r1In + l.r1Out) / 2.0, mid2 = (l.r2In + l.r2Out) / 2.0;
        int n = ROOT_W.length;
        for (int i = 0; i < n; i++) {
            assertInside(WheelMath.rootSlotCenter(i, n), mid0, ROOT_W[i], ROOT_H[i], l.r0In, l.r0Out, "root " + i);
        }
        for (Arc arc : ARCS) {
            boolean two = arc.parent() != null;
            double around = WheelLayout.parentAngle(arc, n, mid1);
            checkArc(arc.spec(), around, two ? mid2 : mid1, two ? l.r2In : l.r1In, two ? l.r2Out : l.r1Out,
                    (two ? "level2 " : "level1 ") + arc.rootSlot() + "/" + arc.childIndex());
        }
    }

    private static void checkArc(RingSpec spec, double parentAngle, double mid, int rIn, int rOut, String what) {
        double w = WheelMath.slotWidthFor(spec.span(), mid);
        int capacity = WheelMath.arcCapacity(w);
        if (spec.count() > capacity) spec = ring(capacity, spec.maxItemWidth(), spec.maxItemHeight());
        int slots = spec.count();
        double[][] boxes = new double[slots][];
        for (int k = 0; k < slots; k++) {
            double a = WheelMath.arcSlotCenter(parentAngle, slots, w, k);
            assertInside(a, mid, spec.widths()[k], spec.heights()[k], rIn, rOut, what + " slot " + k);
            boxes[k] = new double[] {Math.sin(a) * mid, -Math.cos(a) * mid};
        }
        for (int k = 0; k + 1 < slots; k++) {
            double[] p = boxes[k], q = boxes[k + 1];
            boolean overlap = Math.abs(p[0] - q[0]) < spec.maxItemWidth()
                    && Math.abs(p[1] - q[1]) < spec.maxItemHeight();
            assertTrue(!overlap, what + " slots " + k + "," + (k + 1) + " overlap");
        }
    }

    /** The box's nearest and furthest points from the centre lie inside [rIn, rOut]. */
    private static void assertInside(double angle, double mid, double w, double h, int rIn, int rOut, String what) {
        double x = Math.sin(angle) * mid, y = -Math.cos(angle) * mid;
        double nx = Math.max(Math.abs(x) - w / 2, 0), ny = Math.max(Math.abs(y) - h / 2, 0);
        double near = Math.hypot(nx, ny);
        double far = Math.hypot(Math.abs(x) + w / 2, Math.abs(y) + h / 2);
        // The radial projection ignores the slight bulge of a corner off the radius; allow 2px of it.
        assertTrue(near >= rIn, what + ": reaches inside the ring (" + near + " < " + rIn + ")");
        assertTrue(far <= rOut + 2, what + ": reaches outside the ring (" + far + " > " + rOut + ")");
    }
}
