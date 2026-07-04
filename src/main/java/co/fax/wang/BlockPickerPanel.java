package co.fax.wang;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable scrollable list of block rows for the block picker. The host owns the Source + assign
 * buttons (vanilla widgets, for the native 3D look) and supplies the rows already coloured + tagged;
 * this panel just renders the list, scrolls, and reports which row was clicked. Used by the Gradient
 * and Noise tabs.
 */
public final class BlockPickerPanel {

    /** One list entry: the stack, its id, a text colour, a tag (e.g. " [S]"), and a name prefix (Pick #). */
    public record Row(net.minecraft.world.item.ItemStack stack, String id, int color, String tag, String prefix) {}

    public static final int ROW_H = 18;
    /** Height cap: 10½ rows — the half-cut row signals the list scrolls even before the bar shows. */
    public static final int MAX_H = ROW_H * 10 + ROW_H / 2;

    private static final int HOVER_BG = 0x33FFFFFF;
    private static final int TRACK = 0x30FFFFFF;
    private static final int THUMB = 0x90FFFFFF;

    private final Font font;
    private List<Row> rows = new ArrayList<>();
    private int scroll = 0;
    private int x, y, w, h;

    public BlockPickerPanel(Font font) { this.font = font; }

    /** Bounds of the list area (below the host's Source + assign buttons). */
    public void setBounds(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
    }

    public void setRows(List<Row> rows) {
        this.rows = rows;
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private int visibleRows() { return Math.max(1, h / ROW_H); }
    private int maxScroll() { return Math.max(0, rows.size() - visibleRows()); }

    /** Id of the row at the given coords (including a half-visible bottom row), or null. */
    public String rowIdAt(double mx, double my) {
        if (mx < x || mx > x + w || my < y || my >= y + h) return null;
        int idx = scroll + (int) ((my - y) / ROW_H);
        return (idx >= 0 && idx < rows.size()) ? rows.get(idx).id() : null;
    }

    public boolean mouseScrolled(double mx, double my, double dir) {
        if (mx < x || mx > x + w || my < y || my > y + h) return false;
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(dir)));
        return true;
    }

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // Scissor so the bottom row can be genuinely half-cut (a scroll affordance) instead of
        // stopping at the last full row.
        g.enableScissor(x, y, x + w, y + h);
        for (int i = scroll; i < rows.size(); i++) {
            int ry = y + (i - scroll) * ROW_H;
            if (ry >= y + h) break;
            Row row = rows.get(i);
            boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= ry && mouseY < ry + ROW_H;
            if (hover) g.fill(x, ry, x + w, ry + ROW_H, HOVER_BG);
            g.item(row.stack(), x + 1, ry + 1);
            String name = row.prefix() + row.stack().getHoverName().getString();
            String label = font.plainSubstrByWidth(name, w - 26 - font.width(row.tag())) + row.tag();
            g.text(font, label, x + 20, ry + 5, row.color());
        }
        g.disableScissor();

        // Skinny scrollbar on the right edge, only when there's something to scroll.
        if (maxScroll() > 0) {
            int trackX = x + w - 2;
            g.fill(trackX, y, x + w, y + h, TRACK);
            int thumbH = Math.max(8, h * visibleRows() / rows.size());
            int thumbY = y + (int) ((h - thumbH) * (double) scroll / maxScroll());
            g.fill(trackX, thumbY, x + w, thumbY + thumbH, THUMB);
        }
    }
}
