package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
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
import java.util.Set;

/**
 * The FW Paint screen (opened by K). Three tabs:
 * <ul>
 *   <li><b>Gradient</b> — block picker (left) + gradient settings (right);</li>
 *   <li><b>Noise Paint</b> — block picker (left) + noise settings (right, placeholder);</li>
 *   <li><b>Settings</b> — pick the gradient/noise tool items, per-tool mode, marker options.</li>
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
    private static final int TOOL_LIST_TOP = 100;

    private enum Tab { GRADIENT, NOISE, SETTINGS }
    private enum AssignTarget { NONE, GRADIENT, NOISE }
    private enum Assign { NONE, START, END, REQUIRE, EXCLUDE }

    private static final int TOOL_GRAD_BTN_Y = 30;
    private static final int TOOL_NOISE_BTN_Y = 52;
    private static final int TOOL_BTN_H = 20;

    // Picker-tab left column (aligned with the right column's first button at y=30).
    private static final int PICK_SRC_Y = 30;
    private static final int PICK_BTN_Y = 54;
    private static final int PICK_LIST_Y = 78;
    private static final int PRESSED_OVERLAY = 0x80000000; // darkens a vanilla button to show "on"

    private Assign assign = Assign.NONE;

    private record SourceBlock(String id, ItemStack stack, Block block) {}
    private record ToolRow(String id, String name) {}

    private Tab tab = Tab.GRADIENT;

    // Picker tabs (Gradient + Noise) state.
    private BlockPickerPanel picker;
    private List<SourceBlock> sourceBlocks = new ArrayList<>();
    private List<SourceBlock> orderedBlocks = new ArrayList<>(); // distinct gradient order (valley→peak)
    private int whiteCount = 0;
    private final List<String> whiteOrder = new ArrayList<>(); // ids of the placement sequence (for logging)
    private String previewStartId, previewEndId;
    private int modeDescY, deviationDescY, chaosDescY;

    // Settings tab state.
    private EditBox filterBox;
    private final List<ToolRow> matches = new ArrayList<>();
    private String filter = "";
    private int toolRowHeight = 12;
    private AssignTarget assignTarget = AssignTarget.NONE;

    public GradientScreen() {
        super(Component.literal("FW Paint"));
    }

    // ---- layout helpers -------------------------------------------------------------------------

    private int colW() {
        int avail = this.width - 2 * LEFT_X;
        return Math.max(60, Math.min(COL_W_MAX, (avail - COL_GAP) / 2));
    }
    private int contentX() { return Math.max(LEFT_X, (this.width - (2 * colW() + COL_GAP)) / 2); }
    private int rightX() { return contentX() + colW() + COL_GAP; }
    private int rightW() { return colW(); }
    private int leftW() { return colW(); }

    @Override
    protected void init() {
        toolRowHeight = this.font.lineHeight + 3;
        if (tab == Tab.GRADIENT || tab == Tab.NOISE) initPickerTab();
        else initSettingsTab();

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(this.width / 2 - 50, this.height - 26, 100, 20).build());
    }

    private void setTab(Tab t) { tab = t; rebuildWidgets(); }

    // ---- tabs in the title bar ------------------------------------------------------------------

    private String tabName(Tab t) {
        return switch (t) { case GRADIENT -> "Gradient"; case NOISE -> "Noise Paint"; case SETTINGS -> "Settings"; };
    }
    private String tabText(Tab t) { return tab == t ? "» " + tabName(t) : tabName(t); }

    /** Right-aligned tab x positions: returns {x,width} pairs for GRADIENT, NOISE, SETTINGS. */
    private int[] tabXs() {
        int gap = 14;
        int sw = this.font.width(tabText(Tab.SETTINGS));
        int nw = this.font.width(tabText(Tab.NOISE));
        int gw = this.font.width(tabText(Tab.GRADIENT));
        int sx = this.width - 10 - sw;
        int nx = sx - gap - nw;
        int gx = nx - gap - gw;
        return new int[]{gx, gw, nx, nw, sx, sw};
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

        // Four assign buttons under Source: [S] [E] ✓ ✗ — set the click-assign mode (overlay shows on).
        addRenderableWidget(Button.builder(Component.literal("[S]"),
                b -> assign = assign == Assign.START ? Assign.NONE : Assign.START).bounds(cx, PICK_BTN_Y, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("[E]"),
                b -> assign = assign == Assign.END ? Assign.NONE : Assign.END).bounds(cx + bw, PICK_BTN_Y, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("✓"),
                b -> assign = assign == Assign.REQUIRE ? Assign.NONE : Assign.REQUIRE).bounds(cx + bw * 2, PICK_BTN_Y, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("✗"),
                b -> assign = assign == Assign.EXCLUDE ? Assign.NONE : Assign.EXCLUDE).bounds(cx + bw * 3, PICK_BTN_Y, w - bw * 3, 20).build());

        picker.setBounds(cx, PICK_LIST_Y, w, (this.height - 30) - PICK_LIST_Y);
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

        cycleButton(rx, y, rw, this::curveLabel, () -> {
            GradientConfig c = ConfigManager.get(); c.curve = c.curve.next(); rebuildDisplayRows(); });
        y += 24;
        cycleButton(rx, y, rw, this::sourceLabel, () -> {
            GradientConfig c = ConfigManager.get(); c.source = c.source.next();
            sourceBlocks = gatherSourceBlocks(); rebuildDisplayRows(); });
        y += 24;

        addRenderableWidget(new ConfigSlider(rx, y, rw, cfg.deviationBudget,
                v -> "Deviation: " + Math.round(v * 100) + "%",
                v -> { ConfigManager.get().deviationBudget = v; rebuildDisplayRows(); }));
        y += 24; deviationDescY = y; y += 12;

        addRenderableWidget(new ConfigSlider(rx, y, rw, cfg.chaos,
                v -> "Chaos: " + Math.round(v * 100) + "%",
                v -> { ConfigManager.get().chaos = v; rebuildDisplayRows(); }));
        y += 24; chaosDescY = y; y += 12;

        addRenderableWidget(new ConfigSlider(rx, y, rw, stepsToSlider(cfg.maxSteps),
                v -> "Max steps: " + sliderToSteps(v),
                v -> { ConfigManager.get().maxSteps = sliderToSteps(v); rebuildDisplayRows(); }));
    }

    private int noisePreviewY;          // y where the noise preview grid starts (set in initNoiseSettings)
    private double previewOffX, previewOffZ; // pan offset (in cells) for scrubbing the preview

    private void initNoiseSettings() {
        int rx = rightX(), rw = rightW();
        GradientConfig cfg = ConfigManager.get();
        int y = 30;

        cycleButton(rx, y, rw, () -> Component.literal("Noise: " + ConfigManager.get().noiseType.displayName()),
                () -> ConfigManager.get().noiseType = ConfigManager.get().noiseType.next());
        y += 24;
        // Block-ordering mode for the noise tool (its own, separate from the gradient tool).
        cycleButton(rx, y, rw, () -> Component.literal("Order: " + ConfigManager.get().noiseGradientMode.displayName()),
                () -> { GradientConfig c = ConfigManager.get(); c.noiseGradientMode = c.noiseGradientMode.next(); rebuildDisplayRows(); });
        y += 24;

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
        y += 26;
        noisePreviewY = y;
    }

    private long noiseSeedLong() {
        String s = ConfigManager.get().noiseSeed.trim();
        if (s.isEmpty()) return 0L;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return s.hashCode(); }
    }

    private static int sliderToScale(double v) { return Math.max(1, Math.min(64, 1 + (int) Math.round(v * 63))); }
    private static double scaleToSlider(double s) { return (Math.max(1, Math.min(64, s)) - 1) / 63.0; }

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

    /** The block-ordering mode for the active tab (gradient and noise each have their own). */
    private GradientMode activeMode() {
        return tab == Tab.NOISE ? ConfigManager.get().noiseGradientMode : ConfigManager.get().gradientMode;
    }

    private void rebuildDisplayRows() {
        if (picker == null) return;
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
        double pct = cfg.pixelPercent;
        int startRgb = startBlock == null ? 0 : BlockTextures.gradientValue(startBlock, startBlock, mode, pct);
        int endRgb = endBlock == null ? 0 : BlockTextures.gradientValue(endBlock, startBlock, mode, pct);
        int[] rgbs = new int[nonEx.size()];
        Set<Integer> forced = new HashSet<>();
        for (int i = 0; i < nonEx.size(); i++) {
            rgbs[i] = BlockTextures.gradientValue(nonEx.get(i).block(), startBlock, mode, pct);
            if (required.contains(nonEx.get(i).id())) forced.add(i);
        }

        int[] order = nonEx.isEmpty() ? new int[0]
                : GradientRamp.gradientOrder(rgbs, startRgb, endRgb, mode, cfg.deviationBudget, forced);
        int[] used = GradientRamp.subsample(order, cfg.maxSteps);
        orderedBlocks = new ArrayList<>();
        for (int idx : used) orderedBlocks.add(nonEx.get(idx)); // distinct valley→peak order for noise
        int[] seq = used.length == 0 ? new int[0]
                : GradientRamp.buildSequence(used, cfg.curve, used.length, cfg.chaos, new java.util.Random());

        List<BlockPickerPanel.Row> rows = new ArrayList<>();
        whiteOrder.clear();
        Set<Integer> inSeq = new HashSet<>();
        for (int j = 0; j < seq.length; j++) {
            int idx = seq[j];
            inSeq.add(idx);
            SourceBlock sb = nonEx.get(idx);
            String tag = (j == 0) ? " [S]" : (j == seq.length - 1 && seq.length > 1) ? " [E]" : "";
            int color = required.contains(sb.id()) ? GREEN : WHITE;
            rows.add(new BlockPickerPanel.Row(sb.stack(), sb.id(), color, tag));
            whiteOrder.add(sb.id());
        }
        whiteCount = seq.length;
        // Not in the sequence: green if must-use, else blue.
        for (int i = 0; i < nonEx.size(); i++) {
            if (inSeq.contains(i)) continue;
            SourceBlock sb = nonEx.get(i);
            int color = required.contains(sb.id()) ? GREEN : BLUE;
            rows.add(new BlockPickerPanel.Row(sb.stack(), sb.id(), color, ""));
        }
        // Excluded (red) at the bottom.
        for (SourceBlock sb : sourceBlocks) {
            if (excluded.contains(sb.id())) rows.add(new BlockPickerPanel.Row(sb.stack(), sb.id(), RED, ""));
        }

        picker.setRows(rows);
        logPreview();
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

    // ---- Settings tab ---------------------------------------------------------------------------

    private void initSettingsTab() {
        int rx = rightX(), rw = rightW();
        GradientConfig cfg = ConfigManager.get();
        int y = 30;

        cycleButton(rx, y, rw, () -> Component.literal("Gradient mode: " + cfg.gradientToolMode.displayName()),
                () -> { ConfigManager.get().gradientToolMode = ConfigManager.get().gradientToolMode.next(); });
        y += 24;
        cycleButton(rx, y, rw, () -> Component.literal("Noise mode: " + cfg.noiseToolMode.displayName()),
                () -> { ConfigManager.get().noiseToolMode = ConfigManager.get().noiseToolMode.next(); });
        y += 24;

        addRenderableWidget(new ConfigSlider(rx, y, rw, distToSlider(cfg.maxMarkerDistance),
                v -> "Marker dist: " + sliderToDist(v),
                v -> ConfigManager.get().maxMarkerDistance = sliderToDist(v)));
        y += 24;
        cycleButton(rx, y, rw, () -> Component.literal("Auto end marker: " + (ConfigManager.get().autoPlaceEnd ? "On" : "Off")),
                () -> ConfigManager.get().autoPlaceEnd = !ConfigManager.get().autoPlaceEnd);
        y += 24;

        addRenderableWidget(Button.builder(clearMarkersLabel(), b -> {
            MarkerManager.clearAll(); b.setMessage(clearMarkersLabel());
        }).bounds(rx, y, rw, 20).build());
        y += 24;
        cycleButton(rx, y, rw, () -> Component.literal("Debug logging: " + (ConfigManager.get().debug ? "On" : "Off")),
                () -> ConfigManager.get().debug = !ConfigManager.get().debug);

        // Left column: two vanilla tool-assign buttons (overlay shows which is armed) + filter + list.
        int cx = contentX(), w = leftW();
        String gt = cfg.gradientTool.isEmpty() ? "(none)" : Gradient.toolDisplayName(cfg.gradientTool);
        String nt = cfg.noiseTool.isEmpty() ? "(none)" : Gradient.toolDisplayName(cfg.noiseTool);
        addRenderableWidget(Button.builder(Component.literal("Gradient tool: " + gt),
                b -> assignTarget = assignTarget == AssignTarget.GRADIENT ? AssignTarget.NONE : AssignTarget.GRADIENT)
                .bounds(cx, TOOL_GRAD_BTN_Y, w, TOOL_BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("Noise tool: " + nt),
                b -> assignTarget = assignTarget == AssignTarget.NOISE ? AssignTarget.NONE : AssignTarget.NOISE)
                .bounds(cx, TOOL_NOISE_BTN_Y, w, TOOL_BTN_H).build());

        filterBox = new EditBox(this.font, cx, 76, w, 20, Component.literal("Filter"));
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

    // ---- input ----------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.y() < BAR_H && event.button() == 0) {
            int[] xs = tabXs();
            if (event.x() >= xs[0] && event.x() <= xs[0] + xs[1]) { setTab(Tab.GRADIENT); return true; }
            if (event.x() >= xs[2] && event.x() <= xs[2] + xs[3]) { setTab(Tab.NOISE); return true; }
            if (event.x() >= xs[4] && event.x() <= xs[4] + xs[5]) { setTab(Tab.SETTINGS); return true; }
        }
        if (super.mouseClicked(event, doubled)) return true;
        if ((tab == Tab.GRADIENT || tab == Tab.NOISE) && picker != null && event.button() == 0) {
            String id = picker.rowIdAt(event.x(), event.y());
            if (id != null && assign != Assign.NONE) {
                GradientConfig cfg = ConfigManager.get();
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
        if (tab == Tab.SETTINGS && event.button() == 0) {
            int idx = toolRowAt(event.x(), event.y());
            if (idx >= 0 && assignTarget != AssignTarget.NONE) {
                String id = matches.get(idx).id();
                if (assignTarget == AssignTarget.GRADIENT) ConfigManager.get().gradientTool = id;
                else ConfigManager.get().noiseTool = id;
                ConfigManager.save();
                assignTarget = AssignTarget.NONE; // un-arm + refresh the button label
                rebuildWidgets();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if ((tab == Tab.GRADIENT || tab == Tab.NOISE) && picker != null && picker.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ---- labels ---------------------------------------------------------------------------------

    private Component gradientLabel() { return Component.literal("Gradient: " + ConfigManager.get().gradientMode.displayName()); }
    private Component curveLabel() { return Component.literal("Curve: " + ConfigManager.get().curve.displayName()); }
    private Component sourceLabel() { return Component.literal("Source: " + ConfigManager.get().source.displayName()); }
    private Component clearMarkersLabel() {
        return Component.literal("Clear Markers (" + (MarkerManager.startMarkers.size() + MarkerManager.endMarkers.size()) + ")");
    }

    private String modeDescription() {
        return switch (ConfigManager.get().gradientMode) {
            case COLOR -> "Average texture colour.";
            case BRIGHTNESS -> "Average texture brightness.";
            case TOP_DARK_COLOR -> "Colour of the darkest pixels.";
            case TOP_DARK -> "Brightness of the darkest pixels.";
            case TOP_LIGHT_COLOR -> "Colour of the lightest pixels.";
            case TOP_LIGHT -> "Brightness of the lightest pixels.";
            case BW_DIFF -> "B&W texture difference from start.";
            case COLOR_DIFF -> "Colour texture difference from start.";
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

    // ---- rendering ------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        renderTitleBar(g);
        if (tab == Tab.GRADIENT) renderGradientTab(g, mouseX, mouseY);
        else if (tab == Tab.NOISE) renderNoiseTab(g, mouseX, mouseY);
        else renderSettingsTab(g, mouseX, mouseY);
    }

    private void renderTitleBar(GuiGraphicsExtractor g) {
        g.fill(0, 0, this.width, BAR_H, BAR_BG);
        g.fill(0, BAR_H, this.width, BAR_H + 1, BAR_LINE);
        int textY = (BAR_H - this.font.lineHeight) / 2 + 1;
        g.text(this.font, "FW Paint", 8, textY, WHITE);
        int[] xs = tabXs();
        g.text(this.font, tabText(Tab.GRADIENT), xs[0], textY, tab == Tab.GRADIENT ? WHITE : GREY);
        g.text(this.font, tabText(Tab.NOISE), xs[2], textY, tab == Tab.NOISE ? WHITE : GREY);
        g.text(this.font, tabText(Tab.SETTINGS), xs[4], textY, tab == Tab.SETTINGS ? WHITE : GREY);
    }

    /** Darken the active assign button (drawn over the vanilla button) to show it's toggled on. */
    private void renderAssignOverlay(GuiGraphicsExtractor g) {
        if (assign == Assign.NONE) return;
        g.fill(assignBtnX(), PICK_BTN_Y, assignBtnX() + assignBtnW(), PICK_BTN_Y + 20, PRESSED_OVERLAY);
    }

    private void renderGradientTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (picker != null) picker.render(g, mouseX, mouseY);
        renderAssignOverlay(g);
        int rx = rightX(), rw = rightW();
        g.text(this.font, this.font.plainSubstrByWidth(modeDescription(), rw), rx, modeDescY, YELLOW);
        g.text(this.font, this.font.plainSubstrByWidth("Max colour distance from the gradient", rw), rx, deviationDescY, YELLOW);
        g.text(this.font, this.font.plainSubstrByWidth("Chance to repeat or skip a step", rw), rx, chaosDescY, YELLOW);
    }

    private void renderNoiseTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (picker != null) picker.render(g, mouseX, mouseY);
        renderAssignOverlay(g);
        renderNoisePreview(g);
    }

    /** Top-down slice of the noise field, each cell drawn as the block it maps to (valley→peak). */
    private void renderNoisePreview(GuiGraphicsExtractor g) {
        int rx = rightX(), rw = rightW();
        g.text(this.font, "Preview:", rx, noisePreviewY, GREY);
        int gridY = noisePreviewY + 12;
        if (orderedBlocks.isEmpty()) {
            g.text(this.font, this.font.plainSubstrByWidth("Select blocks to preview", rw), rx, gridY, YELLOW);
            return;
        }
        GradientConfig cfg = ConfigManager.get();
        long seed = noiseSeedLong();
        int cells = Math.max(1, rw / 16);                       // square-ish grid sized to the column
        int rows = Math.max(1, Math.min(cells, (this.height - 30 - gridY) / 16));
        for (int gz = 0; gz < rows; gz++) {
            for (int gx = 0; gx < cells; gx++) {
                // Top-down (XZ) slice at y=0; drag the grid to pan. Noise.sample is equalised so the
                // value spreads across [0,1] → the whole block list is used, not just the middle.
                double v = Noise.sample(cfg.noiseType, gx + previewOffX, 0, gz + previewOffZ, seed,
                        cfg.noiseScaleX, cfg.noiseScaleY, cfg.noiseScaleZ);
                int idx = GradientRamp.rampIndex(orderedBlocks.size(), cfg.curve.apply(v));
                g.item(orderedBlocks.get(idx).stack(), rx + gx * 16, gridY + gz * 16);
            }
        }
    }

    private boolean inNoisePreview(double mx, double my) {
        int rx = rightX();
        return tab == Tab.NOISE && mx >= rx && mx <= rx + rightW() && my >= noisePreviewY + 12 && my < this.height - 30;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (event.button() == 0 && inNoisePreview(event.x(), event.y())) {
            previewOffX -= dragX / 16.0; // one cell == 16px
            previewOffZ -= dragY / 16.0;
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    private void renderSettingsTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        GradientConfig cfg = ConfigManager.get();
        int cx = contentX(), w = leftW();

        // Darken whichever tool button is armed (drawn over the vanilla button).
        if (assignTarget == AssignTarget.GRADIENT) g.fill(cx, TOOL_GRAD_BTN_Y, cx + w, TOOL_GRAD_BTN_Y + TOOL_BTN_H, PRESSED_OVERLAY);
        if (assignTarget == AssignTarget.NOISE) g.fill(cx, TOOL_NOISE_BTN_Y, cx + w, TOOL_NOISE_BTN_Y + TOOL_BTN_H, PRESSED_OVERLAY);

        int top = TOOL_LIST_TOP;
        String selId = (assignTarget == AssignTarget.GRADIENT) ? cfg.gradientTool : cfg.noiseTool;
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
