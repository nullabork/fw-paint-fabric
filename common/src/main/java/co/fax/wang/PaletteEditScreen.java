package co.fax.wang;

import co.fax.wang.palette.AutoMode;
import co.fax.wang.palette.Palette;
import co.fax.wang.palette.PaletteMath;
import co.fax.wang.palette.PaletteOrder;
import co.fax.wang.palette.PaletteSegment;
import co.fax.wang.palette.PaletteStore;
import co.fax.wang.palette.SizingMode;
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
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The palette editor — a temporary mode of the Palette tab (its own screen, same title bar).
 * Layout, top to bottom (everything below the bar scrolls as one page): the two previews
 * (gradient cylinder + noise cube, side by side, each expandable) → name row (Save / Cancel) →
 * three columns: the grouped settings (left), the colour-sorted source list (middle — double-click
 * or drag blocks into the strip, right-click bans a block from Automatic segments), and the
 * segment strip (right) with its labels on the right, stop handles on the left, drag to reorder,
 * and drag out sideways to remove.
 */
public class PaletteEditScreen extends Screen {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFA0A0A0;
    private static final int LIGHT = 0xFFE0E0E0; // headings + interaction hints
    private static final int RED = 0xFFFF5555;
    private static final int YELLOW = 0xFFFFE34D;
    private static final int BAR_H = 22;
    private static final int LEFT_X = 10;
    private static final int COL_GAP = 12;
    private static final int HOVER_BG = 0x33FFFFFF;

    // Vertical layout (base y positions — the page scroll offsets them).
    private static final int PREVIEW_Y = 30;
    private static final int PREVIEW_H = 104;
    private static final int NAME_LABEL_Y = PREVIEW_Y + PREVIEW_H + 6;
    private static final int NAME_Y = NAME_LABEL_Y + 10;
    private static final int SEP_Y = NAME_Y + 20 + 8;      // separator line, 8px above + below
    private static final int COL_TOP = SEP_Y + 1 + 8;
    private static final int LIST_H = 204;
    private static final int STRIP_H = 224;
    private static final int HEADING_H = 14;

    // Left column: the settings (sliders leave room for the help icons).
    private static final int SET_W = 140;

    // Right column: stop handles left of the strip, labels to its right.
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

    private final Palette editing;      // working copy — Cancel discards it
    private final boolean isNew;
    private boolean dirty;

    private String nameDraft;
    private String nameError = "";
    private EditBox nameBox;
    private Button orderBtn, curveBtn;

    // Page scroll: widgets remember their base y and get repositioned/hidden.
    private record ScrolledWidget(AbstractWidget widget, int baseY) {}
    private final List<ScrolledWidget> scrolledWidgets = new ArrayList<>();
    private int scroll;

    // Middle column: pinned Automatic rows + the source blocks sorted by colour.
    private record LRow(AutoMode auto, String id, ItemStack stack, String name, int rgb) {}
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

    // Previews: both shown side by side; either can be expanded.
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

    // Help popups. Spots with an icon get the circled-?; clicking it shows the help instantly,
    // but the popup only lives while the mouse stays over the control — moving away dismisses
    // it. Texts are suppliers so they always describe the control's CURRENT state.
    private record HelpSpot(int x, int baseY, int w, int h,
                            java.util.function.Supplier<String> text, boolean icon) {}
    private final List<HelpSpot> helpSpots = new ArrayList<>();
    private HelpSpot clickedSpot;
    private HelpSpot hoverSpot;
    private long hoverSince;

    // Discard-confirm modal (unsaved changes).
    private Screen pendingExit;

    // OS cursor shape management (move / vertical-resize on the right hovers).
    private static final Map<Integer, Long> CURSORS = new HashMap<>();
    private int cursorShape;

    public PaletteEditScreen(Palette source) {
        super(Component.literal("FW Paint — Palette editor"));
        this.isNew = source == null;
        this.editing = source == null ? new Palette() : source.copy();
        this.nameDraft = this.editing.name;
    }

    // ---- layout ---------------------------------------------------------------------------------

    private int paneW() {
        int avail = this.width - 2 * LEFT_X - SET_W - RIGHT_W - 2 * COL_GAP;
        return Math.max(60, Math.min(200, avail));
    }

    private int contentX() {
        int total = SET_W + COL_GAP + paneW() + COL_GAP + RIGHT_W;
        return Math.max(LEFT_X, (this.width - total) / 2);
    }

    private int paneX() { return contentX() + SET_W + COL_GAP; }
    private int rightX() { return paneX() + paneW() + COL_GAP; }

    private int stripX() { return rightX() + HANDLE_W; }
    private int labelX() { return stripX() + STRIP_W + 4; }
    private int stripYBase() { return COL_TOP + 26; } // one toggle row above the strip
    private int stripY() { return stripYBase() - scroll; }

    private int contentHeight() {
        int paneBottom = COL_TOP + 24 + LIST_H + 26;
        int stripBottom = stripYBase() + STRIP_H + 6;
        return Math.max(Math.max(paneBottom, stripBottom), settingsBottomBase) + 8;
    }

    private int viewBottom() { return this.height - 4; }

    private int maxScroll() {
        return Math.max(0, contentHeight() - (viewBottom() - BAR_H));
    }

    private int settingsBottomBase; // set during init

