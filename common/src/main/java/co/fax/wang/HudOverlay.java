package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import co.fax.wang.ui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/**
 * The in-game HUD, shown only while the paint tool is held. It says what the next click will do:
 * <ol>
 *   <li>"&lt;Paint&gt; · &lt;Mode&gt;" on one line, the mode in its colour;</li>
 *   <li>the selection — palette strip, pattern thumbnail or Solid block (or where to set one up);</li>
 *   <li>yellow lines only for Place options switched from their default, or session-only and on
 *       ({@link PlaceOptions#attentionLines});</li>
 *   <li>while you look at a line marker or any block of its tube (or its toolbar is open), its
 *       length and node count;</li>
 *   <li>grey sourcing lines (where the gradient endpoints come from) in the placing modes.</li>
 * </ol>
 * Position comes from config ({@code hudX}/{@code hudY}), movable via {@link HudPlacementScreen},
 * which previews the same rows through {@link #drawHud}.
 */
public final class HudOverlay {

    /** ARGB colour of the paint-type text on line 1; the mode uses {@link PlacementMode#color()}. */
    public static final int TYPE_COLOR = 0xFFFFFFFF;
    /** ARGB colour of the selection name and the sourcing lines. */
    public static final int SOURCE_COLOR = 0xFFC8C8C8;
    /** Backing panel so the HUD text reads over snow, sky or sand. */
    private static final int BACKING = 0x90000000;
    private static final int PAD = 3;

    /** Size of the rows drawn last frame: the panel goes down first, before this frame's rows. */
    private static int lastW, lastH;

    private HudOverlay() {}

    public static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return; // only while in a world
        GradientConfig cfg = ConfigManager.get();
        // Clamp so a position saved on a larger window can never render off-screen.
        int x = Math.max(0, Math.min(cfg.hudX, Math.max(0, g.guiWidth() - 10)));
        int y = Math.max(0, Math.min(cfg.hudY, Math.max(0, g.guiHeight() - 20)));

