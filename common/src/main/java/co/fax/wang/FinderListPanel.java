package co.fax.wang;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/**
 * Scrollable list of {@link ColorIndex.Entry} rows for the Finder tab. Each row shows the item
 * icon on a contrasting backdrop, an optional solid swatch of the block's computed colour, and the
 * name. Unlike {@link BlockPickerPanel} it supports a selection highlight, a coloured insertion
 * marker (where a pure colour would fall in the ordering), centering the view on a row, and a
 * clickable/draggable scrollbar — the whole game's block list is ~1100 rows.
 */
public final class FinderListPanel {

    public static final int ROW_H = 18;

    private static final int HOVER_BG = 0x33FFFFFF;
    private static final int SELECTED_BG = 0x3355FF55;
    private static final int TRACK = 0x30FFFFFF;
    private static final int THUMB = 0x90FFFFFF;
    private static final int ICON_BG = 0xFF6E6E6E;
    private static final int SWATCH_BORDER = 0x60FFFFFF;
    private static final int GREEN = 0xFF55FF55;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFC0C0C0;
    private static final int SCROLL_STEP = 3;
    private static final int BAR_HIT_W = 6; // wider than the drawn 2px track so it's grabbable

    private final Font font;
    private List<ColorIndex.Entry> entries = List.of();
    private int scroll = 0;
    private int x, y, w, h;
    private boolean showSwatch = true;
    private String selectedId;
    private int markerIndex = -1;
    private int markerRgb;
    private boolean draggingBar;

    public FinderListPanel(Font font) { this.font = font; }

    public void setBounds(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    public void setEntries(List<ColorIndex.Entry> entries) {
        this.entries = entries;
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    public void setShowSwatch(boolean show) { showSwatch = show; }

    public void setSelectedId(String id) { selectedId = id; }

    /** Mark the row boundary where a pure colour would insert (bar drawn in that colour). */
    public void setMarker(int index, int rgb) { markerIndex = index; markerRgb = rgb; }

    public void clearMarker() { markerIndex = -1; }

    /** Scroll so the given row index sits in the middle of the visible area. */
    public void centerOn(int index) {
        scroll = Math.max(0, Math.min(maxScroll(), index - visibleRows() / 2));
    }

    /** Index of the entry with this id, or -1. */
    public int indexOfId(String id) {
        if (id == null || id.isEmpty()) return -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    /** Id of the row at the given coords (excluding the scrollbar strip), or null. */
    public String idAt(double mx, double my) {
        if (mx < x || mx > x + w - BAR_HIT_W || my < y || my >= y + h) return null;
        int idx = scroll + (int) ((my - y) / ROW_H);
        return (idx >= 0 && idx < entries.size()) ? entries.get(idx).id() : null;
    }

    private int visibleRows() { return Math.max(1, h / ROW_H); }
    private int maxScroll() { return Math.max(0, entries.size() - visibleRows()); }

    public boolean mouseScrolled(double mx, double my, double dir) {
        if (mx < x || mx > x + w || my < y || my > y + h) return false;
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(dir) * SCROLL_STEP));
        return true;
    }

    /** Left-click in the scrollbar strip jumps the thumb there and starts a drag. */
    public boolean mouseClicked(double mx, double my) {
        draggingBar = false;
        if (maxScroll() == 0 || mx < x + w - BAR_HIT_W || mx > x + w || my < y || my > y + h) return false;
        draggingBar = true;
        dragTo(my);
        return true;
    }

    public boolean mouseDragged(double my) {
        if (!draggingBar) return false;
        dragTo(my);
        return true;
    }

    private void dragTo(double my) {
        int thumbH = thumbH();
        double t = (my - y - thumbH / 2.0) / Math.max(1, h - thumbH);
        scroll = (int) Math.round(Math.max(0, Math.min(1, t)) * maxScroll());
    }

    private int thumbH() { return Math.max(8, h * visibleRows() / Math.max(1, entries.size())); }

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.enableScissor(x, y, x + w, y + h);
        int textX = showSwatch ? x + 39 : x + 22;
        for (int i = scroll; i < entries.size(); i++) {
            int ry = y + (i - scroll) * ROW_H;
            if (ry >= y + h) break;
            ColorIndex.Entry e = entries.get(i);
            boolean selected = e.id().equals(selectedId);
            boolean hover = mouseX >= x && mouseX <= x + w - BAR_HIT_W && mouseY >= ry && mouseY < ry + ROW_H;
            if (selected) g.fill(x, ry, x + w, ry + ROW_H, SELECTED_BG);
            else if (hover) g.fill(x, ry, x + w, ry + ROW_H, HOVER_BG);
            g.fill(x, ry, x + ROW_H, ry + ROW_H, ICON_BG); // contrasting backdrop behind the icon
            g.item(e.stack(), x + 1, ry + 1);
            if (showSwatch) {
                g.fill(x + 20, ry + 1, x + 36, ry + 17, SWATCH_BORDER);
                g.fill(x + 21, ry + 2, x + 35, ry + 16, 0xFF000000 | e.rgb());
            }
            int color = selected ? GREEN : (hover ? WHITE : GREY);
            g.text(font, font.plainSubstrByWidth(e.name(), w - (textX - x) - 4), textX, ry + 5, color);
        }

        // Insertion marker: a bar in the picked colour at the row boundary where it would sort.
        if (markerIndex >= 0) {
            int my = y + (markerIndex - scroll) * ROW_H;
            if (my >= y - 2 && my <= y + h + 2) {
                g.fill(x, my - 2, x + w - 3, my + 2, WHITE);
                g.fill(x, my - 1, x + w - 3, my + 1, 0xFF000000 | markerRgb);
            }
        }
        g.disableScissor();

        if (maxScroll() > 0) {
            int trackX = x + w - 2;
            g.fill(trackX, y, x + w, y + h, TRACK);
            int thumbH = thumbH();
            int thumbY = y + (int) ((h - thumbH) * (double) scroll / maxScroll());
            g.fill(trackX, thumbY, x + w, thumbY + thumbH, THUMB);
        }
    }
}
