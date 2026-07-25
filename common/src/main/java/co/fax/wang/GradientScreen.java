package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import co.fax.wang.palette.MissingBlockPolicy;
import co.fax.wang.palette.Palette;
import co.fax.wang.palette.PaletteSegment;
import co.fax.wang.palette.PaletteStore;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
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
 * The FW Paint screen (opened by K). Five tabs:
 * <ul>
 *   <li><b>Solid</b> — block picker with ✓ (one block to place) / ✗ (exclusions) + match mode;</li>
 *   <li><b>Palette</b> — the saved palettes driving gradient AND noise painting: a list view
 *       (use/edit/delete) plus the editor ({@link PaletteEditScreen});</li>
 *   <li><b>Finder</b> — every block ranked by colour or brightness ({@link ColorIndex}), centred
 *       on a chosen reference block or pure colour, to discover close blocks you don't have;</li>
 *   <li><b>Settings</b> — pick the paint tool item, placement mode, marker options,
 *       helper-text position;</li>
 *   <li><b>Help</b> — the in-game manual (the drill-down {@link HelpPanel}).</li>
 * </ul>
 */
public class GradientScreen extends Screen {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFA0A0A0;
    private static final int GREEN = 0xFF55FF55;  // solid ✓ block
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

    private enum Tab { PALETTE, SOLID, FINDER, SETTINGS, HELP }

    /** Title-bar order, left to right (right-aligned as a group). */
    private static final Tab[] BAR_ORDER = {Tab.SOLID, Tab.PALETTE, Tab.FINDER, Tab.SETTINGS, Tab.HELP};
    private enum SolidAssign { NONE, TICK, CROSS }

    private static final int TOOL_BTN_Y = 30;
    private static final int TOOL_BTN_H = 20;
    private static final int TOOL_FILTER_Y = 54;

    // Solid-tab left column layout.
    private static final int PICK_SRC_Y = 30;
    private static final int PICK_BTN_Y = 54;
    private static final int PICK_LIST_Y = 78;
    private static final int LEGEND_H = 26; // two key lines under the list
    private static final int PRESSED_OVERLAY = 0x80000000; // darkens a vanilla button to show "on"

    private record SourceBlock(String id, ItemStack stack, Block block) {}
    private record ToolRow(String id, String name) {}

    private static Tab tab = Tab.SOLID; // set on open to the active paint type's tab

    // Solid tab state.
    private BlockPickerPanel picker;
    private List<SourceBlock> sourceBlocks = new ArrayList<>();
    private SolidAssign solidAssign = SolidAssign.NONE;
    private int solidMatchDescY;
    private int legendY; // y of the yellow button key under the block list

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

    // Palette tab state (selection/expansion static so they survive reopen + editor round-trips).
    private PaletteListPanel paletteList;
    private static String paletteSelectedId = "";
    private static String paletteExpandId = "";
    private String confirmDeleteId; // non-null → the delete confirmation modal is up
    private Button paletteUseBtn, paletteEditBtn, paletteDeleteBtn;

    // Settings tab state.
    private EditBox filterBox;
    private int autoEndDescY, fillVoidsDescY, memoryDescY;
    private final List<ToolRow> matches = new ArrayList<>();
    private String filter = "";
    private int toolRowHeight = 12;
    private boolean assigningTool = false;

    public GradientScreen() {
        this(true);
    }

    /** @param followPaintType false keeps the last tab (used when returning from the editor). */
    public GradientScreen(boolean followPaintType) {
        super(Component.literal("FW Paint"));
        if (ConfigManager.get().paintTool.isEmpty()) {
            tab = Tab.SETTINGS; // no tool assigned yet — land where the tool picker lives
        } else if (followPaintType) {
            // Open on the tab of whatever is being painted right now.
            tab = switch (ConfigManager.get().activePaintType) {
                case SOLID -> Tab.SOLID;
                case GRADIENT, NOISE -> Tab.PALETTE;
            };
        }
    }

    /** Open on the Palette tab with {@code id} selected (used when the editor saves). */
    public static GradientScreen openOnPalette(String id) {
        GradientScreen s = new GradientScreen(false);
        tab = Tab.PALETTE;
        paletteSelectedId = id;
        paletteExpandId = id;
        return s;
    }

    /** For the editor's title bar: open the screen on a specific {@link #BAR_ORDER} tab. */
    static GradientScreen atBarTab(int barIndex) {
        GradientScreen s = new GradientScreen(false);
        tab = BAR_ORDER[Math.max(0, Math.min(BAR_ORDER.length - 1, barIndex))];
        return s;
    }

