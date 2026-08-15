package co.fax.wang.wheel;

import co.fax.wang.wheel.WheelLayout.RingSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layout's contract: rings are ordered and non-overlapping, and at the solved radii no two
 * item boxes (same ring, cross ring, or item vs the center text block) can collide in any
 * expansion state. The collision check reconstructs slot positions with the same content-driven
 * slot-width rule the wheel renders with, and rect-tests all pairs.
 */
class WheelLayoutTest {

    private static final int LINE_H = 9;
    private static final int CENTER_W = 84;
    private static final int CENTER_H = 2 * LINE_H + 2;

    /** Roughly the real wheel: Paint / Place / Palette, Place fanning three mode groups. */
    private static final int[] ROOT_W = {30, 33, 42};
    private static final RingSpec[] LEVEL1 = {
            new RingSpec(4, 48),    // paints
            new RingSpec(3, 84),    // Blocks / Markers / Shape markers
            new RingSpec(14, 84),   // palette (merged variants)
    };
    private static final RingSpec[][] LEVEL2 = {
            {null, null, null, null},
            {new RingSpec(4, 57), new RingSpec(2, 66), new RingSpec(3, 81)},
            {new RingSpec(30, 16), null, null, null, null, null, null, null, null, null, null, null, null, null},
    };

    private static WheelLayout layout() {
        return WheelLayout.compute(LINE_H, CENTER_W, CENTER_H, ROOT_W, LEVEL1, LEVEL2);
    }

    @Test
    void ringsAreOrderedAndDisjoint() {
        WheelLayout l = layout();
        assertTrue(l.r0In > 0);
        assertTrue(l.r0Out > l.r0In);
        assertTrue(l.r1In > l.r0Out, "level-1 ring must start outside the root ring");
        assertTrue(l.r1Out > l.r1In);
        assertTrue(l.r2In > l.r1Out, "level-2 ring must start outside the level-1 ring");
        assertTrue(l.r2Out > l.r2In);
    }

    @Test
    void holeFitsCenterText() {
        assertTrue(layout().r0In >= CENTER_W / 2, "hole radius must cover half the center text width");
    }

    @Test
    void longerLabelsGrowTheWheelFromTheStart() {
        WheelLayout small = WheelLayout.compute(LINE_H, 40, CENTER_H,
                new int[] {30, 30, 30},
                new RingSpec[] {new RingSpec(2, 30), new RingSpec(2, 30), null},
                new RingSpec[][] {{null, null}, {null, null}, null});
        WheelLayout big = WheelLayout.compute(LINE_H, 160, CENTER_H,
                new int[] {120, 140, 30},
                new RingSpec[] {new RingSpec(2, 150), new RingSpec(2, 30), null},
                new RingSpec[][] {{null, null}, {null, null}, null});
        assertTrue(big.r0Out > small.r0Out);
        assertTrue(big.r1Out > small.r1Out);
    }

    @Test
    void blockIconRingSitsAtTheNormalRingGap() {
        // Regression: the icon ring's constant adjacent spacing used to fail a radius-independent
        // same-ring check, driving the ring to the sanity cap (visibly miles away in-game). The
        // remaining gap is only what clearing the inner ring's widest sideways-protruding label
        // genuinely needs (~half its width), never a runaway.
        WheelLayout l = layout();
        assertTrue(l.r2In - l.r1Out <= LEVEL1[2].maxItemWidth(),
                "level-2 icon ring should hug the level-1 ring, gap was " + (l.r2In - l.r1Out));
    }

    @Test
    void iconRingsDontInflateTheRadius() {
        // A 30-icon ring should not need a materially bigger radius than a 4-label ring:
        // narrow slots absorb the count.
        WheelLayout icons = WheelLayout.compute(LINE_H, 40, CENTER_H,
                new int[] {30, 30, 30},
                new RingSpec[] {new RingSpec(30, 16), null, null},
                new RingSpec[][] {null, null, null});
        assertTrue(icons.r1Out < 160, "icon ring should stay compact, was " + icons.r1Out);
    }

    @Test
    void noBoxesCollideAtTheSolvedRadiiInAnyExpansionState() {
        WheelLayout l = layout();
        double r0 = (l.r0In + l.r0Out) / 2.0;
        int r1 = (l.r1In + l.r1Out) / 2;
        int r2 = (l.r2In + l.r2Out) / 2;
        int n = ROOT_W.length;

        for (int expanded = 0; expanded < n; expanded++) {
            RingSpec spec1 = LEVEL1[expanded];
            if (spec1 == null) continue;
            RingSpec[] subs = LEVEL2[expanded];
            int groups = subs == null ? 0 : subs.length;
            for (int sub = -1; sub < groups; sub++) {
                RingSpec spec2 = sub >= 0 ? subs[sub] : null;
                if (sub >= 0 && spec2 == null) continue;
                java.util.List<double[]> boxes = new java.util.ArrayList<>(); // {x, y, w, h}
                boxes.add(new double[] {0, 0, CENTER_W, CENTER_H});           // center text block
                for (int i = 0; i < n; i++) {
                    double a = WheelMath.rootSlotCenter(i, n);
                    boxes.add(new double[] {Math.sin(a) * r0, -Math.cos(a) * r0, ROOT_W[i], LINE_H});
                }
                double rootA = WheelMath.rootSlotCenter(expanded, n);
                addArc(boxes, spec1, rootA, r1);
                if (spec2 != null) {
                    double w1 = WheelMath.slotWidthFor(spec1.maxItemWidth(), r1);
                    int slots1 = Math.min(spec1.count(), WheelMath.arcCapacity(w1));
                    double parent = WheelMath.arcSlotCenter(rootA, slots1, w1, Math.min(sub, slots1 - 1));
                    addArc(boxes, spec2, parent, r2);
                }
                for (int i = 0; i < boxes.size(); i++) {
                    for (int j = i + 1; j < boxes.size(); j++) {
                        double[] a = boxes.get(i), b = boxes.get(j);
                        boolean overlap = Math.abs(a[0] - b[0]) < (a[2] + b[2]) / 2
                                && Math.abs(a[1] - b[1]) < (a[3] + b[3]) / 2;
                        assertTrue(!overlap, "expanded=" + expanded + " sub=" + sub
                                + " boxes " + i + "," + j + " collide");
                    }
                }
            }
        }
    }

    /** Slot-center boxes of one arc at {@code radius}, using the render-time slot-width rule. */
    private static void addArc(java.util.List<double[]> boxes, RingSpec spec, double parentAngle, int radius) {
        double w = WheelMath.slotWidthFor(spec.maxItemWidth(), radius);
        int slots = Math.min(spec.count(), WheelMath.arcCapacity(w));
        for (int k = 0; k < slots; k++) {
            double a = WheelMath.arcSlotCenter(parentAngle, slots, w, k);
            boxes.add(new double[] {Math.sin(a) * radius, -Math.cos(a) * radius,
                    spec.maxItemWidth(), LINE_H});
        }
    }
}
