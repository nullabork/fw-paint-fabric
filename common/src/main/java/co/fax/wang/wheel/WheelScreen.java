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
 * closes, keeping whatever was chosen. The inner ring holds the root slots — Paint, Markers,
 * Placement, Palette, and Disabled — and clicking a category fans its children out into an
 * outer arc around its angle (via {@link ExpandedRing}, which turns into a scrollable radial
 * carousel when a list outgrows the arc). The Palette slot's children follow the active paint
 * type: gradient palettes for Gradient/Noise paint, patterns for Pattern paint, and for Solid
 * paint "Closest colour" followed by every unique placeable block in the configured source
 * (Oklab colour-sorted like the list views; clicking one selects it as the Solid block).
 * Clicking a child applies it exactly like the old cycle hotkeys did (persisted to config +
 * action-bar flash); the wheel stays open so several things can be set in one hold. The donut
 * hole always shows the current paint type + placement mode, mirroring the HUD.
 *
 * <p>Ring radii come from {@link WheelLayout}, solved from the pixel widths of every label when
 * the wheel opens (across every possible Palette-ring variant) — longer labels make a bigger
 * wheel from the start, nothing resizes live. The donut is painted per-pixel (with horizontal
 * run merging into {@code fill} calls) — the 26.2 render-state GUI only exposes axis-aligned
 * fills, and even a large wheel is well under ~100k pixel tests per frame, which is nothing.
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
    private static final int COL_DIM = 0x48000000;           // full-screen dim behind the wheel
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFA0A0A0;

    /** Root slot whose children are rebuilt from the active paint type on every expansion. */
    private static final int PALETTE_INDEX = 3;

    private final List<WheelItem> root;
    private final WheelLayout layout;

    /** The category currently fanned out into the outer ring, or null. */
    private ExpandedRing expanded;

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
        List<WheelItem> markers = new ArrayList<>();
        List<WheelItem> placing = new ArrayList<>();
        for (PlacementMode m : PlacementMode.values()) {
            if (m == PlacementMode.DISABLED) continue;
            (m.isMarker() ? markers : placing).add(modeLeaf(m));
        }
        return List.of(
                WheelItem.category("Paint", paints.toArray(WheelItem[]::new)),
                WheelItem.category("Markers", markers.toArray(WheelItem[]::new)),
                WheelItem.category("Placement", placing.toArray(WheelItem[]::new)),
                // Children here are only the open-time snapshot (they make it a category and
                // feed the layout); every expansion rebuilds them for the paint type of the
                // moment — see mouseClicked.
                WheelItem.category("Palette",
                        paletteChildren(mc, ConfigManager.get().activePaintType).toArray(WheelItem[]::new)),
                modeLeaf(PlacementMode.DISABLED));
    }

    private static WheelItem modeLeaf(PlacementMode m) {
        return WheelItem.leaf(m.shortName(), m.color(),
                () -> ConfigManager.get().placementMode == m,
                () -> Gradient.setPlacementMode(m));
    }

    /** The Palette ring's items for a paint type: palettes, patterns, or the Solid block picker. */
    private static List<WheelItem> paletteChildren(Minecraft mc, PaintType type) {
        if (type == PaintType.SOLID) return solidChildren(mc);
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
     * Solid paint's ring: "Closest colour" first, then the unique placeable blocks from the
     * configured source range (the same scan the Solid tab does), skipping excluded blocks and
     * Oklab colour-sorted like the list views.
     */
    private static List<WheelItem> solidChildren(Minecraft mc) {
        List<WheelItem> out = new ArrayList<>();
        out.add(WheelItem.leaf("Closest colour", WHITE,
                () -> ConfigManager.get().solidMatch == SolidMatch.CLOSEST_COLOR,
                Gradient::setSolidClosestColour));
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
        int[] rootW = new int[root.size()];
        int[][] childW = new int[root.size()][];
        int centerW = 0;
        for (int i = 0; i < root.size(); i++) {
            WheelItem item = root.get(i);
            rootW[i] = font.width(item.label());
            childW[i] = ringWidths(font, item.children());
            for (WheelItem child : item.children()) {
                if (child.icon() == null) {
                    // The hole shows the current paint type + mode — those labels come from here.
                    centerW = Math.max(centerW, font.width(child.label()));
                }
            }
        }
        int centerH = 2 * font.lineHeight + 2;
        WheelLayout best = null;
        for (PaintType type : new PaintType[] { PaintType.GRADIENT, PaintType.PATTERN, PaintType.SOLID }) {
            childW[PALETTE_INDEX] = ringWidths(font, paletteChildren(mc, type));
            WheelLayout l = WheelLayout.compute(font.lineHeight, centerW, centerH, rootW, childW);
            if (best == null || l.r1Out > best.r1Out) best = l;
        }
        return best;
    }

    /**
     * Slot widths a ring presents to the layout: the actual label widths (16 for icon slots) —
     * or, when the list overflows into a carousel, its fixed slot arc: an arrow, the visible
     * item slots each as wide as the widest item (any item can scroll into any slot), an arrow.
     */
    private static int[] ringWidths(Font font, List<WheelItem> items) {
        int[] w = new int[items.size()];
        for (int i = 0; i < items.size(); i++) {
            WheelItem it = items.get(i);
            w[i] = it.icon() != null ? 16 : font.width(it.label());
        }
        if (items.size() <= ExpandedRing.MAX_SLOTS) return w;
        int max = 16;
        for (int width : w) max = Math.max(max, width);
        int[] slots = new int[ExpandedRing.CAROUSEL_SLOTS];
        java.util.Arrays.fill(slots, max);
        slots[0] = slots[slots.length - 1] = font.width("<");
        return slots;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- hit testing ----------------------------------------------------------------------

    /** ring: 0 = root, 1 = slots of {@link #expanded} (carousel arrows included). */
    private record Hit(int ring, int index) {}

    private Hit hitAt(double mx, double my) {
        double dx = mx - this.width / 2.0, dy = my - this.height / 2.0;
        double r = Math.hypot(dx, dy);
        double angle = WheelMath.angleOf(dx, dy);
        if (r >= layout.r0In && r <= layout.r0Out) {
            return new Hit(0, WheelMath.rootSlot(angle, root.size()));
        }
        if (expanded != null && r >= layout.r1In && r <= layout.r1Out) {
            int i = expanded.slotAt(angle);
            if (i >= 0) return new Hit(1, i);
        }
        return null;
    }

    // ---- input ----------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() == 0) {
            Hit hit = hitAt(event.x(), event.y());
            if (hit != null) {
                if (hit.ring() == 0) {
                    clickRoot(hit.index());
                } else {
                    clickExpanded(hit.index());
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    private void clickRoot(int index) {
        WheelItem item = root.get(index);
        if (item.isCategory()) {
            if (expanded != null && expanded.category == item) {
                expanded = null;
            } else {
                // The Palette ring follows the paint type of the moment; the rest are static.
                List<WheelItem> children = index == PALETTE_INDEX
                        ? paletteChildren(Minecraft.getInstance(), ConfigManager.get().activePaintType)
                        : item.children();
                expanded = new ExpandedRing(item, children,
                        WheelMath.rootSlotCenter(index, root.size()));
            }
        } else if (item.action() != null) {
            item.action().run();
        }
    }

    private void clickExpanded(int slot) {
        if (expanded.isLeftArrow(slot)) {
            expanded.scroll(-1);
            return;
        }
        if (expanded.isRightArrow(slot)) {
            expanded.scroll(1);
            return;
        }
        WheelItem item = expanded.itemAt(slot);
        // Apply but stay open — the wheel only closes on key release / Esc, so paint type,
        // placement, and palette can all be set in one hold.
        if (item != null && item.action() != null) item.action().run();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (expanded != null && expanded.carousel() && scrollY != 0) {
            expanded.scroll(scrollY > 0 ? -1 : 1);
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
        paintRings(g, cx, cy, hover);
        paintSlotContents(g, cx, cy);

        // The donut hole mirrors the HUD: current paint type over current placement mode.
        GradientConfig cfg = ConfigManager.get();
        g.centeredText(this.font, cfg.activePaintType.label(), cx, cy - this.font.lineHeight - 1, WHITE);
        g.centeredText(this.font, cfg.placementMode.shortName(), cx, cy + 1, cfg.placementMode.color());

        // Below the wheel: the hovered icon-slot's name (icons carry no label), then the
        // carousel window position.
        int maxR = expanded == null ? layout.r0Out : layout.r1Out;
        int belowY = cy + maxR + 6;
        if (hover != null && hover.ring() == 1) {
            WheelItem item = expanded.itemAt(hover.index());
            if (item != null && item.icon() != null) {
                g.centeredText(this.font, item.label(), cx, belowY, WHITE);
                belowY += this.font.lineHeight + 2;
            }
        }
        if (expanded != null && expanded.carousel()) {
            String pos = (expanded.offset() + 1) + "-"
                    + Math.min(expanded.offset() + ExpandedRing.CAROUSEL_ITEMS, expanded.items.size())
                    + " of " + expanded.items.size();
            g.centeredText(this.font, pos, cx, belowY, GREY);
        }
    }

    /** Color for one slot given its state; selection wins over hover, hover over expansion. */
    private static int slotColor(boolean selected, boolean missing, boolean hovered, boolean expandedSlot) {
        if (selected) {
            if (missing) return hovered ? COL_MISSING_HOVER : COL_MISSING;
            return hovered ? COL_SELECTED_HOVER : COL_SELECTED;
        }
        if (hovered) return COL_HOVER;
        return expandedSlot ? COL_EXPANDED : COL_BASE;
    }

    /** Per-pixel scan of both rings, merging same-colored horizontal runs into fills. */
    private void paintRings(GuiGraphicsExtractor g, int cx, int cy, Hit hover) {
        int rootCount = root.size();
        int slotCount = expanded == null ? 0 : expanded.slotCount();
        int hoverRoot = hover != null && hover.ring() == 0 ? hover.index() : -1;
        int hoverChild = hover != null && hover.ring() == 1 ? hover.index() : -1;
        int expandedIndex = expanded == null ? -1 : root.indexOf(expanded.category);
        int maxR = expanded == null ? layout.r0Out : layout.r1Out;

        // Selected/missing state per slot, resolved once per frame.
        boolean[] slotSelected = new boolean[slotCount];
        boolean[] slotMissing = new boolean[slotCount];
        for (int i = 0; i < slotCount; i++) {
            WheelItem item = expanded.itemAt(i);
            slotSelected[i] = item != null && item.isSelected();
            slotMissing[i] = item != null && item.missing();
        }

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
                        color = slotColor(root.get(slot).isSelected(), false, slot == hoverRoot,
                                slot == expandedIndex);
                        if (rootCount > 1 && onRootBoundary(angle, rootCount, r)) color = COL_SEPARATOR;
                    } else if (slotCount > 0 && r >= layout.r1In && r <= layout.r1Out) {
                        double angle = WheelMath.angleOf(dx, dy);
                        int slot = expanded.slotAt(angle);
                        if (slot >= 0) {
                            color = slotColor(slotSelected[slot], slotMissing[slot],
                                    slot == hoverChild, false);
                            if (onChildBoundary(angle, slotCount, r)) color = COL_SEPARATOR;
                        }
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

    /** True when the pixel sits within ~0.8px of a root slot boundary line. */
    private boolean onRootBoundary(double angle, int count, double r) {
        double slot = WheelMath.TAU / count;
        double off = WheelMath.normalize(angle + slot / 2) % slot;
        return Math.min(off, slot - off) * r < 0.8;
    }

    /** True near an interior boundary of the child arc (not its two outer ends). */
    private boolean onChildBoundary(double angle, int count, double r) {
        if (count < 2) return false;
        double slot = WheelMath.childSlotWidth(count);
        double fromStart = WheelMath.delta(angle, expanded.parentAngle) + count * slot / 2;
        double off = fromStart % slot;
        double distance = Math.min(off, slot - off) * r;
        if (distance >= 0.8) return false;
        // A full-circle arc has no ends; otherwise exclude them — only boundaries strictly
        // between slots count.
        return WheelMath.childArcIsFullCircle(count)
                || (fromStart > slot / 2 && fromStart < count * slot - slot / 2);
    }

    /** Slot contents: labels / item icons / carousel arrows, centered on each slot's midpoint. */
    private void paintSlotContents(GuiGraphicsExtractor g, int cx, int cy) {
        double mid0 = (layout.r0In + layout.r0Out) / 2.0;
        for (int i = 0; i < root.size(); i++) {
            drawLabelAt(g, cx, cy, root.get(i).label(), root.get(i).labelColor(),
                    WheelMath.rootSlotCenter(i, root.size()), mid0);
        }
        if (expanded == null) return;
        double mid1 = (layout.r1In + layout.r1Out) / 2.0;
        List<int[]> crosses = new ArrayList<>(); // missing icons get their X above the sprites
        for (int slot = 0; slot < expanded.slotCount(); slot++) {
            double angle = expanded.slotAngle(slot);
            if (expanded.isLeftArrow(slot)) {
                drawLabelAt(g, cx, cy, "<", expanded.canScroll(-1) ? WHITE : GREY, angle, mid1);
            } else if (expanded.isRightArrow(slot)) {
                drawLabelAt(g, cx, cy, ">", expanded.canScroll(1) ? WHITE : GREY, angle, mid1);
            } else {
                WheelItem item = expanded.itemAt(slot);
                if (item.icon() != null) {
                    int x = cx + (int) Math.round(Math.sin(angle) * mid1);
                    int y = cy - (int) Math.round(Math.cos(angle) * mid1);
                    g.item(item.icon(), x - 8, y - 8);
                    if (item.missing()) crosses.add(new int[] {x - 8, y - 8});
                } else {
                    drawLabelAt(g, cx, cy, item.label(), item.labelColor(), angle, mid1);
                }
            }
        }
        if (!crosses.isEmpty()) {
            g.nextStratum(); // item sprites render on their own layer; the X must sit above them
            for (int[] c : crosses) drawCross(g, c[0], c[1]);
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
