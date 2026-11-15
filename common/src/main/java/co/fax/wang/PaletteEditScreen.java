package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.palette.AutoMode;
import com.mojang.blaze3d.platform.InputConstants;
import co.fax.wang.palette.Palette;
import co.fax.wang.palette.PaletteMath;
import co.fax.wang.palette.PaletteOrder;
import co.fax.wang.palette.PaletteSegment;
import co.fax.wang.palette.PaletteStore;
import co.fax.wang.palette.SizingMode;
import co.fax.wang.ui.EditorChrome;
import co.fax.wang.ui.Segmented;
import co.fax.wang.ui.Toggle;
import co.fax.wang.ui.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
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
import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The palette editor — its own screen under the shared editor bar ({@code ‹ Paint / PALETTE
 * [name] … Cancel Save}); no main tab bar. Columns left → right in working order (everything
 * below the bar scrolls as one page): 1 PICK BLOCKS (Blocks from switch, pinned Auto rows, the
 * colour-sorted source list — double-click or drag blocks into the strip, right-click bans a
 * block from Auto) · 2 ARRANGE (the segment strip START → END, labels on the right, stop handles
 * on the left, drag to reorder, drag out sideways to remove) · 3 HOW IT BLENDS (Order / Curve /
 * Sizing switches, the sliders, and the collapsible NOISE group) · PREVIEW (one preview, As
 * gradient | As noise, expandable). Narrow screens fold the right columns under the left ones.
 */
public class PaletteEditScreen extends Screen {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = Ui.HINT;
    private static final int RED = 0xFFFF5555;
    private static final int YELLOW = 0xFFFFE34D;
    private static final int BAR_H = EditorChrome.H;
    private static final int LEFT_X = 10;
    private static final int COL_GAP = 12;
    private static final int HOVER_BG = 0x33FFFFFF;
    private static final int ROW = 18;              // list row height

    /** Base y of every column's heading (the page scroll offsets all base y positions). */
    private static final int COL_Y = BAR_H + 8;

    // Column widths: PICK BLOCKS and the two right columns flex between these.
    private static final int C1_MIN = 120, C1_MAX = 190;
    private static final int C3_MIN = 160, C3_MAX = 250;
    private static final int C4_MIN = 140, C4_MAX = 250;

    // ARRANGE column: stop handles left of the strip, labels to its right.
    private static final int HANDLE_W = 12;
    private static final int STRIP_W = 36;
    private static final int LABEL_W = 84;
    private static final int RIGHT_W = HANDLE_W + STRIP_W + 4 + LABEL_W;

    /** Labels pack to within a couple of px when segments get thin. */
    private static final int LABEL_SPACING = 9;

    // Iso sprite tiling (see GradientScreen for the derivation).
    private static final float ISO_X = 7.0711f;
    private static final float ISO_DOWN = 3.5355f;
    private static final float ISO_UP = 8.6603f;

    private static final PaletteOrder[] ORDERS =
            {PaletteOrder.COLOR_ASC, PaletteOrder.COLOR_DESC, PaletteOrder.BRIGHTNESS_ASC, PaletteOrder.BRIGHTNESS_DESC};
    private static final CurveFunction[] CURVES =
            {CurveFunction.LINEAR, CurveFunction.EASE_IN, CurveFunction.EASE_OUT, CurveFunction.EASE_IN_OUT, CurveFunction.STEP};

    private final Palette editing;      // working copy — Cancel discards it
    private final boolean isNew;
    private boolean dirty;

    private final EditorChrome chrome = new EditorChrome("Palette");
    private String nameDraft;
    private String nameError = "";
    private EditBox nameBox, seedBox;

    // Column geometry, worked out by layout() in init (base y positions).
    private int c1x, c1w, c2x, c3x, c3w, c3y, c4x, c4w, c4y;
    private int stripH, listH;
    private int settingsBottomBase;          // bottom of the HOW IT BLENDS column
    private int noiseHeadingY;               // base y of the collapsible NOISE heading
    private int previewYBase, previewH;      // the preview box inside the PREVIEW column

    // Page scroll: widgets remember their base y and get repositioned/hidden.
    private record ScrolledWidget(AbstractWidget widget, int baseY) {}
    private final List<ScrolledWidget> scrolledWidgets = new ArrayList<>();
    private int scroll;

    // The ui-kit parts and caption lines on the page (base y; positioned per frame).
    private record SegRow(Segmented seg, int x, int baseY, int w) {}
    private record TogRow(Toggle toggle, int x, int baseY, int w) {}
    /** A text line: label left (white, or grey when {@code hint}), optional grey note right. */
    private record Caption(int x, int baseY, int w, Supplier<String> left, Supplier<String> right, boolean hint) {}
    private final List<SegRow> segRows = new ArrayList<>();
    private final List<TogRow> togRows = new ArrayList<>();
    private final List<Caption> captions = new ArrayList<>();
    private Segmented orderSeg, curveSeg, sizingSeg, sourceSeg, varSeg, noiseTypeSeg, previewSeg;

    /** The NOISE group's collapsed state (kept across editor sessions). */
    private static boolean noiseOpen = true;

    // PICK BLOCKS: pinned Auto rows (leftRows 0 and 1) + the source blocks sorted by colour.
    private record LRow(AutoMode auto, String id, ItemStack stack, String name, int rgb) {}
    private static final int PINNED = 2;
    private List<LRow> leftRows = new ArrayList<>();
    private int leftScroll;
    private String lastRowClickId;
    private long lastRowClickMs;

    // Dragging a block OUT of the pane INTO the strip.
    private int paneDragIdx = -1;
    private boolean paneDragging;
    private double paneDragX, paneDragY;
    private double panePressX, panePressY;

    // Segment display caches, rebuilt on any segment change.
    private final List<ItemStack> segStacks = new ArrayList<>();
    private final List<String> segNames = new ArrayList<>();
    private final List<Boolean> segMissing = new ArrayList<>();
    private final List<Integer> segTints = new ArrayList<>(); // ARGB crosshatch tint; 0 = static
    private Set<String> availableIds = new HashSet<>();

    // Strip interaction.
    private int selectedSeg = -1;
    private int dragStop = -1;               // stop-handle drag
    private int dragSeg = -1;                // segment being dragged (reorder / drag-out)
    private boolean dragSegMoved;
    private boolean dragOut;                 // pulled sideways — release removes
    private double grabOffset;               // pressY − segment top at press
    private double dragMouseX, dragMouseY;
    private double pressX;

    // Preview: one at a time (As gradient = cylinder, As noise = cube), either expandable.
    private int previewMode;                 // 0 = gradient cylinder, 1 = noise cube
    private int expandedPreview;             // 0 = none, 1 = cylinder, 2 = noise cube
    private double previewOffX, previewOffY, previewOffZ;
    private enum CubeFace { TOP, RIGHT, LEFT }
    private CubeFace dragFace;
    private static final int CLOSE_X_SIZE = 24;

    // Cylinder preview cache (null cell = crosshatch / Automatic, tinted per cylTint).
    private ItemStack[][] cylCache;
    private int[][] cylTint;
    private int[][] cylCells;
    private int cylD, cylH;

    // Help popups: one circled-? per section heading (clicking it shows the help instantly), plus
    // icon-less spots over the switches that describe the choice UNDER THE MOUSE after a hover
    // (not the one that is on). Popups only live while the mouse stays over the spot. Texts are
    // suppliers so they stay current.
    private record HelpSpot(int x, int baseY, int w, int h,
                            Supplier<String> text, boolean icon) {}
    private final List<HelpSpot> helpSpots = new ArrayList<>();
    private HelpSpot clickedSpot;
    private HelpSpot hoverSpot;
    private long hoverSince;
    /** Where the mouse was when the help popup was last laid out (switch spots read it). */
    private int helpMx, helpMy;

    // Discard-confirm modal (unsaved changes).
    private Screen pendingExit;

    public PaletteEditScreen(Palette source) {
        super(Component.literal("FW Paint — Palette editor"));
        this.isNew = source == null;
        this.editing = source == null ? new Palette() : source.copy();
        this.nameDraft = this.editing.name;
        this.previewMode = ConfigManager.get().activePaintType == PaintType.NOISE ? 1 : 0;
    }

    // ---- layout ---------------------------------------------------------------------------------

    /**
     * Four columns when they fit; otherwise PREVIEW folds under HOW IT BLENDS, and on very narrow
     * screens both fold under PICK BLOCKS + ARRANGE. The strip and list heights follow the screen
     * height (the page scrolls for the rest).
     */
    private void layout() {
        int avail = this.width - 2 * LEFT_X;
        stripH = Math.max(140, Math.min(260, viewBottom() - COL_Y - 25 - 37 - 6));
        listH = Math.max(72, stripH - 47); // so the two hint blocks line up
        int four = C1_MIN + RIGHT_W + C3_MIN + C4_MIN + 3 * COL_GAP;
        int three = C1_MIN + RIGHT_W + C3_MIN + 2 * COL_GAP;
        int total;
        if (avail >= four) {
            int extra = avail - four;
            c1w = C1_MIN + Math.min(C1_MAX - C1_MIN, extra / 5);
            extra -= c1w - C1_MIN;
            c3w = C3_MIN + Math.min(C3_MAX - C3_MIN, extra / 2);
            extra -= c3w - C3_MIN;
            c4w = C4_MIN + Math.min(C4_MAX - C4_MIN, extra);
            total = c1w + RIGHT_W + c3w + c4w + 3 * COL_GAP;
            int x0 = Math.max(LEFT_X, (this.width - total) / 2);
            c1x = x0;
            c2x = c1x + c1w + COL_GAP;
            c3x = c2x + RIGHT_W + COL_GAP;
            c4x = c3x + c3w + COL_GAP;
            c3y = c4y = COL_Y;
        } else if (avail >= three) {
            int extra = avail - three;
            c3w = C3_MIN + Math.min(C3_MAX - C3_MIN, extra / 2);
            c1w = C1_MIN + Math.min(C1_MAX - C1_MIN, avail - three - (c3w - C3_MIN));
            c4w = c3w;
            total = c1w + RIGHT_W + c3w + 2 * COL_GAP;
            c1x = Math.max(LEFT_X, (this.width - total) / 2);
            c2x = c1x + c1w + COL_GAP;
            c3x = c4x = c2x + RIGHT_W + COL_GAP;
            c3y = COL_Y;
            c4y = -1; // under HOW IT BLENDS — set once that column is built
        } else {
            c1w = Math.max(90, Math.min(C1_MAX, avail - RIGHT_W - COL_GAP));
            total = c1w + RIGHT_W + COL_GAP;
            c1x = Math.max(LEFT_X, (this.width - total) / 2);
            c2x = c1x + c1w + COL_GAP;
            c3x = c4x = c1x;
            c3w = c4w = total;
            c3y = leftColumnsBottom() + 12;
            c4y = -1;
        }
    }

