package co.fax.wang;

import java.util.Arrays;

/**
 * Pure pixel-analysis for block textures (no Minecraft deps, so it's unit-testable). Input is an
 * array of ARGB pixels (0xAARRGGBB); fully/mostly transparent pixels are ignored.
 *
 * <p>Supports averaging the whole texture, or only the darkest/lightest fraction of its pixels —
 * which is what the "top % dark/light" gradient modes use.
 */
public final class TextureStats {

    private TextureStats() {}

    private static final int ALPHA_THRESHOLD = 128;

    /** Perceived luminance of a 0xRRGGBB colour. */
    public static double luminance(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** Opaque pixels as 0xRRGGBB, sorted darkest → lightest. Empty if none are opaque. */
    private static int[] opaqueSortedByLuminance(int[] argb) {
        int[] rgb = new int[argb.length];
        int n = 0;
        for (int p : argb) {
            if (((p >> 24) & 0xFF) >= ALPHA_THRESHOLD) rgb[n++] = p & 0xFFFFFF;
        }
        rgb = Arrays.copyOf(rgb, n);
        // sort by luminance
        Integer[] boxed = new Integer[n];
        for (int i = 0; i < n; i++) boxed[i] = rgb[i];
        Arrays.sort(boxed, (a, b) -> Double.compare(luminance(a), luminance(b)));
        for (int i = 0; i < n; i++) rgb[i] = boxed[i];
        return rgb;
    }

    /** Average colour (0xRRGGBB) of the darkest/lightest {@code fraction} of opaque pixels. */
    public static int topColor(int[] argb, double fraction, boolean lightest) {
        int[] sorted = opaqueSortedByLuminance(argb);
        int n = sorted.length;
        if (n == 0) return 0;
        int count = Math.max(1, (int) Math.round(n * clamp01(fraction)));
        int from = lightest ? n - count : 0;
        int to = lightest ? n : count;
        long r = 0, g = 0, b = 0;
        for (int i = from; i < to; i++) {
            r += (sorted[i] >> 16) & 0xFF;
            g += (sorted[i] >> 8) & 0xFF;
            b += sorted[i] & 0xFF;
        }
        int m = to - from;
        return ((int) (r / m) << 16) | ((int) (g / m) << 8) | (int) (b / m);
    }

    /** Average luminance of the darkest/lightest {@code fraction} of opaque pixels. */
    public static double topBrightness(int[] argb, double fraction, boolean lightest) {
        return luminance(topColor(argb, fraction, lightest));
    }

    /** Average colour of every opaque pixel. */
    public static int averageColor(int[] argb) {
        return topColor(argb, 1.0, false);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
