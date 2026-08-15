package co.fax.wang.shape;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The paint-region semantics layered on the ported shapes: containment + gradient segments. */
class RegionTest {

    // ---- box ------------------------------------------------------------------------------

    @Test
    void boxContainsIsInclusiveAndOrderless() {
        BoxRegion b = new BoxRegion(new BlockPos(5, 10, 5), new BlockPos(2, 7, 8), Direction.Axis.Y);
        assertTrue(b.contains(new BlockPos(2, 7, 5)));
        assertTrue(b.contains(new BlockPos(5, 10, 8)));
        assertTrue(b.contains(new BlockPos(3, 8, 6)));
        assertFalse(b.contains(new BlockPos(6, 8, 6)));
        assertFalse(b.contains(new BlockPos(3, 11, 6)));
    }

    @Test
    void boxSegmentRunsFromCornerAPlaneToCornerBPlane() {
        // Corner A at y=10 (start side), corner B at y=7 — gradient runs downward.
        BoxRegion b = new BoxRegion(new BlockPos(5, 10, 5), new BlockPos(2, 7, 8), Direction.Axis.Y);
        RingShape.Segment seg = b.segmentFor(new BlockPos(3, 9, 6));
        assertEquals(new BlockPos(3, 10, 6), seg.start());
        assertEquals(new BlockPos(3, 7, 6), seg.end());
        assertEquals(1, seg.index());
        assertEquals(3, seg.length());
        // On the start plane: index 0; on the end plane: index == length.
        assertEquals(0, b.segmentFor(new BlockPos(3, 10, 6)).index());
        assertEquals(3, b.segmentFor(new BlockPos(3, 7, 6)).index());
    }

    @Test
    void flatBoxHasNoSegment() {
        BoxRegion b = new BoxRegion(new BlockPos(0, 5, 0), new BlockPos(4, 5, 4), Direction.Axis.Y);
        assertTrue(b.contains(new BlockPos(2, 5, 2)));
        assertNull(b.segmentFor(new BlockPos(2, 5, 2)));
    }

    // ---- rings ----------------------------------------------------------------------------

    private static RingShape ring(int rIn, int rOut, int height) {
        // Y-normal circle at origin: u = X, v = Z. Controls on the +X axis at the two radii.
        RingShape s = new RingShape(RingShape.Kind.CIRCLE, BlockPos.ZERO, Direction.Axis.Y,
                new BlockPos(rIn, 0, 0), new BlockPos(rOut, 0, 0));
        s.height = height;
        return s;
    }

    @Test
    void ringContainsRespectsBandAndExtrusion() {
        RingShape s = ring(3, 5, 4); // band 3..5, layers 0..3
        assertTrue(s.contains(new BlockPos(4, 0, 0)));
        assertTrue(s.contains(new BlockPos(0, 2, 4)));   // radius 4, layer 2
        assertTrue(s.contains(new BlockPos(3, 3, 4)));   // hypot(3,4)=5, top layer
        assertFalse(s.contains(new BlockPos(1, 0, 1)));  // inside the hole
        assertFalse(s.contains(new BlockPos(4, 4, 0)));  // above the extrusion
        assertFalse(s.contains(new BlockPos(4, -1, 0))); // below the base plane
    }

    @Test
    void ringSegmentRunsFromBasePlaneToFarEnd() {
        RingShape s = ring(3, 5, 4); // layers 0..3; base plane (layer 0) = start
        RingShape.Segment seg = s.segmentFor(new BlockPos(4, 1, 0));
        assertEquals(new BlockPos(4, 0, 0), seg.start());
        assertEquals(new BlockPos(4, 3, 0), seg.end());
        assertEquals(1, seg.index());
        assertEquals(3, seg.length());
    }

    @Test
    void negativeExtrusionKeepsBasePlaneAsStart() {
        RingShape s = ring(2, 2, -3); // layers -2..0; base plane still the start side
        RingShape.Segment seg = s.segmentFor(new BlockPos(2, -1, 0));
        assertEquals(new BlockPos(2, 0, 0), seg.start());
        assertEquals(new BlockPos(2, -2, 0), seg.end());
        assertEquals(1, seg.index());
        assertEquals(2, seg.length());
    }

    @Test
    void singleLayerRingHasNoSegment() {
        RingShape s = ring(2, 2, 1);
        assertTrue(s.contains(new BlockPos(2, 0, 0)));
        assertNull(s.segmentFor(new BlockPos(2, 0, 0)));
    }

    @Test
    void equalRadiiGiveOneWideOutlineAndBandsGiveWalls() {
        assertEquals(1, ring(4, 4, 1).rMax() - ring(4, 4, 1).rMin() + 1);
        RingShape band = ring(3, 6, 1);
        assertTrue(band.contains(new BlockPos(5, 0, 0)));  // wall thickness spans 3..6
        assertFalse(band.contains(new BlockPos(2, 0, 0)));
    }

    @Test
    void rotationKeepsCapturedRadii() {
        RingShape s = new RingShape(RingShape.Kind.SQUARE, BlockPos.ZERO, Direction.Axis.Y,
                new BlockPos(3, 0, 0), new BlockPos(5, 0, 0));
        double[] offA = s.offsetOf(s.ctrlA), offB = s.offsetOf(s.ctrlB);
        s.applyRotation(Math.PI / 4, 0, offA, offB);
        assertEquals(3, s.rMin());
        assertEquals(5, s.rMax());
        // The old axis-aligned corner is outside the rotated square's band...
        assertFalse(s.contains(new BlockPos(5, 0, 5)));
        // ...and the rotated corner (on the diagonal at ~rMax·√2 along X) is inside.
        assertTrue(s.contains(new BlockPos(7, 0, 0)));
    }
}
