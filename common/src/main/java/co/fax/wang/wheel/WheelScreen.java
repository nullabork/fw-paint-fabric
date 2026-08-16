package co.fax.wang.wheel;

import co.fax.wang.BlockTextures;
import co.fax.wang.ColorOrder;
import co.fax.wang.Gradient;
import co.fax.wang.GradientMode;
import co.fax.wang.GradientSource;
import co.fax.wang.PaintType;
import co.fax.wang.PlacementMode;
import co.fax.wang.SolidMatch;
import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import co.fax.wang.palette.Palette;
import co.fax.wang.palette.PaletteKind;
import co.fax.wang.palette.PaletteStore;
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
 * The radial selector wheel. Held open by the wheel keybind (default G); releasing it (or Esc)
 * closes, keeping whatever was chosen. Three ring levels: the root holds <b>Paint</b>,
 * <b>Place</b>, and <b>Palette</b>; hovering a category fans its children into the next ring
 * out (clicking is reserved for the actual options). Paint fans the paint types; Place fans
 * <b>Blocks</b> (Single/Face/Face perp/3D Fill), <b>Markers</b> (Marker/Marker draw), and
 * <b>Shape markers</b> (Marker box/circle/square), each fanning a third ring of modes. The
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
    private static final int COL_DIM = 0x48000000;           // full-screen dim behind the wheel
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFA0A0A0;

    /** Root slot whose children are rebuilt from the active paint type on every expansion. */
    private static final int PALETTE_INDEX = 2;

    /** Height of the tab bar along the top (matches GradientScreen / the editors). */
    private static final int BAR_H = 22;

    private final List<WheelItem> root;
    private final WheelLayout layout;

    /** The fanned-out rings: level 1 (children of a root category) and level 2, or null. */
    private ExpandedRing expanded1;
    private ExpandedRing expanded2;

    public WheelScreen() {
        super(Component.literal("FW Paint selector wheel"));
        Minecraft mc = Minecraft.getInstance();
        this.root = buildRoot(mc);
        this.layout = computeLayout(mc.font, this.root, mc);
    }

    // ---- menu tree ------------------------------------------------------------------------

    private static List<WheelItem> buildRoot(Minecraft mc) {
        List<WheelItem> paints = new ArrayList<>();
        for (PaintType t : new PaintType[] {
                PaintType.SOLID, PaintType.GRADIENT, PaintType.PATTERN, PaintType.NOISE }) {
            paints.add(WheelItem.leaf(t.label(), WHITE,
                    () -> ConfigManager.get().activePaintType == t,
                    () -> Gradient.setPaintType(t)));
        }
        WheelItem blocks = WheelItem.category("Blocks",
                modeLeaf(PlacementMode.SINGLE), modeLeaf(PlacementMode.FACE),
                modeLeaf(PlacementMode.FACE_PERP), modeLeaf(PlacementMode.FILL3D));
        WheelItem markers = WheelItem.category("Markers",
                modeLeaf(PlacementMode.MARKER), modeLeaf(PlacementMode.MARKER_DRAW));
        WheelItem shapes = WheelItem.category("Shape markers",
                modeLeaf(PlacementMode.MARKER_BOX), modeLeaf(PlacementMode.MARKER_CIRCLE),
                modeLeaf(PlacementMode.MARKER_SQUARE));
        return List.of(
                WheelItem.category("Paint", paints.toArray(WheelItem[]::new)),
                WheelItem.category("Place", blocks, markers, shapes),
                // Children here are only the open-time snapshot (they make it a category and
                // feed the layout); every expansion rebuilds them for the paint type of the
                // moment — see updateExpansion.
                WheelItem.category("Palette",
                        paletteChildren(mc, ConfigManager.get().activePaintType).toArray(WheelItem[]::new)));
    }

    private static WheelItem modeLeaf(PlacementMode m) {
        return WheelItem.leaf(m.shortName(), m.color(),
                () -> ConfigManager.get().placementMode == m,
                () -> Gradient.setPlacementMode(m));
    }

    /**
     * The Palette ring's items for a paint type: palettes, patterns, or — for Solid — the
     * match modes, with "Select block" fanning the inventory block picker onto the next ring.
     */
    private static List<WheelItem> paletteChildren(Minecraft mc, PaintType type) {
        if (type == PaintType.SOLID) {
            List<WheelItem> blocks = solidBlockItems(mc);
            WheelItem select = blocks.isEmpty()
                    ? WheelItem.leaf("No blocks in inventory", GREY, null, () -> {})
                    : WheelItem.category("Select block", blocks.toArray(WheelItem[]::new));
            return List.of(select,
                    matchLeaf(SolidMatch.EXACT, "Exact block"),
                    matchLeaf(SolidMatch.CLOSEST_COLOR, "Closest colour"),
                    matchLeaf(SolidMatch.CLOSEST_BRIGHTNESS, "Closest brightness"));
        }
        PaletteKind kind = Gradient.kindFor(type);
        List<Palette> list = PaletteStore.allOf(kind);
        if (list.isEmpty()) {
            return List.of(WheelItem.leaf(
                    "No " + kind.label().toLowerCase(Locale.ROOT) + "s yet", GREY, null, () -> {}));
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
            boolean missing = !p.missingBlocks(
                    avail.computeIfAbsent(p.source, s -> availableIds(mc, s))).isEmpty();
            out.add(missing
                    ? WheelItem.missingLeaf(p.name + " (missing)", selected, action)
                    : WheelItem.leaf(p.name, WHITE, selected, action));
        }
        return out;
    }

    private static WheelItem matchLeaf(SolidMatch m, String label) {
        return WheelItem.leaf(label, WHITE,
                () -> ConfigManager.get().solidMatch == m,
                () -> Gradient.setSolidMatch(m));
    }

    /** Inventory slot range [from, to) for a block source (the idiom every scan here uses). */
    private static int[] sourceRange(GradientSource source, int size) {
        int from = source == GradientSource.INVENTORY ? 9 : 0;
        int to = Math.min(source == GradientSource.HOTBAR ? 9 : 36, size);
        return new int[] {from, to};
    }

    /** Item ids of the unique placeable blocks in a source range (availability check). */
    private static Set<String> availableIds(Minecraft mc, GradientSource source) {
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
     * same scan the Solid tab does), skipping excluded blocks and Oklab colour-sorted like the
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
            out.add(e.missing()
                    ? WheelItem.missingIconLeaf(name + " (missing)", e.stack(), selected, action)
                    : WheelItem.iconLeaf(name, e.stack(), selected, action));
        }
        return out;
    }

    // ---- layout ---------------------------------------------------------------------------

    /**
     * Solve the ring radii from the pixel width of every label. The Palette ring's content
     * depends on the paint type, which can change while the wheel is open — so solve against
     * every variant it could show and keep the biggest, honouring "sized from the start,
     * never resizes live".
     */
    private static WheelLayout computeLayout(Font font, List<WheelItem> root, Minecraft mc) {
        int n = root.size();
        int[] rootW = new int[n];
        WheelLayout.RingSpec[] level1 = new WheelLayout.RingSpec[n];
        WheelLayout.RingSpec[][] level2 = new WheelLayout.RingSpec[n][];
        int centerW = 0;
        for (int i = 0; i < n; i++) {
            WheelItem item = root.get(i);
            rootW[i] = font.width(item.label());
            List<WheelItem> children = item.children();
            level1[i] = specOf(font, children);
            level2[i] = new WheelLayout.RingSpec[children.size()];
            for (int j = 0; j < children.size(); j++) {
                if (children.get(j).isCategory()) {
                    level2[i][j] = specOf(font, children.get(j).children());
                }
            }
        }
        // The hole shows the current paint type + placement mode — those labels come from the
        // Paint children and the Place grandchildren.
        for (WheelItem child : root.get(0).children()) {
            centerW = Math.max(centerW, font.width(child.label()));
        }
        for (WheelItem group : root.get(1).children()) {
            for (WheelItem mode : group.children()) {
                centerW = Math.max(centerW, font.width(mode.label()));
            }
        }
        // The Palette ring's content depends on the paint type, which can change while the
        // wheel is open — merge every variant it could show into one conservative spec
        // ("sized from the start, never resizes live").
        int palCount = 0, palMax = 8;
        WheelLayout.RingSpec blockSpec = null;
        for (PaintType type : new PaintType[] { PaintType.GRADIENT, PaintType.PATTERN, PaintType.SOLID }) {
            List<WheelItem> variant = paletteChildren(mc, type);
            WheelLayout.RingSpec spec = specOf(font, variant);
            palCount = Math.max(palCount, spec.count());
            palMax = Math.max(palMax, spec.maxItemWidth());
            for (WheelItem child : variant) {
                if (child.isCategory()) blockSpec = specOf(font, child.children());
            }
        }
        level1[PALETTE_INDEX] = new WheelLayout.RingSpec(palCount, palMax);
        level2[PALETTE_INDEX] = new WheelLayout.RingSpec[Math.max(1, palCount)];
        level2[PALETTE_INDEX][0] = blockSpec; // Select block sits first on the Solid ring
        return WheelLayout.compute(font.lineHeight, centerW, 2 * font.lineHeight + 2,
                rootW, level1, level2);
    }

    /** A ring's layout summary: item count + the widest item (16 for icon slots). */
    private static WheelLayout.RingSpec specOf(Font font, List<WheelItem> items) {
        int max = 8;
        for (WheelItem it : items) {
            max = Math.max(max, it.icon() != null ? 16 : font.width(it.label()));
        }
        return new WheelLayout.RingSpec(items.size(), max);
    }

    /** Build the fanned ring for a category at its level's solved radius. */
    private static ExpandedRing makeRing(WheelItem category, List<WheelItem> children,
                                         double parentAngle, double midRadius) {
        Font font = Minecraft.getInstance().font;
        int max = 8;
        for (WheelItem it : children) {
            max = Math.max(max, it.icon() != null ? 16 : font.width(it.label()));
        }
        return new ExpandedRing(category, children, parentAngle, max, midRadius);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- hit testing + hover expansion ------------------------------------------------------

    /** ring: 0 = root, 1 = {@link #expanded1}'s slots, 2 = {@link #expanded2}'s. */
    private record Hit(int ring, int index) {}

    private Hit hitAt(double mx, double my) {
        double dx = mx - this.width / 2.0, dy = my - this.height / 2.0;
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

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // Title bar: clicking a tab opens the full FW Paint screen there — a quick shortcut
        // out of the wheel. The new screen stays open when the wheel key is released.
        if (event.button() == 0 && event.y() < BAR_H) {
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
        if (event.button() == 0) {
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
                            item.action().run();
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
        // resync so the wheel stays centered across window resizes / GUI-scale changes
        // (this.width/height only update through init paths this widgetless screen skips).
        this.width = g.guiWidth();
        this.height = g.guiHeight();

        // Deliberately no super call: no widgets, and the world should stay visible
        // behind our own light dim instead of the vanilla screen background.
        g.fill(0, 0, this.width, this.height, COL_DIM);

        int cx = this.width / 2, cy = this.height / 2;
        Hit hover = hitAt(mouseX, mouseY);
        updateExpansion(hover);
        hover = hitAt(mouseX, mouseY); // a fresh expansion can put a ring under the cursor
        paintRings(g, cx, cy, hover);
        paintSlotContents(g, cx, cy);
        paintTabBar(g, mouseX, mouseY);

        // The donut hole mirrors the HUD: current paint type over current placement mode.
        GradientConfig cfg = ConfigManager.get();
        g.centeredText(this.font, cfg.activePaintType.label(), cx, cy - this.font.lineHeight - 1, WHITE);
        g.centeredText(this.font, cfg.placementMode.shortName(), cx, cy + 1, cfg.placementMode.color());

        // Below the wheel: the hovered icon-slot's name (icons carry no label), then the
        // carousel window position.
        int maxR = maxRadius();
        int belowY = cy + maxR + 6;
        if (hover != null && hover.ring() >= 1) {
            ExpandedRing ring = hover.ring() == 1 ? expanded1 : expanded2;
            WheelItem item = ring == null ? null : ring.itemAt(hover.index());
            if (item != null && item.icon() != null) {
                g.centeredText(this.font, item.label(), cx, belowY, WHITE);
                belowY += this.font.lineHeight + 2;
            }
        }
        ExpandedRing carousel = expanded2 != null && expanded2.carousel() ? expanded2
                : expanded1 != null && expanded1.carousel() ? expanded1 : null;
        if (carousel != null) {
            String pos = (carousel.offset() + 1) + "-"
                    + Math.min(carousel.offset() + carousel.visibleItems(), carousel.items.size())
                    + " of " + carousel.items.size();
            g.centeredText(this.font, pos, cx, belowY, GREY);
        }
    }

    /**
     * The FW Paint tab bar along the top, same look as the main screen's: quick shortcuts to
     * the settings pages while the wheel is held. No tab is highlighted — the wheel isn't a
     * tab — and clicking one opens the full screen there (which then outlives the key release).
     */
    private void paintTabBar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(0, 0, this.width, BAR_H, 0xE0000000);
        g.fill(0, BAR_H, this.width, BAR_H + 1, 0x60FFFFFF);
        int textY = (BAR_H - this.font.lineHeight) / 2 + 1;
        g.text(this.font, "FW Paint — Quick selector", 8, textY, WHITE);
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
     * Color for one slot given its state; selection wins over hover, hover over the semi
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

    /** Per-pixel scan of all rings, merging same-colored horizontal runs into fills. */
    private void paintRings(GuiGraphicsExtractor g, int cx, int cy, Hit hover) {
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

        for (int y = cy - maxR; y <= cy + maxR; y++) {
            int runStart = 0, runColor = 0;
            for (int x = cx - maxR; x <= cx + maxR + 1; x++) {
                int color = 0;
                if (x <= cx + maxR) {
                    double dx = x - cx + 0.5, dy = y - cy + 0.5;
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
                        color = arcPixel(s1, dx, dy, r, layout.r1In, layout.r1Out);
                    } else if (s2 != null && r >= layout.r2In && r <= layout.r2Out) {
                        color = arcPixel(s2, dx, dy, r, layout.r2In, layout.r2Out);
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
    private int arcPixel(RingState s, double dx, double dy, double r, int rIn, int rOut) {
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

    /** Slot contents: labels / item icons / carousel arrows, centered on each slot's midpoint. */
    private void paintSlotContents(GuiGraphicsExtractor g, int cx, int cy) {
        double mid0 = (layout.r0In + layout.r0Out) / 2.0;
        for (int i = 0; i < root.size(); i++) {
            drawLabelAt(g, cx, cy, root.get(i).label(), root.get(i).labelColor(),
                    WheelMath.rootSlotCenter(i, root.size()), mid0);
        }
        List<int[]> crosses = new ArrayList<>(); // missing icons get their X above the sprites
        if (expanded1 != null) {
            paintArcContents(g, cx, cy, expanded1, (layout.r1In + layout.r1Out) / 2.0, crosses);
        }
        if (expanded2 != null) {
            paintArcContents(g, cx, cy, expanded2, (layout.r2In + layout.r2Out) / 2.0, crosses);
        }
        if (!crosses.isEmpty()) {
            g.nextStratum(); // item sprites render on their own layer; the X must sit above them
            for (int[] c : crosses) drawCross(g, c[0], c[1]);
        }
    }

    private void paintArcContents(GuiGraphicsExtractor g, int cx, int cy, ExpandedRing ring,
                                  double mid, List<int[]> crosses) {
        for (int slot = 0; slot < ring.slotCount(); slot++) {
            double angle = ring.slotAngle(slot);
            if (ring.isLeftArrow(slot)) {
                drawLabelAt(g, cx, cy, "<", ring.canScroll(-1) ? WHITE : GREY, angle, mid);
            } else if (ring.isRightArrow(slot)) {
                drawLabelAt(g, cx, cy, ">", ring.canScroll(1) ? WHITE : GREY, angle, mid);
            } else {
                WheelItem item = ring.itemAt(slot);
                if (item.icon() != null) {
                    int x = cx + (int) Math.round(Math.sin(angle) * mid);
                    int y = cy - (int) Math.round(Math.cos(angle) * mid);
                    g.item(item.icon(), x - 8, y - 8);
                    if (item.missing()) crosses.add(new int[] {x - 8, y - 8});
                } else {
                    drawLabelAt(g, cx, cy, item.label(), item.labelColor(), angle, mid);
                }
            }
        }
    }

    /** A red X over a 16px icon: the selected block is no longer in the inventory. */
    private static void drawCross(GuiGraphicsExtractor g, int x, int y) {
        for (int i = 0; i < 16; i++) {
            g.fill(x + i, y + i, x + i + 1, y + i + 1, COL_CROSS);
            g.fill(x + 15 - i, y + i, x + 16 - i, y + i + 1, COL_CROSS);
        }
    }

    private void drawLabelAt(GuiGraphicsExtractor g, int cx, int cy, String label, int color,
                             double angle, double radius) {
        int x = cx + (int) Math.round(Math.sin(angle) * radius);
        int y = cy - (int) Math.round(Math.cos(angle) * radius);
        g.centeredText(this.font, label, x, y - this.font.lineHeight / 2, color);
    }
}