    /** The title-bar tab names, in bar order (shared with the editor so the bars match). */
    static String[] barTabNames() {
        String[] out = new String[BAR_ORDER.length];
        for (int i = 0; i < BAR_ORDER.length; i++) {
            out[i] = switch (BAR_ORDER[i]) {
                case PALETTE -> "Palette"; case SOLID -> "Solid"; case FINDER -> "Finder";
                case SETTINGS -> "Settings"; case HELP -> "Help";
            };
        }
        return out;
    }

    /** Index of the Palette tab in {@link #BAR_ORDER} (the editor highlights it). */
    static int paletteBarIndex() {
        for (int i = 0; i < BAR_ORDER.length; i++) {
            if (BAR_ORDER[i] == Tab.PALETTE) return i;
        }
        return 0;
    }

    // ---- layout helpers -------------------------------------------------------------------------

    private int colW() {
        int avail = this.width - 2 * LEFT_X;
        return Math.max(60, Math.min(COL_W_MAX, (avail - COL_GAP) / 2));
    }
    private int contentX() {
        int total = 2 * colW() + COL_GAP;
        return Math.max(LEFT_X, (this.width - total) / 2);
    }
    private int rightX() { return contentX() + colW() + COL_GAP; }
    private int rightW() { return colW(); }
    private int leftW() { return colW(); }

