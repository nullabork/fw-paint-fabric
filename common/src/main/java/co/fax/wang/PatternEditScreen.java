package co.fax.wang;

import co.fax.wang.palette.Palette;
import co.fax.wang.palette.PaletteKind;
import co.fax.wang.palette.PaletteStore;
import co.fax.wang.palette.PatternTiling;
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
import java.util.Set;

/**
 * The pattern editor — draw a W×H block grid by hand. Left column: the colour-sorted source
 * list (left-click selects the drawing block, whose sprite then follows the cursor) with the
 * tiling + variation toggles below. Right column: Width/Height inputs over the canvas ("start"
 * above, "end" below). Hold left mouse to draw; pressing on a cell that already holds the
 * selected block turns the drag into an erase of that block only. The full 32×32 buffer is kept
 * while editing, so shrinking then re-growing restores cropped cells (cropped cells are only
 * dropped on Save).
 */
public class PatternEditScreen extends Screen {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFA0A0A0;
    private static final int LIGHT = 0xFFE0E0E0;
    private static final int RED = 0xFFFF5555;
    private static final int YELLOW = 0xFFFFE34D;
    private static final int BAR_H = 22;
    private static final int LEFT_X = 10;
    private static final int COL_GAP = 12;
    private static final int HOVER_BG = 0x33FFFFFF;

    private static final int MAX_DIM = 32;

    // Vertical layout (base y positions — the page scroll offsets them).
    private static final int PREVIEW_Y = 30;
    private static final int PREVIEW_H = 104;
    private static final int NAME_LABEL_Y = PREVIEW_Y + PREVIEW_H + 6;
    private static final int NAME_Y = NAME_LABEL_Y + 10;
    private static final int SEP_Y = NAME_Y + 20 + 8;
    private static final int COL_TOP = SEP_Y + 1 + 20; // room for the W/H captions under the line
    private static final int LIST_H = 204;
    private static final int CANVAS_TARGET = 320;  // the canvas roughly fits this square
    private static final int CANVAS_TOP = COL_TOP + 24 + 12; // inputs row + "start" label

    private final Palette editing;   // working copy (width/height/tiling/variation/name/source)
    private final boolean isNew;
    private boolean dirty;

    /** Full 32×32 working buffer; the palette's cells are the top-left W×H view of it on save. */
    private final String[] buffer = new String[MAX_DIM * MAX_DIM];

    private String nameDraft;
    private String nameError = "";
    private EditBox nameBox, widthBox, heightBox;

    private record ScrolledWidget(AbstractWidget widget, int baseY) {}
    private final List<ScrolledWidget> scrolledWidgets = new ArrayList<>();
    private int scroll;

    // Left column source rows.
    private record LRow(String id, ItemStack stack, String name, int rgb) {}
    private List<LRow> leftRows = new ArrayList<>();
    private int leftScroll;
    private Set<String> availableIds = new HashSet<>();

    // The selected drawing block (sprite follows the cursor); the eraser clears any cell.
    private String selectedId = "";
    private ItemStack selectedStack = ItemStack.EMPTY;
    private boolean eraserSelected;

    // Canvas drag state: mode fixed at mouse-down.
    private boolean drawing;
    private boolean erasing;

    // Clear-grid confirmation modal.
    private boolean confirmClear;

    // Cell colour memo for canvas + preview (id → 0xFFrrggbb).
    private final Map<String, Integer> cellColor = new HashMap<>();

    // Help popups (shared conventions with the other editors).
    private record HelpSpot(int x, int baseY, int w, int h, java.util.function.Supplier<String> text) {}
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

    private int paneW() { return 180; }

    /** Cell px, sized so the canvas NEVER exceeds the fixed layout envelope — dense grids get
     *  smaller cells (32×32 → 8px) so the canvas stays compact; the layout must not shift when
     *  W/H change (widgets are placed at init; a moving column desyncs them). */
    private int canvasCell() {
        return Math.max(4, Math.min(20, 256 / Math.max(editing.width, editing.height)));
    }

    private int canvasW() { return canvasCell() * editing.width; }
    private int canvasH() { return canvasCell() * editing.height; }

    private int contentX() {
        int total = paneW() + COL_GAP + CANVAS_TARGET + 8; // constant — independent of grid size
        return Math.max(LEFT_X, (this.width - total) / 2);
    }

