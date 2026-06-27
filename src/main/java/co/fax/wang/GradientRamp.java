package co.fax.wang;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure gradient-selection maths (no Minecraft dependencies, so it's unit-testable).
 *
 * <p>The gradient runs from the start-marker block to the end-marker block. We give each palette
 * block a 1-D <b>position</b> along the gradient, keep only the blocks that are actually <b>near</b>
 * the gradient (within a deviation budget — so we never reach for wildly off-gradient colours just
 * to fill steps), order the survivors start→end, and distribute them evenly across the fill cells.
 * That uses the intermediate blocks without straying from the gradient.
 *
 * <ul>
 *   <li>Brightness mode: position = perceived luminance; deviation = distance outside the
 *       start..end luminance range.</li>
 *   <li>Colour mode: position = projection onto the (end − start) colour axis; deviation =
 *       distance from the block's RGB to the start→end segment in colour space.</li>
 * </ul>
 */
public final class GradientRamp {

    private GradientRamp() {}

    /** How far a block may sit from the gradient and still be eligible. */
    public static final double COLOR_MAX_DEVIATION = 110.0;      // RGB distance
    public static final double BRIGHTNESS_MAX_DEVIATION = 60.0;  // luminance distance

    public static double maxDeviation(GradientMode mode) {
        return mode == GradientMode.BRIGHTNESS ? BRIGHTNESS_MAX_DEVIATION : COLOR_MAX_DEVIATION;
    }

    /** Perceived (apparent) brightness of a 0xRRGGBB colour. */
    public static double luminance(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** 1-D position of a colour along the gradient defined by start→end, per mode. */
    public static double position(int rgb, int startRgb, int endRgb, GradientMode mode) {
        if (mode == GradientMode.BRIGHTNESS) return luminance(rgb);

        double sr = (startRgb >> 16) & 0xFF, sg = (startRgb >> 8) & 0xFF, sb = startRgb & 0xFF;
        double er = (endRgb >> 16) & 0xFF, eg = (endRgb >> 8) & 0xFF, eb = endRgb & 0xFF;
        double vr = er - sr, vg = eg - sg, vb = eb - sb;
        double len2 = vr * vr + vg * vg + vb * vb;
        if (len2 < 1e-6) return luminance(rgb); // endpoints share a colour → fall back to brightness

        double pr = ((rgb >> 16) & 0xFF) - sr, pg = ((rgb >> 8) & 0xFF) - sg, pb = (rgb & 0xFF) - sb;
        return (pr * vr + pg * vg + pb * vb) / Math.sqrt(len2);
    }

    /**
     * Distance of a colour from the gradient — how far it strays. Zero means it sits exactly on the
     * gradient; larger means more "off-gradient".
     */
    public static double deviation(int rgb, int startRgb, int endRgb, GradientMode mode) {
        if (mode == GradientMode.BRIGHTNESS) {
            double l = luminance(rgb), a = luminance(startRgb), b = luminance(endRgb);
            double lo = Math.min(a, b), hi = Math.max(a, b);
            if (l < lo) return lo - l;
            if (l > hi) return l - hi;
            return 0.0; // within the start..end luminance range
        }
        // colour: distance from the point to the start→end segment in RGB space
        double pr = (rgb >> 16) & 0xFF, pg = (rgb >> 8) & 0xFF, pb = rgb & 0xFF;
        double sr = (startRgb >> 16) & 0xFF, sg = (startRgb >> 8) & 0xFF, sb = startRgb & 0xFF;
        double er = (endRgb >> 16) & 0xFF, eg = (endRgb >> 8) & 0xFF, eb = endRgb & 0xFF;
        double vr = er - sr, vg = eg - sg, vb = eb - sb;
        double len2 = vr * vr + vg * vg + vb * vb;
        double t = len2 < 1e-9 ? 0.0 : clamp01(((pr - sr) * vr + (pg - sg) * vg + (pb - sb) * vb) / len2);
        double dr = pr - (sr + t * vr), dg = pg - (sg + t * vg), db = pb - (sb + t * vb);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    /** All palette indices ordered from the start side of the gradient to the end side. */
    public static int[] orderedIndices(int[] paletteRgb, int startRgb, int endRgb, GradientMode mode) {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < paletteRgb.length; i++) all.add(i);
        return orderBy(all, paletteRgb, startRgb, endRgb, mode);
    }

    /**
     * The palette blocks that are near enough to the gradient (within the deviation budget), ordered
     * start→end. If none qualify, falls back to the single least-deviating block so we still place
     * something rather than nothing.
     */
    public static int[] gradientOrder(int[] paletteRgb, int startRgb, int endRgb, GradientMode mode) {
        double max = maxDeviation(mode);
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < paletteRgb.length; i++) {
            if (deviation(paletteRgb[i], startRgb, endRgb, mode) <= max) kept.add(i);
        }
        if (kept.isEmpty()) {
            if (paletteRgb.length == 0) return new int[0];
            int best = 0;
            double bestDev = Double.MAX_VALUE;
            for (int i = 0; i < paletteRgb.length; i++) {
                double d = deviation(paletteRgb[i], startRgb, endRgb, mode);
                if (d < bestDev) { bestDev = d; best = i; }
            }
            return new int[]{best};
        }
        return orderBy(kept, paletteRgb, startRgb, endRgb, mode);
    }

    private static int[] orderBy(List<Integer> indices, int[] paletteRgb, int startRgb, int endRgb, GradientMode mode) {
        boolean ascending = position(endRgb, startRgb, endRgb, mode) >= position(startRgb, startRgb, endRgb, mode);
        Comparator<Integer> cmp = Comparator.comparingDouble(i -> position(paletteRgb[i], startRgb, endRgb, mode));
        indices.sort(ascending ? cmp : cmp.reversed());
        int[] out = new int[indices.size()];
        for (int i = 0; i < out.length; i++) out[i] = indices.get(i);
        return out;
    }

    /** Ordered slot (0..count-1) for a curved gradient fraction in [0,1]. */
    public static int rampIndex(int count, double curvedT) {
        if (count <= 1) return 0;
        int i = (int) Math.round(clamp01(curvedT) * (count - 1));
        return Math.max(0, Math.min(count - 1, i));
    }

    /**
     * Palette index chosen for each of {@code cells} fill cells of a clean gradient (deterministic).
     * The fill region spans the full ramp: the first cell is anchored to the start-closest block and
     * the last to the end-closest block, so cell {@code c} is at fraction {@code c/(cells-1)}.
     */
    public static int[] buildGradient(int[] paletteRgb, int startRgb, int endRgb,
                                      GradientMode mode, CurveFunction curve, int cells) {
        int[] order = gradientOrder(paletteRgb, startRgb, endRgb, mode);
        int[] result = new int[Math.max(0, cells)];
        for (int c = 0; c < result.length; c++) {
            double t = result.length <= 1 ? 0.0 : (double) c / (result.length - 1);
            result[c] = order[rampIndex(order.length, curve.apply(t))];
        }
        return result;
    }

    /**
     * Gradient fraction in [0,1] for fill cell {@code index} (0-based) of {@code count} fill cells
     * — the same anchoring {@link #buildGradient} uses, exposed for one-cell-at-a-time placement.
     */
    public static double fillFraction(int index, int count) {
        if (count <= 1) return 0.0;
        return clamp01((double) index / (count - 1));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