        // First-run guidance: with no paint tool assigned at all the HUD (only in this case)
        // points at the Settings tab, where the tool picker lives.
        if (cfg.paintTool.isEmpty()) {
            backing(g, x, y, mc.font.width(noToolLine()), mc.font.lineHeight);
            g.text(mc.font, noToolLine(), x, y, Ui.ATTENTION);
            return;
        }
        if (!Gradient.holdingPaintTool(mc)) return; // HUD only while the tool is held
        drawHud(g, x, y, Gradient.currentPlacement(mc));
    }

    /** The no-tool line ("FW Paint: pick a paint tool — K › Settings"). */
    public static String noToolLine() {
        return "FW Paint: pick a paint tool — " + Gradient.fixIn("Settings");
    }

    /**
     * Draw every HUD row at (x, y) for {@code mode}; returns {width, height} of what was drawn
     * (the placement screen outlines it).
     */
    public static int[] drawHud(GuiGraphicsExtractor g, int x, int y, PlacementMode mode) {
        Minecraft mc = Minecraft.getInstance();
        GradientConfig cfg = ConfigManager.get();
        Font font = mc.font;
        int lh = font.lineHeight + 1;
        if (lastW > 0) backing(g, x, y, lastW, lastH);

        String type = cfg.activePaintType.label();
        String sep = " · ";
        g.text(font, type, x, y, TYPE_COLOR);
        g.text(font, sep, x + font.width(type), y, Ui.HINT);
        g.text(font, mode.shortName(), x + font.width(type + sep), y, mode.color());
        int w = font.width(type + sep + mode.shortName());

        int lineY = y + lh;
        int[] sel = renderSelectionRow(g, mc, cfg, x, lineY);
        w = Math.max(w, sel[0]);
        lineY = sel[1];
        for (String line : PlaceOptions.attentionLines(cfg, mode)) {
            g.text(font, line, x, lineY, Ui.ATTENTION);
            w = Math.max(w, font.width(line));
            lineY += lh;
        }
        String lineInfo = co.fax.wang.shape.ShapeMarkers.hudLineInfo();
        if (lineInfo != null) {
            g.text(font, lineInfo, x, lineY, TYPE_COLOR);
            w = Math.max(w, font.width(lineInfo));
            lineY += lh;
        }
        for (String line : PaintPlacer.sourcingLines()) {
            g.text(font, line, x, lineY, SOURCE_COLOR);
            w = Math.max(w, font.width(line));
            lineY += lh;
        }
        lastW = w;
        lastH = lineY - y;
        return new int[] {w, lineY - y};
    }

    private static void backing(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x - PAD, y - PAD, x + w + PAD, y + h + PAD - 2, BACKING);
    }

    /**
     * The selection row: for gradient/noise, the active palette's segment sprites overlapped into
     * a compact stripe plus its name (or where to set one up); for Pattern its thumbnail; for
     * Solid, the chosen block or match mode. Returns {width, y below the row}.
     */
    private static int[] renderSelectionRow(GuiGraphicsExtractor g, Minecraft mc,
                                            GradientConfig cfg, int x, int y) {
        Font font = mc.font;
        int rowH = 18;
        if (cfg.activePaintType == PaintType.SOLID) {
            if (cfg.solidMatch == SolidMatch.SELECTED) {
                ItemStack st = GradientScreen.stackOfId(cfg.solidBlock);
                if (!st.isEmpty()) {
                    g.item(st, x, y);
                    String name = st.getHoverName().getString();
                    g.text(font, name, x + 18, y + 4, SOURCE_COLOR);
                    return new int[] {18 + font.width(name), y + rowH};
                }
                return hint(g, font, "No block picked — " + Gradient.fixIn("Paint"), x, y, rowH);
            }
            String m = cfg.solidMatch.displayName();
            g.text(font, m, x, y + 4, SOURCE_COLOR);
            return new int[] {font.width(m), y + rowH};
        }
        if (cfg.activePaintType == PaintType.PATTERN) {
            co.fax.wang.palette.Palette pat = co.fax.wang.palette.PaletteStore.activePattern();
            if (pat == null) {
                return hint(g, font, "No pattern yet — " + Gradient.fixIn("Paint"), x, y, rowH);
            }
            PatternThumb.draw(g, pat, x, y, 16);
            String name = pat.name + " (" + pat.width + "×" + pat.height + ")";
            g.text(font, name, x + 20, y + 4, SOURCE_COLOR);
            return new int[] {20 + font.width(name), y + rowH};
        }
        co.fax.wang.palette.Palette active = co.fax.wang.palette.PaletteStore.active();
        if (active == null) {
            return hint(g, font, "No palette yet — " + Gradient.fixIn("Paint"), x, y, rowH);
        }
        // Segment sprites drawn overlapping (tight spacing) so the stripe stays compact.
        int n = active.segments.size();
        int spacing = 6;
        int sx = x;
        int[] tints = PaletteTints.forPalette(active);
        for (int i = 0; i < n; i++) {
            co.fax.wang.palette.PaletteSegment seg = active.segments.get(i);
            if (seg.isAutomatic()) {
                PaletteListPanel.drawCrosshatch(g, sx, y, 16, tints[i]);
            } else {
                ItemStack st = GradientScreen.stackOfId(seg.block);
                if (st.isEmpty()) PaletteListPanel.drawCrosshatch(g, sx, y, 16);
                else g.item(st, sx, y);
            }
            sx += spacing;
        }
        int nameX = n == 0 ? x : sx + 12;
        g.text(font, active.name, nameX, y + 4, SOURCE_COLOR);
        return new int[] {nameX - x + font.width(active.name), y + rowH};
    }

    private static int[] hint(GuiGraphicsExtractor g, Font font, String text, int x, int y, int rowH) {
        g.text(font, text, x, y + 4, Ui.ATTENTION);
        return new int[] {font.width(text), y + rowH};
    }
}
