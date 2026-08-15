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
    private static final int LIGHT = 0xFFE0E0E0;  // interaction hints
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

    private enum Tab { PAINT, PALETTE, SOLID, FINDER, SETTINGS, HELP }

    /** Title-bar order, left to right (right-aligned as a group). */
    private static final Tab[] BAR_ORDER =
            {Tab.PAINT, Tab.SOLID, Tab.PALETTE, Tab.FINDER, Tab.SETTINGS, Tab.HELP};

    private static final int TOOL_BTN_Y = 30;
    private static final int TOOL_BTN_H = 20;
    private static final int TOOL_FILTER_Y = 54;

    // Solid-tab layout.
    private static final int PICK_SRC_Y = 30;
    private static final int SOLID_LIST_Y = 54;
    private static final int SOLID_PREVIEW_H = 80;   // big selected-block preview above the settings
    private static final int SOLID_ROW_H = 18;
    private static final int PRESSED_OVERLAY = 0x80000000; // darkens a vanilla button to show "on"

    private record SourceBlock(String id, ItemStack stack, Block block) {}
    private record ToolRow(String id, String name) {}

    private static Tab tab = Tab.SOLID; // set on open to the active paint type's tab

    // Solid tab state: left-click toggles the selection, right-click toggles exclusion.
    private record SolidRow(SourceBlock sb, int color, String tag, boolean strike) {}
    private List<SourceBlock> sourceBlocks = new ArrayList<>();
    private final List<SolidRow> solidRows = new ArrayList<>();
    private int solidScroll;
    private int solidListH;

    // Circled-? help spots (Solid + Settings tabs): hover a control (or click its icon) for a
    // popup describing what it's CURRENTLY doing; moving away dismisses it.
    private record HelpSpot(int x, int y, int w, int h, java.util.function.Supplier<String> text) {}
    private final List<HelpSpot> helpSpots = new ArrayList<>();
    private HelpSpot hoverSpot, clickedSpot;
    private long hoverSince;

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
            tab = Tab.PAINT; // the hotkey always lands on the quick controls
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
                case PAINT -> "Paint"; case PALETTE -> "Palette"; case SOLID -> "Solid";
                case FINDER -> "Finder"; case SETTINGS -> "Settings"; case HELP -> "Help";
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
        helpSpots.clear();
        if (tab == Tab.PAINT) initPaintTab();
        else if (tab == Tab.PALETTE) initPaletteTab();
        else if (tab == Tab.SOLID) initSolidTab();
        else if (tab == Tab.FINDER) initFinderTab();
        else if (tab == Tab.HELP) initHelpTab();
        else initSettingsTab();

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(this.width / 2 - 50, this.height - 26, 100, 20).build());
    }

    private void setTab(Tab t) { tab = t; rebuildWidgets(); }

    // ---- tabs in the title bar ------------------------------------------------------------------

    private String tabName(Tab t) {
        return switch (t) {
            case PAINT -> "Paint"; case PALETTE -> "Palette"; case FINDER -> "Finder";
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

    // ---- Paint tab (quick controls — the hotkey's landing page) ---------------------------------

    private Button paletteCycleBtn;

    /**
     * A single centred column mirroring the HUD: cycle the paint type, cycle the placement mode,
     * cycle the active palette (which shows the HUD's condensed sprites + name — or, while Solid
     * is the paint type, the solid selection state, not cyclable).
     */
    private void initPaintTab() {
        int w = 220;
        int x = (this.width - w) / 2;
        int y = 56;
        cycleButton(x, y, w, () -> Component.literal("Paint: "
                        + ConfigManager.get().activePaintType.label()),
                () -> {
                    GradientConfig c = ConfigManager.get();
                    c.activePaintType = c.activePaintType.next();
                });
        y += 24;
        cycleButton(x, y, w, () -> Component.literal("Placement: "
                        + ConfigManager.get().placementMode.shortName()),
                () -> {
                    GradientConfig c = ConfigManager.get();
                    c.placementMode = c.placementMode.next();
                });
        y += 24;
        // The palette row: content is drawn over the button each frame (renderPaintTab). It
        // cycles the kind the current paint type consumes (patterns in Pattern paint).
        paletteCycleBtn = addRenderableWidget(Button.builder(Component.empty(), b -> {
            co.fax.wang.palette.PaletteKind kind =
                    Gradient.kindFor(ConfigManager.get().activePaintType);
            if (kind == null) return;
            PaletteStore.cycleActive(1, kind);
            paletteList = null; // stale-proof: the Palette tab rebuilds on next visit anyway
        }).bounds(x, y, w, 20).build());
    }

    private void renderPaintTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        GradientConfig cfg = ConfigManager.get();
        String title = "Quick controls — what the tool does right now";
        g.text(this.font, title, (this.width - this.font.width(title)) / 2, 38, LIGHT);
        if (paletteCycleBtn == null) return;
        boolean solid = cfg.activePaintType == PaintType.SOLID;
        co.fax.wang.palette.PaletteKind kind = Gradient.kindFor(cfg.activePaintType);
        paletteCycleBtn.active = !solid && kind != null && !PaletteStore.allOf(kind).isEmpty();

        int bx = paletteCycleBtn.getX(), by = paletteCycleBtn.getY();
        int bw = paletteCycleBtn.getWidth(), bh = paletteCycleBtn.getHeight();
        g.enableScissor(bx + 2, by + 2, bx + bw - 2, by + bh - 2);
        if (solid) {
            // Mirrors the HUD: solid shows its selection / match state instead of a palette.
            if (cfg.solidMatch == SolidMatch.SELECTED) {
                ItemStack st = stackOfId(cfg.solidBlock);
                String name = st.isEmpty() ? "Solid: no block picked"
                        : st.getHoverName().getString();
                int tw = (st.isEmpty() ? 0 : 20) + this.font.width(name);
                int sx = bx + (bw - tw) / 2;
                if (!st.isEmpty()) {
                    g.item(st, sx, by + 2);
                    sx += 20;
                }
                g.text(this.font, name, sx, by + 6, WHITE);
            } else {
                String name = "Match: " + cfg.solidMatch.displayName();
                g.text(this.font, name, bx + (bw - this.font.width(name)) / 2, by + 6, WHITE);
            }
        } else if (cfg.activePaintType == PaintType.PATTERN) {
            Palette pat = PaletteStore.activePattern();
            if (pat == null) {
                String s = "No patterns — create one on the Palette tab";
                g.text(this.font, this.font.plainSubstrByWidth(s, bw - 8), bx + 4, by + 6, GREY);
            } else {
                String label = pat.name + " (" + pat.width + "×" + pat.height + ")";
                int tw = 20 + this.font.width(label);
                int sx = bx + (bw - tw) / 2;
                PatternThumb.draw(g, pat, sx, by + 2, 16);
                g.text(this.font, label, sx + 20, by + 6, WHITE);
            }
        } else {
            Palette active = PaletteStore.active();
            if (active == null) {
                String s = "No palettes — create one on the Palette tab";
                g.text(this.font, this.font.plainSubstrByWidth(s, bw - 8),
                        bx + 4, by + 6, GREY);
            } else {
                int n = active.segments.size();
                int spacing = 6;
                int tw = (n == 0 ? 0 : (n - 1) * spacing + 16 + 8) + this.font.width(active.name);
                int sx = bx + (bw - tw) / 2;
                int[] tints = PaletteTints.forPalette(active);
                for (int i = 0; i < n; i++) {
                    PaletteSegment seg = active.segments.get(i);
                    if (seg.isAutomatic()) {
                        PaletteListPanel.drawCrosshatch(g, sx, by + 2, 16, tints[i]);
                    } else {
                        ItemStack st = stackOfId(seg.block);
                        if (st.isEmpty()) PaletteListPanel.drawCrosshatch(g, sx, by + 2, 16);
                        else g.item(st, sx, by + 2);
                    }
                    sx += spacing;
                }
                if (n > 0) sx += 16 - spacing + 8;
                g.text(this.font, active.name, sx, by + 6, WHITE);
            }
        }
        g.disableScissor();
    }

    // ---- Palette tab (list view) ----------------------------------------------------------------

    private void initPaletteTab() {
        int cx = contentX(), w = 2 * colW() + COL_GAP;
        int bw = (w - 4 * 4) / 5;
        addRenderableWidget(Button.builder(Component.literal("+ Gradient"),
                b -> openEditor(null)).bounds(cx, 30, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+ Pattern"), b -> {
            if (this.minecraft != null) this.minecraft.setScreenAndShow(new PatternEditScreen(null));
        }).bounds(cx + bw + 4, 30, bw, 20).build());
        paletteUseBtn = addRenderableWidget(Button.builder(Component.literal("Use"), b -> {
            String id = paletteList.selectedId();
            if (!id.isEmpty()) {
                PaletteStore.setActive(id); // routes to the item's kind's active pointer
                paletteList.setActiveId(PaletteStore.activeId());
                Palette nowActive = PaletteStore.activePattern();
                paletteList.setActivePatternId(nowActive == null ? "" : nowActive.id);
                paletteList.expand(id); // using a palette auto-expands its summary
                paletteSelectedId = id;
                paletteExpandId = id;
            }
        }).bounds(cx + 2 * (bw + 4), 30, bw, 20).build());
        paletteEditBtn = addRenderableWidget(Button.builder(Component.literal("Edit"), b -> {
            Palette p = PaletteStore.byId(paletteList.selectedId());
            if (p == null) return;
            if (p.kind == co.fax.wang.palette.PaletteKind.PATTERN) {
                if (this.minecraft != null) this.minecraft.setScreenAndShow(new PatternEditScreen(p));
            } else {
                openEditor(p);
            }
        }).bounds(cx + 3 * (bw + 4), 30, bw, 20).build());
        paletteDeleteBtn = addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
            if (!paletteList.selectedId().isEmpty()) confirmDeleteId = paletteList.selectedId();
        }).bounds(cx + 4 * (bw + 4), 30, w - 4 * (bw + 4), 20).build());

        paletteList = new PaletteListPanel(this.font);
        paletteList.setBounds(cx, 56, w, this.height - 56 - 34);
        paletteList.setActiveId(PaletteStore.activeId());
        Palette ap = PaletteStore.activePattern();
        paletteList.setActivePatternId(ap == null ? "" : ap.id);
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
            List<Integer> tints = new ArrayList<>();
            int[] segTints = PaletteTints.forPalette(p);
            for (int i = 0; i < p.segments.size(); i++) {
                PaletteSegment s = p.segments.get(i);
                auto.add(s.isAutomatic());
                sprites.add(stackOfId(s.block));
                tints.add(segTints[i]);
            }
            List<String> missing = p.missingBlocks(availableIds(p.source));
            List<ItemStack> mStacks = new ArrayList<>();
            List<String> mNames = new ArrayList<>();
            for (String id : missing) {
                ItemStack st = stackOfId(id);
                mStacks.add(st);
                mNames.add(st.isEmpty() ? id : st.getHoverName().getString());
            }
            entries.add(new PaletteListPanel.Entry(p, sprites, auto, tints, mStacks, mNames));
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
        int cx = contentX(), w = leftW();

        addRenderableWidget(Button.builder(sourceLabel(), b -> {
            GradientConfig c = ConfigManager.get(); c.source = c.source.next(); ConfigManager.save();
            sourceBlocks = gatherSourceBlocks(); rebuildSolidRows(); b.setMessage(sourceLabel());
        }).bounds(cx, PICK_SRC_Y, w, 20).build());
        helpSpots.add(new HelpSpot(cx, PICK_SRC_Y, w, 20,
                () -> "Candidate blocks come from " + ConfigManager.get().source.displayName()));

        int availH = (this.height - 34) - SOLID_LIST_Y - 26; // room for the grey hint lines
        solidListH = Math.max(SOLID_ROW_H, Math.min(SOLID_ROW_H * 11, availH));
        rebuildSolidRows();

        // Right column: the big selected-block preview renders above; Match button under it.
        int rx = rightX(), rw = rightW();
        int matchY = 30 + SOLID_PREVIEW_H + 8;
        cycleButton(rx, matchY, rw - 14, this::matchLabel, () -> {
            GradientConfig c = ConfigManager.get(); c.solidMatch = c.solidMatch.next();
            rebuildSolidRows(); // the selected row gains/loses its strikethrough with the mode
        });
        helpSpots.add(new HelpSpot(rx, matchY, rw - 14, 20, this::matchDescription));
    }

    /** Rows: the selected block green (struck through when the mode ignores it), excluded red at
     *  the bottom, the rest white. */
    private void rebuildSolidRows() {
        solidRows.clear();
        GradientConfig cfg = ConfigManager.get();
        Set<String> excluded = new HashSet<>(cfg.solidExcludedBlocks);
        boolean selectionActive = cfg.solidMatch == SolidMatch.SELECTED;
        for (SourceBlock sb : sourceBlocks) {
            if (excluded.contains(sb.id())) continue;
            boolean sel = sb.id().equals(cfg.solidBlock);
            solidRows.add(new SolidRow(sb, sel ? GREEN : WHITE, sel ? " ✓" : "", sel && !selectionActive));
        }
        for (SourceBlock sb : sourceBlocks) {
            if (excluded.contains(sb.id())) solidRows.add(new SolidRow(sb, RED, "", false));
        }
        solidScroll = Math.max(0, Math.min(solidScroll, maxSolidScroll()));
    }

    private int maxSolidScroll() {
        return Math.max(0, solidRows.size() - solidListH / SOLID_ROW_H);
    }

    /** Row id under the mouse in the solid list, or null. */
    private String solidRowIdAt(double mx, double my) {
        int cx = contentX(), w = leftW();
        if (mx < cx || mx > cx + w || my < SOLID_LIST_Y || my >= SOLID_LIST_Y + solidListH) return null;
        int idx = solidScroll + (int) ((my - SOLID_LIST_Y) / SOLID_ROW_H);
        return (idx >= 0 && idx < solidRows.size()) ? solidRows.get(idx).sb().id() : null;
    }

    /** Left-click toggles the selection; right-click toggles exclusion (closest-match modes). */
    private boolean handleSolidRowClick(String id, int button) {
        GradientConfig cfg = ConfigManager.get();
        if (button == 0) {
            cfg.solidExcludedBlocks.remove(id); // selecting an excluded block un-excludes it
            cfg.solidBlock = id.equals(cfg.solidBlock) ? "" : id;
        } else if (button == 1) {
            if (!cfg.solidExcludedBlocks.remove(id)) {
                cfg.solidExcludedBlocks.add(id);
                if (id.equals(cfg.solidBlock)) cfg.solidBlock = "";
            }
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
            case SELECTED -> "Places the selected block from the list";
            case EXACT -> "Places exactly the block you click";
            case CLOSEST_COLOR -> "Places your closest colour match to the clicked block";
            case CLOSEST_BRIGHTNESS -> "Places your closest brightness match to the clicked block";
        };
    }

    private void renderSolidTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int cx = contentX(), w = leftW();
        int ly = SOLID_LIST_Y, lh = solidListH;

        // Editor-style list box: dark translucent background, white outline.
        g.fill(cx, ly, cx + w, ly + lh, 0x90000000);
        UiIcons.outline(g, cx, ly, w, lh, 0xA0FFFFFF);
        g.enableScissor(cx, ly, cx + w, ly + lh);
        for (int i = solidScroll; i < solidRows.size(); i++) {
            int ry = ly + (i - solidScroll) * SOLID_ROW_H;
            if (ry >= ly + lh) break;
            SolidRow row = solidRows.get(i);
            boolean hover = mouseX >= cx && mouseX <= cx + w && mouseY >= ry && mouseY < ry + SOLID_ROW_H;
            if (hover) g.fill(cx + 1, ry, cx + w - 1, ry + SOLID_ROW_H, HOVER_BG);
            g.item(row.sb().stack(), cx + 2, ry + 1);
            String name = (row.strike() ? "§m" : "") + row.sb().stack().getHoverName().getString();
            String label = this.font.plainSubstrByWidth(name, w - 26 - this.font.width(row.tag())) + row.tag();
            g.text(this.font, label, cx + 21, ry + 5, row.color());
        }
        g.disableScissor();
        if (maxSolidScroll() > 0) {
            int trackX = cx + w - 3;
            int visible = lh / SOLID_ROW_H;
            int thumbH = Math.max(8, lh * visible / solidRows.size());
            int thumbY = ly + (int) ((lh - thumbH) * (double) solidScroll / maxSolidScroll());
            g.fill(trackX, ly + 1, trackX + 2, ly + lh - 1, 0x30FFFFFF);
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0x90FFFFFF);
        }
        int hintY = ly + lh + 4;
        g.text(this.font, this.font.plainSubstrByWidth("Left-click: select · again to clear", w), cx, hintY, LIGHT);
        g.text(this.font, this.font.plainSubstrByWidth("Right-click: exclude from closest match", w), cx, hintY + 11, LIGHT);

        renderSolidPreview(g);
    }

    /** The big selected-block preview above the Match settings; a large ? when the mode picks
     *  the block at click time (or nothing is selected yet). */
    private void renderSolidPreview(GuiGraphicsExtractor g) {
        GradientConfig cfg = ConfigManager.get();
        int rx = rightX(), rw = rightW();
        int py = 30;
        ItemStack st = stackOfId(cfg.solidBlock);
        int scale = 4; // 64px sprite
        int bx = rx + (rw - 16 * scale) / 2;
        if (!st.isEmpty()) {
            g.pose().pushMatrix();
            g.pose().translate(bx, py);
            g.pose().scale(scale, scale);
            g.item(st, 0, 0);
            g.pose().popMatrix();
        }
        boolean unknown = cfg.solidMatch != SolidMatch.SELECTED;
        if (unknown || st.isEmpty()) {
            String q = "?";
            g.pose().pushMatrix();
            g.pose().translate(rx + rw / 2f - 2.5f * this.font.width(q),
                    py + 32 - 2.5f * this.font.lineHeight);
            g.pose().scale(5f, 5f);
            g.text(this.font, q, 0, 0, WHITE);
            g.pose().popMatrix();
        }
        if (!unknown) {
            String caption = st.isEmpty() ? "Click a block to select"
                    : st.getHoverName().getString();
            caption = this.font.plainSubstrByWidth(caption, rw);
            g.text(this.font, caption, rx + (rw - this.font.width(caption)) / 2, py + 66,
                    st.isEmpty() ? GREY : WHITE);
        }
    }

    // ---- circled-? help system (Solid + Settings) -----------------------------------------------

    private void renderHelpSystem(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        for (HelpSpot h : helpSpots) {
            UiIcons.drawHelpIcon(g, h.x() + h.w() + 3, h.y() + 5);
        }
        HelpSpot over = null;
        for (HelpSpot h : helpSpots) {
            if (mouseX >= h.x() && mouseX <= h.x() + h.w() + 13
                    && mouseY >= h.y() && mouseY <= h.y() + h.h()) {
                over = h;
                break;
            }
        }
        if (over != hoverSpot) {
            hoverSpot = over;
            hoverSince = System.currentTimeMillis();
        }
        if (clickedSpot != null && over != clickedSpot) clickedSpot = null;
        if (over == null) return;
        if (over != clickedSpot && System.currentTimeMillis() - hoverSince < 1000) return;
        List<String> lines = wrapText(over.text().get(), 150);
        int w = 0;
        for (String l : lines) w = Math.max(w, this.font.width(l));
        int h = lines.size() * 10 + 8;
        int x = Math.min(mouseX + 10, this.width - w - 14);
        int y = Math.min(mouseY + 8, this.height - h - 4);
        g.nextStratum();
        g.fill(x, y, x + w + 8, y + h, 0xF0101010);
        UiIcons.outline(g, x, y, w + 8, h, 0x80FFE34D);
        for (int i = 0; i < lines.size(); i++) {
            g.text(this.font, lines.get(i), x + 4, y + 4 + i * 10, YELLOW);
        }
    }

    private boolean handleHelpIconClick(double mx, double my) {
        for (HelpSpot h : helpSpots) {
            if (mx >= h.x() + h.w() + 2 && mx <= h.x() + h.w() + 13
                    && my >= h.y() + 4 && my <= h.y() + 15) {
                clickedSpot = h;
                return true;
            }
        }
        return false;
    }

    private List<String> wrapText(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (this.font.width(candidate) > width && !line.isEmpty()) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    // ---- Settings tab ---------------------------------------------------------------------------

    private void initSettingsTab() {
        int rx = rightX(), rw = rightW();
        int cw = rw - 14; // controls with a help icon leave room for it
        GradientConfig cfg = ConfigManager.get();
        int y = 30;

        // One global placement mode (Marker/Single/Face/3D Fill/Disabled), shared by every paint type.
        cycleButton(rx, y, cw, () -> Component.literal("Placement: " + ConfigManager.get().placementMode.shortName()),
                () -> { GradientConfig c = ConfigManager.get(); c.placementMode = c.placementMode.next(); });
        helpSpots.add(new HelpSpot(rx, y, cw, 20,
                () -> "Where blocks go: " + ConfigManager.get().placementMode.displayName()));
        y += 24;

        addRenderableWidget(new ConfigSlider(rx, y, cw, distToSlider(cfg.maxMarkerDistance),
                v -> "Marker dist: " + sliderToDist(v),
                v -> ConfigManager.get().maxMarkerDistance = sliderToDist(v)));
        helpSpots.add(new HelpSpot(rx, y, cw, 20,
                () -> "Max distance between a start marker and its end marker"));
        y += 24;
        cycleButton(rx, y, cw, () -> Component.literal("Auto end marker: " + (ConfigManager.get().autoPlaceEnd ? "On" : "Off")),
                () -> ConfigManager.get().autoPlaceEnd = !ConfigManager.get().autoPlaceEnd);
        helpSpots.add(new HelpSpot(rx, y, cw, 20, () -> ConfigManager.get().autoPlaceEnd
                ? "On: each start marker scans out from its face and drops the end on the first block"
                : "Off: place end markers yourself"));
        y += 24;

        cycleButton(rx, y, cw, () -> Component.literal("Fill voids first: " + (ConfigManager.get().faceFillVoids ? "On" : "Off")),
                () -> ConfigManager.get().faceFillVoids = !ConfigManager.get().faceFillVoids);
        helpSpots.add(new HelpSpot(rx, y, cw, 20, () -> ConfigManager.get().faceFillVoids
                ? "On: in-marker face fills level the lowest columns first, then stack together"
                : "Off: every column advances at once"));
        y += 24;

        addRenderableWidget(new ConfigSlider(rx, y, cw, secsToSlider(cfg.gradientCacheSeconds),
                v -> "Paint memory: " + secsLabel(sliderToSecs(v)),
                v -> ConfigManager.get().gradientCacheSeconds = sliderToSecs(v)));
        helpSpots.add(new HelpSpot(rx, y, cw, 20,
                () -> "Idle gap before free-hand paint (gradients, 3D fills, patterns) forgets "
                        + "its progress"));
        y += 24;

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
        cycleButton(rx, y, cw,
                () -> Component.literal("Color match: " + (ConfigManager.get().perceptualColor ? "Perceptual" : "Classic")),
                () -> {
                    GradientConfig c = ConfigManager.get();
                    c.perceptualColor = !c.perceptualColor;
                    GradientRamp.perceptual = c.perceptualColor;
                });
        helpSpots.add(new HelpSpot(rx, y, cw, 20, () -> ConfigManager.get().perceptualColor
                ? "Perceptual: colours compared as the eye sees them (Oklab)"
                : "Classic: raw RGB / luma maths (pre-1.3 behaviour)"));
        y += 24;
        // What happens when a palette's blocks aren't all in the inventory at paint time.
        cycleButton(rx, y, cw,
                () -> Component.literal("Missing blocks: " + ConfigManager.get().missingBlockPolicy.label()),
                () -> {
                    GradientConfig c = ConfigManager.get();
                    c.missingBlockPolicy = c.missingBlockPolicy == MissingBlockPolicy.DONT_PAINT
                            ? MissingBlockPolicy.SKIP_MISSING
                            : MissingBlockPolicy.DONT_PAINT;
                });
        helpSpots.add(new HelpSpot(rx, y, cw, 20,
                () -> ConfigManager.get().missingBlockPolicy == MissingBlockPolicy.DONT_PAINT
                        ? "Don't paint: painting refuses while a palette's blocks are missing"
                        : "Skip missing: absent segments are dropped and the rest still paint"));
        y += 24;
        // Angular snapping for facing-derived directions (Face perp runs, pattern planes).
        cycleButton(rx, y, cw,
                () -> Component.literal("Perp snap: " + ConfigManager.get().perpSnapDegrees + "°"),
                () -> {
                    GradientConfig c = ConfigManager.get();
                    c.perpSnapDegrees = c.perpSnapDegrees == 45 ? 90 : 45;
                });
        helpSpots.add(new HelpSpot(rx, y, cw, 20,
                () -> ConfigManager.get().perpSnapDegrees == 45
                        ? "45°: Face perp runs and pattern planes can go diagonal"
                        : "90°: Face perp runs and pattern planes snap to the block axes"));

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
        if ((tab == Tab.SOLID || tab == Tab.SETTINGS) && event.button() == 0
                && handleHelpIconClick(event.x(), event.y())) {
            return true;
        }
        if (tab == Tab.HELP && event.button() == 0 && help != null
                && help.mouseClicked(event.x(), event.y())) {
            return true;
        }
        if (tab == Tab.SOLID && (event.button() == 0 || event.button() == 1)) {
            String id = solidRowIdAt(event.x(), event.y());
            if (id != null) return handleSolidRowClick(id, event.button());
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
        if (tab == Tab.SOLID && mouseX >= contentX() && mouseX <= contentX() + leftW()
                && mouseY >= SOLID_LIST_Y && mouseY <= SOLID_LIST_Y + solidListH) {
            solidScroll = Math.max(0, Math.min(maxSolidScroll(),
                    solidScroll - (int) Math.signum(scrollY)));
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
        return Component.literal("Clear Markers (" + (MarkerManager.startMarkers.size()
                + MarkerManager.endMarkers.size() + co.fax.wang.shape.ShapeMarkers.count()) + ")");
    }

    private void cycleButton(int x, int y, int w, java.util.function.Supplier<Component> label, Runnable onCycle) {
        addRenderableWidget(Button.builder(label.get(), b -> {
            onCycle.run(); ConfigManager.save(); b.setMessage(label.get());
        }).bounds(x, y, w, 20).build());
    }

    private static int sliderToDist(double v) { return Math.max(1, Math.min(128, 1 + (int) Math.round(v * 127))); }
    private static double distToSlider(int d) { return (Math.max(1, Math.min(128, d)) - 1) / 127.0; }
    // Paint memory: 10 s – 30 min.
    private static int sliderToSecs(double v) { return Math.max(10, Math.min(1800, 10 + (int) Math.round(v * 1790))); }
    private static double secsToSlider(int s) { return (Math.max(10, Math.min(1800, s)) - 10) / 1790.0; }
    private static String secsLabel(int s) {
        if (s < 60) return s + "s";
        return s % 60 == 0 ? (s / 60) + "m" : (s / 60) + "m " + (s % 60) + "s";
    }

    // ---- rendering ------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Full-page rgba(0,0,0,0.3) underlay so every tab reads against any world (before super
        // → beneath the widgets).
        g.fill(0, 0, this.width, this.height, 0x4D000000);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        renderTitleBar(g);
        if (tab == Tab.PAINT) renderPaintTab(g, mouseX, mouseY);
        else if (tab == Tab.PALETTE) renderPaletteTab(g, mouseX, mouseY);
        else if (tab == Tab.SOLID) renderSolidTab(g, mouseX, mouseY);
        else if (tab == Tab.FINDER) renderFinderTab(g, mouseX, mouseY);
        else if (tab == Tab.HELP) { if (help != null) help.render(g, mouseX, mouseY); }
        else renderSettingsTab(g, mouseX, mouseY);
        if (tab == Tab.SOLID || tab == Tab.SETTINGS) renderHelpSystem(g, mouseX, mouseY);
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

    private void renderSettingsTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        GradientConfig cfg = ConfigManager.get();
        int cx = contentX(), w = leftW();

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
