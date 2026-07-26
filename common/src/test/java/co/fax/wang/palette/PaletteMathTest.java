package co.fax.wang.palette;

import co.fax.wang.CurveFunction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaletteMathTest {

    @Test
    void linearSegmentBoundsAreEven() {
        assertArrayEquals(new double[]{0.25, 0.5, 0.75},
                PaletteMath.segmentBounds(4, CurveFunction.LINEAR, List.of()), 1e-9);
        assertArrayEquals(new double[0], PaletteMath.segmentBounds(1, CurveFunction.LINEAR, List.of()));
    }

    @Test
    void customUsesStopsOnlyWhenSizesMatch() {
        List<Double> stops = List.of(0.1, 0.9);
        assertArrayEquals(new double[]{0.1, 0.9},
                PaletteMath.segmentBounds(3, CurveFunction.CUSTOM, stops), 1e-9);
        // mismatch (e.g. skip-missing dropped a segment) falls back to even bands
        assertArrayEquals(new double[]{0.5},
                PaletteMath.segmentBounds(2, CurveFunction.CUSTOM, stops), 1e-9);
    }

    @Test
    void curvedPassesThroughCurveExceptCustom() {
        assertEquals(0.25, PaletteMath.curved(CurveFunction.EASE_IN, 0.5), 1e-9);
        assertEquals(0.5, PaletteMath.curved(CurveFunction.CUSTOM, 0.5), 1e-9); // raw for CUSTOM
    }

    @Test
    void flatBoundsSubdivideAutomaticSegmentsEvenly() {
        // 2 segments, boundary at 0.5; second segment resolved to 2 blocks → sub-boundary at 0.75
        double[] seg = {0.5};
        assertArrayEquals(new double[]{0.5, 0.75},
                PaletteMath.flatBounds(seg, new int[]{1, 2}), 1e-9);
        // single segment resolved to 4 blocks → even quarters
        assertArrayEquals(new double[]{0.25, 0.5, 0.75},
                PaletteMath.flatBounds(new double[0], new int[]{4}), 1e-9);
    }

    @Test
    void indexForScansBands() {
        double[] bounds = {0.25, 0.5, 0.75};
        assertEquals(0, PaletteMath.indexFor(0.0, bounds));
        assertEquals(1, PaletteMath.indexFor(0.25, bounds)); // boundary belongs to the upper band
        assertEquals(3, PaletteMath.indexFor(1.0, bounds));
    }

    @Test
    void minCellsCoversEveryBand() {
        // even quarters: 4 cells at 0, 1/3, 2/3, 1 → bands 0,1,2,3 all hit
        assertEquals(4, PaletteMath.minCells(new double[]{0.25, 0.5, 0.75}, 64));
        // a 10% first band needs more cells before its band is touched by c/(N-1)
        int n = PaletteMath.minCells(new double[]{0.1}, 64);
        assertTrue(n >= 2);
        boolean hitFirst = false;
        for (int c = 0; c < n; c++) {
            if (PaletteMath.indexFor((double) c / (n - 1), new double[]{0.1}) == 0) hitFirst = true;
        }
        assertTrue(hitFirst);
        assertEquals(1, PaletteMath.minCells(new double[0], 64)); // single step → one block
        // an impossibly thin middle band (no rational c/(N-1) inside it) can never be hit → cap
        assertEquals(64, PaletteMath.minCells(new double[]{1.0 / 3 + 1e-12, 1.0 / 3 + 2e-12}, 64));
    }
}
