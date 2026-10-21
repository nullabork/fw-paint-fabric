package co.fax.wang;

import co.fax.wang.palette.Palette;
import com.mojang.blaze3d.platform.InputConstants;
import co.fax.wang.palette.PaletteKind;
import co.fax.wang.palette.PaletteStore;
import co.fax.wang.palette.PatternTiling;
import co.fax.wang.ui.EditorChrome;
import co.fax.wang.ui.Segmented;
import co.fax.wang.ui.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The pattern editor — draw a W×H block grid by hand, under the shared editor bar
 * ({@code ‹ Paint / PATTERN [name] … Cancel Save}). Columns: INK (Blocks from switch, the pinned
 * Eraser, the colour-sorted source list — left-click picks the ink, whose sprite then follows
 * the cursor) · CANVAS (tool bar Draw / Line / Fill / Pick / Origin choosing what plain LMB does,
 * Size W × H, Clear, and the grid, START above / END below) · PREVIEW (iso wall, expandable) and
 * HOW IT REPEATS (tiling, start row, variation). The tool shortcuts (Shift line, RMB fill, MMB
 * pick, Ctrl origin) work whichever tool is chosen. Drawing on a cell that already holds the ink
 * turns the drag into an erase of that ink only. The full 32×32 buffer is kept while editing, so
 * shrinking then re-growing restores cropped cells (cropped cells are only dropped on Save).
 */
public class PatternEditScreen extends Screen {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int RED = 0xFFFF5555;
    private static final int YELLOW = 0xFFFFE34D;
    private static final int BAR_H = EditorChrome.H;
    private static final int LEFT_X = 10;
    private static final int COL_GAP = 12;
    private static final int HOVER_BG = 0x33FFFFFF;
    private static final int ROW = 18;

    private static final int MAX_DIM = 32;

    /** Base y of every column's heading (the page scroll offsets all base y positions). */
    private static final int COL_Y = BAR_H + 8;

    // Column widths: INK and the right column flex between these; the canvas takes the rest.
    private static final int C1_MIN = 120, C1_MAX = 190;
    private static final int C2_MIN = 200;
    private static final int C3_MIN = 140, C3_MAX = 220;

    private static final float ISO_X = 7.0711f, ISO_DOWN = 3.5355f, ISO_UP = 8.6603f;

    /** What plain LMB does on the canvas; the shortcuts reach the others from any tool. */
    private enum Tool {
        DRAW("Draw", "LMB"), LINE("Line", "Shift"), FILL("Fill", "RMB"), PICK("Pick", "MMB"), ORIGIN("Origin", "Ctrl");
        final String label, key;
        Tool(String label, String key) { this.label = label; this.key = key; }
    }

    private final Palette editing;   // working copy (width/height/tiling/variation/name/source)
    private final boolean isNew;
    private boolean dirty;

    /** Full 32×32 working buffer; the palette's cells are the top-left W×H view of it on save. */
    private final String[] buffer = new String[MAX_DIM * MAX_DIM];

    private final EditorChrome chrome = new EditorChrome("Pattern");
    private String nameDraft;
    private String nameError = "";
    private EditBox nameBox, widthBox, heightBox;

    // Column geometry, worked out by layout() in init (base y positions).
    private int c1x, c1w, c2x, c2w, c3x, c3w, c3y;
    private int listH;
    private int toolY, sizeRowY, clearX, clearW; // tool bar row (+ the Size/Clear row when it wraps)
    private int canvasTopBase;                   // grid top (base y; START caption above)
    private int canvasEnv;                       // the square the grid fits in
    private int previewYBase, previewH, repeatsY, rightBottom;

    private record ScrolledWidget(AbstractWidget widget, int baseY) {}
    private final List<ScrolledWidget> scrolledWidgets = new ArrayList<>();
    private int scroll;

    // The ui-kit switches and caption lines (base y; positioned per frame).
    private record SegRow(Segmented seg, int x, int baseY, int w) {}
    private record Caption(int x, int baseY, int w, Supplier<String> text, boolean hint) {}
    private final List<SegRow> segRows = new ArrayList<>();
    private final List<Caption> captions = new ArrayList<>();
    private Segmented sourceSeg, tileSeg, startSeg, varSeg;

    // INK source rows (the Eraser is pinned above them).
    private record LRow(String id, ItemStack stack, String name, int rgb) {}
    private List<LRow> leftRows = new ArrayList<>();
    private int leftScroll;
    private Set<String> availableIds = new HashSet<>();

    // The ink (sprite follows the cursor); the eraser clears any cell.
    private String selectedId = "";
    private ItemStack selectedStack = ItemStack.EMPTY;
    private boolean eraserSelected;
    private Tool tool = Tool.DRAW;

    // Canvas drag state: mode fixed at mouse-down.
    private boolean drawing;
    private boolean erasing;

    // Line tool (Shift-drag, or the Line tool): anchored at press, live preview to the current
    // cell, commit on release. The grid itself is untouched until release (the preview is an overlay).
    private boolean lineActive;
    private int lineU0, lineV0, lineU1, lineV1;

    // Clear-grid confirmation modal.
    private boolean confirmClear;

    // Expanded (full-screen) preview overlay.
    private boolean previewExpanded;
    private static final int CLOSE_X_SIZE = 24;

    // Cell colour memo for canvas + preview (id → 0xFFrrggbb).
    private final Map<String, Integer> cellColor = new HashMap<>();

    // Help popups (shared conventions with the palette editor): one circled-? per section, plus
    // icon-less spots over the switches describing their current state.
    private record HelpSpot(int x, int baseY, int w, int h, Supplier<String> text, boolean icon) {}
    private final List<HelpSpot> helpSpots = new ArrayList<>();
    private HelpSpot clickedSpot, hoverSpot;
    private long hoverSince;

    private Screen pendingExit;

    public PatternEditScreen(Palette source) {
        super(Component.literal("FW Paint — Pattern editor"));
        this.isNew = source == null;
        this.editing = source == null ? newPattern() : source.copy();
        this.nameDraft = this.editing.name;
        // Load cells into the working buffer (top-left aligned).
        for (int v = 0; v < editing.height; v++) {
            for (int u = 0; u < editing.width; u++) {
                String c = editing.cellAt(u, v);
                buffer[v * MAX_DIM + u] = c == null || c.isEmpty() ? null : c;
            }
        }
    }

    private static Palette newPattern() {
        Palette p = new Palette();
        p.kind = PaletteKind.PATTERN;
        p.width = 8;
        p.height = 8;
        return p;
    }

    // ---- layout ---------------------------------------------------------------------------------

