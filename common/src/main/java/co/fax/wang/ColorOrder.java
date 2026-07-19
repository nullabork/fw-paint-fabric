package co.fax.wang;

/**
 * Pure colour-ordering math for the Finder tab (no Minecraft deps, so it's unit-testable). Defines
 * the two linear orderings the Finder uses: a "rainbow" order — grays first dark → light, then hue
 * bands with brightness snaking through each band — and a plain brightness order.
 *
 * <p>Both orderings measure colours in <a href="https://bottosson.github.io/posts/oklab/">Oklch</a>
 * (perceptual lightness + perceptual hue) rather than HSV/luma: HSV hue crowds the greens and luma
 * misjudges blues, which is what puts a dark block in the middle of a light run. There is no
 * perfect 1D ordering of a 3D colour space — this is the "step sorting" compromise, tuned for
 * finding neighbours of a colour.
 *
 * <p>Keys are ascending longs so a reference colour can be placed into a sorted list with a plain
 * binary search ({@link #insertionIndex}).
 */
public final class ColorOrder {

    private ColorOrder() {}

    /** Below this Oklch chroma a colour reads as gray and its hue is noise, so it sorts by lightness. */
    public static final double GRAY_CHROMA_THRESHOLD = 0.03;

    /**
     * Hue bands in the colour ordering: 30° each, the classic 12-family colour wheel. Bands must be
     * coarse — with fine buckets, near-identical hues outrank brightness and a dark green lands in
     * the middle of the light greens.
     */
    public static final int HUE_BANDS = 12;

    private static final int BUCKETS = 1024;

    /** 0xRRGGBB → {hue 0..360, saturation 0..1, value 0..1}. */
    public static double[] rgbToHsv(int rgb) {
        double r = ((rgb >> 16) & 0xFF) / 255.0;
        double g = ((rgb >> 8) & 0xFF) / 255.0;
        double b = (rgb & 0xFF) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double d = max - min;
        double hue;
        if (d == 0) hue = 0;
        else if (max == r) hue = 60 * ((g - b) / d);
        else if (max == g) hue = 60 * ((b - r) / d + 2);
        else hue = 60 * ((r - g) / d + 4);
        if (hue < 0) hue += 360;
        double sat = max == 0 ? 0 : d / max;
        return new double[]{hue, sat, max};
    }

    /** HSV (hue 0..360, saturation 0..1, value 0..1) → 0xRRGGBB. */
    public static int hsvToRgb(double hue, double sat, double val) {
        hue = ((hue % 360) + 360) % 360;
        sat = clamp01(sat);
        val = clamp01(val);
        double c = val * sat;
        double x = c * (1 - Math.abs((hue / 60) % 2 - 1));
        double m = val - c;
        double r, g, b;
        if (hue < 60) { r = c; g = x; b = 0; }
        else if (hue < 120) { r = x; g = c; b = 0; }
        else if (hue < 180) { r = 0; g = c; b = x; }
        else if (hue < 240) { r = 0; g = x; b = c; }
        else if (hue < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return (round8(r + m) << 16) | (round8(g + m) << 8) | round8(b + m);
    }

    /**
     * Rainbow sort key: grays occupy [0, 1024) ordered dark → light, colours follow in the 12 hue
     * bands ordered by perceived lightness inside each band. The lightness direction snakes —
     * grays end at white, so band 0 runs light → dark, the next band dark → light, and so on —
     * keeping brightness continuous across band edges instead of jumping.
     */
    public static long colorSortKey(int rgb) {
        double[] lch = rgbToOklch(rgb);
        long light = lightBucket(lch[0]);
        if (lch[1] < GRAY_CHROMA_THRESHOLD) return light;
        long band = hueBand(rgb);
        long lightPart = band % 2 == 0 ? (BUCKETS - 1 - light) : light;
        return BUCKETS + band * BUCKETS + lightPart;
    }

    /** The 30° Oklch hue band (0..{@link #HUE_BANDS}-1) this colour falls in. */
    public static int hueBand(int rgb) {
        double[] lch = rgbToOklch(rgb);
        return (int) Math.min(HUE_BANDS - 1, (long) (lch[2] / 360.0 * HUE_BANDS));
    }

    /** Brightness sort key: perceived (Oklab) lightness alone, dark → light. */
    public static long brightnessSortKey(int rgb) {
        return lightBucket(rgbToOklch(rgb)[0]);
    }

    /**
     * 0xRRGGBB → Oklab {lightness 0..1, a, b}. Reference implementation from Björn Ottosson's
     * Oklab post (sRGB → linear → LMS → Oklab).
     */
    public static double[] rgbToOklab(int rgb) {
        double r = srgbToLinear(((rgb >> 16) & 0xFF) / 255.0);
        double g = srgbToLinear(((rgb >> 8) & 0xFF) / 255.0);
        double b = srgbToLinear((rgb & 0xFF) / 255.0);
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        return new double[]{
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        };
    }

    /** 0xRRGGBB → Oklch {lightness 0..1, chroma, hue 0..360} — {@link #rgbToOklab} in polar form. */
    public static double[] rgbToOklch(int rgb) {
        double[] lab = rgbToOklab(rgb);
        double chroma = Math.sqrt(lab[1] * lab[1] + lab[2] * lab[2]);
        double hue = Math.toDegrees(Math.atan2(lab[2], lab[1]));
        if (hue < 0) hue += 360;
        return new double[]{lab[0], chroma, hue};
    }

    /** Perceptual colour difference: Euclidean distance in Oklab (0 = identical, 1 ≈ black↔white). */
    public static double oklabDist(int rgbA, int rgbB) {
        double[] a = rgbToOklab(rgbA), b = rgbToOklab(rgbB);
        double dl = a[0] - b[0], da = a[1] - b[1], db = a[2] - b[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    private static double srgbToLinear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** First index in the ascending {@code sortedKeys} at which {@code key} could be inserted. */
    public static int insertionIndex(long key, long[] sortedKeys) {
        int lo = 0, hi = sortedKeys.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sortedKeys[mid] < key) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static long lightBucket(double oklabLightness) {
        return Math.max(0, Math.min(BUCKETS - 1, (long) (oklabLightness * (BUCKETS - 1))));
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private static int round8(double v) { return (int) Math.round(v * 255); }
}
