package co.fax.wang;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the pure gradient-selection maths across the different settings combinations. */
class GradientRampTest {

    private static final int BLACK = 0x000000, GRAY = 0x808080, WHITE = 0xFFFFFF;
    private static final int RED = 0xFF0000, GREEN = 0x00AA00, BLUE = 0x0000FF, YELLOW = 0xFFFF00;
    private static final int PINK = 0xFFAACC, MIDGREEN = 0x55AA55, OLIVE = 0x88AA44;

    private static long distinct(int[] a) {
        return Arrays.stream(a).distinct().count();
    }

    // ---- luminance / position ------------------------------------------------------------------

    @Test
    void luminanceIncreasesWithLightness() {
        assertTrue(GradientRamp.luminance(WHITE) > GradientRamp.luminance(GRAY));
        assertTrue(GradientRamp.luminance(GRAY) > GradientRamp.luminance(BLACK));
    }

    @Test
    void colorPositionRunsFromStartToEnd() {
        // The start colour sits at 0 and the end colour furthest along its own axis.
        double atStart = GradientRamp.position(RED, RED, BLUE, GradientMode.COLOR);
        double atEnd = GradientRamp.position(BLUE, RED, BLUE, GradientMode.COLOR);
        double mid = GradientRamp.position(0x800080, RED, BLUE, GradientMode.COLOR); // purple
        assertEquals(0.0, atStart, 1e-6);
        assertTrue(atEnd > mid && mid > atStart);
    }

    // ---- ordering: brightness mode --------------------------------------------------------------

    @Test
    void brightnessOrdersDarkToLight() {
        int[] pal = {WHITE, BLACK, GRAY};
        int[] order = GradientRamp.orderedIndices(pal, BLACK, WHITE, GradientMode.BRIGHTNESS);
        assertArrayEquals(new int[]{1, 2, 0}, order); // black, gray, white
    }

    @Test
    void brightnessReversesWhenStartIsBrighter() {
        int[] pal = {WHITE, BLACK, GRAY};
        int[] order = GradientRamp.orderedIndices(pal, WHITE, BLACK, GradientMode.BRIGHTNESS);
        assertArrayEquals(new int[]{0, 2, 1}, order); // white, gray, black
    }

    // ---- ordering: colour mode ------------------------------------------------------------------

    @Test
    void colorOrdersAlongTheStartEndAxis() {
        int purple = 0x800080;
        int[] pal = {BLUE, RED, purple};
        int[] order = GradientRamp.orderedIndices(pal, RED, BLUE, GradientMode.COLOR);
        assertArrayEquals(new int[]{1, 2, 0}, order); // red, purple, blue
    }

    @Test
    void brightnessAndColorOrderingsDiffer() {
        // Yellow is bright but lies at the start of a black→blue colour axis; blue is dark.
        int[] pal = {YELLOW, BLUE};
        int[] byBrightness = GradientRamp.orderedIndices(pal, BLACK, BLUE, GradientMode.BRIGHTNESS);
        int[] byColor = GradientRamp.orderedIndices(pal, BLACK, BLUE, GradientMode.COLOR);
        assertArrayEquals(new int[]{1, 0}, byBrightness); // blue (dark), yellow (bright)
        assertArrayEquals(new int[]{0, 1}, byColor);      // yellow (near black on this axis), blue
        assertFalse(Arrays.equals(byBrightness, byColor));
    }

    // ---- distribution: the original "only endpoints" regression ---------------------------------

    @Test
    void usesIntermediateBlocksNotJustEndpoints() {
        // pink start, green end, plus two in-between greens — the v1 bug used only pink + green.
        int[] pal = {PINK, GREEN, MIDGREEN, OLIVE};
        int[] grad = GradientRamp.buildGradient(pal, PINK, GREEN, GradientMode.COLOR, CurveFunction.LINEAR, 6);
        assertTrue(distinct(grad) > 2, "expected >2 distinct blocks, got " + distinct(grad));
    }

    @Test
    void evenDistributionHitsEveryBlockWhenCellsAllow() {
        int[] pal = {BLACK, 0x404040, GRAY, 0xC0C0C0, WHITE};
        int[] grad = GradientRamp.buildGradient(pal, BLACK, WHITE, GradientMode.BRIGHTNESS, CurveFunction.LINEAR, 9);
        assertEquals(pal.length, distinct(grad), "a long enough line should use every palette block");
    }