    private int rightX() { return contentX() + paneW() + COL_GAP; }
    private int canvasX() { return rightX(); }
    private int canvasY() { return CANVAS_TOP - scroll; }

    private int contentHeight() {
        int paneBottom = COL_TOP + 24 + LIST_H + 26 + 72;   // list + hints + three toggles below
        int canvasBottom = CANVAS_TOP + canvasH() + 14 + 60; // "end" label + 4 hint lines
        return Math.max(paneBottom, canvasBottom) + 8;
    }

    private int viewBottom() { return this.height - 4; }

    private int maxScroll() { return Math.max(0, contentHeight() - (viewBottom() - BAR_H)); }

    // ---- init -----------------------------------------------------------------------------------

    @Override
    protected void init() {
        scrolledWidgets.clear();
        helpSpots.clear();
        rebuildLeftRows();

        int cx = contentX();

        nameBox = new EditBox(this.font, cx, NAME_Y, paneW() + COL_GAP + 220 - 124 - 4, 20,
                Component.literal("Name"));
        nameBox.setHint(Component.literal("Untitled"));
        nameBox.setMaxLength(48);
        nameBox.setValue(nameDraft == null ? "" : nameDraft);
        nameBox.setResponder(s -> {
            nameDraft = s;
            nameError = "";
            dirty = true;
        });
        addScrolled(nameBox);
        int saveX = cx + paneW() + COL_GAP + 220 - 124;
        addScrolled(Button.builder(Component.literal("Save"), b -> save())
                .bounds(saveX, NAME_Y, 60, 20).build());
        addScrolled(Button.builder(Component.literal("Cancel"), b -> attemptExit(backToList()))
                .bounds(saveX + 62, NAME_Y, 60, 20).build());

        // Left column: source toggle over the list; tiling + variation under it.
        addScrolled(Button.builder(Component.literal("Source: " + editing.source.displayName()), b -> {
            editing.source = editing.source.next();
            b.setMessage(Component.literal("Source: " + editing.source.displayName()));
            dirty = true;
            rebuildLeftRows();
        }).bounds(cx, COL_TOP, paneW(), 20).build());
        helpSpots.add(new HelpSpot(cx, COL_TOP, paneW(), 20,
                () -> "Blocks come from " + editing.source.displayName()));

        int togY = COL_TOP + 24 + LIST_H + 26;
        addScrolled(Button.builder(Component.literal("Tile: " + editing.tiling.label()), b -> {
            editing.tiling = editing.tiling.next();
            dirty = true;
            b.setMessage(Component.literal("Tile: " + editing.tiling.label()));
        }).bounds(cx, togY, paneW() - 14, 20).build());
        helpSpots.add(new HelpSpot(cx, togY, paneW() - 14, 20, () -> switch (editing.tiling) {
            case NONE -> "No tiling: one copy — columns stop at the end, nothing past the sides";
            case SIDES -> "Tiles sideways: the pattern wraps across its width";
            case START_END -> "Tiles start/end: columns repeat the pattern as they run";
            case START_END_SIDES -> "Tiles both ways: wraps across the width and along the run";
        }));
        int half = (paneW() - 14 - 4) / 2;
        addScrolled(Button.builder(Component.literal("Var: " + variationLabel()), b -> {
            editing.variationWindow = (editing.variationWindow + 1) % 4;
            dirty = true;
            b.setMessage(Component.literal("Var: " + variationLabel()));
        }).bounds(cx, togY + 24, half, 20).build());
        addScrolled(new ChanceSlider(cx + half + 4, togY + 24, paneW() - 14 - half - 4));
        helpSpots.add(new HelpSpot(cx, togY + 24, paneW() - 14, 20,
                () -> editing.variationWindow == 0
                        ? "Off: cells place exactly the block you drew"
                        : "±" + editing.variationWindow + ": with a " + editing.variationChance
                                + "% chance a cell swaps to a block within "
                                + editing.variationWindow + " position(s) of it in the colour ordering"));
        addScrolled(Button.builder(Component.literal("Start: " + (editing.startAtBottom ? "Bottom" : "Top")), b -> {
            editing.startAtBottom = !editing.startAtBottom;
            dirty = true;
            b.setMessage(Component.literal("Start: " + (editing.startAtBottom ? "Bottom" : "Top")));
        }).bounds(cx, togY + 48, paneW() - 14, 20).build());
        helpSpots.add(new HelpSpot(cx, togY + 48, paneW() - 14, 20,
                () -> editing.startAtBottom
                        ? "Placement begins at the drawing's BOTTOM row — painting up from the "
                                + "ground keeps the drawing upright"
                        : "Placement begins at the drawing's TOP row"));

        // Right column: [Clear] [Width] [Height], each a third of the canvas width.
        int rx = rightX();
        int third = (CANVAS_TARGET - 2 * 8) / 3;
        addScrolled(Button.builder(Component.literal("Clear"), b -> confirmClear = true)
                .bounds(rx, COL_TOP, third, 20).build());
        widthBox = sizeBox(rx + third + 8, COL_TOP, editing.width, v -> {
            editing.width = v;
            dirty = true;
        });
        heightBox = sizeBox(rx + 2 * (third + 8), COL_TOP, editing.height, v -> {
            editing.height = v;
            dirty = true;
        });
        widthBox.setWidth(third);
        heightBox.setWidth(third);
        addScrolled(widthBox);
        addScrolled(heightBox);
        helpSpots.add(new HelpSpot(rx + 3 * third + 16, COL_TOP, 0, 20,
                () -> "Pattern size in cells (1–" + MAX_DIM + " each). Shrinking keeps the "
                        + "cropped cells until you save. Clear empties the whole grid"));

        scroll = Math.min(scroll, maxScroll());
        applyScroll();
    }

