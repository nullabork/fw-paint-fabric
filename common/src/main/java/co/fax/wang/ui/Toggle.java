package co.fax.wang.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * An on/off row: label (and an optional grey note) on the left, a switch on the right. Reads its
 * state from a supplier so it always shows the live value. {@code session} marks a toggle that
 * resets on relaunch with the yellow dot; {@code applies} false greys the whole row out (it does
 * nothing in the current mode) but it stays clickable.
 */
public final class Toggle {

    private static final int SWITCH_W = 20, SWITCH_H = 10;

    private final String label;
    private final String note;
    private final BooleanSupplier value;
    private final Consumer<Boolean> onSet;
    private final boolean session;
    private BooleanSupplier applies = () -> true;
    private int x, y, w;

    public Toggle(String label, String note, boolean session, BooleanSupplier value, Consumer<Boolean> onSet) {
        this.label = label;
        this.note = note;
        this.session = session;
        this.value = value;
        this.onSet = onSet;
    }

    public Toggle applies(BooleanSupplier applies) {
        this.applies = applies;
        return this;
    }

    public Toggle setBounds(int x, int y, int w) {
        this.x = x; this.y = y; this.w = w;
        return this;
    }

    public int height(Font font) {
        return note == null || note.isEmpty() ? Ui.ROW_H : Ui.ROW_H + font.lineHeight;
    }

    public void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        boolean on = value.getAsBoolean();
        boolean live = applies.getAsBoolean();
        int h = height(font);
        if (Ui.inside(mouseX, mouseY, x, y, w, h)) g.fill(x - 2, y, x + w + 2, y + h, Ui.HOVER);
        int ty = y + (Ui.ROW_H - font.lineHeight) / 2 + 1;
        g.text(font, label, x, ty, live ? Ui.TEXT : Ui.DIM);
        if (session) Ui.sessionDot(g, x + font.width(label) + 4, ty + 2);
        if (note != null && !note.isEmpty()) {
            g.text(font, font.plainSubstrByWidth(note, w - SWITCH_W - 6), x, ty + font.lineHeight + 1,
                    live ? Ui.HINT : Ui.DIM);
        }
        int sx = x + w - SWITCH_W, sy = y + (Ui.ROW_H - SWITCH_H) / 2;
        g.fill(sx, sy, sx + SWITCH_W, sy + SWITCH_H, on ? (live ? 0xFF3D6FD9 : 0xFF3A4A6A) : Ui.LINE);
        int kx = on ? sx + SWITCH_W - SWITCH_H + 1 : sx + 1;
        g.fill(kx, sy + 1, kx + SWITCH_H - 2, sy + SWITCH_H - 1, live ? 0xFFFFFFFF : 0xFFB0B0B0);
    }

    public boolean click(double mx, double my, Font font) {
        if (!Ui.inside(mx, my, x, y, w, height(font))) return false;
        onSet.accept(!value.getAsBoolean());
        return true;
    }
}
