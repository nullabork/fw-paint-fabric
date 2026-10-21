package co.fax.wang.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The top bar both editors share, in place of the main screen's tab bar:
 * {@code ‹ Paint  /  PALETTE  [name]  ● unsaved changes  …  Cancel  Save}. The host owns the
 * name {@code EditBox} (it needs keyboard focus) and places it at {@link #nameX()} etc.; this
 * class lays out, draws and hit-tests the rest. "‹ Paint" does the same as Cancel. Also holds the
 * few drawing helpers only the editors use (numbered section headings, dashed outlines, the
 * primary/plain buttons).
 */
public final class EditorChrome {

    /** Bar height; the editors' content starts below it. */
    public static final int H = 24;
    /** Height of the name box inside the bar. */
    public static final int NAME_H = 16;

    public enum Hit { NONE, BACK, CANCEL, SAVE }

    private static final String BACK = "‹ Paint";
    private static final int PAD = 8, GAP = 6, BTN_H = 16;

    private final String kind; // upper-case crumb, "PALETTE" / "PATTERN"
    private int backX, backW, slashX, kindX, nameX, nameW, noteX, cancelX, cancelW, saveX, saveW;
    private int screenW;

    public EditorChrome(String kind) {
        this.kind = kind.toUpperCase(java.util.Locale.ROOT);
    }

    /** Lay the bar out for a screen {@code screenW} wide (call from the host's init). */
    public void layout(Font font, int screenW) {
        this.screenW = screenW;
        backX = PAD;
        backW = font.width(BACK);
        slashX = backX + backW + GAP;
        kindX = slashX + font.width("/") + GAP;
        nameX = kindX + font.width(kind) + GAP + 2;
        saveW = font.width("Save") + 16;
        cancelW = font.width("Cancel") + 12;
        saveX = screenW - PAD - saveW;
        cancelX = saveX - 4 - cancelW;
        // The name box takes what's left after leaving room for the unsaved note.
        int room = cancelX - GAP - nameX;
        int noteRoom = Math.min(font.width("● unsaved changes") + GAP, Math.max(0, room - 60));
        nameW = Math.max(40, Math.min(160, room - noteRoom));
        noteX = nameX + nameW + GAP;
    }

    public int nameX() { return nameX; }
    public int nameY() { return (H - NAME_H) / 2; }
    public int nameW() { return nameW; }

    /**
     * Draw the bar (everything except the name box, which the host renders as a widget).
     * {@code error} (red) replaces the unsaved note when set, e.g. a duplicate name.
     */
    public void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY, boolean dirty, String error) {
        g.fill(0, 0, screenW, H, 0xF01A1A1A);
        g.fill(0, H, screenW, H + 1, Ui.LINE);
        int ty = (H - font.lineHeight) / 2 + 1;
        boolean backHover = Ui.inside(mouseX, mouseY, backX - 2, 0, backW + 4, H);
        g.text(font, BACK, backX, ty, backHover ? Ui.TEXT : Ui.HINT);
        g.text(font, "/", slashX, ty, Ui.DIM);
        g.text(font, kind, kindX, ty, Ui.HINT);
        int room = cancelX - GAP - noteX;
        if (error != null && !error.isEmpty()) {
            g.text(font, font.plainSubstrByWidth(error, room), noteX, ty, Ui.MISSING);
        } else if (dirty) {
            String note = font.width("● unsaved changes") <= room ? "● unsaved changes"
                    : font.width("● unsaved") <= room ? "● unsaved" : "●";
            if (font.width(note) <= room) g.text(font, note, noteX, ty, Ui.ATTENTION);
        }
        int by = (H - BTN_H) / 2;
        plainButton(g, font, cancelX, by, cancelW, BTN_H, "Cancel", Ui.TEXT, mouseX, mouseY);
        primaryButton(g, font, saveX, by, saveW, BTN_H, "Save", mouseX, mouseY);
    }

    public Hit click(double mx, double my) {
        if (my < 0 || my >= H) return Hit.NONE;
        if (mx >= backX - 2 && mx < backX + backW + 2) return Hit.BACK;
        if (mx >= cancelX && mx < cancelX + cancelW) return Hit.CANCEL;
        if (mx >= saveX && mx < saveX + saveW) return Hit.SAVE;
        return Hit.NONE;
    }

    // ---- shared editor drawing helpers -----------------------------------------------------------

    /**
     * A section heading with a blue step number in front ("1 PICK BLOCKS"); {@code num} null for
     * an un-numbered one. Leaves {@code reserveRight} px free at the right end of the title line
     * (for a ? icon or a button). Returns the y just under it.
     */
    public static int section(GuiGraphicsExtractor g, Font font, int x, int y, int w, String num,
                              String title, String note, int reserveRight) {
        int nx = x;
        if (num != null) {
            g.text(font, num, x, y, Ui.SELECTED);
            nx = x + font.width(num) + 5;
        }
        int out = Ui.section(g, font, nx, y, Math.max(0, x + w - reserveRight - nx), title, note);
        // Run the rule under the number and the reserved space too, so it spans the column.
        g.fill(x, y + font.lineHeight + 1, x + w, y + font.lineHeight + 2, Ui.LINE);
        return out;
    }

    /** A 1px dashed outline (the pinned Auto rows, "wildcard" things). */
    public static void dashedOutline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        for (int i = 0; i < w; i += 4) {
            int e = Math.min(x + w, x + i + 2);
            g.fill(x + i, y, e, y + 1, color);
            g.fill(x + i, y + h - 1, e, y + h, color);
        }
        for (int i = 0; i < h; i += 4) {
            int e = Math.min(y + h, y + i + 2);
            g.fill(x, y + i, x + 1, e, color);
            g.fill(x + w - 1, y + i, x + w, e, color);
        }
    }

    /** The one blue primary button of a panel. */
    public static void primaryButton(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                                     String label, int mouseX, int mouseY) {
        boolean hover = Ui.inside(mouseX, mouseY, x, y, w, h);
        g.fill(x, y, x + w, y + h, hover ? 0xFF5584E6 : 0xFF3D6FD9);
        g.text(font, label, x + (w - font.width(label)) / 2, y + (h - font.lineHeight) / 2 + 1, 0xFFFFFFFF);
    }

    /** A plain outlined button; pass {@link Ui#MISSING} as {@code color} for a destructive one. */
    public static void plainButton(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                                   String label, int color, int mouseX, int mouseY) {
        boolean hover = Ui.inside(mouseX, mouseY, x, y, w, h);
        if (hover) g.fill(x, y, x + w, y + h, Ui.HOVER);
        Ui.outline(g, x, y, w, h, Ui.BORDER);
        String l = font.plainSubstrByWidth(label, w - 4);
        g.text(font, l, x + (w - font.width(l)) / 2, y + (h - font.lineHeight) / 2 + 1, color);
    }
}
