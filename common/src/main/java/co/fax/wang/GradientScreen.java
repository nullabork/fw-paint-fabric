package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import co.fax.wang.palette.MissingBlockPolicy;
import co.fax.wang.palette.Palette;
import co.fax.wang.palette.PaletteKind;
import co.fax.wang.palette.PaletteSegment;
import co.fax.wang.palette.PaletteStore;
import co.fax.wang.ui.FlatButton;
import co.fax.wang.ui.FlowColumn;
import co.fax.wang.ui.Segmented;
import co.fax.wang.ui.Toggle;
import co.fax.wang.ui.Ui;
import co.fax.wang.ui.ValueSlider;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The FW Paint screen (opened — and closed again — by the open key, default K). Five tabs:
 * <ul>
 *   <li><b>Paint</b> — what gets placed: the "Paint with" switch (Solid / Gradient / Noise /
 *       Pattern, same as the wheel) over that paint type's library — the gradient palettes
 *       (Gradient, Noise), the patterns (Pattern) with Use / Edit / Duplicate / Delete and a
 *       preview, or for Solid the block list and how the painted block is chosen;</li>
 *   <li><b>Place</b> — where it goes: the placement mode tiles, grouped like the wheel's Place
 *       ring ({@link PlacementMode#groups()}), with the mode's Options and the marker settings;</li>
 *   <li><b>Finder</b> — every block ranked by colour or brightness ({@link ColorIndex}), centred
 *       on a chosen reference block or pure colour, to discover close blocks you don't have;</li>
 *   <li><b>Settings</b> — the paint tool, colour maths, missing-block policy, paint memory, HUD
 *       position and a read-only list of the keys and gestures;</li>
 *   <li><b>Help</b> — the in-game manual (the drill-down {@link HelpPanel}).</li>
 * </ul>
 * Controls come from the shared kit in {@code co.fax.wang.ui}; the columns of settings are
 * {@link FlowColumn}s so they scroll in a short window. Each section has one "?" (hover for a
 * summary, click to open its Help topic).
 */
public class GradientScreen extends Screen {

    private static final int BAR_H = 22;
    private static final int BAR_BG = 0x80000000;
    private static final int TOP = 30;          // first content row under the title bar
    private static final int LIB_Y = 52;        // Paint tab: library top, under "Paint with"
    private static final int GAP = 14;          // between the two columns
    private static final int ROW_LINE = 0xFF3E3E3E;

    // Legacy two-column layout (Finder + Help).
    private static final int LEFT_X = 10;
    private static final int COL_W_MAX = 200;
    private static final int COL_GAP = 12;

    private static final int LIST_ROW_H = 18;   // Solid block list + paint-tool list

    private enum Tab { PAINT, PLACE, FINDER, SETTINGS, HELP }

    /** Title-bar order, left to right (right-aligned as a group). */
    private static final Tab[] BAR_ORDER = Tab.values();

    /** Paint types in "Paint with" order. */
    private static final PaintType[] PAINT_ORDER =
            {PaintType.SOLID, PaintType.GRADIENT, PaintType.NOISE, PaintType.PATTERN};

    private record SourceBlock(String id, ItemStack stack, Block block) {}
    private record ToolRow(String id, String name, ItemStack stack) {}

    private static Tab tab = Tab.PAINT;

    /** Every part on the current tab drawn by the kit (switches, buttons, settings columns). */
    private final List<FlowColumn> flows = new ArrayList<>();
    private final List<Segmented> switches = new ArrayList<>();
    private final List<FlatButton> buttons = new ArrayList<>();

    // One "?" per section: hover for a popup describing it, click to open its Help topic.
    // Rebuilt every frame (the sections move when a column scrolls).
    private record HelpSpot(int x, int y, String text, String topic) {}
    private final List<HelpSpot> helpSpots = new ArrayList<>();
    private HelpSpot hoverSpot;
    private long hoverSince;

    // Help tab state — static so the manual keeps its expansion + scroll across reopening (like tab).
    private static HelpPanel help;
    /** Help topic to open + scroll to the next time the Help tab lays out (see {@link #atHelpTopic}). */
    private static String pendingHelpTopic;

    // ---- Paint tab state (selection static so it survives reopen + editor round-trips) ----------
    private PaletteListPanel paletteList;
    private static String paletteSelectedId = "";
    private String confirmDeleteId; // non-null → the delete confirmation modal is up
    private static double noisePanX, noisePanZ;

    // Solid view: left-click uses the block, right-click toggles its exclusion.
    private record SolidRow(SourceBlock sb, boolean selected, boolean excluded) {}
    private List<SourceBlock> sourceBlocks = new ArrayList<>();
    private final List<SolidRow> solidRows = new ArrayList<>();
    private int solidScroll;
    private int solidListY, solidListH;

    // ---- Place tab ------------------------------------------------------------------------------
    private PlacementMode hoveredMode; // tile under the mouse this frame (its description shows)

    // ---- Settings tab ---------------------------------------------------------------------------
    private EditBox filterBox;
    private final List<ToolRow> matches = new ArrayList<>();
    private static String filter = "";
    private int toolScroll;
    private int toolListY, toolListH;

    // ---- Finder tab (static so the sort mode and reference survive reopening) -------------------
    private enum FinderSort { COLOR, BRIGHTNESS }
    private enum FinderRef { BLOCK, COLOR }
    private static FinderSort finderSort = FinderSort.COLOR;
    private static FinderRef finderRef = FinderRef.BLOCK; // set by whichever picker was clicked last
    private static String finderSelectedId = "";
    private static double finderHue = 0, finderSat = 1, finderBri = 1;
    private FinderListPanel finderList; // left: all blocks in the active ordering (click to select)
    private ColorField2D finderField;   // right: hue×sat field (click for a pure-colour reference)
    private ValueSlider finderBriSlider;
    private int finderHeaderY, finderFieldY, finderSliderY, finderSwatchY;

    public GradientScreen() {
        this(true);
    }

    /** @param landOnPaint false keeps the last tab (used when returning from an editor). */
    public GradientScreen(boolean landOnPaint) {
        super(Component.literal("FW Paint"));
        if (ConfigManager.get().paintTool.isEmpty()) {
            tab = Tab.SETTINGS; // no tool assigned yet — land where the tool picker lives
        } else if (landOnPaint) {
            tab = Tab.PAINT;
        }
    }

    /**
     * Open on the Paint tab with {@code id} selected (used when an editor saves). When the item
     * is of a kind the current paint type doesn't show (a pattern while painting gradients), the
     * paint type follows it so the saved item is in view.
     */
    public static GradientScreen openOnPalette(String id) {
        GradientScreen s = new GradientScreen(false);
        tab = Tab.PAINT;
        paletteSelectedId = id == null ? "" : id;
        Palette p = PaletteStore.byId(paletteSelectedId);
        GradientConfig cfg = ConfigManager.get();
        if (p != null && Gradient.kindFor(cfg.activePaintType) != p.kind) {
            cfg.activePaintType = p.kind == PaletteKind.PATTERN ? PaintType.PATTERN : PaintType.GRADIENT;
            ConfigManager.save();
        }
        return s;
    }

    /** Open on the Paint tab, keeping its selection (an editor's Cancel). */
    public static GradientScreen atPaintTab() {
        GradientScreen s = new GradientScreen(false);
        tab = Tab.PAINT;
        return s;
    }

    /** Open on the Place tab (the wheel's Place ring "More…"). */
    public static GradientScreen atPlaceTab() {
        GradientScreen s = new GradientScreen(false);
        tab = Tab.PLACE;
        return s;
    }

    /** Open the screen on the Help tab with {@code title}'s topic expanded and scrolled to the top. */
    public static GradientScreen atHelpTopic(String title) {
        GradientScreen s = new GradientScreen(false);
        tab = Tab.HELP;
        pendingHelpTopic = title;
        return s;
    }

    /** For the editors' + wheel's title bars: open the screen on a specific {@link #BAR_ORDER} tab. */
    public static GradientScreen atBarTab(int barIndex) {
        GradientScreen s = new GradientScreen(false);
        tab = BAR_ORDER[Math.max(0, Math.min(BAR_ORDER.length - 1, barIndex))];
        return s;
    }

    /** The title-bar tab names, in bar order (shared with the editors + wheel so the bars match). */
    public static String[] barTabNames() {
        String[] out = new String[BAR_ORDER.length];
        for (int i = 0; i < BAR_ORDER.length; i++) out[i] = tabName(BAR_ORDER[i]);
        return out;
    }

    /** Index of the Paint tab in {@link #BAR_ORDER} — where the palette/pattern library lives. */
    static int paletteBarIndex() {
        return Tab.PAINT.ordinal();
    }

    private static String tabName(Tab t) {
        return switch (t) {
            case PAINT -> "Paint"; case PLACE -> "Place"; case FINDER -> "Finder";
            case SETTINGS -> "Settings"; case HELP -> "Help";
        };
    }

    // ---- layout helpers -------------------------------------------------------------------------

    private int bottom() { return this.height - 32; }

    /** Two columns {x, leftW, rightX, rightW} inside at most {@code maxW}, left share clamped. */
    private int[] columns(int maxW, double leftShare, int leftMin, int leftMax) {
        int w = Math.max(200, Math.min(maxW, this.width - 20));
        int x = (this.width - w) / 2;
        int lw = Math.max(leftMin, Math.min(leftMax, (int) (w * leftShare)));
        lw = Math.min(lw, w - GAP - 100);
        return new int[] {x, lw, x + lw + GAP, w - lw - GAP};
    }

    private int[] paintCols() { return columns(560, 0.48, 140, 260); }
    private int[] placeCols() {
        int w = Math.max(200, Math.min(620, this.width - 20));
        int right = Math.max(150, Math.min(220, (int) (w * 0.36)));
        return columns(620, (w - right - GAP) / (double) w, 120, 1000);
    }
    private int[] settingsCols() { return columns(560, 0.42, 140, 220); }

    // Legacy Finder/Help layout.
    private int colW() {
        int avail = this.width - 2 * LEFT_X;
        return Math.max(60, Math.min(COL_W_MAX, (avail - COL_GAP) / 2));
    }
    private int contentX() {
        int total = 2 * colW() + COL_GAP;
        return Math.max(LEFT_X, (this.width - total) / 2);
    }
    private int rightX() { return contentX() + colW() + COL_GAP; }

    @Override
    protected void init() {
        flows.clear();
        switches.clear();
        buttons.clear();
        // Drop the previous tab's/view's panels so input routing only sees what's on screen.
        paletteList = null;
        solidListH = 0;
        toolListH = 0;
        finderList = null;
        finderField = null;
        finderBriSlider = null;
        switch (tab) {
            case PAINT -> initPaintTab();
            case PLACE -> initPlaceTab();
            case FINDER -> initFinderTab();
            case SETTINGS -> initSettingsTab();
            case HELP -> initHelpTab();
        }
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(this.width / 2 - 50, this.height - 26, 100, 20).build());
    }

    private void setTab(Tab t) { tab = t; rebuildWidgets(); }

    private void openHelp(String topic) {
        pendingHelpTopic = topic;
        setTab(Tab.HELP);
    }

    // ---- shared rows ----------------------------------------------------------------------------

    /** A section heading with an optional live note and one "?" (hover text + Help topic). */
    private FlowColumn.Row sectionRow(FlowColumn col, String title, Supplier<String> note,
                                      String helpText, String helpTopic) {
        return new FlowColumn.Row() {
            public int height(Font f) { return Ui.SECTION_H; }
            public void render(GuiGraphicsExtractor g, Font f, int x, int y, int w, int mx, int my) {
                boolean hasHelp = helpText != null;
                Ui.section(g, f, x, y, hasHelp ? w - 13 : w, title, note == null ? null : note.get());
                if (hasHelp) {
                    g.fill(x + w - 13, y + f.lineHeight + 1, x + w, y + f.lineHeight + 2, Ui.LINE);
                    if (col.inside(x + w - 5, y + 4)) addHelpSpot(x + w - 10, y - 1, helpText, helpTopic);
                }
            }
        };
    }

    private void addHelpSpot(int x, int y, String text, String topic) {
        helpSpots.add(new HelpSpot(x, y, text, topic));
    }

    private FlowColumn.Row toggleRow(Toggle t) {
        return new FlowColumn.Row() {
            public int height(Font f) { return t.height(f); }
            public void render(GuiGraphicsExtractor g, Font f, int x, int y, int w, int mx, int my) {
                t.setBounds(x, y, w).render(g, f, mx, my);
            }
            public boolean click(double mx, double my, int button) {
                return button == InputConstants.MOUSE_BUTTON_LEFT && t.click(mx, my, font);
            }
        };
    }

    /** A label and a switch: side by side when the column is wide enough, else stacked. */
    private FlowColumn.Row switchRow(String label, Segmented s, int colW, BooleanSupplier applies) {
        boolean stacked = colW < 170 || this.font.width(label) > colW * 0.45;
        return new FlowColumn.Row() {
            public int height(Font f) { return stacked ? Ui.ROW_H + f.lineHeight + 2 : Ui.ROW_H; }
            public void render(GuiGraphicsExtractor g, Font f, int x, int y, int w, int mx, int my) {
                int color = applies.getAsBoolean() ? Ui.TEXT : Ui.DIM;
                if (stacked) {
                    g.text(f, f.plainSubstrByWidth(label, w), x, y + 1, color);
                    s.setBounds(x, y + f.lineHeight + 2, w);
                } else {
                    int lw = (int) (w * 0.45);
                    g.text(f, f.plainSubstrByWidth(label, lw - 4), x, y + (Ui.ROW_H - f.lineHeight) / 2 + 1, color);
                    s.setBounds(x + lw, y, w - lw);
                }
                s.render(g, f, mx, my);
            }
            public boolean click(double mx, double my, int button) {
                return button == InputConstants.MOUSE_BUTTON_LEFT && s.click(mx, my);
            }
        };
    }

    private FlowColumn.Row sliderRow(ValueSlider s) {
        return new FlowColumn.Row() {
            public int height(Font f) { return s.height(); }
            public void render(GuiGraphicsExtractor g, Font f, int x, int y, int w, int mx, int my) {
                s.setBounds(x, y, w).render(g, f, mx, my);
            }
            public boolean click(double mx, double my, int button) {
                return button == InputConstants.MOUSE_BUTTON_LEFT && s.click(mx, my);
            }
            public boolean drag(double mx, double my) { return s.drag(mx); }
            public boolean release() { return s.release(); }
        };
    }

    /** Buttons sharing one row, widths in proportion to their labels. */
    private FlowColumn.Row buttonRow(FlatButton... row) {
        return new FlowColumn.Row() {
            public int height(Font f) { return Ui.ROW_H; }
            public void render(GuiGraphicsExtractor g, Font f, int x, int y, int w, int mx, int my) {
                int total = 0;
                for (FlatButton b : row) total += b.preferredWidth(f);
                int avail = w - 4 * (row.length - 1);
                int bx = x;
                for (int i = 0; i < row.length; i++) {
                    int bw = i == row.length - 1 ? x + w - bx
                            : Math.max(16, avail * row[i].preferredWidth(f) / Math.max(1, total));
                    row[i].setBounds(bx, y, bw).render(g, f, mx, my);
                    bx += bw + 4;
                }
            }
            public boolean click(double mx, double my, int button) {
                if (button != InputConstants.MOUSE_BUTTON_LEFT) return false;
                for (FlatButton b : row) if (b.click(mx, my)) return true;
                return false;
            }
        };
    }

    /** Word-wrapped live text in one colour. {@code colW} sizes the row before its first render. */
    private FlowColumn.Row textRow(Supplier<String> text, int color, int colW) {
        return new FlowColumn.Row() {
            public int height(Font f) {
                String t = text.get();
                return t == null || t.isEmpty() ? 0 : wrapText(t, colW).size() * (f.lineHeight + 1);
            }
            public void render(GuiGraphicsExtractor g, Font f, int x, int y, int w, int mx, int my) {
                String t = text.get();
                if (t == null || t.isEmpty()) return;
                for (String line : wrapText(t, colW)) {
                    g.text(f, line, x, y, color);
                    y += f.lineHeight + 1;
                }
            }
        };
    }

    private static FlowColumn.Row gapRow(int h) {
        return new FlowColumn.Row() {
            public int height(Font f) { return h; }
            public void render(GuiGraphicsExtractor g, Font f, int x, int y, int w, int mx, int my) {}
        };
    }

    private FlowColumn flow(int x, int y, int w, int h) {
        FlowColumn c = new FlowColumn().setBounds(x, y, w, h);
        flows.add(c);
        return c;
    }

    // ---- Paint tab ------------------------------------------------------------------------------

    private void initPaintTab() {
        GradientConfig cfg = ConfigManager.get();
        int[] c = paintCols();
        int total = c[3] + c[2] - c[0];
        int labelW = this.font.width("PAINT WITH") + 8;
        List<String> names = new ArrayList<>();
        int sel = 0;
        for (int i = 0; i < PAINT_ORDER.length; i++) {
            names.add(PAINT_ORDER[i].label());
            if (PAINT_ORDER[i] == cfg.activePaintType) sel = i;
        }
        Segmented paintWith = new Segmented(names, sel, i -> {
            ConfigManager.get().activePaintType = PAINT_ORDER[i];
            ConfigManager.save();
            rebuildWidgets(); // the library below follows the paint type
        });
        paintWith.setBounds(c[0] + labelW, TOP - 2, Math.min(260, total - labelW));
        switches.add(paintWith);

        if (cfg.activePaintType == PaintType.SOLID) initSolidView(c);
        else initLibraryView(c, Gradient.kindFor(cfg.activePaintType));
    }

    private void renderPaintTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int[] c = paintCols();
        g.text(this.font, "PAINT WITH", c[0], TOP + 3, Ui.HINT);
        Segmented s = switches.isEmpty() ? null : switches.get(0);
        if (s != null) {
            int nx = s.x() + s.width() + 10;
            String note = "Same as wheel › Paint";
            if (nx + this.font.width(note) <= c[2] + c[3]) g.text(this.font, note, nx, TOP + 3, Ui.HINT);
        }
        if (ConfigManager.get().activePaintType == PaintType.SOLID) renderSolidView(g, mouseX, mouseY, c);
        else renderLibraryView(g, mouseX, mouseY, c);
        if (confirmDeleteId != null) renderDeleteConfirm(g, mouseX, mouseY);
    }

    // ---- Paint tab: palette / pattern library ---------------------------------------------------

    private PaletteKind libraryKind() {
        PaletteKind k = Gradient.kindFor(ConfigManager.get().activePaintType);
        return k == null ? PaletteKind.GRADIENT : k;
    }

    private boolean libraryIsPattern() { return libraryKind() == PaletteKind.PATTERN; }

    private void initLibraryView(int[] c, PaletteKind kind) {
        boolean pattern = kind == PaletteKind.PATTERN;
        String newLabel = pattern ? "+ New pattern" : "+ New palette";
        int bw = this.font.width(newLabel) + 12;
        buttons.add(new FlatButton(newLabel, FlatButton.Style.PLAIN, () -> openEditor(null, pattern))
                .setBounds(c[0] + c[1] - bw, LIB_Y, bw));

        paletteList = new PaletteListPanel(this.font);
        paletteList.setBounds(c[0], LIB_Y + 22, c[1], bottom() - (LIB_Y + 22) - 14);
        paletteList.setActiveId(activeIdOf(kind));
        rebuildPaletteEntries();
        // Keep the remembered selection only when it's in this list; else select what's in use.
        if (PaletteStore.byId(paletteSelectedId) == null || PaletteStore.byId(paletteSelectedId).kind != kind) {
            paletteSelectedId = activeIdOf(kind);
        }
        paletteList.select(paletteSelectedId);
        paletteList.revealSelected();

        // Right: the selected item's panel.
        int rx = c[2], rw = c[3];
        FlowColumn detail = flow(rx, LIB_Y, rw, bottom() - LIB_Y);
        detail.add(new FlowColumn.Row() { // name + in-use tag
            public int height(Font f) { return f.lineHeight + 4; }
            public void render(GuiGraphicsExtractor g, Font f, int x, int y, int w, int mx, int my) {
                Palette p = selectedPalette();
                if (p == null) return;
                boolean inUse = p.id.equals(activeIdOf(p.kind));
                String tag = inUse ? "● in use" : "";
                g.text(f, f.plainSubstrByWidth(p.name, w - f.width(tag) - 6), x, y + 2, Ui.TEXT);
                if (inUse) g.text(f, tag, x + w - f.width(tag), y + 2, Ui.IN_USE);
            }
        });
        FlatButton use = new FlatButton("Use", FlatButton.Style.PRIMARY, this::useSelected);
        FlatButton edit = new FlatButton("Edit", FlatButton.Style.PLAIN, () -> {
            Palette p = selectedPalette();
            if (p != null) openEditor(p, p.kind == PaletteKind.PATTERN);
        });
        FlatButton dup = new FlatButton("Duplicate", FlatButton.Style.PLAIN, this::duplicateSelected);
        FlatButton del = new FlatButton("Delete", FlatButton.Style.DANGER, () -> {
            if (selectedPalette() != null) confirmDeleteId = paletteSelectedId;
        });
        detail.add(buttonRow(use, edit, dup, del));
        detail.add(gapRow(2));
        if (pattern) {
            detail.add(new FlowColumn.Row() { // pattern thumbnail
                public int height(Font f) { return selectedPalette() == null ? 0 : 44; }
                public void render(GuiGraphicsExtractor g, Font f, int x, int y, int w, int mx, int my) {
                    Palette p = selectedPalette();
                    if (p != null) PatternThumb.draw(g, p, x, y + 2, 40);
                }
            });
            detail.add(summaryRow("Pattern", () -> {
                Palette p = selectedPalette();
                return p == null ? List.of() : List.of(
                        p.width + "×" + p.height + " · Tile: " + p.tiling.label(),
                        "Start from: " + (p.startAtBottom ? "Bottom" : "Top"),
                        "Variation " + variationLabel(p),
                        "Blocks from " + p.source.displayName());
            }, rw));
        } else {
            // Both previews; the one for the current paint type first.
            boolean noiseFirst = ConfigManager.get().activePaintType == PaintType.NOISE;
            if (noiseFirst) detail.add(noisePreviewRow());
            detail.add(gradientPreviewRow());
            if (!noiseFirst) detail.add(noisePreviewRow());
            detail.add(summaryRow("Blocks", () -> {
                Palette p = selectedPalette();
                return p == null ? List.of() : List.of(
                        p.segments.size() + (p.segments.size() == 1 ? " segment" : " segments")
                                + " · from " + p.source.displayName(),
                        "Order: " + p.order.label() + " · Curve: " + p.curve.displayName());
            }, rw));
            detail.add(summaryRow("Gradient", () -> {
                Palette p = selectedPalette();
                if (p == null) return List.of();
                String sizing = p.sizing.label() + (p.sizing == co.fax.wang.palette.SizingMode.SET_STEPS
                        ? " (" + p.steps + ")" : "");
                return List.of("Variation " + variationLabel(p) + " · Chaos " + pct(p.chaos)
                                + " · Step length " + pct(p.stepWobble),
                        "Sizing: " + sizing);
            }, rw));
            detail.add(summaryRow("Noise", () -> {
                Palette p = selectedPalette();
                if (p == null) return List.of();
                String scale = p.noiseLock ? String.valueOf(Math.round(p.noiseScaleX))
                        : Math.round(p.noiseScaleX) + "/" + Math.round(p.noiseScaleY) + "/" + Math.round(p.noiseScaleZ);
                return List.of(p.noiseType.displayName() + " · Scale " + scale + (p.noiseLock ? " (locked)" : ""),
                        "Seed " + (p.noiseSeed == null || p.noiseSeed.isBlank() ? "none" : p.noiseSeed));
            }, rw));
        }
        detail.add(textRow(() -> {
            PaletteListPanel.Entry e = paletteList == null ? null : paletteList.selected();
            return e == null || !e.missing() ? ""
                    : "Missing " + e.missingNames().size() + ": " + String.join(", ", e.missingNames());
        }, Ui.MISSING, rw));
        detail.add(textRow(() -> selectedPalette() == null && !PaletteStore.allOf(kind).isEmpty()
                ? "Select a " + (pattern ? "pattern" : "palette") + " on the left." : "", Ui.HINT, rw));
    }

    private FlowColumn.Row gradientPreviewRow() {
        return new FlowColumn.Row() {
            public int height(Font f) { return selectedPalette() == null ? 0 : f.lineHeight + 16; }
            public void render(GuiGraphicsExtractor g, Font f, int x, int y, int w, int mx, int my) {
                Palette p = selectedPalette();
                if (p == null) return;
                g.text(f, "Gradient preview", x, y, Ui.HINT);
                int py = y + f.lineHeight + 1;
                g.fill(x, py, x + w, py + 12, 0x60000000);
                if (p.segments.isEmpty()) g.text(f, "No blocks yet", x + 3, py + 2, Ui.DIM);
                else PalettePreview.drawStrip(g, p, x, py, w, 12);
            }
        };
    }

    /** The noise slice; drag it to pan. */
    private FlowColumn.Row noisePreviewRow() {
        return new FlowColumn.Row() {
            int bx, by, bw, bh;
            boolean panning;
            double lastX, lastY;
            public int height(Font f) { return selectedPalette() == null ? 0 : f.lineHeight + 40; }
            public void render(GuiGraphicsExtractor g, Font f, int x, int y, int w, int mx, int my) {
                Palette p = selectedPalette();
                if (p == null) return;
                g.text(f, "Noise preview · drag to pan", x, y, Ui.HINT);
                bx = x; by = y + f.lineHeight + 1; bw = w; bh = 36;
                g.fill(bx, by, bx + bw, by + bh, 0x60000000);
                if (p.segments.isEmpty()) g.text(f, "No blocks yet", bx + 3, by + 2, Ui.DIM);
                else PalettePreview.drawNoise(g, p, bx, by, bw, bh, noisePanX, noisePanZ);
            }
            public boolean click(double mx, double my, int button) {
                if (button != InputConstants.MOUSE_BUTTON_LEFT || !Ui.inside(mx, my, bx, by, bw, bh)) return false;
                panning = true;
                lastX = mx;
                lastY = my;
                return true;
            }
            public boolean drag(double mx, double my) {
                if (!panning) return false;
                noisePanX -= (mx - lastX) / 3.0; // 3 px per block, like the slice draws
                noisePanZ -= (my - lastY) / 3.0;
                lastX = mx;
                lastY = my;
                return true;
            }
            public boolean release() {
                boolean was = panning;
                panning = false;
                return was;
            }
        };
    }

    /** A small labelled block of grey summary lines (empty list → no row). */
    private FlowColumn.Row summaryRow(String title, Supplier<List<String>> lines, int colW) {
        return new FlowColumn.Row() {
            public int height(Font f) {
                List<String> l = lines.get();
                return l.isEmpty() ? 0 : (l.size() + 1) * (f.lineHeight + 1) + 2;
            }
            public void render(GuiGraphicsExtractor g, Font f, int x, int y, int w, int mx, int my) {
                List<String> l = lines.get();
                if (l.isEmpty()) return;
                g.text(f, title, x, y + 2, Ui.TEXT);
                int yy = y + 2 + f.lineHeight + 1;
                for (String s : l) {
                    g.text(f, f.plainSubstrByWidth(s, w - 6), x + 6, yy, Ui.HINT);
                    yy += f.lineHeight + 1;
                }
            }
        };
    }

    private static String activeIdOf(PaletteKind kind) {
        Palette p = PaletteStore.activeOf(kind);
        return p == null ? "" : p.id;
    }

    private Palette selectedPalette() {
        Palette p = PaletteStore.byId(paletteSelectedId);
        return p != null && p.kind == libraryKind() ? p : null;
    }

    private void useSelected() {
        Palette p = selectedPalette();
        if (p == null) return;
        PaletteStore.setActive(p.id); // routes to the item's kind's active pointer
        if (paletteList != null) paletteList.setActiveId(activeIdOf(p.kind));
    }

    /** Duplicate = a copy named "<name> copy" (made unique), added and selected. */
    private void duplicateSelected() {
        Palette src = selectedPalette();
        if (src == null) return;
        List<String> names = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Palette p : PaletteStore.all()) {
            names.add(p.name);
            ids.add(p.id);
        }
        Palette copy = src.copy();
        copy.name = PaletteStore.uniqueName(src.name + " copy", names);
        copy.id = PaletteStore.slugFor(copy.name, ids);
        PaletteStore.upsert(copy);
        paletteSelectedId = copy.id;
        rebuildPaletteEntries();
        paletteList.select(copy.id);
        paletteList.revealSelected();
    }

    private void openEditor(Palette palette, boolean pattern) {
        if (this.minecraft == null) return;
        this.minecraft.setScreenAndShow(pattern ? new PatternEditScreen(palette) : new PaletteEditScreen(palette));
    }

    private void rebuildPaletteEntries() {
        if (paletteList == null) return;
        List<PaletteListPanel.Entry> entries = new ArrayList<>();
        for (Palette p : PaletteStore.allOf(libraryKind())) {
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

    private void renderLibraryView(GuiGraphicsExtractor g, int mouseX, int mouseY, int[] c) {
        boolean pattern = libraryIsPattern();
        int n = PaletteStore.allOf(libraryKind()).size();
        String title = (pattern ? "PATTERNS · " : "PALETTES · ") + n;
        int x = c[0], w = c[1];
        g.text(this.font, title, x, LIB_Y + 5, Ui.TEXT);
        int helpX = x + this.font.width(title) + 4;
        addHelpSpot(helpX, LIB_Y + 4, pattern
                ? "Patterns: hand-drawn grids of blocks, repeated by Pattern paint. Use one, or make "
                        + "and edit them here."
                : "Palettes: saved lists of blocks that Gradient and Noise paint blend. Use one, or "
                        + "make and edit them here.", pattern ? "Patterns" : "Palettes");
        g.fill(x, LIB_Y + 18, x + w, LIB_Y + 19, Ui.LINE);
        if (paletteList != null) paletteList.render(g, mouseX, mouseY);
        if (n == 0) {
            g.text(this.font, this.font.plainSubstrByWidth(pattern
                    ? "No patterns yet — press + New pattern" : "No palettes yet — press + New palette", w),
                    x + 2, LIB_Y + 26, Ui.HINT);
        }
        Ui.hint(g, this.font, x, bottom() - 10, w,
                "LMB select · Dbl-click use · " + Gradient.boundKey("palette") + " cycles in world");
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
        Ui.outline(g, b[0], b[1], b[2], b[3], Ui.BORDER);
        String msg = this.font.plainSubstrByWidth("Delete '" + name + "'?", b[2] - 16);
        g.text(this.font, msg, b[0] + (b[2] - this.font.width(msg)) / 2, b[1] + 10, Ui.TEXT);
        for (boolean del : new boolean[]{true, false}) {
            int[] r = confirmBtn(del);
            boolean hover = inRect(r, mouseX, mouseY);
            if (hover) g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], Ui.HOVER);
            Ui.outline(g, r[0], r[1], r[2], r[3], Ui.BORDER);
            String label = del ? "Delete" : "Cancel";
            g.text(this.font, label, r[0] + (r[2] - this.font.width(label)) / 2, r[1] + 5,
                    del ? Ui.MISSING : Ui.TEXT);
        }
    }

    /** Modal click handling; swallows everything while the confirmation is up. */
    private boolean handleDeleteConfirmClick(double mx, double my, int button) {
        if (button == InputConstants.MOUSE_BUTTON_LEFT && inRect(confirmBtn(true), mx, my)) {
            PaletteStore.delete(confirmDeleteId);
            if (paletteSelectedId.equals(confirmDeleteId)) paletteSelectedId = activeIdOf(libraryKind());
            if (paletteList != null) {
                paletteList.setActiveId(activeIdOf(libraryKind()));
                paletteList.select(paletteSelectedId);
            }
            rebuildPaletteEntries();
        }
        confirmDeleteId = null; // Delete, Cancel, and click-away all dismiss
        return true;
    }

    // ---- Paint tab: Solid -----------------------------------------------------------------------

    private static final SolidMatch[] MATCH_ORDER =
            {SolidMatch.SELECTED, SolidMatch.EXACT, SolidMatch.CLOSEST_COLOR, SolidMatch.CLOSEST_BRIGHTNESS};

    private static String matchName(SolidMatch m) {
        return m == SolidMatch.SELECTED ? "This block" : m.displayName();
    }

    private static String matchDescription(SolidMatch m) {
        return switch (m) {
            case SELECTED -> "Always the block picked on the left.";
            case EXACT -> "Whatever block you click on.";
            case CLOSEST_COLOR -> "Nearest colour from your blocks.";
            case CLOSEST_BRIGHTNESS -> "Nearest brightness from your blocks.";
        };
    }

    private void initSolidView(int[] c) {
        sourceBlocks = gatherSourceBlocks();
        GradientConfig cfg = ConfigManager.get();
        int x = c[0], w = c[1];
        int fromW = this.font.width("from") + 6;
        Segmented from = new Segmented(List.of("Hotbar", "Inventory", "Both"), cfg.source.ordinal(), i -> {
            ConfigManager.get().source = GradientSource.values()[i];
            ConfigManager.save();
            sourceBlocks = gatherSourceBlocks();
            rebuildSolidRows();
        });
        from.setBounds(x + fromW, LIB_Y + Ui.SECTION_H, w - fromW);
        switches.add(from);
        solidListY = LIB_Y + Ui.SECTION_H + Ui.ROW_H + 4;
        int availH = bottom() - 14 - solidListY;
        solidListH = Math.max(LIST_ROW_H, availH - availH % LIST_ROW_H);
        rebuildSolidRows();

        // Right: which block paints (the four match modes), the preview, the exclusions.
        int rx = c[2], rw = c[3];
        FlowColumn right = flow(rx, LIB_Y, rw, bottom() - LIB_Y);
        right.add(sectionRow(right, "Which block paints", null,
                "How Solid chooses the block it places: the one you pick on the left, or one "
                        + "derived from the block you click. Excluded blocks are never chosen.",
                "Match modes"));
        for (SolidMatch m : MATCH_ORDER) right.add(matchRow(m));
        right.add(gapRow(2));
        right.add(new FlowColumn.Row() { // big preview of what paints
            public int height(Font f) { return 36; }
            public void render(GuiGraphicsExtractor g, Font f, int x, int y, int w, int mx, int my) {
                GradientConfig cfg = ConfigManager.get();
                g.fill(x, y, x + 36, y + 36, 0x60000000);
                boolean fixed = cfg.solidMatch == SolidMatch.SELECTED;
                ItemStack st = stackOfId(cfg.solidBlock);
                String name, desc;
                if (fixed && !st.isEmpty()) {
                    g.pose().pushMatrix();
                    g.pose().translate(x + 2, y + 2);
                    g.pose().scale(2f, 2f);
                    g.item(st, 0, 0);
                    g.pose().popMatrix();
                    name = st.getHoverName().getString();
                    desc = "Paints this block on every click.";
                } else {
                    g.pose().pushMatrix();
                    g.pose().translate(x + 18 - f.width("?"), y + 18 - f.lineHeight);
                    g.pose().scale(2f, 2f);
                    g.text(f, "?", 0, 0, Ui.TEXT);
                    g.pose().popMatrix();
                    name = fixed ? "No block picked" : matchName(cfg.solidMatch);
                    desc = fixed ? "LMB a block on the left." : "Decided by the block you click.";
                }
                g.text(f, f.plainSubstrByWidth(name, w - 42), x + 42, y + 8, fixed && st.isEmpty() ? Ui.ATTENTION : Ui.TEXT);
                g.text(f, f.plainSubstrByWidth(desc, w - 42), x + 42, y + 20, Ui.HINT);
            }
        });
        right.add(gapRow(2));
        right.add(textRow(() -> {
            List<String> ex = ConfigManager.get().solidExcludedBlocks;
            if (ex.isEmpty()) return "Excluded from matching: none";
            List<String> names = new ArrayList<>();
            for (String id : ex) names.add(Gradient.toolDisplayName(id));
            return "Excluded from matching: " + ex.size() + " (" + String.join(", ", names) + ")";
        }, Ui.HINT, rw));
    }

    /** One radio row of "Which block paints": name, one-line description, selected blue. */
    private FlowColumn.Row matchRow(SolidMatch m) {
        return new FlowColumn.Row() {
            int bx, by, bw;
            public int height(Font f) { return 2 * f.lineHeight + 6; }
            public void render(GuiGraphicsExtractor g, Font f, int x, int y, int w, int mx, int my) {
                bx = x; by = y; bw = w;
                int h = height(f);
                boolean on = ConfigManager.get().solidMatch == m;
                if (on) g.fill(x, y, x + w, y + h, Ui.SELECTED_FILL);
                else if (Ui.inside(mx, my, x, y, w, h)) g.fill(x, y, x + w, y + h, Ui.HOVER);
                Ui.outline(g, x, y, w, h, on ? Ui.SELECTED : ROW_LINE);
                // Radio dot.
                int dx = x + 5, dy = y + (h - 7) / 2;
                Ui.outline(g, dx, dy, 7, 7, on ? Ui.SELECTED : Ui.BORDER);
                if (on) g.fill(dx + 2, dy + 2, dx + 5, dy + 5, Ui.SELECTED);
                g.text(f, f.plainSubstrByWidth(matchName(m), w - 22), x + 17, y + 3, Ui.TEXT);
                g.text(f, f.plainSubstrByWidth(matchDescription(m), w - 22), x + 17, y + 4 + f.lineHeight, Ui.HINT);
            }
            public boolean click(double mx, double my, int button) {
                if (button != InputConstants.MOUSE_BUTTON_LEFT || !Ui.inside(mx, my, bx, by, bw, height(font))) return false;
                ConfigManager.get().solidMatch = m;
                ConfigManager.save();
                rebuildSolidRows();
                return true;
            }
        };
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

    /** Rows: the usable blocks in slot order (the picked one selected), then the excluded ones. */
    private void rebuildSolidRows() {
        solidRows.clear();
        GradientConfig cfg = ConfigManager.get();
        Set<String> excluded = new HashSet<>(cfg.solidExcludedBlocks);
        for (SourceBlock sb : sourceBlocks) {
            if (!excluded.contains(sb.id())) solidRows.add(new SolidRow(sb, sb.id().equals(cfg.solidBlock), false));
        }
        for (SourceBlock sb : sourceBlocks) {
            if (excluded.contains(sb.id())) solidRows.add(new SolidRow(sb, false, true));
        }
        solidScroll = Math.max(0, Math.min(solidScroll, maxSolidScroll()));
    }

    private int maxSolidScroll() {
        return Math.max(0, solidRows.size() - solidListH / LIST_ROW_H);
    }

    /** Row id under the mouse in the solid list, or null. */
    private String solidRowIdAt(double mx, double my) {
        int[] c = paintCols();
        if (mx < c[0] || mx > c[0] + c[1] || my < solidListY || my >= solidListY + solidListH) return null;
        int idx = solidScroll + (int) ((my - solidListY) / LIST_ROW_H);
        return (idx >= 0 && idx < solidRows.size()) ? solidRows.get(idx).sb().id() : null;
    }

    /** LMB: paint with this block ("This block"); RMB: toggle its exclusion from matching. */
    private boolean handleSolidRowClick(String id, int button) {
        GradientConfig cfg = ConfigManager.get();
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            cfg.solidExcludedBlocks.remove(id); // using an excluded block un-excludes it
            cfg.solidBlock = id;
            cfg.solidMatch = SolidMatch.SELECTED;
        } else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
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

    private void renderSolidView(GuiGraphicsExtractor g, int mouseX, int mouseY, int[] c) {
        int x = c[0], w = c[1];
        Ui.section(g, this.font, x, LIB_Y, w, "Blocks", sourceBlocks.size() + " here");
        g.text(this.font, "from", x, LIB_Y + Ui.SECTION_H + (Ui.ROW_H - this.font.lineHeight) / 2 + 1, Ui.HINT);
        int ly = solidListY, lh = solidListH;
        GradientConfig cfg = ConfigManager.get();
        boolean fixed = cfg.solidMatch == SolidMatch.SELECTED;
        g.enableScissor(x, ly, x + w, ly + lh);
        for (int i = solidScroll; i < solidRows.size(); i++) {
            int ry = ly + (i - solidScroll) * LIST_ROW_H;
            if (ry >= ly + lh) break;
            SolidRow row = solidRows.get(i);
            int rh = LIST_ROW_H - 1;
            boolean hover = Ui.inside(mouseX, mouseY, x, ry, w, rh);
            if (row.selected()) g.fill(x, ry, x + w, ry + rh, Ui.SELECTED_FILL);
            else if (hover) g.fill(x, ry, x + w, ry + rh, Ui.HOVER);
            Ui.outline(g, x, ry, w, rh, row.selected() ? Ui.SELECTED : ROW_LINE);
            g.item(row.sb().stack(), x + 2, ry);
            String tag = row.excluded() ? "excluded" : row.selected() && fixed ? "● in use" : "";
            int tagW = tag.isEmpty() ? 0 : this.font.width(tag) + 6;
            String name = this.font.plainSubstrByWidth(row.sb().stack().getHoverName().getString(), w - 26 - tagW);
            int ty = ry + (rh - this.font.lineHeight) / 2 + 1;
            g.text(this.font, (row.excluded() ? "§m" : "") + name, x + 21, ty, row.excluded() ? Ui.MISSING : Ui.TEXT);
            if (!tag.isEmpty()) {
                g.text(this.font, tag, x + w - 4 - this.font.width(tag), ty, row.excluded() ? Ui.MISSING : Ui.IN_USE);
            }
        }
        g.disableScissor();
        if (solidRows.isEmpty()) {
            g.text(this.font, this.font.plainSubstrByWidth("No blocks in " + cfg.source.displayName(), w),
                    x + 2, ly + 4, Ui.HINT);
        }
        if (maxSolidScroll() > 0) {
            int trackX = x + w + 2;
            int visible = lh / LIST_ROW_H;
            int thumbH = Math.max(8, lh * visible / solidRows.size());
            int thumbY = ly + (int) ((lh - thumbH) * (double) solidScroll / maxSolidScroll());
            g.fill(trackX, ly, trackX + 2, ly + lh, 0x30FFFFFF);
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0x90FFFFFF);
        }
        Ui.hint(g, this.font, x, bottom() - 10, w, "LMB use this block · RMB exclude from matching");
    }

    // ---- Place tab ------------------------------------------------------------------------------

    private static final int TILE_H = 22, TILE_MIN_W = 84, TILE_GAP = 4;

    private void initPlaceTab() {
        GradientConfig cfg = ConfigManager.get();
        int[] c = placeCols();
        FlowColumn grid = flow(c[0], TOP, c[1], bottom() - TOP);
        for (PlacementMode.Group group : PlacementMode.groups()) {
            grid.add(sectionRow(grid, group.title(), () -> group.description().toLowerCase(Locale.ROOT)
                    .replaceAll("\\.$", ""), null, null));
            grid.add(tilesRow(group.modes(), c[1]));
            grid.add(gapRow(2));
        }
        grid.add(textRow(() -> hoveredMode != null ? hoveredMode.shortName() + ": " + PlaceOptions.description(hoveredMode)
                : "Same groups and order as wheel › Place. Off = the paint tool does nothing.", Ui.HINT, c[1]));

        int rx = c[2], rw = c[3];
        FlowColumn right = flow(rx, TOP, rw, bottom() - TOP);
        right.add(sectionRow(right, "Options", () -> "for " + ConfigManager.get().placementMode.shortName(),
                "Options change how the selected mode places. Greyed ones don't apply to it. The "
                        + "yellow dot marks a session-only option (off again after a relaunch).",
                "Placement modes"));
        Supplier<PlacementMode> mode = () -> ConfigManager.get().placementMode;
        right.add(toggleRow(new Toggle("Don't match palette", "Don't spread over what you just painted.", true,
                () -> Gradient.dontMatchPalette, v -> Gradient.dontMatchPalette = v)
                .applies(() -> PlaceOptions.dontMatchApplies(mode.get()))));
        right.add(toggleRow(new Toggle("Fill voids first", "In markers: level the gaps first.", false,
                () -> ConfigManager.get().faceFillVoids, v -> { ConfigManager.get().faceFillVoids = v; ConfigManager.save(); })
                .applies(() -> PlaceOptions.fillVoidsApplies(mode.get()))));
        right.add(toggleRow(new Toggle("Place in air", "Paint at reach when aiming at nothing.", false,
                () -> ConfigManager.get().placeInAir, v -> { ConfigManager.get().placeInAir = v; ConfigManager.save(); })
                .applies(() -> PlaceOptions.placeInAirApplies(mode.get()))));
        right.add(toggleRow(new Toggle("Only place inside markers", "A click on a marker's outside places nothing.", false,
                () -> ConfigManager.get().onlyInsideMarkers,
                v -> { ConfigManager.get().onlyInsideMarkers = v; ConfigManager.save(); })
                .applies(() -> PlaceOptions.onlyInsideApplies(mode.get()))));
        BooleanSupplier perpApplies = () -> PlaceOptions.perpSnapApplies(mode.get(), ConfigManager.get().activePaintType);
        Segmented perp = new Segmented(List.of("45°", "90°"), cfg.perpSnapDegrees == 90 ? 1 : 0, i -> {
            ConfigManager.get().perpSnapDegrees = i == 1 ? 90 : 45;
            ConfigManager.save();
        });
        right.add(switchRow("Perp snap", perp, rw, perpApplies));
        right.add(textRow(() -> "Face perp runs and pattern planes.", Ui.HINT, rw));
        right.add(gapRow(4));

        right.add(sectionRow(right, "Markers", null,
                "Marker settings: the auto end marker, how far an end can be from its start, and "
                        + "clearing every marker and shape.", "Markers"));
        right.add(toggleRow(new Toggle("Auto end marker", "Drop the end on the first block out.", false,
                () -> ConfigManager.get().autoPlaceEnd, v -> { ConfigManager.get().autoPlaceEnd = v; ConfigManager.save(); })));
        right.add(sliderRow(new ValueSlider("Marker reach", distToSlider(cfg.maxMarkerDistance),
                v -> sliderToDist(v) + " blocks",
                v -> ConfigManager.get().maxMarkerDistance = sliderToDist(v), ConfigManager::save)));
        right.add(buttonRow(new FlatButton(() -> "Clear all markers (" + markerCount() + ")",
                FlatButton.Style.DANGER, MarkerManager::clearAll)));
    }

    private static int markerCount() {
        return MarkerManager.startMarkers.size() + MarkerManager.endMarkers.size()
                + co.fax.wang.shape.ShapeMarkers.count();
    }

    /** One group's mode tiles in a grid: name + a few words, selected blue, a stripe of its colour. */
    private FlowColumn.Row tilesRow(List<PlacementMode> modes, int colW) {
        int cols = Math.max(1, Math.min(3, (colW + TILE_GAP) / (TILE_MIN_W + TILE_GAP)));
        int rows = (modes.size() + cols - 1) / cols;
        return new FlowColumn.Row() {
            final int[][] boxes = new int[modes.size()][];
            public int height(Font f) { return rows * (TILE_H + TILE_GAP) - TILE_GAP; }
            public void render(GuiGraphicsExtractor g, Font f, int x, int y, int w, int mx, int my) {
                int tw = (w - (cols - 1) * TILE_GAP) / cols;
                PlacementMode current = ConfigManager.get().placementMode;
                for (int i = 0; i < modes.size(); i++) {
                    PlacementMode m = modes.get(i);
                    int tx = x + (i % cols) * (tw + TILE_GAP), ty = y + (i / cols) * (TILE_H + TILE_GAP);
                    boxes[i] = new int[] {tx, ty, tw, TILE_H};
                    boolean on = m == current;
                    boolean hover = Ui.inside(mx, my, tx, ty, tw, TILE_H);
                    if (hover) hoveredMode = m;
                    if (on) g.fill(tx, ty, tx + tw, ty + TILE_H, Ui.SELECTED_FILL);
                    else if (hover) g.fill(tx, ty, tx + tw, ty + TILE_H, Ui.HOVER);
                    g.fill(tx + 1, ty + 1, tx + 3, ty + TILE_H - 1, m.color());
                    Ui.outline(g, tx, ty, tw, TILE_H, on ? Ui.SELECTED : ROW_LINE);
                    if (on) Ui.outline(g, tx + 1, ty + 1, tw - 2, TILE_H - 2, Ui.SELECTED);
                    g.text(f, f.plainSubstrByWidth(m.shortName(), tw - 9), tx + 6, ty + 3, Ui.TEXT);
                    g.text(f, f.plainSubstrByWidth(PlaceOptions.shortDescription(m), tw - 9), tx + 6,
                            ty + 4 + f.lineHeight, Ui.HINT);
                }
            }
            public boolean click(double mx, double my, int button) {
                if (button != InputConstants.MOUSE_BUTTON_LEFT) return false;
                for (int i = 0; i < modes.size(); i++) {
                    int[] b = boxes[i];
                    if (b != null && Ui.inside(mx, my, b[0], b[1], b[2], b[3])) {
                        ConfigManager.get().placementMode = modes.get(i);
                        ConfigManager.save();
                        return true;
                    }
                }
                return false;
            }
        };
    }

    // ---- Settings tab ---------------------------------------------------------------------------

    private void initSettingsTab() {
        GradientConfig cfg = ConfigManager.get();
        int[] c = settingsCols();
        int x = c[0], w = c[1];

        // Left: the paint tool — current row, filter, list (LMB sets it directly).
        filterBox = new EditBox(this.font, x, TOP + Ui.SECTION_H + 20, w, 18, Component.literal("Filter"));
        filterBox.setHint(Component.literal("Filter items…"));
        filterBox.setMaxLength(64);
        filterBox.setValue(filter);
        filterBox.setResponder(s -> { filter = s; toolScroll = 0; rebuildMatches(); });
        addRenderableWidget(filterBox);
        toolListY = TOP + Ui.SECTION_H + 42;
        int availH = bottom() - 14 - toolListY;
        toolListH = Math.max(LIST_ROW_H, availH - availH % LIST_ROW_H);
        rebuildMatches();

        // Right: general, HUD, keys.
        int rx = c[2], rw = c[3];
        FlowColumn right = flow(rx, TOP, rw, bottom() - TOP);
        right.add(sectionRow(right, "General", null,
                "Colour match: how colours are compared everywhere (Perceptual = as the eye sees "
                        + "them). When blocks are missing: refuse to paint, or skip the missing ones. "
                        + "Paint memory: how long paint remembers where it left off.",
                "Paint memory"));
        right.add(switchRow("Colour match", new Segmented(List.of("Perceptual", "Classic"),
                cfg.perceptualColor ? 0 : 1, i -> {
                    GradientConfig gc = ConfigManager.get();
                    gc.perceptualColor = i == 0;
                    GradientRamp.perceptual = gc.perceptualColor;
                    ConfigManager.save();
                }), rw, () -> true));
        right.add(switchRow("When blocks are missing", new Segmented(List.of("Don't paint", "Skip them"),
                cfg.missingBlockPolicy == MissingBlockPolicy.DONT_PAINT ? 0 : 1, i -> {
                    ConfigManager.get().missingBlockPolicy = i == 0
                            ? MissingBlockPolicy.DONT_PAINT : MissingBlockPolicy.SKIP_MISSING;
                    ConfigManager.save();
                }), rw, () -> true));
        right.add(sliderRow(new ValueSlider("Paint memory", secsToSlider(cfg.gradientCacheSeconds),
                v -> secsLabel(sliderToSecs(v)),
                v -> ConfigManager.get().gradientCacheSeconds = sliderToSecs(v), ConfigManager::save)));
        right.add(textRow(() -> "How long paint remembers where it left off between clicks "
                + "(gradients, 3D fills, patterns).", Ui.HINT, rw));
        right.add(gapRow(4));

        right.add(sectionRow(right, "HUD", () -> "shows your real HUD", null, null));
        right.add(buttonRow(new FlatButton("Move HUD…", FlatButton.Style.PLAIN, () -> {
            if (this.minecraft != null) this.minecraft.setScreenAndShow(new HudPlacementScreen(this));
        })));
        right.add(gapRow(4));

        right.add(sectionRow(right, "Keys & gestures", () -> "read-only",
                "Every key and mouse gesture, with its current binding. Rebind the keys in "
                        + "Options › Controls › Key Binds › FW Paint.", "How do I use this?"));
        List<String[]> keys = keyRows();
        int keyW = 0;
        for (String[] k : keys) keyW = Math.max(keyW, this.font.width(k[0]));
        int keyCol = Math.min(keyW + 8, rw / 2);
        for (String[] k : keys) {
            right.add(new FlowColumn.Row() {
                public int height(Font f) { return f.lineHeight + 2; }
                public void render(GuiGraphicsExtractor g, Font f, int x, int y, int w, int mx, int my) {
                    g.text(f, f.plainSubstrByWidth(k[0], keyCol - 4), x, y + 1, Ui.TEXT);
                    g.text(f, f.plainSubstrByWidth(k[1], w - keyCol), x + keyCol, y + 1, Ui.HINT);
                }
            });
        }
        right.add(gapRow(2));
        right.add(buttonRow(new FlatButton("Change in Controls…", FlatButton.Style.PLAIN, () -> {
            if (this.minecraft != null) {
                this.minecraft.setScreenAndShow(new net.minecraft.client.gui.screens.options.controls
                        .KeyBindsScreen(this, this.minecraft.options));
            }
        })));
    }

    /** The keys and gestures list: {live key name, what it does}. */
    private List<String[]> keyRows() {
        var o = this.minecraft == null ? null : this.minecraft.options;
        String use = o == null ? "RMB" : keyName(o.keyUse);
        String attack = o == null ? "LMB" : keyName(o.keyAttack);
        String pick = o == null ? "MMB" : keyName(o.keyPickItem);
        String mod = Gradient.boundKey("modifier");
        List<String[]> out = new ArrayList<>();
        out.add(new String[] {Gradient.boundKey("open"), "Open / close this screen"});
        out.add(new String[] {Gradient.boundKey("wheel") + " (hold)", "Wheel"});
        out.add(new String[] {Gradient.boundKey("palette"), "Next palette, pattern or block"});
        out.add(new String[] {use, "Paint (hold to keep going)"});
        out.add(new String[] {attack, "Start marker · shape points"});
        out.add(new String[] {pick, "Delete marker / shape / line"});
        out.add(new String[] {mod + "+" + pick, "Delete connected markers"});
        out.add(new String[] {mod, "Other way / both ends"});
        out.add(new String[] {Gradient.boundKey("axis"), "Lock a drag to one axis"});
        out.add(new String[] {"Scroll", "Slide shape · " + mod + "+scroll extrude"});
        return out;
    }

    /** A key's live name, with the three mouse buttons shortened to LMB / RMB / MMB. */
    private static String keyName(KeyMapping k) {
        return switch (k.saveString()) {
            case "key.mouse.left" -> "LMB";
            case "key.mouse.right" -> "RMB";
            case "key.mouse.middle" -> "MMB";
            default -> k.getTranslatedKeyMessage().getString();
        };
    }

    private void rebuildMatches() {
        matches.clear();
        String q = filter.toLowerCase(Locale.ROOT);
        for (var item : BuiltInRegistries.ITEM.stream().toList()) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) continue;
            String idStr = id.toString();
            if (idStr.equals("minecraft:air")) continue;
            ItemStack st = new ItemStack(item);
            String name = st.getHoverName().getString();
            if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q) && !idStr.toLowerCase(Locale.ROOT).contains(q)) continue;
            matches.add(new ToolRow(idStr, name, st));
        }
        toolScroll = Math.max(0, Math.min(toolScroll, maxToolScroll()));
    }

    private int maxToolScroll() {
        return Math.max(0, matches.size() - toolListH / LIST_ROW_H);
    }

    private int toolRowAt(double mouseX, double mouseY) {
        int[] c = settingsCols();
        if (mouseX < c[0] || mouseX > c[0] + c[1] || mouseY < toolListY || mouseY >= toolListY + toolListH) return -1;
        int idx = toolScroll + (int) ((mouseY - toolListY) / LIST_ROW_H);
        return (idx >= 0 && idx < matches.size()) ? idx : -1;
    }

    private void renderSettingsTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        GradientConfig cfg = ConfigManager.get();
        int[] c = settingsCols();
        int x = c[0], w = c[1];
        Ui.section(g, this.font, x, TOP, w, "Paint tool", null);
        int cy = TOP + Ui.SECTION_H;
        if (cfg.paintTool.isEmpty()) {
            g.text(this.font, this.font.plainSubstrByWidth("None yet — pick one below", w), x, cy + 5, Ui.ATTENTION);
        } else {
            g.item(stackOfId(cfg.paintTool), x, cy);
            String tag = "● current";
            g.text(this.font, this.font.plainSubstrByWidth(Gradient.toolDisplayName(cfg.paintTool),
                    w - 24 - this.font.width(tag)), x + 20, cy + 5, Ui.TEXT);
            g.text(this.font, tag, x + w - this.font.width(tag), cy + 5, Ui.IN_USE);
        }

        int ly = toolListY, lh = toolListH;
        g.enableScissor(x, ly, x + w, ly + lh);
        for (int i = toolScroll; i < matches.size(); i++) {
            int ry = ly + (i - toolScroll) * LIST_ROW_H;
            if (ry >= ly + lh) break;
            ToolRow row = matches.get(i);
            boolean current = row.id().equals(cfg.paintTool);
            boolean hover = Ui.inside(mouseX, mouseY, x, ry, w, LIST_ROW_H);
            if (current) g.fill(x, ry, x + w, ry + LIST_ROW_H, 0x336BCB8B);
            else if (hover) g.fill(x, ry, x + w, ry + LIST_ROW_H, Ui.HOVER);
            g.item(row.stack(), x + 1, ry + 1);
            g.text(this.font, this.font.plainSubstrByWidth(row.name(), w - 24), x + 20, ry + 5,
                    current ? Ui.IN_USE : Ui.TEXT);
        }
        g.disableScissor();
        if (maxToolScroll() > 0) {
            int trackX = x + w + 2;
            int visible = lh / LIST_ROW_H;
            int thumbH = Math.max(8, lh * visible / Math.max(1, matches.size()));
            int thumbY = ly + (int) ((lh - thumbH) * (double) toolScroll / maxToolScroll());
            g.fill(trackX, ly, trackX + 2, ly + lh, 0x30FFFFFF);
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0x90FFFFFF);
        }
        Ui.hint(g, this.font, x, bottom() - 10, w, "LMB make this the paint tool");
    }

    // ---- Finder tab -----------------------------------------------------------------------------

    private void initFinderTab() {
        int cx = contentX(), w = colW();
        int listBottom = this.height - 40;

        // Left: every block in the game, in the active ordering, with a target button to re-center.
        // Clicking a row selects that block as the reference — there is no separate picker.
        addRenderableWidget(Button.builder(Component.literal("⌖"), b -> recenterFinder())
                .bounds(cx + w - 20, TOP - 2, 20, 18).build());
        finderList = new FinderListPanel(this.font);
        finderList.setShowSwatch(true);
        finderList.setBounds(cx, TOP + Ui.SECTION_H + 6, w, listBottom - (TOP + Ui.SECTION_H + 6));
        finderList.setEntries(activeFinderEntries()); // first call builds the session colour index

        // Right: sort switch, the selected block, and the always-visible pure-colour picker.
        int rx = rightX(), rw = colW();
        int sortW = this.font.width("Sort") + 8;
        switches.add(new Segmented(List.of("Colour", "Brightness"), finderSort == FinderSort.COLOR ? 0 : 1, i -> {
            finderSort = i == 0 ? FinderSort.COLOR : FinderSort.BRIGHTNESS;
            rebuildWidgets();
        }).setBounds(rx + sortW, TOP - 2, rw - sortW));

        finderHeaderY = TOP + 22 + Ui.SECTION_H; // selected-block header is render-only
        finderFieldY = finderHeaderY + 28 + Ui.SECTION_H;
        // Landscape field: cap the height so tall windows don't stretch it into a square.
        int fieldH = Math.max(40, Math.min(72, listBottom - finderFieldY - 48));
        finderField = new ColorField2D();
        finderField.setBounds(rx, finderFieldY, rw, fieldH);
        finderSliderY = finderFieldY + fieldH + 4;
        finderSwatchY = finderSliderY + 24;
        finderBriSlider = new ValueSlider("Brightness", finderBri, v -> Math.round(v * 100) + "%",
                v -> { finderBri = v; finderRef = FinderRef.COLOR; recenterFinder(); }, () -> {})
                .setBounds(rx, finderSliderY, rw);
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
        if (finderBriSlider != null && finderBriSlider.click(mx, my)) return true;
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
        int cx = contentX(), w = colW();
        Ui.section(g, this.font, cx, TOP + 2, w - 24, "Blocks",
                finderSort == FinderSort.COLOR ? "by colour" : "by brightness");
        if (finderList != null) finderList.render(g, mouseX, mouseY);

        int rx = rightX(), rw = colW();
        g.text(this.font, "Sort", rx, TOP + 3, Ui.HINT);
        Ui.section(g, this.font, rx, TOP + 22, rw, "Reference", finderRef == FinderRef.BLOCK ? "block" : "colour");
        ColorIndex.Entry sel = finderSelectedId.isEmpty() ? null : ColorIndex.byId(finderSelectedId);
        int hy = finderHeaderY;
        if (sel == null) {
            g.text(this.font, this.font.plainSubstrByWidth("Click a block or pick a colour", rw), rx, hy + 8, Ui.HINT);
        } else {
            g.fill(rx, hy, rx + 24, hy + 24, 0xFF6E6E6E);
            g.item(sel.stack(), rx + 4, hy + 4);
            g.fill(rx + rw - 17, hy + 4, rx + rw - 1, hy + 20, 0x60FFFFFF);
            g.fill(rx + rw - 16, hy + 5, rx + rw - 2, hy + 19, 0xFF000000 | sel.rgb());
            g.text(this.font, this.font.plainSubstrByWidth(sel.name(), rw - 50), rx + 30, hy + 2,
                    finderRef == FinderRef.BLOCK ? Ui.TEXT : Ui.HINT);
            g.text(this.font, String.format("#%06X", sel.rgb()), rx + 30, hy + 14, Ui.HINT);
        }
        Ui.section(g, this.font, rx, finderFieldY - Ui.SECTION_H, rw, "Pure colour", null);
        if (finderField != null) finderField.render(g, finderHue, finderSat, finderBri);
        if (finderBriSlider != null) finderBriSlider.render(g, this.font, mouseX, mouseY);
        int rgb = ColorOrder.hsvToRgb(finderHue, finderSat, finderBri);
        g.fill(rx, finderSwatchY, rx + 20, finderSwatchY + 20, 0x60FFFFFF);
        g.fill(rx + 1, finderSwatchY + 1, rx + 19, finderSwatchY + 19, 0xFF000000 | rgb);
        g.text(this.font, String.format("#%06X", rgb), rx + 26, finderSwatchY + 6,
                finderRef == FinderRef.COLOR ? Ui.TEXT : Ui.HINT);
    }

    // ---- Help tab -------------------------------------------------------------------------------

    private void initHelpTab() {
        if (help == null) help = new HelpPanel(this.font);
        // The manual gets the full content width (both columns), from under the bar to the Done button.
        help.setBounds(contentX(), TOP, 2 * colW() + COL_GAP, this.height - TOP - 34);
        if (pendingHelpTopic != null) {
            help.focus(pendingHelpTopic);
            pendingHelpTopic = null;
        }
    }

    // ---- section "?" help -----------------------------------------------------------------------

    private HelpSpot spotAt(double mx, double my) {
        for (HelpSpot h : helpSpots) {
            if (mx >= h.x() && mx <= h.x() + 10 && my >= h.y() && my <= h.y() + 10) return h;
        }
        return null;
    }

    /** Draw the "?" icons; after a short hover, a popup with the section's summary. */
    private void renderHelpSpots(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        for (HelpSpot h : helpSpots) UiIcons.drawHelpIcon(g, h.x(), h.y());
        HelpSpot over = spotAt(mouseX, mouseY);
        if (over == null || hoverSpot == null || !over.topic().equals(hoverSpot.topic())) {
            hoverSpot = over;
            hoverSince = System.currentTimeMillis();
        }
        if (over == null || System.currentTimeMillis() - hoverSince < 400) return;
        List<String> lines = wrapText(over.text(), 170);
        lines.add("Click: open " + over.topic() + " in Help");
        int w = 0;
        for (String l : lines) w = Math.max(w, this.font.width(l));
        int h = lines.size() * 10 + 8;
        int x = Math.max(4, Math.min(mouseX + 10, this.width - w - 14));
        int y = Math.min(mouseY + 8, this.height - h - 4);
        g.nextStratum();
        g.fill(x, y, x + w + 8, y + h, 0xF0101010);
        Ui.outline(g, x, y, w + 8, h, Ui.BORDER);
        for (int i = 0; i < lines.size(); i++) {
            g.text(this.font, lines.get(i), x + 4, y + 4 + i * 10, i == lines.size() - 1 ? Ui.HINT : Ui.TEXT);
        }
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

    // ---- input ----------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x(), my = event.y();
        int button = event.button();
        if (confirmDeleteId != null) return handleDeleteConfirmClick(mx, my, button);
        if (my < BAR_H && button == InputConstants.MOUSE_BUTTON_LEFT) {
            int[] xs = tabXs();
            for (int i = 0; i < BAR_ORDER.length; i++) {
                if (mx >= xs[i * 2] - 4 && mx <= xs[i * 2] + xs[i * 2 + 1] + 4) {
                    setTab(BAR_ORDER[i]);
                    return true;
                }
            }
        }
        if (super.mouseClicked(event, doubled)) return true;
        // A click anywhere else drops text-box focus, so the open key closes the screen again.
        if (getFocused() != null) setFocused(null);
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            HelpSpot spot = spotAt(mx, my);
            if (spot != null) {
                openHelp(spot.topic());
                return true;
            }
            for (Segmented s : switches) if (s.click(mx, my)) return true;
            for (FlatButton b : buttons) if (b.click(mx, my)) return true;
        }
        for (FlowColumn f : flows) if (f.click(mx, my, button)) return true;

        switch (tab) {
            case PAINT -> {
                if (ConfigManager.get().activePaintType == PaintType.SOLID) {
                    String id = solidRowIdAt(mx, my);
                    if (id != null) return handleSolidRowClick(id, button);
                } else if (paletteList != null && button == InputConstants.MOUSE_BUTTON_LEFT) {
                    String id = paletteList.click(mx, my);
                    if (id != null) {
                        paletteSelectedId = id;
                        if (doubled) useSelected();
                        return true;
                    }
                }
            }
            case PLACE -> { }
            case SETTINGS -> {
                int idx = toolRowAt(mx, my);
                if (idx >= 0 && button == InputConstants.MOUSE_BUTTON_LEFT) {
                    ConfigManager.get().paintTool = matches.get(idx).id();
                    ConfigManager.save();
                    return true;
                }
            }
            case FINDER -> {
                if (button == InputConstants.MOUSE_BUTTON_LEFT && handleFinderClick(mx, my)) return true;
            }
            case HELP -> {
                if (button == InputConstants.MOUSE_BUTTON_LEFT && help != null && help.mouseClicked(mx, my)) return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean any = false;
        for (FlowColumn f : flows) any |= f.release();
        if (finderBriSlider != null) any |= finderBriSlider.release();
        return super.mouseReleased(event) || any;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (confirmDeleteId != null) return true;
        for (FlowColumn f : flows) if (f.mouseScrolled(mouseX, mouseY, scrollY, this.font)) return true;
        if (tab == Tab.PAINT && paletteList != null && paletteList.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        int[] pc = paintCols();
        if (tab == Tab.PAINT && Ui.inside(mouseX, mouseY, pc[0], solidListY, pc[1], solidListH)) {
            solidScroll = Math.max(0, Math.min(maxSolidScroll(), solidScroll - (int) Math.signum(scrollY)));
            return true;
        }
        int[] sc = settingsCols();
        if (tab == Tab.SETTINGS && Ui.inside(mouseX, mouseY, sc[0], toolListY, sc[1], toolListH)) {
            toolScroll = Math.max(0, Math.min(maxToolScroll(), toolScroll - 3 * (int) Math.signum(scrollY)));
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
        for (FlowColumn f : flows) if (f.drag(event.x(), event.y())) return true;
        if (tab == Tab.FINDER && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (finderBriSlider != null && finderBriSlider.drag(event.x())) return true;
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
        if (confirmDeleteId != null && event.isEscape()) { // Esc dismisses the delete confirm
            confirmDeleteId = null;
            return true;
        }
        // The open key closes the screen again — unless a text box is taking the typing.
        boolean typing = getFocused() instanceof EditBox box && box.isFocused();
        if (!typing && confirmDeleteId == null && Gradient.openKey != null && Gradient.openKey.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    // ---- value mappings ---------------------------------------------------------------------------

    private static int sliderToDist(double v) { return Math.max(1, Math.min(128, 1 + (int) Math.round(v * 127))); }
    private static double distToSlider(int d) { return (Math.max(1, Math.min(128, d)) - 1) / 127.0; }
    // Paint memory: 10 s – 30 min.
    private static int sliderToSecs(double v) { return Math.max(10, Math.min(1800, 10 + (int) Math.round(v * 1790))); }
    private static double secsToSlider(int s) { return (Math.max(10, Math.min(1800, s)) - 10) / 1790.0; }
    private static String secsLabel(int s) {
        if (s < 60) return s + " s";
        return s % 60 == 0 ? (s / 60) + " min" : (s / 60) + " min " + (s % 60) + " s";
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    /** The unified variation window+chance, e.g. "±2 @ 35%" or "off". */
    private static String variationLabel(Palette p) {
        return p.variationWindow == 0 || p.variationChance == 0
                ? "off" : "±" + p.variationWindow + " @ " + p.variationChance + "%";
    }

    // ---- rendering ------------------------------------------------------------------------------

    /** Right-aligned tab x positions: {x,width} pairs, one per {@link #BAR_ORDER} entry. */
    private int[] tabXs() {
        int gap = 14;
        int[] out = new int[BAR_ORDER.length * 2];
        int x = this.width - 10;
        for (int i = BAR_ORDER.length - 1; i >= 0; i--) {
            int w = this.font.width(tabName(BAR_ORDER[i]));
            x -= w;
            out[i * 2] = x;
            out[i * 2 + 1] = w;
            x -= gap;
        }
        return out;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Full-page Ui.BACKDROP underlay so every tab reads against any world (before super
        // → beneath the widgets).
        g.fill(0, 0, this.width, this.height, Ui.BACKDROP);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        helpSpots.clear();
        hoveredMode = null;
        renderTitleBar(g, mouseX, mouseY);
        // While the delete confirm is up nothing underneath reacts to hover.
        int mx = confirmDeleteId != null ? -10000 : mouseX, my = confirmDeleteId != null ? -10000 : mouseY;
        for (FlowColumn f : flows) f.render(g, this.font, mx, my);
        for (Segmented s : switches) s.render(g, this.font, mx, my);
        for (FlatButton b : buttons) b.render(g, this.font, mx, my);
        switch (tab) {
            case PAINT -> renderPaintTab(g, mx, my);
            case PLACE -> { }
            case FINDER -> renderFinderTab(g, mx, my);
            case SETTINGS -> renderSettingsTab(g, mx, my);
            case HELP -> { if (help != null) help.render(g, mx, my); }
        }
        if (confirmDeleteId == null) renderHelpSpots(g, mouseX, mouseY);
    }

    private void renderTitleBar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(0, 0, this.width, BAR_H, BAR_BG);
        g.fill(0, BAR_H, this.width, BAR_H + 1, Ui.LINE);
        int textY = (BAR_H - this.font.lineHeight) / 2 + 1;
        g.text(this.font, "FW Paint", 8, textY, Ui.TEXT);
        int[] xs = tabXs();
        for (int i = 0; i < BAR_ORDER.length; i++) {
            Tab t = BAR_ORDER[i];
            boolean on = tab == t;
            boolean hover = mouseY < BAR_H && mouseX >= xs[i * 2] - 4 && mouseX <= xs[i * 2] + xs[i * 2 + 1] + 4;
            g.text(this.font, tabName(t), xs[i * 2], textY, on || hover ? Ui.TEXT : Ui.HINT);
            if (on) g.fill(xs[i * 2] - 2, BAR_H - 2, xs[i * 2] + xs[i * 2 + 1] + 2, BAR_H, Ui.SELECTED);
        }
    }

    @Override
    public void onClose() { ConfigManager.save(); super.onClose(); }
}