    /** Base y under PICK BLOCKS / ARRANGE (both end at the same line). */
    private int leftColumnsBottom() { return stripYBase() + stripH + 15 + 22; }

    private int paneX() { return c1x; }
    private int paneW() { return c1w; }
    private int pinYBase() { return COL_Y + Ui.SECTION_H + 10 + Ui.ROW_H + 4; }
    private int listYBase() { return pinYBase() + PINNED * ROW + 3; }
    private int listY() { return listYBase() - scroll; }
    private int visibleListRows() { return Math.max(1, listH / ROW); }
    private int maxListScroll() { return Math.max(0, leftRows.size() - PINNED - visibleListRows()); }

    private int rightX() { return c2x; }
    private int stripX() { return rightX() + HANDLE_W; }
    private int labelX() { return stripX() + STRIP_W + 4; }
    private int stripYBase() { return COL_Y + Ui.SECTION_H + 11; } // the START caption above
    private int stripY() { return stripYBase() - scroll; }

    /** {x, y (scrolled), w, h} of the preview box. */
    private int[] previewRect() {
        return new int[]{c4x, previewYBase - scroll, c4w, previewH};
    }

    /** Base y of the lowest thing on the page. */
    private int contentBottom() {
        int previewBottom = previewYBase + previewH + 4 + 10 + 30; // status + adjacent-Auto warning
        return Math.max(Math.max(leftColumnsBottom(), settingsBottomBase), previewBottom);
    }

    private int viewBottom() { return this.height - 4; }

    private int maxScroll() {
        return Math.max(0, contentBottom() + 8 - viewBottom());
    }

    // ---- init -----------------------------------------------------------------------------------

    @Override
    protected void init() {
        scrolledWidgets.clear();
        helpSpots.clear();
        segRows.clear();
        togRows.clear();
        captions.clear();
        refreshAvailable();
        rebuildLeftRows();
        refreshSegmentDisplay();
        layout();

        // Editor bar: the name box lives in it (not scrolled).
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

        // 1 PICK BLOCKS: the per-palette source switch over the pinned Auto rows + list.
        int y = COL_Y + Ui.SECTION_H;
        captions.add(new Caption(c1x, y, c1w, () -> "Blocks from", null, false));
        sourceSeg = seg(List.of("Hotbar", "Inventory", "Both"), editing.source.ordinal(), c1x, y + 10, c1w, i -> {
            editing.source = GradientSource.values()[i];
            dirty = true;
            refreshAvailable();
            rebuildLeftRows();
            refreshSegmentDisplay();
        });
        sectionHelp(c1x, c1w, COL_Y, () -> "Blocks from picks where this palette's blocks come from "
                + "(the list here, and the pool Auto segments draw from). Double-click a block or drag "
                + "it onto the strip to add it. Auto colour / Auto brightness add a wildcard segment "
                + "that's filled in when you paint. Right-click a block to ban it from Auto (red).");

        // 2 ARRANGE: the strip is drawn, not a widget.
        sectionHelp(c2x, RIGHT_W, COL_Y, () -> "The palette itself: START at the top, END at the bottom. "
                + "Drag a segment to reorder it, drag it out sideways to remove it, drag the handles on "
                + "the left to resize. Click a segment to select it: ↑ ↓ move it, Delete removes it. A red "
                + "outline means the block isn't in Blocks from.");

        // 3 HOW IT BLENDS.
        int rx = c3x, rw = c3w;
        sectionHelp(rx, rw, c3y, () -> "Order re-sorts the strip. Curve shapes where the gradient changes "
                + "fastest. Sizing sets how long a placed gradient runs. Reordering segments by hand turns "
                + "Order Custom; dragging a stop turns Curve Custom. Variation, Chaos and Step length add "
                + "randomness to both Gradient and Noise paint.");
        y = c3y + Ui.SECTION_H;
        captions.add(new Caption(rx, y, rw, () -> "Order",
                () -> editing.order == PaletteOrder.CUSTOM ? "Custom · arranged by hand" : "", false));
        orderSeg = seg(fit(rw, List.of("Colour ↑", "Colour ↓", "Bright ↑", "Bright ↓"),
                List.of("Col ↑", "Col ↓", "Bri ↑", "Bri ↓")), orderIndex(), rx, y + 10, rw, i -> {
            sortSegments(ORDERS[i]);
            resetPreview();
        });
        switchHelp(orderSeg, rx, y + 10, rw, i -> orderText(i < 0 ? PaletteOrder.CUSTOM : ORDERS[i]));
        y += 10 + Ui.ROW_H + 6;
        captions.add(new Caption(rx, y, rw, () -> "Curve",
                () -> editing.curve == CurveFunction.CUSTOM ? "Custom · dragged stops" : "", false));
        curveSeg = seg(fit(rw, List.of("Linear", "Ease in", "Ease out", "In/out", "Step"),
                List.of("Lin", "In", "Out", "In/out", "Step")), curveIndex(), rx, y + 10, rw, i -> {
            editing.curve = CURVES[i];
            dirty = true;
            resetPreview();
        });
        switchHelp(curveSeg, rx, y + 10, rw, i -> curveText(i < 0 ? CurveFunction.CUSTOM : CURVES[i]));
        y += 10 + Ui.ROW_H + 6;
        captions.add(new Caption(rx, y, rw, () -> "Sizing", null, false));
        sizingSeg = seg(fit(rw, List.of("Min blocks", "Fill space", "Set steps"), List.of("Min", "Fill", "Steps")),
                editing.sizing.ordinal(), rx, y + 10, rw, i -> {
            editing.sizing = SizingMode.values()[i];
            dirty = true;
            rebuildWidgets(); // the Steps slider shows/hides
        });
        switchHelp(sizingSeg, rx, y + 10, rw, i -> switch (SizingMode.values()[Math.max(0, i)]) {
            case MIN_BLOCKS -> "Min blocks: the shortest run that fits the segment ratios";
            case FILL_SPACE -> "Fill space: expands out to the end marker or first block";
            case SET_STEPS -> "Set steps: a fixed length (the Steps slider below)";
        });
        y += 10 + Ui.ROW_H + 6;
        if (editing.sizing == SizingMode.SET_STEPS) {
            y = addSlider(rx, y, rw, (editing.steps - 1) / 15.0,
                    v -> "Steps: " + (1 + (int) Math.round(v * 15)),
                    v -> editing.steps = 1 + (int) Math.round(v * 15),
                    "The gradient stretches or shrinks to this many blocks");
        }
        // Variation: the unified window + chance model — range switch half, chance slider half.
        int halfW = (rw - 4) / 2;
        varSeg = seg(List.of("Off", "±1", "±2", "±3"), editing.variationWindow, rx, y + 2, halfW, i -> {
            editing.variationWindow = i;
            dirty = true;
            resetPreview();
        });
        addScrolled(new EditSlider(rx + halfW + 4, y, rw - halfW - 4,
                editing.variationChance / 100.0,
                v -> ((int) Math.round(v * 100)) + "%",
                v -> {
                    editing.variationChance = (int) Math.round(v * 100);
                    dirty = true;
                    resetPreview();
                }));
        captions.add(new Caption(rx, y + 22, rw, () ->
                editing.variationWindow == 0 || editing.variationChance == 0
                        ? "Variation off: cells place their segment's block"
                        : "Variation: " + editing.variationChance + "% swap to a colour ±"
                                + editing.variationWindow + " away", null, true));
        y += 34;
        y = addSlider(rx, y, rw, editing.chaos, v -> "Chaos: " + pct(v),
                v -> { editing.chaos = v; resetPreview(); },
                "How ragged the bands are: repeat or skip a step");
        y = addSlider(rx, y, rw, editing.stepWobble, v -> "Step length: " + pct(v),
                v -> { editing.stepWobble = v; resetPreview(); },
                "Wobble: bands run longer or shorter");

        // NOISE (collapsible): only Noise paint reads these.
        y += 4;
        noiseHeadingY = y;
        sectionHelp(rx, rw, y, () -> "Only Noise paint uses these. Type picks the noise shape, Scale its "
                + "feature size in blocks (lock XYZ for round blobs, unlock to stretch along an axis), and "
                + "Seed which pattern you get — the same seed always paints the same pattern. Click the "
                + "heading to fold this group away.");
        y += Ui.SECTION_H;
        if (noiseOpen) {
            captions.add(new Caption(rx, y, rw, () -> "Type", null, false));
            noiseTypeSeg = seg(List.of("Smooth", "Perlin", "Fractal"), editing.noiseType.ordinal(),
                    rx, y + 10, rw, i -> {
                editing.noiseType = NoiseType.values()[i];
                dirty = true;
            });
            switchHelp(noiseTypeSeg, rx, y + 10, rw, i -> switch (NoiseType.values()[Math.max(0, i)]) {
                case SMOOTH -> "Smooth noise: soft rounded blobs";
                case PERLIN -> "Perlin noise: natural ridged shapes";
                case FRACTAL -> "Fractal noise: layered detail";
            });
            y += 10 + Ui.ROW_H + 6;
            Toggle lock = new Toggle("Lock XYZ", null, false, () -> editing.noiseLock, on -> {
                editing.noiseLock = on;
                dirty = true;
                rebuildWidgets();
            });
            togRows.add(new TogRow(lock, rx, y, rw));
            y += Ui.ROW_H + 4;
            if (editing.noiseLock) {
                y = addSlider(rx, y, rw, (clampScale(editing.noiseScaleX) - 1) / 14.0,
                        v -> "Scale: " + (1 + (int) Math.round(v * 14)),
                        v -> { double s = 1 + Math.round(v * 14); editing.noiseScaleX = editing.noiseScaleY = editing.noiseScaleZ = s; },
                        "Feature size, in blocks");
            } else {
                y = addSlider(rx, y, rw, (clampScale(editing.noiseScaleX) - 1) / 14.0,
                        v -> "Scale X: " + (1 + (int) Math.round(v * 14)),
                        v -> editing.noiseScaleX = 1 + Math.round(v * 14), null);
                y = addSlider(rx, y, rw, (clampScale(editing.noiseScaleY) - 1) / 14.0,
                        v -> "Scale Y: " + (1 + (int) Math.round(v * 14)),
                        v -> editing.noiseScaleY = 1 + Math.round(v * 14), null);
                y = addSlider(rx, y, rw, (clampScale(editing.noiseScaleZ) - 1) / 14.0,
                        v -> "Scale Z: " + (1 + (int) Math.round(v * 14)),
                        v -> editing.noiseScaleZ = 1 + Math.round(v * 14),
                        "Feature size per axis, in blocks");
            }
            int randW = Math.max(46, this.font.width("Random") + 10);
            seedBox = new EditBox(this.font, rx, y, rw - randW - 4, 20, Component.literal("Seed"));
            seedBox.setHint(Component.literal("Seed…"));
            seedBox.setMaxLength(32);
            seedBox.setValue(editing.noiseSeed == null ? "" : editing.noiseSeed);
            seedBox.setResponder(s -> {
                editing.noiseSeed = s;
                dirty = true;
            });
            addScrolled(seedBox);
            addScrolled(Button.builder(Component.literal("Random"), b -> {
                seedBox.setValue(String.valueOf(new Random().nextInt(100000)));
            }).bounds(rx + rw - randW, y, randW, 20).build());
            y += 24;
        } else {
            seedBox = null;
            noiseTypeSeg = null;
        }
        settingsBottomBase = y;

        // PREVIEW.
        if (c4y < 0) c4y = settingsBottomBase + 12;
        sectionHelp(c4x, c4w - 18, c4y, () -> "As gradient shows a column painted START (top) to END "
                + "(bottom); As noise shows a cube of Noise paint — drag a face to move through the noise. "
                + "⛶ enlarges the preview; Esc closes it.");
        addScrolled(Button.builder(Component.literal("⛶"), b -> expandedPreview = previewMode + 1)
                .bounds(c4x + c4w - 16, c4y - 3, 16, 13).build());
        previewSeg = seg(List.of("As gradient", "As noise"), previewMode, c4x, c4y + Ui.SECTION_H, c4w,
                i -> previewMode = i);
        previewYBase = c4y + Ui.SECTION_H + Ui.ROW_H + 4;
        previewH = Math.max(90, Math.min(170, c4w * 3 / 4));

        leftScroll = Math.min(leftScroll, maxListScroll());
        scroll = Math.min(scroll, maxScroll());
        applyScroll();
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

    /**
     * A switch's hover help: {@code text} describes the choice the mouse is on (its index), so
     * hovering a choice that is off explains that one, not the one that is on. Off the cells (the
     * margin beside the switch) it describes the chosen one; -1 = none chosen.
     */
    private void switchHelp(Segmented s, int x, int baseY, int w, java.util.function.IntFunction<String> text) {
        helpSpots.add(new HelpSpot(x, baseY, w, Ui.ROW_H, () -> {
            int cell = s.cellAt(helpMx, helpMy);
            boolean on = cell < 0 || cell == s.selected();
            return text.apply(cell < 0 ? s.selected() : cell) + (on ? "" : " · LMB switch to it");
        }, false));
    }

    /** A section's one circled-? at the right end of its heading line. */
    private void sectionHelp(int x, int w, int headingY, Supplier<String> text) {
        helpSpots.add(new HelpSpot(x + w - 12, headingY - 5, 0, 14, text, true));
    }

    private int orderIndex() {
        for (int i = 0; i < ORDERS.length; i++) if (ORDERS[i] == editing.order) return i;
        return -1; // Custom: nothing lit
    }

    private int curveIndex() {
        for (int i = 0; i < CURVES.length; i++) if (CURVES[i] == editing.curve) return i;
        return -1; // Custom: nothing lit
    }

    private static double clampScale(double s) { return Math.max(1, Math.min(15, s)); }

    private static String pct(double v) { return Math.round(v * 100) + "%"; }

    private static String orderText(PaletteOrder o) {
        return switch (o) {
            case COLOR_ASC -> "Colour ↑: sort the strip by colour, ascending";
            case COLOR_DESC -> "Colour ↓: sort the strip by colour, descending";
            case BRIGHTNESS_ASC -> "Bright ↑: sort the strip by brightness, ascending";
            case BRIGHTNESS_DESC -> "Bright ↓: sort the strip by brightness, descending";
            case CUSTOM -> "Custom order — arranged by hand. Pick a sort to re-sort the strip";
        };
    }

    private static String curveText(CurveFunction c) {
        return switch (c) {
            case LINEAR -> "Linear: even change end to end";
            case EASE_IN -> "Ease in: holds the start colour longer";
            case EASE_OUT -> "Ease out: reaches the end colour sooner";
            case EASE_IN_OUT -> "In/out: lingers at both ends";
            case STEP -> "Step: hard quantised bands";
            case CUSTOM -> "Custom: exactly the stop positions you dragged. Pick a curve to replace them";
        };
    }

    private <T extends AbstractWidget> T addScrolled(T w) {
        scrolledWidgets.add(new ScrolledWidget(w, w.getY()));
        return addRenderableWidget(w);
    }

    /** A slider with its one-line grey explanation under it; returns the y below both. */
    private int addSlider(int x, int y, int w, double initial,
                          java.util.function.DoubleFunction<String> label,
                          java.util.function.DoubleConsumer onChange, String explain) {
        addScrolled(new EditSlider(x, y, w, initial, label, v -> {
            onChange.accept(v);
            dirty = true;
        }));
        if (explain == null) return y + 24;
        captions.add(new Caption(x, y + 22, w, () -> explain, null, true));
        return y + 34;
    }

    private void applyScroll() {
        for (ScrolledWidget s : scrolledWidgets) {
            int y = s.baseY() - scroll;
            s.widget().setY(y);
            s.widget().visible = y >= BAR_H + 2 && y + s.widget().getHeight() <= viewBottom();
        }
    }

    /** Whether a scrolled row at base y (h tall) is fully on screen below the bar. */
    private boolean rowVisible(int baseY, int h) {
        int y = baseY - scroll;
        return y >= BAR_H + 2 && y + h <= viewBottom();
    }

    // ---- toggle enablement ----------------------------------------------------------------------

    private int staticCount() {
        int n = 0;
        for (PaletteSegment s : editing.segments) {
            if (!s.isAutomatic()) n++;
        }
        return n;
    }

    private boolean orderEnabled() { return staticCount() >= 2; }

    private boolean curveEnabled() { return editing.segments.size() >= 3; }

    // ---- data helpers ---------------------------------------------------------------------------

    private void refreshAvailable() {
        availableIds = new HashSet<>();
        if (this.minecraft == null || this.minecraft.player == null) return;
        var items = this.minecraft.player.getInventory().getNonEquipmentItems();
        int from = editing.source == GradientSource.INVENTORY ? 9 : 0;
        int to = Math.min(editing.source == GradientSource.HOTBAR ? 9 : 36, items.size());
        for (int slot = from; slot < to; slot++) {
            ItemStack st = items.get(slot);
            if (st.getItem() instanceof BlockItem bi && !bi.getBlock().defaultBlockState().isAir()) {
                Identifier id = BuiltInRegistries.ITEM.getKey(st.getItem());
                if (id != null) availableIds.add(id.toString());
            }
        }
    }

    /** Pinned Automatic rows on top, then the source blocks sorted by colour. */
    private void rebuildLeftRows() {
        leftRows = new ArrayList<>();
        leftRows.add(new LRow(AutoMode.COLOR, "", ItemStack.EMPTY, AutoMode.COLOR.label(), 0));
        leftRows.add(new LRow(AutoMode.BRIGHTNESS, "", ItemStack.EMPTY, AutoMode.BRIGHTNESS.label(), 0));
        List<LRow> blocks = new ArrayList<>();
        if (this.minecraft != null && this.minecraft.player != null) {
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
                blocks.add(new LRow(null, id.toString(), st.copy(),
                        st.getHoverName().getString(), rgb));
            }
        }
        blocks.sort(java.util.Comparator.comparingLong(r -> ColorOrder.colorSortKey(r.rgb())));
        leftRows.addAll(blocks);
    }

