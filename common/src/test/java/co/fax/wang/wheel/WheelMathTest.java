package co.fax.wang.wheel;

import org.junit.jupiter.api.Test;

import static co.fax.wang.wheel.WheelMath.TAU;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WheelMathTest {

    private static final double EPS = 1e-9;

    @Test
    void angleOfCardinalDirections() {
        assertEquals(0, WheelMath.angleOf(0, -1), EPS);            // up
        assertEquals(Math.PI / 2, WheelMath.angleOf(1, 0), EPS);   // right
        assertEquals(Math.PI, WheelMath.angleOf(0, 1), EPS);       // down
        assertEquals(3 * Math.PI / 2, WheelMath.angleOf(-1, 0), EPS); // left
    }

    @Test
    void normalizeWrapsBothDirections() {
        assertEquals(0.5, WheelMath.normalize(0.5 + TAU), EPS);
        assertEquals(TAU - 0.5, WheelMath.normalize(-0.5), EPS);
        assertEquals(0, WheelMath.normalize(TAU), EPS);
    }

    @Test
    void deltaIsShortestSignedDifference() {
        assertEquals(0.2, WheelMath.delta(0.1, TAU - 0.1), EPS);
        assertEquals(-0.2, WheelMath.delta(TAU - 0.1, 0.1), EPS);
        assertEquals(Math.PI, WheelMath.delta(Math.PI, 0), EPS);
    }

    @Test
    void rootSlotsForTwoItems() {
        // Slot 0 centered at top, slot 1 centered at bottom; boundaries at 3 and 9 o'clock.
        assertEquals(0, WheelMath.rootSlot(0, 2));
        assertEquals(0, WheelMath.rootSlot(Math.PI / 2 - 0.01, 2));    // just before right
        assertEquals(1, WheelMath.rootSlot(Math.PI / 2 + 0.01, 2));    // just past right
        assertEquals(1, WheelMath.rootSlot(Math.PI, 2));
        assertEquals(0, WheelMath.rootSlot(TAU - 0.01, 2));            // just left of top
    }

    @Test
    void rootSlotCentersAreEvenlySpaced() {
        assertEquals(0, WheelMath.rootSlotCenter(0, 4), EPS);
        assertEquals(Math.PI / 2, WheelMath.rootSlotCenter(1, 4), EPS);
        assertEquals(Math.PI, WheelMath.rootSlotCenter(2, 4), EPS);
    }

    @Test
    void slotWidthScalesWithContentAndCapsAtAQuarterTurn() {
        // 16px icons at radius 100: (16+12)/100 rad — far narrower than the 45° cap.
        assertEquals(0.28, WheelMath.slotWidthFor(16, 100), EPS);
        // Wide labels at a small radius hit the cap.
        assertEquals(WheelMath.MAX_CHILD_SLOT, WheelMath.slotWidthFor(200, 100), EPS);
        // Bigger radius → narrower slots for the same content.
        assertTrue(WheelMath.slotWidthFor(84, 300) < WheelMath.slotWidthFor(84, 150));
    }

    @Test
    void capacityFollowsSlotWidth() {
        assertEquals(8, WheelMath.arcCapacity(WheelMath.MAX_CHILD_SLOT)); // 45° slots: 8 max
        assertEquals(22, WheelMath.arcCapacity(WheelMath.slotWidthFor(16, 100))); // icons pack in
        assertTrue(WheelMath.arcCapacity(WheelMath.slotWidthFor(16, 200))
                > WheelMath.arcCapacity(WheelMath.slotWidthFor(16, 100)));
    }

    @Test
    void fullCircleDetection() {
        assertTrue(WheelMath.arcIsFullCircle(8, WheelMath.MAX_CHILD_SLOT));
        assertFalse(WheelMath.arcIsFullCircle(4, WheelMath.MAX_CHILD_SLOT));
    }

    @Test
    void singleSlotArcIsCenteredOnParent() {
        double parent = Math.PI; // category at the bottom
        double w = WheelMath.MAX_CHILD_SLOT;
        assertEquals(parent, WheelMath.arcSlotCenter(parent, 1, w, 0), EPS);
        assertEquals(0, WheelMath.arcSlot(parent, parent, 1, w));
        // Inside the slot's half-width still hits; outside misses.
        assertEquals(0, WheelMath.arcSlot(parent + w / 2 - 0.01, parent, 1, w));
        assertEquals(-1, WheelMath.arcSlot(parent + w / 2 + 0.01, parent, 1, w));
        assertEquals(-1, WheelMath.arcSlot(0, parent, 1, w));
    }

    @Test
    void multiSlotArcFansAroundParentAndWraps() {
        double parent = 0; // category at the top: arc spans across the 0/TAU wrap
        double w = 0.4;
        assertEquals(WheelMath.normalize(-w), WheelMath.arcSlotCenter(parent, 3, w, 0), EPS);
        assertEquals(0, WheelMath.arcSlotCenter(parent, 3, w, 1), EPS);
        assertEquals(w, WheelMath.arcSlotCenter(parent, 3, w, 2), EPS);

        assertEquals(0, WheelMath.arcSlot(WheelMath.normalize(-w), parent, 3, w));
        assertEquals(1, WheelMath.arcSlot(0.0, parent, 3, w));
        assertEquals(2, WheelMath.arcSlot(w, parent, 3, w));
        assertEquals(-1, WheelMath.arcSlot(Math.PI, parent, 3, w));
    }

    @Test
    void arcSlotCentersRoundTripThroughArcSlot() {
        double parent = 3 * Math.PI / 2;
        for (double w : new double[] {0.15, 0.3, WheelMath.MAX_CHILD_SLOT}) {
            int max = WheelMath.arcCapacity(w);
            for (int count = 1; count <= Math.min(12, max); count++) {
                for (int i = 0; i < count; i++) {
                    double center = WheelMath.arcSlotCenter(parent, count, w, i);
                    assertEquals(i, WheelMath.arcSlot(center, parent, count, w),
                            "w=" + w + " count=" + count + " i=" + i);
                }
            }
        }
    }
}
