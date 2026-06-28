package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
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
import java.util.Set;

/**
 * Two-tab settings screen (opened by the K keybind):
 * <ul>
 *   <li><b>Gradient</b> — the source-block palette (left) in the order it would be placed, plus the
 *       gradient settings and a live preview (right). Left-click a block = preview start, right = end,
 *       middle = exclude. Three categories: used (white), auto-skipped by the deviation budget (blue),
 *       manually excluded (red).</li>
 *   <li><b>Tool</b> — pick the activation item, switch mode, clear markers.</li>
 * </ul>
 */
public class GradientScreen extends Screen {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFA0A0A0;
    private static final int BLUE = 0xFF5599FF;   // auto-skipped (deviation)
    private static final int RED = 0xFFFF5555;    // manually excluded
    private static final int YELLOW = 0xFFFFE34D; // help text
    private static final int HOVER_BG = 0x33FFFFFF;

    private static final int LEFT_X = 10;
    private static final int LIST_TOP = 42;
    private static final int ROW_H = 18;
    private static final int TOOL_LIST_TOP = 66; // settings tab item list start (below the filter)
    private static final int BAR_H = 22;          // title bar height
    private static final int BAR_BG = 0x80000000; // semi-transparent black title bar
    private static final int BAR_LINE = 0x60FFFFFF;

    private enum Tab { CONFIGURE, SETTINGS }
    private enum Category { USED, NOTPICKED, EXCLUDED }

    private record SourceBlock(String id, ItemStack stack, Block block) {}
    private record DisplayRow(SourceBlock block, Category category) {}
    private record ToolRow(String id, String name) {}

    private Tab tab = Tab.CONFIGURE;

    // Gradient tab state.
    private List<SourceBlock> sourceBlocks = new ArrayList<>();
    private List<DisplayRow> displayRows = new ArrayList<>();
    private int whiteCount = 0; // number of leading USED (placed-sequence) rows
    private int modeDescY, deviationDescY, chaosDescY; // y of the yellow help lines
    private String previewStartId;
    private String previewEndId;
    private int paletteScroll = 0;

    // Tool tab state.
    private EditBox filterBox;
    private Button modeButton;
    private final List<ToolRow> matches = new ArrayList<>();
    private String filter = "";
    private int toolRowHeight = 12;

    public GradientScreen() {
        super(Component.literal("Gradient"));
    }

    // ---- layout ---------------------------------------------------------------------------------

