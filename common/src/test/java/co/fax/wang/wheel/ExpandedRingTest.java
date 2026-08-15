package co.fax.wang.wheel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpandedRingTest {

    private static final int ICON_W = 16;    // slotWidthFor(16, 100) = 0.28 → capacity 22
    private static final int RADIUS = 100;

    private static List<WheelItem> items(int n, int selected) {
        List<WheelItem> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final boolean sel = i == selected;
            out.add(WheelItem.leaf("item" + i, 0xFFFFFFFF, () -> sel, () -> {}));
        }
        return out;
    }

    private static ExpandedRing ring(int n, int selected) {
        return new ExpandedRing(null, items(n, selected), 0, ICON_W, RADIUS);
    }

    @Test
    void listsWithinCapacityFanOutPlainWithoutArrows() {
        ExpandedRing ring = ring(8, -1);
        assertFalse(ring.carousel());
        assertEquals(8, ring.slotCount());
        assertFalse(ring.isLeftArrow(0));
        assertFalse(ring.isRightArrow(7));
        for (int i = 0; i < 8; i++) {
            assertEquals("item" + i, ring.itemAt(i).label());
        }
    }

    @Test
    void narrowItemsPackFarMoreSlotsThanTheOldFixedEight() {
        // 22 icons fit plain at this radius; the fixed-45° wheel capped out at 8.
        ExpandedRing ring = ring(22, -1);
        assertFalse(ring.carousel());
        assertEquals(22, ring.slotCount());
    }

    @Test
    void wideItemsHitTheQuarterTurnCapAndOverflowSooner() {
        // 100px labels at radius 100 cap at 45° slots → capacity 8 → 10 items scroll.
        ExpandedRing ring = new ExpandedRing(null, items(10, -1), 0, 100, RADIUS);
        assertTrue(ring.carousel());
        assertEquals(6, ring.visibleItems());
        assertEquals(8, ring.slotCount());
    }

    @Test
    void overflowBecomesCarouselWithArrowEndSlots() {
        ExpandedRing ring = ring(30, -1); // capacity 22 → carousel: 20 visible + 2 arrows
        assertTrue(ring.carousel());
        assertEquals(20, ring.visibleItems());
        assertEquals(22, ring.slotCount());
        assertTrue(ring.isLeftArrow(0));
        assertTrue(ring.isRightArrow(ring.slotCount() - 1));
        assertNull(ring.itemAt(0));
        assertNull(ring.itemAt(ring.slotCount() - 1));
        // Window starts at the list head: slot k shows item k-1.
        for (int slot = 1; slot <= ring.visibleItems(); slot++) {
            assertEquals("item" + (slot - 1), ring.itemAt(slot).label());
        }
    }

    @Test
    void scrollingMovesTheWindowAndClampsAtTheEnds() {
        ExpandedRing ring = ring(25, -1); // capacity 22 → visible 20, offsets 0..5
        assertTrue(ring.carousel());
        assertFalse(ring.canScroll(-1));
        assertTrue(ring.canScroll(1));
        ring.scroll(1);
        assertEquals("item1", ring.itemAt(1).label());
        ring.scroll(1000);
        assertEquals(25 - ring.visibleItems(), ring.offset(), "clamped to the tail");
        assertFalse(ring.canScroll(1));
        ring.scroll(-1000);
        assertEquals(0, ring.offset());
    }

    @Test
    void carouselOpensCenteredOnTheSelection() {
        ExpandedRing ring = ring(60, 40);
        int first = ring.offset();
        assertTrue(first <= 40 && 40 < first + ring.visibleItems(),
                "selected item must be inside the initial window (offset=" + first + ")");
    }

    @Test
    void slotAnglesRoundTripThroughSlotAt() {
        for (int n : new int[] {1, 3, 8, 22, 40}) {
            ExpandedRing ring = new ExpandedRing(null, items(n, -1), Math.PI / 3, ICON_W, RADIUS);
            for (int slot = 0; slot < ring.slotCount(); slot++) {
                assertEquals(slot, ring.slotAt(ring.slotAngle(slot)), "n=" + n + " slot=" + slot);
            }
        }
    }

    @Test
    void categoryIsExposedForExpansionToggling() {
        WheelItem cat = WheelItem.category("Cat",
                WheelItem.leaf("a", 0xFFFFFFFF, null, () -> {}));
        ExpandedRing ring = new ExpandedRing(cat, cat.children(), 0, 20, RADIUS);
        assertSame(cat, ring.category);
    }
}
