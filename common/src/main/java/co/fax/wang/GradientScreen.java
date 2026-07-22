package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * The FW Paint screen (opened by K). Six tabs:
 * <ul>
 *   <li><b>Solid</b> — block picker with ✓ (one block to place) / ✗ (exclusions) + match mode;</li>
 *   <li><b>Gradient</b> — block picker (left) + gradient settings (right);</li>
 *   <li><b>Noise Paint</b> — block picker (left) + noise settings (right);</li>
 *   <li><b>Finder</b> — every block ranked by colour or brightness ({@link ColorIndex}), centred
 *       on a chosen reference block or pure colour, to discover close blocks you don't have;</li>
 *   <li><b>Settings</b> — pick the paint tool item, per-paint-type mode, marker options,
 *       helper-text position;</li>
 *   <li><b>Help</b> — the in-game manual (the drill-down {@link HelpPanel}).</li>
 * </ul>
 * The block list is the modular {@link BlockPickerPanel}.
 */
public class GradientScreen extends Screen {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFA0A0A0;
    private static final int BLUE = 0xFF5599FF;   // not picked this preview
    private static final int GREEN = 0xFF55FF55;  // must-use
    private static final int RED = 0xFFFF5555;    // excluded
    private static final int YELLOW = 0xFFFFE34D; // help text
    private static final int HOVER_BG = 0x33FFFFFF;

    private static final int LEFT_X = 10;
    private static final int BAR_H = 22;
    private static final int BAR_BG = 0x80000000;
    private static final int BAR_LINE = 0x60FFFFFF;
    private static final int COL_W_MAX = 200;
    private static final int COL_GAP = 12;
    private static final int TOOL_LIST_TOP = 78;

    private enum Tab { GRADIENT, NOISE, SOLID, FINDER, SETTINGS, HELP }

    /** Title-bar order, left to right (right-aligned as a group). */
    private static final Tab[] BAR_ORDER = {Tab.SOLID, Tab.GRADIENT, Tab.NOISE, Tab.FINDER, Tab.SETTINGS, Tab.HELP};
    private enum Assign { NONE, START, END, REQUIRE, EXCLUDE }
    private enum SolidAssign { NONE, TICK, CROSS }

    private static final int TOOL_BTN_Y = 30;
    private static final int TOOL_BTN_H = 20;
    private static final int TOOL_FILTER_Y = 54;

    // Picker-tab left column (aligned with the right column's first button at y=30).
    private static final int PICK_SRC_Y = 30;
    private static final int PICK_BTN_Y = 54;
    private static final int PICK_LIST_Y = 78;
    private static final int LEGEND_H = 26; // two key lines under the list
    private static final int PRESSED_OVERLAY = 0x80000000; // darkens a vanilla button to show "on"

    private Assign assign = Assign.NONE;

    private record SourceBlock(String id, ItemStack stack, Block block) {}
    private record ToolRow(String id, String name) {}

    private static Tab tab = Tab.SOLID; // set on open to the active paint type's tab

    // Picker tabs (Gradient + Noise) state.
    private BlockPickerPanel picker;
    private List<SourceBlock> sourceBlocks = new ArrayList<>();
    private List<SourceBlock> orderedBlocks = new ArrayList<>(); // distinct gradient order (valley→peak)
    private int whiteCount = 0;
    private final List<String> whiteOrder = new ArrayList<>(); // ids of the placement sequence (for logging)
    private String previewStartId, previewEndId;
    private String pickSelectedId; // the single selected row in Pick mode (»» chevron)
    private int modeDescY, endpointsDescY, deviationDescY, chaosDescY, stepWobbleDescY;
    private int legendY; // y of the yellow button key under the block list

    // Solid tab state.
    private SolidAssign solidAssign = SolidAssign.NONE;
    private int solidMatchDescY;

    // Manual double-click tracking for list rows (the vanilla flag only fires on real widgets).
    private String lastRowClickId;
    private long lastRowClickMs;

    /** True when this left-click is the second click on the same row within the double-click window. */
    private boolean isRowDoubleClick(String id) {
        long now = System.currentTimeMillis();
        boolean dbl = id.equals(lastRowClickId) && now - lastRowClickMs < 400;
        lastRowClickId = dbl ? null : id; // reset after a double so a triple doesn't chain
        lastRowClickMs = now;
        return dbl;
    }

    // Help tab state — static so the manual keeps its expansion + scroll across reopening (like tab).
    private static HelpPanel help;

    // Finder tab state — static so the sort mode and reference survive reopening (like tab).
    private enum FinderSort { COLOR, BRIGHTNESS }
    private enum FinderRef { BLOCK, COLOR }
    private static FinderSort finderSort = FinderSort.COLOR;
    private static FinderRef finderRef = FinderRef.BLOCK; // set by whichever picker was clicked last
    private static String finderSelectedId = "";
    private static double finderHue = 0, finderSat = 1, finderBri = 1;
    private FinderListPanel finderList; // left: all blocks in the active ordering (click to select)
    private ColorField2D finderField;   // right: hue×sat field (click for a pure-colour reference)
    private int finderLabelY, finderHeaderY, finderSwatchY;

    // Settings tab state.
    private EditBox filterBox;
    private int autoEndDescY, fillVoidsDescY, memoryDescY;
    private final List<ToolRow> matches = new ArrayList<>();
    private String filter = "";
    private int toolRowHeight = 12;
    private boolean assigningTool = false;

    public GradientScreen() {
        super(Component.literal("FW Paint"));
        // Open on the tab of whatever is being painted right now.
        tab = switch (ConfigManager.get().activePaintType) {
            case SOLID -> Tab.SOLID;
            case GRADIENT -> Tab.GRADIENT;
            case NOISE -> Tab.NOISE;
        };
    }

    // ---- layout helpers -------------------------------------------------------------------------

    // The Gradient/Noise tabs slot a thin third column (the curve strip) between the picker and the
    // settings. The outer columns keep their two-column width — the strip shifts them apart.
    private static final int MID_W = 44;

    private boolean threeCol() { return tab == Tab.GRADIENT || tab == Tab.NOISE; }
    private int colW() {
        int avail = this.width - 2 * LEFT_X;
        return Math.max(60, Math.min(COL_W_MAX, (avail - COL_GAP) / 2));
    }
    private int contentX() {
        int total = 2 * colW() + COL_GAP + (threeCol() ? MID_W + COL_GAP : 0);
        return Math.max(LEFT_X, (this.width - total) / 2);
    }
    private int midX() { return contentX() + colW() + COL_GAP; }
    private int rightX() { return midX() + (threeCol() ? MID_W + COL_GAP : 0); }
    private int rightW() { return colW(); }
    private int leftW() { return colW(); }

