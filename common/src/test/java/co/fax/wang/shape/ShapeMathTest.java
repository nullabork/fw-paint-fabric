package co.fax.wang.shape;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeMathTest {

    private static final double EPS = 1e-9;

    @Test
    void onRingMatchesMinecraftCircleRule() {
        // Radius 5: (3,4) is exactly 5; (5,0) too; (4,4) rounds to 6.
        assertTrue(ShapeMath.onRing(3, 4, 5, 5));
        assertTrue(ShapeMath.onRing(5, 0, 5, 5));
        assertFalse(ShapeMath.onRing(4, 4, 5, 5));
        // Donut band 3..5 includes radius 4 cells; solid disc 0..2 includes the center.
        assertTrue(ShapeMath.onRing(0, 4, 3, 5));
        assertTrue(ShapeMath.onRing(0, 0, 0, 2));
        assertFalse(ShapeMath.onRing(0, 0, 1, 2));
    }

    @Test
    void squareRingUsesChebyshevDistance() {
        assertTrue(ShapeMath.onSquareRing(5, 3, 5, 5));
        assertTrue(ShapeMath.onSquareRing(5, 5, 5, 5)); // the corner is on the ring
        assertFalse(ShapeMath.onSquareRing(4, 3, 5, 5));
        assertTrue(ShapeMath.onSquareRing(4, 3, 3, 5)); // band includes radius 4
    }

    @Test
    void rotatedSquareRingMatchesUnrotatedAtZeroAndTiltsAt45() {
        // theta = 0 behaves exactly like the plain square ring.
        assertTrue(ShapeMath.onRotatedSquareRing(5, 3, 0, 5, 5));
        assertFalse(ShapeMath.onRotatedSquareRing(4, 3, 0, 5, 5));
        // 45°: the old corner (5,5) now sits at local (~7.07, 0) — radius 7, not 5.
        double t = Math.PI / 4;
        assertFalse(ShapeMath.onRotatedSquareRing(5, 5, t, 5, 5));
        assertTrue(ShapeMath.onRotatedSquareRing(5, 5, t, 7, 7));
        // ...and the axis point (7,0) rotates to local (~4.95, ~-4.95) — on the r=5 ring.
        assertTrue(ShapeMath.onRotatedSquareRing(7, 0, t, 5, 5));
    }

    @Test
    void rayBoxIntersectHitsMissesAndInside() {
        // Straight-on hit: from x=-2 toward +x into the unit box at origin → enters at t=2.
        assertEquals(2, ShapeMath.rayBoxIntersect(-2, 0.5, 0.5, 1, 0, 0, 0, 0, 0, 1, 1, 1), EPS);
        // Miss: aimed above the box.
        assertEquals(-1, ShapeMath.rayBoxIntersect(-2, 2.5, 0.5, 1, 0, 0, 0, 0, 0, 1, 1, 1), EPS);
        // Behind: box is in -x but the ray goes +x.
        assertEquals(-1, ShapeMath.rayBoxIntersect(5, 0.5, 0.5, 1, 0, 0, 0, 0, 0, 1, 1, 1), EPS);
        // Inside: starts within the box → 0.
        assertEquals(0, ShapeMath.rayBoxIntersect(0.5, 0.5, 0.5, 1, 0, 0, 0, 0, 0, 1, 1, 1), EPS);
    }
}
