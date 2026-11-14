package co.fax.wang.wheel;

import co.fax.wang.ui.Ui;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The one way a wheel slot shows what it is, shared by the rings and the Recent bar: an optional
 * small grey path line on top (Recent only), then a 16px icon or the label word-wrapped to at most
 * {@link #MAX_LINES} lines of {@link #MAX_W}, then an optional small red note under it ("2
 * missing", "deleted"). {@link #of} measures once; {@link #draw} centres it on a point.
 */
final class SlotLabel {

    /** Widest a label line may be before it wraps (and, on the last line, is cut with "…"). */
    static final int MAX_W = 48;
    static final int MAX_LINES = 3;
    /** Scale of the path line and the note. */
    static final float SMALL = 0.75f;
    private static final int LINE_GAP = 1;
    /** Room after a label for the yellow session dot. */
    static final int DOT_W = 7;

    final String path;
    final List<String> lines;
    final ItemStack icon;
    final String note;
    final boolean dot;
    final int width, height;
    private final int lineH, smallH;

    private SlotLabel(Font font, String path, List<String> lines, ItemStack icon, String note, boolean dot) {
        this.path = path;
        this.lines = lines;
        this.icon = icon;
        this.note = note;
        this.dot = dot;
        this.lineH = font.lineHeight;
        this.smallH = Math.round(font.lineHeight * SMALL);
        int w = 0, h = 0;
        if (path != null) {
            w = Math.max(w, small(font, path));
            h += smallH + LINE_GAP + 1;
        }
        if (icon != null) {
            w = Math.max(w, 16);
            h += 16;
        } else {
            for (String l : lines) w = Math.max(w, font.width(l));
            if (dot) w += DOT_W;
            h += lines.size() * lineH + (lines.size() - 1) * LINE_GAP;
        }
        if (note != null) {
            w = Math.max(w, small(font, note));
            h += LINE_GAP + 1 + smallH;
        }
        this.width = w;
        this.height = h;
    }

    static SlotLabel of(Font font, String label, ItemStack icon, String note, boolean dot) {
        return new SlotLabel(font, null, icon != null ? List.of() : wrap(font, label, MAX_W), icon, note, dot);
    }

    /** A Recent-bar button: path on top, then the label (one line, wider) or icon + label. */
    static SlotLabel withPath(Font font, String path, String label, ItemStack icon, String note, int maxW) {
        List<String> lines = icon != null ? List.of() : wrap(font, label, maxW);
        return new SlotLabel(font, path == null || path.isEmpty() ? null : clip(font, path, maxW / SMALL),
                lines, icon, note, false);
    }

    static int small(Font font, String s) {
        return (int) Math.ceil(font.width(s) * SMALL);
    }

    /** Draw centred on (cx, cy). */
    void draw(GuiGraphicsExtractor g, Font font, int cx, int cy, int color) {
        int y = cy - height / 2;
        if (path != null) {
            drawSmall(g, font, path, cx, y, Ui.HINT);
            y += smallH + LINE_GAP + 1;
        }
        if (icon != null) {
            g.item(icon, cx - 8, y);
            y += 16;
        } else {
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                int lw = font.width(l) + (dot && i == lines.size() - 1 ? DOT_W : 0);
                g.text(font, l, cx - lw / 2, y, color);
                if (dot && i == lines.size() - 1) Ui.sessionDot(g, cx - lw / 2 + font.width(l) + 3, y + 2);
                y += lineH + LINE_GAP;
            }
            y -= LINE_GAP;
        }
        if (note != null) {
            y += LINE_GAP + 1;
            drawSmall(g, font, note, cx, y, Ui.MISSING);
        }
    }

    private static void drawSmall(GuiGraphicsExtractor g, Font font, String s, int cx, int y, int color) {
        g.pose().pushMatrix();
        g.pose().translate(cx - font.width(s) * SMALL / 2f, y);
        g.pose().scale(SMALL, SMALL);
        g.text(font, s, 0, 0, color);
        g.pose().popMatrix();
    }

    /** Word-wrap to {@link #MAX_LINES} lines of {@code maxW}; overflow ends the last line with "…". */
    static List<String> wrap(Font font, String text, int maxW) {
        List<String> out = new ArrayList<>();
        // Nearly fits: keep it whole rather than strand a short word ("gradient / 1").
        if (font.width(text) <= maxW * 5 / 4) {
            out.add(text);
            return out;
        }
        String line = "";
        String[] words = text.split(" ");
        int i = 0;
        for (; i < words.length; i++) {
            String next = line.isEmpty() ? words[i] : line + " " + words[i];
            if (!line.isEmpty() && font.width(next) > maxW) {
                out.add(line);
                line = words[i];
                if (out.size() == MAX_LINES - 1) { i++; break; }
            } else {
                line = next;
            }
        }
        StringBuilder rest = new StringBuilder(line);
        for (; i < words.length; i++) rest.append(' ').append(words[i]);
        // A single long word ("brightness") may run a little past maxW rather than be cut.
        int hard = maxW * 5 / 4;
        out.add(clip(font, rest.toString(), font.width(rest.toString()) > maxW && rest.indexOf(" ") >= 0 ? maxW : hard));
        for (int k = 0; k < out.size() - 1; k++) out.set(k, clip(font, out.get(k), hard));
        return out;
    }

    static String clip(Font font, String s, float maxW) {
        if (font.width(s) <= maxW) return s;
        return font.plainSubstrByWidth(s, (int) maxW - font.width("…")) + "…";
    }
}