    /**
     * Three columns when they fit; on narrow screens the PREVIEW / HOW IT REPEATS column folds
     * under INK. The canvas column takes whatever is left.
     */
    private void layout() {
        int avail = this.width - 2 * LEFT_X;
        int three = C1_MIN + C2_MIN + C3_MIN + 2 * COL_GAP;
        if (avail >= three) {
            int extra = avail - three;
            c1w = C1_MIN + Math.min(C1_MAX - C1_MIN, extra / 4);
            c3w = C3_MIN + Math.min(C3_MAX - C3_MIN, extra / 4);
            c2w = Math.min(420, avail - c1w - c3w - 2 * COL_GAP);
            int total = c1w + c2w + c3w + 2 * COL_GAP;
            c1x = Math.max(LEFT_X, (this.width - total) / 2);
            c2x = c1x + c1w + COL_GAP;
            c3x = c2x + c2w + COL_GAP;
            c3y = COL_Y;
        } else {
            c1w = Math.max(C1_MIN, Math.min(C1_MAX, avail - C2_MIN - COL_GAP));
            c2w = Math.max(140, avail - c1w - COL_GAP);
            c1x = Math.max(LEFT_X, (this.width - (c1w + c2w + COL_GAP)) / 2);
            c2x = c1x + c1w + COL_GAP;
            c3x = c1x;
            c3w = c1w;
            c3y = -1; // under INK — set once that column is laid out
        }
        listH = Math.max(90, Math.min(306, viewBottom() - listYBase() - 18));
        if (c3y < 0) c3y = listYBase() + listH + 18;
    }

    private int pinYBase() { return COL_Y + Ui.SECTION_H + 10 + Ui.ROW_H + 4; }
    private int listYBase() { return pinYBase() + ROW + 3; }
    private int listY() { return listYBase() - scroll; }
    private int visibleListRows() { return Math.max(1, listH / ROW); }
    private int maxListScroll() { return Math.max(0, leftRows.size() - visibleListRows()); }

    /** Cell px, sized so the grid fits its envelope — dense grids get smaller cells (≥4px). */
    private int canvasCell() {
        return Math.max(4, Math.min(20, canvasEnv / Math.max(editing.width, editing.height)));
    }

    private int canvasW() { return canvasCell() * editing.width; }
    private int canvasH() { return canvasCell() * editing.height; }
    private int canvasX() { return c2x + (c2w - canvasW()) / 2; }
    private int canvasY() { return canvasTopBase - scroll; }

    private int contentBottom() {
        int inkBottom = listYBase() + listH + 16;
        int canvasBottom = canvasTopBase + canvasH() + 14 + 24; // END caption + hint lines
        return Math.max(Math.max(inkBottom, canvasBottom), rightBottom);
    }

    private int viewBottom() { return this.height - 4; }

    private int maxScroll() { return Math.max(0, contentBottom() + 8 - viewBottom()); }

    // ---- init -----------------------------------------------------------------------------------

    @Override
    protected void init() {
        scrolledWidgets.clear();
        helpSpots.clear();
        segRows.clear();
        captions.clear();
        rebuildLeftRows();
        layout();

        chrome.layout(this.font, this.width);
        nameBox = new EditBox(this.font, chrome.nameX(), chrome.nameY(), chrome.nameW(), EditorChrome.NAME_H,
                Component.literal("Name"));
        nameBox.setHint(Component.literal("Untitled"));
        nameBox.setMaxLength(48);
        nameBox.setValue(nameDraft == null ? "" : nameDraft);
        nameBox.setResponder(s -> {
            nameDraft = s;
            nameError = "";
            dirty = true;
        });
        addRenderableWidget(nameBox);

        // INK.
        int y = COL_Y + Ui.SECTION_H;
        captions.add(new Caption(c1x, y, c1w, () -> "Blocks from", false));
        sourceSeg = seg(List.of("Hotbar", "Inventory", "Both"), editing.source.ordinal(), c1x, y + 10, c1w, i -> {
            editing.source = GradientSource.values()[i];
            dirty = true;
            rebuildLeftRows();
            leftScroll = Math.min(leftScroll, maxListScroll());
        });
        sectionHelp(c1x, c1w, COL_Y, () -> "The ink is what the canvas draws with. Blocks from picks where the "
                + "list's blocks (and the blocks painting uses) come from: hotbar, inventory, or both. "
                + "Left-click a block to pick it as the ink, or the Eraser to clear cells. Cells whose "
                + "block isn't in Blocks from get a red outline.");

        // CANVAS: tool bar, then Size W × H and Clear (same row when there's room).
        toolY = COL_Y + Ui.SECTION_H;
        int toolsW = 0;
        for (Tool t : Tool.values()) toolsW += toolW(t) + 3;
        int boxW = 22;
        int sizeW = this.font.width("Size ") + boxW + this.font.width(" × ") + boxW + 6;
        clearW = this.font.width("Clear") + 12;
        boolean oneRow = toolsW + 8 + sizeW + clearW <= c2w;
        sizeRowY = oneRow ? toolY + 3 : toolY + 26;
        int sx = oneRow ? c2x + c2w - clearW - 6 - sizeW : c2x;
        widthBox = sizeBox(sx + this.font.width("Size "), sizeRowY, boxW, editing.width, v -> {
            editing.width = v;
            dirty = true;
        });
        heightBox = sizeBox(sx + this.font.width("Size ") + boxW + this.font.width(" × "), sizeRowY, boxW,
                editing.height, v -> {
            editing.height = v;
            dirty = true;
        });
        addScrolled(widthBox);
        addScrolled(heightBox);
        clearX = c2x + c2w - clearW;
        sectionHelp(c2x, c2w, COL_Y, () -> "Pick a tool for plain left-click: Draw (drag to paint cells; "
                + "starting on a cell that already holds the ink erases that ink instead), Line (straight or "
                + "45°, locks in on release), Fill (the matching region), Pick (take a cell's block as the "
                + "ink), Origin (where placement starts, one per grid). The shortcuts work from any tool: "
                + "Shift-drag line, RMB fill, MMB pick, Ctrl-click origin. Size is in cells (1–" + MAX_DIM
                + " each); shrinking keeps the cropped cells until you save. Clear empties the grid.");
        int gridTop = sizeRowY + 20 + 14; // the START caption sits in the gap
        canvasTopBase = gridTop;
        int roomH = viewBottom() - gridTop - 14 - 26;
        canvasEnv = Math.max(128, Math.min(Math.min(c2w, 320), roomH));

        // PREVIEW + HOW IT REPEATS.
        int rx = c3x, rw = c3w;
        sectionHelp(rx, rw - 18, c3y, () -> "The pattern as it paints: a wall one block thick, with the "
                + "variation rolled. ⛶ enlarges it; Esc closes.");
        addScrolled(Button.builder(Component.literal("⛶"), b -> previewExpanded = true)
                .bounds(rx + rw - 16, c3y - 3, 16, 13).build());
        previewYBase = c3y + Ui.SECTION_H;
        previewH = Math.max(80, Math.min(160, rw * 2 / 3));
        repeatsY = previewYBase + previewH + 10;
        sectionHelp(rx, rw, repeatsY, () -> "Tile: how the pattern repeats when painting runs past its "
                + "edges — None (one copy), Sides (wraps across its width), Start/end (repeats along the "
                + "run), Both. Start from: which row of the drawing placement begins at. Variation: a cell "
                + "may swap to a block near it in colour.");
        y = repeatsY + Ui.SECTION_H;
        captions.add(new Caption(rx, y, rw, () -> "Tile", false));
        tileSeg = seg(fit(rw, List.of("None", "Sides", "Start/end", "Both"), List.of("None", "Sides", "Ends", "Both")),
                editing.tiling.ordinal(), rx, y + 10, rw, i -> {
            editing.tiling = PatternTiling.values()[i];
            dirty = true;
        });
        helpSpots.add(new HelpSpot(rx, y + 10, rw, Ui.ROW_H, () -> switch (editing.tiling) {
            case NONE -> "No tiling: one copy — columns stop at the end, nothing past the sides";
            case SIDES -> "Tiles sideways: the pattern wraps across its width";
            case START_END -> "Tiles start/end: columns repeat the pattern as they run";
            case START_END_SIDES -> "Tiles both ways: wraps across the width and along the run";
        }, false));
        y += 10 + Ui.ROW_H + 6;
        captions.add(new Caption(rx, y, rw, () -> "Start from", false));
        startSeg = seg(List.of("Top", "Bottom"), editing.startAtBottom ? 1 : 0, rx, y + 10, rw, i -> {
            editing.startAtBottom = i == 1;
            dirty = true;
        });
        helpSpots.add(new HelpSpot(rx, y + 10, rw, Ui.ROW_H, () -> editing.startAtBottom
                ? "Placement begins at the drawing's BOTTOM row — painting up from the ground keeps "
                        + "the drawing upright"
                : "Placement begins at the drawing's TOP row", false));
        y += 10 + Ui.ROW_H + 6;
        int half = (rw - 4) / 2;
        varSeg = seg(List.of("Off", "±1", "±2", "±3"), editing.variationWindow, rx, y + 2, half, i -> {
            editing.variationWindow = i;
            dirty = true;
        });
        addScrolled(new ChanceSlider(rx + half + 4, y, rw - half - 4));
        captions.add(new Caption(rx, y + 22, rw, () -> editing.variationWindow == 0 || editing.variationChance == 0
                ? "Variation off: cells place the block you drew"
                : "Variation: " + editing.variationChance + "% swap to a colour ±" + editing.variationWindow
                        + " away", true));
        rightBottom = y + 34;

        leftScroll = Math.min(leftScroll, maxListScroll());
        scroll = Math.min(scroll, maxScroll());
        applyScroll();
    }