    @Override
    protected void init() {
        toolRowHeight = this.font.lineHeight + 3;
        if (tab == Tab.PALETTE) initPaletteTab();
        else if (tab == Tab.SOLID) initSolidTab();
        else if (tab == Tab.FINDER) initFinderTab();
        else if (tab == Tab.HELP) initHelpTab();
        else initSettingsTab();

        // Tool tabs get "Use" buttons next to Done: make a paint type the active one (same as
        // cycling with the paint-type keybind). The Palette tab serves both gradient and noise.
        if (tab == Tab.SOLID) {
            addRenderableWidget(Button.builder(useLabel(PaintType.SOLID), b -> {
                ConfigManager.get().activePaintType = PaintType.SOLID;
                ConfigManager.save();
                b.setMessage(useLabel(PaintType.SOLID));
            }).bounds(this.width / 2 - 102, this.height - 26, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                    .bounds(this.width / 2 + 2, this.height - 26, 100, 20).build());
        } else if (tab == Tab.PALETTE) {
            addRenderableWidget(Button.builder(useLabel(PaintType.GRADIENT), b -> {
                ConfigManager.get().activePaintType = PaintType.GRADIENT;
                ConfigManager.save();
                rebuildWidgets();
            }).bounds(this.width / 2 - 154, this.height - 26, 100, 20).build());
            addRenderableWidget(Button.builder(useLabel(PaintType.NOISE), b -> {
                ConfigManager.get().activePaintType = PaintType.NOISE;
                ConfigManager.save();
                rebuildWidgets();
            }).bounds(this.width / 2 - 50, this.height - 26, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                    .bounds(this.width / 2 + 54, this.height - 26, 100, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                    .bounds(this.width / 2 - 50, this.height - 26, 100, 20).build());
        }
    }

    private Component useLabel(PaintType t) {
        return Component.literal(ConfigManager.get().activePaintType == t
                ? "In use ✓" : "Use " + t.label());
    }

    private void setTab(Tab t) { tab = t; rebuildWidgets(); }

    // ---- tabs in the title bar ------------------------------------------------------------------

    private String tabName(Tab t) {
        return switch (t) {
            case PALETTE -> "Palette"; case FINDER -> "Finder"; case SOLID -> "Solid";
            case SETTINGS -> "Settings"; case HELP -> "Help";
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

    // ---- Palette tab (list view) ----------------------------------------------------------------

    private void initPaletteTab() {
        int cx = contentX(), w = 2 * colW() + COL_GAP;
        int bw = (w - 3 * 4) / 4;
        addRenderableWidget(Button.builder(Component.literal("+ New"),
                b -> openEditor(null)).bounds(cx, 30, bw, 20).build());
        paletteUseBtn = addRenderableWidget(Button.builder(Component.literal("Use"), b -> {
            String id = paletteList.selectedId();
            if (!id.isEmpty()) {
                PaletteStore.setActive(id);
                paletteList.setActiveId(id);
                paletteList.expand(id); // using a palette auto-expands its summary
                paletteSelectedId = id;
                paletteExpandId = id;
            }
        }).bounds(cx + bw + 4, 30, bw, 20).build());
        paletteEditBtn = addRenderableWidget(Button.builder(Component.literal("Edit"), b -> {
            Palette p = PaletteStore.byId(paletteList.selectedId());
            if (p != null) openEditor(p);
        }).bounds(cx + 2 * (bw + 4), 30, bw, 20).build());
        paletteDeleteBtn = addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
            if (!paletteList.selectedId().isEmpty()) confirmDeleteId = paletteList.selectedId();
        }).bounds(cx + 3 * (bw + 4), 30, w - 3 * (bw + 4), 20).build());

        paletteList = new PaletteListPanel(this.font);
        paletteList.setBounds(cx, 56, w, this.height - 56 - 34);
        paletteList.setActiveId(PaletteStore.activeId());
        paletteList.select(paletteSelectedId);
        if (!paletteExpandId.isEmpty()) paletteList.expand(paletteExpandId);
        rebuildPaletteEntries();
    }

    private void openEditor(Palette palette) {
        if (this.minecraft != null) this.minecraft.setScreenAndShow(new PaletteEditScreen(palette));
    }

    private void rebuildPaletteEntries() {
        List<PaletteListPanel.Entry> entries = new ArrayList<>();
        for (Palette p : PaletteStore.all()) {
            List<ItemStack> sprites = new ArrayList<>();
            List<Boolean> auto = new ArrayList<>();
            for (PaletteSegment s : p.segments) {
                auto.add(s.isAutomatic());
                sprites.add(stackOfId(s.block));
            }
            List<String> missing = p.missingBlocks(availableIds(p.source));
            List<ItemStack> mStacks = new ArrayList<>();
            List<String> mNames = new ArrayList<>();
            for (String id : missing) {
                ItemStack st = stackOfId(id);
                mStacks.add(st);
                mNames.add(st.isEmpty() ? id : st.getHoverName().getString());
            }
            entries.add(new PaletteListPanel.Entry(p, sprites, auto, mStacks, mNames));
        }
        paletteList.setEntries(entries);
    }

    static ItemStack stackOfId(String id) {
        if (id == null || id.isEmpty()) return ItemStack.EMPTY;
        Identifier ident = Identifier.tryParse(id);
        if (ident == null) return ItemStack.EMPTY;
        return BuiltInRegistries.ITEM.getOptional(ident).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    /** Block-item ids available from a source range of the player's inventory. */
    private Set<String> availableIds(GradientSource src) {
        Set<String> out = new HashSet<>();
        if (this.minecraft == null || this.minecraft.player == null) return out;
        var items = this.minecraft.player.getInventory().getNonEquipmentItems();
        int from = src == GradientSource.INVENTORY ? 9 : 0;
        int to = Math.min(src == GradientSource.HOTBAR ? 9 : 36, items.size());
        for (int slot = from; slot < to; slot++) {
            ItemStack st = items.get(slot);
            if (st.getItem() instanceof BlockItem bi && !bi.getBlock().defaultBlockState().isAir()) {
                Identifier id = BuiltInRegistries.ITEM.getKey(st.getItem());
                if (id != null) out.add(id.toString());
            }
        }
        return out;
    }

    private void renderPaletteTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        boolean hasSelection = paletteList != null && !paletteList.selectedId().isEmpty()
                && PaletteStore.byId(paletteList.selectedId()) != null;
        if (paletteUseBtn != null) paletteUseBtn.active = hasSelection;
        if (paletteEditBtn != null) paletteEditBtn.active = hasSelection;
        if (paletteDeleteBtn != null) paletteDeleteBtn.active = hasSelection;
        if (paletteList != null) paletteList.render(g, mouseX, mouseY);
        if (PaletteStore.all().isEmpty()) {
            g.text(this.font, "No palettes yet — press + New to create one",
                    contentX() + 4, 64, YELLOW);
        }
        if (confirmDeleteId != null) renderDeleteConfirm(g, mouseX, mouseY);
    }

    // Delete confirmation modal: {x, y, w, h} of the dialog; buttons live on its bottom row.
    private int[] confirmBox() {
        int w = 240, h = 64;
        return new int[]{(this.width - w) / 2, (this.height - h) / 2, w, h};
    }

    private int[] confirmBtn(boolean delete) {
        int[] b = confirmBox();
        int bw = (b[2] - 3 * 8) / 2;
        int x = delete ? b[0] + 8 : b[0] + 2 * 8 + bw;
        return new int[]{x, b[1] + b[3] - 26, bw, 18};
    }

    private static boolean inRect(int[] r, double mx, double my) {
        return mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    private void renderDeleteConfirm(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Palette p = PaletteStore.byId(confirmDeleteId);
        String name = p == null ? "?" : p.name;
        g.nextStratum();
        g.fill(0, 0, this.width, this.height, 0xB0000000);
        int[] b = confirmBox();
        g.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], 0xF0202020);
        g.fill(b[0], b[1], b[0] + b[2], b[1] + 1, 0xFFFFFFFF);
        g.fill(b[0], b[1] + b[3] - 1, b[0] + b[2], b[1] + b[3], 0xFFFFFFFF);
        g.fill(b[0], b[1], b[0] + 1, b[1] + b[3], 0xFFFFFFFF);
        g.fill(b[0] + b[2] - 1, b[1], b[0] + b[2], b[1] + b[3], 0xFFFFFFFF);
        String msg = this.font.plainSubstrByWidth("Delete '" + name + "'?", b[2] - 16);
        g.text(this.font, msg, b[0] + (b[2] - this.font.width(msg)) / 2, b[1] + 10, WHITE);
        for (boolean del : new boolean[]{true, false}) {
            int[] r = confirmBtn(del);
            boolean hover = inRect(r, mouseX, mouseY);
            g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], hover ? 0x60FFFFFF : 0x30FFFFFF);
            String label = del ? "Delete" : "Cancel";
            g.text(this.font, label, r[0] + (r[2] - this.font.width(label)) / 2, r[1] + 5,
                    del ? RED : WHITE);
        }
    }

    /** Modal click handling; swallows everything while the confirmation is up. */
    private boolean handleDeleteConfirmClick(double mx, double my, int button) {
        if (button == 0 && inRect(confirmBtn(true), mx, my)) {
            PaletteStore.delete(confirmDeleteId);
            paletteList.setActiveId(PaletteStore.activeId());
            if (paletteList.selectedId().equals(confirmDeleteId)) {
                paletteList.select("");
                paletteSelectedId = "";
            }
            rebuildPaletteEntries();
        }
        confirmDeleteId = null; // Delete, Cancel, and click-away all dismiss
        return true;
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
        y += 24;
        // What happens when a palette's blocks aren't all in the inventory at paint time.
        cycleButton(rx, y, rw,
                () -> Component.literal("Missing blocks: " + ConfigManager.get().missingBlockPolicy.label()),
                () -> {
                    GradientConfig c = ConfigManager.get();
                    c.missingBlockPolicy = c.missingBlockPolicy == MissingBlockPolicy.DONT_PAINT
                            ? MissingBlockPolicy.SKIP_MISSING
                            : MissingBlockPolicy.DONT_PAINT;
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
        if (tab == Tab.PALETTE && confirmDeleteId != null) {
            return handleDeleteConfirmClick(event.x(), event.y(), event.button());
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
        if (tab == Tab.HELP && event.button() == 0 && help != null
                && help.mouseClicked(event.x(), event.y())) {
            return true;
        }
        if (tab == Tab.SOLID && picker != null && event.button() >= 0 && event.button() <= 2) {
            String id = picker.rowIdAt(event.x(), event.y());
            if (id == null) return false;
            boolean dbl = event.button() == 0 && (doubled || isRowDoubleClick(id));
            return handleSolidRowClick(id, event.button(), dbl);
        }
        if (tab == Tab.PALETTE && event.button() == 0 && paletteList != null
                && paletteList.click(event.x(), event.y())) {
            paletteSelectedId = paletteList.selectedId();
            paletteExpandId = paletteList.expandedId();
            return true;
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
        if (tab == Tab.PALETTE && confirmDeleteId != null) return true;
        if (tab == Tab.PALETTE && paletteList != null
                && paletteList.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        if (tab == Tab.SOLID && picker != null && picker.mouseScrolled(mouseX, mouseY, scrollY)) {
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

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
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
    public boolean keyPressed(KeyEvent event) {
        if (confirmDeleteId != null && event.key() == 256) { // Esc dismisses the delete confirm
            confirmDeleteId = null;
            return true;
        }
        return super.keyPressed(event);
    }

    // ---- labels ---------------------------------------------------------------------------------

    private Component sourceLabel() { return Component.literal("Source: " + ConfigManager.get().source.displayName()); }
    private Component clearMarkersLabel() {
        return Component.literal("Clear Markers (" + (MarkerManager.startMarkers.size() + MarkerManager.endMarkers.size()) + ")");
    }

    private void cycleButton(int x, int y, int w, java.util.function.Supplier<Component> label, Runnable onCycle) {
        addRenderableWidget(Button.builder(label.get(), b -> {
            onCycle.run(); ConfigManager.save(); b.setMessage(label.get());
        }).bounds(x, y, w, 20).build());
    }

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
        if (tab == Tab.PALETTE) renderPaletteTab(g, mouseX, mouseY);
        else if (tab == Tab.SOLID) renderSolidTab(g, mouseX, mouseY);
        else if (tab == Tab.FINDER) renderFinderTab(g, mouseX, mouseY);
        else if (tab == Tab.HELP) { if (help != null) help.render(g, mouseX, mouseY); }
        else renderSettingsTab(g, mouseX, mouseY);
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

    /** Yellow key under the block list explaining the Solid picker buttons. */
    private void renderPickerLegend(GuiGraphicsExtractor g) {
        int cx = contentX(), w = leftW();
        String[] lines = {"✓ block to place · ✗ exclude", "Dbl-click: ✓ · middle-click: ✗"};
        for (int i = 0; i < lines.length; i++) {
            g.text(this.font, this.font.plainSubstrByWidth(lines[i], w), cx, legendY + i * 11, YELLOW);
        }
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
