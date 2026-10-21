package co.fax.wang.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The small design system every FW Paint screen draws with: one set of state colours (the same
 * meaning on the screen, the wheel and the HUD) plus the few drawing helpers the parts share. The
 * interactive parts are {@link Segmented} (a switch of 2–5 visible choices) and {@link Toggle}
 * (an on/off row) — plain classes the host screen lays out, renders and routes clicks to, like
 * the other custom panels.
 */
public final class Ui {
    private Ui() {}

    // ---- colours: one meaning each ----------------------------------------------------------------

    /** Primary text. */
    public static final int TEXT = 0xFFEDEDED;
    /** Secondary text: hints, captions, descriptions. */
    public static final int HINT = 0xFFA8A8A8;
    /** Disabled / not applicable. */
    public static final int DIM = 0xFF6A6A6A;
    /** Selected — what you clicked here. */
    public static final int SELECTED = 0xFF6C9BF2;
    public static final int SELECTED_FILL = 0x336C9BF2;
    /** In use — what will paint now. */
    public static final int IN_USE = 0xFF6BCB8B;
    /** Missing blocks, destructive actions. */
    public static final int MISSING = 0xFFF28B82;
    /** Attention: a non-default option, or a session-only toggle. */
    public static final int ATTENTION = 0xFFE0B000;
    /** Section rules and control borders. */
    public static final int LINE = 0xFF4A4A4A;
    public static final int BORDER = 0xFF6A6A6A;
    public static final int HOVER = 0x22FFFFFF;
    public static final int PANEL = 0x60000000;

    // In-world marker colours, by what a control does when dragged.
    public static final int START = 0xFF2BB5B0;
    public static final int END = 0xFFE89A2C;
    public static final int MOVE = 0xFFFFFFFF;
    public static final int SIZE = 0xFF4CC46A;
    public static final int TURN = 0xFF4A7BE0;
    public static final int BEND = 0xFFE46FB0;
    public static final int OPTIONS = 0xFF9A9A9A;

    /** Height of a {@link #section} heading, including the gap under its rule. */
    public static final int SECTION_H = 14;
    /** Height of one control row ({@link Segmented}, {@link Toggle}). */
    public static final int ROW_H = 16;

    // ---- drawing helpers ---------------------------------------------------------------------------

    public static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /**
     * A section heading: an upper-case title on a rule, with an optional grey note right-aligned
     * on the same line. Returns the y just under it.
     */
    public static int section(GuiGraphicsExtractor g, Font font, int x, int y, int w, String title, String note) {
        g.text(font, title.toUpperCase(java.util.Locale.ROOT), x, y, TEXT);
        if (note != null && !note.isEmpty()) {
            String n = font.plainSubstrByWidth(note, Math.max(0, w - font.width(title) - 12));
            g.text(font, n, x + w - font.width(n), y, HINT);
        }
        g.fill(x, y + font.lineHeight + 1, x + w, y + font.lineHeight + 2, LINE);
        return y + SECTION_H;
    }

    /** One grey hint line (how to use the list above it), clipped to {@code w}. */
    public static void hint(GuiGraphicsExtractor g, Font font, int x, int y, int w, String text) {
        g.text(font, font.plainSubstrByWidth(text, w), x, y, HINT);
    }

    /** The small yellow square that marks a session-only toggle (it resets on relaunch). */
    public static void sessionDot(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x, y, x + 4, y + 4, ATTENTION);
    }

    public static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
