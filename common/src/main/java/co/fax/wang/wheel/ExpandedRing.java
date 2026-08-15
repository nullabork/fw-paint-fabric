package co.fax.wang.wheel;

import java.util.List;

/**
 * The state of one fanned-out outer ring: which category is open, its (possibly dynamic) item
 * list, and — when the list is too long for the arc — the carousel window scrolled over it.
 * Up to {@link #MAX_SLOTS} items fan out as plain slots; with more, the ring becomes a radial
 * carousel: {@link #CAROUSEL_ITEMS} visible item slots bracketed by a scroll arrow at each end
 * of the arc, stepped by clicking the arrows or scrolling the mouse wheel. Every category goes
 * through this class, so any ring that grows past the cap scrolls for free.
 */
final class ExpandedRing {

    /** Slots the outer ring can hold before scrolling (8 × the 45° slot cap = full circle). */
    static final int MAX_SLOTS = 8;
    /** Visible item slots in carousel mode; with the two arrows that's a 315° arc. */
    static final int CAROUSEL_ITEMS = 5;
    static final int CAROUSEL_SLOTS = CAROUSEL_ITEMS + 2;

    final WheelItem category;
    final List<WheelItem> items;
    final double parentAngle;
    /** First visible item index (carousel mode only). */
    private int offset;

    ExpandedRing(WheelItem category, List<WheelItem> items, double parentAngle) {
        this.category = category;
        this.items = items;
        this.parentAngle = parentAngle;
        // Start the carousel window on the current selection so it's visible without scrolling.
        if (carousel()) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).isSelected()) {
                    offset = clampOffset(i - CAROUSEL_ITEMS / 2);
                    break;
                }
            }
        }
    }

    boolean carousel() {
        return items.size() > MAX_SLOTS;
    }

    int slotCount() {
        return carousel() ? CAROUSEL_SLOTS : items.size();
    }

    boolean isLeftArrow(int slot) {
        return carousel() && slot == 0;
    }

    boolean isRightArrow(int slot) {
        return carousel() && slot == CAROUSEL_SLOTS - 1;
    }

    /** The item shown in a slot, or null for the two arrow slots. */
    WheelItem itemAt(int slot) {
        if (!carousel()) return items.get(slot);
        if (slot <= 0 || slot >= CAROUSEL_SLOTS - 1) return null;
        return items.get(offset + slot - 1);
    }

    double slotAngle(int slot) {
        return WheelMath.childSlotCenter(parentAngle, slotCount(), slot);
    }

    /** Slot under the angle, or -1 outside the arc. */
    int slotAt(double angle) {
        return WheelMath.childSlot(angle, parentAngle, slotCount());
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
        return Math.max(0, Math.min(items.size() - CAROUSEL_ITEMS, o));
    }
}
