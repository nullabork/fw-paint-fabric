package co.fax.wang.wheel;

/**
 * Pure geometry for the radial selector wheel — no Minecraft classes, so it unit-tests headlessly.
 *
 * <p>Angle convention throughout: radians in {@code [0, TAU)}, {@code 0} pointing straight up
 * (12 o'clock), increasing clockwise (screen y grows downward).
 *
 * <p>The root ring divides the full circle evenly among its items, slot 0 centered at the top.
 * A category's children don't take the whole outer ring; they fan out as an arc of fixed-width
 * slots centered on the parent's angle (capped at {@link #MAX_CHILD_SLOT}), so the sub-menu
 * stays visually attached to the category that opened it. Enough children (8 at the 45° cap)
 * wrap into a complete outer circle.
 */
public final class WheelMath {

    public static final double TAU = 2 * Math.PI;
    /** Widest a single child slot may be (a quarter turn looks right for 1-3 children). */
    public static final double MAX_CHILD_SLOT = Math.PI / 4;

    private WheelMath() {}

    /** Normalizes any angle into {@code [0, TAU)}. */
    public static double normalize(double angle) {
        double a = angle % TAU;
        return a < 0 ? a + TAU : a;
    }

    /** Angle of the vector (dx, dy) in screen coords: 0 = up, clockwise. */
    public static double angleOf(double dx, double dy) {
        return normalize(Math.atan2(dx, -dy));
    }

    /** Signed shortest angular difference {@code a - b}, in {@code (-PI, PI]}. */
    public static double delta(double a, double b) {
        double d = normalize(a - b);
        return d > Math.PI ? d - TAU : d;
    }

    /** Center angle of root slot {@code i} when the ring holds {@code count} items. */
    public static double rootSlotCenter(int i, int count) {
        return normalize(TAU * i / count);
    }

    /** Which of {@code count} evenly divided root slots the angle falls in. */
    public static int rootSlot(double angle, int count) {
        double slot = TAU / count;
        return (int) (normalize(angle + slot / 2) / slot) % count;
    }

    /** Arc padding (px) added around an item when sizing its slot's angular width. */
    public static final double SLOT_ARC_PAD = 12;

    /**
     * Content-driven slot width: just wide enough that the slot's arc at {@code radius} holds
     * the ring's widest item plus padding, capped at {@link #MAX_CHILD_SLOT}. Narrow items
     * (16px block icons) get narrow slots, so far more fit around the circle than wide labels.
     */
    public static double slotWidthFor(int maxItemWidth, double radius) {
        return Math.min(MAX_CHILD_SLOT, (maxItemWidth + SLOT_ARC_PAD) / Math.max(1, radius));
    }

    /** How many slots of {@code slotWidth} fit the full circle (the carousel threshold). */
    public static int arcCapacity(double slotWidth) {
        return (int) Math.floor(TAU / slotWidth + 1e-9);
    }

    /** True when {@code slots} slots of {@code slotWidth} fill the whole ring (no arc ends). */
    public static boolean arcIsFullCircle(int slots, double slotWidth) {
        return slots * slotWidth >= TAU - 1e-9;
    }

    /** Center angle of slot {@code i} in an arc of {@code slots} fanned around {@code parentCenter}. */
    public static double arcSlotCenter(double parentCenter, int slots, double slotWidth, int i) {
        double start = parentCenter - slots * slotWidth / 2;
        return normalize(start + slotWidth * (i + 0.5));
    }

    /**
     * Which slot the angle falls in for an arc of {@code slots} slots of {@code slotWidth}
     * centered on {@code parentCenter}, or {@code -1} when the angle is outside the arc.
     */
    public static int arcSlot(double angle, double parentCenter, int slots, double slotWidth) {
        double half = slots * slotWidth / 2;
        double d = delta(angle, parentCenter);
        if (d < -half || d >= half) return -1;
        return Math.min(slots - 1, (int) ((d + half) / slotWidth));
    }
}
