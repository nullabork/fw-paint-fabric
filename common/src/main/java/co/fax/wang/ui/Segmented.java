package co.fax.wang.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * A switch: 2–5 choices side by side, all visible, the chosen one filled. Replaces cycle buttons
 * wherever the choices fit, so you see the options without clicking through them. The host lays
 * it out with {@link #setBounds}, calls {@link #render} and routes clicks to {@link #click}.
 */
public final class Segmented {

    private final List<String> labels;
    private final IntConsumer onPick;
    private int selected;
    private boolean enabled = true;
    private int x, y, w, h = Ui.ROW_H;

    public Segmented(List<String> labels, int selected, IntConsumer onPick) {
        this.labels = List.copyOf(labels);
        this.selected = selected;
        this.onPick = onPick;
    }

    public Segmented setBounds(int x, int y, int w) {
        this.x = x; this.y = y; this.w = w;
        return this;
    }

    public void setSelected(int i) { selected = i; }
    public int selected() { return selected; }
    public void setEnabled(boolean e) { enabled = e; }
    public int x() { return x; }
    public int y() { return y; }
    public int width() { return w; }
    public int height() { return h; }

    private int cellX(int i) { return x + (int) Math.round((double) w * i / labels.size()); }

    public void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        int n = labels.size();
        for (int i = 0; i < n; i++) {
            int cx = cellX(i), cw = cellX(i + 1) - cx;
            boolean on = i == selected;
            boolean hover = enabled && Ui.inside(mouseX, mouseY, cx, y, cw, h);
            if (on) g.fill(cx, y, cx + cw, y + h, enabled ? 0xFF3D6FD9 : 0xFF3A3A3A);
            else if (hover) g.fill(cx, y, cx + cw, y + h, Ui.HOVER);
            if (i > 0 && !on && i - 1 != selected) g.fill(cx, y + 3, cx + 1, y + h - 3, Ui.LINE);
            String label = font.plainSubstrByWidth(labels.get(i), cw - 4);
            int color = !enabled ? Ui.DIM : on ? 0xFFFFFFFF : Ui.TEXT;
            g.text(font, label, cx + (cw - font.width(label)) / 2, y + (h - font.lineHeight) / 2 + 1, color);
        }
        Ui.outline(g, x, y, w, h, enabled ? Ui.BORDER : Ui.LINE);
    }

    /** The choice under the mouse, or -1 when it is off the switch (help popups describe that one). */
    public int cellAt(double mx, double my) {
        if (!Ui.inside(mx, my, x, y, w, h)) return -1;
        for (int i = 0; i < labels.size(); i++) {
            if (mx < cellX(i + 1)) return i;
        }
        return labels.size() - 1;
    }

    /** True when the click landed on the switch (picking a choice, or the one already chosen). */
    public boolean click(double mx, double my) {
        if (!enabled || !Ui.inside(mx, my, x, y, w, h)) return false;
        for (int i = 0; i < labels.size(); i++) {
            if (mx < cellX(i + 1)) {
                if (i != selected) {
                    selected = i;
                    onPick.accept(i);
                }
                return true;
            }
        }
        return true;
    }
}
