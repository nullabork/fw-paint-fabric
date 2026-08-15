package co.fax.wang.wheel;

import java.util.List;

/**
 * The state of one fanned-out ring: which category is open, its (possibly dynamic) item list,
 * the content-driven slot geometry, and — when the list outgrows the circle — the carousel
 * window scrolled over it. Slots are exactly as wide as the ring's widest item needs at the
 * ring's radius ({@link WheelMath#slotWidthFor}), so a ring of 16px block icons packs far
 * more slots than a ring of text labels; how many fit before the ring becomes a scrolling
 * carousel follows from that ({@link WheelMath#arcCapacity}). In carousel mode the visible
 * item slots are bracketed by a scroll arrow at each end of the arc, stepped by clicking the
 * arrows or scrolling the mouse wheel.
 */
final class ExpandedRing {

    final WheelItem category;
    final List<WheelItem> items;
    final double parentAngle;
    /** Angular width of one slot, sized from the ring's widest item at its radius. */
    final double slotWidth;
    /** Item slots shown at once: all of them, or the circle's capacity minus the two arrows. */
    private final int visibleItems;
    /** First visible item index (carousel mode only). */
    private int offset;

    ExpandedRing(WheelItem category, List<WheelItem> items, double parentAngle,
                 int maxItemWidth, double radius) {
        this.category = category;
        this.items = items;
        this.parentAngle = parentAngle;
        this.slotWidth = WheelMath.slotWidthFor(maxItemWidth, radius);
        int capacity = WheelMath.arcCapacity(slotWidth);
        this.visibleItems = items.size() > capacity ? Math.max(1, capacity - 2) : items.size();
        // Start the carousel window on the current selection so it's visible without scrolling.
        if (carousel()) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).isSelected()) {
                    offset = clampOffset(i - visibleItems / 2);
                    break;
                }
            }
        }
    }

    boolean carousel() {
        return items.size() > visibleItems;
    }

    int visibleItems() {
        return visibleItems;
    }

    int slotCount() {
        return carousel() ? visibleItems + 2 : items.size();
    }

    boolean isLeftArrow(int slot) {
        return carousel() && slot == 0;
    }

    boolean isRightArrow(int slot) {
        return carousel() && slot == slotCount() - 1;
    }

    /** The item shown in a slot, or null for the two arrow slots. */
    WheelItem itemAt(int slot) {
        if (!carousel()) return items.get(slot);
        if (slot <= 0 || slot >= slotCount() - 1) return null;
        return items.get(offset + slot - 1);
    }

    double slotAngle(int slot) {
        return WheelMath.arcSlotCenter(parentAngle, slotCount(), slotWidth, slot);
    }

    /** Slot under the angle, or -1 outside the arc. */
    int slotAt(double angle) {
        return WheelMath.arcSlot(angle, parentAngle, slotCount(), slotWidth);
    }

    boolean canScroll(int dir) {
        return carousel() && clampOffset(offset + dir) != offset;
    }

    /** Step the carousel window; no-op beyond either end. */
    void scroll(int dir) {
        offset = clampOffset(offset + dir);
    }

    int offset() {
        return offset;
    }

    private int clampOffset(int o) {
        return Math.max(0, Math.min(items.size() - visibleItems, o));
    }
}
