package co.fax.wang.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * A number on a track: label left, value (with units) right, the track underneath. The value is
 * a 0–1 fraction; the host maps it to its own range in {@code valueText} and {@code onChange}
 * (called on every drag step) and persists in {@code onRelease}. Route clicks, drags and the
 * mouse release to it.
 */
public final class ValueSlider {

    private static final int HEIGHT = 20;

    private final String label;
    private final DoubleFunction<String> valueText;
    private final DoubleConsumer onChange;
    private final Runnable onRelease;
    private double value;
    private boolean dragging;
    private int x, y, w;

    public ValueSlider(String label, double value, DoubleFunction<String> valueText,
                       DoubleConsumer onChange, Runnable onRelease) {
        this.label = label;
        this.value = clamp(value);
        this.valueText = valueText;
        this.onChange = onChange;
        this.onRelease = onRelease;
    }

    public ValueSlider setBounds(int x, int y, int w) {
        this.x = x; this.y = y; this.w = w;
        return this;
    }

    public int height() { return HEIGHT; }

    public void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        boolean hover = dragging || Ui.inside(mouseX, mouseY, x, y, w, HEIGHT);
        if (hover) g.fill(x - 2, y, x + w + 2, y + HEIGHT, Ui.HOVER);
        String v = valueText.apply(value);
        int vw = font.width(v);
        g.text(font, font.plainSubstrByWidth(label, w - vw - 6), x, y + 2, Ui.TEXT);
        g.text(font, v, x + w - vw, y + 2, Ui.TEXT);
        int ty = y + 14;
        int knob = x + (int) Math.round(value * (w - 4));
        g.fill(x, ty, x + w, ty + 2, Ui.LINE);
        g.fill(x, ty, knob + 2, ty + 2, 0xFF3D6FD9);
        g.fill(knob, ty - 3, knob + 4, ty + 5, 0xFFFFFFFF);
    }

    public boolean click(double mx, double my) {
        if (!Ui.inside(mx, my, x, y, w, HEIGHT)) return false;
        dragging = true;
        set(mx);
        return true;
    }

    public boolean drag(double mx) {
        if (!dragging) return false;
        set(mx);
        return true;
    }

    public boolean release() {
        if (!dragging) return false;
        dragging = false;
        onRelease.run();
        return true;
    }

    private void set(double mx) {
        double v = clamp((mx - x - 2) / Math.max(1.0, w - 4));
        if (v != value) {
            value = v;
            onChange.accept(v);
        }
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