    @Test
    void anchorsFirstAndLastCellToTheClosestBlocks() {
        // Regression: the end-closest block (e.g. the palette colour matching the end marker) must
        // land on the LAST fill cell, next to the end marker — not be left out by an under-shot t.
        int[] pal = {BLACK, 0x404040, GRAY, 0xC0C0C0, WHITE};
        int[] order = GradientRamp.orderedIndices(pal, BLACK, WHITE, GradientMode.BRIGHTNESS);
        for (int cells = 2; cells <= 12; cells++) {
            int[] grad = GradientRamp.buildGradient(pal, BLACK, WHITE, GradientMode.BRIGHTNESS, CurveFunction.LINEAR, cells);
            assertEquals(order[0], grad[0], "first cell should be the start-closest block");
            assertEquals(order[order.length - 1], grad[grad.length - 1], "last cell should be the end-closest block");
        }
    }

    @Test
    void fillFractionSpansZeroToOne() {
        assertEquals(0.0, GradientRamp.fillFraction(0, 5), 1e-9);
        assertEquals(1.0, GradientRamp.fillFraction(4, 5), 1e-9);
        assertEquals(0.5, GradientRamp.fillFraction(2, 5), 1e-9);
        assertEquals(0.0, GradientRamp.fillFraction(0, 1), 1e-9); // single cell
    }

    @Test
    void gradientProgressesMonotonicallyFromStartToEnd() {
        int[] pal = {BLACK, 0x404040, GRAY, 0xC0C0C0, WHITE};
        int[] grad = GradientRamp.buildGradient(pal, BLACK, WHITE, GradientMode.BRIGHTNESS, CurveFunction.LINEAR, 8);
        double prev = -1;
        for (int idx : grad) {
            double lum = GradientRamp.luminance(pal[idx]);
            assertTrue(lum >= prev - 1e-9, "luminance should not decrease along the gradient");
            prev = lum;
        }
    }

    // ---- curve functions ------------------------------------------------------------------------

    @Test
    void rampIndexSpansTheFullRange() {
        assertEquals(0, GradientRamp.rampIndex(5, 0.0));
        assertEquals(2, GradientRamp.rampIndex(5, 0.5));
        assertEquals(4, GradientRamp.rampIndex(5, 1.0));
    }

    @Test
    void rampIndexIsMonotonic() {
        int prev = -1;
        for (double t = 0; t <= 1.0001; t += 0.05) {
            int i = GradientRamp.rampIndex(8, t);
            assertTrue(i >= prev);
            prev = i;
        }
    }

    @Test
    void stepCurveUsesFewerDistinctBlocksThanLinear() {
        int[] pal = {BLACK, 0x202020, 0x404040, 0x606060, GRAY, 0xA0A0A0, 0xC0C0C0, 0xE0E0E0, WHITE};
        int[] lin = GradientRamp.buildGradient(pal, BLACK, WHITE, GradientMode.BRIGHTNESS, CurveFunction.LINEAR, 16);
        int[] step = GradientRamp.buildGradient(pal, BLACK, WHITE, GradientMode.BRIGHTNESS, CurveFunction.STEP, 16);
        assertTrue(distinct(step) <= distinct(lin),
                "step quantises, so it should use no more distinct blocks than linear");
    }

    @Test
    void easeInStaysNearStartLongerThanEaseOut() {
        // At t=0.25 ease-in (slow start) should map lower than ease-out (fast start).
        int count = 9;
        int easeIn = GradientRamp.rampIndex(count, CurveFunction.EASE_IN.apply(0.25));
        int easeOut = GradientRamp.rampIndex(count, CurveFunction.EASE_OUT.apply(0.25));
        assertTrue(easeIn < easeOut, "ease-in should lag and ease-out should lead at t=0.25");
    }

    // ---- edge cases -----------------------------------------------------------------------------

    @Test
    void singleBlockPaletteAlwaysReturnsThatBlock() {
        int[] pal = {GREEN};
        int[] grad = GradientRamp.buildGradient(pal, PINK, GREEN, GradientMode.COLOR, CurveFunction.LINEAR, 5);
        for (int idx : grad) assertEquals(0, idx);
    }

    @Test
    void identicalEndpointsFallBackToBrightnessOrdering() {
        // start == end colour: colour axis is undefined, so position falls back to luminance.
        int[] pal = {WHITE, BLACK, GRAY};
        int[] order = GradientRamp.orderedIndices(pal, GRAY, GRAY, GradientMode.COLOR);
        assertArrayEquals(new int[]{1, 2, 0}, order); // black, gray, white by luminance
    }
}
