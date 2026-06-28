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

    // ---- texture signatures + difference --------------------------------------------------------

    /**
     * Downsample a {@code w×h} ARGB image to a {@code grid×grid} signature of average cell colours
     * (0xRRGGBB), or {@code -1} for a cell with no opaque pixels. Used to compare two textures.
     */
    public static int[] downsample(int[] argb, int w, int h, int grid) {
        int[] sig = new int[grid * grid];
        for (int cy = 0; cy < grid; cy++) {
            for (int cx = 0; cx < grid; cx++) {
                long r = 0, g = 0, b = 0;
                int n = 0;
                int x0 = cx * w / grid, x1 = (cx + 1) * w / grid;
                int y0 = cy * h / grid, y1 = (cy + 1) * h / grid;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int p = argb[y * w + x];
                        if (((p >> 24) & 0xFF) >= ALPHA_THRESHOLD) {
                            r += (p >> 16) & 0xFF;
                            g += (p >> 8) & 0xFF;
                            b += p & 0xFF;
                            n++;
                        }
                    }
                }
                sig[cy * grid + cx] = n == 0 ? -1
                        : (((int) (r / n)) << 16) | (((int) (g / n)) << 8) | (int) (b / n);
            }
        }
        return sig;
    }

    /** Mean grayscale (brightness) difference between two signatures, 0..255. */
    public static double bwDiff(int[] a, int[] b) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < a.length && i < b.length; i++) {
            if (a[i] < 0 || b[i] < 0) continue;
            sum += Math.abs(luminance(a[i]) - luminance(b[i]));
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    /** Mean colour difference between two signatures, normalised to 0..255. */
    public static double colorDiff(int[] a, int[] b) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < a.length && i < b.length; i++) {
            if (a[i] < 0 || b[i] < 0) continue;
            int dr = ((a[i] >> 16) & 0xFF) - ((b[i] >> 16) & 0xFF);
            int dg = ((a[i] >> 8) & 0xFF) - ((b[i] >> 8) & 0xFF);
            int db = (a[i] & 0xFF) - (b[i] & 0xFF);
            sum += Math.sqrt((double) dr * dr + dg * dg + db * db);
            n++;
        }
        double avg = n == 0 ? 0 : sum / n;        // 0..441 (RGB diagonal)
        return Math.min(255.0, avg * 255.0 / 441.0);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
