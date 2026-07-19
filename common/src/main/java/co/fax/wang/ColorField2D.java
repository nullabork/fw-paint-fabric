package co.fax.wang;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A 2D colour field for the Finder tab: hue left → right, saturation top (1) → bottom (0), drawn
 * at the current brightness so the field shows the colours you'd actually get. The host owns the
 * hue/sat/brightness state and routes clicks/drags here (like the noise preview does).
 */
public final class ColorField2D {

    private static final int CELL = 4;

    private int x, y, w, h;

    public void setBounds(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Hue (0..360) and saturation (0..1) at the given mouse position, clamped to the field. */
    public double[] pick(double mx, double my) {
        double hue = clamp01((mx - x) / Math.max(1, w - 1)) * 360.0;
        double sat = 1.0 - clamp01((my - y) / Math.max(1, h - 1));
        return new double[]{hue, sat};
    }

    public void render(GuiGraphicsExtractor g, double hue, double sat, double brightness) {
        for (int cy = 0; cy < h; cy += CELL) {
            double cellSat = 1.0 - (cy + CELL / 2.0) / h;
            int cellH = Math.min(CELL, h - cy);
            for (int cx = 0; cx < w; cx += CELL) {
                double cellHue = (cx + CELL / 2.0) / w * 360.0;
                int cellW = Math.min(CELL, w - cx);
                int rgb = ColorOrder.hsvToRgb(cellHue, cellSat, brightness);
                g.fill(x + cx, y + cy, x + cx + cellW, y + cy + cellH, 0xFF000000 | rgb);
            }
        }

        // Crosshair at the current pick: black shadow under a white cross so it reads on any colour.
        int px = x + (int) Math.round(hue / 360.0 * (w - 1));
        int py = y + (int) Math.round((1.0 - sat) * (h - 1));
        px = Math.max(x, Math.min(x + w - 1, px));
        py = Math.max(y, Math.min(y + h - 1, py));
        g.fill(px - 4, py - 1, px + 5, py + 2, 0xFF000000);
        g.fill(px - 1, py - 4, px + 2, py + 5, 0xFF000000);
        g.fill(px - 4, py, px + 5, py + 1, 0xFFFFFFFF);
        g.fill(px, py - 4, px + 1, py + 5, 0xFFFFFFFF);
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
