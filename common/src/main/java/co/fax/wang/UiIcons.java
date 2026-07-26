package co.fax.wang;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Tiny shared pixel-art UI glyphs used by the FW Paint screens. */
final class UiIcons {

    private UiIcons() {}

    /** A small circled question mark, 9×9 px, drawn pixel by pixel. */
    static void drawHelpIcon(GuiGraphicsExtractor g, int x, int y) {
        int c = 0xFFB0B0B0;
        // Circle outline (radius ~4).
        g.fill(x + 3, y, x + 6, y + 1, c);         // top
        g.fill(x + 3, y + 8, x + 6, y + 9, c);     // bottom
        g.fill(x, y + 3, x + 1, y + 6, c);         // left
        g.fill(x + 8, y + 3, x + 9, y + 6, c);     // right
        g.fill(x + 1, y + 1, x + 3, y + 2, c);     // corners
        g.fill(x + 6, y + 1, x + 8, y + 2, c);
        g.fill(x + 1, y + 7, x + 3, y + 8, c);
        g.fill(x + 6, y + 7, x + 8, y + 8, c);
        g.fill(x + 1, y + 2, x + 2, y + 3, c);
        g.fill(x + 7, y + 2, x + 8, y + 3, c);
        g.fill(x + 1, y + 6, x + 2, y + 7, c);
        g.fill(x + 7, y + 6, x + 8, y + 7, c);
        // The "?" glyph, 3×5, centred.
        int q = 0xFFE0E0E0;
        g.fill(x + 3, y + 2, x + 6, y + 3, q);     // top bar
        g.fill(x + 5, y + 3, x + 6, y + 4, q);     // right descender
        g.fill(x + 4, y + 4, x + 5, y + 5, q);     // middle
        g.fill(x + 4, y + 6, x + 5, y + 7, q);     // dot
    }

    /** 1px rectangle outline. */
    static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }
}
