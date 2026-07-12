package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Nudge-button screen for moving the helper text, opened from the Settings tab. Four arrow buttons
 * move the text by a cycling pixel step (50/20/10/1); every move saves immediately. The real HUD
 * lines are drawn at the live position (with a highlight box) as a preview, so the user can park
 * the text clear of anything it was covering.
 */
public class HudPlacementScreen extends Screen {

    private static final int HIGHLIGHT = 0x553399FF;

    private final Screen parent;

    public HudPlacementScreen(Screen parent) {
        super(Component.literal("Move helper text"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int baseY = this.height / 2 - 24;

        addRenderableWidget(Button.builder(incrementLabel(), b -> {
            ConfigManager.get().cycleHudIncrement();
            ConfigManager.save();
            b.setMessage(incrementLabel());
        }).bounds(cx - 50, baseY, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("▲"), b -> move(0, -1))
                .bounds(cx - 10, baseY + 24, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> move(-1, 0))
                .bounds(cx - 34, baseY + 48, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> move(1, 0))
                .bounds(cx + 14, baseY + 48, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("▼"), b -> move(0, 1))
                .bounds(cx - 10, baseY + 72, 20, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(cx - 50, this.height - 26, 100, 20).build());
    }

    private Component incrementLabel() {
        return Component.literal("Step: " + ConfigManager.get().hudIncrement() + "px");
    }

    private void move(int signX, int signY) {
        GradientConfig cfg = ConfigManager.get();
        int step = cfg.hudIncrement();
        cfg.hudX = Mth.clamp(cfg.hudX + signX * step, 0, Math.max(0, this.width - 10));
        cfg.hudY = Mth.clamp(cfg.hudY + signY * step, 0, Math.max(0, this.height - 20));
        ConfigManager.save();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        GradientConfig cfg = ConfigManager.get();
        String typeLine = "FW Paint — " + cfg.activePaintType.label();
        String modeLine = PlacementMode.MARKER.displayName(); // sample mode line for the preview
        int w = Math.max(this.font.width(typeLine), this.font.width(modeLine));
        int h = this.font.lineHeight * 2 + 1;
        g.fill(cfg.hudX - 2, cfg.hudY - 2, cfg.hudX + w + 2, cfg.hudY + h + 2, HIGHLIGHT);
        HudOverlay.draw(g, cfg.hudX, cfg.hudY, typeLine, modeLine, PlacementMode.MARKER.color());
        super.extractRenderState(g, mouseX, mouseY, partialTick); // buttons on top
    }

    @Override
    public void onClose() {
        ConfigManager.save();
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }
}
