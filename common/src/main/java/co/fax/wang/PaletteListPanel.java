package co.fax.wang;

import co.fax.wang.palette.Palette;
import co.fax.wang.palette.PaletteSegment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The Palette tab's scrollable list of saved palettes: name on the left, the segment sprites lined
 * up on the right (Automatic segments as a crosshatch tile), a ▸ chevron when collapsed. Exactly
 * one row can be expanded — it shows a summary of every setting plus the vertical missing-blocks
 * list. Rows with missing blocks get a red border and a "missing blocks" line; the selected row a
 * white outline. The host owns the New/Use/Edit/Delete buttons and rebuilds entries on change.
 */
public final class PaletteListPanel {

    /** One palette plus everything precomputed for display (sprites, missing info). */
    public record Entry(Palette palette, List<ItemStack> sprites, List<Boolean> auto,
                        List<ItemStack> missingStacks, List<String> missingNames) {
        public boolean missing() {
            return !missingNames.isEmpty();
        }
    }

    private static final int ROW_H = 26;
    private static final int MISSING_EXTRA = 11;  // extra height for the "missing blocks" line
    private static final int ROW_GAP = 4;
    private static final int PAD = 4;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFA0A0A0;
    private static final int RED = 0xFFFF5555;
    private static final int YELLOW = 0xFFFFE34D;
    private static final int ROW_BG = 0x66000000;
    private static final int HOVER_BG = 0x33FFFFFF;
    private static final int BORDER = 0x60FFFFFF;
    private static final int SUMMARY_BG = 0x40000000;

    private final Font font;
    private List<Entry> entries = new ArrayList<>();
    private String selectedId = "";
    private String expandedId = "";
    private String activeId = "";
    private int scroll;
    private int x, y, w, h;

    /** The in-use palette (shown with a green tag). */
    public void setActiveId(String id) {
        activeId = id == null ? "" : id;
    }

    public PaletteListPanel(Font font) {
        this.font = font;
    }

    public void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public void setEntries(List<Entry> entries) {
        this.entries = entries;
        clampScroll();
    }

    public String selectedId() {
        return selectedId;
    }

    public String expandedId() {
        return expandedId;
    }

    public void select(String id) {
        selectedId = id == null ? "" : id;
    }

    /** Select + expand (the Use button's auto-expand); collapses any other row. */
    public void expand(String id) {
        selectedId = id == null ? "" : id;
        expandedId = selectedId;
    }

    // ---- geometry -------------------------------------------------------------------------------

    private int rowHeight(Entry e) {
        int base = ROW_H + (e.missing() ? MISSING_EXTRA : 0);
        if (!e.palette().id.equals(expandedId)) return base;
        return base + summaryHeight(e);
    }

    private int summaryHeight(Entry e) {
        int lines = 4; // order/curve/sizing · variation/chaos/step len · noise · source
        int hgt = PAD + lines * 11 + PAD;
        if (e.missing()) hgt += 11 + e.missingNames().size() * 18 + PAD;
        return hgt;
    }

