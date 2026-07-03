package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The in-game helper text: two lines shown only while the paint tool is held — the active paint
 * type on top, the tool's mode below. Position comes from config ({@code hudX}/{@code hudY}),
 * movable via {@link HudPlacementScreen}. {@link #draw} is static and side-effect-free so the
 * placement screen reuses it as a live preview.
 */
public final class HudOverlay {

    /** ARGB colour of the paint-type (top) line; the mode line uses {@link ToolMode#color()}. */
    public static final int TYPE_COLOR = 0xFFFFFFFF;

    private HudOverlay() {}

    public static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return; // only while in a world
        if (!Gradient.holdingPaintTool(mc)) return;        // helper text only while the tool is held
        GradientConfig cfg = ConfigManager.get();
        ToolMode mode = Gradient.currentMode(mc);
        // Clamp so a position saved on a larger window can never render off-screen.
        int x = Math.max(0, Math.min(cfg.hudX, Math.max(0, g.guiWidth() - 10)));
        int y = Math.max(0, Math.min(cfg.hudY, Math.max(0, g.guiHeight() - 20)));
        draw(g, x, y, "FW Paint — " + cfg.activePaintType.label(), mode.displayName(), mode.color());
    }

    /** Draw the two helper lines at (x, y): paint type on top, mode underneath. */
    public static void draw(GuiGraphicsExtractor g, int x, int y,
                            String typeLine, String modeLine, int modeColor) {
        Font font = Minecraft.getInstance().font;
        g.text(font, typeLine, x, y, TYPE_COLOR);
        g.text(font, modeLine, x, y + font.lineHeight + 1, modeColor);
    }
}