    @Override
    protected void init() {
        toolRowHeight = this.font.lineHeight + 3;

        // Tabs are drawn as text in the title bar (see extractRenderState) and handled in mouseClicked.
        if (tab == Tab.CONFIGURE) initGradientTab();
        else initToolTab();

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(this.width / 2 - 50, this.height - 26, 100, 20).build());
    }

    /** Tab label with a » chevron on the selected one. */
    private String tabText(Tab t) {
        String name = (t == Tab.CONFIGURE) ? "Configure" : "Settings";
        return tab == t ? "» " + name : name;
    }

    /** Right-aligned tab hit-boxes in the title bar: {configureX, configureW, settingsX, settingsW}. */
    private int[] tabXs() {
        int cw = this.font.width(tabText(Tab.CONFIGURE));
        int sw = this.font.width(tabText(Tab.SETTINGS));
        int gap = 16;
        int sx = this.width - 10 - sw;
        int cx = sx - gap - cw;
        return new int[]{cx, cw, sx, sw};
    }

    private void setTab(Tab t) {
        tab = t;
        rebuildWidgets();
    }

    // Two columns capped at a max width and centred, so wide windows (small GUI scale) keep tidy
    // columns with balanced margins instead of stretching to half-width.
    private static final int COL_W_MAX = 200;
    private static final int COL_GAP = 12;

    private int colW() {
        int avail = this.width - 2 * LEFT_X;
        return Math.max(60, Math.min(COL_W_MAX, (avail - COL_GAP) / 2));
    }

    private int contentX() {
        int total = 2 * colW() + COL_GAP;
        return Math.max(LEFT_X, (this.width - total) / 2);
    }

    private int rightX() {
        return contentX() + colW() + COL_GAP;
    }

    private int rightW() {
        return colW();
    }

    private void initGradientTab() {
        int rx = rightX(), rw = rightW();
        sourceBlocks = gatherSourceBlocks();
        rebuildDisplayRows();
        GradientConfig cfg = ConfigManager.get();
        int y = 42;

        // Mode change re-lays-out the tab (so the Pixel % slider shows/hides).
        addRenderableWidget(Button.builder(gradientLabel(), b -> {
            GradientConfig c = ConfigManager.get();
            c.gradientMode = c.gradientMode.next();
            ConfigManager.save();
            rebuildWidgets();
        }).bounds(rx, y, rw, 20).build());
        y += 24;

        // Pixel % only matters for the "top %" texture modes — sits right under the mode button.
        if (cfg.gradientMode.usesPixelPercent()) {
            addRenderableWidget(new ConfigSlider(rx, y, rw, cfg.pixelPercent,
                    v -> "Pixel %: " + Math.round(v * 100) + "%",
                    v -> { ConfigManager.get().pixelPercent = v; rebuildDisplayRows(); }));
            y += 24;
        }
        modeDescY = y;
        y += 12;

        cycleButton(rx, y, rw, this::curveLabel, () -> {
            GradientConfig c = ConfigManager.get(); c.curve = c.curve.next(); rebuildDisplayRows();
        });
        y += 24;
        cycleButton(rx, y, rw, this::sourceLabel, () -> {
            GradientConfig c = ConfigManager.get(); c.source = c.source.next();
            sourceBlocks = gatherSourceBlocks(); rebuildDisplayRows();
        });
        y += 24;

        addRenderableWidget(new ConfigSlider(rx, y, rw, cfg.deviationBudget,
                v -> "Deviation: " + Math.round(v * 100) + "%",
                v -> { ConfigManager.get().deviationBudget = v; rebuildDisplayRows(); }));
        y += 24;
        deviationDescY = y;
        y += 12;

        addRenderableWidget(new ConfigSlider(rx, y, rw, cfg.chaos,
                v -> "Chaos: " + Math.round(v * 100) + "%",
                v -> { ConfigManager.get().chaos = v; rebuildDisplayRows(); }));
        y += 24;
        chaosDescY = y;
        y += 12;

        addRenderableWidget(new ConfigSlider(rx, y, rw, stepsToSlider(cfg.maxSteps),
                v -> "Max steps: " + sliderToSteps(v),
                v -> { ConfigManager.get().maxSteps = sliderToSteps(v); rebuildDisplayRows(); }));
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

    private void initToolTab() {
        int rx = rightX(), rw = rightW();

        // Right column: buttons.
        modeButton = addRenderableWidget(Button.builder(modeLabel(), b -> {
            GradientConfig cfg = ConfigManager.get();
            cfg.mode = cfg.mode.next();
            ConfigManager.save();
            b.setMessage(modeLabel());
        }).bounds(rx, 32, rw, 20).build());

        addRenderableWidget(Button.builder(clearMarkersLabel(), b -> {
            MarkerManager.clearAll();
            b.setMessage(clearMarkersLabel());
        }).bounds(rx, 56, rw, 20).build());

        addRenderableWidget(Button.builder(debugLabel(), b -> {
            GradientConfig cfg = ConfigManager.get();
            cfg.debug = !cfg.debug;
            ConfigManager.save();
            b.setMessage(debugLabel());
        }).bounds(rx, 80, rw, 20).build());

        // Left column: filter + tool list.
        filterBox = new EditBox(this.font, contentX(), 44, leftW(), 20, Component.literal("Filter"));
        filterBox.setHint(Component.literal("Filter items…"));
        filterBox.setMaxLength(64);
        filterBox.setValue(filter);
        filterBox.setResponder(s -> { filter = s; rebuildMatches(); });
        addRenderableWidget(filterBox);
        setInitialFocus(filterBox);
        rebuildMatches();
    }


    private void cycleButton(int x, int y, int w, java.util.function.Supplier<Component> label, Runnable onCycle) {
        addRenderableWidget(Button.builder(label.get(), b -> {
            onCycle.run();
            ConfigManager.save();
            b.setMessage(label.get());
        }).bounds(x, y, w, 20).build());
    }

    private static int sliderToSteps(double v) {
        return Math.max(1, Math.min(16, 1 + (int) Math.round(v * 15)));
    }

    private static double stepsToSlider(int steps) {
        return (Math.max(1, Math.min(16, steps)) - 1) / 15.0;
    }

    // ---- gradient tab data ----------------------------------------------------------------------

    private List<SourceBlock> gatherSourceBlocks() {
        List<SourceBlock> out = new ArrayList<>();
        if (this.minecraft == null || this.minecraft.player == null) return out;
        var player = this.minecraft.player;
        var items = player.getInventory().getNonEquipmentItems();
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

    private void rebuildDisplayRows() {
        displayRows = new ArrayList<>();
        GradientConfig cfg = ConfigManager.get();
        Set<String> excluded = new HashSet<>(cfg.excludedBlocks);

        List<SourceBlock> nonEx = new ArrayList<>();
        for (SourceBlock sb : sourceBlocks) if (!excluded.contains(sb.id())) nonEx.add(sb);
        ensureEndpoints(nonEx);

        Block startBlock = blockOfId(previewStartId, nonEx);
        Block endBlock = blockOfId(previewEndId, nonEx);
        GradientMode mode = cfg.gradientMode;
        double pct = cfg.pixelPercent;
        int startRgb = startBlock == null ? 0 : BlockTextures.gradientValue(startBlock, startBlock, mode, pct);
        int endRgb = endBlock == null ? 0 : BlockTextures.gradientValue(endBlock, startBlock, mode, pct);
        int[] rgbs = new int[nonEx.size()];
        for (int i = 0; i < nonEx.size(); i++) {
            rgbs[i] = BlockTextures.gradientValue(nonEx.get(i).block(), startBlock, mode, pct);
        }

        // Eligible blocks near the gradient, then the max-steps cap = what actually gets placed.
        int[] order = nonEx.isEmpty() ? new int[0]
                : GradientRamp.gradientOrder(rgbs, startRgb, endRgb, cfg.gradientMode, cfg.deviationBudget);
        int[] used = GradientRamp.subsample(order, cfg.maxSteps);

        // White = the actual placement SEQUENCE (start pinned first → end pinned last), so curve and
        // chaos show up as repeats/skips in the middle. New random each rebuild so sliding chaos
        // shows different outcomes.
        int[] seq = used.length == 0 ? new int[0]
                : GradientRamp.buildSequence(used, cfg.curve, used.length, cfg.chaos, new java.util.Random());
        Set<Integer> inSequence = new HashSet<>();
        for (int idx : seq) {
            displayRows.add(new DisplayRow(nonEx.get(idx), Category.USED));
            inSequence.add(idx);
        }
        whiteCount = seq.length;

        // Blue = source blocks not in THIS preview — either ineligible (budget/range/max-steps) or
        // dropped this round because chaos replaced their step with a duplicate.
        for (int i = 0; i < nonEx.size(); i++) {
            if (!inSequence.contains(i)) displayRows.add(new DisplayRow(nonEx.get(i), Category.NOTPICKED));
        }
        // Red = manually excluded.
        for (SourceBlock sb : sourceBlocks) {
            if (excluded.contains(sb.id())) displayRows.add(new DisplayRow(sb, Category.EXCLUDED));
        }

        paletteScroll = Math.max(0, Math.min(paletteScroll, maxScroll()));
        logPreview();
    }

    /** Log the white placement order + flag anomalies (first≠start / last≠end), when debug is on. */
    private void logPreview() {
        if (!ConfigManager.get().debug) return;
        List<String> white = new ArrayList<>();
        for (int i = 0; i < whiteCount && i < displayRows.size(); i++) white.add(displayRows.get(i).block().id());
        GradientConfig cfg = ConfigManager.get();
        Gradient.LOG.info("[preview] start={} end={} steps={} curve={} chaos={} | white={}",
                previewStartId, previewEndId, white.size(), cfg.curve, Math.round(cfg.chaos * 100) / 100.0, white);
        if (!white.isEmpty()) {
            if (!white.get(0).equals(previewStartId)) {
                Gradient.LOG.warn("[preview] ANOMALY first white {} != start {}", white.get(0), previewStartId);
            }
            if (white.size() > 1 && !white.get(white.size() - 1).equals(previewEndId)) {
                Gradient.LOG.warn("[preview] ANOMALY last white {} != end {}", white.get(white.size() - 1), previewEndId);
            }
        }
    }

    private void ensureEndpoints(List<SourceBlock> nonEx) {
        boolean startOk = previewStartId != null && nonEx.stream().anyMatch(s -> s.id().equals(previewStartId));
        boolean endOk = previewEndId != null && nonEx.stream().anyMatch(s -> s.id().equals(previewEndId));
        if (startOk && endOk) return;
        // Default endpoints use a start-independent brightness so diff modes have a stable reference.
        SourceBlock lightest = null, darkest = null;
        double lightestL = -1, darkestL = Double.MAX_VALUE;
        for (SourceBlock sb : nonEx) {
            double l = BlockTextures.baseLuminance(sb.block());
            if (lightest == null || l > lightestL) { lightest = sb; lightestL = l; }
            if (darkest == null || l < darkestL) { darkest = sb; darkestL = l; }
        }
        if (!startOk) previewStartId = lightest != null ? lightest.id() : null; // lightest = start
        if (!endOk) previewEndId = darkest != null ? darkest.id() : null;       // darkest = end
    }


    private int visibleRows() {
        return Math.max(1, (this.height - 34 - LIST_TOP) / ROW_H);
    }

    private int maxScroll() {
        return Math.max(0, displayRows.size() - visibleRows());
    }

    private int leftW() {
        return colW();
    }

    // ---- input ----------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // Title-bar tabs (text, not widgets).
        if (event.y() < BAR_H && event.button() == 0) {
            int[] xs = tabXs();
            if (event.x() >= xs[0] && event.x() <= xs[0] + xs[1]) { setTab(Tab.CONFIGURE); return true; }
            if (event.x() >= xs[2] && event.x() <= xs[2] + xs[3]) { setTab(Tab.SETTINGS); return true; }
        }
        if (super.mouseClicked(event, doubled)) return true;
        if (tab == Tab.CONFIGURE) {
            int idx = paletteRowAt(event.x(), event.y());
            if (idx >= 0) { handlePaletteClick(displayRows.get(idx), event.button()); return true; }
        } else if (event.button() == 0) {
            int idx = toolRowAt(event.x(), event.y());
            if (idx >= 0) {
                ConfigManager.get().selectedTool = matches.get(idx).id();
                ConfigManager.save();
                return true;
            }
        }
        return false;
    }

    private void handlePaletteClick(DisplayRow row, int button) {
        SourceBlock sb = row.block();
        if (button == 2) { // middle: toggle exclusion
            List<String> ex = ConfigManager.get().excludedBlocks;
            if (ex.contains(sb.id())) ex.remove(sb.id());
            else ex.add(sb.id());
            ConfigManager.save();
            rebuildDisplayRows();
        } else if (row.category() != Category.EXCLUDED) {
            if (button == 0) previewStartId = sb.id();      // left: preview start
            else if (button == 1) previewEndId = sb.id();   // right: preview end
            rebuildDisplayRows();
        }
    }

    private int paletteRowAt(double mouseX, double mouseY) {
        int cx = contentX();
        if (mouseX < cx || mouseX > cx + leftW() || mouseY < LIST_TOP) return -1;
        int idx = paletteScroll + (int) ((mouseY - LIST_TOP) / ROW_H);
        return (idx >= 0 && idx < displayRows.size() && mouseY < LIST_TOP + visibleRows() * ROW_H) ? idx : -1;
    }

    private int toolRowAt(double mouseX, double mouseY) {
        int cx = contentX();
        if (mouseX < cx || mouseX > cx + leftW() || mouseY < TOOL_LIST_TOP) return -1;
        int idx = (int) ((mouseY - TOOL_LIST_TOP) / toolRowHeight);
        return (idx >= 0 && idx < matches.size()) ? idx : -1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab == Tab.CONFIGURE && scrollY != 0) {
            paletteScroll = Math.max(0, Math.min(maxScroll(), paletteScroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ---- tool tab list --------------------------------------------------------------------------

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
            if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q) && !idStr.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            if (matches.size() >= maxRows) break;
            matches.add(new ToolRow(idStr, name));
        }
    }

    // ---- labels ---------------------------------------------------------------------------------

    private Component modeLabel() {
        return Component.literal("Mode: " + ConfigManager.get().mode.displayName());
    }

    private Component gradientLabel() {
        return Component.literal("Gradient: " + ConfigManager.get().gradientMode.displayName());
    }

    private Component curveLabel() {
        return Component.literal("Curve: " + ConfigManager.get().curve.displayName());
    }

    private Component sourceLabel() {
        return Component.literal("Source: " + ConfigManager.get().source.displayName());
    }

    private Component clearMarkersLabel() {
        int n = MarkerManager.startMarkers.size() + MarkerManager.endMarkers.size();
        return Component.literal("Clear Markers (" + n + ")");
    }

    private Component debugLabel() {
        return Component.literal("Debug logging: " + (ConfigManager.get().debug ? "On" : "Off"));
    }

    // ---- rendering ------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        renderTitleBar(g);
        if (tab == Tab.CONFIGURE) renderGradientTab(g, mouseX, mouseY);
        else renderToolTab(g, mouseX, mouseY);
    }

    private void renderTitleBar(GuiGraphicsExtractor g) {
        g.fill(0, 0, this.width, BAR_H, BAR_BG);
        g.fill(0, BAR_H, this.width, BAR_H + 1, BAR_LINE); // full-width underline
        int textY = (BAR_H - this.font.lineHeight) / 2 + 1;
        g.text(this.font, "Gradient", 8, textY, WHITE); // mod name, left-aligned (not a tab)
        int[] xs = tabXs();
        g.text(this.font, tabText(Tab.CONFIGURE), xs[0], textY, tab == Tab.CONFIGURE ? WHITE : GREY);
        g.text(this.font, tabText(Tab.SETTINGS), xs[2], textY, tab == Tab.SETTINGS ? WHITE : GREY);
    }

    private void renderGradientTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int cx0 = contentX();
        g.text(this.font, "Left = start   Right = end   Middle = exclude", cx0, 30, GREY);

        // Yellow help lines under the relevant controls (positions recorded in initGradientTab).
        int rx = rightX(), rw = rightW();
        g.text(this.font, this.font.plainSubstrByWidth(modeDescription(), rw), rx, modeDescY, YELLOW);
        g.text(this.font, this.font.plainSubstrByWidth("Max colour distance from the gradient", rw), rx, deviationDescY, YELLOW);
        g.text(this.font, this.font.plainSubstrByWidth("Chance to repeat or skip a step", rw), rx, chaosDescY, YELLOW);

        int x0 = cx0, w = leftW();
        for (int i = paletteScroll; i < displayRows.size(); i++) {
            int rowIndex = i - paletteScroll;
            int y = LIST_TOP + rowIndex * ROW_H;
            if (y + ROW_H > LIST_TOP + visibleRows() * ROW_H) break;
            DisplayRow row = displayRows.get(i);
            boolean hover = mouseX >= x0 && mouseX <= x0 + w && mouseY >= y && mouseY < y + ROW_H;
            if (hover) g.fill(x0, y, x0 + w, y + ROW_H, HOVER_BG);

            g.item(row.block().stack(), x0 + 1, y + 1);

            int color = switch (row.category()) {
                case USED -> WHITE;
                case NOTPICKED -> BLUE;
                case EXCLUDED -> RED;
            };
            // Exactly one [S] (first white row) and one [E] (last white row).
            String tag = (i == 0 && whiteCount > 0) ? " [S]"
                    : (i == whiteCount - 1 && whiteCount > 1) ? " [E]" : "";
            String name = row.block().stack().getHoverName().getString();
            String label = this.font.plainSubstrByWidth(name, w - 26 - this.font.width(tag)) + tag;
            g.text(this.font, label, x0 + 20, y + 5, color);
        }
    }

    private void renderToolTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        String sel = ConfigManager.get().selectedTool;
        String selName = sel.isEmpty() ? "(none)" : Gradient.toolDisplayName(sel);
        int cx0 = contentX();
        g.text(this.font, "Tool: " + selName, cx0, 32, WHITE);
        modeButton.setMessage(modeLabel());

        int x0 = cx0, w = leftW(), top = TOOL_LIST_TOP;
        for (int i = 0; i < matches.size(); i++) {
            int y = top + i * toolRowHeight;
            ToolRow row = matches.get(i);
            boolean hover = mouseX >= x0 && mouseX <= x0 + w && mouseY >= y && mouseY < y + toolRowHeight;
            boolean selected = row.id().equals(sel);
            if (hover || selected) g.fill(x0, y, x0 + w, y + toolRowHeight, hover ? HOVER_BG : 0x3355FF55);
            int color = selected ? 0xFF55FF55 : (hover ? WHITE : 0xFFC0C0C0);
            g.text(this.font, this.font.plainSubstrByWidth(row.name(), w - 8), x0 + 4, y + 2, color);
        }
    }

    // ---- slider widget --------------------------------------------------------------------------

    private static final class ConfigSlider extends AbstractSliderButton {
        private final java.util.function.DoubleConsumer onChange;
        private final java.util.function.DoubleFunction<String> labelFn;

        ConfigSlider(int x, int y, int w, double initial,
                     java.util.function.DoubleFunction<String> labelFn,
                     java.util.function.DoubleConsumer onChange) {
            super(x, y, w, 20, Component.empty(), initial);
            this.labelFn = labelFn;
            this.onChange = onChange;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(labelFn.apply(this.value)));
        }

        @Override
        protected void applyValue() {
            onChange.accept(this.value);
            ConfigManager.save();
        }
    }

    @Override
    public void onClose() {
        ConfigManager.save();
        super.onClose();
    }
}
