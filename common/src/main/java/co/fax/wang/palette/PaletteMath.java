package co.fax.wang.palette;

import co.fax.wang.CurveFunction;

import java.util.List;

/**
 * Pure maths for mapping a fill fraction onto a palette's segments (no Minecraft dependencies,
 * unit-testable). Works in "curved space": for a non-CUSTOM curve the fraction is passed through
 * {@link CurveFunction#apply} and segments occupy even bands; for CUSTOM the raw fraction is
 * looked up against the palette's stored stop positions. Automatic segments that resolved to
 * several blocks subdivide their segment's band evenly.
 */
public final class PaletteMath {

    private PaletteMath() {}

    /**
     * Internal segment boundaries in curved space, ascending, length {@code count − 1}. CUSTOM
     * with a matching stops list uses the stops; anything else (including a stops-size mismatch,
     * e.g. after skip-missing dropped a segment) gets even bands.
     */
    public static double[] segmentBounds(int count, CurveFunction curve, List<Double> stops) {
        if (count <= 1) return new double[0];
        double[] out = new double[count - 1];
        if (curve == CurveFunction.CUSTOM && stops != null && stops.size() == count - 1) {
            for (int i = 0; i < out.length; i++) out[i] = clamp01(stops.get(i));
        } else {
            for (int i = 0; i < out.length; i++) out[i] = (i + 1) / (double) count;
        }
        return out;
    }

    /** The fraction to look up against boundaries: curved, or raw for CUSTOM (stops carry the shape). */
    public static double curved(CurveFunction curve, double t) {
        return curve == CurveFunction.CUSTOM ? clamp01(t) : curve.apply(t);
    }

    /**
     * Flatten per-segment block counts into one boundary array: each segment's band (from
     * {@code segmentBounds}) is subdivided evenly among its resolved blocks. Length =
     * {@code sum(blocksPerSegment) − 1}.
     */
    public static double[] flatBounds(double[] segBounds, int[] blocksPerSegment) {
        int total = 0;
        for (int k : blocksPerSegment) total += Math.max(1, k);
        double[] out = new double[Math.max(0, total - 1)];
        int bi = 0;
        for (int seg = 0; seg < blocksPerSegment.length; seg++) {
            double lo = seg == 0 ? 0.0 : segBounds[seg - 1];
            double hi = seg == blocksPerSegment.length - 1 ? 1.0 : segBounds[seg];
            int k = Math.max(1, blocksPerSegment[seg]);
            for (int j = 1; j < k; j++) {
                out[bi++] = lo + (hi - lo) * j / k;
            }
            if (seg < blocksPerSegment.length - 1) out[bi++] = hi;
        }
        return out;
    }

    /** Band index of a curved fraction against ascending internal boundaries. */
    public static int indexFor(double tc, double[] bounds) {
        int pos = 0;
        while (pos < bounds.length && tc >= bounds[pos]) pos++;
        return pos;
    }

    /**
     * Smallest cell count N (≥ the step count) such that placing cells at fractions
     * {@code c/(N−1)} touches every band at least once — the "Min blocks" sizing. Falls back to
     * {@code cap} when tiny bands would need more cells than that.
     */
    public static int minCells(double[] bounds, int cap) {
        int steps = bounds.length + 1;
        if (steps <= 1) return 1;
        for (int n = steps; n <= cap; n++) {
            if (coversAll(bounds, n)) return n;
        }
        return cap;
    }

    private static boolean coversAll(double[] bounds, int n) {
        boolean[] hit = new boolean[bounds.length + 1];
        for (int c = 0; c < n; c++) {
            double t = n <= 1 ? 0.0 : (double) c / (n - 1);
            hit[indexFor(t, bounds)] = true;
        }
        for (boolean h : hit) {
            if (!h) return false;
        }
        return true;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
