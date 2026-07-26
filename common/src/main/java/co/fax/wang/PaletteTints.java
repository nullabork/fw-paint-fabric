package co.fax.wang;

import co.fax.wang.palette.AutoMode;
import co.fax.wang.palette.Palette;
import co.fax.wang.palette.PaletteSegment;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Representative display colours for a palette's Automatic segments — used to tint the
 * crosshatch in the editor strip, previews, palette list, HUD, and quick controls. Display only:
 * nothing to do with what placement resolves. {@code Auto colour} segments get colours (lerped
 * between neighbouring static blocks, or a position-stable rainbow when the strip has no
 * statics); {@code Auto brightness} segments get grey shades instead (the lerped colour's
 * luminance, or a positional dark→light ramp). Static segments are 0. Memoized by palette
 * content so nothing recomputes per frame.
 */
final class PaletteTints {

    private PaletteTints() {}

    private static final Map<String, int[]> CACHE = new HashMap<>();

    /** Per-segment ARGB crosshatch tints (0 for static segments), cached by palette content. */
    static int[] forPalette(Palette p) {
        String key = p.contentKey();
        int[] tints = CACHE.get(key);
        if (tints == null) {
            if (CACHE.size() > 32) CACHE.clear(); // editors churn keys — keep the cache tiny
            tints = compute(p.segments);
            CACHE.put(key, tints);
        }
        return tints;
    }

    /** Uncached compute (the editor calls this directly on its working copy's change events). */
    static int[] compute(List<PaletteSegment> segments) {
        int n = segments.size();
        int[] out = new int[n];
        int[] staticColor = new int[n];
        boolean anyStatic = false;
        for (int i = 0; i < n; i++) {
            PaletteSegment s = segments.get(i);
            if (!s.isAutomatic()) {
                anyStatic = true;
                Block b = Gradient.blockOfItemId(s.block);
                staticColor[i] = b == null ? 0x808080
                        : BlockTextures.gradientValue(b, null, GradientMode.COLOR, 0.5);
            }
        }
        if (!anyStatic) {
            // No statics to anchor to — a generic, position-stable ramp: rainbow for colour
            // autos, a dark→light shade ramp for brightness autos.
            for (int i = 0; i < n; i++) {
                double pos = n <= 1 ? 0 : i / (double) (n - 1);
                if (segments.get(i).auto == AutoMode.BRIGHTNESS) {
                    int v = (int) Math.round(0x30 + pos * (0xE8 - 0x30));
                    out[i] = (v << 16) | (v << 8) | v;
                } else {
                    out[i] = ColorOrder.hsvToRgb(300.0 * pos, 0.65, 0.95);
                }
            }
        } else {
            // The next static colour strictly after each index (mirrors placement's anchors).
            int[] nextC = new int[n];
            boolean[] hasNext = new boolean[n];
            int ahead = 0;
            boolean has = false;
            for (int i = n - 1; i >= 0; i--) {
                hasNext[i] = has;
                nextC[i] = ahead;
                if (!segments.get(i).isAutomatic()) {
                    ahead = staticColor[i];
                    has = true;
                }
            }
            int prevC = 0;
            boolean hasPrev = false;
            int i = 0;
            while (i < n) {
                if (!segments.get(i).isAutomatic()) {
                    prevC = staticColor[i];
                    hasPrev = true;
                    i++;
                    continue;
                }
                int runEnd = i;
                while (runEnd + 1 < n && segments.get(runEnd + 1).isAutomatic()) runEnd++;
                int from = hasPrev ? prevC : (hasNext[runEnd] ? nextC[runEnd] : 0x808080);
                int to = hasNext[runEnd] ? nextC[runEnd] : (hasPrev ? prevC : 0x808080);
                int k = runEnd - i + 1;
                for (int j = 0; j < k; j++) {
                    int rgb = lerpRgb(from, to, (j + 1) / (double) (k + 1));
                    if (segments.get(i + j).auto == AutoMode.BRIGHTNESS) {
                        int v = (int) Math.round(GradientRamp.brightness(rgb));
                        rgb = (v << 16) | (v << 8) | v; // brightness autos show as shades
                    }
                    out[i + j] = rgb;
                }
                prevC = out[runEnd];
                hasPrev = true;
                i = runEnd + 1;
            }
        }
        for (int i = 0; i < n; i++) {
            out[i] = segments.get(i).isAutomatic() ? 0xFF000000 | (out[i] & 0xFFFFFF) : 0;
        }
        return out;
    }

    private static int lerpRgb(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
