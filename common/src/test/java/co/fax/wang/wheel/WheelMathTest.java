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
    void childSlotWidthCapsAtMax() {
        assertEquals(WheelMath.MAX_CHILD_SLOT, WheelMath.childSlotWidth(1), EPS);
        assertEquals(WheelMath.MAX_CHILD_SLOT, WheelMath.childSlotWidth(8), EPS);
        assertEquals(TAU / 12, WheelMath.childSlotWidth(12), EPS);
    }

    @Test
    void eightChildrenAtTheCapFillTheWholeRing() {
        assertTrue(WheelMath.childArcIsFullCircle(8));
        assertTrue(WheelMath.childArcIsFullCircle(12));
        assertFalse(WheelMath.childArcIsFullCircle(4));
        // Every angle lands in some slot when the arc is a full circle.
        for (double a = 0; a < TAU; a += 0.05) {
            assertTrue(WheelMath.childSlot(a, Math.PI / 3, 8) >= 0, "angle=" + a);
        }
    }

    @Test
    void singleChildArcIsCenteredOnParent() {
        double parent = Math.PI; // category at the bottom
        assertEquals(parent, WheelMath.childSlotCenter(parent, 1, 0), EPS);
        assertEquals(0, WheelMath.childSlot(parent, parent, 1));
        // Inside the slot's half-width still hits; outside misses.
        double half = WheelMath.childSlotWidth(1) / 2;
        assertEquals(0, WheelMath.childSlot(parent + half - 0.01, parent, 1));
        assertEquals(-1, WheelMath.childSlot(parent + half + 0.01, parent, 1));
        assertEquals(-1, WheelMath.childSlot(0, parent, 1));
    }

    @Test
    void multiChildArcFansAroundParentAndWraps() {
        double parent = 0; // category at the top: arc spans across the 0/TAU wrap
        double slot = WheelMath.childSlotWidth(3);
        assertEquals(WheelMath.normalize(-slot), WheelMath.childSlotCenter(parent, 3, 0), EPS);
        assertEquals(0, WheelMath.childSlotCenter(parent, 3, 1), EPS);
        assertEquals(slot, WheelMath.childSlotCenter(parent, 3, 2), EPS);

        assertEquals(0, WheelMath.childSlot(WheelMath.normalize(-slot), parent, 3));
        assertEquals(1, WheelMath.childSlot(0.0, parent, 3));
        assertEquals(2, WheelMath.childSlot(slot, parent, 3));
        assertEquals(-1, WheelMath.childSlot(Math.PI, parent, 3));
    }

    @Test
    void childSlotCentersRoundTripThroughChildSlot() {
        double parent = 3 * Math.PI / 2;
        for (int count = 1; count <= 8; count++) {
            for (int i = 0; i < count; i++) {
                double center = WheelMath.childSlotCenter(parent, count, i);
                assertEquals(i, WheelMath.childSlot(center, parent, count),
                        "count=" + count + " i=" + i);
            }
        }
    }
}
