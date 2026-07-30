package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/**
 * The in-game helper text, shown only while the paint tool is held: the active paint type on top,
 * the placement mode below, then (gradient/noise only) where the gradient endpoints would come
 * from for the next click — "Selected from markers" / "Selected from picker", or a split pair
 * when one marker endpoint sits in air. Position comes from config ({@code hudX}/{@code hudY}),
 * movable via {@link HudPlacementScreen}. {@link #draw} is static and side-effect-free so the
 * placement screen reuses it as a live preview.
 */
public final class HudOverlay {

    /** ARGB colour of the paint-type (top) line; the mode line uses {@link PlacementMode#color()}. */
    public static final int TYPE_COLOR = 0xFFFFFFFF;
    /** ARGB colour of the sourcing lines. */
    public static final int SOURCE_COLOR = 0xFFC8C8C8;
    /** ARGB colour of guidance hints (no tool / no palette). */
    public static final int YELLOW = 0xFFFFE34D;

    private HudOverlay() {}

    public static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return; // only while in a world
        GradientConfig cfg = ConfigManager.get();
        Font font = mc.font;
        // Clamp so a position saved on a larger window can never render off-screen.
        int x = Math.max(0, Math.min(cfg.hudX, Math.max(0, g.guiWidth() - 10)));
        int y = Math.max(0, Math.min(cfg.hudY, Math.max(0, g.guiHeight() - 20)));

        // First-run guidance: with no paint tool assigned at all the HUD (only in this case)
        // points at the Settings tab, where the tool picker lives.
        if (cfg.paintTool.isEmpty()) {
            g.text(font, "FW Paint: tool selection required — press "
                    + Gradient.boundKey("open") + " and go to Settings", x, y, YELLOW);
            return;
        }
        if (!Gradient.holdingPaintTool(mc)) return; // helper text only while the tool is held

        PlacementMode mode = Gradient.currentPlacement(mc);
        draw(g, x, y, "FW Paint — " + cfg.activePaintType.label(), mode.displayName(), mode.color());
        int lineY = y + 2 * (font.lineHeight + 1);
        lineY = renderSelectionRow(g, mc, cfg, x, lineY);
        for (String line : PaintPlacer.sourcingLines()) {
            g.text(font, line, x, lineY, SOURCE_COLOR);
            lineY += font.lineHeight + 1;
        }
    }

    /**
     * The selection row: for gradient/noise, the active palette's segment sprites overlapped into
     * a compact stripe plus its name (or the set-up hint); for solid, the chosen block or match
     * mode. Returns the y below the row.
     */
    private static int renderSelectionRow(GuiGraphicsExtractor g, Minecraft mc,
                                          GradientConfig cfg, int x, int y) {
        Font font = mc.font;
        int rowH = 18;
        if (cfg.activePaintType == PaintType.SOLID) {
            if (cfg.solidMatch == SolidMatch.SELECTED) {
                ItemStack st = GradientScreen.stackOfId(cfg.solidBlock);
                if (!st.isEmpty()) {
                    g.item(st, x, y);
                    g.text(font, st.getHoverName().getString(), x + 18, y + 4, SOURCE_COLOR);
                } else {
                    g.text(font, "Solid: no block picked", x, y + 4, YELLOW);
                }
            } else {
                g.text(font, "Match: " + cfg.solidMatch.displayName(), x, y + 4, SOURCE_COLOR);
            }
            return y + rowH;
        }
        if (cfg.activePaintType == PaintType.PATTERN) {
            co.fax.wang.palette.Palette pat = co.fax.wang.palette.PaletteStore.activePattern();
            if (pat == null) {
                g.text(font, "Press " + Gradient.boundKey("open") + " to set up a pattern",
                        x, y + 4, YELLOW);
            } else {
                PatternThumb.draw(g, pat, x, y, 16);
                g.text(font, pat.name + " (" + pat.width + "×" + pat.height + ")",
                        x + 20, y + 4, SOURCE_COLOR);
            }
            return y + rowH;
        }
        co.fax.wang.palette.Palette active = co.fax.wang.palette.PaletteStore.active();
        if (active == null) {
            g.text(font, "Press " + Gradient.boundKey("open") + " to set up a palette",
                    x, y + 4, YELLOW);
            return y + rowH;
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
        return y + rowH;
    }

    /** Draw the two fixed helper lines at (x, y): paint type on top, mode underneath. */
    public static void draw(GuiGraphicsExtractor g, int x, int y,
                            String typeLine, String modeLine, int modeColor) {
        Font font = Minecraft.getInstance().font;
        g.text(font, typeLine, x, y, TYPE_COLOR);
        g.text(font, modeLine, x, y + font.lineHeight + 1, modeColor);
    }
}
