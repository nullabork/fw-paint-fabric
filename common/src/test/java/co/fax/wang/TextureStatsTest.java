package co.fax.wang;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureStatsTest {

    private static final int A = 0xFF000000; // opaque flag helper
    private static final int BLACK = A | 0x000000;
    private static final int WHITE = A | 0xFFFFFF;
    private static final int RED = A | 0xFF0000;
    private static final int GREEN = A | 0x00FF00;
    private static final int TRANSPARENT = 0x00FFFFFF; // white but fully transparent

    @Test
    void averageIgnoresTransparentPixels() {
        // two black + one transparent white → average is black, not gray.
        int[] px = {BLACK, BLACK, TRANSPARENT};
        assertEquals(0x000000, TextureStats.averageColor(px));
    }

    @Test
    void averageOfBlackAndWhiteIsGray() {
        int[] px = {BLACK, WHITE};
        int avg = TextureStats.averageColor(px);
        assertEquals(0x7F7F7F, avg); // (0+255)/2 = 127
    }

    @Test
    void topDarkPicksTheDarkPixels() {
        // half black, half white; darkest 50% → black.
        int[] px = {BLACK, BLACK, WHITE, WHITE};
        assertEquals(0x000000, TextureStats.topColor(px, 0.5, false));
        assertEquals(0xFFFFFF, TextureStats.topColor(px, 0.5, true)); // lightest 50% → white
    }

    @Test
    void topDarkColorUsesTheActualColorOfDarkPixels() {
        // dark-ish red + bright white; darkest 50% → the red, not an average.
        int[] px = {RED, RED, WHITE, WHITE};
        assertEquals(0xFF0000, TextureStats.topColor(px, 0.5, false));
    }

    @Test
    void topBrightnessReflectsSelectedPixels() {
        int[] px = {BLACK, BLACK, WHITE, WHITE};
        double dark = TextureStats.topBrightness(px, 0.5, false);
        double light = TextureStats.topBrightness(px, 0.5, true);
        assertTrue(dark < light);
        assertEquals(0.0, dark, 1e-9);
    }

    @Test
    void fractionScalesHowManyPixelsAreUsed() {
        // gradient of greys; smallest fraction → just the single darkest.
        int[] px = {A | 0x000000, A | 0x404040, A | 0x808080, A | 0xC0C0C0, A | 0xFFFFFF};
        assertEquals(0x000000, TextureStats.topColor(px, 0.01, false)); // ≥1 pixel: the darkest
        assertEquals(0xFFFFFF, TextureStats.topColor(px, 0.01, true));   // the lightest
    }

    @Test
    void downsampleAveragesCells() {
        // 2x2 image, grid 2 → each pixel is its own cell.
        int[] img = {BLACK, WHITE, RED, GREEN};
        int[] sig = TextureStats.downsample(img, 2, 2, 2);
        assertEquals(0x000000, sig[0]);
        assertEquals(0xFFFFFF, sig[1]);
        assertEquals(0xFF0000, sig[2]);
        assertEquals(0x00FF00, sig[3]);
    }

    @Test
    void identicalSignaturesHaveZeroDiff() {
        int[] sig = TextureStats.downsample(new int[]{BLACK, WHITE, RED, GREEN}, 2, 2, 2);
        assertEquals(0.0, TextureStats.bwDiff(sig, sig), 1e-9);
        assertEquals(0.0, TextureStats.colorDiff(sig, sig), 1e-9);
    }

    @Test
    void blackVsWhiteIsMaxBwDiff() {
        int[] black = {0x000000};
        int[] white = {0xFFFFFF};
        assertEquals(255.0, TextureStats.bwDiff(black, white), 1e-6);
    }

    @Test
    void colorDiffSeesHueWhereBwDiffDoesNot() {
        // red vs green have similar brightness-ish but very different colour.
        int[] red = {0xFF0000};
        int[] green = {0x00FF00};
        double bw = TextureStats.bwDiff(red, green);
        double color = TextureStats.colorDiff(red, green);
        assertTrue(color > bw, "colour diff should exceed b&w diff for same-ish brightness hues");
        assertTrue(color > 50, "red vs green is a big colour difference");
    }

    @Test
    void emptyCellsAreIgnoredInDiff() {
        int[] a = {0x000000, -1};
        int[] b = {0x000000, 0xFFFFFF};
        assertEquals(0.0, TextureStats.bwDiff(a, b), 1e-9); // only the matching black cell counts
    }

    @Test
    void emptyOrAllTransparentReturnsZero() {
        assertEquals(0, TextureStats.averageColor(new int[]{}));
        assertEquals(0, TextureStats.averageColor(new int[]{TRANSPARENT, TRANSPARENT}));
    }
}