    private String variationLabel() {
        return editing.variationWindow == 0 ? "Off" : "±" + editing.variationWindow;
    }

    /** The swap-chance slider (0–100%, whole-percent steps) beside the Variation toggle. */
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

    private EditBox sizeBox(int x, int y, int initial, java.util.function.IntConsumer apply) {
        EditBox box = new EditBox(this.font, x, y, 70, 20, Component.literal("size"));
        box.setMaxLength(2);
        box.setValue(String.valueOf(initial));
        // (26.2's EditBox has no input filter — the responder only applies clean 1–32 parses.)
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

    // ---- input ----------------------------------------------------------------------------------

    private int[] cellAt(double mx, double my) {
        int px = canvasCell();
        int cxs = canvasX(), cys = canvasY();
        if (mx < cxs || my < cys) return null;
        int u = (int) ((mx - cxs) / px), v = (int) ((my - cys) / px);
        if (u < 0 || u >= editing.width || v < 0 || v >= editing.height) return null;
        return new int[]{u, v};
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x(), my = event.y();
        if (pendingExit != null) return handleDiscardClick(mx, my, event.button());
        if (confirmClear) {
            if (event.button() == 0 && inRect(confirmBtn(true), mx, my)) {
                java.util.Arrays.fill(buffer, null);
                editing.startU = -1;
                editing.startV = -1;
                dirty = true;
            }
            confirmClear = false; // confirm, cancel, and click-away all dismiss
            return true;
        }

        if (my < BAR_H && event.button() == 0) {
            int[] xs = tabXs();
            String[] names = GradientScreen.barTabNames();
            for (int i = 0; i < names.length; i++) {
                if (mx >= xs[i * 2] && mx <= xs[i * 2] + xs[i * 2 + 1]) {
                    attemptExit(GradientScreen.atBarTab(i));
                    return true;
                }
            }
        }
        if (event.button() == 0) {
            for (HelpSpot h : helpSpots) {
                int hy = h.baseY() - scroll;
                if (mx >= h.x() + h.w() + 2 && mx <= h.x() + h.w() + 13
                        && my >= hy + 5 && my <= hy + 16) {
                    clickedSpot = h;
                    return true;
                }
            }
        }
        if (super.mouseClicked(event, doubled)) return true;

        // Canvas: left press decides draw vs erase for the whole drag; right = flood fill;
        // middle = toggle the start-cell marker.
        int[] c = cellAt(mx, my);
        if (c != null) {
            if (event.button() == 0 && !event.hasControlDown()
                    && (eraserSelected || !selectedId.isEmpty())) {
                erasing = eraserSelected || selectedId.equals(cell(c[0], c[1]));
                drawing = true;
                applyCell(c[0], c[1]);
                return true;
            }
            if (event.button() == 1 && (eraserSelected || !selectedId.isEmpty())) {
                floodFill(c[0], c[1]);
                return true;
            }
            // Ctrl+click: toggle the placement-origin plus (one per grid).
            if (event.button() == 0 && event.hasControlDown()) {
                if (editing.startU == c[0] && editing.startV == c[1]) {
                    editing.startU = -1;
                    editing.startV = -1;
                } else {
                    editing.startU = c[0];
                    editing.startV = c[1];
                }
                dirty = true;
                return true;
            }
            // Middle-click: eyedropper — pick the cell's block (empty cell picks the eraser)
            // and show it selected in the left list, scrolled into view.
            if (event.button() == 2) {
                String id = cell(c[0], c[1]);
                if (id == null) {
                    eraserSelected = true;
                    selectedId = "";
                    selectedStack = ItemStack.EMPTY;
                    leftScroll = 0;
                } else {
                    for (int i = 0; i < leftRows.size(); i++) {
                        if (leftRows.get(i).id().equals(id)) {
                            eraserSelected = false;
                            selectedId = id;
                            selectedStack = leftRows.get(i).stack();
                            int visible = LIST_H / 18;
                            int row = i + 1; // +1: the pinned eraser row
                            leftScroll = Math.max(0, Math.min(
                                    Math.max(0, leftRows.size() + 1 - visible),
                                    row - visible / 2));
                            break;
                        }
                    }
                }
                return true;
            }
        }
        // Left list: left-click selects the drawing block (row 0 = the eraser).
        int cx = contentX(), ly = COL_TOP + 24 - scroll;
        if (event.button() == 0 && mx >= cx && mx <= cx + paneW() && my >= ly && my <= ly + LIST_H) {
            int idx = leftScroll + (int) ((my - ly) / 18);
            if (idx == 0) {
                eraserSelected = true;
                selectedId = "";
                selectedStack = ItemStack.EMPTY;
            } else if (idx >= 1 && idx - 1 < leftRows.size()) {
                eraserSelected = false;
                selectedId = leftRows.get(idx - 1).id();
                selectedStack = leftRows.get(idx - 1).stack();
            }
            return true;
        }
        return false;
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
     * Right-click flood fill: repaint the 4-connected region of cells matching the clicked
     * cell's content (one block, or the empty region) with the selected block (or clear it,
     * with the eraser). Cells holding any OTHER block bound the fill.
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
        if (event.button() == 0 && drawing) {
            int[] c = cellAt(event.x(), event.y());
            if (c != null) applyCell(c[0], c[1]);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0 && drawing) {
            drawing = false;
            erasing = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (pendingExit != null) return true;
        int cx = contentX(), ly = COL_TOP + 24 - scroll;
        if (mouseX >= cx && mouseX <= cx + paneW() && mouseY >= ly && mouseY <= ly + LIST_H) {
            int maxLeft = Math.max(0, leftRows.size() + 1 - LIST_H / 18);
            leftScroll = Math.max(0, Math.min(maxLeft, leftScroll - (int) Math.signum(scrollY)));
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
        return new GradientScreen(false);
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
        if (button != 0) return true;
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

    private int[] tabXs() {
        String[] names = GradientScreen.barTabNames();
        int gap = 14;
        int[] out = new int[names.length * 2];
        int x = this.width - 10;
        for (int i = names.length - 1; i >= 0; i--) {
            String label = i == GradientScreen.paletteBarIndex() ? "» " + names[i] : names[i];
            int w = this.font.width(label);
            x -= w;
            out[i * 2] = x;
            out[i * 2 + 1] = w;
            x -= gap;
        }
        return out;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0x4D000000);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        renderIsoPreview(g);
        renderNameRow(g);
        renderLeftList(g, mouseX, mouseY);
        renderCanvas(g, mouseX, mouseY);
        renderHelpIcons(g);
        renderPageScrollbar(g);
        renderTitleBar(g);
        renderHelpPopup(g, mouseX, mouseY);
        renderCursorBlock(g, mouseX, mouseY);
        if (confirmClear) renderClearConfirm(g, mouseX, mouseY);
        if (pendingExit != null) renderDiscardConfirm(g, mouseX, mouseY);
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

    private void renderTitleBar(GuiGraphicsExtractor g) {
        g.fill(0, 0, this.width, BAR_H, 0xE0000000);
        g.fill(0, BAR_H, this.width, BAR_H + 1, 0x60FFFFFF);
        int textY = (BAR_H - this.font.lineHeight) / 2 + 1;
        g.text(this.font, "FW Paint — Edit pattern", 8, textY, WHITE);
        String[] names = GradientScreen.barTabNames();
        int[] xs = tabXs();
        for (int i = 0; i < names.length; i++) {
            boolean cur = i == GradientScreen.paletteBarIndex();
            g.text(this.font, cur ? "» " + names[i] : names[i], xs[i * 2], textY, cur ? WHITE : GREY);
        }
    }

    private void renderNameRow(GuiGraphicsExtractor g) {
        int cx = contentX();
        int total = paneW() + COL_GAP + Math.max(canvasW() + 8, 220);
        int labelY = NAME_LABEL_Y - scroll;
        if (labelY >= BAR_H + 2 && labelY <= viewBottom() - 8) {
            g.text(this.font, "Name:", cx, labelY, GREY);
            if (!nameError.isEmpty()) {
                g.text(this.font, nameError, cx + this.font.width("Name: ") + 4, labelY, RED);
            }
        }
        int sepY = SEP_Y - scroll;
        if (sepY >= BAR_H + 2 && sepY <= viewBottom()) {
            g.fill(cx, sepY, cx + total, sepY + 1, 0x50FFFFFF);
        }
        // Width/Height captions over their boxes (right of the Clear button).
        int rx = rightX();
        int third = (CANVAS_TARGET - 2 * 8) / 3;
        int capY = COL_TOP - scroll - 10;
        if (capY >= BAR_H + 2) {
            g.text(this.font, "Width", rx + third + 8, capY, GREY);
            g.text(this.font, "Height", rx + 2 * (third + 8), capY, GREY);
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
        int cx = contentX(), lw = paneW();
        int ly = COL_TOP + 24 - scroll;
        if (ly + LIST_H < BAR_H || ly > viewBottom()) return;
        g.fill(cx, ly, cx + lw, ly + LIST_H, 0x90000000);
        UiIcons.outline(g, cx, ly, lw, LIST_H, 0xA0FFFFFF);
        g.enableScissor(cx, Math.max(BAR_H + 1, ly), cx + lw, Math.min(viewBottom(), ly + LIST_H));
        // Row 0 is the pinned eraser; block rows follow, shifted by one.
        for (int i = leftScroll; i < leftRows.size() + 1; i++) {
            int ry = ly + (i - leftScroll) * 18;
            if (ry >= ly + LIST_H) break;
            boolean hover = mouseX >= cx && mouseX <= cx + lw && mouseY >= ry && mouseY < ry + 18;
            if (i == 0) {
                boolean sel = eraserSelected;
                if (hover || sel) g.fill(cx + 1, ry, cx + lw - 1, ry + 18, sel ? 0x4455FF55 : HOVER_BG);
                drawEraserIcon(g, cx + 2, ry + 1);
                g.text(this.font, "Eraser — clear cells", cx + 21, ry + 5,
                        sel ? WHITE : 0xFFC8C8C8);
                continue;
            }
            LRow row = leftRows.get(i - 1);
            boolean sel = !eraserSelected && row.id().equals(selectedId);
            if (hover || sel) g.fill(cx + 1, ry, cx + lw - 1, ry + 18, sel ? 0x4455FF55 : HOVER_BG);
            g.item(row.stack(), cx + 2, ry + 1);
            g.text(this.font, this.font.plainSubstrByWidth(row.name(), lw - 26),
                    cx + 21, ry + 5, sel ? WHITE : 0xFFE0E0E0);
        }
        g.disableScissor();
        int maxLeft = Math.max(0, leftRows.size() + 1 - LIST_H / 18);
        if (maxLeft > 0) {
            int trackX = cx + lw - 3;
            int thumbH = Math.max(8, LIST_H * (LIST_H / 18) / (leftRows.size() + 1));
            int thumbY = ly + (int) ((LIST_H - thumbH) * (double) leftScroll / maxLeft);
            g.fill(trackX, ly + 1, trackX + 2, ly + LIST_H - 1, 0x30FFFFFF);
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0x90FFFFFF);
        }
        int hintY = ly + LIST_H + 4;
        g.text(this.font, this.font.plainSubstrByWidth("Left-click: pick the drawing block", lw), cx, hintY, LIGHT);
        g.text(this.font, this.font.plainSubstrByWidth("Press a same-block cell to erase it", lw), cx, hintY + 11, LIGHT);
    }

    private void renderCanvas(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int px = canvasCell();
        int cxs = canvasX(), cys = canvasY();
        int w = canvasW(), h = canvasH();
        if (cys + h < BAR_H || cys > viewBottom()) return;

        String start = editing.startAtBottom ? "end" : "start";
        g.text(this.font, start, cxs + (w - this.font.width(start)) / 2, cys - 10, GREY);
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
        // Hovered cell highlight.
        int[] c = cellAt(mouseX, mouseY);
        if (c != null) {
            int x0 = cxs + c[0] * px, y0 = cys + c[1] * px;
            UiIcons.outline(g, x0, y0, px, px, 0xC0FFFFFF);
        }
        String end = editing.startAtBottom ? "start" : "end";
        g.text(this.font, end, cxs + (w - this.font.width(end)) / 2, cys + h + 4, GREY);
        // Interaction key (grey, like the other editors' hints) — one action per line.
        int hy = cys + h + 16;
        String[] key = {
                "Left-drag: draw (pressing a same-block cell erases it)",
                "Right-click: flood fill the matching region",
                "Middle-click: pick a cell's block",
                "Ctrl-click: set the placement origin",
        };
        for (int i = 0; i < key.length; i++) {
            g.text(this.font, key[i], cxs, hy + i * 11, LIGHT);
        }
    }

    /** A small pink eraser block icon for the pinned list row. */
    private static void drawEraserIcon(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x + 2, y + 5, x + 14, y + 12, 0xFFE791AF);   // body
        g.fill(x + 2, y + 10, x + 14, y + 12, 0xFF4A6EA9);  // ferrule band
        UiIcons.outline(g, x + 2, y + 5, 12, 7, 0xFF3A3A3A);
    }

    /** The isometric preview: the pattern as a 1-thick wall, start row on top, dynamic scale. */
    private void renderIsoPreview(GuiGraphicsExtractor g) {
        int y = PREVIEW_Y - scroll;
        if (y + PREVIEW_H < BAR_H || y > viewBottom()) return;
        boolean any = false;
        for (String c : buffer) {
            if (c != null) {
                any = true;
                break;
            }
        }
        if (!any) {
            String s = "Draw cells to preview";
            g.text(this.font, s, (this.width - this.font.width(s)) / 2, y + PREVIEW_H / 2 - 4, YELLOW);
            return;
        }
        float isoX = 7.0711f, isoDown = 3.5355f, isoUp = 8.6603f;
        float wPx = editing.width * isoX + 16, hPx = editing.width * isoDown + editing.height * isoUp + 16;
        float s = Math.min(1f, Math.min((PREVIEW_H - 8) / hPx, 260f / wPx));
        float tx = (this.width - wPx * s) / 2f, ty = y + (PREVIEW_H - hPx * s) / 2f;
        g.pose().pushMatrix();
        g.pose().translate(tx, ty);
        g.pose().scale(s, s);
        // Painter's order: bottom rows first (larger v), back-to-front along u — upper sprites
        // must draw over the top faces of the blocks below them.
        for (int v = editing.height - 1; v >= 0; v--) {
            for (int u = 0; u < editing.width; u++) {
                String id = cell(u, v);
                if (id == null) continue;
                ItemStack st = GradientScreen.stackOfId(id);
                g.pose().pushMatrix();
                g.pose().translate(u * isoX, u * isoDown + v * isoUp);
                if (st.isEmpty()) g.fill(0, 0, 16, 16, colorOf(id));
                else g.item(st, 0, 0);
                g.pose().popMatrix();
            }
        }
        g.pose().popMatrix();
    }

    /** The selected drawing block (or eraser) rides the cursor so you know what you'll paint. */
    private void renderCursorBlock(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (pendingExit != null || confirmClear) return;
        if (!eraserSelected && selectedId.isEmpty()) return;
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
            int y = h.baseY() - scroll;
            if (y < BAR_H + 2 || y + h.h() > viewBottom()) continue;
            UiIcons.drawHelpIcon(g, h.x() + h.w() + 3, y + 5);
        }
    }

    private void renderHelpPopup(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        HelpSpot over = null;
        for (HelpSpot h : helpSpots) {
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
