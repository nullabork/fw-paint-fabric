package co.fax.wang;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the pure marker-segment geometry that bounds the noise flood fill. */
class NoisePlacerTest {

    @Test
    void cellStrictlyBetweenColinearMarkersIsInside() {
        // start (0,0,0) → end (0,0,5) along Z; cells 1..4 are inside, endpoints are not.
        assertTrue(NoisePlacer.betweenColinear(0, 0, 0, 0, 0, 5, 0, 0, 3, 64));
        assertFalse(NoisePlacer.betweenColinear(0, 0, 0, 0, 0, 5, 0, 0, 0, 64)); // the start marker itself
        assertFalse(NoisePlacer.betweenColinear(0, 0, 0, 0, 0, 5, 0, 0, 5, 64)); // the end marker itself
        assertFalse(NoisePlacer.betweenColinear(0, 0, 0, 0, 0, 5, 0, 0, 7, 64)); // past the end
    }

    @Test
    void offAxisCellIsOutside() {
        // on the X→ line but the cell is shifted in Y/Z → not on the segment.
        assertTrue(NoisePlacer.betweenColinear(0, 0, 0, 6, 0, 0, 3, 0, 0, 64));
        assertFalse(NoisePlacer.betweenColinear(0, 0, 0, 6, 0, 0, 3, 1, 0, 64));
        assertFalse(NoisePlacer.betweenColinear(0, 0, 0, 6, 0, 0, 3, 0, 1, 64));
    }

    @Test
    void diagonalOrSameMarkersAreNotSegments() {
        assertFalse(NoisePlacer.betweenColinear(0, 0, 0, 5, 5, 0, 2, 2, 0, 64)); // diagonal (2 axes)
        assertFalse(NoisePlacer.betweenColinear(0, 0, 0, 0, 0, 0, 0, 0, 0, 64)); // zero length
    }

    @Test
    void respectsTheMaxDistance() {
        assertTrue(NoisePlacer.betweenColinear(0, 0, 0, 0, 0, 10, 0, 0, 5, 16));
        assertFalse(NoisePlacer.betweenColinear(0, 0, 0, 0, 0, 20, 0, 0, 5, 16)); // segment too long
    }

    @Test
    void worksInBothDirections() {
        // end before start on the axis (negative delta) still brackets the cell.
        assertTrue(NoisePlacer.betweenColinear(0, 0, 5, 0, 0, 0, 0, 0, 2, 64));
    }
}
