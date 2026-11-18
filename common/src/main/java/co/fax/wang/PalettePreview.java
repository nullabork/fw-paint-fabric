package co.fax.wang;

import co.fax.wang.palette.Palette;
import co.fax.wang.palette.PaletteMath;
import co.fax.wang.palette.PaletteSegment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.Map;

/**
 * Flat-colour previews for the Paint tab's palette panel: a gradient strip (each segment's band
 * as wide as the curve and stops make it) and a top-down slice of the noise field (one cell per
 * block, coloured by the segment the noise lands on). Colours are each block's average texture
 * colour, Automatic segments their representative tint ({@link PaletteTints}); memoized by
 * palette content so nothing samples textures per frame. Display only — chaos, variation and
 * step-length wobble are left out.
 */
public final class PalettePreview {

    private PalettePreview() {}

    private static final Map<String, int[]> CACHE = new HashMap<>();
    private static final int AUTO_GREY = 0xFF8A8A8A;
    /** Noise slice: px per world block. */
    private static final int CELL = 3;

    /** ARGB per segment. */
    private static int[] colorsFor(Palette p) {
        String key = p.contentKey();
        int[] colors = CACHE.get(key);
        if (colors == null) {
            if (CACHE.size() > 32) CACHE.clear();
            int[] tints = PaletteTints.forPalette(p);
            colors = new int[p.segments.size()];
            for (int i = 0; i < colors.length; i++) {
                PaletteSegment s = p.segments.get(i);
                if (s.isAutomatic()) {
                    colors[i] = tints[i] == 0 ? AUTO_GREY : tints[i];
                } else {
                    Block b = Gradient.blockOfItemId(s.block);
                    colors[i] = b == null ? AUTO_GREY
                            : 0xFF000000 | BlockTextures.gradientValue(b, null, GradientMode.COLOR, 0.5);
                }
            }
            CACHE.put(key, colors);
        }
        return colors;
    }

    private static int segmentAt(Palette p, double t, double[] bounds) {
        return PaletteMath.indexFor(PaletteMath.curved(p.curve, t), bounds);
    }

    /**
     * One palette segment for an in-world preview: its block's item id (null for an Automatic
     * segment, which has no block until it is painted) and its flat colour.
     */
    public record Band(String block, int color) {}

    /**
     * The palette's gradient as {@code n} positions, start first, each naming the segment it
     * falls in (the same bands the strip draws; one {@link Band} object per segment). Empty when
     * the palette has no segments.
     */
    public static Band[] bands(Palette p, int n) {
        int[] colors = colorsFor(p);
        if (colors.length == 0 || n <= 0) return new Band[0];
        Band[] segs = new Band[colors.length];
        for (int i = 0; i < segs.length; i++) {
            PaletteSegment s = p.segments.get(i);
            segs[i] = new Band(s.isAutomatic() || s.block == null || s.block.isEmpty() ? null : s.block, colors[i]);
        }
        double[] bounds = PaletteMath.segmentBounds(colors.length, p.curve, p.stops);
        Band[] out = new Band[n];
        for (int i = 0; i < n; i++) out[i] = segs[segmentAt(p, (i + 0.5) / n, bounds)];
        return out;
    }

    /** The gradient as a horizontal strip, start on the left. */
    static void drawStrip(GuiGraphicsExtractor g, Palette p, int x, int y, int w, int h) {
        int[] colors = colorsFor(p);
        if (colors.length == 0 || w <= 0) return;
        double[] bounds = PaletteMath.segmentBounds(colors.length, p.curve, p.stops);
        int runStart = 0, runColor = colors[segmentAt(p, 0.5 / w, bounds)];
        for (int px = 1; px <= w; px++) {
            int c = px == w ? 0 : colors[segmentAt(p, (px + 0.5) / w, bounds)];
            if (c != runColor) {
                g.fill(x + runStart, y, x + px, y + h, runColor);
                runStart = px;
                runColor = c;
            }
        }
    }

    /** A top-down slice through the noise field, offset by (offX, offZ) blocks (drag to pan). */
    static void drawNoise(GuiGraphicsExtractor g, Palette p, int x, int y, int w, int h,
                          double offX, double offZ) {
        int[] colors = colorsFor(p);
        if (colors.length == 0) return;
        double[] bounds = PaletteMath.segmentBounds(colors.length, p.curve, p.stops);
        long seed = seedOf(p);
        int cols = (w + CELL - 1) / CELL, rows = (h + CELL - 1) / CELL;
        int ox = (int) Math.floor(offX), oz = (int) Math.floor(offZ);
        for (int r = 0; r < rows; r++) {
            int cy = y + r * CELL, ch = Math.min(CELL, y + h - cy);
            int runStart = x, runColor = 0;
            for (int c = 0; c <= cols; c++) {
                int color = 0;
                if (c < cols) {
                    double v = Noise.sample(p.noiseType, ox + c, 0, oz + r, seed,
                            p.noiseScaleX, p.noiseScaleY, p.noiseScaleZ);
                    color = colors[segmentAt(p, v, bounds)];
                }
                int cx = Math.min(x + w, x + c * CELL);
                if (color != runColor) {
                    if (runColor != 0) g.fill(runStart, cy, cx, cy + ch, runColor);
                    runStart = cx;
                    runColor = color;
                }
            }
        }
    }

    /** The seed as painting reads it: a number, else the text's hash; blank = 0. */
    private static long seedOf(Palette p) {
        String s = p.noiseSeed == null ? "" : p.noiseSeed.trim();
        if (s.isEmpty()) return 0L;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return s.hashCode();
        }
    }
}
