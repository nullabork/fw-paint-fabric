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

    /** One list entry: the stack, its id, a text colour, and any tag (e.g. " [S]"). */
    public record Row(net.minecraft.world.item.ItemStack stack, String id, int color, String tag) {}

    private static final int ROW_H = 18;
    private static final int HOVER_BG = 0x33FFFFFF;

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

    /** Id of the row at the given coords, or null. */
    public String rowIdAt(double mx, double my) {
        if (mx < x || mx > x + w || my < y || my >= y + visibleRows() * ROW_H) return null;
        int idx = scroll + (int) ((my - y) / ROW_H);
        return (idx >= 0 && idx < rows.size()) ? rows.get(idx).id() : null;
    }

    public boolean mouseScrolled(double mx, double my, double dir) {
        if (mx < x || mx > x + w || my < y || my > y + h) return false;
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(dir)));
        return true;
    }

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        for (int i = scroll; i < rows.size(); i++) {
            int ry = y + (i - scroll) * ROW_H;
            if (ry + ROW_H > y + h) break;
            Row row = rows.get(i);
            boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= ry && mouseY < ry + ROW_H;
            if (hover) g.fill(x, ry, x + w, ry + ROW_H, HOVER_BG);
            g.item(row.stack(), x + 1, ry + 1);
            String name = row.stack().getHoverName().getString();
            String label = font.plainSubstrByWidth(name, w - 26 - font.width(row.tag())) + row.tag();
            g.text(font, label, x + 20, ry + 5, row.color());
        }
    }
}