    @Override
    protected void init() {
        toolRowHeight = this.font.lineHeight + 3;
        if (tab == Tab.GRADIENT || tab == Tab.NOISE) initPickerTab();
        else if (tab == Tab.SOLID) initSolidTab();
        else if (tab == Tab.FINDER) initFinderTab();
        else if (tab == Tab.HELP) initHelpTab();
        else initSettingsTab();

        // Tool tabs get a "Use" button next to Done: makes this tab's paint type the active one
        // (same as cycling with the paint-type keybind).
        PaintType tabType = switch (tab) {
            case SOLID -> PaintType.SOLID;
            case GRADIENT -> PaintType.GRADIENT;
            case NOISE -> PaintType.NOISE;
            default -> null;
        };
        if (tabType != null) {
            addRenderableWidget(Button.builder(useLabel(tabType), b -> {
                ConfigManager.get().activePaintType = tabType;
                ConfigManager.save();
                b.setMessage(useLabel(tabType));
            }).bounds(this.width / 2 - 102, this.height - 26, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                    .bounds(this.width / 2 + 2, this.height - 26, 100, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                    .bounds(this.width / 2 - 50, this.height - 26, 100, 20).build());
        }
    }

    private Component useLabel(PaintType t) {
        return Component.literal(ConfigManager.get().activePaintType == t
                ? "In use ✓" : "Use " + t.label());
    }

    private void setTab(Tab t) { tab = t; previewExpanded = false; rebuildWidgets(); }

    // ---- tabs in the title bar ------------------------------------------------------------------

    private String tabName(Tab t) {
        return switch (t) {
            case GRADIENT -> "Gradient"; case NOISE -> "Noise Paint"; case FINDER -> "Finder";
            case SOLID -> "Solid"; case SETTINGS -> "Settings"; case HELP -> "Help";
        };
    }
    private String tabText(Tab t) { return tab == t ? "» " + tabName(t) : tabName(t); }

    /** Right-aligned tab x positions: {x,width} pairs, one per {@link #BAR_ORDER} entry. */
    private int[] tabXs() {
        int gap = 14;
        int[] out = new int[BAR_ORDER.length * 2];
        int x = this.width - 10;
        for (int i = BAR_ORDER.length - 1; i >= 0; i--) {
            int w = this.font.width(tabText(BAR_ORDER[i]));
            x -= w;
            out[i * 2] = x;
            out[i * 2 + 1] = w;
            x -= gap;
        }
        return out;
    }

    // ---- Gradient / Noise tab -------------------------------------------------------------------

    private void initPickerTab() {
        sourceBlocks = gatherSourceBlocks();
        // Restore the persisted ordering endpoints (shared with the placer).
        GradientConfig pc = ConfigManager.get();
        if (previewStartId == null && !pc.orderStartBlock.isEmpty()) previewStartId = pc.orderStartBlock;
        if (previewEndId == null && !pc.orderEndBlock.isEmpty()) previewEndId = pc.orderEndBlock;
        picker = new BlockPickerPanel(this.font);
        int cx = contentX(), w = leftW(), bw = w / 4;

        // Source button (vanilla) — cycles the palette source.
        addRenderableWidget(Button.builder(sourceLabel(), b -> {
            GradientConfig c = ConfigManager.get(); c.source = c.source.next(); ConfigManager.save();
            sourceBlocks = gatherSourceBlocks(); rebuildDisplayRows(); b.setMessage(sourceLabel());
        }).bounds(cx, PICK_SRC_Y, w, 20).build());

        if (activeMode().isPick()) {
            // Pick mode: three action buttons (+ / − / ✗) that act on the selected row.
            assign = Assign.NONE;
            ensurePickSelection();
            int bw3 = w / 3;
            addRenderableWidget(Button.builder(Component.literal("+"), b -> pickAdjust(1)).bounds(cx, PICK_BTN_Y, bw3, 20).build());
            addRenderableWidget(Button.builder(Component.literal("−"), b -> pickAdjust(-1)).bounds(cx + bw3, PICK_BTN_Y, bw3, 20).build());
            addRenderableWidget(Button.builder(Component.literal("✗"), b -> pickExclude()).bounds(cx + bw3 * 2, PICK_BTN_Y, w - bw3 * 2, 20).build());
        } else {
            // Four assign buttons under Source: [S] [E] ✓ ✗ — set the click-assign mode (overlay shows on).
            addRenderableWidget(Button.builder(Component.literal("[S]"),
                    b -> assign = assign == Assign.START ? Assign.NONE : Assign.START).bounds(cx, PICK_BTN_Y, bw, 20).build());
            addRenderableWidget(Button.builder(Component.literal("[E]"),
                    b -> assign = assign == Assign.END ? Assign.NONE : Assign.END).bounds(cx + bw, PICK_BTN_Y, bw, 20).build());
            addRenderableWidget(Button.builder(Component.literal("✓"),
                    b -> assign = assign == Assign.REQUIRE ? Assign.NONE : Assign.REQUIRE).bounds(cx + bw * 2, PICK_BTN_Y, bw, 20).build());
            addRenderableWidget(Button.builder(Component.literal("✗"),
                    b -> assign = assign == Assign.EXCLUDE ? Assign.NONE : Assign.EXCLUDE).bounds(cx + bw * 3, PICK_BTN_Y, w - bw * 3, 20).build());
        }

        // List capped at 10½ rows (the half row + scrollbar show it scrolls), leaving room for the
        // yellow button key underneath (Pick mode's key runs four lines instead of two).
        int availH = (this.height - 30) - PICK_LIST_Y;
        int legendH = activeMode().isPick() ? 50 : LEGEND_H;
        int listH = Math.max(BlockPickerPanel.ROW_H, Math.min(BlockPickerPanel.MAX_H, availH - legendH));
        picker.setBounds(cx, PICK_LIST_Y, w, listH);
        legendY = PICK_LIST_Y + listH + 4;

        // Centre column: the curve button (its icon is drawn over it in render; the distribution
        // strip below it is render-only, with hand-rolled handle dragging).
        addRenderableWidget(Button.builder(Component.empty(), b -> {
            GradientConfig c = ConfigManager.get();
            if (tab == Tab.NOISE) c.noiseCurve = c.noiseCurve.next();
            else c.curve = c.curve.next();
            ConfigManager.save();
            rebuildDisplayRows();
        }).bounds(midX(), 30, MID_W, 20).build());

        rebuildDisplayRows();

        if (tab == Tab.GRADIENT) initGradientSettings();
        else initNoiseSettings();
    }

    /** x of the assign button for the current mode (for the pressed overlay). */
    private int assignBtnX() {
        int cx = contentX(), bw = leftW() / 4;
        return switch (assign) {
            case START -> cx; case END -> cx + bw; case REQUIRE -> cx + bw * 2; case EXCLUDE -> cx + bw * 3;
            default -> cx;
        };
    }
    private int assignBtnW() {
        int bw = leftW() / 4;
        return assign == Assign.EXCLUDE ? leftW() - bw * 3 : bw;
    }

    /** Toggle {@code id} in {@code on}, removing it from the mutually-exclusive {@code off} list. */
    private void toggle(List<String> on, List<String> off, String id) {
        off.remove(id);
        if (on.contains(id)) on.remove(id); else on.add(id);
        ConfigManager.save();
        rebuildDisplayRows();
    }

    private void initGradientSettings() {
        int rx = rightX(), rw = rightW();
        GradientConfig cfg = ConfigManager.get();
        int y = 30;

        addRenderableWidget(Button.builder(gradientLabel(), b -> {
            GradientConfig c = ConfigManager.get(); c.gradientMode = c.gradientMode.next();
            ConfigManager.save(); rebuildWidgets();
        }).bounds(rx, y, rw, 20).build());
        y += 24;
        if (cfg.gradientMode.usesPixelPercent()) {
            addRenderableWidget(new ConfigSlider(rx, y, rw, cfg.pixelPercent,
                    v -> "Pixel %: " + Math.round(v * 100) + "%",
                    v -> { ConfigManager.get().pixelPercent = v; rebuildDisplayRows(); }));
            y += 24;
        }
        modeDescY = y; y += 12;

        cycleButton(rx, y, rw, this::endpointsLabel, () -> {
            GradientConfig c = ConfigManager.get(); c.gradientFromMarkers = !c.gradientFromMarkers; });
        y += 24; endpointsDescY = y; y += 12;

        addRenderableWidget(new ConfigSlider(rx, y, rw, cfg.deviationBudget,
                v -> "Variation: " + Math.round(v * 100) + "%",
                v -> { ConfigManager.get().deviationBudget = v; rebuildDisplayRows(); }));
        y += 24; deviationDescY = y; y += 12;

        addRenderableWidget(new ConfigSlider(rx, y, rw, cfg.chaos,
                v -> "Chaos: " + Math.round(v * 100) + "%",
                v -> { ConfigManager.get().chaos = v; rebuildDisplayRows(); }));
        y += 24; chaosDescY = y; y += 12;

        addRenderableWidget(new ConfigSlider(rx, y, rw, cfg.stepWobble,
                v -> "Step length: " + Math.round(v * 100) + "%",
                v -> ConfigManager.get().stepWobble = v));
        y += 24; stepWobbleDescY = y; y += 12;

        addRenderableWidget(new ConfigSlider(rx, y, rw, stepsToSlider(cfg.maxSteps),
                v -> "Max steps: " + sliderToSteps(v),
                v -> { ConfigManager.get().maxSteps = sliderToSteps(v); rebuildDisplayRows(); }));
        y += 26;
        gradPreviewY = y;
        if (gradPreviewY + 12 < this.height - 32) {
            addRenderableWidget(Button.builder(Component.literal("⛶"), b -> previewExpanded = true)
                    .bounds(rx + rw - 16, gradPreviewY - 2, 16, 14).build());
        }
    }

    private int noisePreviewY;          // y where the noise preview cube starts (set in initNoiseSettings)
    private int noiseModeDescY, noiseDevDescY, noiseChaosDescY; // yellow help lines on the noise tab
    private double previewOffX, previewOffY, previewOffZ; // pan offset (in blocks) from the player anchor
    private enum CubeFace { TOP, RIGHT, LEFT }
    private CubeFace dragFace; // face pressed at mouse-down — the drag pans along it until release

    // Curve strip (centre column): index of the boundary handle being dragged, −1 when none.
    private int dragHandle = -1;

    // Expanded preview: the tab's preview blown up over a dark backdrop (⛶ opens, ✗/Esc closes).
    private boolean previewExpanded;
    private static final int CLOSE_X_SIZE = 24;

    // Gradient tab cylinder preview: gradient start at the top rim, end at the bottom. The cell
    // choices are cached (chaos/wobble/variation are random) and re-rolled when settings change.
    private int gradPreviewY; // y where the cylinder preview starts (set in initGradientSettings)
    private List<List<ItemStack>> previewStepGroups = new ArrayList<>(); // step → its variation group
    private ItemStack[][] cylCache; // [ring cell][row, 0 = top rim] chosen sprite
    private int[][] cylCells;       // ring cells (x,z) of the cached cylinder, painter-sorted
    private int cylD, cylH;

    private void initNoiseSettings() {
        int rx = rightX(), rw = rightW();
        GradientConfig cfg = ConfigManager.get();
        int y = 30;

        cycleButton(rx, y, rw, () -> Component.literal("Noise: " + ConfigManager.get().noiseType.displayName()),
                () -> ConfigManager.get().noiseType = ConfigManager.get().noiseType.next());
        y += 24;
        // Block-ordering mode for the noise tool (its own, separate from the gradient tool).
        addRenderableWidget(Button.builder(Component.literal("Order: " + cfg.noiseGradientMode.displayName()), b -> {
            GradientConfig c = ConfigManager.get(); c.noiseGradientMode = c.noiseGradientMode.next();
            ConfigManager.save(); rebuildWidgets(); // relayout so the Pixel % slider shows/hides
        }).bounds(rx, y, rw, 20).build());
        y += 24;
        // Pixel % sits directly under the Order button, only for the "top %" texture modes.
        if (cfg.noiseGradientMode.usesPixelPercent()) {
            addRenderableWidget(new ConfigSlider(rx, y, rw, cfg.noisePixelPercent,
                    v -> "Pixel %: " + Math.round(v * 100) + "%",
                    v -> { ConfigManager.get().noisePixelPercent = v; rebuildDisplayRows(); }));
            y += 24;
        }
        noiseModeDescY = y; y += 12;

        EditBox seed = new EditBox(this.font, rx, y, rw, 20, Component.literal("Seed"));
        seed.setHint(Component.literal("Seed…"));
        seed.setMaxLength(32);
        seed.setValue(cfg.noiseSeed);
        seed.setResponder(s -> { ConfigManager.get().noiseSeed = s; ConfigManager.save(); });
        addRenderableWidget(seed);
        y += 24;

        if (cfg.noiseLock) {
            addRenderableWidget(new ConfigSlider(rx, y, rw, scaleToSlider(cfg.noiseScaleX),
                    v -> "Scale: " + sliderToScale(v),
                    v -> { GradientConfig c = ConfigManager.get(); double s = sliderToScale(v); c.noiseScaleX = c.noiseScaleY = c.noiseScaleZ = s; }));
            y += 24;
        } else {
            addRenderableWidget(new ConfigSlider(rx, y, rw, scaleToSlider(cfg.noiseScaleX),
                    v -> "Scale X: " + sliderToScale(v), v -> ConfigManager.get().noiseScaleX = sliderToScale(v)));
            y += 24;
            addRenderableWidget(new ConfigSlider(rx, y, rw, scaleToSlider(cfg.noiseScaleY),
                    v -> "Scale Y: " + sliderToScale(v), v -> ConfigManager.get().noiseScaleY = sliderToScale(v)));
            y += 24;
            addRenderableWidget(new ConfigSlider(rx, y, rw, scaleToSlider(cfg.noiseScaleZ),
                    v -> "Scale Z: " + sliderToScale(v), v -> ConfigManager.get().noiseScaleZ = sliderToScale(v)));
            y += 24;
        }

        cycleButton(rx, y, rw, () -> Component.literal("Lock XYZ: " + (ConfigManager.get().noiseLock ? "On" : "Off")),
                () -> { ConfigManager.get().noiseLock = !ConfigManager.get().noiseLock; rebuildWidgets(); });
        y += 24;

        // Gradient shaping for the noise tool (its own values, separate from the gradient tool).
        addRenderableWidget(new ConfigSlider(rx, y, rw, cfg.noiseDeviation,
                v -> "Variation: " + Math.round(v * 100) + "%",
                v -> { ConfigManager.get().noiseDeviation = v; rebuildDisplayRows(); }));
        y += 24; noiseDevDescY = y; y += 12;
        addRenderableWidget(new ConfigSlider(rx, y, rw, cfg.noiseChaos,
                v -> "Chaos: " + Math.round(v * 100) + "%",
                v -> { ConfigManager.get().noiseChaos = v; rebuildDisplayRows(); }));
        y += 24; noiseChaosDescY = y; y += 12;
        addRenderableWidget(new ConfigSlider(rx, y, rw, stepsToSlider(cfg.noiseMaxSteps),
                v -> "Max steps: " + sliderToSteps(v),
                v -> { ConfigManager.get().noiseMaxSteps = sliderToSteps(v); rebuildDisplayRows(); }));
        y += 26;
        noisePreviewY = y;
        if (noisePreviewY + 12 < this.height - 32) {
            addRenderableWidget(Button.builder(Component.literal("⛶"), b -> previewExpanded = true)
                    .bounds(rx + rw - 16, noisePreviewY - 2, 16, 14).build());
        }
    }

    private long noiseSeedLong() {
        String s = ConfigManager.get().noiseSeed.trim();
        if (s.isEmpty()) return 0L;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return s.hashCode(); }
    }

    private static int sliderToScale(double v) { return Math.max(1, Math.min(15, 1 + (int) Math.round(v * 14))); }
    private static double scaleToSlider(double s) { return (Math.max(1, Math.min(15, s)) - 1) / 14.0; }

    private List<SourceBlock> gatherSourceBlocks() {
        List<SourceBlock> out = new ArrayList<>();
        if (this.minecraft == null || this.minecraft.player == null) return out;
        var items = this.minecraft.player.getInventory().getNonEquipmentItems();
        int from, to;
        switch (ConfigManager.get().source) {
            case HOTBAR -> { from = 0; to = 9; }
            case INVENTORY -> { from = 9; to = 36; }
            default -> { from = 0; to = 36; }
        }
        to = Math.min(to, items.size());
        Set<String> seen = new HashSet<>();
        for (int slot = from; slot < to; slot++) {
            ItemStack st = items.get(slot);
            if (!(st.getItem() instanceof BlockItem bi)) continue;
            Block b = bi.getBlock();
            if (b.defaultBlockState().isAir()) continue;
            Identifier id = BuiltInRegistries.ITEM.getKey(st.getItem());
            if (id == null || !seen.add(id.toString())) continue;
            out.add(new SourceBlock(id.toString(), st.copy(), b));
        }
        return out;
    }

    private Block blockOfId(String id, List<SourceBlock> list) {
        if (id != null) for (SourceBlock sb : list) if (sb.id().equals(id)) return sb.block();
        return null;
    }

    // The gradient and noise tools each have their own ordering mode + shaping values; the picker
    // shows whichever belongs to the active tab.
    private GradientMode activeMode() {
        GradientConfig c = ConfigManager.get();
        return tab == Tab.NOISE ? c.noiseGradientMode : c.gradientMode;
    }
    /**
     * Budget used to filter eligible blocks when building the order. The gradient tool uses the
     * FIXED sanity budget (its Variation slider widens step bands, it never gates steps); the noise
     * tool uses a full budget — every range-eligible block is a candidate step.
     */
    private double orderingBudget() {
        return tab == Tab.NOISE ? 1.0 : GradientRamp.STEP_ELIGIBILITY_BUDGET;
    }
    private int activeMaxSteps() {
        GradientConfig c = ConfigManager.get();
        return tab == Tab.NOISE ? c.noiseMaxSteps : c.maxSteps;
    }
    private double activePixelPercent() {
        GradientConfig c = ConfigManager.get();
        return tab == Tab.NOISE ? c.noisePixelPercent : c.pixelPercent;
    }

    private void rebuildDisplayRows() {
        if (picker == null) return;
        if (activeMode().isPick()) { rebuildPickRows(); return; }

        GradientConfig cfg = ConfigManager.get();
        Set<String> excluded = new HashSet<>(cfg.excludedBlocks);
        Set<String> required = new HashSet<>(cfg.requiredBlocks);

        List<SourceBlock> nonEx = new ArrayList<>();
        for (SourceBlock sb : sourceBlocks) if (!excluded.contains(sb.id())) nonEx.add(sb);

        ensureEndpoints(nonEx);

        // Persist the endpoints so the noise placer uses the same valley→peak ordering.
        cfg.orderStartBlock = previewStartId == null ? "" : previewStartId;
        cfg.orderEndBlock = previewEndId == null ? "" : previewEndId;

        Block startBlock = blockOfId(previewStartId, nonEx);
        Block endBlock = blockOfId(previewEndId, nonEx);
        GradientMode mode = activeMode(); // gradient tab vs noise tab use their own ordering mode
        double pct = activePixelPercent();
        int startRgb = startBlock == null ? 0 : BlockTextures.gradientValue(startBlock, startBlock, mode, pct);
        int endRgb = endBlock == null ? 0 : BlockTextures.gradientValue(endBlock, startBlock, mode, pct);
        int[] rgbs = new int[nonEx.size()];
        Set<Integer> forced = new HashSet<>();
        for (int i = 0; i < nonEx.size(); i++) {
            rgbs[i] = BlockTextures.gradientValue(nonEx.get(i).block(), startBlock, mode, pct);
            if (required.contains(nonEx.get(i).id())) forced.add(i);
        }

        int[] order = nonEx.isEmpty() ? new int[0]
                : GradientRamp.gradientOrder(rgbs, startRgb, endRgb, mode, orderingBudget(), forced);
        int[] used = GradientRamp.subsample(order, activeMaxSteps());
        orderedBlocks = new ArrayList<>();
        for (int idx : used) orderedBlocks.add(nonEx.get(idx)); // distinct valley→peak order for noise

        // Each step's band-variation group (its swappable similar blocks), shown as icons on the
        // step's row — both tabs, each using its own Variation slider.
        double variation = tab == Tab.NOISE ? cfg.noiseDeviation : cfg.deviationBudget;
        java.util.Map<Integer, List<ItemStack>> extrasByStep = new java.util.HashMap<>();
        List<int[]> groups = GradientRamp.bandGroups(rgbs, used, order, mode, variation);
        previewStepGroups = new ArrayList<>();
        cylCache = null; // settings changed — re-roll the cylinder preview
        for (int i = 0; i < used.length; i++) {
            List<ItemStack> ex = new ArrayList<>();
            List<ItemStack> full = new ArrayList<>();
            for (int member : groups.get(i)) {
                full.add(nonEx.get(member).stack());
                if (member != used[i]) ex.add(nonEx.get(member).stack());
            }
            previewStepGroups.add(full);
            if (!ex.isEmpty()) extrasByStep.put(used[i], ex);
        }

        // White rows: the distinct gradient STEPS, start→end — one row per step, matching exactly
        // what placement distributes across the fill. (The curve/chaos shape how many cells each
        // step gets in-world, which depends on the marker distance — they never add, remove, or
        // reorder steps, so they don't belong in this list.)
        int[] seq = used;

        List<BlockPickerPanel.Row> rows = new ArrayList<>();
        whiteOrder.clear();
        Set<Integer> inSeq = new HashSet<>();
        for (int j = 0; j < seq.length; j++) {
            int idx = seq[j];
            inSeq.add(idx);
            SourceBlock sb = nonEx.get(idx);
            String tag = (j == 0) ? " [S]" : (j == seq.length - 1 && seq.length > 1) ? " [E]" : "";
            int color = required.contains(sb.id()) ? GREEN : WHITE;
            List<ItemStack> extras = extrasByStep.getOrDefault(idx, List.of());
            rows.add(new BlockPickerPanel.Row(sb.stack(), sb.id(), color, tag, "", extras));
            whiteOrder.add(sb.id());
        }
        whiteCount = seq.length;
        // Not in the sequence: green if must-use, else blue.
        for (int i = 0; i < nonEx.size(); i++) {
            if (inSeq.contains(i)) continue;
            SourceBlock sb = nonEx.get(i);
            int color = required.contains(sb.id()) ? GREEN : BLUE;
            rows.add(new BlockPickerPanel.Row(sb.stack(), sb.id(), color, "", ""));
        }
        // Excluded (red) at the bottom.
        for (SourceBlock sb : sourceBlocks) {
            if (excluded.contains(sb.id())) rows.add(new BlockPickerPanel.Row(sb.stack(), sb.id(), RED, "", ""));
        }

        picker.setRows(rows);
        logPreview();
    }

    /**
     * Pick mode: numbered blocks are white (sorted low→high, with [S]/[E]); everything unnumbered is
     * red (not used). The selected row shows a »» chevron; the +/−/✗ buttons act on the selection.
     */
    private void rebuildPickRows() {
        GradientConfig cfg = ConfigManager.get();
        ensurePickSelection();
        List<String> ids = new ArrayList<>();
        for (SourceBlock sb : sourceBlocks) ids.add(sb.id());
        List<List<String>> groups = Picks.groups(ids, cfg.pickNumbers);

        // Flatten to numbered blocks (low→high) and one representative per group for the preview.
        List<SourceBlock> numbered = new ArrayList<>();
        orderedBlocks = new ArrayList<>();
        previewStepGroups = new ArrayList<>();
        cylCache = null; // settings changed — re-roll the cylinder preview
        for (List<String> grp : groups) {
            SourceBlock rep = sbById(grp.get(0), sourceBlocks);
            if (rep != null) orderedBlocks.add(rep);
            List<ItemStack> full = new ArrayList<>();
            for (String id : grp) {
                SourceBlock sb = sbById(id, sourceBlocks);
                if (sb != null) { numbered.add(sb); full.add(sb.stack()); }
            }
            if (!full.isEmpty()) previewStepGroups.add(full);
        }

        List<BlockPickerPanel.Row> rows = new ArrayList<>();
        whiteOrder.clear();
        Set<String> numberedIds = new HashSet<>();
        for (int j = 0; j < numbered.size(); j++) {
            SourceBlock sb = numbered.get(j);
            numberedIds.add(sb.id());
            String tag = (j == 0) ? " [S]" : (j == numbered.size() - 1 && numbered.size() > 1) ? " [E]" : "";
            rows.add(pickRow(sb, WHITE, tag, cfg.pickNumbers.get(sb.id()) + " "));
            whiteOrder.add(sb.id());
        }
        whiteCount = numbered.size();
        // Unnumbered = not used = red.
        for (SourceBlock sb : sourceBlocks) {
            if (!numberedIds.contains(sb.id())) rows.add(pickRow(sb, RED, "", ""));
        }
        picker.setRows(rows);
        logPreview();
    }

    /** Build a Pick row, adding the »» chevron prefix when it's the selected one. */
    private BlockPickerPanel.Row pickRow(SourceBlock sb, int color, String tag, String numberPrefix) {
        String prefix = (sb.id().equals(pickSelectedId) ? "» " : "") + numberPrefix;
        return new BlockPickerPanel.Row(sb.stack(), sb.id(), color, tag, prefix);
    }

    private SourceBlock sbById(String id, List<SourceBlock> list) {
        for (SourceBlock sb : list) if (sb.id().equals(id)) return sb;
        return null;
    }

    /** Ensure a valid Pick selection (defaults to the first source block). */
    private void ensurePickSelection() {
        boolean ok = pickSelectedId != null && sourceBlocks.stream().anyMatch(s -> s.id().equals(pickSelectedId));
        if (!ok) pickSelectedId = sourceBlocks.isEmpty() ? null : sourceBlocks.get(0).id();
    }

    /** + / − the selected block's pick number; reaching 0 removes the number (→ unused/red). */
    private void pickAdjust(int delta) {
        if (pickSelectedId == null) return;
        GradientConfig cfg = ConfigManager.get();
        int next = Math.max(0, cfg.pickNumbers.getOrDefault(pickSelectedId, 0) + delta);
        if (next == 0) cfg.pickNumbers.remove(pickSelectedId);
        else cfg.pickNumbers.put(pickSelectedId, next);
        ConfigManager.save();
        rebuildDisplayRows();
    }

    /** ✗ the selected block: just remove its number (→ unused/red). */
    private void pickExclude() {
        if (pickSelectedId == null) return;
        ConfigManager.get().pickNumbers.remove(pickSelectedId);
        ConfigManager.save();
        rebuildDisplayRows();
    }

    private void ensureEndpoints(List<SourceBlock> nonEx) {
        boolean startOk = previewStartId != null && nonEx.stream().anyMatch(s -> s.id().equals(previewStartId));
        boolean endOk = previewEndId != null && nonEx.stream().anyMatch(s -> s.id().equals(previewEndId));
        if (startOk && endOk) return;
        SourceBlock lightest = null, darkest = null;
        double lightestL = -1, darkestL = Double.MAX_VALUE;
        for (SourceBlock sb : nonEx) {
            double l = BlockTextures.baseLuminance(sb.block());
            if (lightest == null || l > lightestL) { lightest = sb; lightestL = l; }
            if (darkest == null || l < darkestL) { darkest = sb; darkestL = l; }
        }
        if (!startOk) previewStartId = lightest != null ? lightest.id() : null;
        if (!endOk) previewEndId = darkest != null ? darkest.id() : null;
    }

    private void logPreview() {
        if (!ConfigManager.get().debug) return;
        GradientConfig cfg = ConfigManager.get();
        Gradient.LOG.info("[preview] start={} end={} steps={} curve={} chaos={} | white={}",
                previewStartId, previewEndId, whiteOrder.size(), cfg.curve, Math.round(cfg.chaos * 100) / 100.0, whiteOrder);
    }

    // ---- Solid tab ------------------------------------------------------------------------------

    private void initSolidTab() {
        sourceBlocks = gatherSourceBlocks();
        picker = new BlockPickerPanel(this.font);
        int cx = contentX(), w = leftW();

        addRenderableWidget(Button.builder(sourceLabel(), b -> {
            GradientConfig c = ConfigManager.get(); c.source = c.source.next(); ConfigManager.save();
            sourceBlocks = gatherSourceBlocks(); rebuildSolidRows(); b.setMessage(sourceLabel());
        }).bounds(cx, PICK_SRC_Y, w, 20).build());

        // ✓ arms a one-shot single selection; ✗ stays armed until clicked again.
        int bw2 = w / 2;
        addRenderableWidget(Button.builder(Component.literal("✓"),
                b -> solidAssign = solidAssign == SolidAssign.TICK ? SolidAssign.NONE : SolidAssign.TICK)
                .bounds(cx, PICK_BTN_Y, bw2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("✗"),
                b -> solidAssign = solidAssign == SolidAssign.CROSS ? SolidAssign.NONE : SolidAssign.CROSS)
                .bounds(cx + bw2, PICK_BTN_Y, w - bw2, 20).build());

        int availH = (this.height - 30) - PICK_LIST_Y;
        int listH = Math.max(BlockPickerPanel.ROW_H, Math.min(BlockPickerPanel.MAX_H, availH - LEGEND_H));
        picker.setBounds(cx, PICK_LIST_Y, w, listH);
        legendY = PICK_LIST_Y + listH + 4;
        rebuildSolidRows();

        // Right column: how the placed block is chosen.
        int rx = rightX(), rw = rightW();
        int y = 30;
        cycleButton(rx, y, rw, this::matchLabel, () -> {
            GradientConfig c = ConfigManager.get(); c.solidMatch = c.solidMatch.next();
            rebuildSolidRows(); // the ✓ row gains/loses its strikethrough with the mode
        });
        y += 24; solidMatchDescY = y;
    }

    /** Rows: ✓ block green (at most one), excluded red at the bottom, the rest white. The ✓ row is
     *  struck through when the match mode isn't "Selected block" (the selection is inactive). */
    private void rebuildSolidRows() {
        if (picker == null) return;
        GradientConfig cfg = ConfigManager.get();
        Set<String> excluded = new HashSet<>(cfg.solidExcludedBlocks);
        boolean selectionActive = cfg.solidMatch == SolidMatch.SELECTED;
        List<BlockPickerPanel.Row> rows = new ArrayList<>();
        for (SourceBlock sb : sourceBlocks) {
            if (excluded.contains(sb.id())) continue;
            boolean sel = sb.id().equals(cfg.solidBlock);
            String prefix = (sel && !selectionActive) ? "§m" : "";
            rows.add(new BlockPickerPanel.Row(sb.stack(), sb.id(), sel ? GREEN : WHITE, sel ? " ✓" : "", prefix));
        }
        for (SourceBlock sb : sourceBlocks) {
            if (excluded.contains(sb.id())) rows.add(new BlockPickerPanel.Row(sb.stack(), sb.id(), RED, "", ""));
        }
        picker.setRows(rows);
    }

    /**
     * Solid-list clicks. Armed ✓ = one-shot single select; armed ✗ = toggle exclusion (stays armed).
     * Shortcuts: double-click un-excludes / toggles the ✓; middle-click un-ticks / toggles exclusion.
     */
    private boolean handleSolidRowClick(String id, int button, boolean doubled) {
        GradientConfig cfg = ConfigManager.get();
        if (button == 2) { // middle: ✓ → neutral, neutral ↔ excluded
            if (id.equals(cfg.solidBlock)) cfg.solidBlock = "";
            else if (!cfg.solidExcludedBlocks.remove(id)) cfg.solidExcludedBlocks.add(id);
        } else if (doubled && button == 0 && solidAssign == SolidAssign.NONE) {
            // double: excluded → neutral, ✓ → neutral, neutral → ✓
            if (cfg.solidExcludedBlocks.contains(id)) cfg.solidExcludedBlocks.remove(id);
            else if (id.equals(cfg.solidBlock)) cfg.solidBlock = "";
            else cfg.solidBlock = id;
        } else if (button == 0 && solidAssign == SolidAssign.TICK) {
            cfg.solidBlock = id; // single selection — replaces any previous ✓
            cfg.solidExcludedBlocks.remove(id);
            solidAssign = SolidAssign.NONE; // ✓ toggles itself off after one pick
            lastRowClickId = null; // armed action — don't let a follow-up click read as a double
        } else if (button == 0 && solidAssign == SolidAssign.CROSS) {
            if (!cfg.solidExcludedBlocks.remove(id)) {
                cfg.solidExcludedBlocks.add(id);
                if (id.equals(cfg.solidBlock)) cfg.solidBlock = "";
            }
            lastRowClickId = null;
        } else {
            return false;
        }
        ConfigManager.save();
        rebuildSolidRows();
        return true;
    }

    private Component matchLabel() {
        return Component.literal("Match: " + ConfigManager.get().solidMatch.displayName());
    }

    private String matchDescription() {
        return switch (ConfigManager.get().solidMatch) {
            case SELECTED -> "Places the ✓ block from the list";
            case EXACT -> "Places exactly the block you click";
            case CLOSEST_COLOR -> "Closest colour to the clicked block";
            case CLOSEST_BRIGHTNESS -> "Closest brightness to the clicked";
        };
    }

    private void renderSolidTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (picker != null) picker.render(g, mouseX, mouseY);
        int cx = contentX(), bw2 = leftW() / 2;
        if (solidAssign == SolidAssign.TICK) g.fill(cx, PICK_BTN_Y, cx + bw2, PICK_BTN_Y + 20, PRESSED_OVERLAY);
        if (solidAssign == SolidAssign.CROSS) g.fill(cx + bw2, PICK_BTN_Y, cx + leftW(), PICK_BTN_Y + 20, PRESSED_OVERLAY);
        renderPickerLegend(g);
        GradientConfig cfg = ConfigManager.get();
        int rx = rightX(), rw = rightW();
        g.text(this.font, this.font.plainSubstrByWidth(matchDescription(), rw), rx, solidMatchDescY, YELLOW);
        if (cfg.solidMatch == SolidMatch.SELECTED) {
            String name = cfg.solidBlock.isEmpty() ? "(none)" : Gradient.toolDisplayName(cfg.solidBlock);
            g.text(this.font, this.font.plainSubstrByWidth("Block: " + name, rw), rx, solidMatchDescY + 12, YELLOW);
        }
    }