    private int contentHeight() {
        int total = 0;
        for (Entry e : entries) total += rowHeight(e) + ROW_GAP;
        return total;
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight() - h)));
    }

    /** Palette id of the row header at the given coords, or null. */
    public String rowIdAt(double mx, double my) {
        if (mx < x || mx > x + w || my < y || my > y + h) return null;
        int ry = y - scroll;
        for (Entry e : entries) {
            int rh = rowHeight(e);
            if (my >= ry && my < ry + rh) return e.palette().id;
            ry += rh + ROW_GAP;
        }
        return null;
    }

    /**
     * A left-click on a row: select it and toggle expansion (expanding collapses the previously
     * expanded row; clicking the expanded row collapses it but keeps it selected).
     */
    public boolean click(double mx, double my) {
        String id = rowIdAt(mx, my);
        if (id == null) return false;
        selectedId = id;
        expandedId = id.equals(expandedId) ? "" : id;
        return true;
    }

    public boolean mouseScrolled(double mx, double my, double dir) {
        if (mx < x || mx > x + w || my < y || my > y + h) return false;
        scroll -= (int) (Math.signum(dir) * 20);
        clampScroll();
        return true;
    }

    // ---- rendering ------------------------------------------------------------------------------

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        clampScroll();
        g.enableScissor(x, y, x + w, y + h);
        int ry = y - scroll;
        for (Entry e : entries) {
            int rh = rowHeight(e);
            if (ry + rh >= y && ry <= y + h) renderRow(g, e, ry, rh, mouseX, mouseY);
            ry += rh + ROW_GAP;
        }
        g.disableScissor();

        int content = contentHeight();
        if (content > h) {
            int trackX = x + w + 2;
            g.fill(trackX, y, trackX + 2, y + h, 0x30FFFFFF);
            int thumbH = Math.max(8, h * h / content);
            int thumbY = y + (int) ((h - thumbH) * (double) scroll / (content - h));
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0x90FFFFFF);
        }
    }

    private void renderRow(GuiGraphicsExtractor g, Entry e, int ry, int rh, int mouseX, int mouseY) {
        Palette p = e.palette();
        boolean selected = p.id.equals(selectedId);
        boolean expanded = p.id.equals(expandedId);
        boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= ry && mouseY < ry + rh;

        g.fill(x, ry, x + w, ry + rh, ROW_BG);
        if (hover && !expanded) g.fill(x, ry, x + w, ry + ROW_H, HOVER_BG);
        int border = e.missing() ? RED : (selected ? WHITE : BORDER);
        outline(g, x, ry, w, rh, border);
        if (selected && e.missing()) outline(g, x - 1, ry - 1, w + 2, rh + 2, WHITE);

        // Header: name left, sprites right (squeezed to overlap when there are many), chevron.
        int textY = ry + (ROW_H - font.lineHeight) / 2;
        int chevW = 12;
        int spriteAreaW = Math.min(w / 2, e.sprites().size() * 17);
        int spritesX = x + w - PAD - chevW - spriteAreaW;
        boolean inUse = p.id.equals(activeId);
        int nameMax = spritesX - x - 2 * PAD - (inUse ? font.width(" · in use") : 0);
        String name = font.plainSubstrByWidth(p.name, nameMax);
        g.text(font, name, x + PAD + 1, textY, selected ? WHITE : 0xFFE0E0E0);
        if (inUse) g.text(font, " · in use", x + PAD + 1 + font.width(name), textY, 0xFF55FF55);

        int n = e.sprites().size();
        if (n > 0) {
            int spacing = n == 1 ? 0 : Math.min(17, (spriteAreaW - 16) / Math.max(1, n - 1));
            for (int i = 0; i < n; i++) {
                int sx = spritesX + i * spacing;
                if (e.auto().get(i)) drawCrosshatch(g, sx, ry + (ROW_H - 16) / 2, 16);
                else g.item(e.sprites().get(i), sx, ry + (ROW_H - 16) / 2);
            }
        }
        g.text(font, expanded ? "▾" : "▸", x + w - PAD - 8, textY, GREY);

        int yy = ry + ROW_H;
        if (e.missing()) {
            g.text(font, "missing blocks", x + PAD + 1, yy - 3, RED);
            yy += MISSING_EXTRA;
        }
        if (expanded) renderSummary(g, e, yy);
    }

    private void renderSummary(GuiGraphicsExtractor g, Entry e, int sy) {
        Palette p = e.palette();
        int sx = x + PAD + 4;
        int sw = w - 2 * (PAD + 4);
        g.fill(sx - 2, sy, sx + sw + 2, sy + summaryHeight(e) - PAD, SUMMARY_BG);
        int yy = sy + PAD;
        String noiseScale = p.noiseLock
                ? String.valueOf(Math.round(p.noiseScaleX))
                : Math.round(p.noiseScaleX) + "/" + Math.round(p.noiseScaleY) + "/" + Math.round(p.noiseScaleZ);
        String sizing = p.sizing.label() + (p.sizing == co.fax.wang.palette.SizingMode.SET_STEPS
                ? " (" + p.steps + ")" : "");
        String[] lines = {
                "Order: " + p.order.label() + " · Curve: " + p.curve.displayName() + " · " + sizing,
                "Variation " + pct(p.variation) + " · Chaos " + pct(p.chaos)
                        + " · Step len " + pct(p.stepWobble),
                "Noise: " + p.noiseType.displayName() + ", scale " + noiseScale
                        + (p.noiseLock ? " (locked)" : "")
                        + (p.noiseSeed == null || p.noiseSeed.isEmpty() ? "" : ", seed " + p.noiseSeed),
                "Source: " + p.source.displayName(),
        };
        for (String line : lines) {
            g.text(font, font.plainSubstrByWidth(line, sw), sx, yy, GREY);
            yy += 11;
        }
        if (e.missing()) {
            g.text(font, "Missing blocks:", sx, yy + 2, RED);
            yy += 13;
            for (int i = 0; i < e.missingNames().size(); i++) {
                ItemStack st = e.missingStacks().get(i);
                if (!st.isEmpty()) g.item(st, sx, yy);
                g.text(font, font.plainSubstrByWidth(e.missingNames().get(i), sw - 22),
                        sx + 20, yy + 4, WHITE);
                yy += 18;
            }
        }
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    private static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** The 16×16 crosshatch texture — the visual for an Automatic segment. */
    private static final net.minecraft.resources.Identifier CROSSHATCH =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("gradient", "textures/gui/crosshatch.png");

    /**
     * A 45° crosshatch tile. A static texture asset drawn with one blit — the old per-pixel fill
     * version issued ~128 quads per tile and dragged the whole UI down once Automatic segments
     * covered any real area (strip, previews, HUD).
     */
    public static void drawCrosshatch(GuiGraphicsExtractor g, int x, int y, int size) {
        g.blit(CROSSHATCH, x, y, size, size, 0f, 0f, 1f, 1f);
    }

    /** Both PaletteSegment shapes rendered the same way everywhere: sprite or crosshatch. */
    public static void drawSegmentIcon(GuiGraphicsExtractor g, PaletteSegment seg, ItemStack stack,
                                       int x, int y) {
        if (seg.isAutomatic()) drawCrosshatch(g, x, y, 16);
        else g.item(stack, x, y);
    }
}
