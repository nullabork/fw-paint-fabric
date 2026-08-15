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

    private static List<WheelItem> items(int n, int selected) {
        List<WheelItem> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final boolean sel = i == selected;
            out.add(WheelItem.leaf("item" + i, 0xFFFFFFFF, () -> sel, () -> {}));
        }
        return out;
    }

    @Test
    void smallListsFanOutPlainWithoutArrows() {
        ExpandedRing ring = new ExpandedRing(null, items(8, -1), 0);
        assertFalse(ring.carousel());
        assertEquals(8, ring.slotCount());
        assertFalse(ring.isLeftArrow(0));
        assertFalse(ring.isRightArrow(7));
        for (int i = 0; i < 8; i++) {
            assertEquals("item" + i, ring.itemAt(i).label());
        }
    }

    @Test
    void overflowBecomesCarouselWithArrowEndSlots() {
        ExpandedRing ring = new ExpandedRing(null, items(20, -1), 0);
        assertTrue(ring.carousel());
        assertEquals(ExpandedRing.CAROUSEL_SLOTS, ring.slotCount());
        assertTrue(ring.isLeftArrow(0));
        assertTrue(ring.isRightArrow(ExpandedRing.CAROUSEL_SLOTS - 1));
        assertNull(ring.itemAt(0));
        assertNull(ring.itemAt(ExpandedRing.CAROUSEL_SLOTS - 1));
        // Window starts at the list head: slots 1..5 show items 0..4.
        for (int slot = 1; slot <= ExpandedRing.CAROUSEL_ITEMS; slot++) {
            assertEquals("item" + (slot - 1), ring.itemAt(slot).label());
        }
    }

    @Test
    void scrollingMovesTheWindowAndClampsAtTheEnds() {
        ExpandedRing ring = new ExpandedRing(null, items(9, -1), 0);
        assertTrue(ring.carousel(), "9 items must overflow the 8-slot arc");
        assertFalse(ring.canScroll(-1));
        assertTrue(ring.canScroll(1));
        ring.scroll(1);
        assertEquals("item1", ring.itemAt(1).label());
        ring.scroll(1000);
        assertEquals(9 - ExpandedRing.CAROUSEL_ITEMS, ring.offset(), "clamped to the tail");
        assertFalse(ring.canScroll(1));
        ring.scroll(-1000);
        assertEquals(0, ring.offset());
    }

    @Test
    void carouselOpensCenteredOnTheSelection() {
        ExpandedRing ring = new ExpandedRing(null, items(30, 15), 0);
        int first = ring.offset();
        assertTrue(first <= 15 && 15 < first + ExpandedRing.CAROUSEL_ITEMS,
                "selected item must be inside the initial window (offset=" + first + ")");
    }

    @Test
    void slotAnglesRoundTripThroughSlotAt() {
        for (int n : new int[] {1, 3, 8, 20}) {
            ExpandedRing ring = new ExpandedRing(null, items(n, -1), Math.PI / 3);
            for (int slot = 0; slot < ring.slotCount(); slot++) {
                assertEquals(slot, ring.slotAt(ring.slotAngle(slot)), "n=" + n + " slot=" + slot);
            }
        }
    }

    @Test
    void categoryIsExposedForExpansionToggling() {
        WheelItem cat = WheelItem.category("Cat",
                WheelItem.leaf("a", 0xFFFFFFFF, null, () -> {}));
        ExpandedRing ring = new ExpandedRing(cat, cat.children(), 0);
        assertSame(cat, ring.category);
    }
}