    // ---- init -----------------------------------------------------------------------------------

    @Override
    protected void init() {
        scrolledWidgets.clear();
        helpSpots.clear();
        refreshAvailable();
        rebuildLeftRows();
        refreshSegmentDisplay();

        int cx = contentX();

        // Per-preview expand buttons (⛶), top-right corner of each preview half.
        int[] cyl = previewHalf(false);
        int[] noise = previewHalf(true);
        addScrolled(Button.builder(Component.literal("⛶"), b -> expandedPreview = 1)
                .bounds(cyl[0] + cyl[2] - 16, PREVIEW_Y, 16, 14).build());
        addScrolled(Button.builder(Component.literal("⛶"), b -> expandedPreview = 2)
                .bounds(noise[0] + noise[2] - 16, PREVIEW_Y, 16, 14).build());

        // Name row: the name box spans the left two columns, Save/Cancel sit over the strip.
        nameBox = new EditBox(this.font, cx, NAME_Y, SET_W + COL_GAP + paneW(), 20,
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
        int saveX = rightX() + RIGHT_W - 124;
        addScrolled(Button.builder(Component.literal("Save"), b -> save())
                .bounds(saveX, NAME_Y, 60, 20).build());
        addScrolled(Button.builder(Component.literal("Cancel"), b -> attemptExit(backToList()))
                .bounds(saveX + 62, NAME_Y, 60, 20).build());

        // Middle column: source toggle + the block list (render-only, below).
        addScrolled(Button.builder(Component.literal("Source: " + editing.source.displayName()), b -> {
            editing.source = editing.source.next();
            b.setMessage(Component.literal("Source: " + editing.source.displayName()));
            dirty = true;
            refreshAvailable();
            rebuildLeftRows();
            refreshSegmentDisplay();
        }).bounds(paneX(), COL_TOP, paneW(), 20).build());
        helpSpots.add(new HelpSpot(paneX(), COL_TOP, paneW(), 20,
                () -> "Painting draws blocks from " + editing.source.displayName()
                        + " (also the pool for Automatic segments)", true));

        // Right column: Order + Curve icon toggles side by side above the strip, left-aligned
        // with the strip itself. Their icons render from live state (see renderToggleIcons), so
        // manual reordering / stop drags show Custom immediately. One shared (?) for the row;
        // hovering either button describes what it's currently doing.
        int togX = stripX(), togW = STRIP_W + 4 + LABEL_W;
        int togBw = (togW - 16 - 4) / 2;
        orderBtn = addScrolled(Button.builder(Component.empty(),
                b -> sortSegments(editing.order.nextSort()))
                .bounds(togX, COL_TOP, togBw, 20).build());
        helpSpots.add(new HelpSpot(togX, COL_TOP, togBw, 20, this::orderStateText, false));
        curveBtn = addScrolled(Button.builder(Component.empty(), b -> {
            editing.curve = editing.curve.next();
            dirty = true;
            resetPreview();
        }).bounds(togX + togBw + 4, COL_TOP, togBw, 20).build());
        helpSpots.add(new HelpSpot(togX + togBw + 4, COL_TOP, togBw, 20, this::curveStateText, false));
        helpSpots.add(new HelpSpot(togX + 2 * togBw + 5, COL_TOP, 0, 20,
                () -> "Left: sort order — cycles colour/brightness, ascending/descending. "
                        + "Right: curve — cycles the easing shape. Reordering segments by hand "
                        + "or dragging a stop turns either Custom (C).", true));

        // Left column: the grouped settings.
        int rx = contentX(), rw = SET_W - 14; // room for the (?) icons
        int y = COL_TOP + HEADING_H; // "Gradient & noise" heading renders above
        y = addSlider(rx, y, rw, editing.variation, v -> "Variation: " + pct(v),
                v -> { editing.variation = v; resetPreview(); },
                "Similar blocks swap within each segment");
        y = addSlider(rx, y, rw, editing.chaos, v -> "Chaos: " + pct(v),
                v -> { editing.chaos = v; resetPreview(); },
                "Chance to repeat or skip a step");
        y = addSlider(rx, y, rw, editing.stepWobble, v -> "Step length: " + pct(v),
                v -> { editing.stepWobble = v; resetPreview(); },
                "Chance steps run longer or shorter");

        addScrolled(Button.builder(Component.literal("Sizing: " + editing.sizing.label()), b -> {
            editing.sizing = switch (editing.sizing) {
                case MIN_BLOCKS -> SizingMode.FILL_SPACE;
                case FILL_SPACE -> SizingMode.SET_STEPS;
                case SET_STEPS -> SizingMode.MIN_BLOCKS;
            };
            dirty = true;
            rebuildWidgets(); // the Steps slider shows/hides
        }).bounds(rx, y, rw, 20).build());
        helpSpots.add(new HelpSpot(rx, y, rw, 20, () -> switch (editing.sizing) {
            case MIN_BLOCKS -> "Min blocks: the shortest run that fits the segment ratios";
            case FILL_SPACE -> "Fill space: expands out to the end marker or first block";
            case SET_STEPS -> "Set steps: a fixed length (the Steps slider below)";
        }, true));
        y += 24;
        if (editing.sizing == SizingMode.SET_STEPS) {
            y = addSlider(rx, y, rw, (editing.steps - 1) / 15.0,
                    v -> "Steps: " + (1 + (int) Math.round(v * 15)),
                    v -> editing.steps = 1 + (int) Math.round(v * 15),
                    "The gradient stretches or shrinks to this many blocks");
        }

        noiseHeadingY = y;
        y += HEADING_H; // "Noise only" heading renders above the noise group
        addScrolled(Button.builder(Component.literal("Noise: " + editing.noiseType.displayName()), b -> {
            editing.noiseType = editing.noiseType.next();
            dirty = true;
            b.setMessage(Component.literal("Noise: " + editing.noiseType.displayName()));
        }).bounds(rx, y, rw, 20).build());
        helpSpots.add(new HelpSpot(rx, y, rw, 20, () -> switch (editing.noiseType) {
            case SMOOTH -> "Smooth noise: soft rounded blobs — noise painting only";
            case PERLIN -> "Perlin noise: natural ridged shapes — noise painting only";
            case FRACTAL -> "Fractal noise: layered detail — noise painting only";
        }, true));
        y += 24;
        if (editing.noiseLock) {
            y = addSlider(rx, y, rw, (clampScale(editing.noiseScaleX) - 1) / 14.0,
                    v -> "Scale: " + (1 + (int) Math.round(v * 14)),
                    v -> { double s = 1 + Math.round(v * 14); editing.noiseScaleX = editing.noiseScaleY = editing.noiseScaleZ = s; },
                    "Noise feature size — only used when noise painting");
        } else {
            y = addSlider(rx, y, rw, (clampScale(editing.noiseScaleX) - 1) / 14.0,
                    v -> "Scale X: " + (1 + (int) Math.round(v * 14)),
                    v -> editing.noiseScaleX = 1 + Math.round(v * 14),
                    "Noise feature size — only used when noise painting");
            y = addSlider(rx, y, rw, (clampScale(editing.noiseScaleY) - 1) / 14.0,
                    v -> "Scale Y: " + (1 + (int) Math.round(v * 14)),
                    v -> editing.noiseScaleY = 1 + Math.round(v * 14), null);
            y = addSlider(rx, y, rw, (clampScale(editing.noiseScaleZ) - 1) / 14.0,
                    v -> "Scale Z: " + (1 + (int) Math.round(v * 14)),
                    v -> editing.noiseScaleZ = 1 + Math.round(v * 14), null);
        }
        addScrolled(Button.builder(Component.literal("Lock XYZ: " + (editing.noiseLock ? "On" : "Off")), b -> {
            editing.noiseLock = !editing.noiseLock;
            dirty = true;
            rebuildWidgets();
        }).bounds(rx, y, rw, 20).build());
        y += 24;
        EditBox seed = new EditBox(this.font, rx, y, rw, 20, Component.literal("Seed"));
        seed.setHint(Component.literal("Seed…"));
        seed.setMaxLength(32);
        seed.setValue(editing.noiseSeed == null ? "" : editing.noiseSeed);
        seed.setResponder(s -> {
            editing.noiseSeed = s;
            dirty = true;
        });
        addScrolled(seed);
        helpSpots.add(new HelpSpot(rx, y, rw, 20,
                () -> "Noise seed — only used when noise painting", true));
        y += 24;
        settingsBottomBase = y;

        scroll = Math.min(scroll, maxScroll());
        applyScroll();
    }

    private int noiseHeadingY;

    private static double clampScale(double s) { return Math.max(1, Math.min(15, s)); }

    private static String pct(double v) { return Math.round(v * 100) + "%"; }

    private String orderStateText() {
        return switch (editing.order) {
            case COLOR_ASC -> "Sorted by colour, ascending";
            case COLOR_DESC -> "Sorted by colour, descending";
            case BRIGHTNESS_ASC -> "Sorted by brightness, ascending";
            case BRIGHTNESS_DESC -> "Sorted by brightness, descending";
            case CUSTOM -> "Custom order — arranged by hand";
        };
    }

    private String curveStateText() {
        return switch (editing.curve) {
            case LINEAR -> "Linear: even change end to end";
            case EASE_IN -> "Ease In: holds the start colour longer";
            case EASE_OUT -> "Ease Out: reaches the end colour sooner";
            case EASE_IN_OUT -> "Ease In/Out: lingers at both ends";
            case STEP -> "Step: hard quantised bands";
            case CUSTOM -> "Custom: exactly the stop positions you dragged";
        };
    }

    private <T extends AbstractWidget> T addScrolled(T w) {
        scrolledWidgets.add(new ScrolledWidget(w, w.getY()));
        return addRenderableWidget(w);
    }

    private int addSlider(int x, int y, int w, double initial,
                          java.util.function.DoubleFunction<String> label,
                          java.util.function.DoubleConsumer onChange, String help) {
        addScrolled(new EditSlider(x, y, w, initial, label, v -> {
            onChange.accept(v);
            dirty = true;
        }));
        if (help != null) helpSpots.add(new HelpSpot(x, y, w, 20, () -> help, true));
        return y + 24;
    }

    private void applyScroll() {
        for (ScrolledWidget s : scrolledWidgets) {
            int y = s.baseY() - scroll;
            s.widget().setY(y);
            s.widget().visible = y >= BAR_H + 2 && y + s.widget().getHeight() <= viewBottom();
        }
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

    /**
     * Representative colours for Automatic segments — display only, nothing to do with what
     * placement resolves; they just make autos legible in the strip and previews. Memoized here
     * (recomputed only when segments change, never per frame). Autos between static blocks lerp
     * between their neighbours' colours; an all-Automatic strip gets a position-stable rainbow
     * (the colours stay in strip order even when segments are rearranged).
     */
    private void computeSegTints() {
        segTints.clear();
        int n = editing.segments.size();
        int[] out = new int[n];
        int[] staticColor = new int[n];
        boolean anyStatic = false;
        for (int i = 0; i < n; i++) {
            PaletteSegment s = editing.segments.get(i);
            if (!s.isAutomatic()) {
                anyStatic = true;
                Block b = Gradient.blockOfItemId(s.block);
                staticColor[i] = b == null ? 0x808080
                        : BlockTextures.gradientValue(b, null, GradientMode.COLOR, 0.5);
            }
        }
        if (!anyStatic) {
            for (int i = 0; i < n; i++) {
                double hue = n <= 1 ? 0 : 0.85 * i / (n - 1); // rainbow, stable by position
                out[i] = ColorOrder.hsvToRgb(hue, 0.65, 0.95);
            }
        } else {
            // The next static colour strictly after each index (mirrors placement's anchors).
            int[] nextC = new int[n];
            boolean[] hasNext = new boolean[n];
            int ahead = 0;
            boolean has = false;
            for (int i = n - 1; i >= 0; i--) {
                hasNext[i] = has;
                nextC[i] = ahead;
                if (!editing.segments.get(i).isAutomatic()) {
                    ahead = staticColor[i];
                    has = true;
                }
            }
            int prevC = 0;
            boolean hasPrev = false;
            int i = 0;
            while (i < n) {
                if (!editing.segments.get(i).isAutomatic()) {
                    prevC = staticColor[i];
                    hasPrev = true;
                    i++;
                    continue;
                }
                int runEnd = i;
                while (runEnd + 1 < n && editing.segments.get(runEnd + 1).isAutomatic()) runEnd++;
                int from = hasPrev ? prevC : (hasNext[runEnd] ? nextC[runEnd] : 0x808080);
                int to = hasNext[runEnd] ? nextC[runEnd] : (hasPrev ? prevC : 0x808080);
                int k = runEnd - i + 1;
                for (int j = 0; j < k; j++) {
                    out[i + j] = lerpRgb(from, to, (j + 1) / (double) (k + 1));
                }
                prevC = out[runEnd];
                hasPrev = true;
                i = runEnd + 1;
            }
        }
        for (int i = 0; i < n; i++) {
            segTints.add(editing.segments.get(i).isAutomatic() ? 0xFF000000 | (out[i] & 0xFFFFFF) : 0);
        }
    }

    private static int lerpRgb(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int gr = (int) Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (gr << 8) | bl;
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
        edges[count] = stripY() + STRIP_H;
        for (int k = 0; k < count - 1; k++) edges[k + 1] = stripY() + (int) Math.round(b[k] * STRIP_H);
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
            edges[pos++] = stripY() + (int) Math.round(Math.min(1, acc) * STRIP_H);
        }
        edges[count] = stripY() + STRIP_H;
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
            if (event.button() == 0) {
                if (inCloseX(mx, my)) expandedPreview = 0;
                else if (expandedPreview == 2) dragFace = faceAt(mx, my, true);
            }
            return true;
        }
        // Title bar: switching tabs leaves the editor (confirming unsaved changes).
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
        // Circled-? icons show their popup immediately (it hides again on mouse-away).
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

        // Stop handles.
        if (event.button() == 0) {
            int h = stopHandleAt(mx, my);
            if (h >= 0) {
                beginStopDrag(h);
                return true;
            }
        }
        // Segment body: press begins a potential drag; release without movement selects.
        if (event.button() == 0) {
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
        // Noise-preview pan (collapsed, right half).
        if (event.button() == 0 && !editing.segments.isEmpty()) {
            int[] nb = previewHalf(true);
            int ny = nb[1] - scroll;
            if (mx >= nb[0] && mx <= nb[0] + nb[2] && my >= ny && my <= ny + nb[3]) {
                dragFace = faceAt(mx, my, false);
                return true;
            }
        }
        // Block pane (middle): press arms a drag-into-strip; double-click appends; right-click bans.
        if (paneListClick(mx, my, event.button(), doubled)) return true;
        return false;
    }

    private boolean paneListClick(double mx, double my, int button, boolean doubled) {
        int cx = paneX(), lw = paneW();
        int ly = COL_TOP + 24 - scroll;
        if (mx < cx || mx > cx + lw || my < ly || my > ly + LIST_H) return false;
        int idx = leftScroll + (int) ((my - ly) / 18);
        if (idx < 0 || idx >= leftRows.size()) return true;
        LRow row = leftRows.get(idx);
        String key = row.auto() != null ? "auto:" + row.auto() : row.id();
        if (button == 1 && row.auto() == null) {
            // Right-click: ban/unban from Automatic segments (saved on the palette).
            if (!editing.autoExclude.remove(row.id())) editing.autoExclude.add(row.id());
            dirty = true;
            return true;
        }
        if (button == 0) {
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
        if (event.button() == 0 && dragStop >= 0) {
            int count = editing.segments.size();
            if (editing.stops.size() == count - 1 && dragStop < editing.stops.size()) {
                double v = (my - stripY()) / (double) STRIP_H;
                double min = 4.0 / STRIP_H;
                double lo = (dragStop == 0 ? 0 : editing.stops.get(dragStop - 1)) + min;
                double hi = (dragStop == editing.stops.size() - 1 ? 1 : editing.stops.get(dragStop + 1)) - min;
                editing.stops.set(dragStop, Math.max(lo, Math.min(hi, v)));
                resetPreview();
            }
            return true;
        }
        if (event.button() == 0 && paneDragIdx >= 0) {
            paneDragX = mx;
            paneDragY = my;
            if (!paneDragging && (Math.abs(mx - panePressX) > 4 || Math.abs(my - panePressY) > 4)) {
                paneDragging = true;
            }
            return true;
        }
        if (event.button() == 0 && dragSeg >= 0) {
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
        if (event.button() == 0 && dragFace != null) {
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
        if (event.button() == 0 && dragStop >= 0) {
            dragStop = -1;
            return true;
        }
        if (event.button() == 0 && paneDragIdx >= 0) {
            if (paneDragging && paneDragX >= stripX() - HANDLE_W - 4
                    && paneDragX <= stripX() + STRIP_W + 8) {
                insertSegmentAt(insertIndexAt(paneDragY), rowSegment(leftRows.get(paneDragIdx)));
            }
            paneDragIdx = -1;
            paneDragging = false;
            return true;
        }
        if (event.button() == 0 && dragSeg >= 0) {
            if (dragOut && dragSegMoved) {
                removeSegment(dragSeg);
            } else if (!dragSegMoved) {
                selectedSeg = dragSeg; // plain click — select (white outline, arrow keys move)
            }
            dragSeg = -1;
            dragOut = false;
            return true;
        }
        if (event.button() == 0 && dragFace != null) {
            dragFace = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (pendingExit != null || expandedPreview != 0) return true;
        int cx = paneX(), lw = paneW();
        int ly = COL_TOP + 24 - scroll;
        if (mouseX >= cx && mouseX <= cx + lw && mouseY >= ly && mouseY <= ly + LIST_H) {
            int maxLeft = Math.max(0, leftRows.size() - LIST_H / 18);
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
        boolean typing = nameBox != null && nameBox.isFocused();
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
        return new GradientScreen(false);
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
        if (button != 0) return true;
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

    @Override
    public void removed() {
        applyCursor(0); // never leak a move/resize cursor to the rest of the game
        super.removed();
    }

    // ---- OS cursor ------------------------------------------------------------------------------

    /** 0 = default arrow; otherwise a GLFW standard cursor shape. Set only on change. */
    private void applyCursor(int shape) {
        if (shape == cursorShape || this.minecraft == null) return;
        cursorShape = shape;
        long win = this.minecraft.getWindow().handle();
        if (shape == 0) {
            GLFW.glfwSetCursor(win, 0L);
        } else {
            long cur = CURSORS.computeIfAbsent(shape, GLFW::glfwCreateStandardCursor);
            GLFW.glfwSetCursor(win, cur);
        }
    }

    /** Move cursor over draggables (segments, pane rows), NS-resize over stop handles. */
    private void updateCursor(int mouseX, int mouseY) {
        if (pendingExit != null || expandedPreview != 0) {
            applyCursor(0);
            return;
        }
        if (dragStop >= 0) {
            applyCursor(GLFW.GLFW_RESIZE_NS_CURSOR);
            return;
        }
        if (dragSeg >= 0 || paneDragging) {
            applyCursor(GLFW.GLFW_RESIZE_ALL_CURSOR);
            return;
        }
        if (stopHandleAt(mouseX, mouseY) >= 0) {
            applyCursor(GLFW.GLFW_RESIZE_NS_CURSOR);
            return;
        }
        if (segmentAt(mouseX, mouseY) >= 0) {
            applyCursor(GLFW.GLFW_RESIZE_ALL_CURSOR);
            return;
        }
        int cx = paneX(), ly = COL_TOP + 24 - scroll;
        if (mouseX >= cx && mouseX <= cx + paneW() && mouseY >= ly && mouseY <= ly + LIST_H) {
            int idx = leftScroll + (int) ((mouseY - ly) / 18.0);
            if (idx >= 0 && idx < leftRows.size()) {
                applyCursor(GLFW.GLFW_RESIZE_ALL_CURSOR);
                return;
            }
        }
        applyCursor(0);
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
        // Full-page rgba(0,0,0,0.3) underlay so the UI reads against any world (before super →
        // beneath the widgets).
        g.fill(0, 0, this.width, this.height, 0x4D000000);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        renderPreviewArea(g);
        renderNameRow(g);
        renderSettingsHeadings(g);
        renderPaneList(g, mouseX, mouseY);
        renderStrip(g, mouseX, mouseY);
        renderHelpIcons(g);
        renderPageScrollbar(g);
        renderTitleBar(g);
        updateToggleStates();
        renderToggleIcons(g);
        renderDisabledHints(g, mouseX, mouseY);
        renderPaneDragChip(g);
        renderHelpPopup(g, mouseX, mouseY);
        if (expandedPreview != 0) renderExpandedOverlay(g, mouseX, mouseY);
        if (pendingExit != null) renderDiscardConfirm(g, mouseX, mouseY);
        updateCursor(mouseX, mouseY);
    }

    private void updateToggleStates() {
        if (orderBtn != null) orderBtn.active = orderEnabled();
        if (curveBtn != null) curveBtn.active = curveEnabled();
    }

    /** The Order/Curve buttons carry icons drawn from live state, so Custom shows instantly. */
    private void renderToggleIcons(GuiGraphicsExtractor g) {
        if (orderBtn != null && orderBtn.visible) drawOrderIcon(g, orderBtn);
        if (curveBtn != null && curveBtn.visible) drawCurveIcon(g, curveBtn);
    }

    /** Order: palette (colour) or sun (brightness) + an asc/desc arrow; palette + C for Custom. */
    private void drawOrderIcon(GuiGraphicsExtractor g, AbstractWidget b) {
        boolean dim = !b.active;
        int cx = b.getX() + b.getWidth() / 2, cy = b.getY() + b.getHeight() / 2;
        PaletteOrder ord = editing.order;
        boolean sun = ord.byBrightness();
        int iconX = cx - 10, iconY = cy - 5;
        if (ord == PaletteOrder.CUSTOM) {
            drawPaletteIcon(g, iconX, iconY, dim);
            g.text(this.font, "C", cx + 4, cy - 4, dim ? 0xFF808080 : WHITE);
            return;
        }
        if (sun) drawSunIcon(g, iconX, iconY, dim);
        else drawPaletteIcon(g, iconX, iconY, dim);
        drawArrowIcon(g, cx + 5, cy - 4, !ord.descending(), dim);
    }

    /** Curve: the current easing drawn as a little line; Custom = a pronounced bell + C. */
    private void drawCurveIcon(GuiGraphicsExtractor g, AbstractWidget b) {
        boolean dim = !b.active;
        int color = dim ? 0xFF808080 : WHITE;
        int w = Math.min(26, b.getWidth() - 12), h = 10;
        boolean custom = editing.curve == CurveFunction.CUSTOM;
        int x0 = b.getX() + (b.getWidth() - w - (custom ? 8 : 0)) / 2;
        int y0 = b.getY() + (b.getHeight() + h) / 2 - 1;
        for (int i = 0; i < w; i++) {
            double t = i / (double) (w - 1);
            double v = custom
                    ? Math.exp(-Math.pow(t - 0.5, 2) / (2 * 0.15 * 0.15)) // pronounced bell
                    : editing.curve.apply(t);
            int py = y0 - (int) Math.round(v * h);
            g.fill(x0 + i, py - 1, x0 + i + 1, py + 1, color);
        }
        if (custom) g.text(this.font, "C", x0 + w + 3, b.getY() + (b.getHeight() - 8) / 2, color);
    }

    /** A tiny painter's palette: ring outline, three paint dots, a thumb notch. */
    private static void drawPaletteIcon(GuiGraphicsExtractor g, int x, int y, boolean dim) {
        int c = dim ? 0xFF707070 : 0xFFD8D8D8;
        // Blob outline (a squashed 11×10 ring with a bite at the bottom-right).
        g.fill(x + 3, y, x + 8, y + 1, c);
        g.fill(x + 1, y + 1, x + 3, y + 2, c);
        g.fill(x + 8, y + 1, x + 10, y + 2, c);
        g.fill(x, y + 2, x + 1, y + 7, c);
        g.fill(x + 10, y + 2, x + 11, y + 5, c);
        g.fill(x + 1, y + 7, x + 3, y + 8, c);
        g.fill(x + 3, y + 8, x + 8, y + 9, c);
        g.fill(x + 8, y + 7, x + 10, y + 8, c);
        g.fill(x + 8, y + 5, x + 9, y + 6, c);  // thumb-notch bite
        // Paint dots.
        g.fill(x + 3, y + 2, x + 4, y + 3, dim ? 0xFF804040 : 0xFFFF5555);
        g.fill(x + 6, y + 2, x + 7, y + 3, dim ? 0xFF408040 : 0xFF55FF55);
        g.fill(x + 4, y + 5, x + 5, y + 6, dim ? 0xFF404080 : 0xFF5599FF);
    }

    /** A tiny sun: 3×3 core + rays. */
    private static void drawSunIcon(GuiGraphicsExtractor g, int x, int y, boolean dim) {
        int c = dim ? 0xFF707070 : 0xFFFFE34D;
        g.fill(x + 4, y + 3, x + 7, y + 6, c);          // core
        g.fill(x + 5, y, x + 6, y + 2, c);              // N ray
        g.fill(x + 5, y + 7, x + 6, y + 9, c);          // S
        g.fill(x, y + 4, x + 2, y + 5, c);              // W
        g.fill(x + 9, y + 4, x + 11, y + 5, c);         // E
        g.fill(x + 2, y + 1, x + 3, y + 2, c);          // diagonals
        g.fill(x + 8, y + 1, x + 9, y + 2, c);
        g.fill(x + 2, y + 7, x + 3, y + 8, c);
        g.fill(x + 8, y + 7, x + 9, y + 8, c);
    }

    /** A small up/down arrow (triangle head + stem). */
    private static void drawArrowIcon(GuiGraphicsExtractor g, int x, int y, boolean up, boolean dim) {
        int c = dim ? 0xFF707070 : 0xFFD8D8D8;
        if (up) {
            g.fill(x + 2, y, x + 3, y + 1, c);
            g.fill(x + 1, y + 1, x + 4, y + 2, c);
            g.fill(x, y + 2, x + 5, y + 3, c);
            g.fill(x + 2, y + 3, x + 3, y + 8, c);
        } else {
            g.fill(x + 2, y, x + 3, y + 5, c);
            g.fill(x, y + 5, x + 5, y + 6, c);
            g.fill(x + 1, y + 6, x + 4, y + 7, c);
            g.fill(x + 2, y + 7, x + 3, y + 8, c);
        }
    }

    /** Short hover hints on the disabled toggles, explaining how to enable them. */
    private void renderDisabledHints(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (pendingExit != null || expandedPreview != 0) return;
        String hint = null;
        if (orderBtn != null && !orderBtn.active && overWidget(orderBtn, mouseX, mouseY)) {
            hint = "Add at least 2 blocks to sort";
        } else if (curveBtn != null && !curveBtn.active && overWidget(curveBtn, mouseX, mouseY)) {
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

    private static boolean overWidget(AbstractWidget w, int mx, int my) {
        return w.visible && mx >= w.getX() && mx <= w.getX() + w.getWidth()
                && my >= w.getY() && my <= w.getY() + w.getHeight();
    }

    private void renderTitleBar(GuiGraphicsExtractor g) {
        g.fill(0, 0, this.width, BAR_H, 0xE0000000);
        g.fill(0, BAR_H, this.width, BAR_H + 1, 0x60FFFFFF);
        int textY = (BAR_H - this.font.lineHeight) / 2 + 1;
        g.text(this.font, "FW Paint — Edit palette", 8, textY, WHITE);
        String[] names = GradientScreen.barTabNames();
        int[] xs = tabXs();
        for (int i = 0; i < names.length; i++) {
            boolean cur = i == GradientScreen.paletteBarIndex();
            g.text(this.font, cur ? "» " + names[i] : names[i], xs[i * 2], textY, cur ? WHITE : GREY);
        }
    }

    private void renderNameRow(GuiGraphicsExtractor g) {
        int cx = contentX();
        int total = SET_W + COL_GAP + paneW() + COL_GAP + RIGHT_W;
        int labelY = NAME_LABEL_Y - scroll;
        if (labelY >= BAR_H + 2 && labelY <= viewBottom() - 8) {
            g.text(this.font, "Name:", cx, labelY, GREY);
            if (!nameError.isEmpty()) {
                g.text(this.font, nameError, cx + this.font.width("Name: ") + 4, labelY, RED);
            }
        }
        // Separator between the name row and the settings, spanning all three columns.
        int sepY = SEP_Y - scroll;
        if (sepY >= BAR_H + 2 && sepY <= viewBottom()) {
            g.fill(cx, sepY, cx + total, sepY + 1, 0x50FFFFFF);
        }
    }

    /** Group headings over the left settings column. */
    private void renderSettingsHeadings(GuiGraphicsExtractor g) {
        int x = contentX();
        int y1 = COL_TOP - scroll;
        if (y1 >= BAR_H + 2 && y1 <= viewBottom() - 10) {
            g.text(this.font, "Gradient & noise", x, y1 + 2, LIGHT);
            g.fill(x, y1 + 11, x + SET_W - 14, y1 + 12, 0x60FFFFFF);
        }
        int y2 = noiseHeadingY - scroll;
        if (y2 >= BAR_H + 2 && y2 <= viewBottom() - 10) {
            g.text(this.font, "Noise only", x, y2 + 2, LIGHT);
            g.fill(x, y2 + 11, x + SET_W - 14, y2 + 12, 0x60FFFFFF);
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

    // ---- block pane (middle) --------------------------------------------------------------------

    private void renderPaneList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int cx = paneX(), lw = paneW();
        int ly = COL_TOP + 24 - scroll;
        int lh = LIST_H;
        if (ly + lh < BAR_H || ly > viewBottom()) return;
        g.fill(cx, ly, cx + lw, ly + lh, 0x90000000);
        outline(g, cx, ly, lw, lh, 0xA0FFFFFF);
        g.enableScissor(cx, Math.max(BAR_H + 1, ly), cx + lw, Math.min(viewBottom(), ly + lh));
        for (int i = leftScroll; i < leftRows.size(); i++) {
            int ry = ly + (i - leftScroll) * 18;
            if (ry >= ly + lh) break;
            LRow row = leftRows.get(i);
            boolean hover = mouseX >= cx && mouseX <= cx + lw && mouseY >= ry && mouseY < ry + 18;
            if (hover) g.fill(cx + 1, ry, cx + lw - 1, ry + 18, HOVER_BG);
            if (row.auto() != null) {
                PaletteListPanel.drawCrosshatch(g, cx + 2, ry + 1, 16);
                g.text(this.font, this.font.plainSubstrByWidth(row.name(), lw - 26),
                        cx + 21, ry + 5, 0xFFC8C8C8);
            } else {
                g.item(row.stack(), cx + 2, ry + 1);
                boolean banned = editing.autoExclude.contains(row.id());
                g.text(this.font, this.font.plainSubstrByWidth(row.name(), lw - 26),
                        cx + 21, ry + 5, banned ? RED : WHITE);
            }
        }
        g.disableScissor();
        int maxLeft = Math.max(0, leftRows.size() - lh / 18);
        if (maxLeft > 0) {
            int trackX = cx + lw - 3;
            int thumbH = Math.max(8, lh * (lh / 18) / leftRows.size());
            int thumbY = ly + (int) ((lh - thumbH) * (double) leftScroll / maxLeft);
            g.fill(trackX, ly + 1, trackX + 2, ly + lh - 1, 0x30FFFFFF);
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0x90FFFFFF);
        }
        int hintY = ly + lh + 4;
        g.text(this.font, this.font.plainSubstrByWidth("Dbl-click or drag: add to the strip", lw), cx, hintY, LIGHT);
        g.text(this.font, this.font.plainSubstrByWidth("Right-click: ban from Automatic", lw), cx, hintY + 11, LIGHT);
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
        int count = editing.segments.size();
        int sx = stripX(), sy = stripY();
        outline(g, sx - 1, sy - 1, STRIP_W + 2, STRIP_H + 2, 0x60FFFFFF);
        if (count == 0) {
            // Empty strip: the "select a block" placeholder, stacked vertically down the middle.
            String hint = "SELECT A BLOCK";
            int ty = sy + (STRIP_H - hint.length() * 9) / 2;
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
        int bottom = stripY() + STRIP_H - 8;
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

    // ---- previews (both, side by side) ----------------------------------------------------------

    /** {x, y(base), w, h} of a preview half: false = cylinder (left), true = noise cube (right). */
    private int[] previewHalf(boolean noise) {
        int total = SET_W + COL_GAP + paneW() + COL_GAP + RIGHT_W;
        int half = Math.min(220, (total - 8) / 2);
        int x0 = (this.width - (2 * half + 8)) / 2;
        return new int[]{noise ? x0 + half + 8 : x0, PREVIEW_Y, half, PREVIEW_H};
    }

    private void renderPreviewArea(GuiGraphicsExtractor g) {
        int[] cyl = previewHalf(false);
        int[] noise = previewHalf(true);
        int y = cyl[1] - scroll;
        if (y + cyl[3] < BAR_H || y > viewBottom()) return;
        if (editing.segments.isEmpty()) {
            String s = "Add blocks to preview";
            int mid = (cyl[0] + noise[0] + noise[2]) / 2;
            g.text(this.font, s, mid - this.font.width(s) / 2, y + cyl[3] / 2 - 4, YELLOW);
            return;
        }
        g.enableScissor(cyl[0], Math.max(BAR_H + 1, y), cyl[0] + cyl[2], Math.min(viewBottom(), y + cyl[3]));
        drawCylinderPreview(g, cyl[0], y, cyl[2], cyl[3], 1f);
        g.disableScissor();
        g.enableScissor(noise[0], Math.max(BAR_H + 1, y), noise[0] + noise[2], Math.min(viewBottom(), y + noise[3]));
        drawNoisePreview(g, noise[0], y, noise[2], noise[3], 1f);
        g.disableScissor();
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
                cylCache[i][j] = band.isEmpty() ? null : band.get(rnd.nextInt(band.size()));
                cylTint[i][j] = band.isEmpty() && idx < segTints.size() ? segTints.get(idx) : 0;
            }
        }
    }

    /** Per segment: its stack + variation alternates from the source (empty list = crosshatch). */
    private List<List<ItemStack>> previewBands() {
        List<List<ItemStack>> out = new ArrayList<>();
        double thresh = Math.max(0, Math.min(1, editing.variation)) * 127.5;
        for (int k = 0; k < editing.segments.size(); k++) {
            PaletteSegment seg = editing.segments.get(k);
            if (seg.isAutomatic() || segStacks.get(k).isEmpty()) {
                out.add(List.of());
                continue;
            }
            List<ItemStack> band = new ArrayList<>();
            band.add(segStacks.get(k));
            if (thresh > 0) {
                Block base = Gradient.blockOfItemId(seg.block);
                int rgb = base == null ? 0 : BlockTextures.gradientValue(base, null, GradientMode.COLOR, 0.5);
                for (LRow row : leftRows) {
                    if (row.auto() != null || row.id().equals(seg.block)) continue;
                    if (ColorOrder.oklabDist(row.rgb(), rgb) * 255.0 <= thresh) band.add(row.stack());
                }
            }
            out.add(band);
        }
        return out;
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
                    List<ItemStack> band = bands.get(idx);
                    if (!band.isEmpty()) st = band.get(cell.nextInt(band.size()));
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
            int[] b = previewHalf(true);
            s = 1f;
            n = Math.max(2, Math.min(10, 1 + (int) Math.min((b[2] - 16) / (2 * ISO_X),
                    (b[3] - 16) / (2 * ISO_DOWN + ISO_UP))));
            float cw = 2 * (n - 1) * ISO_X + 16;
            tx = b[0] + (b[2] - cw) / 2f;
            ty = b[1] - scroll + (b[3] - ((n - 1) * (2 * ISO_DOWN + ISO_UP) + 16)) / 2f;
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
