package co.fax.wang.palette;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PatternMathTest {

    @Test
    void yawSnapsToEightOrFourDirections() {
        assertArrayEquals(new int[]{0, 1}, PatternMath.snappedFacingStep(0, 45));    // south
        assertArrayEquals(new int[]{-1, 0}, PatternMath.snappedFacingStep(90, 45));  // west
        assertArrayEquals(new int[]{0, -1}, PatternMath.snappedFacingStep(180, 45)); // north
        assertArrayEquals(new int[]{1, 0}, PatternMath.snappedFacingStep(-90, 45));  // east
        assertArrayEquals(new int[]{-1, 1}, PatternMath.snappedFacingStep(45, 45));  // south-west
        assertArrayEquals(new int[]{0, 1}, PatternMath.snappedFacingStep(22, 45));   // rounds down
        // 90° snapping folds the diagonal onto an axis.
        assertArrayEquals(new int[]{-1, 0}, PatternMath.snappedFacingStep(45, 90));
    }

    @Test
    void widthStepIsPerpendicularWithColinearFallback() {
        // Facing south (0,1) → width axis east/west.
        assertArrayEquals(new int[]{-1, 0, 0},
                PatternMath.widthStep(new int[]{0, 1}, new int[]{0, 1, 0})); // extrusion up
        // Extrusion east (1,0,0) and facing north (0,-1) → perpendicular would be (1,0)=east,
        // colinear with the extrusion → falls back to the facing step itself.
        assertArrayEquals(new int[]{0, 0, -1},
                PatternMath.widthStep(new int[]{0, -1}, new int[]{1, 0, 0}));
    }

    @Test
    void uProjectsToNearestStepIncludingDiagonals() {
        int[] diag = {1, 0, 1}; // 45° width axis
        assertEquals(0, PatternMath.uOf(new int[]{0, 0, 0}, diag));
        assertEquals(1, PatternMath.uOf(new int[]{1, 0, 1}, diag));
        assertEquals(1, PatternMath.uOf(new int[]{1, 0, 0}, diag)); // off-lattice → nearest (0.5 → 1)
        assertEquals(-2, PatternMath.uOf(new int[]{-2, 0, -2}, diag));
        // Displacement along the plane normal (perpendicular diagonal) leaves u unchanged.
        assertEquals(0, PatternMath.uOf(new int[]{1, 0, -1}, diag));
    }

    @Test
    void vIsSignedExtrusionDistance() {
        assertEquals(3, PatternMath.vOf(new int[]{0, 3, 0}, new int[]{0, 1, 0}));
        assertEquals(-2, PatternMath.vOf(new int[]{2, 0, 0}, new int[]{-1, 0, 0}));
    }

    private static Palette pat(PatternTiling tiling) {
        Palette p = new Palette();
        p.kind = PaletteKind.PATTERN;
        p.width = 2;
        p.height = 2;
        p.cells = List.of("a", "", "c", "d");
        p.tiling = tiling;
        return p;
    }

    @Test
    void cellForAppliesTiling() {
        Palette none = pat(PatternTiling.NONE);
        assertEquals("a", PatternMath.cellFor(none, 0, 0));
        assertEquals("", PatternMath.cellFor(none, 1, 0));        // hole
        assertNull(PatternMath.cellFor(none, 2, 0));              // outside width → skip
        assertNull(PatternMath.cellFor(none, -1, 0));
        assertEquals(PatternMath.COLUMN_DONE, PatternMath.cellFor(none, 0, 2)); // past end

        Palette sides = pat(PatternTiling.SIDES);
        assertEquals("a", PatternMath.cellFor(sides, 2, 0));      // u wraps
        assertEquals(PatternMath.COLUMN_DONE, PatternMath.cellFor(sides, 0, 2));

        Palette wrapV = pat(PatternTiling.START_END);
        assertEquals("a", PatternMath.cellFor(wrapV, 0, 2));      // v wraps
        assertNull(PatternMath.cellFor(wrapV, 2, 0));

        Palette both = pat(PatternTiling.START_END_SIDES);
        assertEquals("a", PatternMath.cellFor(both, 2, 2));
        assertEquals("d", PatternMath.cellFor(both, 3, 3));
    }
}