    private int toolW(Tool t) {
        return Math.max(this.font.width(t.label), this.font.width(t.key)) + 10;
    }

    private Segmented seg(List<String> labels, int selected, int x, int baseY, int w,
                          java.util.function.IntConsumer onPick) {
        Segmented s = new Segmented(labels, selected, onPick);
        segRows.add(new SegRow(s, x, baseY, w));
        return s;
    }

    /** The full labels when every cell fits them, else the short ones. */
    private List<String> fit(int w, List<String> full, List<String> shortLabels) {
        int cell = w / full.size();
        for (String l : full) {
            if (this.font.width(l) + 6 > cell) return shortLabels;
        }
        return full;
    }

    /** A section's one circled-? at the right end of its heading line. */
    private void sectionHelp(int x, int w, int headingY, Supplier<String> text) {
        helpSpots.add(new HelpSpot(x + w - 12, headingY - 5, 0, 14, text, true));
    }

    /** The swap-chance slider (0–100%, whole-percent steps) beside the Variation switch. */
    private final class ChanceSlider extends net.minecraft.client.gui.components.AbstractSliderButton {
        ChanceSlider(int x, int y, int w) {
            super(x, y, w, 20, Component.empty(), editing.variationChance / 100.0);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(((int) Math.round(this.value * 100)) + "%"));
        }

