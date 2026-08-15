package co.fax.wang.wheel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layout's contract: rings are ordered and non-overlapping, and at the solved radii no two
 * label boxes (same ring, cross ring, or label vs the center text block) can collide in any
 * expansion state. The collision check here is independent of the solver's internals — it
 * places every label box explicitly and rect-tests all pairs.
 */
class WheelLayoutTest {

    private static final int LINE_H = 9;

    /** Roughly the real wheel: Paint, Markers, Placement, Palette, and the Disabled leaf. */
    private static final int[] ROOT_W = {30, 45, 57, 42, 51};
    private static final int[][] CHILD_W = {
            {30, 48, 42, 33},               // Solid, Gradient, Pattern, Noise
            {39, 84, 66},                   // Marker, Marker corners, Marker draw
            {36, 27, 57, 39},               // Single, Face, Face perp, 3D Fill
            {6, 84, 84, 84, 84, 84, 6},     // palette carousel: arrows + widest-item slots
            {},                             // Disabled leaf
    };

    @Test
    void ringsAreOrderedAndDisjoint() {
        WheelLayout l = WheelLayout.compute(LINE_H, 84, 2 * LINE_H + 2, ROOT_W, CHILD_W);
        assertTrue(l.r0In > 0);
        assertTrue(l.r0Out > l.r0In);
        assertTrue(l.r1In > l.r0Out, "child ring must start outside the root ring");
        assertTrue(l.r1Out > l.r1In);
    }

    @Test
    void holeFitsCenterText() {
        int centerW = 84;
        WheelLayout l = WheelLayout.compute(LINE_H, centerW, 2 * LINE_H + 2, ROOT_W, CHILD_W);
        assertTrue(l.r0In >= centerW / 2, "hole radius must cover half the center text width");
    }

    @Test
    void longerLabelsGrowTheWheelFromTheStart() {
        WheelLayout small = WheelLayout.compute(LINE_H, 40, 2 * LINE_H + 2,
                new int[] {30, 30, 30}, new int[][] {{30, 30}, {30, 30}, {}});
        WheelLayout big = WheelLayout.compute(LINE_H, 160, 2 * LINE_H + 2,
                new int[] {120, 140, 30}, new int[][] {{130, 150}, {30, 30}, {}});
        assertTrue(big.r0Out > small.r0Out);
        assertTrue(big.r1Out > small.r1Out);
    }

    @Test
    void noLabelBoxesCollideAtTheSolvedRadii() {
        WheelLayout l = WheelLayout.compute(LINE_H, 84, 2 * LINE_H + 2, ROOT_W, CHILD_W);
        double r0 = (l.r0In + l.r0Out) / 2.0;
        double r1 = (l.r1In + l.r1Out) / 2.0;
        int n = ROOT_W.length;

        // One expansion state at a time: root labels + center block + one category's children.
        for (int expanded = 0; expanded < n; expanded++) {
            java.util.List<double[]> boxes = new java.util.ArrayList<>(); // {x, y, w, h}
            boxes.add(new double[] {0, 0, 84, 2 * LINE_H + 2});           // center text block
            for (int i = 0; i < n; i++) {
                double a = WheelMath.rootSlotCenter(i, n);
                boxes.add(new double[] {Math.sin(a) * r0, -Math.cos(a) * r0, ROOT_W[i], LINE_H});
            }
            for (int k = 0; k < CHILD_W[expanded].length; k++) {
                double a = WheelMath.childSlotCenter(
                        WheelMath.rootSlotCenter(expanded, n), CHILD_W[expanded].length, k);
                boxes.add(new double[] {Math.sin(a) * r1, -Math.cos(a) * r1,
                        CHILD_W[expanded][k], LINE_H});
            }
            for (int i = 0; i < boxes.size(); i++) {
                for (int j = i + 1; j < boxes.size(); j++) {
                    double[] a = boxes.get(i), b = boxes.get(j);
                    boolean overlap = Math.abs(a[0] - b[0]) < (a[2] + b[2]) / 2
                            && Math.abs(a[1] - b[1]) < (a[3] + b[3]) / 2;
                    assertTrue(!overlap, "expanded=" + expanded + " boxes " + i + "," + j + " collide");
                }
            }
        }
    }
}
