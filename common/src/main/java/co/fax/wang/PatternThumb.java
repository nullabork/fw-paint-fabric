package co.fax.wang;

import co.fax.wang.palette.Palette;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.Map;

/**
 * Tiny flat-colour thumbnail of a pattern's grid (average texture colour per cell, holes left
 * dark) — used on the HUD, palette list rows, and Paint tab. Cell colours are memoized by the
 * pattern's content so nothing samples textures per frame.
 */
final class PatternThumb {

    private PatternThumb() {}

    private static final Map<String, int[]> CACHE = new HashMap<>();
    private static final int HOLE = 0x30000000;

    private static int[] colorsFor(Palette pat) {
        String key = pat.contentKey();
        int[] colors = CACHE.get(key);
        if (colors == null) {
            if (CACHE.size() > 32) CACHE.clear();
            colors = new int[pat.width * pat.height];
            for (int v = 0; v < pat.height; v++) {
                for (int u = 0; u < pat.width; u++) {
                    String id = pat.cellAt(u, v);
                    int c = 0;
                    if (id != null && !id.isEmpty()) {
                        Block b = Gradient.blockOfItemId(id);
                        if (b != null) {
                            c = 0xFF000000
                                    | (BlockTextures.gradientValue(b, null, GradientMode.COLOR, 0.5) & 0xFFFFFF);
                        }
                    }
                    colors[v * pat.width + u] = c; // 0 = hole
                }
            }
            CACHE.put(key, colors);
        }
        return colors;
    }

    /**
     * Draw the pattern scaled into a {@code size}×{@code size} box at (x, y), start row at the
     * top, centred, each cell a flat colour square (at least 1px).
     */
    static void draw(GuiGraphicsExtractor g, Palette pat, int x, int y, int size) {
        int[] colors = colorsFor(pat);
        int px = Math.max(1, size / Math.max(pat.width, pat.height));
        int tw = px * pat.width, th = px * pat.height;
        int ox = x + (size - tw) / 2, oy = y + (size - th) / 2;
        g.fill(ox - 1, oy - 1, ox + tw + 1, oy + th + 1, 0x60000000);
        for (int v = 0; v < pat.height; v++) {
            for (int u = 0; u < pat.width; u++) {
                int c = colors[v * pat.width + u];
                int cx = ox + u * px, cy = oy + v * px;
                g.fill(cx, cy, cx + px, cy + px, c == 0 ? HOLE : c);
            }
        }
    }
}
