package co.fax.wang;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorOrderTest {

    private static final int BLACK = 0x000000;
    private static final int GRAY = 0x808080;
    private static final int WHITE = 0xFFFFFF;
    private static final int RED = 0xFF0000;
    private static final int DARK_RED = 0x800000;
    private static final int YELLOW = 0xFFFF00;
    private static final int GREEN = 0x00FF00;
    private static final int CYAN = 0x00FFFF;
    private static final int BLUE = 0x0000FF;
    private static final int MAGENTA = 0xFF00FF;

    @Test
    void graysComeFirstOrderedByLuminance() {
        assertTrue(ColorOrder.colorSortKey(BLACK) < ColorOrder.colorSortKey(GRAY));
        assertTrue(ColorOrder.colorSortKey(GRAY) < ColorOrder.colorSortKey(WHITE));
        // every gray sorts before every colour
        assertTrue(ColorOrder.colorSortKey(WHITE) < ColorOrder.colorSortKey(DARK_RED));
    }

    @Test
    void colorsFollowTheRainbow() {
        long r = ColorOrder.colorSortKey(RED);
        long y = ColorOrder.colorSortKey(YELLOW);
        long g = ColorOrder.colorSortKey(GREEN);
        long c = ColorOrder.colorSortKey(CYAN);
        long b = ColorOrder.colorSortKey(BLUE);
        long m = ColorOrder.colorSortKey(MAGENTA);
        assertTrue(r < y);
        assertTrue(y < g);
        assertTrue(g < c);
        assertTrue(c < b);
        assertTrue(b < m);
    }

    @Test
    void brightnessSnakesThroughTheBands() {
        // Pure red sits in Oklch band 0 (hue ≈ 29°), which continues from the white end of the
        // grays: light → dark.
        assertEquals(0, ColorOrder.hueBand(RED));
        assertEquals(0, ColorOrder.hueBand(DARK_RED));
        assertTrue(ColorOrder.colorSortKey(RED) < ColorOrder.colorSortKey(DARK_RED));
        // In every band the light/dark direction matches the band's snake parity.
        int lightOrange = 0xFF8000, darkOrange = 0x7F4000;
        assertEquals(ColorOrder.hueBand(lightOrange), ColorOrder.hueBand(darkOrange));
        boolean lightFirst = ColorOrder.hueBand(lightOrange) % 2 == 0;
        assertEquals(lightFirst,
                ColorOrder.colorSortKey(lightOrange) < ColorOrder.colorSortKey(darkOrange));
        // ...and every red still comes before the orange band.
        assertTrue(ColorOrder.colorSortKey(DARK_RED) < ColorOrder.colorSortKey(darkOrange));
    }

    @Test
    void withinABandBrightnessBeatsHue() {
        // Three greens of slightly different hue in one band: the darker one must NOT sit between
        // lighter ones because of a tiny hue difference — lightness orders them monotonically.
        int darkGreen = ColorOrder.hsvToRgb(110, 1, 0.35);
        int midGreen = ColorOrder.hsvToRgb(120, 1, 0.65);
        int lightGreen = ColorOrder.hsvToRgb(130, 1, 0.95);
        assertEquals(ColorOrder.hueBand(darkGreen), ColorOrder.hueBand(midGreen));
        assertEquals(ColorOrder.hueBand(midGreen), ColorOrder.hueBand(lightGreen));
        long dark = ColorOrder.colorSortKey(darkGreen);
        long mid = ColorOrder.colorSortKey(midGreen);
        long light = ColorOrder.colorSortKey(lightGreen);
        assertTrue((dark < mid && mid < light) || (light < mid && mid < dark),
                "lightness must order same-band colours monotonically");
    }

    @Test
    void chromaThresholdSplitsGraysFromColors() {
        int nearlyGray = ColorOrder.hsvToRgb(0, 0.05, 0.8);
        int clearlyRed = ColorOrder.hsvToRgb(0, 0.5, 0.8);
        assertTrue(ColorOrder.colorSortKey(nearlyGray) < 1024, "low chroma lands in the gray group");
        assertTrue(ColorOrder.colorSortKey(clearlyRed) >= 1024, "chromatic colour lands in the colour group");
    }

    @Test
    void oklchMatchesKnownReferenceValues() {
        // White: L=1, chroma≈0. Black: L=0.
        double[] white = ColorOrder.rgbToOklch(WHITE);
        assertEquals(1.0, white[0], 0.01);
        assertEquals(0.0, white[1], 0.01);
        assertEquals(0.0, ColorOrder.rgbToOklch(BLACK)[0], 0.01);
        // Pure red: L≈0.628, hue≈29° (Björn Ottosson's published values).
        double[] red = ColorOrder.rgbToOklch(RED);
        assertEquals(0.628, red[0], 0.01);
        assertEquals(29.2, red[2], 1.0);
    }

    @Test
    void hsvRoundTripsOnPrimaries() {
        assertEquals(RED, ColorOrder.hsvToRgb(0, 1, 1));
        assertEquals(GREEN, ColorOrder.hsvToRgb(120, 1, 1));
        assertEquals(BLUE, ColorOrder.hsvToRgb(240, 1, 1));
        assertEquals(WHITE, ColorOrder.hsvToRgb(0, 0, 1));
        assertEquals(BLACK, ColorOrder.hsvToRgb(180, 1, 0));

        double[] hsv = ColorOrder.rgbToHsv(RED);
        assertEquals(0, hsv[0], 1e-9);
        assertEquals(1, hsv[1], 1e-9);
        assertEquals(1, hsv[2], 1e-9);
    }

    @Test
    void hsvRoundTripsWithinRoundingOnMidTones() {
        int original = 0x336699;
        double[] hsv = ColorOrder.rgbToHsv(original);
        int back = ColorOrder.hsvToRgb(hsv[0], hsv[1], hsv[2]);
        assertTrue(Math.abs(((original >> 16) & 0xFF) - ((back >> 16) & 0xFF)) <= 1);
        assertTrue(Math.abs(((original >> 8) & 0xFF) - ((back >> 8) & 0xFF)) <= 1);
        assertTrue(Math.abs((original & 0xFF) - (back & 0xFF)) <= 1);
    }

    @Test
    void brightnessKeyIsMonotoneInLuminance() {
        assertTrue(ColorOrder.brightnessSortKey(BLACK) < ColorOrder.brightnessSortKey(GRAY));
        assertTrue(ColorOrder.brightnessSortKey(GRAY) < ColorOrder.brightnessSortKey(WHITE));
        // green is perceptually brighter than blue at full intensity
        assertTrue(ColorOrder.brightnessSortKey(BLUE) < ColorOrder.brightnessSortKey(GREEN));
    }

    @Test
    void insertionIndexFindsThePosition() {
        long[] keys = {10, 20, 20, 30};
        assertEquals(0, ColorOrder.insertionIndex(5, new long[0]));
        assertEquals(0, ColorOrder.insertionIndex(5, keys));
        assertEquals(4, ColorOrder.insertionIndex(99, keys));
        assertEquals(1, ColorOrder.insertionIndex(20, keys)); // first equal slot
        assertEquals(3, ColorOrder.insertionIndex(25, keys)); // between values
    }
}
