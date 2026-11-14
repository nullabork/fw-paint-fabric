package co.fax.wang.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * A vertical stack of rows (sections, switches, toggles, sliders, buttons) that scrolls with the
 * wheel when it outgrows its box — so a column of settings still works in a short window. Rows
 * lay their parts out while rendering, at the scrolled position, so a click always hits what was
 * drawn; clicks outside the visible box are ignored.
 */
public final class FlowColumn {

    /** One row: its height and how it draws; the input hooks default to "not mine". */
    public interface Row {
        int height(Font font);

        void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, int mouseX, int mouseY);

        default boolean click(double mx, double my, int button) { return false; }

        default boolean drag(double mx, double my) { return false; }

        default boolean release() { return false; }
    }

    private static final int GAP = 3;

    private final List<Row> rows = new ArrayList<>();
    private int x, y, w, h;
    private int scroll;

    public FlowColumn add(Row row) {
        rows.add(row);
        return this;
    }

    public FlowColumn setBounds(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
        return this;
    }

    public int contentHeight(Font font) {
        int total = 0;
        for (Row r : rows) total += r.height(font) + GAP;
        return Math.max(0, total - GAP);
    }

    public boolean inside(double mx, double my) {
        return Ui.inside(mx, my, x, y, w, h);
    }

    public void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        int content = contentHeight(font);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, content - h)));
        boolean in = inside(mouseX, mouseY);
        int mx = in ? mouseX : -10000, my = in ? mouseY : -10000;
        g.enableScissor(x - 3, y, x + w + 3, y + h);
        int ry = y - scroll;
        for (Row r : rows) {
            int rh = r.height(font);
            // Render every row (even off-screen) so its parts' bounds stay current; the scissor
            // keeps the off-screen ones invisible.
            r.render(g, font, x, ry, w, mx, my);
            ry += rh + GAP;
        }
        g.disableScissor();
        if (content > h) {
            int trackX = x + w + 4;
            g.fill(trackX, y, trackX + 2, y + h, 0x30FFFFFF);
            int thumbH = Math.max(8, h * h / content);
            int thumbY = y + (int) ((h - thumbH) * (double) scroll / (content - h));
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0x90FFFFFF);
        }
    }

    public boolean click(double mx, double my, int button) {
        if (!inside(mx, my)) return false;
        for (Row r : rows) {
            if (r.click(mx, my, button)) return true;
        }
        return false;
    }

    public boolean drag(double mx, double my) {
        for (Row r : rows) {
            if (r.drag(mx, my)) return true;
        }
        return false;
    }

    public boolean release() {
        boolean any = false;
        for (Row r : rows) any |= r.release();
        return any;
    }

    public boolean mouseScrolled(double mx, double my, double dir, Font font) {
        if (!inside(mx, my) || contentHeight(font) <= h) return false;
        scroll -= (int) (Math.signum(dir) * 14);
        scroll = Math.max(0, Math.min(scroll, contentHeight(font) - h));
        return true;
    }
}
