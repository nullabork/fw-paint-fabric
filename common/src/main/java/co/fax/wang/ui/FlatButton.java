package co.fax.wang.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.Supplier;

/**
 * A flat button in the kit's look (same outline and fill as {@link Segmented}): PLAIN, PRIMARY
 * (blue fill — one per panel) or DANGER (red text — destructive). The label is read live so a
 * count like "Clear all markers (7)" stays current. The host lays it out with {@link #setBounds},
 * calls {@link #render} and routes clicks to {@link #click}.
 */
public final class FlatButton {

    public enum Style { PLAIN, PRIMARY, DANGER }

    private final Supplier<String> label;
    private final Style style;
    private final Runnable onClick;
    private boolean enabled = true;
    private int x, y, w, h = Ui.ROW_H;

    public FlatButton(Supplier<String> label, Style style, Runnable onClick) {
        this.label = label;
        this.style = style;
        this.onClick = onClick;
    }

    public FlatButton(String label, Style style, Runnable onClick) {
        this(() -> label, style, onClick);
    }

    public FlatButton setBounds(int x, int y, int w) {
        this.x = x; this.y = y; this.w = w;
        return this;
    }

    public void setEnabled(boolean e) { enabled = e; }
    public int height() { return h; }

    /** The width the label needs, with padding (for packing buttons into a row). */
    public int preferredWidth(Font font) {
        return font.width(label.get()) + 12;
    }

    public void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        boolean hover = enabled && Ui.inside(mouseX, mouseY, x, y, w, h);
        if (style == Style.PRIMARY && enabled) g.fill(x, y, x + w, y + h, hover ? 0xFF4F7FE6 : 0xFF3D6FD9);
        else if (hover) g.fill(x, y, x + w, y + h, Ui.HOVER);
        Ui.outline(g, x, y, w, h, enabled ? Ui.BORDER : Ui.LINE);
        String text = font.plainSubstrByWidth(label.get(), w - 4);
        int color = !enabled ? Ui.DIM
                : style == Style.PRIMARY ? 0xFFFFFFFF
                : style == Style.DANGER ? Ui.MISSING : Ui.TEXT;
        g.text(font, text, x + (w - font.width(text)) / 2, y + (h - font.lineHeight) / 2 + 1, color);
    }

    /** True when the click landed on the button (the action runs only while enabled). */
    public boolean click(double mx, double my) {
        if (!Ui.inside(mx, my, x, y, w, h)) return false;
        if (enabled) onClick.run();
        return true;
    }
}