    // ---- Settings tab ---------------------------------------------------------------------------

    private void initSettingsTab() {
        int rx = rightX(), rw = rightW();
        GradientConfig cfg = ConfigManager.get();
        int y = 30;

        // One global placement mode (Marker/Single/Face/3D Fill/Disabled), shared by every paint type.
        cycleButton(rx, y, rw, () -> Component.literal("Placement: " + ConfigManager.get().placementMode.shortName()),
                () -> { GradientConfig c = ConfigManager.get(); c.placementMode = c.placementMode.next(); });
        y += 24;

        addRenderableWidget(new ConfigSlider(rx, y, rw, distToSlider(cfg.maxMarkerDistance),
                v -> "Marker dist: " + sliderToDist(v),
                v -> ConfigManager.get().maxMarkerDistance = sliderToDist(v)));
        y += 24;
        cycleButton(rx, y, rw, () -> Component.literal("Auto end marker: " + (ConfigManager.get().autoPlaceEnd ? "On" : "Off")),
                () -> ConfigManager.get().autoPlaceEnd = !ConfigManager.get().autoPlaceEnd);
        y += 24; autoEndDescY = y; y += 12;

        cycleButton(rx, y, rw, () -> Component.literal("Fill voids first: " + (ConfigManager.get().faceFillVoids ? "On" : "Off")),
                () -> ConfigManager.get().faceFillVoids = !ConfigManager.get().faceFillVoids);
        y += 24; fillVoidsDescY = y; y += 12;

        addRenderableWidget(new ConfigSlider(rx, y, rw, secsToSlider(cfg.gradientCacheSeconds),
                v -> "Gradient memory: " + secsLabel(sliderToSecs(v)),
                v -> ConfigManager.get().gradientCacheSeconds = sliderToSecs(v)));
        y += 24; memoryDescY = y; y += 12;

        addRenderableWidget(Button.builder(clearMarkersLabel(), b -> {
            MarkerManager.clearAll(); b.setMessage(clearMarkersLabel());
        }).bounds(rx, y, rw, 20).build());
        y += 24;
        // Two half-width buttons on one row to keep the column inside short windows.
        addRenderableWidget(Button.builder(Component.literal("Move helper…"), b -> {
            if (this.minecraft != null) this.minecraft.setScreenAndShow(new HudPlacementScreen(this));
        }).bounds(rx, y, rw / 2 - 2, 20).build());
        cycleButton(rx + rw / 2 + 2, y, rw - rw / 2 - 2,
                () -> Component.literal("Debug: " + (ConfigManager.get().debug ? "On" : "Off")),
                () -> ConfigManager.get().debug = !ConfigManager.get().debug);
        y += 24;
        cycleButton(rx, y, rw,
                () -> Component.literal("Color match: " + (ConfigManager.get().perceptualColor ? "Perceptual" : "Classic")),
                () -> {
                    GradientConfig c = ConfigManager.get();
                    c.perceptualColor = !c.perceptualColor;
                    GradientRamp.perceptual = c.perceptualColor;
                });

        // Left column: the paint-tool assign button (overlay shows it's armed) + filter + list.
        int cx = contentX(), w = leftW();
        String pt = cfg.paintTool.isEmpty() ? "(none)" : Gradient.toolDisplayName(cfg.paintTool);
        addRenderableWidget(Button.builder(Component.literal("Paint tool: " + pt),
                b -> assigningTool = !assigningTool)
                .bounds(cx, TOOL_BTN_Y, w, TOOL_BTN_H).build());

        filterBox = new EditBox(this.font, cx, TOOL_FILTER_Y, w, 20, Component.literal("Filter"));
        filterBox.setHint(Component.literal("Filter items…"));
        filterBox.setMaxLength(64);
        filterBox.setValue(filter);
        filterBox.setResponder(s -> { filter = s; rebuildMatches(); });
        addRenderableWidget(filterBox);
        setInitialFocus(filterBox);
        rebuildMatches();
    }