        @Override
        protected void applyValue() {
            editing.variationChance = (int) Math.round(this.value * 100);
            dirty = true;
        }
    }

    private EditBox sizeBox(int x, int y, int w, int initial, java.util.function.IntConsumer apply) {
        EditBox box = new EditBox(this.font, x, y, w, 16, Component.literal("size"));
        box.setMaxLength(2);
        box.setValue(String.valueOf(initial));
        // (EditBox has no input filter — the responder only applies clean 1–32 parses.)
        box.setResponder(s -> {
            try {
                int v = Integer.parseInt(s.strip());
                if (v >= 1 && v <= MAX_DIM) apply.accept(v);
            } catch (NumberFormatException ignored) {
                // partial input — applied once it parses
            }
        });
        return box;
    }

    private <T extends AbstractWidget> T addScrolled(T w) {
        scrolledWidgets.add(new ScrolledWidget(w, w.getY()));
        return addRenderableWidget(w);
    }

    private void applyScroll() {
        for (ScrolledWidget s : scrolledWidgets) {
            int y = s.baseY() - scroll;
            s.widget().setY(y);
            s.widget().visible = y >= BAR_H + 2 && y + s.widget().getHeight() <= viewBottom();
        }
    }

    private boolean rowVisible(int baseY, int h) {
        int y = baseY - scroll;
        return y >= BAR_H + 2 && y + h <= viewBottom();
    }

    // ---- data -----------------------------------------------------------------------------------

    private void rebuildLeftRows() {
        leftRows = new ArrayList<>();
        availableIds = new HashSet<>();
        if (this.minecraft == null || this.minecraft.player == null) return;
        var items = this.minecraft.player.getInventory().getNonEquipmentItems();
        int from = editing.source == GradientSource.INVENTORY ? 9 : 0;
        int to = Math.min(editing.source == GradientSource.HOTBAR ? 9 : 36, items.size());
        Set<String> seen = new HashSet<>();
        for (int slot = from; slot < to; slot++) {
            ItemStack st = items.get(slot);
            if (!(st.getItem() instanceof BlockItem bi)) continue;
            if (bi.getBlock().defaultBlockState().isAir()) continue;
            Identifier id = BuiltInRegistries.ITEM.getKey(st.getItem());
            if (id == null || !seen.add(id.toString())) continue;
            int rgb = BlockTextures.gradientValue(bi.getBlock(), null, GradientMode.COLOR, 0.5);
            leftRows.add(new LRow(id.toString(), st.copy(), st.getHoverName().getString(), rgb));
            availableIds.add(id.toString());
        }
        leftRows.sort(java.util.Comparator.comparingLong(r -> ColorOrder.colorSortKey(r.rgb())));
    }

    private int colorOf(String id) {
        return cellColor.computeIfAbsent(id, k -> {
            var b = Gradient.blockOfItemId(k);
            return b == null ? 0xFF666666
                    : 0xFF000000 | (BlockTextures.gradientValue(b, null, GradientMode.COLOR, 0.5) & 0xFFFFFF);
        });
    }

    private String cell(int u, int v) {
        return buffer[v * MAX_DIM + u];
    }

    private void setCell(int u, int v, String id) {
        buffer[v * MAX_DIM + u] = id;
        dirty = true;
    }

    private boolean hasInk() {
        return eraserSelected || !selectedId.isEmpty();
    }

    // ---- input ----------------------------------------------------------------------------------

    private int[] cellAt(double mx, double my) {
        int px = canvasCell();
        int cxs = canvasX(), cys = canvasY();
        if (mx < cxs || my < cys || my < BAR_H + 1) return null;
        int u = (int) ((mx - cxs) / px), v = (int) ((my - cys) / px);
        if (u < 0 || u >= editing.width || v < 0 || v >= editing.height) return null;
        return new int[]{u, v};
    }

    /** {x, w} of each tool button, left to right. */
    private int[] toolXs() {
        Tool[] all = Tool.values();
        int[] out = new int[all.length * 2];
        int x = c2x;
        for (int i = 0; i < all.length; i++) {
            out[i * 2] = x;
            out[i * 2 + 1] = toolW(all[i]);
            x += toolW(all[i]) + 3;
        }
        return out;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x(), my = event.y();
        if (pendingExit != null) return handleDiscardClick(mx, my, event.button());
        if (previewExpanded) {
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && inCloseX(mx, my)) previewExpanded = false;
            return true; // the editor beneath is covered — swallow everything
        }
        if (confirmClear) {
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && inRect(confirmBtn(true), mx, my)) {
                java.util.Arrays.fill(buffer, null);
                editing.startU = -1;
                editing.startV = -1;
                dirty = true;
            }
            confirmClear = false; // confirm, cancel, and click-away all dismiss
            return true;
        }

        // Editor bar: ‹ Paint = Cancel (both confirm unsaved changes), Save.
        if (my < BAR_H && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            switch (chrome.click(mx, my)) {
                case BACK, CANCEL -> {
                    attemptExit(backToList());
                    return true;
                }
                case SAVE -> {
                    save();
                    return true;
                }
                default -> { }
            }
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (HelpSpot h : helpSpots) {
                if (!h.icon()) continue;
                int hy = h.baseY() - scroll;
                if (mx >= h.x() + h.w() + 2 && mx <= h.x() + h.w() + 13
                        && my >= hy + 5 && my <= hy + 16) {
                    clickedSpot = h;
                    return true;
                }
            }
        }
        if (super.mouseClicked(event, doubled)) return true;
        if (my < BAR_H) return false;

        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (SegRow r : List.copyOf(segRows)) {
                if (!rowVisible(r.baseY(), Ui.ROW_H)) continue;
                r.seg().setBounds(r.x(), r.baseY() - scroll, r.w());
                if (r.seg().click(mx, my)) return true;
            }
            // Tool bar + Clear.
            if (rowVisible(toolY, 22)) {
                int[] xs = toolXs();
                int ty = toolY - scroll;
                for (int i = 0; i < Tool.values().length; i++) {
                    if (Ui.inside(mx, my, xs[i * 2], ty, xs[i * 2 + 1], 22)) {
                        tool = Tool.values()[i];
                        return true;
                    }
                }
            }
            if (rowVisible(sizeRowY, 16) && Ui.inside(mx, my, clearX, sizeRowY - scroll, clearW, 16)) {
                confirmClear = true;
                return true;
            }
        }

        // Canvas: the chosen tool on plain LMB; Shift line, RMB fill, MMB pick, Ctrl origin from any tool.
        int[] c = cellAt(mx, my);
        if (c != null) {
            int button = event.button();
            boolean left = button == InputConstants.MOUSE_BUTTON_LEFT;
            Tool use = null;
            if (left && event.hasShiftDown() && !event.hasControlDown()) use = Tool.LINE;
            else if (left && event.hasControlDown()) use = Tool.ORIGIN;
            else if (left) use = tool;
            else if (button == InputConstants.MOUSE_BUTTON_RIGHT) use = Tool.FILL;
            else if (button == InputConstants.MOUSE_BUTTON_MIDDLE) use = Tool.PICK;
            if (use != null) {
                switch (use) {
                    case LINE -> {
                        if (hasInk()) {
                            lineActive = true;
                            lineU0 = lineU1 = c[0];
                            lineV0 = lineV1 = c[1];
                        }
                    }
                    case DRAW -> {
                        if (hasInk()) {
                            erasing = eraserSelected || selectedId.equals(cell(c[0], c[1]));
                            drawing = true;
                            applyCell(c[0], c[1]);
                        }
                    }
                    case FILL -> {
                        if (hasInk()) floodFill(c[0], c[1]);
                    }
                    case PICK -> {
                        pickInk(c[0], c[1]);
                        if (left) tool = Tool.DRAW; // the Pick tool hands straight back to drawing
                    }
                    case ORIGIN -> toggleOrigin(c[0], c[1]);
                }
                return true;
            }
        }
        // INK list: left-click picks the ink (the pinned row is the eraser).
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && mx >= c1x && mx <= c1x + c1w && my > BAR_H) {
            int py = pinYBase() - scroll;
            if (my >= py && my < py + ROW) {
                eraserSelected = true;
                selectedId = "";
                selectedStack = ItemStack.EMPTY;
                return true;
            }
            int ly = listY();
            if (my >= ly && my <= ly + listH && my <= viewBottom()) {
                int idx = leftScroll + (int) ((my - ly) / ROW);
                if (idx >= 0 && idx < leftRows.size()) {
                    eraserSelected = false;
                    selectedId = leftRows.get(idx).id();
                    selectedStack = leftRows.get(idx).stack();
                }
                return true;
            }
        }
        return false;
    }

    /** Toggle the placement-origin plus (one per grid). */
    private void toggleOrigin(int u, int v) {
        if (editing.startU == u && editing.startV == v) {
            editing.startU = -1;
            editing.startV = -1;
        } else {
            editing.startU = u;
            editing.startV = v;
        }
        dirty = true;
    }

    /**
     * Eyedropper: take the cell's block as the ink (an empty cell picks the eraser) and show it
     * selected in the INK list, scrolled into view.
     */
    private void pickInk(int u, int v) {
        String id = cell(u, v);
        if (id == null) {
            eraserSelected = true;
            selectedId = "";
            selectedStack = ItemStack.EMPTY;
            return;
        }
        for (int i = 0; i < leftRows.size(); i++) {
            if (leftRows.get(i).id().equals(id)) {
                eraserSelected = false;
                selectedId = id;
                selectedStack = leftRows.get(i).stack();
                int visible = visibleListRows();
                leftScroll = Math.max(0, Math.min(maxListScroll(), i - visible / 2));
                break;
            }
        }
    }

    private void applyCell(int u, int v) {
        if (eraserSelected) {
            if (cell(u, v) != null) setCell(u, v, null);
        } else if (erasing) {
            if (selectedId.equals(cell(u, v))) setCell(u, v, null);
        } else {
            setCell(u, v, selectedId);
        }
    }

    /**
     * Flood fill: repaint the 4-connected region of cells matching the clicked cell's content
     * (one block, or the empty region) with the ink (or clear it, with the eraser). Cells holding
     * any OTHER block bound the fill.
     */
    private void floodFill(int u0, int v0) {
        String target = cell(u0, v0);
        String replacement = eraserSelected ? null : selectedId;
        if (java.util.Objects.equals(target, replacement)) return;
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{u0, v0});
        Set<Long> seen = new HashSet<>();
        seen.add(u0 * 64L + v0);
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            if (!java.util.Objects.equals(cell(c[0], c[1]), target)) continue;
            setCell(c[0], c[1], replacement);
            int[][] next = {{c[0] + 1, c[1]}, {c[0] - 1, c[1]}, {c[0], c[1] + 1}, {c[0], c[1] - 1}};
            for (int[] n : next) {
                if (n[0] < 0 || n[0] >= editing.width || n[1] < 0 || n[1] >= editing.height) continue;
                if (seen.add(n[0] * 64L + n[1])) queue.add(n);
            }
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && lineActive) {
            int[] c = clampedCellAt(event.x(), event.y());
            lineU1 = c[0];
            lineV1 = c[1];
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && drawing) {
            int[] c = cellAt(event.x(), event.y());
            if (c != null) applyCell(c[0], c[1]);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && lineActive) {
            // Commit: the line overwrites whatever it crosses with the ink.
            for (int[] c : lineCells()) {
                setCell(c[0], c[1], eraserSelected ? null : selectedId);
            }
            lineActive = false;
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && drawing) {
            drawing = false;
            erasing = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    /** The cell nearest to (mx, my), clamped into the grid — line drags may leave the canvas. */
    private int[] clampedCellAt(double mx, double my) {
        int px = canvasCell();
        int u = (int) Math.floor((mx - canvasX()) / px);
        int v = (int) Math.floor((my - canvasY()) / px);
        return new int[]{Math.max(0, Math.min(editing.width - 1, u)),
                Math.max(0, Math.min(editing.height - 1, v))};
    }

    /**
     * The line-tool cells: from the anchor toward the drag cell, snapped to the nearest of the
     * 8 directions (straight or 45°), length = the drag's projection onto that ray.
     */
    private List<int[]> lineCells() {
        List<int[]> out = new ArrayList<>();
        int du = lineU1 - lineU0, dv = lineV1 - lineV0;
        if (du == 0 && dv == 0) {
            out.add(new int[]{lineU0, lineV0});
            return out;
        }
        double snapped = Math.toRadians(Math.round(Math.toDegrees(Math.atan2(dv, du)) / 45.0) * 45.0);
        int rx = (int) Math.round(Math.cos(snapped));
        int ry = (int) Math.round(Math.sin(snapped));
        int len = (int) Math.round((du * rx + dv * ry) / (double) (rx * rx + ry * ry));
        for (int k = 0; k <= Math.max(0, len); k++) {
            int u = lineU0 + k * rx, v = lineV0 + k * ry;
            if (u < 0 || u >= editing.width || v < 0 || v >= editing.height) break;
            out.add(new int[]{u, v});
        }
        return out;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (pendingExit != null || previewExpanded) return true;
        int ly = listY();
        if (mouseX >= c1x && mouseX <= c1x + c1w && mouseY >= ly && mouseY <= ly + listH) {
            leftScroll = Math.max(0, Math.min(maxListScroll(), leftScroll - (int) Math.signum(scrollY)));
            return true;
        }
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) (Math.signum(scrollY) * 20)));
        applyScroll();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (pendingExit != null) {
            if (key == 256) pendingExit = null;
            return true;
        }
        if (previewExpanded && key == 256) {
            previewExpanded = false;
            return true;
        }
        if (confirmClear) {
            if (key == 256) confirmClear = false;
            return true;
        }
        if (clickedSpot != null && key == 256) {
            clickedSpot = null;
            return true;
        }
        boolean typing = (nameBox != null && nameBox.isFocused())
                || (widthBox != null && widthBox.isFocused())
                || (heightBox != null && heightBox.isFocused());
        if (!typing && key == 256) {
            attemptExit(backToList());
            return true;
        }
        return super.keyPressed(event);
    }

    // ---- save / exit ----------------------------------------------------------------------------

    private Screen backToList() {
        return GradientScreen.atPaintTab();
    }

    private void save() {
        List<String> takenNames = new ArrayList<>();
        List<String> takenIds = new ArrayList<>();
        for (Palette p : PaletteStore.all()) {
            if (p.id.equals(editing.id)) continue;
            takenNames.add(p.name);
            takenIds.add(p.id);
        }
        String name = nameDraft == null ? "" : nameDraft.strip();
        if (name.isEmpty()) {
            name = PaletteStore.uniqueName("", takenNames);
        } else if (PaletteStore.nameTaken(name, takenNames)) {
            nameError = "That name is already in use";
            return;
        }
        editing.name = name;
        if (editing.id == null || editing.id.isEmpty()) {
            editing.id = PaletteStore.slugFor(name, takenIds);
        }
        // Serialize the visible W×H window of the working buffer (cropped cells drop here).
        List<String> cells = new ArrayList<>(editing.width * editing.height);
        for (int v = 0; v < editing.height; v++) {
            for (int u = 0; u < editing.width; u++) {
                String c = cell(u, v);
                cells.add(c == null ? "" : c);
            }
        }
        editing.cells = cells;
        PaletteStore.upsert(editing);
        PaletteStore.setActive(editing.id); // activates as the PATTERN kind's selection
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(GradientScreen.openOnPalette(editing.id));
        }
    }

    private void attemptExit(Screen to) {
        if (!dirty) {
            if (this.minecraft != null) this.minecraft.setScreenAndShow(to);
            return;
        }
        pendingExit = to;
    }

    private boolean handleDiscardClick(double mx, double my, int button) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return true;
        if (inRect(confirmBtn(true), mx, my)) {
            Screen to = pendingExit;
            pendingExit = null;
            if (this.minecraft != null) this.minecraft.setScreenAndShow(to);
        } else {
            pendingExit = null;
        }
        return true;
    }

    private int[] confirmBox() {
        return new int[]{(this.width - 240) / 2, (this.height - 64) / 2, 240, 64};
    }

    private int[] confirmBtn(boolean discard) {
        int[] b = confirmBox();
        int bw = (b[2] - 3 * 8) / 2;
        return new int[]{discard ? b[0] + 8 : b[0] + 2 * 8 + bw, b[1] + b[3] - 26, bw, 18};
    }

    private static boolean inRect(int[] r, double mx, double my) {
        return mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    @Override
    public void onClose() {
        attemptExit(backToList());
    }

    // ---- rendering ------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // The bar goes down first so the name box (a widget) draws over it.
        g.fill(0, 0, this.width, this.height, 0x4D000000);
        chrome.render(g, this.font, mouseX, mouseY, dirty, nameError);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        boolean modal = pendingExit != null || confirmClear || previewExpanded;
        int mx = modal ? -1 : mouseX, my = modal ? -1 : mouseY;
        g.enableScissor(0, BAR_H + 1, this.width, viewBottom());
        renderSections(g);
        renderCaptions(g);
        renderParts(g, mx, my);
        renderToolBar(g, mx, my);
        renderLeftList(g, mx, my);
        renderCanvas(g, mx, my);
        renderIsoPreview(g);
        g.disableScissor();
        renderHelpIcons(g);
        renderPageScrollbar(g);
        renderHelpPopup(g, mouseX, mouseY);
        renderCursorBlock(g, mouseX, mouseY);
        if (previewExpanded) renderExpandedOverlay(g, mouseX, mouseY);
        if (confirmClear) renderClearConfirm(g, mouseX, mouseY);
        if (pendingExit != null) renderDiscardConfirm(g, mouseX, mouseY);
    }

    private void renderSections(GuiGraphicsExtractor g) {
        EditorChrome.section(g, this.font, c1x, COL_Y - scroll, c1w, null, "Ink", null, 14);
        // The canvas heading's note names the current ink.
        String ink = eraserSelected ? "ink: Eraser"
                : selectedId.isEmpty() ? "pick an ink" : "ink: " + selectedStack.getHoverName().getString();
        EditorChrome.section(g, this.font, c2x, COL_Y - scroll, c2w, null, "Canvas", ink, 14);
        EditorChrome.section(g, this.font, c3x, c3y - scroll, c3w, null, "Preview", null, 34);
        EditorChrome.section(g, this.font, c3x, repeatsY - scroll, c3w, null, "How it repeats", null, 14);
    }

    private void renderCaptions(GuiGraphicsExtractor g) {
        for (Caption c : captions) {
            g.text(this.font, this.font.plainSubstrByWidth(c.text().get(), c.w()), c.x(), c.baseY() - scroll,
                    c.hint() ? Ui.HINT : Ui.TEXT);
        }
    }

    private void renderParts(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        sourceSeg.setSelected(editing.source.ordinal());
        tileSeg.setSelected(editing.tiling.ordinal());
        startSeg.setSelected(editing.startAtBottom ? 1 : 0);
        varSeg.setSelected(editing.variationWindow);
        for (SegRow r : segRows) {
            r.seg().setBounds(r.x(), r.baseY() - scroll, r.w());
            r.seg().render(g, this.font, mouseX, mouseY);
        }
    }

    /** Tool buttons (name over its shortcut; the chosen one blue), Size label, red Clear. */
    private void renderToolBar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int[] xs = toolXs();
        int ty = toolY - scroll;
        Tool[] all = Tool.values();
        for (int i = 0; i < all.length; i++) {
            int x = xs[i * 2], w = xs[i * 2 + 1];
            boolean on = all[i] == tool;
            if (on) g.fill(x, ty, x + w, ty + 22, Ui.SELECTED_FILL);
            else if (Ui.inside(mouseX, mouseY, x, ty, w, 22)) g.fill(x, ty, x + w, ty + 22, Ui.HOVER);
            Ui.outline(g, x, ty, w, 22, on ? Ui.SELECTED : Ui.LINE);
            g.text(this.font, all[i].label, x + (w - this.font.width(all[i].label)) / 2, ty + 3, Ui.TEXT);
            g.text(this.font, all[i].key, x + (w - this.font.width(all[i].key)) / 2, ty + 12, Ui.HINT);
        }
        int sy = sizeRowY - scroll;
        g.text(this.font, "Size", widthBox.getX() - this.font.width("Size "), sy + 4, Ui.TEXT);
        g.text(this.font, "×", widthBox.getX() + widthBox.getWidth() + (this.font.width(" × ") - this.font.width("×")) / 2,
                sy + 4, Ui.HINT);
        EditorChrome.plainButton(g, this.font, clearX, sy, clearW, 16, "Clear", Ui.MISSING, mouseX, mouseY);
    }

    /** The full-screen preview: dark backdrop, the iso wall fitted large, ✗/Esc closes. */
    private void renderExpandedOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.nextStratum();
        g.fill(0, 0, this.width, this.height, 0xD0000000);
        if (!anyCells()) {
            g.text(this.font, "Draw cells to preview", this.width / 2 - 60, this.height / 2, YELLOW);
        } else {
            float wPx = editing.width * ISO_X + 16,
                    hPx = editing.width * ISO_DOWN + editing.height * ISO_UP + 16;
            float s = Math.min(3f, Math.min((this.width - 60) / wPx, (this.height - 70) / hPx));
            drawIsoWall(g, (this.width - wPx * s) / 2f, (this.height - hPx * s) / 2f, s);
        }
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

    private boolean inCloseX(double mx, double my) {
        return mx >= this.width - 10 - CLOSE_X_SIZE && mx <= this.width - 10
                && my >= 10 && my <= 10 + CLOSE_X_SIZE;
    }

    private void renderClearConfirm(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.nextStratum();
        g.fill(0, 0, this.width, this.height, 0xB0000000);
        int[] b = confirmBox();
        g.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], 0xF0202020);
        UiIcons.outline(g, b[0], b[1], b[2], b[3], WHITE);
        String msg = "Clear the whole grid?";
        g.text(this.font, msg, b[0] + (b[2] - this.font.width(msg)) / 2, b[1] + 10, WHITE);
        for (boolean yes : new boolean[]{true, false}) {
            int[] r = confirmBtn(yes);
            boolean hover = inRect(r, mouseX, mouseY);
            g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], hover ? 0x60FFFFFF : 0x30FFFFFF);
            String label = yes ? "Clear" : "Cancel";
            g.text(this.font, label, r[0] + (r[2] - this.font.width(label)) / 2, r[1] + 5,
                    yes ? RED : WHITE);
        }
    }

    private void renderPageScrollbar(GuiGraphicsExtractor g) {
        int max = maxScroll();
        if (max <= 0) return;
        int top = BAR_H + 2, h = viewBottom() - top;
        int x = this.width - 4;
        g.fill(x, top, x + 2, top + h, 0x30FFFFFF);
        int thumbH = Math.max(10, h * h / (h + max));
        int thumbY = top + (int) ((h - thumbH) * (double) scroll / max);
        g.fill(x, thumbY, x + 2, thumbY + thumbH, 0x90FFFFFF);
    }

    private void renderLeftList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int cx = c1x, lw = c1w;
        // The pinned eraser row.
        int py = pinYBase() - scroll;
        boolean eHover = Ui.inside(mouseX, mouseY, cx, py, lw, ROW - 1);
        if (eraserSelected) g.fill(cx, py, cx + lw, py + ROW - 1, Ui.SELECTED_FILL);
        else if (eHover) g.fill(cx + 1, py, cx + lw - 1, py + ROW - 1, HOVER_BG);
        if (eraserSelected) Ui.outline(g, cx, py, lw, ROW - 1, Ui.SELECTED);
        else EditorChrome.dashedOutline(g, cx, py, lw, ROW - 1, 0xFF8A8A8A);
        drawEraserIcon(g, cx + 2, py);
        g.text(this.font, this.font.plainSubstrByWidth("Eraser — clear cells", lw - 26), cx + 21, py + 4,
                eraserSelected ? WHITE : 0xFFC8C8C8);

        int ly = listY();
        g.fill(cx, ly, cx + lw, ly + listH, 0x90000000);
        UiIcons.outline(g, cx, ly, lw, listH, 0xA0FFFFFF);
        g.enableScissor(cx, Math.max(BAR_H + 1, ly), cx + lw, Math.min(viewBottom(), ly + listH));
        if (leftRows.isEmpty()) {
            g.text(this.font, this.font.plainSubstrByWidth("No blocks in " + editing.source.displayName(), lw - 8),
                    cx + 4, ly + 5, Ui.HINT);
        }
        for (int i = leftScroll; i < leftRows.size(); i++) {
            int ry = ly + (i - leftScroll) * ROW;
            if (ry >= ly + listH) break;
            boolean hover = mouseX >= cx && mouseX <= cx + lw && mouseY >= ry && mouseY < ry + ROW;
            LRow row = leftRows.get(i);
            boolean sel = !eraserSelected && row.id().equals(selectedId);
            if (sel) g.fill(cx + 1, ry, cx + lw - 1, ry + ROW, Ui.SELECTED_FILL);
            else if (hover) g.fill(cx + 1, ry, cx + lw - 1, ry + ROW, HOVER_BG);
            if (sel) Ui.outline(g, cx + 1, ry, lw - 2, ROW, Ui.SELECTED);
            g.item(row.stack(), cx + 2, ry + 1);
            g.text(this.font, this.font.plainSubstrByWidth(row.name(), lw - 26),
                    cx + 21, ry + 5, sel ? WHITE : 0xFFE0E0E0);
        }
        g.disableScissor();
        int maxLeft = maxListScroll();
        if (maxLeft > 0) {
            int trackX = cx + lw - 3;
            int thumbH = Math.max(8, listH * visibleListRows() / Math.max(1, leftRows.size()));
            int thumbY = ly + (int) ((listH - thumbH) * (double) leftScroll / maxLeft);
            g.fill(trackX, ly + 1, trackX + 2, ly + listH - 1, 0x30FFFFFF);
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0x90FFFFFF);
        }
        Ui.hint(g, this.font, cx, ly + listH + 4, lw, "LMB pick the ink");
    }

    private void renderCanvas(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int px = canvasCell();
        int cxs = canvasX(), cys = canvasY();
        int w = canvasW(), h = canvasH();

        String start = editing.startAtBottom ? "END" : "START";
        g.text(this.font, start, cxs + (w - this.font.width(start)) / 2, cys - 10, Ui.HINT);
        g.fill(cxs - 1, cys - 1, cxs + w + 1, cys + h + 1, 0x90000000);
        UiIcons.outline(g, cxs - 1, cys - 1, w + 2, h + 2, 0xA0FFFFFF);
        for (int v = 0; v < editing.height; v++) {
            for (int u = 0; u < editing.width; u++) {
                int x0 = cxs + u * px, y0 = cys + v * px;
                String id = cell(u, v);
                if (id != null) {
                    boolean missing = !availableIds.contains(id);
                    g.fill(x0, y0, x0 + px, y0 + px, colorOf(id));
                    if (missing) UiIcons.outline(g, x0, y0, px, px, RED);
                }
                // Grid lines (light, so tiny cells stay readable).
                g.fill(x0, y0, x0 + px, y0 + 1, 0x28FFFFFF);
                g.fill(x0, y0, x0 + 1, y0 + px, 0x28FFFFFF);
            }
        }
        // The start-cell plus marker: full-width cross over its cell, one per grid. Bright green
        // normally, magenta over green-dominant blocks so it always contrasts.
        if (editing.startU >= 0 && editing.startU < editing.width
                && editing.startV >= 0 && editing.startV < editing.height) {
            int x0 = cxs + editing.startU * px, y0 = cys + editing.startV * px;
            String under = cell(editing.startU, editing.startV);
            int color = 0xFF39FF14; // bright green
            if (under != null) {
                int rgb = colorOf(under);
                int r = (rgb >> 16) & 0xFF, gg = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (gg >= r && gg >= b) color = 0xFFFF00FF; // greenish block → magenta plus
            }
            int mid = Math.max(1, px / 2 - 1);
            g.fill(x0, y0 + mid, x0 + px, y0 + mid + 2, color);
            g.fill(x0 + mid, y0, x0 + mid + 2, y0 + px, color);
        }
        // Live line-tool preview: an overlay only — the grid beneath stays untouched until
        // release, so cells the line has passed over spring back as it moves.
        if (lineActive) {
            int fill = eraserSelected ? 0xC0202020 : colorOf(selectedId);
            for (int[] lc : lineCells()) {
                int x0 = cxs + lc[0] * px, y0 = cys + lc[1] * px;
                g.fill(x0, y0, x0 + px, y0 + px, fill);
                UiIcons.outline(g, x0, y0, px, px, 0xC0FFFFFF);
            }
        }
        // Hovered cell highlight.
        int[] c = cellAt(mouseX, mouseY);
        if (c != null) {
            int x0 = cxs + c[0] * px, y0 = cys + c[1] * px;
            UiIcons.outline(g, x0, y0, px, px, 0xC0FFFFFF);
        }
        String end = editing.startAtBottom ? "START" : "END";
        g.text(this.font, end, cxs + (w - this.font.width(end)) / 2, cys + h + 4, Ui.HINT);
        // The shortcut key (grey), wrapped to the column.
        int hy = cys + h + 16;
        for (String line : wrap("Any tool: Shift line · RMB fill · MMB pick · Ctrl origin · "
                + "LMB on the same ink erases", c2w)) {
            g.text(this.font, line, c2x, hy, Ui.HINT);
            hy += 11;
        }
    }

    /** A small pink eraser block icon for the pinned row. */
    private static void drawEraserIcon(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x + 2, y + 5, x + 14, y + 12, 0xFFE791AF);   // body
        g.fill(x + 2, y + 10, x + 14, y + 12, 0xFF4A6EA9);  // ferrule band
        UiIcons.outline(g, x + 2, y + 5, 12, 7, 0xFF3A3A3A);
    }

    /** The isometric preview: the pattern as a 1-thick wall, start row on top, fitted to its box. */
    private void renderIsoPreview(GuiGraphicsExtractor g) {
        int x = c3x, y = previewYBase - scroll, w = c3w, h = previewH;
        g.fill(x, y, x + w, y + h, 0x40000000);
        UiIcons.outline(g, x, y, w, h, Ui.LINE);
        if (!anyCells()) {
            String s = "Draw cells to preview";
            g.text(this.font, this.font.plainSubstrByWidth(s, w - 8),
                    x + Math.max(4, (w - this.font.width(s)) / 2), y + h / 2 - 4, YELLOW);
            return;
        }
        float wPx = editing.width * ISO_X + 16, hPx = editing.width * ISO_DOWN + editing.height * ISO_UP + 16;
        float s = Math.min(1f, Math.min((h - 8) / hPx, (w - 8) / wPx));
        g.enableScissor(x + 1, Math.max(BAR_H + 1, y + 1), x + w - 1, Math.min(viewBottom(), y + h - 1));
        drawIsoWall(g, x + (w - wPx * s) / 2f, y + (h - hPx * s) / 2f, s);
        g.disableScissor();
    }

    private boolean anyCells() {
        for (String c : buffer) {
            if (c != null) return true;
        }
        return false;
    }

    /**
     * The pattern as a 1-thick iso wall, WITH the variation roll applied (stable per-cell seeds
     * that include the var settings, so the preview updates live as they change but doesn't
     * flicker frame to frame). Painter's order: bottom rows first, back-to-front along u.
     */
    private void drawIsoWall(GuiGraphicsExtractor g, float tx, float ty, float s) {
        // The Oklab ordering the variation window indexes into (leftRows is colour-sorted).
        List<LRow> ordered = leftRows;
        Map<String, Integer> orderIdx = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) orderIdx.put(ordered.get(i).id(), i);
        int n = Math.max(0, Math.min(3, editing.variationWindow));
        int chance = Math.max(0, Math.min(100, editing.variationChance));

        g.pose().pushMatrix();
        g.pose().translate(tx, ty);
        g.pose().scale(s, s);
        for (int v = editing.height - 1; v >= 0; v--) {
            for (int u = 0; u < editing.width; u++) {
                String id = cell(u, v);
                if (id == null) continue;
                if (n > 0 && chance > 0) {
                    Integer idx = orderIdx.get(id);
                    if (idx != null) {
                        Random rnd = new Random(u * 73428767L ^ v * 912931L
                                ^ (n * 131L + chance));
                        if (rnd.nextInt(100) < chance) {
                            int lo = Math.max(0, idx - n), hi = Math.min(ordered.size() - 1, idx + n);
                            if (hi > lo) {
                                int pick = lo + rnd.nextInt(hi - lo);
                                if (pick >= idx) pick++;
                                id = ordered.get(pick).id();
                            }
                        }
                    }
                }
                ItemStack st = GradientScreen.stackOfId(id);
                g.pose().pushMatrix();
                g.pose().translate(u * ISO_X, u * ISO_DOWN + v * ISO_UP);
                if (st.isEmpty()) g.fill(0, 0, 16, 16, colorOf(id));
                else g.item(st, 0, 0);
                g.pose().popMatrix();
            }
        }
        g.pose().popMatrix();
    }

    /** The ink (or eraser) rides the cursor so you know what you'll paint. */
    private void renderCursorBlock(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (pendingExit != null || confirmClear || previewExpanded) return;
        if (!hasInk()) return;
        g.nextStratum();
        if (eraserSelected) {
            drawEraserIcon(g, mouseX + 6, mouseY + 6);
        } else if (selectedStack.isEmpty()) {
            g.fill(mouseX + 8, mouseY + 8, mouseX + 20, mouseY + 20, colorOf(selectedId));
        } else {
            g.item(selectedStack, mouseX + 8, mouseY + 8);
        }
    }

    private void renderHelpIcons(GuiGraphicsExtractor g) {
        for (HelpSpot h : helpSpots) {
            if (!h.icon()) continue;
            int y = h.baseY() - scroll;
            if (y < BAR_H + 2 || y + h.h() > viewBottom()) continue;
            UiIcons.drawHelpIcon(g, h.x() + h.w() + 3, y + 5);
        }
    }

    private void renderHelpPopup(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        HelpSpot over = null;
        for (HelpSpot h : helpSpots) {
            if (mouseY <= BAR_H || pendingExit != null || confirmClear || previewExpanded) break;
            int y = h.baseY() - scroll;
            if (mouseX >= h.x() && mouseX <= h.x() + h.w() + 13 && mouseY >= y && mouseY <= y + h.h()) {
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
        List<String> lines = wrap(over.text().get(), 150);
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

    private List<String> wrap(String text, int width) {
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

    private void renderDiscardConfirm(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.nextStratum();
        g.fill(0, 0, this.width, this.height, 0xB0000000);
        int[] b = confirmBox();
        g.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], 0xF0202020);
        UiIcons.outline(g, b[0], b[1], b[2], b[3], WHITE);
        String msg = "Discard unsaved changes?";
        g.text(this.font, msg, b[0] + (b[2] - this.font.width(msg)) / 2, b[1] + 10, WHITE);
        for (boolean discard : new boolean[]{true, false}) {
            int[] r = confirmBtn(discard);
            boolean hover = inRect(r, mouseX, mouseY);
            g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], hover ? 0x60FFFFFF : 0x30FFFFFF);
            String label = discard ? "Discard" : "Keep editing";
            g.text(this.font, label, r[0] + (r[2] - this.font.width(label)) / 2, r[1] + 5,
                    discard ? RED : WHITE);
        }
    }
}
