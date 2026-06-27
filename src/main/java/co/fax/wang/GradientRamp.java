package co.fax.wang;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Pure gradient-selection maths (no Minecraft dependencies, so it's unit-testable).
 *
 * <p>The gradient runs from the start-marker block to the end-marker block. Rather than blending a
 * target colour between the two endpoints and matching the nearest block (which collapses to the
 * endpoints when no block sits on the blend line), we give every palette block a 1-D <b>position</b>
 * along the gradient, order the whole palette start→end, and distribute it evenly across the fill
 * cells. That maximises the number of distinct steps and uses the intermediate blocks.
 *
 * <ul>
 *   <li>Brightness mode: position = perceived luminance.</li>
 *   <li>Colour mode: position = projection of the block's RGB onto the (end − start) colour axis.</li>
 * </ul>
 */
public final class GradientRamp {

    private GradientRamp() {}

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

    /** Palette indices ordered from the start side of the gradient to the end side. */
    public static int[] orderedIndices(int[] paletteRgb, int startRgb, int endRgb, GradientMode mode) {
        Integer[] idx = new Integer[paletteRgb.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;

        boolean ascending = position(endRgb, startRgb, endRgb, mode) >= position(startRgb, startRgb, endRgb, mode);
        Comparator<Integer> cmp = Comparator.comparingDouble(i -> position(paletteRgb[i], startRgb, endRgb, mode));
        Arrays.sort(idx, ascending ? cmp : cmp.reversed());

        int[] out = new int[idx.length];
        for (int i = 0; i < idx.length; i++) out[i] = idx[i];
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
        int[] order = orderedIndices(paletteRgb, startRgb, endRgb, mode);
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