    private void rebuildMatches() {
        matches.clear();
        String q = filter.toLowerCase(Locale.ROOT);
        int available = (this.height - 34) - TOOL_LIST_TOP;
        int maxRows = Math.max(1, available / toolRowHeight);
        for (var item : BuiltInRegistries.ITEM.stream().toList()) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) continue;
            String idStr = id.toString();
            if (idStr.equals("minecraft:air")) continue;
            String name = new ItemStack(item).getHoverName().getString();
            if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q) && !idStr.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (matches.size() >= maxRows) break;
            matches.add(new ToolRow(idStr, name));
        }
    }

    private int toolRowAt(double mouseX, double mouseY) {
        int cx = contentX();
        if (mouseX < cx || mouseX > cx + leftW() || mouseY < TOOL_LIST_TOP) return -1;
        int idx = (int) ((mouseY - TOOL_LIST_TOP) / toolRowHeight);
        return (idx >= 0 && idx < matches.size()) ? idx : -1;
    }

    // ---- Finder tab -----------------------------------------------------------------------------

    private void initFinderTab() {
        int cx = contentX(), w = leftW();
        int listBottom = this.height - 40;

        // Left: every block in the game, in the active ordering, with a target button to re-center.
        // Clicking a row selects that block as the reference — there is no separate picker.
        finderLabelY = 36;
        addRenderableWidget(Button.builder(Component.literal("⌖"), b -> recenterFinder())
                .bounds(cx + w - 20, 30, 20, 20).build());
        finderList = new FinderListPanel(this.font);
        finderList.setShowSwatch(true);
        finderList.setBounds(cx, 54, w, listBottom - 54);
        finderList.setEntries(activeFinderEntries()); // first call builds the session colour index

        // Right: sort toggle, the selected block, and the always-visible pure-colour picker.
        int rx = rightX(), rw = rightW();
        addRenderableWidget(Button.builder(
                Component.literal("Sort: " + (finderSort == FinderSort.COLOR ? "Color" : "Brightness")),
                b -> { finderSort = finderSort == FinderSort.COLOR ? FinderSort.BRIGHTNESS : FinderSort.COLOR; rebuildWidgets(); })
                .bounds(rx, 30, rw, 20).build());

        finderHeaderY = 58; // selected-block header is render-only
        int fieldY = 86;
        // Landscape field: cap the height so tall windows don't stretch it into a square.
        int fieldH = Math.max(40, Math.min(72, listBottom - fieldY - 52));
        finderField = new ColorField2D();
        finderField.setBounds(rx, fieldY, rw, fieldH);
        int sliderY = fieldY + fieldH + 4;
        finderSwatchY = sliderY + 24;
        addRenderableWidget(new ConfigSlider(rx, sliderY, rw, finderBri,
                v -> "Brightness: " + Math.round(v * 100) + "%",
                v -> { finderBri = v; finderRef = FinderRef.COLOR; recenterFinder(); }));
        recenterFinder();
    }

    private List<ColorIndex.Entry> activeFinderEntries() {
        return finderSort == FinderSort.COLOR ? ColorIndex.byColor() : ColorIndex.byBrightness();
    }

    /** Scroll the left list so the reference (block or pure colour) sits mid-view. */
    private void recenterFinder() {
        if (finderList == null) return;
        if (finderRef == FinderRef.BLOCK) {
            finderList.clearMarker();
            finderList.setSelectedId(finderSelectedId);
            int idx = finderList.indexOfId(finderSelectedId);
            if (idx >= 0) finderList.centerOn(idx);
        } else {
            int rgb = ColorOrder.hsvToRgb(finderHue, finderSat, finderBri);
            long key = finderSort == FinderSort.COLOR
                    ? ColorOrder.colorSortKey(rgb) : ColorOrder.brightnessSortKey(rgb);
            long[] keys = finderSort == FinderSort.COLOR
                    ? ColorIndex.colorKeys() : ColorIndex.brightnessKeys();
            int idx = ColorOrder.insertionIndex(key, keys);
            finderList.setSelectedId(null);
            finderList.setMarker(idx, rgb);
            finderList.centerOn(idx);
        }
    }

    /** Left-click select: a row makes that block the reference, the field makes a pure colour it. */
    private boolean handleFinderClick(double mx, double my) {
        if (finderList != null && finderList.mouseClicked(mx, my)) return true;
        if (finderList != null) {
            String id = finderList.idAt(mx, my);
            if (id != null) {
                finderSelectedId = id;
                finderRef = FinderRef.BLOCK;
                recenterFinder();
                return true;
            }
        }
        if (finderField != null && finderField.contains(mx, my)) {
            double[] hs = finderField.pick(mx, my);
            finderHue = hs[0];
            finderSat = hs[1];
            finderRef = FinderRef.COLOR;
            recenterFinder();
            return true;
        }
        return false;
    }

    private void renderFinderTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(this.font, finderSort == FinderSort.COLOR ? "By color" : "By brightness",
                contentX(), finderLabelY, GREY);
        if (finderList != null) finderList.render(g, mouseX, mouseY);

        int rx = rightX(), rw = rightW();
        ColorIndex.Entry sel = finderSelectedId.isEmpty() ? null : ColorIndex.byId(finderSelectedId);
        int hy = finderHeaderY;
        if (sel == null) {
            g.text(this.font, this.font.plainSubstrByWidth("Click a block or pick a colour", rw), rx, hy + 8, YELLOW);
        } else {
            g.fill(rx, hy, rx + 24, hy + 24, 0xFF6E6E6E);
            g.item(sel.stack(), rx + 4, hy + 4);
            g.fill(rx + rw - 17, hy + 4, rx + rw - 1, hy + 20, 0x60FFFFFF);
            g.fill(rx + rw - 16, hy + 5, rx + rw - 2, hy + 19, 0xFF000000 | sel.rgb());
            g.text(this.font, this.font.plainSubstrByWidth(sel.name(), rw - 50), rx + 30, hy + 2, WHITE);
            g.text(this.font, String.format("#%06X", sel.rgb()), rx + 30, hy + 14, GREY);
        }
        if (finderField != null) finderField.render(g, finderHue, finderSat, finderBri);
        int rgb = ColorOrder.hsvToRgb(finderHue, finderSat, finderBri);
        g.fill(rx, finderSwatchY, rx + 20, finderSwatchY + 20, 0x60FFFFFF);
        g.fill(rx + 1, finderSwatchY + 1, rx + 19, finderSwatchY + 19, 0xFF000000 | rgb);
        g.text(this.font, String.format("#%06X", rgb), rx + 26, finderSwatchY + 6,
                finderRef == FinderRef.COLOR ? WHITE : GREY);
    }

    // ---- Help tab -------------------------------------------------------------------------------

    private void initHelpTab() {
        if (help == null) help = new HelpPanel(this.font);
        // The manual gets the full content width (both columns), from under the bar to the Done button.
        help.setBounds(contentX(), 30, 2 * colW() + COL_GAP, this.height - 30 - 34);
    }

    // ---- input ----------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (previewExpanded && (tab == Tab.GRADIENT || tab == Tab.NOISE)) {
            if (event.button() == 0) {
                if (inCloseX(event.x(), event.y())) {
                    previewExpanded = false;
                } else if (tab == Tab.NOISE && inNoisePreview(event.x(), event.y())) {
                    dragFace = faceAt(event.x(), event.y());
                }
            }
            return true; // the settings underneath are covered by the backdrop — swallow everything
        }
        if (event.y() < BAR_H && event.button() == 0) {
            int[] xs = tabXs();
            for (int i = 0; i < BAR_ORDER.length; i++) {
                if (event.x() >= xs[i * 2] && event.x() <= xs[i * 2] + xs[i * 2 + 1]) {
                    setTab(BAR_ORDER[i]);
                    return true;
                }
            }
        }
        if (super.mouseClicked(event, doubled)) return true;
        if (threeCol() && event.button() == 0) {
            int hIdx = handleAt(event.x(), event.y());
            if (hIdx >= 0) {
                beginHandleDrag(hIdx);
                return true;
            }
        }
        if (event.button() == 0 && inNoisePreview(event.x(), event.y())) {
            dragFace = faceAt(event.x(), event.y()); // lock the drag to this face until release
            return true;
        }
        if (tab == Tab.HELP && event.button() == 0 && help != null
                && help.mouseClicked(event.x(), event.y())) {
            return true;
        }
        if ((tab == Tab.GRADIENT || tab == Tab.NOISE || tab == Tab.SOLID) && picker != null
                && event.button() >= 0 && event.button() <= 2) {
            String id = picker.rowIdAt(event.x(), event.y());
            if (id == null) return false;
            if (tab == Tab.SOLID) {
                boolean dbl = event.button() == 0 && (doubled || isRowDoubleClick(id));
                return handleSolidRowClick(id, event.button(), dbl);
            }
            GradientConfig cfg = ConfigManager.get();
            // Pick mode: clicking a row selects it AND adjusts its number — left +1, right −1
            // (reaching 0 removes the number). The +/−/✗ buttons still act on the selection.
            if (activeMode().isPick()) {
                pickSelectedId = id;
                if (event.button() == 0) pickAdjust(1);
                else if (event.button() == 1) pickAdjust(-1);
                else rebuildDisplayRows(); // middle: just select
                return true;
            }
            if (event.button() == 2) { // middle: ✓ → neutral, neutral ↔ excluded
                if (cfg.requiredBlocks.contains(id)) cfg.requiredBlocks.remove(id);
                else if (!cfg.excludedBlocks.remove(id)) cfg.excludedBlocks.add(id);
                ConfigManager.save();
                rebuildDisplayRows();
                return true;
            }
            boolean dbl = event.button() == 0 && (doubled || isRowDoubleClick(id));
            if (dbl && assign == Assign.NONE) {
                // double: excluded → neutral, ✓ → neutral, neutral → ✓ (must-use)
                if (cfg.excludedBlocks.contains(id)) cfg.excludedBlocks.remove(id);
                else if (!cfg.requiredBlocks.remove(id)) cfg.requiredBlocks.add(id);
                ConfigManager.save();
                rebuildDisplayRows();
                return true;
            }
            if (event.button() == 0 && assign != Assign.NONE) {
                lastRowClickId = null; // armed action — don't let a quick follow-up read as a double
                switch (assign) {
                    case START -> { previewStartId = id; assign = Assign.NONE; rebuildDisplayRows(); }
                    case END -> { previewEndId = id; assign = Assign.NONE; rebuildDisplayRows(); }
                    case REQUIRE -> toggle(cfg.requiredBlocks, cfg.excludedBlocks, id); // sticky
                    case EXCLUDE -> toggle(cfg.excludedBlocks, cfg.requiredBlocks, id);
                    default -> { }
                }
                return true;
            }
            return false;
        }
        if (tab == Tab.FINDER && event.button() == 0 && handleFinderClick(event.x(), event.y())) {
            return true;
        }
        if (tab == Tab.SETTINGS && event.button() == 0) {
            int idx = toolRowAt(event.x(), event.y());
            if (idx >= 0 && assigningTool) {
                ConfigManager.get().paintTool = matches.get(idx).id();
                ConfigManager.save();
                assigningTool = false; // un-arm + refresh the button label
                rebuildWidgets();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (previewExpanded && (tab == Tab.GRADIENT || tab == Tab.NOISE)) return true;
        if ((tab == Tab.GRADIENT || tab == Tab.NOISE || tab == Tab.SOLID)
                && picker != null && picker.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        if (tab == Tab.HELP && help != null && help.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        if (tab == Tab.FINDER && finderList != null && finderList.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ---- labels ---------------------------------------------------------------------------------

    private Component gradientLabel() { return Component.literal("Gradient: " + ConfigManager.get().gradientMode.displayName()); }
    private Component sourceLabel() { return Component.literal("Source: " + ConfigManager.get().source.displayName()); }
    private Component endpointsLabel() {
        return Component.literal("From: " + (ConfigManager.get().gradientFromMarkers ? "Markers" : "Block list"));
    }
    private String endpointsDescription() {
        return ConfigManager.get().gradientFromMarkers
                ? "Ends = the real blocks at the markers"
                : "Places exactly the preview list";
    }
    private Component clearMarkersLabel() {
        return Component.literal("Clear Markers (" + (MarkerManager.startMarkers.size() + MarkerManager.endMarkers.size()) + ")");
    }

    private String modeDescription() {
        return switch (activeMode()) {
            case COLOR -> "Average texture colour.";
            case BRIGHTNESS -> "Average texture brightness.";
            case TOP_DARK_COLOR -> "Colour of the darkest pixels.";
            case TOP_DARK -> "Brightness of the darkest pixels.";
            case TOP_LIGHT_COLOR -> "Colour of the lightest pixels.";
            case TOP_LIGHT -> "Brightness of the lightest pixels.";
            case BW_DIFF -> "B&W texture difference from start.";
            case COLOR_DIFF -> "Colour texture difference from start.";
            case PICK -> "Left-click to number, right-click to lower.";
        };
    }

    private void cycleButton(int x, int y, int w, java.util.function.Supplier<Component> label, Runnable onCycle) {
        addRenderableWidget(Button.builder(label.get(), b -> {
            onCycle.run(); ConfigManager.save(); b.setMessage(label.get());
        }).bounds(x, y, w, 20).build());
    }

    private static int sliderToSteps(double v) { return Math.max(1, Math.min(16, 1 + (int) Math.round(v * 15))); }
    private static double stepsToSlider(int steps) { return (Math.max(1, Math.min(16, steps)) - 1) / 15.0; }
    private static int sliderToDist(double v) { return Math.max(1, Math.min(128, 1 + (int) Math.round(v * 127))); }
    private static double distToSlider(int d) { return (Math.max(1, Math.min(128, d)) - 1) / 127.0; }
    // Gradient memory: 10 s – 5 min.
    private static int sliderToSecs(double v) { return Math.max(10, Math.min(300, 10 + (int) Math.round(v * 290))); }
    private static double secsToSlider(int s) { return (Math.max(10, Math.min(300, s)) - 10) / 290.0; }
    private static String secsLabel(int s) {
        if (s < 60) return s + "s";
        return s % 60 == 0 ? (s / 60) + "m" : (s / 60) + "m " + (s % 60) + "s";
    }

    // ---- rendering ------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        renderTitleBar(g);
        if (tab == Tab.GRADIENT) renderGradientTab(g, mouseX, mouseY);
        else if (tab == Tab.NOISE) renderNoiseTab(g, mouseX, mouseY);
        else if (tab == Tab.SOLID) renderSolidTab(g, mouseX, mouseY);
        else if (tab == Tab.FINDER) renderFinderTab(g, mouseX, mouseY);
        else if (tab == Tab.HELP) { if (help != null) help.render(g, mouseX, mouseY); }
        else renderSettingsTab(g, mouseX, mouseY);
        if (previewExpanded && (tab == Tab.GRADIENT || tab == Tab.NOISE)) {
            renderExpandedOverlay(g, mouseX, mouseY);
        }
    }

    private void renderTitleBar(GuiGraphicsExtractor g) {
        g.fill(0, 0, this.width, BAR_H, BAR_BG);
        g.fill(0, BAR_H, this.width, BAR_H + 1, BAR_LINE);
        int textY = (BAR_H - this.font.lineHeight) / 2 + 1;
        g.text(this.font, "FW Paint", 8, textY, WHITE);
        int[] xs = tabXs();
        for (int i = 0; i < BAR_ORDER.length; i++) {
            Tab t = BAR_ORDER[i];
            g.text(this.font, tabText(t), xs[i * 2], textY, tab == t ? WHITE : GREY);
        }
    }

    /** Darken the active assign button (drawn over the vanilla button) to show it's toggled on. */
    private void renderAssignOverlay(GuiGraphicsExtractor g) {
        if (assign == Assign.NONE || activeMode().isPick()) return;
        g.fill(assignBtnX(), PICK_BTN_Y, assignBtnX() + assignBtnW(), PICK_BTN_Y + 20, PRESSED_OVERLAY);
    }

    /** Yellow key under the block list explaining the picker buttons (or Pick-mode clicks). */
    private void renderPickerLegend(GuiGraphicsExtractor g) {
        int cx = contentX(), w = leftW();
        String[] lines;
        if (tab == Tab.SOLID) {
            lines = new String[]{"✓ block to place · ✗ exclude", "Dbl-click: ✓ · middle-click: ✗"};
        } else if (activeMode().isPick()) {
            lines = new String[]{
                "Numbers = order · lowest first",
                "Click selects (») and adds +1",
                "Right-click −1 · 0 = unused",
                "+ − adjust selected · ✗ clears"};
        } else {
            lines = new String[]{"[S] start block · [E] end block", "✓ must use · ✗ exclude"};
        }
        for (int i = 0; i < lines.length; i++) {
            g.text(this.font, this.font.plainSubstrByWidth(lines[i], w), cx, legendY + i * 11, YELLOW);
        }
    }

    private void renderGradientTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (picker != null) picker.render(g, mouseX, mouseY);
        renderAssignOverlay(g);
        renderPickerLegend(g);
        int rx = rightX(), rw = rightW();
        g.text(this.font, this.font.plainSubstrByWidth(modeDescription(), rw), rx, modeDescY, YELLOW);
        g.text(this.font, this.font.plainSubstrByWidth(endpointsDescription(), rw), rx, endpointsDescY, YELLOW);
        g.text(this.font, this.font.plainSubstrByWidth("Similar blocks swap within each step", rw), rx, deviationDescY, YELLOW);
        g.text(this.font, this.font.plainSubstrByWidth("Chance to repeat or skip a step", rw), rx, chaosDescY, YELLOW);
        g.text(this.font, this.font.plainSubstrByWidth("Chance steps run longer or shorter", rw), rx, stepWobbleDescY, YELLOW);
        renderCurveStrip(g, mouseX, mouseY);
        renderGradientPreview(g);
    }

    private void renderNoiseTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (picker != null) picker.render(g, mouseX, mouseY);
        renderAssignOverlay(g);
        renderPickerLegend(g);
        int rx = rightX(), rw = rightW();
        g.text(this.font, this.font.plainSubstrByWidth(modeDescription(), rw), rx, noiseModeDescY, YELLOW);
        g.text(this.font, this.font.plainSubstrByWidth("Similar blocks swap within each step", rw), rx, noiseDevDescY, YELLOW);
        g.text(this.font, this.font.plainSubstrByWidth("Chance to repeat or skip a step", rw), rx, noiseChaosDescY, YELLOW);
        renderCurveStrip(g, mouseX, mouseY);
        renderNoisePreview(g);
    }

    // GUI block sprites are a 30° dimetric render with a 10px block edge: one block over in +X is
    // (+7.07, +3.54) px on screen, +Z is (−7.07, +3.54), and one block up is (0, −8.66). Drawing
    // sprites at exactly these offsets tiles them into one solid composite cube.
    private static final float ISO_X = 7.0711f;   // half the projected block width
    private static final float ISO_DOWN = 3.5355f; // screen drop per horizontal step
    private static final float ISO_UP = 8.6603f;  // screen rise per vertical step

    /** Anchor world coords of the preview cube: the player's feet plus the pan offsets. */
    private int[] noiseAnchor() {
        BlockPos feet = this.minecraft != null && this.minecraft.player != null
                ? this.minecraft.player.blockPosition() : BlockPos.ZERO;
        return new int[]{feet.getX() + (int) Math.round(previewOffX),
                feet.getY() + (int) Math.round(previewOffY),
                feet.getZ() + (int) Math.round(previewOffZ)};
    }

    /**
     * The noise field as a packed isometric cube of world blocks, anchored at the player's feet and
     * extending East (+X, the right face), South (+Z, the left face) and up (top face). Each visible
     * block samples the noise at its real world coordinate exactly as placement does
     * ({@link NoisePlacer#slotForCell}: integer coords, same seed and scales, no curve) — so the
     * preview is literally what painting that region would produce (before chaos/variation swaps).
     */
    private void renderNoisePreview(GuiGraphicsExtractor g) {
        int rx = rightX(), rw = rightW();
        g.text(this.font, "Preview:", rx, noisePreviewY, GREY);
        if (previewExpanded) return; // drawn by the overlay instead
        int gridY = noisePreviewY + 12;
        if (orderedBlocks.isEmpty()) {
            g.text(this.font, this.font.plainSubstrByWidth("Select blocks to preview", rw), rx, gridY, YELLOW);
            return;
        }
        float[] l = noiseCubeLayout(false);
        drawNoiseCube(g, l);
        int[] a = noiseAnchor();
        int hintY = (int) (l[2] + l[3] * (((int) l[0] - 1) * (2 * ISO_DOWN + ISO_UP) + 16)) + 3;
        g.text(this.font, this.font.plainSubstrByWidth(
                "At " + a[0] + " " + a[1] + " " + a[2] + " · right = East · left = South", rw), rx, hintY, GREY);
        g.text(this.font, this.font.plainSubstrByWidth("Drag a face to pan along that face", rw),
                rx, hintY + 11, YELLOW);
    }

    /** Draw the noise cube for a {@link #noiseCubeLayout} layout {n, tx, ty, scale}. */
    private void drawNoiseCube(GuiGraphicsExtractor g, float[] l) {
        int n = (int) l[0];
        GradientConfig cfg = ConfigManager.get();
        long seed = noiseSeedLong();
        int[] a = noiseAnchor();
        g.pose().pushMatrix();
        g.pose().translate(l[1], l[2]);
        g.pose().scale(l[3], l[3]);
        // Painter's order: bottom layer up, back-to-front within each layer.
        for (int gy = 0; gy < n; gy++) {
            for (int sum = 0; sum <= 2 * (n - 1); sum++) {
                for (int gx = Math.max(0, sum - (n - 1)); gx <= Math.min(n - 1, sum); gx++) {
                    int gz = sum - gx;
                    if (gx != n - 1 && gz != n - 1 && gy != n - 1) continue; // hidden inside the cube
                    double v = Noise.sample(cfg.noiseType, a[0] + gx, a[1] + gy, a[2] + gz, seed,
                            cfg.noiseScaleX, cfg.noiseScaleY, cfg.noiseScaleZ);
                    int idx = GradientRamp.stepFor(orderedBlocks.size(), v, cfg.noiseCurve, cfg.noiseCurveBounds);
                    g.pose().pushMatrix();
                    g.pose().translate((gx - gz + (n - 1)) * ISO_X, (gx + gz) * ISO_DOWN + (n - 1 - gy) * ISO_UP);
                    g.item(orderedBlocks.get(idx).stack(), 0, 0);
                    g.pose().popMatrix();
                }
            }
        }
        g.pose().popMatrix();
    }

    /**
     * Noise cube layout {n, tx, ty, scale}: (tx,ty) is the on-screen top-left of the cube's bounding
     * box. Collapsed sits in the settings column at 1×; expanded is centred at 2× over the backdrop.
     */
    private float[] noiseCubeLayout(boolean expanded) {
        if (expanded) {
            float s = 2f;
            float availW = this.width - 60, availH = this.height - 70;
            int n = 1 + (int) Math.min((availW / s - 16) / (2 * ISO_X), (availH / s - 16) / (2 * ISO_DOWN + ISO_UP));
            n = Math.max(2, Math.min(12, n));
            float w = s * (2 * (n - 1) * ISO_X + 16), h = s * ((n - 1) * (2 * ISO_DOWN + ISO_UP) + 16);
            return new float[]{n, (this.width - w) / 2f, (this.height - h) / 2f, s};
        }
        int rw = rightW();
        int gridY = noisePreviewY + 12;
        int availH = (this.height - 30 - 24) - gridY; // room for the two hint lines underneath
        int n = 1 + (int) Math.min((rw - 16) / (2 * ISO_X), (availH - 16) / (2 * ISO_DOWN + ISO_UP));
        n = Math.max(2, Math.min(10, n));
        float w = 2 * (n - 1) * ISO_X + 16;
        return new float[]{n, rightX() + (rw - w) / 2f, gridY, 1f};
    }

    /**
     * Which composite-cube face the point is over, by inverting the iso projection relative to the
     * cube's top vertex: u,v are the point's world X/Z if it lay in the top-face plane — inside
     * [0,n]² means the top face; otherwise the sign of the horizontal offset from the front vertical
     * edge picks the East (right) or South (left) face. Off-cube points get the nearest face.
     */
    private CubeFace faceAt(double mx, double my) {
        float[] l = noiseCubeLayout(previewExpanded);
        float n = l[0];
        double dx = (mx - l[1]) / l[3] - ((n - 1) * ISO_X + 8); // top vertex: sprite (0,n−1,0) top-centre
        double dy = (my - l[2]) / l[3];
        double u = (dx / ISO_X + dy / ISO_DOWN) / 2, v = (dy / ISO_DOWN - dx / ISO_X) / 2;
        if (u >= 0 && u <= n && v >= 0 && v <= n) return CubeFace.TOP;
        return dx >= 0 ? CubeFace.RIGHT : CubeFace.LEFT;
    }

    // ---- curve strip (centre column) ------------------------------------------------------------

    private CurveFunction activeCurve() {
        GradientConfig c = ConfigManager.get();
        return tab == Tab.NOISE ? c.noiseCurve : c.curve;
    }
    private List<Double> activeCurveBounds() {
        GradientConfig c = ConfigManager.get();
        return tab == Tab.NOISE ? c.noiseCurveBounds : c.curveBounds;
    }
    private void setActiveCurve(CurveFunction curve) {
        GradientConfig c = ConfigManager.get();
        if (tab == Tab.NOISE) c.noiseCurve = curve; else c.curve = curve;
    }

    private int stripX() { return midX() + 8; }
    private int stripW() { return MID_W - 16; }
    private int stripY() { return 54; }
    private int stripH() { return Math.max(0, this.height - 34 - stripY()); }

    /**
     * Boundary fractions (size count−1) of the active curve's step distribution — where each step
     * hands over to the next along the fill. CUSTOM reads the stored handle positions; any other
     * curve is scanned to find its handovers.
     */
    private double[] displayBounds(int count) {
        CurveFunction curve = activeCurve();
        List<Double> custom = activeCurveBounds();
        double[] b = new double[Math.max(0, count - 1)];
        if (curve == CurveFunction.CUSTOM && custom.size() == count - 1) {
            for (int i = 0; i < b.length; i++) b[i] = custom.get(i);
            return b;
        }
        int samples = 400, pos = 0;
        for (int i = 0; i <= samples && pos < b.length; i++) {
            double t = i / (double) samples;
            int idx = GradientRamp.rampIndex(count, curve.apply(t));
            while (pos < idx && pos < b.length) b[pos++] = t;
        }
        while (pos < b.length) b[pos++] = 1.0;
        return b;
    }

    /** Pixel y of each strip segment edge, size count+1 (first = strip top, last = strip bottom). */
    private int[] stripEdges(int count) {
        double[] b = displayBounds(count);
        int[] edges = new int[count + 1];
        edges[0] = stripY();
        edges[count] = stripY() + stripH();
        for (int k = 0; k < count - 1; k++) edges[k + 1] = stripY() + (int) Math.round(b[k] * stripH());
        return edges;
    }

    /** Handle hit test: boundary index at (mx,my), or −1. Handles alternate left/right. */
    private int handleAt(double mx, double my) {
        int count = orderedBlocks.size();
        if (count < 2 || stripH() < 40) return -1;
        int[] edges = stripEdges(count);
        for (int k = 1; k < count; k++) {
            int hx = k % 2 == 1 ? stripX() - 8 : stripX() + stripW() + 1;
            if (mx >= hx - 2 && mx <= hx + 9 && my >= edges[k] - 6 && my <= edges[k] + 6) return k - 1;
        }
        return -1;
    }

    /** Start dragging a handle: seed the custom bounds from the current curve, switch it to "C". */
    private void beginHandleDrag(int idx) {
        int count = orderedBlocks.size();
        List<Double> bounds = activeCurveBounds();
        if (activeCurve() != CurveFunction.CUSTOM || bounds.size() != count - 1) {
            double[] cur = displayBounds(count);
            bounds.clear();
            for (double v : cur) bounds.add(v);
            setActiveCurve(CurveFunction.CUSTOM);
        }
        dragHandle = idx;
        cylCache = null;
    }

    /**
     * The centre column: the curve's shape drawn over its button (a "C" when CUSTOM), then the
     * distribution strip — one segment per step, its height the step's share of the fill, its
     * background the step's sprite iso-tiled and clipped. Drag a boundary handle to reshape.
     */
    private void renderCurveStrip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        renderCurveButtonIcon(g);
        int count = orderedBlocks.size();
        int sx = stripX(), sw = stripW(), sh = stripH();
        if (count == 0 || sh < 40) return;
        int[] edges = stripEdges(count);
        for (int k = 0; k < count; k++) {
            int top = edges[k], bot = edges[k + 1];
            if (bot <= top) continue;
            SourceBlock sb = orderedBlocks.get(k);
            int avg = BlockTextures.gradientValue(sb.block(), sb.block(), GradientMode.COLOR, 1.0);
            g.enableScissor(sx, top, sx + sw, bot);
            g.fill(sx, top, sx + sw, bot, 0xFF000000 | (avg & 0xFFFFFF));
            for (int row = 0; top - 16 + row * ISO_UP < bot; row++) {
                float y = top - 16 + row * ISO_UP;
                for (int col = 0; col * 2 * ISO_X < sw + 16; col++) {
                    g.pose().pushMatrix();
                    g.pose().translate(sx - 16 + col * 2 * ISO_X + (row % 2) * ISO_X, y);
                    g.item(sb.stack(), 0, 0);
                    g.pose().popMatrix();
                }
            }
            g.disableScissor();
        }
        for (int k = 1; k < count; k++) {
            int yb = edges[k];
            g.fill(sx, yb - 1, sx + sw, yb + 1, 0xFF000000);
            int hx = k % 2 == 1 ? sx - 8 : sx + sw + 1;
            boolean hot = dragHandle == k - 1
                    || (mouseX >= hx - 2 && mouseX <= hx + 9 && mouseY >= yb - 6 && mouseY <= yb + 6);
            g.fill(hx, yb - 5, hx + 7, yb + 5, 0xFF000000);
            g.fill(hx + 1, yb - 4, hx + 6, yb + 4, hot ? 0xFFFFE34D : 0xFFE0E0E0);
        }
    }

    /** The curve drawn as a small plot on the centre-column button; CUSTOM shows a "C" instead. */
    private void renderCurveButtonIcon(GuiGraphicsExtractor g) {
        int bx = midX(), by = 30;
        CurveFunction curve = activeCurve();
        if (curve == CurveFunction.CUSTOM) {
            g.text(this.font, "C", bx + MID_W / 2 - this.font.width("C") / 2, by + 6, WHITE);
            return;
        }
        int w = MID_W - 12, h = 12, x0 = bx + 6, y0 = by + 16;
        for (int i = 0; i < w; i++) {
            double v = curve.apply(i / (double) (w - 1));
            int py = y0 - (int) Math.round(v * h);
            g.fill(x0 + i, py - 1, x0 + i + 1, py + 1, 0xFFFFFFFF);
        }
    }

    // ---- gradient cylinder preview --------------------------------------------------------------

    /**
     * The gradient as a hollow open-topped cylinder: the top rim is the start of the gradient, the
     * bottom the end, and every ring cell is its own vertical run through the same choice pipeline
     * as placement ({@link GradientChoice}): Curve, then Chaos nudges, per-column Step-length wobble
     * boundaries, and a random pick within each step's Variation band group.
     */
    private void renderGradientPreview(GuiGraphicsExtractor g) {
        int rx = rightX(), rw = rightW();
        if (gradPreviewY + 12 >= this.height - 32) return; // no room at all — ⛶ hidden too
        g.text(this.font, "Preview:", rx, gradPreviewY, GREY);
        if (previewExpanded) return; // drawn by the overlay instead
        int gridY = gradPreviewY + 12;
        if (previewStepGroups.isEmpty()) {
            g.text(this.font, this.font.plainSubstrByWidth("Select blocks to preview", rw), rx, gridY, YELLOW);
            return;
        }
        int availH = (this.height - 34) - gridY;
        int d = rw >= 5 * 2 * ISO_X + 16 ? 5 : 3;
        int h = cylinderRows(d, availH);
        if (h < 3 && d > 3) { d = 3; h = cylinderRows(d, availH); }
        if (h < 3) {
            g.text(this.font, this.font.plainSubstrByWidth("No room — use ⛶ to expand", rw), rx, gridY, YELLOW);
            return;
        }
        h = Math.min(h, Math.max(whiteCount, 8)); // don't stretch a short gradient absurdly tall
        float w = 2 * (d - 1) * ISO_X + 16;
        drawCylinder(g, rx + (rw - w) / 2f, gridY, 1f, d, h);
    }

    /** Rows that fit a d-wide cylinder into {@code availH} pixels (bounding height inverted). */
    private static int cylinderRows(int d, float availH) {
        return 1 + (int) ((availH - 16 - (d - 1) * 2 * ISO_DOWN) / ISO_UP);
    }

    /** Ring cells (x,z) of a d-wide hollow circle, painter-sorted (back to front). */
    private static int[][] ringCells(int d) {
        double c = (d - 1) / 2.0, r = (d - 1) / 2.0;
        List<int[]> cells = new ArrayList<>();
        for (int x = 0; x < d; x++) {
            for (int z = 0; z < d; z++) {
                if (Math.abs(Math.hypot(x - c, z - c) - r) <= 0.5) cells.add(new int[]{x, z});
            }
        }
        cells.sort(java.util.Comparator.comparingInt(a -> a[0] + a[1]));
        return cells.toArray(new int[0][]);
    }

    /**
     * Fill {@link #cylCache} for a d×h cylinder. Fixed-seed random: the preview is stable from frame
     * to frame and re-rolls only when the settings change (rebuild clears the cache) or it resizes.
     */
    private void ensureCylinder(int d, int h) {
        if (cylCache != null && cylD == d && cylH == h) return;
        cylD = d;
        cylH = h;
        cylCells = ringCells(d);
        cylCache = new ItemStack[cylCells.length][h];
        if (previewStepGroups.isEmpty()) return;
        GradientConfig cfg = ConfigManager.get();
        Random rnd = new Random(42);
        int count = previewStepGroups.size();
        double band = 1.0 / count;
        List<Double> custom = cfg.curve == CurveFunction.CUSTOM && cfg.curveBounds.size() == count - 1
                ? cfg.curveBounds : null;
        for (int i = 0; i < cylCells.length; i++) {
            // Per-column wobble boundaries, like placement's per-column wobble key.
            double[] bounds = new double[count - 1];
            for (int k = 0; k < count - 1; k++) {
                double off = cfg.stepWobble > 0 && rnd.nextDouble() < cfg.stepWobble
                        ? (rnd.nextDouble() - 0.5) * band : 0.0;
                bounds[k] = (custom != null ? custom.get(k) : (k + 1) * band) + off;
            }
            for (int j = 0; j < h; j++) {
                double t = h == 1 ? 0 : (double) j / (h - 1);
                double tc = cfg.curve.apply(t);
                if (cfg.chaos > 0 && t > 0 && t < 1 && rnd.nextDouble() < cfg.chaos) {
                    double stepFrac = count > 1 ? 1.0 / (count - 1) : 0.1;
                    tc = Math.max(0, Math.min(1, rnd.nextBoolean() ? tc - stepFrac : tc + stepFrac));
                }
                int pos = 0;
                while (pos < bounds.length && tc >= bounds[pos]) pos++;
                List<ItemStack> group = previewStepGroups.get(pos);
                cylCache[i][j] = group.get(rnd.nextInt(group.size()));
            }
        }
    }

    /** Draw the cylinder with its bounding box top-left at (tx,ty), sprites scaled by {@code s}. */
    private void drawCylinder(GuiGraphicsExtractor g, float tx, float ty, float s, int d, int h) {
        ensureCylinder(d, h);
        g.pose().pushMatrix();
        g.pose().translate(tx, ty);
        g.pose().scale(s, s);
        for (int gy = 0; gy < h; gy++) {  // bottom layer up
            int row = h - 1 - gy;          // row 0 = top rim = gradient start
            for (int i = 0; i < cylCells.length; i++) { // back to front (pre-sorted)
                ItemStack st = cylCache[i][row];
                if (st == null) continue;
                int cx = cylCells[i][0], cz = cylCells[i][1];
                g.pose().pushMatrix();
                g.pose().translate((cx - cz + (d - 1)) * ISO_X, (cx + cz) * ISO_DOWN + row * ISO_UP);
                g.item(st, 0, 0);
                g.pose().popMatrix();
            }
        }
        g.pose().popMatrix();
    }

    // ---- expanded preview overlay ---------------------------------------------------------------

    private boolean inCloseX(double mx, double my) {
        return mx >= this.width - 10 - CLOSE_X_SIZE && mx <= this.width - 10
                && my >= 10 && my <= 10 + CLOSE_X_SIZE;
    }

    /** The expanded preview: dark backdrop over the settings, the preview centred and 2×, ✗ closes. */
    private void renderExpandedOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.nextStratum();
        g.fill(0, 0, this.width, this.height, 0xD0000000);
        if (tab == Tab.GRADIENT) {
            if (previewStepGroups.isEmpty()) {
                g.text(this.font, "Select blocks to preview", this.width / 2 - 60, this.height / 2, YELLOW);
            } else {
                float s = 2f;
                float availW = this.width - 60, availH = this.height - 70;
                int d = 7;
                while (d > 3 && s * (2 * (d - 1) * ISO_X + 16) > availW) d -= 2;
                int h = Math.max(3, Math.min(24, cylinderRows(d, availH / s)));
                h = Math.min(h, Math.max(whiteCount * 2, 12));
                float w = s * (2 * (d - 1) * ISO_X + 16);
                float hp = s * ((d - 1) * 2 * ISO_DOWN + (h - 1) * ISO_UP + 16);
                drawCylinder(g, (this.width - w) / 2f, (this.height - hp) / 2f, s, d, h);
                g.text(this.font, "Top rim = start · bottom = end",
                        this.width / 2 - this.font.width("Top rim = start · bottom = end") / 2,
                        (int) ((this.height + hp) / 2f) + 5, GREY);
            }
        } else {
            if (orderedBlocks.isEmpty()) {
                g.text(this.font, "Select blocks to preview", this.width / 2 - 60, this.height / 2, YELLOW);
            } else {
                float[] l = noiseCubeLayout(true);
                drawNoiseCube(g, l);
                int[] a = noiseAnchor();
                String coords = "At " + a[0] + " " + a[1] + " " + a[2]
                        + " · right = East · left = South · drag a face to pan";
                g.text(this.font, coords, this.width / 2 - this.font.width(coords) / 2,
                        (int) (l[2] + l[3] * (((int) l[0] - 1) * (2 * ISO_DOWN + ISO_UP) + 16)) + 5, GREY);
            }
        }
        // Contrasting close ✗, top-right (Esc works too).
        boolean hover = inCloseX(mouseX, mouseY);
        int bx = this.width - 10 - CLOSE_X_SIZE;
        if (hover) g.fill(bx, 10, bx + CLOSE_X_SIZE, 10 + CLOSE_X_SIZE, 0x30FFFFFF);
        g.pose().pushMatrix();
        g.pose().translate(bx + (CLOSE_X_SIZE - 2f * this.font.width("✗")) / 2f,
                10 + (CLOSE_X_SIZE - 2f * this.font.lineHeight) / 2f);
        g.pose().scale(2f, 2f);
        g.text(this.font, "✗", 0, 0, hover ? YELLOW : WHITE);
        g.pose().popMatrix();
    }

    private boolean inNoisePreview(double mx, double my) {
        if (tab != Tab.NOISE) return false;
        if (previewExpanded) {
            float[] l = noiseCubeLayout(true);
            float w = l[3] * (2 * ((int) l[0] - 1) * ISO_X + 16);
            float h = l[3] * (((int) l[0] - 1) * (2 * ISO_DOWN + ISO_UP) + 16);
            return mx >= l[1] && mx <= l[1] + w && my >= l[2] && my <= l[2] + h;
        }
        int rx = rightX();
        return mx >= rx && mx <= rx + rightW() && my >= noisePreviewY + 12 && my < this.height - 30;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (event.button() == 0 && dragHandle >= 0) {
            int count = orderedBlocks.size();
            List<Double> bounds = activeCurveBounds();
            if (bounds.size() == count - 1 && dragHandle < bounds.size() && stripH() > 0) {
                double v = (event.y() - stripY()) / (double) stripH();
                double min = 4.0 / stripH(); // keep every step at least a few px tall
                double lo = (dragHandle == 0 ? 0 : bounds.get(dragHandle - 1)) + min;
                double hi = (dragHandle == bounds.size() - 1 ? 1 : bounds.get(dragHandle + 1)) - min;
                bounds.set(dragHandle, Math.max(lo, Math.min(hi, v)));
                cylCache = null; // the cylinder re-rolls with the new distribution
            }
            return true;
        }
        if (event.button() == 0 && dragFace != null) {
            // Project the mouse delta onto the locked face's world axes (coords follow the mouse:
            // dragging up a side face shifts the anchor up). The face keeps the lock even when the
            // pointer leaves it mid-drag; releasing the button clears it.
            double mx = -dragX, my = -dragY;
            switch (dragFace) {
                case TOP -> {
                    previewOffX += mx / (2 * ISO_X) + my / (2 * ISO_DOWN);
                    previewOffZ += my / (2 * ISO_DOWN) - mx / (2 * ISO_X);
                }
                case RIGHT -> { // East face: Z runs down-left across it, Y runs up
                    double dz = -mx / ISO_X;
                    previewOffZ += dz;
                    previewOffY += (dz * ISO_DOWN - my) / ISO_UP;
                }
                case LEFT -> {  // South face: X runs down-right across it, Y runs up
                    double dxw = mx / ISO_X;
                    previewOffX += dxw;
                    previewOffY += (dxw * ISO_DOWN - my) / ISO_UP;
                }
            }
            return true;
        }
        if (tab == Tab.FINDER && event.button() == 0) {
            if (finderList != null && finderList.mouseDragged(event.y())) return true;
            if (finderField != null && finderField.contains(event.x(), event.y())) {
                double[] hs = finderField.pick(event.x(), event.y());
                finderHue = hs[0];
                finderSat = hs[1];
                finderRef = FinderRef.COLOR;
                recenterFinder();
                return true;
            }
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0 && dragHandle >= 0) {
            dragHandle = -1;
            ConfigManager.save();
            return true;
        }
        if (event.button() == 0 && dragFace != null) {
            dragFace = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (previewExpanded && event.key() == 256) { // Esc closes the expanded preview first
            previewExpanded = false;
            return true;
        }
        return super.keyPressed(event);
    }

    private void renderSettingsTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        GradientConfig cfg = ConfigManager.get();
        int cx = contentX(), w = leftW();

        g.text(this.font, this.font.plainSubstrByWidth("Start click scans its face to a block", rightW()),
                rightX(), autoEndDescY, YELLOW);
        g.text(this.font, this.font.plainSubstrByWidth("Face mode in markers: level, then stack", rightW()),
                rightX(), fillVoidsDescY, YELLOW);
        g.text(this.font, this.font.plainSubstrByWidth("Idle gap before a new gradient starts", rightW()),
                rightX(), memoryDescY, YELLOW);

        // Darken the tool button while it's armed (drawn over the vanilla button).
        if (assigningTool) g.fill(cx, TOOL_BTN_Y, cx + w, TOOL_BTN_Y + TOOL_BTN_H, PRESSED_OVERLAY);

        int top = TOOL_LIST_TOP;
        String selId = cfg.paintTool;
        for (int i = 0; i < matches.size(); i++) {
            int y = top + i * toolRowHeight;
            ToolRow row = matches.get(i);
            boolean hover = mouseX >= cx && mouseX <= cx + w && mouseY >= y && mouseY < y + toolRowHeight;
            boolean selected = row.id().equals(selId);
            if (hover || selected) g.fill(cx, y, cx + w, y + toolRowHeight, hover ? HOVER_BG : 0x3355FF55);
            int color = selected ? GREEN : (hover ? WHITE : 0xFFC0C0C0);
            g.text(this.font, this.font.plainSubstrByWidth(row.name(), w - 8), cx + 4, y + 2, color);
        }
    }

    // ---- slider widget --------------------------------------------------------------------------

    private static final class ConfigSlider extends AbstractSliderButton {
        private final java.util.function.DoubleConsumer onChange;
        private final java.util.function.DoubleFunction<String> labelFn;

        ConfigSlider(int x, int y, int w, double initial,
                     java.util.function.DoubleFunction<String> labelFn, java.util.function.DoubleConsumer onChange) {
            super(x, y, w, 20, Component.empty(), initial);
            this.labelFn = labelFn; this.onChange = onChange; updateMessage();
        }
        @Override protected void updateMessage() { setMessage(Component.literal(labelFn.apply(this.value))); }
        @Override protected void applyValue() { onChange.accept(this.value); ConfigManager.save(); }
    }

    @Override
    public void onClose() { ConfigManager.save(); super.onClose(); }
}
