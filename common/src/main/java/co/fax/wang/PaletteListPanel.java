package co.fax.wang;

import co.fax.wang.palette.Palette;
import co.fax.wang.palette.PaletteSegment;
import co.fax.wang.ui.Ui;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The Paint tab's scrollable library list — the gradient palettes (Gradient / Noise paint) or the
 * patterns (Pattern paint), never both. Each row: the segment sprites (Automatic segments as a
 * tinted crosshatch) or the pattern thumbnail, the name, and a state tag on the right. Row states
 * use the kit's colours: the selected row a blue outline, the in-use one a green "● in use", and
 * one with missing blocks a red border and "N missing". The host owns the buttons and the detail
 * panel, rebuilds entries on change, and turns a double-click into Use.
 */
public final class PaletteListPanel {

    /** One palette plus everything precomputed for display (sprites, tints, missing info). */
    public record Entry(Palette palette, List<ItemStack> sprites, List<Boolean> auto,
                        List<Integer> tints, List<ItemStack> missingStacks,
                        List<String> missingNames) {
        public boolean missing() {
            return !missingNames.isEmpty();
        }
    }

    private static final int ROW_H = 20;
    private static final int ROW_GAP = 2;
    private static final int PAD = 4;
    private static final int ROW_LINE = 0xFF3E3E3E;

    private final Font font;
    private List<Entry> entries = new ArrayList<>();
    private String selectedId = "";
    private String activeId = "";
    private int scroll;
    private int x, y, w, h;

    public PaletteListPanel(Font font) {
        this.font = font;
    }

    /** The in-use item of the kind this list shows (green tag). */
    public void setActiveId(String id) {
        activeId = id == null ? "" : id;
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

    public List<Entry> entries() {
        return entries;
    }

    public String selectedId() {
        return selectedId;
    }

    public void select(String id) {
        selectedId = id == null ? "" : id;
    }

    /** The selected row's entry, or null. */
    public Entry selected() {
        for (Entry e : entries) {
            if (e.palette().id.equals(selectedId)) return e;
        }
        return null;
    }

    /** Scroll just enough that the selected row is visible. */
    public void revealSelected() {
        int ry = 0;
        for (Entry e : entries) {
            if (e.palette().id.equals(selectedId)) {
                if (ry < scroll) scroll = ry;
                else if (ry + ROW_H > scroll + h) scroll = ry + ROW_H - h;
                clampScroll();
                return;
            }
            ry += ROW_H + ROW_GAP;
        }
    }

    // ---- geometry -------------------------------------------------------------------------------

    private int contentHeight() {
        return entries.size() * (ROW_H + ROW_GAP);
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight() - h)));
    }

    /** Palette id of the row at the given coords, or null. */
    public String rowIdAt(double mx, double my) {
        if (mx < x || mx > x + w || my < y || my > y + h) return null;
        int idx = (int) ((my - y + scroll) / (ROW_H + ROW_GAP));
        return idx >= 0 && idx < entries.size() ? entries.get(idx).palette().id : null;
    }

    /** A left-click on a row selects it; returns the row's id, or null when it missed. */
    public String click(double mx, double my) {
        String id = rowIdAt(mx, my);
        if (id != null) selectedId = id;
        return id;
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
            if (ry + ROW_H >= y && ry <= y + h) renderRow(g, e, ry, mouseX, mouseY);
            ry += ROW_H + ROW_GAP;
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

    private void renderRow(GuiGraphicsExtractor g, Entry e, int ry, int mouseX, int mouseY) {
        Palette p = e.palette();
        boolean selected = p.id.equals(selectedId);
        boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= ry && mouseY < ry + ROW_H
                && mouseY >= y && mouseY < y + h;
        boolean inUse = p.id.equals(activeId);

        if (selected) g.fill(x, ry, x + w, ry + ROW_H, Ui.SELECTED_FILL);
        else if (hover) g.fill(x, ry, x + w, ry + ROW_H, Ui.HOVER);
        Ui.outline(g, x, ry, w, ROW_H, e.missing() ? Ui.MISSING : ROW_LINE);
        if (selected) {
            Ui.outline(g, x, ry, w, ROW_H, Ui.SELECTED);
            Ui.outline(g, x + 1, ry + 1, w - 2, ROW_H - 2, Ui.SELECTED);
        }

        // Left: sprites (gradients, overlapped to fit) or the grid thumbnail (patterns).
        boolean isPattern = p.kind == co.fax.wang.palette.PaletteKind.PATTERN;
        int iconY = ry + (ROW_H - 16) / 2;
        int iconsW;
        if (isPattern) {
            PatternThumb.draw(g, p, x + PAD, iconY, 16);
            iconsW = 16;
        } else {
            int n = e.sprites().size();
            int spacing = n <= 1 ? 0 : Math.min(17, 40 / (n - 1));
            iconsW = n == 0 ? 0 : 16 + spacing * (n - 1);
            for (int i = 0; i < n; i++) {
                int sx = x + PAD + i * spacing;
                if (e.auto().get(i)) drawCrosshatch(g, sx, iconY, 16, e.tints().get(i));
                else g.item(e.sprites().get(i), sx, iconY);
            }
        }

        // Right: state tags ("N missing" red, "● in use" green).
        int textY = ry + (ROW_H - font.lineHeight) / 2 + 1;
        int tx = x + w - PAD;
        if (inUse) {
            String t = "● in use";
            tx -= font.width(t);
            g.text(font, t, tx, textY, Ui.IN_USE);
            tx -= 6;
        }
        if (e.missing()) {
            String t = e.missingNames().size() + " missing";
            tx -= font.width(t);
            g.text(font, t, tx, textY, Ui.MISSING);
            tx -= 6;
        }
        int nameX = x + PAD + iconsW + 6;
        String name = font.plainSubstrByWidth(p.name, Math.max(0, tx - nameX));
        g.text(font, name, nameX, textY, e.missing() ? Ui.MISSING : Ui.TEXT);
    }

    /** The 16×16 crosshatch (a gui-atlas sprite: textures/gui/sprites/crosshatch.png). */
    private static final net.minecraft.resources.Identifier CROSSHATCH =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("gradient", "crosshatch");

    /** The neutral grey tint — the untinted crosshatch look. */
    private static final int PLAIN_TINT = 0xFF8A8A8A;

    /**
     * A 45° thin-diagonal crosshatch tile. A static sprite drawn with one blit — the old
     * per-pixel fill version issued ~128 quads per tile and dragged the whole UI down once
     * Automatic segments covered any real area (strip, previews, HUD).
     */
    public static void drawCrosshatch(GuiGraphicsExtractor g, int x, int y, int size) {
        drawCrosshatch(g, x, y, size, 0);
    }

    /**
     * Tinted variant: the sprite's lines are white, so {@code tint} (ARGB; 0 = neutral grey)
     * colours them fully — used for the editor's representative Automatic-segment colours.
     */
    public static void drawCrosshatch(GuiGraphicsExtractor g, int x, int y, int size, int tint) {
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, CROSSHATCH,
                x, y, size, size, tint == 0 ? PLAIN_TINT : tint);
    }

    /** Both PaletteSegment shapes rendered the same way everywhere: sprite or crosshatch. */
    public static void drawSegmentIcon(GuiGraphicsExtractor g, PaletteSegment seg, ItemStack stack,
                                       int x, int y) {
        if (seg.isAutomatic()) drawCrosshatch(g, x, y, 16);
        else g.item(stack, x, y);
    }
}