    private void refreshSegmentDisplay() {
        segStacks.clear();
        segNames.clear();
        segMissing.clear();
        for (PaletteSegment s : editing.segments) {
            if (s.isAutomatic()) {
                segStacks.add(ItemStack.EMPTY);
                segNames.add(s.autoLabel());
                segMissing.add(false);
            } else {
                ItemStack st = GradientScreen.stackOfId(s.block);
                segStacks.add(st);
                segNames.add(st.isEmpty() ? s.block : st.getHoverName().getString());
                segMissing.add(!availableIds.contains(s.block));
            }
        }
        computeSegTints();
        resetPreview();
    }

    /** Memoize the shared representative Automatic tints (see {@link PaletteTints}). */
    private void computeSegTints() {
        segTints.clear();
        for (int t : PaletteTints.compute(editing.segments)) segTints.add(t);
    }

    private void resetPreview() {
        cylCache = null;
    }

    // ---- segment model operations ---------------------------------------------------------------

    /** Current segment sizes (fractions, sum 1) — from stops when CUSTOM, else from the curve. */
    private List<Double> currentSizes() {
        int n = editing.segments.size();
        double[] b = displayBounds(n);
        List<Double> sizes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double lo = i == 0 ? 0 : b[i - 1];
            double hi = i == n - 1 ? 1 : b[i];
            sizes.add(Math.max(0.001, hi - lo));
        }
        return sizes;
    }

    private void setSizes(List<Double> sizes) {
        double sum = 0;
        for (double s : sizes) sum += s;
        editing.stops.clear();
        double acc = 0;
        for (int i = 0; i < sizes.size() - 1; i++) {
            acc += sizes.get(i) / sum;
            editing.stops.add(acc);
        }
        editing.curve = CurveFunction.CUSTOM;
    }

    private void addSegment(PaletteSegment seg) {
        insertSegmentAt(editing.segments.size(), seg);
    }

    /** Insert at {@code idx} with an equal share; the rest rescale proportionally. */
    private void insertSegmentAt(int idx, PaletteSegment seg) {
        idx = Math.max(0, Math.min(editing.segments.size(), idx));
        boolean custom = editing.curve == CurveFunction.CUSTOM && !editing.segments.isEmpty();
        List<Double> sizes = custom ? currentSizes() : null;
        editing.segments.add(idx, seg);
        if (custom) {
            int n = sizes.size();
            List<Double> next = new ArrayList<>();
            for (double s : sizes) next.add(s * n / (n + 1.0));
            next.add(Math.min(idx, next.size()), 1.0 / (n + 1));
            setSizes(next);
        }
        selectedSeg = idx;
        dirty = true;
        refreshSegmentDisplay();
    }

    private void removeSegment(int idx) {
        if (idx < 0 || idx >= editing.segments.size()) return;
        boolean custom = editing.curve == CurveFunction.CUSTOM && editing.segments.size() > 1;
        List<Double> sizes = custom ? currentSizes() : null;
        editing.segments.remove(idx);
        if (custom) {
            sizes.remove(idx);
            setSizes(sizes);
        } else {
            editing.stops.clear();
        }
        if (selectedSeg >= editing.segments.size()) selectedSeg = editing.segments.size() - 1;
        dirty = true;
        refreshSegmentDisplay();
    }

    /** Swap neighbours (a manual reorder) — the only way into Order: Custom. */
    private void swapSegments(int a, int b) {
        int n = editing.segments.size();
        if (a < 0 || b < 0 || a >= n || b >= n || a == b) return;
        boolean custom = editing.curve == CurveFunction.CUSTOM;
        List<Double> sizes = custom ? currentSizes() : null;
        Collections.swap(editing.segments, a, b);
        if (custom) {
            Collections.swap(sizes, a, b);
            setSizes(sizes);
        }
        editing.order = PaletteOrder.CUSTOM;
        dirty = true;
        refreshSegmentDisplay();
    }

    /** Re-sort every segment by the chosen key; Automatic segments rank as a middle grey. */
    private void sortSegments(PaletteOrder ord) {
        editing.order = ord;
        boolean custom = editing.curve == CurveFunction.CUSTOM;
        List<Double> sizes = custom ? currentSizes() : null;

        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < editing.segments.size(); i++) idx.add(i);
        java.util.Comparator<Integer> cmp = java.util.Comparator.comparingLong(i -> {
            PaletteSegment s = editing.segments.get(i);
            int rgb = 0x808080; // Automatic sorts as a middle grey
            if (!s.isAutomatic()) {
                Block b = Gradient.blockOfItemId(s.block);
                if (b != null) rgb = BlockTextures.gradientValue(b, null, GradientMode.COLOR, 0.5);
            }
            return ord.byBrightness() ? ColorOrder.brightnessSortKey(rgb) : ColorOrder.colorSortKey(rgb);
        });
        idx.sort(ord.descending() ? cmp.reversed() : cmp);

        List<PaletteSegment> newSegs = new ArrayList<>();
        List<Double> newSizes = custom ? new ArrayList<>() : null;
        for (int i : idx) {
            newSegs.add(editing.segments.get(i));
            if (custom) newSizes.add(sizes.get(i));
        }
        editing.segments.clear();
        editing.segments.addAll(newSegs);
        if (custom) setSizes(newSizes);
        dirty = true;
        refreshSegmentDisplay();
    }

    // ---- strip geometry -------------------------------------------------------------------------

    /** Raw-t boundary fractions of the strip's segments (stops for CUSTOM, curve scan otherwise). */
    private double[] displayBounds(int count) {
        double[] b = new double[Math.max(0, count - 1)];
        if (editing.curve == CurveFunction.CUSTOM && editing.stops.size() == count - 1) {
            for (int i = 0; i < b.length; i++) b[i] = editing.stops.get(i);
            return b;
        }
        int samples = 400, pos = 0;
        for (int i = 0; i <= samples && pos < b.length; i++) {
            double t = i / (double) samples;
            int idx = PaletteMath.indexFor(editing.curve.apply(t),
                    PaletteMath.segmentBounds(count, CurveFunction.LINEAR, List.of()));
            while (pos < idx && pos < b.length) b[pos++] = t;
        }
        while (pos < b.length) b[pos++] = 1.0;
        return b;
    }

    /** Pixel y of each strip segment edge (size count+1), at the current scroll. */
    private int[] stripEdges(int count) {
        double[] b = displayBounds(count);
        int[] edges = new int[count + 1];
        edges[0] = stripY();
        edges[count] = stripY() + stripH;
        for (int k = 0; k < count - 1; k++) edges[k + 1] = stripY() + (int) Math.round(b[k] * stripH);
        return edges;
    }

    /** Strip edges with the dragged-out segment removed and the rest renormalized (snap preview). */
    private int[] stripEdgesWithout(int count, int skip) {
        List<Double> sizes = currentSizes();
        double removed = sizes.get(skip);
        int[] edges = new int[count + 1];
        edges[0] = stripY();
        double acc = 0;
        int pos = 1;
        for (int i = 0; i < count; i++) {
            if (i == skip) continue;
            acc += sizes.get(i) / (1 - removed);
            edges[pos++] = stripY() + (int) Math.round(Math.min(1, acc) * stripH);
        }
        edges[count] = stripY() + stripH;
        return edges;
    }

    /** All stop handles sit left of the strip, pentagon points at the boundary line. */
    private int stopHandleAt(double mx, double my) {
        int count = editing.segments.size();
        if (count < 2) return -1;
        int[] edges = stripEdges(count);
        int hx = stripX() - HANDLE_W;
        for (int k = 1; k < count; k++) {
            if (mx >= hx - 1 && mx <= stripX() && my >= edges[k] - 6 && my <= edges[k] + 6) return k - 1;
        }
        return -1;
    }

    private int segmentAt(double mx, double my) {
        int count = editing.segments.size();
        if (count == 0) return -1;
        int sx = stripX();
        if (mx < sx || mx > sx + STRIP_W) return -1;
        int[] edges = stripEdges(count);
        for (int k = 0; k < count; k++) {
            if (my >= edges[k] && my < edges[k + 1]) return k;
        }
        return -1;
    }

    /** Insertion index for a drop at {@code my}: before the first segment whose centre is below. */
    private int insertIndexAt(double my) {
        int count = editing.segments.size();
        if (count == 0) return 0;
        int[] edges = stripEdges(count);
        for (int k = 0; k < count; k++) {
            double center = (edges[k] + edges[k + 1]) / 2.0;
            if (my < center) return k;
        }
        return count;
    }

    // ---- input ----------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x(), my = event.y();
        if (pendingExit != null) {
            return handleDiscardClick(mx, my, event.button());
        }
        if (expandedPreview != 0) {
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                if (inCloseX(mx, my)) expandedPreview = 0;
                else if (expandedPreview == 2) dragFace = faceAt(mx, my, true);
            }
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
        // Circled-? icons show their popup immediately (it hides again on mouse-away).
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

        // The switches and toggles.
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (SegRow r : List.copyOf(segRows)) {
                if (!rowVisible(r.baseY(), Ui.ROW_H)) continue;
                r.seg().setBounds(r.x(), r.baseY() - scroll, r.w());
                if (r.seg().click(mx, my)) return true;
            }
            for (TogRow r : List.copyOf(togRows)) {
                if (!rowVisible(r.baseY(), Ui.ROW_H)) continue;
                r.toggle().setBounds(r.x(), r.baseY() - scroll, r.w());
                if (r.toggle().click(mx, my, this.font)) return true;
            }
            // The NOISE heading folds its group.
            int ny = noiseHeadingY - scroll;
            if (mx >= c3x && mx < c3x + c3w - 14 && my >= ny - 2 && my < ny + 11) {
                noiseOpen = !noiseOpen;
                rebuildWidgets();
                return true;
            }
        }

        // Stop handles.
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            int h = stopHandleAt(mx, my);
            if (h >= 0) {
                beginStopDrag(h);
                return true;
            }
        }
        // Segment body: press begins a potential drag; release without movement selects.
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            int seg = segmentAt(mx, my);
            if (seg >= 0) {
                dragSeg = seg;
                dragSegMoved = false;
                dragOut = false;
                grabOffset = my - stripEdges(editing.segments.size())[seg];
                pressX = mx;
                dragMouseX = mx;
                dragMouseY = my;
                return true;
            }
        }
        // Noise-preview pan (As noise, collapsed).
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && previewMode == 1 && !editing.segments.isEmpty()) {
            int[] nb = previewRect();
            if (mx >= nb[0] && mx <= nb[0] + nb[2] && my >= nb[1] && my <= nb[1] + nb[3]) {
                dragFace = faceAt(mx, my, false);
                return true;
            }
        }
        // Block pane: press arms a drag-into-strip; double-click appends; right-click bans.
        if (paneListClick(mx, my, event.button(), doubled)) return true;
        return false;
    }

    /**
     * The leftRows index under the mouse: the two pinned Auto rows, then the scrolling block
     * list. -1 = not over a row (but maybe still inside the pane), -2 = outside the pane.
     */
    private int rowAt(double mx, double my) {
        int cx = paneX(), lw = paneW();
        if (mx < cx || mx > cx + lw) return -2;
        int py = pinYBase() - scroll;
        if (my >= py && my < py + PINNED * ROW) {
            if (my < BAR_H + 1) return -2;
            return (int) ((my - py) / ROW);
        }
        int ly = listY();
        if (my < ly || my > ly + listH || my < BAR_H + 1 || my > viewBottom()) return -2;
        int idx = PINNED + leftScroll + (int) ((my - ly) / ROW);
        return idx < leftRows.size() ? idx : -1;
    }

    private boolean paneListClick(double mx, double my, int button, boolean doubled) {
        int idx = rowAt(mx, my);
        if (idx == -2) return false;
        if (idx < 0) return true;
        LRow row = leftRows.get(idx);
        String key = row.auto() != null ? "auto:" + row.auto() : row.id();
        if (button == InputConstants.MOUSE_BUTTON_RIGHT && row.auto() == null) {
            // Right-click: ban/unban from Automatic segments (saved on the palette).
            if (!editing.autoExclude.remove(row.id())) editing.autoExclude.add(row.id());
            dirty = true;
            return true;
        }
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            long now = System.currentTimeMillis();
            boolean dbl = doubled || (key.equals(lastRowClickId) && now - lastRowClickMs < 400);
            lastRowClickId = dbl ? null : key;
            lastRowClickMs = now;
            if (dbl) {
                addSegment(rowSegment(row));
            } else {
                // Arm a drag toward the strip; a plain release cancels it.
                paneDragIdx = idx;
                paneDragging = false;
                panePressX = mx;
                panePressY = my;
                paneDragX = mx;
                paneDragY = my;
            }
            return true;
        }
        return true;
    }

    private static PaletteSegment rowSegment(LRow row) {
        return row.auto() != null ? PaletteSegment.ofAuto(row.auto()) : PaletteSegment.ofBlock(row.id());
    }

    private void beginStopDrag(int idx) {
        int count = editing.segments.size();
        if (editing.curve != CurveFunction.CUSTOM || editing.stops.size() != count - 1) {
            double[] cur = displayBounds(count);
            editing.stops.clear();
            for (double v : cur) editing.stops.add(v);
            editing.curve = CurveFunction.CUSTOM;
        }
        dragStop = idx;
        dirty = true;
        resetPreview();
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        double mx = event.x(), my = event.y();
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && dragStop >= 0) {
            int count = editing.segments.size();
            if (editing.stops.size() == count - 1 && dragStop < editing.stops.size()) {
                double v = (my - stripY()) / (double) stripH;
                double min = 4.0 / stripH;
                double lo = (dragStop == 0 ? 0 : editing.stops.get(dragStop - 1)) + min;
                double hi = (dragStop == editing.stops.size() - 1 ? 1 : editing.stops.get(dragStop + 1)) - min;
                editing.stops.set(dragStop, Math.max(lo, Math.min(hi, v)));
                resetPreview();
            }
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && paneDragIdx >= 0) {
            paneDragX = mx;
            paneDragY = my;
            if (!paneDragging && (Math.abs(mx - panePressX) > 4 || Math.abs(my - panePressY) > 4)) {
                paneDragging = true;
            }
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && dragSeg >= 0) {
            dragMouseX = mx;
            dragMouseY = my;
            if (!dragSegMoved && (Math.abs(mx - pressX) > 3 || Math.abs(my - (stripEdges(editing.segments.size())[dragSeg] + grabOffset)) > 3)) {
                dragSegMoved = true;
            }
            dragOut = Math.abs(mx - pressX) > STRIP_W + 8;
            if (!dragOut && dragSegMoved) {
                int count = editing.segments.size();
                int[] edges = stripEdges(count);
                int segH = edges[dragSeg + 1] - edges[dragSeg];
                double visualCenter = my - grabOffset + segH / 2.0;
                if (dragSeg > 0) {
                    double above = (edges[dragSeg - 1] + edges[dragSeg]) / 2.0;
                    if (visualCenter < above) {
                        swapSegments(dragSeg - 1, dragSeg);
                        selectedSeg = --dragSeg;
                        return true;
                    }
                }
                if (dragSeg < count - 1) {
                    double below = (edges[dragSeg + 1] + edges[dragSeg + 2]) / 2.0;
                    if (visualCenter > below) {
                        swapSegments(dragSeg, dragSeg + 1);
                        selectedSeg = ++dragSeg;
                        return true;
                    }
                }
            }
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && dragFace != null) {
            double mdx = -dragX, mdy = -dragY;
            switch (dragFace) {
                case TOP -> {
                    previewOffX += mdx / (2 * ISO_X) + mdy / (2 * ISO_DOWN);
                    previewOffZ += mdy / (2 * ISO_DOWN) - mdx / (2 * ISO_X);
                }
                case RIGHT -> {
                    double dz = -mdx / ISO_X;
                    previewOffZ += dz;
                    previewOffY += (dz * ISO_DOWN - mdy) / ISO_UP;
                }
                case LEFT -> {
                    double dxw = mdx / ISO_X;
                    previewOffX += dxw;
                    previewOffY += (dxw * ISO_DOWN - mdy) / ISO_UP;
                }
            }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && dragStop >= 0) {
            dragStop = -1;
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && paneDragIdx >= 0) {
            if (paneDragging && paneDragX >= stripX() - HANDLE_W - 4
                    && paneDragX <= stripX() + STRIP_W + 8) {
                insertSegmentAt(insertIndexAt(paneDragY), rowSegment(leftRows.get(paneDragIdx)));
            }
            paneDragIdx = -1;
            paneDragging = false;
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && dragSeg >= 0) {
            if (dragOut && dragSegMoved) {
                removeSegment(dragSeg);
            } else if (!dragSegMoved) {
                selectedSeg = dragSeg; // plain click — select (white outline, arrow keys move)
            }
            dragSeg = -1;
            dragOut = false;
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && dragFace != null) {
            dragFace = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (pendingExit != null || expandedPreview != 0) return true;
        int ly = listY();
        if (mouseX >= paneX() && mouseX <= paneX() + paneW() && mouseY >= ly && mouseY <= ly + listH) {
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
            if (key == 256) pendingExit = null; // Esc keeps editing
            return true;
        }
        if (expandedPreview != 0 && key == 256) {
            expandedPreview = 0;
            return true;
        }
        if (clickedSpot != null && key == 256) {
            clickedSpot = null;
            return true;
        }
        boolean typing = (nameBox != null && nameBox.isFocused()) || (seedBox != null && seedBox.isFocused());
        if (!typing && selectedSeg >= 0 && selectedSeg < editing.segments.size()) {
            if (key == 265) { // up
                if (selectedSeg > 0) {
                    swapSegments(selectedSeg - 1, selectedSeg);
                    selectedSeg--;
                }
                return true;
            }
            if (key == 264) { // down
                if (selectedSeg < editing.segments.size() - 1) {
                    swapSegments(selectedSeg, selectedSeg + 1);
                    selectedSeg++;
                }
                return true;
            }
            if (key == 261 || key == 259) { // delete / backspace
                removeSegment(selectedSeg);
                return true;
            }
        }
        if (key == 256) { // Esc = cancel (confirming unsaved changes)
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
            if (p.id.equals(editing.id)) continue; // renaming ourselves is fine
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
        PaletteStore.upsert(editing);
        PaletteStore.setActive(editing.id); // saving also makes it the in-use palette
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
            pendingExit = null; // Keep editing (or click-away)
        }
        return true;
    }

    private int[] confirmBox() {
        int w = 240, h = 64;
        return new int[]{(this.width - w) / 2, (this.height - h) / 2, w, h};
    }

    private int[] confirmBtn(boolean discard) {
        int[] b = confirmBox();
        int bw = (b[2] - 3 * 8) / 2;
        int x = discard ? b[0] + 8 : b[0] + 2 * 8 + bw;
        return new int[]{x, b[1] + b[3] - 26, bw, 18};
    }

    private static boolean inRect(int[] r, double mx, double my) {
        return mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    @Override
    public void onClose() {
        // Done/Esc from the harness path — treat like Cancel with the unsaved-changes guard.
        attemptExit(backToList());
    }

    // ---- OS cursor ------------------------------------------------------------------------------

    /**
     * Move cursor over draggables (segments, pane rows), NS-resize over stop handles. 26.3's
     * cursor API is per-frame: request the shape during rendering and the frame applies it
     * (nothing requested = the default arrow), so there is no state to cache or reset.
     */
    private void updateCursor(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        CursorType cursor = cursorFor(mouseX, mouseY);
        if (cursor != null) g.requestCursor(cursor);
    }

    /** The cursor the pointer position wants, or null for the default arrow. */
    private CursorType cursorFor(int mouseX, int mouseY) {
        if (pendingExit != null || expandedPreview != 0) return null;
        if (dragStop >= 0) return CursorTypes.RESIZE_NS;
        if (dragSeg >= 0 || paneDragging) return CursorTypes.RESIZE_ALL;
        if (stopHandleAt(mouseX, mouseY) >= 0) return CursorTypes.RESIZE_NS;
        if (segmentAt(mouseX, mouseY) >= 0) return CursorTypes.RESIZE_ALL;
        if (mouseY > BAR_H && rowAt(mouseX, mouseY) >= 0) return CursorTypes.RESIZE_ALL;
        return null;
    }

    // ---- rendering ------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Full-page Ui.BACKDROP underlay so the UI reads against any world. The bar goes down
        // first so the name box (a widget) draws over it; scrolled widgets hide under the bar.
        g.fill(0, 0, this.width, this.height, Ui.BACKDROP);
        chrome.render(g, this.font, mouseX, mouseY, dirty, nameError);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.enableScissor(0, BAR_H + 1, this.width, viewBottom());
        renderSections(g);
        renderCaptions(g);
        renderParts(g, mouseX, mouseY);
        renderPaneList(g, mouseX, mouseY);
        renderStrip(g, mouseX, mouseY);
        renderPreviewArea(g);
        g.disableScissor();
        renderHelpIcons(g);
        renderPageScrollbar(g);
        renderDisabledHints(g, mouseX, mouseY);
        renderPaneDragChip(g);
        renderHelpPopup(g, mouseX, mouseY);
        if (expandedPreview != 0) renderExpandedOverlay(g, mouseX, mouseY);
        if (pendingExit != null) renderDiscardConfirm(g, mouseX, mouseY);
        updateCursor(g, mouseX, mouseY);
    }

    /** The column headings: numbered in working order, PREVIEW and NOISE un-numbered. */
    private void renderSections(GuiGraphicsExtractor g) {
        EditorChrome.section(g, this.font, c1x, COL_Y - scroll, c1w, "1", "Pick blocks", null, 14);
        EditorChrome.section(g, this.font, c2x, COL_Y - scroll, RIGHT_W, "2", "Arrange", null, 14);
        EditorChrome.section(g, this.font, c3x, c3y - scroll, c3w, "3", "How it blends", null, 14);
        int ny = noiseHeadingY - scroll;
        EditorChrome.section(g, this.font, c3x, ny, c3w, null, "Noise", "· only used by Noise paint", 26);
        g.text(this.font, noiseOpen ? "▾" : "▸", c3x + c3w - 22, ny, Ui.HINT);
        EditorChrome.section(g, this.font, c4x, c4y - scroll, c4w, null, "Preview", null, 34);
    }

    private void renderCaptions(GuiGraphicsExtractor g) {
        for (Caption c : captions) {
            int y = c.baseY() - scroll;
            String left = c.left().get();
            String right = c.right() == null ? "" : c.right().get();
            int rightW = right.isEmpty() ? 0 : this.font.width(right) + 6;
            g.text(this.font, this.font.plainSubstrByWidth(left, c.w() - rightW), c.x(), y,
                    c.hint() ? Ui.HINT : Ui.TEXT);
            if (!right.isEmpty()) {
                String r = this.font.plainSubstrByWidth(right, c.w() - this.font.width(left) - 6);
                g.text(this.font, r, c.x() + c.w() - this.font.width(r), y, Ui.HINT);
            }
        }
    }

    /** The switches and toggles, synced to the live state (manual reorders / stop drags show Custom). */
    private void renderParts(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        orderSeg.setSelected(orderIndex());
        orderSeg.setEnabled(orderEnabled());
        curveSeg.setSelected(curveIndex());
        curveSeg.setEnabled(curveEnabled());
        sizingSeg.setSelected(editing.sizing.ordinal());
        sourceSeg.setSelected(editing.source.ordinal());
        varSeg.setSelected(editing.variationWindow);
        previewSeg.setSelected(previewMode);
        if (noiseTypeSeg != null) noiseTypeSeg.setSelected(editing.noiseType.ordinal());
        boolean modal = pendingExit != null || expandedPreview != 0;
        int mx = modal ? -1 : mouseX, my = modal ? -1 : mouseY;
        for (SegRow r : segRows) {
            r.seg().setBounds(r.x(), r.baseY() - scroll, r.w());
            r.seg().render(g, this.font, mx, my);
        }
        for (TogRow r : togRows) {
            r.toggle().setBounds(r.x(), r.baseY() - scroll, r.w());
            r.toggle().render(g, this.font, mx, my);
        }
    }

    /** Short hover hints on the disabled switches, explaining how to enable them. */
    private void renderDisabledHints(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (pendingExit != null || expandedPreview != 0) return;
        String hint = null;
        if (!orderEnabled() && overSeg(orderSeg, mouseX, mouseY)) {
            hint = "Add at least 2 blocks to sort";
        } else if (!curveEnabled() && overSeg(curveSeg, mouseX, mouseY)) {
            hint = "Add at least 3 segments to shape the curve";
        }
        if (hint == null) return;
        int w = this.font.width(hint);
        int x = Math.min(mouseX + 10, this.width - w - 12);
        int y = mouseY + 10;
        g.nextStratum();
        g.fill(x - 3, y - 3, x + w + 3, y + 11, 0xF0101010);
        outline(g, x - 3, y - 3, w + 6, 14, 0x80FFE34D);
        g.text(this.font, hint, x, y, YELLOW);
    }

    private boolean overSeg(Segmented s, int mx, int my) {
        return s != null && my > BAR_H && my < viewBottom()
                && Ui.inside(mx, my, s.x(), s.y(), s.width(), s.height());
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

    // ---- block pane (PICK BLOCKS) ---------------------------------------------------------------

    private void renderPaneList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int cx = paneX(), lw = paneW();
        boolean modal = pendingExit != null || expandedPreview != 0;
        // The pinned Auto rows: dashed (wildcards), never scroll away from the list.
        int py = pinYBase() - scroll;
        for (int i = 0; i < PINNED && i < leftRows.size(); i++) {
            int ry = py + i * ROW;
            LRow row = leftRows.get(i);
            boolean hover = !modal && Ui.inside(mouseX, mouseY, cx, ry, lw, ROW - 1);
            if (hover) g.fill(cx + 1, ry, cx + lw - 1, ry + ROW - 1, HOVER_BG);
            EditorChrome.dashedOutline(g, cx, ry, lw, ROW - 1, 0xFF8A8A8A);
            PaletteListPanel.drawCrosshatch(g, cx + 2, ry, 16);
            g.text(this.font, this.font.plainSubstrByWidth(row.name(), lw - 26), cx + 21, ry + 4, 0xFFC8C8C8);
        }

        int ly = listY();
        int lh = listH;
        g.fill(cx, ly, cx + lw, ly + lh, 0x90000000);
        outline(g, cx, ly, lw, lh, 0xA0FFFFFF);
        g.enableScissor(cx, Math.max(BAR_H + 1, ly), cx + lw, Math.min(viewBottom(), ly + lh));
        int blocks = leftRows.size() - PINNED;
        if (blocks == 0) {
            g.text(this.font, this.font.plainSubstrByWidth("No blocks in " + editing.source.displayName(), lw - 8),
                    cx + 4, ly + 5, Ui.HINT);
        }
        for (int i = PINNED + leftScroll; i < leftRows.size(); i++) {
            int ry = ly + (i - PINNED - leftScroll) * ROW;
            if (ry >= ly + lh) break;
            LRow row = leftRows.get(i);
            boolean hover = !modal && mouseX >= cx && mouseX <= cx + lw && mouseY >= ry && mouseY < ry + ROW;
            if (hover) g.fill(cx + 1, ry, cx + lw - 1, ry + ROW, HOVER_BG);
            g.item(row.stack(), cx + 2, ry + 1);
            boolean banned = editing.autoExclude.contains(row.id());
            String name = this.font.plainSubstrByWidth(row.name(), lw - 26);
            g.text(this.font, name, cx + 21, ry + 5, banned ? Ui.MISSING : WHITE);
            if (banned) g.fill(cx + 20, ry + 8, cx + 22 + this.font.width(name), ry + 9, Ui.MISSING);
        }
        g.disableScissor();
        int maxLeft = maxListScroll();
        if (maxLeft > 0) {
            int trackX = cx + lw - 3;
            int thumbH = Math.max(8, lh * visibleListRows() / Math.max(1, blocks));
            int thumbY = ly + (int) ((lh - thumbH) * (double) leftScroll / maxLeft);
            g.fill(trackX, ly + 1, trackX + 2, ly + lh - 1, 0x30FFFFFF);
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0x90FFFFFF);
        }
        int hintY = ly + lh + 4;
        Ui.hint(g, this.font, cx, hintY, lw, "Dbl-click / drag → strip");
        Ui.hint(g, this.font, cx, hintY + 11, lw, "RMB ban from Auto");
    }

    /** The floating chip while dragging a block from the pane toward the strip. */
    private void renderPaneDragChip(GuiGraphicsExtractor g) {
        if (paneDragIdx < 0 || !paneDragging) return;
        LRow row = leftRows.get(paneDragIdx);
        int x = (int) paneDragX - 8, y = (int) paneDragY - 8;
        g.nextStratum();
        if (row.auto() != null) PaletteListPanel.drawCrosshatch(g, x, y, 16);
        else g.item(row.stack(), x, y);
        outline(g, x - 1, y - 1, 18, 18, WHITE);
        // Insertion caret when hovering the strip.
        if (paneDragX >= stripX() - HANDLE_W - 4 && paneDragX <= stripX() + STRIP_W + 8) {
            int idx = insertIndexAt(paneDragY);
            int count = editing.segments.size();
            int[] edges = count == 0 ? new int[]{stripY(), stripY()} : stripEdges(count);
            int cy = idx >= edges.length ? edges[edges.length - 1] : edges[Math.min(idx, edges.length - 1)];
            g.fill(stripX() - 4, cy - 1, stripX() + STRIP_W + 4, cy + 1, YELLOW);
        }
    }

    // ---- strip ----------------------------------------------------------------------------------

    private void renderStrip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        renderStripCaptions(g);
        int count = editing.segments.size();
        int sx = stripX(), sy = stripY();
        outline(g, sx - 1, sy - 1, STRIP_W + 2, stripH + 2, 0x60FFFFFF);
        if (count == 0) {
            // Empty strip: the "select a block" placeholder, stacked vertically down the middle.
            String hint = "SELECT A BLOCK";
            int ty = sy + (stripH - hint.length() * 9) / 2;
            for (int i = 0; i < hint.length(); i++) {
                String ch = String.valueOf(hint.charAt(i));
                g.text(this.font, ch, sx + (STRIP_W - this.font.width(ch)) / 2, ty + i * 9, GREY);
            }
            return;
        }

        boolean draggingOut = dragSeg >= 0 && dragSegMoved && dragOut;
        int[] edges = draggingOut ? stripEdgesWithout(count, dragSeg) : stripEdges(count);
        int dragVisualTop = -1, dragH = 0;
        if (dragSeg >= 0 && dragSegMoved) {
            int[] real = stripEdges(count);
            dragH = Math.max(10, real[dragSeg + 1] - real[dragSeg]);
            dragVisualTop = (int) (dragMouseY - grabOffset);
        }

        for (int k = 0; k < count; k++) {
            if (draggingOut && k == dragSeg) continue;
            int top, bot;
            if (draggingOut) {
                int vis = k < dragSeg ? k : k - 1;
                top = edges[vis];
                bot = edges[vis + 1];
            } else {
                top = edges[k];
                bot = edges[k + 1];
            }
            if (dragSeg == k && dragSegMoved && !dragOut) continue; // drawn floating below
            drawSegmentBody(g, k, sx, top, bot);
        }

        // Stop handles (left, pointing at their boundary), hidden during a segment drag.
        if (dragSeg < 0 || !dragSegMoved) {
            for (int k = 1; k < count; k++) {
                int yb = edges[k];
                g.fill(sx, yb - 1, sx + STRIP_W, yb + 1, 0xFF000000);
                boolean hot = dragStop == k - 1
                        || (mouseX >= sx - HANDLE_W - 1 && mouseX <= sx
                            && mouseY >= yb - 6 && mouseY <= yb + 6);
                drawStopHandle(g, sx - HANDLE_W, yb, hot);
            }
        }

        // The floating dragged segment (follows the mouse; sideways = removal preview).
        if (dragSeg >= 0 && dragSegMoved) {
            int fx = dragOut ? (int) (dragMouseX - STRIP_W / 2.0) : sx;
            int top = Math.max(BAR_H, dragVisualTop);
            drawSegmentBody(g, dragSeg, fx, top, top + dragH);
            outline(g, fx - 1, top - 1, STRIP_W + 2, dragH + 2, dragOut ? RED : WHITE);
        }

        renderStripLabels(g, count, edges);
    }

    /** START / END captions over and under the strip, and the strip's hint lines. */
    private void renderStripCaptions(GuiGraphicsExtractor g) {
        int sx = stripX(), sy = stripY();
        g.text(this.font, "START", sx + (STRIP_W - this.font.width("START")) / 2, sy - 10, Ui.HINT);
        g.text(this.font, "END", sx + (STRIP_W - this.font.width("END")) / 2, sy + stripH + 3, Ui.HINT);
        int hy = sy + stripH + 15;
        Ui.hint(g, this.font, rightX(), hy, RIGHT_W, "Drag edges to resize");
        Ui.hint(g, this.font, rightX(), hy + 11, RIGHT_W, "↑ ↓ move · Del remove");
    }

    /** A sideways "house" pentagon whose point touches the boundary line. Hot = yellow. */
    private void drawStopHandle(GuiGraphicsExtractor g, int x, int yb, boolean hot) {
        int body = 6;   // rectangular part
        int color = hot ? 0xFFFFE34D : 0xFFE0E0E0;
        // Black backing one px larger for contrast.
        g.fill(x - 1, yb - 5, x + body + 1, yb + 5, 0xFF000000);
        for (int i = 0; i < 4; i++) {
            g.fill(x + body + i, yb - 4 + i, x + body + i + 1, yb + 5 - i, 0xFF000000);
        }
        g.fill(x, yb - 4, x + body, yb + 4, color);
        for (int i = 0; i < 4; i++) {
            g.fill(x + body + i, yb - 3 + i, x + body + i + 1, yb + 4 - i, color);
        }
    }

    /**
     * Block-name labels to the RIGHT of the strip: centred on their segment when possible, packed
     * tightly (a couple of px) when squeezed, and never pushed outside the strip's vertical span —
     * outer labels pin at the ends and inner ones stack against them.
     */
    private void renderStripLabels(GuiGraphicsExtractor g, int count, int[] edges) {
        int gx = labelX(), gw = LABEL_W;
        int top = stripY() + 1;
        int bottom = stripY() + stripH - 8;
        int[] ys = new int[count];
        for (int k = 0; k < count; k++) {
            int a = edges[Math.min(k, edges.length - 2)];
            int b = edges[Math.min(k + 1, edges.length - 1)];
            ys[k] = Math.max(top, Math.min(bottom, (a + b) / 2 - 4));
        }
        // Keep order with tight spacing, inside the strip: push down, pull back up, re-push.
        for (int k = 1; k < count; k++) ys[k] = Math.max(ys[k], ys[k - 1] + LABEL_SPACING);
        ys[count - 1] = Math.min(ys[count - 1], bottom);
        for (int k = count - 2; k >= 0; k--) ys[k] = Math.min(ys[k], ys[k + 1] - LABEL_SPACING);
        ys[0] = Math.max(ys[0], top);
        for (int k = 1; k < count; k++) ys[k] = Math.max(ys[k], ys[k - 1] + LABEL_SPACING);

        long now = System.currentTimeMillis();
        for (int k = 0; k < count; k++) {
            if (dragSeg == k && dragSegMoved) {
                if (dragOut) continue;
                ys[k] = Math.max(top, Math.min(bottom,
                        (int) (dragMouseY - grabOffset) + 2)); // the label travels with the drag
            }
            if (ys[k] < BAR_H + 2 || ys[k] > viewBottom() - 8) continue;
            String name = segNames.get(k);
            int color = segMissing.get(k) ? RED : (k == selectedSeg ? WHITE : 0xFFE8E8E8);
            int tw = this.font.width(name);
            if (tw <= gw - 2) {
                g.text(this.font, name, gx, ys[k], color);
            } else {
                // Auto-scrolling marquee for names wider than the gutter.
                int span = tw - (gw - 2) + 12;
                int off = (int) ((now / 40) % (span * 2L));
                if (off > span) off = span * 2 - off; // bounce back
                g.enableScissor(gx, ys[k] - 1, gx + gw, ys[k] + 9);
                g.text(this.font, name, gx - off, ys[k], color);
                g.disableScissor();
            }
        }
    }

    private void drawSegmentBody(GuiGraphicsExtractor g, int k, int sx, int top, int bot) {
        if (bot <= top) return;
        PaletteSegment seg = editing.segments.get(k);
        int clipTop = Math.max(BAR_H + 1, top), clipBot = Math.min(viewBottom(), bot);
        if (clipBot <= clipTop) return;
        g.enableScissor(sx, clipTop, sx + STRIP_W, clipBot);
        if (seg.isAutomatic()) {
            int tint = k < segTints.size() ? segTints.get(k) : 0;
            for (int yy = top; yy < bot; yy += 16) {
                for (int xx = sx; xx < sx + STRIP_W; xx += 16) {
                    PaletteListPanel.drawCrosshatch(g, xx, yy, 16, tint);
                }
            }
        } else {
            ItemStack st = segStacks.get(k);
            Block b = Gradient.blockOfItemId(seg.block);
            int avg = b == null ? 0x808080 : BlockTextures.gradientValue(b, b, GradientMode.COLOR, 1.0);
            g.fill(sx, top, sx + STRIP_W, bot, 0xFF000000 | (avg & 0xFFFFFF));
            if (!st.isEmpty()) {
                for (int row = 0; top - 16 + row * ISO_UP < bot; row++) {
                    float yy = top - 16 + row * ISO_UP;
                    for (int col = 0; col * 2 * ISO_X < STRIP_W + 16; col++) {
                        g.pose().pushMatrix();
                        g.pose().translate(sx - 16 + col * 2 * ISO_X + (row % 2) * ISO_X, yy);
                        g.item(st, 0, 0);
                        g.pose().popMatrix();
                    }
                }
            }
        }
        g.disableScissor();
        if (segMissing.get(k)) outline(g, sx, top, STRIP_W, bot - top, RED);
        if (k == selectedSeg && (dragSeg != k || !dragSegMoved)) {
            outline(g, sx - 1, top - 1, STRIP_W + 2, bot - top + 2, WHITE);
        }
    }

    // ---- preview (one at a time: As gradient | As noise) ---------------------------------------

    private void renderPreviewArea(GuiGraphicsExtractor g) {
        int[] r = previewRect();
        int x = r[0], y = r[1], w = r[2], h = r[3];
        g.fill(x, y, x + w, y + h, 0x40000000);
        outline(g, x, y, w, h, Ui.LINE);
        int count = editing.segments.size();
        if (count == 0) {
            String s = "Add blocks to preview";
            g.text(this.font, this.font.plainSubstrByWidth(s, w - 8),
                    x + Math.max(4, (w - this.font.width(s)) / 2), y + h / 2 - 4, YELLOW);
        } else {
            g.enableScissor(x + 1, Math.max(BAR_H + 1, y + 1), x + w - 1, Math.min(viewBottom(), y + h - 1));
            if (previewMode == 1) drawNoisePreview(g, x, y, w, h, 1f);
            else drawCylinderPreview(g, x, y, w, h, 1f);
            g.disableScissor();
        }

        // Status: segment makeup, then the adjacent-Auto warning (they can resolve to the same
        // block, so a "step" may look longer than the strip suggests).
        int autos = 0;
        for (PaletteSegment s : editing.segments) if (s.isAutomatic()) autos++;
        String status = count + (count == 1 ? " segment" : " segments") + " · "
                + (count - autos) + (count - autos == 1 ? " block" : " blocks")
                + (autos > 0 ? " + " + autos + " Auto" : "");
        int sy = y + h + 4;
        Ui.hint(g, this.font, x, sy, w, status);
        boolean adjacentAutos = false;
        for (int k = 0; k + 1 < count; k++) {
            if (editing.segments.get(k).isAutomatic() && editing.segments.get(k + 1).isAutomatic()) {
                adjacentAutos = true;
                break;
            }
        }
        if (adjacentAutos) {
            int hy = sy + 11;
            for (String line : wrap("Auto segments next to each other may pick the same block.", w)) {
                g.text(this.font, line, x, hy, Ui.ATTENTION);
                hy += 10;
            }
        }
    }

    private void drawCylinderPreview(GuiGraphicsExtractor g, int x, int y, int w, int h, float s) {
        int d = w / (int) (2 * ISO_X) >= 7 ? 5 : 3;
        int rows = Math.max(3, (int) ((h / s - 16 - (d - 1) * 2 * ISO_DOWN) / ISO_UP));
        rows = Math.min(rows, Math.max(editing.segments.size() * 2, 8));
        ensureCylinder(d, rows);
        float cw = 2 * (d - 1) * ISO_X + 16;
        float ch = (d - 1) * 2 * ISO_DOWN + (rows - 1) * ISO_UP + 16;
        drawCylinder(g, x + (w - s * cw) / 2f, y + (h - s * ch) / 2f, s, d, rows);
    }

    private void ensureCylinder(int d, int h) {
        if (cylCache != null && cylD == d && cylH == h) return;
        cylD = d;
        cylH = h;
        cylCells = ringCells(d);
        cylCache = new ItemStack[cylCells.length][h];
        cylTint = new int[cylCells.length][h];
        int count = editing.segments.size();
        if (count == 0) return;
        double[] bounds = displayBounds(count);
        List<List<ItemStack>> bands = previewBands();
        Random rnd = new Random(42);
        for (int i = 0; i < cylCells.length; i++) {
            double[] wob = new double[bounds.length];
            for (int k = 0; k < bounds.length; k++) {
                double lo = k == 0 ? 0 : bounds[k - 1];
                double hi = k == bounds.length - 1 ? 1 : bounds[k + 1];
                double room = Math.min(bounds[k] - lo, hi - bounds[k]);
                wob[k] = bounds[k] + (editing.stepWobble > 0 && rnd.nextDouble() < editing.stepWobble
                        ? (rnd.nextDouble() - 0.5) * room : 0);
            }
            for (int j = 0; j < h; j++) {
                double t = h == 1 ? 0 : (double) j / (h - 1);
                if (editing.chaos > 0 && t > 0 && t < 1 && rnd.nextDouble() < editing.chaos) {
                    double stepFrac = count > 1 ? 1.0 / (count - 1) : 0.1;
                    t = Math.max(0, Math.min(1, rnd.nextBoolean() ? t - stepFrac : t + stepFrac));
                }
                int idx = PaletteMath.indexFor(t, wob);
                List<ItemStack> band = bands.get(idx);
                cylCache[i][j] = rollBand(band, rnd);
                cylTint[i][j] = band.isEmpty() && idx < segTints.size() ? segTints.get(idx) : 0;
            }
        }
    }

    /**
     * Per segment: its stack first, then its ±window colour-order neighbours from the source
     * (empty list = crosshatch). The previews roll the palette's chance per cell: index 0 on a
     * failed roll, a random later entry on success — matching placement's unified model.
     */
    private List<List<ItemStack>> previewBands() {
        List<List<ItemStack>> out = new ArrayList<>();
        int n = Math.max(0, Math.min(3, editing.variationWindow));
        // leftRows is already colour-sorted; its block rows ARE the Oklab ordering.
        List<LRow> blocks = new ArrayList<>();
        for (LRow row : leftRows) {
            if (row.auto() == null) blocks.add(row);
        }
        for (int k = 0; k < editing.segments.size(); k++) {
            PaletteSegment seg = editing.segments.get(k);
            if (seg.isAutomatic() || segStacks.get(k).isEmpty()) {
                out.add(List.of());
                continue;
            }
            List<ItemStack> band = new ArrayList<>();
            band.add(segStacks.get(k));
            if (n > 0 && editing.variationChance > 0) {
                int idx = -1;
                for (int i = 0; i < blocks.size(); i++) {
                    if (blocks.get(i).id().equals(seg.block)) {
                        idx = i;
                        break;
                    }
                }
                if (idx >= 0) {
                    for (int i = Math.max(0, idx - n); i <= Math.min(blocks.size() - 1, idx + n); i++) {
                        if (i != idx) band.add(blocks.get(i).stack());
                    }
                }
            }
            out.add(band);
        }
        return out;
    }

    /** One preview cell's roll under the unified model: base, or a random window neighbour. */
    private ItemStack rollBand(List<ItemStack> band, Random rnd) {
        if (band.isEmpty()) return null;
        if (band.size() > 1 && rnd.nextInt(100) < Math.max(0, Math.min(100, editing.variationChance))) {
            return band.get(1 + rnd.nextInt(band.size() - 1));
        }
        return band.get(0);
    }

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

    private void drawCylinder(GuiGraphicsExtractor g, float tx, float ty, float s, int d, int h) {
        g.pose().pushMatrix();
        g.pose().translate(tx, ty);
        g.pose().scale(s, s);
        for (int gy = 0; gy < h; gy++) {
            int row = h - 1 - gy;
            for (int i = 0; i < cylCells.length; i++) {
                ItemStack st = cylCache[i][row];
                int cx = cylCells[i][0], cz = cylCells[i][1];
                g.pose().pushMatrix();
                g.pose().translate((cx - cz + (d - 1)) * ISO_X, (cx + cz) * ISO_DOWN + row * ISO_UP);
                if (st == null) PaletteListPanel.drawCrosshatch(g, 0, 0, 16, cylTint[i][row]);
                else g.item(st, 0, 0);
                g.pose().popMatrix();
            }
        }
        g.pose().popMatrix();
    }

    // ---- noise preview --------------------------------------------------------------------------

    private int[] noiseAnchor() {
        BlockPos feet = this.minecraft != null && this.minecraft.player != null
                ? this.minecraft.player.blockPosition() : BlockPos.ZERO;
        return new int[]{feet.getX() + (int) Math.round(previewOffX),
                feet.getY() + (int) Math.round(previewOffY),
                feet.getZ() + (int) Math.round(previewOffZ)};
    }

    private long noiseSeedLong() {
        String s = editing.noiseSeed == null ? "" : editing.noiseSeed.trim();
        if (s.isEmpty()) return 0L;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return s.hashCode();
        }
    }

    private void drawNoisePreview(GuiGraphicsExtractor g, int x, int y, int w, int h, float s) {
        int n = 1 + (int) Math.min((w / s - 16) / (2 * ISO_X), (h / s - 16) / (2 * ISO_DOWN + ISO_UP));
        n = Math.max(2, Math.min(10, n));
        float cw = 2 * (n - 1) * ISO_X + 16;
        float ch = (n - 1) * (2 * ISO_DOWN + ISO_UP) + 16;
        drawNoiseCube(g, n, x + (w - s * cw) / 2f, y + (h - s * ch) / 2f, s);
    }

    private void drawNoiseCube(GuiGraphicsExtractor g, int n, float tx, float ty, float s) {
        int count = editing.segments.size();
        if (count == 0) return;
        double[] cb = PaletteMath.segmentBounds(count, editing.curve, editing.stops);
        // Step length: jitter the band boundaries once (stable seed → stable preview).
        if (editing.stepWobble > 0 && cb.length > 0) {
            Random wr = new Random(43);
            double[] wob = new double[cb.length];
            for (int k = 0; k < cb.length; k++) {
                double lo = k == 0 ? 0 : cb[k - 1];
                double hi = k == cb.length - 1 ? 1 : cb[k + 1];
                double room = Math.min(cb[k] - lo, hi - cb[k]);
                wob[k] = cb[k] + (wr.nextDouble() < editing.stepWobble
                        ? (wr.nextDouble() - 0.5) * room : 0);
            }
            cb = wob;
        }
        List<List<ItemStack>> bands = previewBands();
        long seed = noiseSeedLong();
        int[] a = noiseAnchor();
        g.pose().pushMatrix();
        g.pose().translate(tx, ty);
        g.pose().scale(s, s);
        for (int gy = 0; gy < n; gy++) {
            for (int sum = 0; sum <= 2 * (n - 1); sum++) {
                for (int gx = Math.max(0, sum - (n - 1)); gx <= Math.min(n - 1, sum); gx++) {
                    int gz = sum - gx;
                    if (gx != n - 1 && gz != n - 1 && gy != n - 1) continue;
                    int wx = a[0] + gx, wy = a[1] + gy, wz = a[2] + gz;
                    double v = Noise.sample(editing.noiseType, wx, wy, wz, seed,
                            editing.noiseScaleX, editing.noiseScaleY, editing.noiseScaleZ);
                    int idx = PaletteMath.indexFor(PaletteMath.curved(editing.curve, v), cb);
                    // Chaos + Variation, rolled per cell from its world position so the preview
                    // is stable frame to frame — the same maths placement applies.
                    Random cell = new Random(wx * 73428767L ^ wy * 912931L ^ wz * 31337L);
                    if (editing.chaos > 0 && cell.nextDouble() < editing.chaos) {
                        idx = Math.max(0, Math.min(count - 1, cell.nextBoolean() ? idx - 1 : idx + 1));
                    }
                    ItemStack st = segStacks.get(idx);
                    ItemStack rolled = rollBand(bands.get(idx), cell);
                    if (rolled != null) st = rolled;
                    g.pose().pushMatrix();
                    g.pose().translate((gx - gz + (n - 1)) * ISO_X, (gx + gz) * ISO_DOWN + (n - 1 - gy) * ISO_UP);
                    if (editing.segments.get(idx).isAutomatic() || st.isEmpty()) {
                        PaletteListPanel.drawCrosshatch(g, 0, 0, 16,
                                idx < segTints.size() ? segTints.get(idx) : 0);
                    } else {
                        g.item(st, 0, 0);
                    }
                    g.pose().popMatrix();
                }
            }
        }
        g.pose().popMatrix();
    }

    /** Which cube face a point is over (see GradientScreen history for the projection notes). */
    private CubeFace faceAt(double mx, double my, boolean expanded) {
        int n;
        float tx, ty, s;
        if (expanded) {
            s = 2f;
            float availW = this.width - 60, availH = this.height - 70;
            n = Math.max(2, Math.min(12, 1 + (int) Math.min((availW / s - 16) / (2 * ISO_X),
                    (availH / s - 16) / (2 * ISO_DOWN + ISO_UP))));
            float w = s * (2 * (n - 1) * ISO_X + 16), h = s * ((n - 1) * (2 * ISO_DOWN + ISO_UP) + 16);
            tx = (this.width - w) / 2f;
            ty = (this.height - h) / 2f;
        } else {
            int[] b = previewRect();
            s = 1f;
            n = Math.max(2, Math.min(10, 1 + (int) Math.min((b[2] - 16) / (2 * ISO_X),
                    (b[3] - 16) / (2 * ISO_DOWN + ISO_UP))));
            float cw = 2 * (n - 1) * ISO_X + 16;
            tx = b[0] + (b[2] - cw) / 2f;
            ty = b[1] + (b[3] - ((n - 1) * (2 * ISO_DOWN + ISO_UP) + 16)) / 2f;
        }
        double dx = (mx - tx) / s - ((n - 1) * ISO_X + 8);
        double dy = (my - ty) / s;
        double u = (dx / ISO_X + dy / ISO_DOWN) / 2, v = (dy / ISO_DOWN - dx / ISO_X) / 2;
        if (u >= 0 && u <= n && v >= 0 && v <= n) return CubeFace.TOP;
        return dx >= 0 ? CubeFace.RIGHT : CubeFace.LEFT;
    }

    // ---- expanded preview -----------------------------------------------------------------------

    private boolean inCloseX(double mx, double my) {
        return mx >= this.width - 10 - CLOSE_X_SIZE && mx <= this.width - 10
                && my >= 10 && my <= 10 + CLOSE_X_SIZE;
    }

    private void renderExpandedOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.nextStratum();
        g.fill(0, 0, this.width, this.height, 0xD0000000);
        if (editing.segments.isEmpty()) {
            g.text(this.font, "Add blocks to preview", this.width / 2 - 60, this.height / 2, YELLOW);
        } else if (expandedPreview == 2) {
            drawNoisePreview(g, 30, 35, this.width - 60, this.height - 70, 2f);
        } else {
            drawCylinderPreview(g, 30, 35, this.width - 60, this.height - 70, 2f);
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

    // ---- help popups ----------------------------------------------------------------------------

    private void renderHelpIcons(GuiGraphicsExtractor g) {
        for (HelpSpot h : helpSpots) {
            if (!h.icon()) continue; // icon-less spots only contribute hover text
            int y = h.baseY() - scroll;
            if (y < BAR_H + 2 || y + h.h() > viewBottom()) continue;
            drawHelpIcon(g, h.x() + h.w() + 3, y + 5);
        }
    }

    /** A small circled question mark (shared glyph — see {@link UiIcons}). */
    private static void drawHelpIcon(GuiGraphicsExtractor g, int x, int y) {
        UiIcons.drawHelpIcon(g, x, y);
    }

    private void renderHelpPopup(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // Which control (incl. its ? icon) is the mouse over right now?
        HelpSpot over = null;
        for (HelpSpot h : helpSpots) {
            if (mouseY <= BAR_H || pendingExit != null || expandedPreview != 0) break;
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
        // A clicked ? shows instantly, but only while the mouse stays on that control.
        if (clickedSpot != null && over != clickedSpot) clickedSpot = null;
        if (over == null) return;
        if (over != clickedSpot && System.currentTimeMillis() - hoverSince < 1000) return;
        helpMx = mouseX;
        helpMy = mouseY;
        String text = over.text().get();
        double px = mouseX, py = mouseY;
        List<String> lines = wrap(text, 150);
        int w = 0;
        for (String l : lines) w = Math.max(w, this.font.width(l));
        int h = lines.size() * 10 + 8;
        int x = (int) Math.min(px + 10, this.width - w - 14);
        int y = (int) Math.min(py + 8, this.height - h - 4);
        g.nextStratum();
        g.fill(x, y, x + w + 8, y + h, 0xF0101010);
        outline(g, x, y, w + 8, h, 0x80FFE34D);
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

    // ---- discard confirm ------------------------------------------------------------------------

    private void renderDiscardConfirm(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.nextStratum();
        g.fill(0, 0, this.width, this.height, 0xB0000000);
        int[] b = confirmBox();
        g.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], 0xF0202020);
        outline(g, b[0], b[1], b[2], b[3], WHITE);
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

    private static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    // ---- slider ---------------------------------------------------------------------------------

    private static final class EditSlider extends AbstractSliderButton {
        private final java.util.function.DoubleConsumer onChange;
        private final java.util.function.DoubleFunction<String> labelFn;

        EditSlider(int x, int y, int w, double initial,
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
        }
    }
}
