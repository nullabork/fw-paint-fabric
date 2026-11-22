package co.fax.wang.wheel;

import co.fax.wang.BlockTextures;
import com.mojang.blaze3d.platform.InputConstants;
import co.fax.wang.ColorOrder;
import co.fax.wang.Gradient;
import co.fax.wang.GradientMode;
import co.fax.wang.GradientSource;
import co.fax.wang.PaintType;
import co.fax.wang.PlaceOptions;
import co.fax.wang.PlacementMode;
import co.fax.wang.SolidMatch;
import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import co.fax.wang.config.WheelPress;
import co.fax.wang.palette.Palette;
import co.fax.wang.palette.PaletteKind;
import co.fax.wang.palette.PaletteStore;
import co.fax.wang.ui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The Wheel. Held open by the wheel keybind (default G); releasing it (or Esc) closes, keeping
 * whatever was chosen. Three ring levels: the root holds <b>Paint</b>, <b>Palette</b> and
 * <b>Place</b> (the screen's tab order); hovering a category fans its children into the next ring
 * out (clicking is reserved for the actual options). Paint fans the paint types; Place fans
 * <b>Blocks</b>, <b>Markers</b> and <b>Shapes</b> (the Place tab's groups, from
 * {@link PlacementMode#groups()}), <b>Options</b> (the placement toggles), and Off. Every ring
 * ends in "More… (K)", which opens the screen on the matching tab. The
 * Palette ring follows the active paint type (palettes, patterns, or the Solid block picker,
 * Oklab colour-sorted like the list views) and becomes a scrollable radial carousel when it
 * outgrows the arc ({@link ExpandedRing}). The category wedge DIRECTLY holding the current
 * selection gets a blue-tinted background, so where the active choice lives reads at a
 * glance without opening anything. Clicking a
 * leaf applies it exactly like the old hotkeys did (persisted + action-bar flash); the wheel
 * stays open so several things can be set in one hold. The donut hole always shows the current
 * paint type + placement mode, mirroring the HUD.
 *
 * <p>Ring radii come from {@link WheelLayout}, solved from the pixel widths of every label
 * when the wheel opens (across every possible Palette-ring variant) — longer labels make a
 * bigger wheel from the start, nothing resizes live. The donut is painted per-pixel (with
 * horizontal run merging into {@code fill} calls) — the 26.2 render-state GUI only exposes
 * axis-aligned fills, and even a large wheel is well under ~100k pixel tests per frame.
 */
public class WheelScreen extends Screen {

    private static final int COL_BASE = 0xC8141420;          // ring background
    private static final int COL_HOVER = 0xC83A4A6E;         // hovered slot
    private static final int COL_EXPANDED = 0xC82A3450;      // category whose ring is open
    private static final int COL_SELECTED = 0xC81F5FD0;      // slot of the current choice
    private static final int COL_SELECTED_HOVER = 0xC83A7BE8;
    private static final int COL_MISSING = 0xC8B02020;       // selected but no longer available
    private static final int COL_MISSING_HOVER = 0xC8D04040;
    private static final int COL_CROSS = 0xFFFF4040;         // the X over a missing block icon
    private static final int COL_SEPARATOR = 0x38FFFFFF;     // thin line between slots
    private static final int COL_SEMI = 0xC81A3A78;          // category directly holding the selection
    private static final int COL_SEMI_HOVER = 0xC82E5498;
    private static final int COL_DIM = 0x70000000;           // full-screen dim behind the wheel
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = Ui.HINT;

    /** Root slot whose children are rebuilt from the active paint type on every expansion. */
    private static final int PALETTE_INDEX = 1;

    /** Height of the tab bar along the top (matches GradientScreen / the editors). */
    private static final int BAR_H = 22;
    /** The Recent bar along the bottom: button height, gap, widest label line. */
    private static final int HIST_H = 34;
    private static final int HIST_GAP = 3;
    private static final int HIST_MAX_W = 96;
    private static final int HIST_ARROW_W = 14;
    /** Room kept between the wheel and the Recent bar for the two-line description. */
    private static final int DESC_RESERVE = 26;

    private final List<WheelItem> root;
    private final WheelLayout layout;

    /** The fanned-out rings: level 1 (children of a root category) and level 2, or null. */
    private ExpandedRing expanded1;
    private ExpandedRing expanded2;

    /**
     * Dev-only ({@link co.fax.wang.DebugTour}): slot indices to "hover" ring by ring, so a
     * screenshot shows fanned-out rings without a mouse. Null in normal play.
     */
    public static int[] debugHoverPath;

    /**
     * Dev-only ({@link co.fax.wang.DebugTour}): fill the Recent bar with one pick of each kind
     * (palette, pattern, Solid block, mode, option) so a screenshot shows it. Not saved.
     */
    public static void debugSeedHistory() {
        List<WheelPress> list = WheelHistory.list();
        list.clear();
        list.add(withPath(new WheelPress(WheelHistory.MODE, PlacementMode.FACE.name(), null,
                PlacementMode.FACE.shortName()), "Place › Blocks"));
        Set<String> ids = availableIds(Minecraft.getInstance(), GradientSource.HOTBAR_AND_INVENTORY);
        for (String id : ids.stream().sorted().limit(2).toList()) {
            list.add(withPath(new WheelPress(WheelHistory.BLOCK, id, null,
                    Gradient.toolDisplayName(id)), "Solid › This block"));
        }
        for (Palette p : PaletteStore.all()) {
            boolean pattern = p.kind == PaletteKind.PATTERN;
            list.add(withPath(new WheelPress(pattern ? WheelHistory.PATTERN : WheelHistory.PALETTE, p.id,
                    pattern ? "PATTERN" : "GRADIENT", p.name), (pattern ? "Pattern" : "Gradient") + " › Palette"));
        }
        list.add(withPath(new WheelPress(WheelHistory.OPTION, "placeInAir=on", null, "Place in air: on"),
                "Place › Options"));
        list.add(withPath(new WheelPress(WheelHistory.PAINT, "NOISE", null, "Noise"), "Paint"));
    }

    private static WheelPress withPath(WheelPress p, String path) {
        p.path = path;
        return p;
    }

    public WheelScreen() {
        super(Component.literal("FW Paint — Wheel"));
        Minecraft mc = Minecraft.getInstance();
        this.root = buildRoot(mc);
        this.layout = computeLayout(mc.font, this.root, mc);
        if (debugHoverPath != null) {
            org.slf4j.LoggerFactory.getLogger("fwpaint/tour").info("wheel radii {}-{} / {}-{} / {}-{} reach u{} d{} l{} r{} gui {}x{}",
                    layout.r0In, layout.r0Out, layout.r1In, layout.r1Out, layout.r2In, layout.r2Out,
                    layout.up, layout.down, layout.left, layout.right,
                    mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        }
    }

    // ---- menu tree ------------------------------------------------------------------------

    // Option leaves whose slot text is drawn live (see labelOf / colorOf).
    private static final String DONT_MATCH = "Don't match palette";
    private static final String PERP_SNAP = "Perp snap 45°";

    private static List<WheelItem> buildRoot(Minecraft mc) {
        List<WheelItem> paints = new ArrayList<>();
        for (PaintType t : new PaintType[] {
                PaintType.SOLID, PaintType.GRADIENT, PaintType.NOISE, PaintType.PATTERN }) {
            paints.add(WheelItem.leaf(t.label(), WHITE,
                    () -> ConfigManager.get().activePaintType == t,
                    () -> Gradient.setPaintType(t)).describe(paintDescription(t))
                    .remembers(new WheelPress(WheelHistory.PAINT, t.name(), null, t.label())));
        }
        paints.add(moreLeaf("Paint", co.fax.wang.GradientScreen::atPaintTab));

        // Blocks / Markers / Shapes come from the same groups as the Place tab. Off sits directly
        // on the Place ring, so the Markers group leaves it out here.
        List<WheelItem> place = new ArrayList<>();
        for (PlacementMode.Group group : PlacementMode.groups()) {
            List<WheelItem> modes = new ArrayList<>();
            for (PlacementMode m : group.modes()) {
                if (m != PlacementMode.DISABLED) modes.add(modeLeaf(m));
            }
            place.add(WheelItem.category(group.title(), modes.toArray(WheelItem[]::new))
                    .describe(group.description()));
        }
        // Toggles, not modes: lit while on. Don't match palette is session-only (yellow dot).
        WheelItem options = WheelItem.category("Options",
                option(WheelItem.leaf(DONT_MATCH, WHITE, () -> Gradient.dontMatchPalette,
                        Gradient::toggleDontMatchPalette), WheelHistory.DONT_MATCH)
                        .describe("Stop the Face modes spreading over blocks of your current "
                                + "palette, pattern or Solid block. Resets on relaunch."),
                option(WheelItem.leaf("Fill voids first", WHITE, () -> ConfigManager.get().faceFillVoids,
                        PlaceOptions::toggleFillVoids), WheelHistory.FILL_VOIDS)
                        .describe("Face any / Face target in markers: level the gaps before "
                                + "growing outward."),
                option(WheelItem.leaf("Place in air", WHITE, () -> ConfigManager.get().placeInAir,
                        PlaceOptions::togglePlaceInAir), WheelHistory.PLACE_IN_AIR)
                        .describe("Place blocks with nothing next to them straight into the "
                                + "air; aim at the sky to paint at reach."),
                option(WheelItem.leaf(PERP_SNAP, WHITE, () -> ConfigManager.get().perpSnapDegrees == 90,
                        PlaceOptions::togglePerpSnap), WheelHistory.PERP_SNAP)
                        .describe("Click to switch 45° / 90°: the angle Face perp runs and "
                                + "pattern planes snap to."))
                .describe("How the current mode places.");
        place.add(options);
        place.add(modeLeaf(PlacementMode.DISABLED));
        place.add(moreLeaf("Place", co.fax.wang.GradientScreen::atPlaceTab));

        // Ring 1 follows the screen's tab order: Paint · Palette · Place.
        return List.of(
                WheelItem.category("Paint", paints.toArray(WheelItem[]::new))
                        .describe("What gets placed."),
                // Children here are only the open-time snapshot (they make it a category and
                // feed the layout); every expansion rebuilds them for the paint type of the
                // moment — see updateExpansion.
                WheelItem.category("Palette",
                        paletteChildren(mc, ConfigManager.get().activePaintType).toArray(WheelItem[]::new))
                        .describe("Which palette, pattern or block to paint with."),
                WheelItem.category("Place", place.toArray(WheelItem[]::new))
                        .describe("Where it goes: a mode and its options."));
    }

    /** "More… (K)": opens the FW Paint screen on the matching tab. */
    private static WheelItem moreLeaf(String tab, java.util.function.Supplier<Screen> screen) {
        return WheelItem.leaf("More… (" + Gradient.boundKey("open") + ")", GREY, null,
                () -> Minecraft.getInstance().setScreenAndShow(screen.get()))
                .describe("Open the " + tab + " tab of the FW Paint screen.");
    }

    private static WheelItem modeLeaf(PlacementMode m) {
        return WheelItem.leaf(m.shortName(), m.color(),
                () -> ConfigManager.get().placementMode == m,
                () -> Gradient.setPlacementMode(m)).describe(PlaceOptions.description(m))
                .remembers(new WheelPress(WheelHistory.MODE, m.name(), null, m.shortName()));
    }

    /** An Options toggle, remembered on the Recent bar with the state it was set to. */
    private static WheelItem option(WheelItem leaf, String name) {
        return leaf.remembers(new WheelPress(WheelHistory.OPTION, name, null, leaf.label()));
    }

    private static String paintDescription(PaintType t) {
        return switch (t) {
            case SOLID -> "One block type everywhere.";
            case GRADIENT -> "Blends the palette's blocks from start to end.";
            case PATTERN -> "Repeats a hand-drawn grid of blocks.";
            case NOISE -> "Scatters the palette's blocks in natural patches.";
        };
    }

    /** A slot's text: the live value for Perp snap, the stored label otherwise. */
    private static String labelOf(WheelItem item) {
        if (PERP_SNAP.equals(item.label())) return "Perp snap " + ConfigManager.get().perpSnapDegrees + "°";
        return item.label();
    }

    /** A slot's text colour: Options toggles grey out when they don't apply to the current mode. */
    private static int colorOf(WheelItem item) {
        GradientConfig cfg = ConfigManager.get();
        PlacementMode m = cfg.placementMode;
        boolean applies = switch (item.label()) {
            case DONT_MATCH -> PlaceOptions.dontMatchApplies(m);
            case "Fill voids first" -> PlaceOptions.fillVoidsApplies(m);
            case "Place in air" -> PlaceOptions.placeInAirApplies(m);
            case PERP_SNAP -> PlaceOptions.perpSnapApplies(m, cfg.activePaintType);
            default -> true;
        };
        return applies ? item.labelColor() : GREY;
    }

    /**
     * The Palette ring's items for a paint type: palettes, patterns, or — for Solid — the
     * match modes, with "This block" fanning the inventory block picker onto the next ring.
     * Every variant ends in "More… (K)" to the Paint tab.
     */
    private static List<WheelItem> paletteChildren(Minecraft mc, PaintType type) {
        WheelItem more = moreLeaf("Paint", co.fax.wang.GradientScreen::atPaintTab);
        if (type == PaintType.SOLID) {
            List<WheelItem> blocks = solidBlockItems(mc);
            WheelItem select = blocks.isEmpty()
                    ? WheelItem.leaf("No blocks in inventory", GREY, null, () -> {})
                    : WheelItem.category("This block", blocks.toArray(WheelItem[]::new))
                            .describe("Pick the block to place from your inventory.");
            return List.of(select,
                    matchLeaf(SolidMatch.EXACT, "Exact block")
                            .describe("Copies exactly the block you click."),
                    matchLeaf(SolidMatch.CLOSEST_COLOR, "Closest colour")
                            .describe("Your block closest in colour to the one you click."),
                    matchLeaf(SolidMatch.CLOSEST_BRIGHTNESS, "Closest brightness")
                            .describe("Your block closest in brightness to the one you click."),
                    more);
        }
        PaletteKind kind = Gradient.kindFor(type);
        List<Palette> list = PaletteStore.allOf(kind);
        if (list.isEmpty()) {
            return List.of(WheelItem.leaf(
                    "No " + kind.label().toLowerCase(Locale.ROOT) + "s yet", GREY, null, () -> {})
                    .describe("Make one on the Paint tab: More… (" + Gradient.boundKey("open") + ")."),
                    more);
        }
        List<WheelItem> out = new ArrayList<>();
        Map<GradientSource, Set<String>> avail = new EnumMap<>(GradientSource.class);
        for (Palette p : list) {
            BooleanSupplier selected = () -> {
                Palette active = PaletteStore.activeOf(kind);
                return active != null && active.id.equals(p.id);
            };
            Runnable action = () -> Gradient.setActivePalette(p);
            // Flag items whose explicitly-defined blocks aren't all in their source right now.
            int missing = p.missingBlocks(
                    avail.computeIfAbsent(p.source, s -> availableIds(mc, s))).size();
            WheelPress press = new WheelPress(kind == PaletteKind.PATTERN ? WheelHistory.PATTERN : WheelHistory.PALETTE,
                    p.id, type.name(), p.name);
            out.add((missing > 0
                    ? WheelItem.missingLeaf(p.name, missing, selected, action)
                    : WheelItem.leaf(p.name, WHITE, selected, action)).remembers(press));
        }
        out.add(more);
        return out;
    }

    private static WheelItem matchLeaf(SolidMatch m, String label) {
        return WheelItem.leaf(label, WHITE,
                () -> ConfigManager.get().solidMatch == m,
                () -> Gradient.setSolidMatch(m))
                .remembers(new WheelPress(WheelHistory.MATCH, m.name(), null, label));
    }

    /** Inventory slot range [from, to) for a block source (the idiom every scan here uses). */
    private static int[] sourceRange(GradientSource source, int size) {
        int from = source == GradientSource.INVENTORY ? 9 : 0;
        int to = Math.min(source == GradientSource.HOTBAR ? 9 : 36, size);
        return new int[] {from, to};
    }

    /** Item ids of the unique placeable blocks in a source range (availability check). */
    static Set<String> availableIds(Minecraft mc, GradientSource source) {
        Set<String> out = new HashSet<>();
        if (mc.player == null) return out;
        var items = mc.player.getInventory().getNonEquipmentItems();
        int[] range = sourceRange(source, items.size());
        for (int slot = range[0]; slot < range[1]; slot++) {
            ItemStack st = items.get(slot);
            if (!(st.getItem() instanceof BlockItem bi)) continue;
            if (bi.getBlock().defaultBlockState().isAir()) continue;
            Identifier id = BuiltInRegistries.ITEM.getKey(st.getItem());
            if (id != null) out.add(id.toString());
        }
        return out;
    }

    /**
     * The Select-block ring: the unique placeable blocks from the configured source range (the
     * same scan the Paint tab's Solid list does), skipping excluded blocks and Oklab colour-sorted like the
     * list views. Clicking one makes it the Solid block.
     */
    private static List<WheelItem> solidBlockItems(Minecraft mc) {
        List<WheelItem> out = new ArrayList<>();
        if (mc.player == null) return out;
        GradientConfig cfg = ConfigManager.get();
        var items = mc.player.getInventory().getNonEquipmentItems();
        int[] range = sourceRange(cfg.source, items.size());
        record Entry(String id, ItemStack stack, int rgb, boolean missing) {}
        List<Entry> blocks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int slot = range[0]; slot < range[1]; slot++) {
            ItemStack st = items.get(slot);
            if (!(st.getItem() instanceof BlockItem bi)) continue;
            Block b = bi.getBlock();
            if (b.defaultBlockState().isAir()) continue;
            Identifier regId = BuiltInRegistries.ITEM.getKey(st.getItem());
            if (regId == null) continue;
            String id = regId.toString();
            if (!seen.add(id) || cfg.solidExcludedBlocks.contains(id)) continue;
            blocks.add(new Entry(id, st.copy(),
                    BlockTextures.gradientValue(b, null, GradientMode.COLOR, 0.5), false));
        }
        // The selected block stays on the ring (at its colour position, still selected — the
        // carousel rotates to it) even when it's no longer in the inventory; it renders red
        // with a cross instead of disappearing.
        String sel = cfg.solidBlock;
        if (!sel.isEmpty() && !seen.contains(sel)) {
            Block b = Gradient.blockOfItemId(sel);
            if (b != null) {
                blocks.add(new Entry(sel, new ItemStack(b.asItem()),
                        BlockTextures.gradientValue(b, null, GradientMode.COLOR, 0.5), true));
            }
        }
        blocks.sort(Comparator.comparingLong(e -> ColorOrder.colorSortKey(e.rgb())));
        for (Entry e : blocks) {
            String name = e.stack().getHoverName().getString();
            BooleanSupplier selected = () -> {
                GradientConfig c = ConfigManager.get();
                return c.solidMatch == SolidMatch.SELECTED && e.id().equals(c.solidBlock);
            };
            Runnable action = () -> Gradient.setSolidBlock(e.id());
            out.add((e.missing()
                    ? WheelItem.missingIconLeaf(name, e.stack(), selected, action)
                    : WheelItem.iconLeaf(name, e.stack(), selected, action))
                    .remembers(new WheelPress(WheelHistory.BLOCK, e.id(), null, name)));
        }
        return out;
    }

    // ---- layout ---------------------------------------------------------------------------

    /** How a wheel item shows: wrapped label or icon, plus its "N missing" note. */
    private SlotLabel slotLabel(WheelItem item) {
        return SlotLabel.of(this.font, labelOf(item), item.icon(), item.note(), DONT_MATCH.equals(item.label()));
    }

    private static SlotLabel slotLabel(Font font, WheelItem item) {
        return SlotLabel.of(font, item.label(), item.icon(), item.note(), DONT_MATCH.equals(item.label()));
    }

    /**
     * Solve the ring sizes from every item's measured box. The Palette ring's content depends
     * on the paint type, which can change while the wheel is open — so solve against every
     * variant it could show and keep the biggest ("sized from the start, never resizes live").
     */
    private static WheelLayout computeLayout(Font font, List<WheelItem> root, Minecraft mc) {
        int n = root.size();
        int[] rootW = new int[n], rootH = new int[n];
        List<WheelLayout.Arc> arcs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            WheelItem item = root.get(i);
            SlotLabel l = slotLabel(font, item);
            rootW[i] = l.width;
            rootH[i] = l.height;
            if (i == PALETTE_INDEX) {
                // The Palette ring follows the paint type, which can change while the wheel is
                // open: every variant is an arc of its own.
                for (PaintType type : new PaintType[] { PaintType.GRADIENT, PaintType.PATTERN, PaintType.SOLID }) {
                    addArcs(font, arcs, i, paletteChildren(mc, type));
                }
            } else if (item.isCategory()) {
                addArcs(font, arcs, i, item.children());
            }
        }
        // The hole shows the current paint type over the placement mode.
        int centerW = 0;
        for (PaintType t : PaintType.values()) centerW = Math.max(centerW, font.width(t.label()));
        for (PlacementMode m : PlacementMode.values()) centerW = Math.max(centerW, font.width(m.shortName()));
        return WheelLayout.compute(centerW, 2 * font.lineHeight + 2, rootW, rootH, arcs);
    }

    /** A level-1 arc for {@code children} plus a level-2 arc for each sub-category in it. */
    private static void addArcs(Font font, List<WheelLayout.Arc> arcs, int rootSlot, List<WheelItem> children) {
        WheelLayout.Arc arc = WheelLayout.Arc.level1(rootSlot, specOf(font, children));
        arcs.add(arc);
        for (int j = 0; j < children.size(); j++) {
            if (children.get(j).isCategory()) {
                arcs.add(WheelLayout.Arc.level2(arc, j, specOf(font, children.get(j).children())));
            }
        }
    }

    /** A ring's items measured: each one's box width and height. */
    private static WheelLayout.RingSpec specOf(Font font, List<WheelItem> items) {
        int[] w = new int[items.size()], h = new int[items.size()];
        for (int i = 0; i < items.size(); i++) {
            SlotLabel l = slotLabel(font, items.get(i));
            w[i] = Math.max(8, l.width);
            h[i] = Math.max(8, l.height);
        }
        return new WheelLayout.RingSpec(w, h);
    }

    /** Build the fanned ring for a category at its level's solved radius. */
    private ExpandedRing makeRing(WheelItem category, List<WheelItem> children,
                                  double parentAngle, double midRadius) {
        return new ExpandedRing(category, children, parentAngle, specOf(this.font, children).span(), midRadius);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- frame: where the wheel sits and how far it is scaled ---------------------------------

    /** Wheel centre (screen px) and scale, refreshed every frame from the window size. */
    private int cx, cy;
    private float scale = 1f;

    private int historyTop() {
        return this.height - HIST_H - 4;
    }

    /**
     * Fit the wheel between the tab bar and the description + Recent bar: shrink it (never grow
     * it) so everything it can fan out fits, and place its centre so that reach box sits in the
     * middle of the space — arcs fan towards their parent, so the centre is often off-middle.
     */
    private void updateFrame() {
        int top = BAR_H + 4;
        int bottom = historyTop() - DESC_RESERVE;
        int availW = this.width - 12, availH = bottom - top;
        int boxW = layout.left + layout.right + 2, boxH = layout.up + layout.down + 2;
        scale = crispScale(Math.min((double) availW / boxW, (double) availH / boxH));
        cx = (int) Math.round(this.width / 2.0 + (layout.left - layout.right) * scale / 2);
        cy = (int) Math.round((top + bottom) / 2.0 + (layout.up - layout.down) * scale / 2);
    }

    /**
     * The largest scale ≤ {@code want} (and ≤ 1) that keeps text on whole screen pixels — k / GUI
     * scale, e.g. 2/3 at GUI scale 3 — so a shrunk wheel stays sharp instead of blurry, as long as
     * that step is close to {@code want}; otherwise the exact (slightly soft) value.
     */
    private static float crispScale(double want) {
        if (want >= 1) return 1f;
        int gui = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        int k = (int) Math.floor(want * gui);
        // A sharp step far below what fits would leave the wheel needlessly tiny: take the
        // exact size then (slightly soft text beats unreadably small text).
        if (k >= 1 && (float) k / gui >= want * 0.85) return (float) k / gui;
        return (float) Math.max(0.25, want);
    }

    /** Screen px → wheel space (origin at the centre, unscaled). */
    private double wx(double mx) { return (mx - cx) / scale; }
    private double wy(double my) { return (my - cy) / scale; }

    // ---- hit testing + hover expansion ------------------------------------------------------

    /** ring: 0 = root, 1 = {@link #expanded1}'s slots, 2 = {@link #expanded2}'s. */
    private record Hit(int ring, int index) {}

    private Hit hitAt(double mx, double my) {
        double dx = wx(mx), dy = wy(my);
        double r = Math.hypot(dx, dy);
        double angle = WheelMath.angleOf(dx, dy);
        if (r >= layout.r0In && r <= layout.r0Out) {
            return new Hit(0, WheelMath.rootSlot(angle, root.size()));
        }
        if (expanded1 != null && r >= layout.r1In && r <= layout.r1Out) {
            int i = expanded1.slotAt(angle);
            if (i >= 0) return new Hit(1, i);
        }
        if (expanded2 != null && r >= layout.r2In && r <= layout.r2Out) {
            int i = expanded2.slotAt(angle);
            if (i >= 0) return new Hit(2, i);
        }
        return null;
    }

    /** Hovering a category fans it out; hovering a leaf collapses anything deeper than it. */
    private void updateExpansion(Hit hover) {
        if (hover == null) return;
        if (hover.ring() == 0) {
            WheelItem item = root.get(hover.index());
            if (item.isCategory() && (expanded1 == null || expanded1.category != item)) {
                // The Palette ring follows the paint type of the moment; the rest are static.
                List<WheelItem> children = hover.index() == PALETTE_INDEX
                        ? paletteChildren(Minecraft.getInstance(), ConfigManager.get().activePaintType)
                        : item.children();
                expanded1 = makeRing(item, children,
                        WheelMath.rootSlotCenter(hover.index(), root.size()),
                        (layout.r1In + layout.r1Out) / 2.0);
                expanded2 = null;
            }
        } else if (hover.ring() == 1) {
            WheelItem item = expanded1.itemAt(hover.index());
            if (item == null) return; // carousel arrow — leave the expansion alone
            if (item.isCategory()) {
                if (expanded2 == null || expanded2.category != item) {
                    expanded2 = makeRing(item, item.children(),
                            expanded1.slotAngle(hover.index()),
                            (layout.r2In + layout.r2Out) / 2.0);
                }
            } else {
                expanded2 = null;
            }
        }
    }

    /**
     * Open the rings down to where a Recent pick lives, so picking from the bar shows it lit on
     * the wheel. The Palette ring is rebuilt: the pick may just have changed the paint type.
     */
    private void reveal(WheelPress p) {
        int rootIdx = switch (p.kind) {
            case WheelHistory.PAINT -> 0;
            case WheelHistory.MODE, WheelHistory.OPTION -> 2;
            default -> PALETTE_INDEX;
        };
        String sub = switch (p.kind) {
            case WheelHistory.BLOCK -> "This block";
            case WheelHistory.OPTION -> "Options";
            case WheelHistory.MODE -> {
                for (PlacementMode.Group group : PlacementMode.groups()) {
                    if (PlacementMode.byName(p.value) != PlacementMode.DISABLED
                            && group.modes().contains(PlacementMode.byName(p.value))) yield group.title();
                }
                yield null;
            }
            default -> null;
        };
        expanded1 = null;
        expanded2 = null;
        updateExpansion(new Hit(0, rootIdx));
        if (sub == null || expanded1 == null) return;
        for (int i = 0; i < expanded1.slotCount(); i++) {
            WheelItem item = expanded1.itemAt(i);
            if (item != null && item.label().equals(sub)) {
                updateExpansion(new Hit(1, i));
                return;
            }
        }
    }

    // ---- Recent bar ---------------------------------------------------------------------------

    /** One laid-out Recent button (screen px). */
    private record HistButton(WheelPress press, SlotLabel label, int x, int w) {}

    /** Horizontal scroll of the Recent bar, px; 0 = newest at the left (where it opens). */
    private int histScroll;

    private int histLeft() {
        return 8 + SlotLabel.small(this.font, "RECENT") + 8;
    }

    private int histRight() {
        return this.width - 8;
    }

    /** Lay out the buttons at the current scroll; also returns the total content width. */
    private List<HistButton> histButtons(int[] totalOut) {
        List<HistButton> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        int x = histLeft() - histScroll;
        for (WheelPress p : WheelHistory.list()) {
            SlotLabel l = SlotLabel.withPath(this.font, p.path, WheelHistory.label(p), WheelHistory.icon(p),
                    WheelHistory.note(mc, p), HIST_MAX_W);
            int w = Math.max(l.width, 24) + 12;
            out.add(new HistButton(p, l, x, w));
            x += w + HIST_GAP;
        }
        totalOut[0] = x + histScroll - histLeft() - (out.isEmpty() ? 0 : HIST_GAP);
        return out;
    }

    private int maxHistScroll() {
        int[] total = new int[1];
        histButtons(total);
        return Math.max(0, total[0] - (histRight() - histLeft()));
    }

    private boolean overHistory(double mx, double my) {
        return my >= historyTop() && my < historyTop() + HIST_H;
    }

    private HistButton histAt(double mx, double my) {
        if (!overHistory(mx, my) || mx < histLeft() || mx >= histRight() || histArrowAt(mx, my) != 0) return null;
        for (HistButton b : histButtons(new int[1])) {
            if (mx >= b.x() && mx < b.x() + b.w()) return b;
        }
        return null;
    }

    private void paintHistory(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int top = historyTop();
        g.fill(0, top - 2, this.width, top + HIST_H + 2, 0xB0000000);
        // "RECENT" caption, small, to the left of the buttons.
        g.pose().pushMatrix();
        g.pose().translate(8, top + HIST_H / 2f - 3);
        g.pose().scale(SlotLabel.SMALL, SlotLabel.SMALL);
        g.text(this.font, "RECENT", 0, 0, GREY);
        g.pose().popMatrix();

        int[] total = new int[1];
        List<HistButton> buttons = histButtons(total);
        int left = histLeft(), right = histRight();
        if (buttons.isEmpty()) {
            g.text(this.font, "What you pick on the wheel shows up here.", left, top + HIST_H / 2 - 4, GREY);
            return;
        }
        HistButton hover = histAt(mouseX, mouseY);
        g.enableScissor(left, top, right, top + HIST_H);
        List<int[]> crosses = new ArrayList<>();
        for (HistButton b : buttons) {
            if (b.x() + b.w() < left || b.x() > right) continue;
            boolean active = WheelHistory.isActive(b.press());
            boolean missing = b.label().note != null;
            g.fill(b.x(), top, b.x() + b.w(), top + HIST_H, slotColor(active, missing, hover != null && hover.press() == b.press(), false, false));
            int mid = b.x() + b.w() / 2;
            b.label().draw(g, this.font, mid, top + HIST_H / 2, missing ? Ui.MISSING : WHITE);
            if (missing && b.label().icon != null) {
                int iy = top + HIST_H / 2 - b.label().height / 2 + Math.round(this.font.lineHeight * SlotLabel.SMALL) + 2;
                crosses.add(new int[] {mid - 8, iy});
            }
        }
        if (!crosses.isEmpty()) {
            g.nextStratum();
            for (int[] c : crosses) drawCross(g, c[0], c[1]);
        }
        g.disableScissor();
        // More off either end: an arrow button over that edge (click or scroll to move).
        if (histScroll > 0) histArrow(g, left, top, "‹", mouseX, mouseY);
        if (histScroll < maxHistScroll()) histArrow(g, right - HIST_ARROW_W, top, "›", mouseX, mouseY);
    }

    private void histArrow(GuiGraphicsExtractor g, int x, int top, String glyph, int mouseX, int mouseY) {
        boolean hover = Ui.inside(mouseX, mouseY, x, top, HIST_ARROW_W, HIST_H);
        g.fill(x, top, x + HIST_ARROW_W, top + HIST_H, hover ? COL_HOVER | 0xFF000000 : 0xF0141420);
        g.centeredText(this.font, glyph, x + HIST_ARROW_W / 2, top + HIST_H / 2 - 4, WHITE);
    }

    /** -1 / +1 when (mx, my) is on the left / right Recent arrow, else 0. */
    private int histArrowAt(double mx, double my) {
        if (!overHistory(mx, my)) return 0;
        if (histScroll > 0 && mx >= histLeft() && mx < histLeft() + HIST_ARROW_W) return -1;
        if (histScroll < maxHistScroll() && mx >= histRight() - HIST_ARROW_W && mx < histRight()) return 1;
        return 0;
    }

    // ---- input ----------------------------------------------------------------------------

    /** Right-aligned tab x positions: {x, width} pairs, matching the editors' title bars. */
    private int[] tabXs() {
        String[] names = co.fax.wang.GradientScreen.barTabNames();
        int gap = 14;
        int[] out = new int[names.length * 2];
        int x = this.width - 10;
        for (int i = names.length - 1; i >= 0; i--) {
            int w = this.font.width(names[i]);
            x -= w;
            out[i * 2] = x;
            out[i * 2 + 1] = w;
            x -= gap;
        }
        return out;
    }

    /** The ring path a click on {@code ring} came from, for the Recent button's top line. */
    private String pathOf(int ring) {
        String base = expanded1.category.label();
        // Palette picks restore their paint type too, so say which one.
        if (expanded1.category == root.get(PALETTE_INDEX)) {
            base = ConfigManager.get().activePaintType.label() + " › " + base;
        }
        return ring == 2 && expanded2 != null ? base + " › " + expanded2.category.label() : base;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // Title bar: clicking a tab opens the full FW Paint screen there — a quick shortcut
        // out of the wheel. The new screen stays open when the wheel key is released.
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && event.y() < BAR_H) {
            int[] xs = tabXs();
            String[] names = co.fax.wang.GradientScreen.barTabNames();
            for (int i = 0; i < names.length; i++) {
                if (event.x() >= xs[i * 2] && event.x() <= xs[i * 2] + xs[i * 2 + 1]) {
                    this.minecraft.setScreenAndShow(co.fax.wang.GradientScreen.atBarTab(i));
                    return true;
                }
            }
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && overHistory(event.x(), event.y())) {
            int arrow = histArrowAt(event.x(), event.y());
            if (arrow != 0) {
                int page = (histRight() - histLeft()) * 2 / 3;
                histScroll = Math.max(0, Math.min(maxHistScroll(), histScroll + arrow * page));
                return true;
            }
            HistButton b = histAt(event.x(), event.y());
            if (b != null) {
                WheelHistory.apply(b.press());
                histScroll = 0; // it moved to the front
                reveal(b.press());
            }
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            Hit hit = hitAt(event.x(), event.y());
            if (hit != null) {
                ExpandedRing ring = hit.ring() == 1 ? expanded1 : hit.ring() == 2 ? expanded2 : null;
                if (ring != null) {
                    if (ring.isLeftArrow(hit.index())) {
                        ring.scroll(-1);
                    } else if (ring.isRightArrow(hit.index())) {
                        ring.scroll(1);
                    } else {
                        WheelItem item = ring.itemAt(hit.index());
                        // Apply but stay open — the wheel only closes on key release / Esc, so
                        // paint, placement, and palette can all be set in one hold. Categories
                        // expand on hover; clicks are reserved for the actual options.
                        if (item != null && !item.isCategory() && item.action() != null) {
                            String path = item.press() == null ? null : pathOf(hit.ring());
                            item.action().run();
                            if (item.press() != null) {
                                WheelHistory.record(item.press(), path);
                                histScroll = 0;
                            }
                        }
                    }
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (overHistory(mouseX, mouseY)) {
            double d = scrollX != 0 ? -scrollX : -scrollY;
            histScroll = (int) Math.max(0, Math.min(maxHistScroll(), histScroll + d * 30));
            return true;
        }
        ExpandedRing carousel = expanded2 != null && expanded2.carousel() ? expanded2
                : expanded1 != null && expanded1.carousel() ? expanded1 : null;
        if (carousel != null && scrollY != 0) {
            carousel.scroll(scrollY > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        // Hold-to-open: releasing the wheel key closes, keeping the current selection.
        if (Gradient.wheelKey.matches(event)) {
            onClose();
            return true;
        }
        return super.keyReleased(event);
    }

    // ---- rendering ------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // The extractor's dimensions are authoritative for the frame actually being drawn;
        // resync so the wheel stays centred across window resizes / GUI-scale changes
        // (this.width/height only update through init paths this widgetless screen skips).
        this.width = g.guiWidth();
        this.height = g.guiHeight();
        updateFrame();

        // Deliberately no super call: no widgets, and the world should stay visible
        // behind our own dim instead of the vanilla screen background.
        g.fill(0, 0, this.width, this.height, COL_DIM);

        if (debugHoverPath != null) {
            int[] at = debugHoverPoint();
            mouseX = at[0];
            mouseY = at[1];
        }
        Hit hover = hitAt(mouseX, mouseY);
        updateExpansion(hover);
        hover = hitAt(mouseX, mouseY); // a fresh expansion can put a ring under the cursor

        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(scale, scale);
        paintRings(g, hover);
        paintSlotContents(g);
        // The donut hole mirrors the HUD: current paint type over current placement mode.
        GradientConfig cfg = ConfigManager.get();
        g.centeredText(this.font, cfg.activePaintType.label(), 0, -this.font.lineHeight - 1, WHITE);
        g.centeredText(this.font, cfg.placementMode.shortName(), 0, 1, cfg.placementMode.color());
        g.pose().popMatrix();

        paintTabBar(g, mouseX, mouseY);
        paintHistory(g, mouseX, mouseY);
        paintDescription(g, hover, histAt(mouseX, mouseY));
    }

    /** Walk {@link #debugHoverPath}, expanding each ring, and return the last slot's centre. */
    private int[] debugHoverPoint() {
        int[] at = {cx, cy};
        for (int level = 0; level < debugHoverPath.length; level++) {
            int slot = debugHoverPath[level];
            double angle, mid;
            if (level == 0) {
                angle = WheelMath.rootSlotCenter(slot, root.size());
                mid = (layout.r0In + layout.r0Out) / 2.0;
            } else {
                ExpandedRing ring = level == 1 ? expanded1 : expanded2;
                if (ring == null) break;
                angle = ring.slotAngle(Math.min(slot, ring.slotCount() - 1));
                mid = level == 1 ? (layout.r1In + layout.r1Out) / 2.0 : (layout.r2In + layout.r2Out) / 2.0;
            }
            at = new int[] {cx + (int) Math.round(Math.sin(angle) * mid * scale),
                    cy - (int) Math.round(Math.cos(angle) * mid * scale)};
            updateExpansion(hitAt(at[0], at[1]));
        }
        return at;
    }

    /**
     * The description line just above the Recent bar: the hovered ring item's description (an
     * icon's name first, since icons carry no label), a Recent button's full path, and the
     * carousel position when a ring scrolls.
     */
    private void paintDescription(GuiGraphicsExtractor g, Hit hover, HistButton hist) {
        String text = null;
        if (hist != null) {
            WheelPress p = hist.press();
            text = (p.path.isEmpty() ? "" : p.path + " › ") + WheelHistory.label(p)
                    + (hist.label().note != null ? " (" + hist.label().note + ")" : "")
                    + " · click to use again";
        } else if (hover != null) {
            WheelItem item;
            if (hover.ring() == 0) {
                item = root.get(hover.index());
            } else {
                ExpandedRing ring = hover.ring() == 1 ? expanded1 : expanded2;
                item = ring == null ? null : ring.itemAt(hover.index());
            }
            if (item != null) {
                String name = item.icon() != null ? item.label() + (item.note() != null ? " (" + item.note() + ")" : "") : null;
                String desc = item.description();
                text = name == null ? desc : desc == null ? name : name + " — " + desc;
            }
        }
        ExpandedRing carousel = expanded2 != null && expanded2.carousel() ? expanded2
                : expanded1 != null && expanded1.carousel() ? expanded1 : null;
        if (carousel != null && hist == null) {
            String pos = (carousel.offset() + 1) + "–"
                    + Math.min(carousel.offset() + carousel.visibleItems(), carousel.items.size())
                    + " of " + carousel.items.size() + " · scroll for more";
            text = text == null ? pos : text + "   " + pos;
        }
        if (text == null) return;
        int maxW = Math.min(this.width - 32, 380);
        List<String> lines = new ArrayList<>();
        String line = "";
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && this.font.width(next) > maxW) {
                lines.add(line);
                line = word;
            } else {
                line = next;
            }
        }
        if (!line.isEmpty()) lines.add(line);
        if (lines.size() > 2) lines = lines.subList(0, 2);
        int lh = this.font.lineHeight + 2;
        int bottom = historyTop() - 6;
        int y = bottom - lines.size() * lh;
        int boxW = 0;
        for (String l : lines) boxW = Math.max(boxW, this.font.width(l));
        g.fill(this.width / 2 - boxW / 2 - 6, y - 3, this.width / 2 + boxW / 2 + 6, bottom + 1, 0xC0000000);
        for (String l : lines) {
            g.centeredText(this.font, l, this.width / 2, y, WHITE);
            y += lh;
        }
    }

    /**
     * The FW Paint tab bar along the top, same look as the main screen's: quick shortcuts to
     * the settings pages while the wheel is held. No tab is highlighted — the wheel isn't a
     * tab — and clicking one opens the full screen there (which then outlives the key release).
     */
    private void paintTabBar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(0, 0, this.width, BAR_H, 0xE0000000);
        g.fill(0, BAR_H, this.width, BAR_H + 1, Ui.LINE);
        int textY = (BAR_H - this.font.lineHeight) / 2 + 1;
        g.text(this.font, "FW Paint — Wheel", 8, textY, WHITE);
        String[] names = co.fax.wang.GradientScreen.barTabNames();
        int[] xs = tabXs();
        for (int i = 0; i < names.length; i++) {
            boolean hover = mouseY < BAR_H && mouseX >= xs[i * 2]
                    && mouseX <= xs[i * 2] + xs[i * 2 + 1];
            g.text(this.font, names[i], xs[i * 2], textY, hover ? WHITE : GREY);
        }
    }

    private int maxRadius() {
        if (expanded2 != null) return layout.r2Out;
        if (expanded1 != null) return layout.r1Out;
        return layout.r0Out;
    }

    /**
     * Colour for one slot given its state; selection wins over hover, hover over the semi
     * (parent-of-selection) tint, and that over plain expansion.
     */
    private static int slotColor(boolean selected, boolean missing, boolean hovered,
                                 boolean semi, boolean expandedSlot) {
        if (selected) {
            if (missing) return hovered ? COL_MISSING_HOVER : COL_MISSING;
            return hovered ? COL_SELECTED_HOVER : COL_SELECTED;
        }
        if (semi) return hovered ? COL_SEMI_HOVER : COL_SEMI;
        if (hovered) return COL_HOVER;
        return expandedSlot ? COL_EXPANDED : COL_BASE;
    }

    /** Per-slot render state for one ring, resolved once per frame. */
    private record RingState(ExpandedRing ring, boolean[] selected, boolean[] missing,
                             boolean[] semi, int hoverSlot, int expandedSlot) {}

    private RingState stateOf(ExpandedRing ring, Hit hover, int hoverRing, WheelItem expandedChild) {
        int count = ring.slotCount();
        boolean[] sel = new boolean[count];
        boolean[] miss = new boolean[count];
        boolean[] semi = new boolean[count];
        int expandedSlot = -1;
        for (int i = 0; i < count; i++) {
            WheelItem item = ring.itemAt(i);
            if (item == null) continue;
            sel[i] = item.isSelected();
            miss[i] = item.missing();
            semi[i] = item.hasSelectedChild();
            if (expandedChild != null && item == expandedChild) expandedSlot = i;
        }
        int hoverSlot = hover != null && hover.ring() == hoverRing ? hover.index() : -1;
        return new RingState(ring, sel, miss, semi, hoverSlot, expandedSlot);
    }

    /** Per-pixel scan of all rings (wheel space), merging same-coloured horizontal runs into fills. */
    private void paintRings(GuiGraphicsExtractor g, Hit hover) {
        int rootCount = root.size();
        int hoverRoot = hover != null && hover.ring() == 0 ? hover.index() : -1;
        int expandedRoot = expanded1 == null ? -1 : root.indexOf(expanded1.category);
        boolean[] rootSemi = new boolean[rootCount];
        for (int i = 0; i < rootCount; i++) {
            rootSemi[i] = root.get(i).hasSelectedChild();
        }
        RingState s1 = expanded1 == null ? null
                : stateOf(expanded1, hover, 1, expanded2 == null ? null : expanded2.category);
        RingState s2 = expanded2 == null ? null : stateOf(expanded2, hover, 2, null);
        int maxR = maxRadius();

        for (int y = -maxR; y <= maxR; y++) {
            int runStart = 0, runColor = 0;
            for (int x = -maxR; x <= maxR + 1; x++) {
                int color = 0;
                if (x <= maxR) {
                    double dx = x + 0.5, dy = y + 0.5;
                    double r = Math.hypot(dx, dy);
                    if (r >= layout.r0In && r <= layout.r0Out) {
                        double angle = WheelMath.angleOf(dx, dy);
                        int slot = WheelMath.rootSlot(angle, rootCount);
                        color = slotColor(false, false, slot == hoverRoot,
                                rootSemi[slot], slot == expandedRoot);
                        if (rootCount > 1 && onRootBoundary(angle, rootCount, r)) {
                            color = COL_SEPARATOR;
                        }
                    } else if (s1 != null && r >= layout.r1In && r <= layout.r1Out) {
                        color = arcPixel(s1, dx, dy, r);
                    } else if (s2 != null && r >= layout.r2In && r <= layout.r2Out) {
                        color = arcPixel(s2, dx, dy, r);
                    }
                }
                if (color != runColor) {
                    if (runColor != 0) g.fill(runStart, y, x, y + 1, runColor);
                    runStart = x;
                    runColor = color;
                }
            }
        }
    }

    /** One arc ring's pixel: slot fill or separator line. */
    private int arcPixel(RingState s, double dx, double dy, double r) {
        double angle = WheelMath.angleOf(dx, dy);
        int slot = s.ring().slotAt(angle);
        if (slot < 0) return 0;
        if (onArcBoundary(s.ring(), angle, r)) return COL_SEPARATOR;
        return slotColor(s.selected()[slot], s.missing()[slot],
                slot == s.hoverSlot(), s.semi()[slot], slot == s.expandedSlot());
    }

    /** True when the pixel sits within ~0.8px of a root slot boundary line. */
    private boolean onRootBoundary(double angle, int count, double r) {
        double slot = WheelMath.TAU / count;
        double off = WheelMath.normalize(angle + slot / 2) % slot;
        return Math.min(off, slot - off) * r < 0.8;
    }

    /** True near an interior boundary of an arc ring (not its two outer ends). */
    private boolean onArcBoundary(ExpandedRing ring, double angle, double r) {
        int count = ring.slotCount();
        if (count < 2) return false;
        double slot = ring.slotWidth;
        double fromStart = WheelMath.delta(angle, ring.parentAngle) + count * slot / 2;
        double off = fromStart % slot;
        double distance = Math.min(off, slot - off) * r;
        if (distance >= 0.8) return false;
        // A full-circle arc has no ends; otherwise exclude them — only boundaries strictly
        // between slots count.
        return WheelMath.arcIsFullCircle(count, slot)
                || (fromStart > slot / 2 && fromStart < count * slot - slot / 2);
    }

    /** Slot contents (wheel space): labels / icons / carousel arrows, centred on each slot. */
    private void paintSlotContents(GuiGraphicsExtractor g) {
        double mid0 = (layout.r0In + layout.r0Out) / 2.0;
        for (int i = 0; i < root.size(); i++) {
            WheelItem item = root.get(i);
            double a = WheelMath.rootSlotCenter(i, root.size());
            slotLabel(item).draw(g, this.font, (int) Math.round(Math.sin(a) * mid0),
                    -(int) Math.round(Math.cos(a) * mid0), item.labelColor());
        }
        List<int[]> crosses = new ArrayList<>(); // missing icons get their X above the sprites
        if (expanded1 != null) paintArcContents(g, expanded1, (layout.r1In + layout.r1Out) / 2.0, crosses);
        if (expanded2 != null) paintArcContents(g, expanded2, (layout.r2In + layout.r2Out) / 2.0, crosses);
        if (!crosses.isEmpty()) {
            g.nextStratum(); // item sprites render on their own layer; the X must sit above them
            for (int[] c : crosses) drawCross(g, c[0], c[1]);
        }
    }

    private void paintArcContents(GuiGraphicsExtractor g, ExpandedRing ring, double mid, List<int[]> crosses) {
        for (int slot = 0; slot < ring.slotCount(); slot++) {
            double angle = ring.slotAngle(slot);
            int x = (int) Math.round(Math.sin(angle) * mid);
            int y = -(int) Math.round(Math.cos(angle) * mid);
            if (ring.isLeftArrow(slot)) {
                g.centeredText(this.font, "<", x, y - this.font.lineHeight / 2, ring.canScroll(-1) ? WHITE : GREY);
            } else if (ring.isRightArrow(slot)) {
                g.centeredText(this.font, ">", x, y - this.font.lineHeight / 2, ring.canScroll(1) ? WHITE : GREY);
            } else {
                WheelItem item = ring.itemAt(slot);
                SlotLabel l = slotLabel(item);
                l.draw(g, this.font, x, y, colorOf(item));
                if (item.missing() && item.icon() != null) crosses.add(new int[] {x - 8, y - l.height / 2});
            }
        }
    }

    /** A red X over a 16px icon: the block isn't in the inventory. */
    private static void drawCross(GuiGraphicsExtractor g, int x, int y) {
        for (int i = 0; i < 16; i++) {
            g.fill(x + i, y + i, x + i + 1, y + i + 1, COL_CROSS);
            g.fill(x + 15 - i, y + i, x + 16 - i, y + i + 1, COL_CROSS);
        }
    }
}
