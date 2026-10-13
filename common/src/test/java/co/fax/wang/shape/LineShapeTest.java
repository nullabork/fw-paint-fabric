package co.fax.wang.shape;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LineShapeTest {

    private static LineShape straight(double len) {
        return new LineShape(new LineCurve(new Vec3(0.5, 10.5, 0.5), new Vec3(0.5 + len, 10.5, 0.5)));
    }

    @Test
    void straightLineSamplesHaveArcLengthAndOrthonormalFrames() {
        List<LineCurve.Sample> ss = straight(10).samples();
        assertEquals(10.0, ss.get(ss.size() - 1).s(), 1e-9);
        for (LineCurve.Sample s : ss) {
            assertEquals(1.0, s.tan().length(), 1e-9);
            assertEquals(0.0, s.tan().dot(s.n()), 1e-9);
            assertEquals(0.0, s.tan().dot(s.b()), 1e-9);
        }
    }

    @Test
    void framesDoNotTwistAroundABend() {
        // Quarter turn in the XZ plane: the normal should stay world-up the whole way.
        LineCurve c = new LineCurve(new Vec3(0, 0, 0), new Vec3(10, 0, 10));
        c.nodes.get(0).out = new Vec3(6, 0, 0);
        c.nodes.get(1).in = new Vec3(0, 0, -6);
        for (LineCurve.Sample s : c.sample(0.25)) {
            assertEquals(1.0, s.n().y, 1e-6, "normal drifted off up at s=" + s.s());
        }
    }

    @Test
    void insertNodeKeepsTheCurveShape() {
        LineCurve c = new LineCurve(new Vec3(0, 0, 0), new Vec3(10, 0, 0));
        c.nodes.get(0).out = new Vec3(2, 5, 0);
        c.nodes.get(1).in = new Vec3(-3, 4, 0);
        LineCurve before = c.copy();
        int idx = c.insertNode(0, 0.4);
        assertEquals(1, idx);
        assertEquals(3, c.nodes.size());
        assertTrue(c.nodes.get(1).p.distanceTo(before.point(0, 0.4)) < 1e-9);
        // The two halves trace the original segment.
        assertTrue(c.point(0, 0.5).distanceTo(before.point(0, 0.2)) < 1e-9);
        assertTrue(c.point(1, 0.5).distanceTo(before.point(0, 0.7)) < 1e-9);
    }

    @Test
    void closestToRayFindsTheCrossing() {
        LineShape l = straight(10);
        // A ray straight down through x = 4.5 crosses the line there.
        LineCurve.RayHit hit = LineCurve.closestToRay(l.samples(), new Vec3(4.5, 20, 0.5), new Vec3(0, -1, 0));
        assertNotNull(hit);
        assertEquals(0.0, hit.distance(), 1e-9);
        Vec3 a = l.samples().get(hit.sampleIndex()).pos(), b = l.samples().get(hit.sampleIndex() + 1).pos();
        assertEquals(4.5, a.x + (b.x - a.x) * hit.frac(), 1e-9);
    }

    @Test
    void bareLineIsAConnectedChainOfCells() {
        LineShape l = new LineShape(new LineCurve(new Vec3(0.5, 0.5, 0.5), new Vec3(6.5, 4.5, 3.5)));
        l.ensureCells(true);
        assertTrue(l.contains(new BlockPos(0, 0, 0)));
        assertTrue(l.contains(new BlockPos(6, 4, 3)));
        // Manhattan path at least: every axis step needs its own cell.
        assertTrue(l.cellCount() >= 6 + 4 + 3 + 1);
        for (long k : l.cellKeys()) {
            BlockPos p = BlockPos.of(k);
            if (p.equals(new BlockPos(0, 0, 0)) || p.equals(new BlockPos(6, 4, 3))) continue;
            int neighbours = 0;
            for (var d : net.minecraft.core.Direction.values()) {
                if (l.cellKeys().contains(p.relative(d).asLong())) neighbours++;
            }
            assertTrue(neighbours >= 2, "chain broken at " + p);
        }
    }

    @Test
    void circleShellIsHollowAndSolidIsFull() {
        LineShape l = straight(10);
        l.profile = LineProfile.CIRCLE;
        l.r0 = l.r1 = 3;
        BlockPos axis = new BlockPos(5, 10, 0);
        assertFalse(l.contains(axis), "shell must be hollow on the axis");
        assertTrue(l.contains(axis.above(3)), "rim cell missing");
        l.solid = true;
        l.markDirty();
        assertTrue(l.contains(axis));
        assertTrue(l.contains(axis.above(3)));
        assertFalse(l.contains(axis.above(5)));
    }

    @Test
    void bigSquareKeepsSharpCorners() {
        LineShape l = straight(10);
        l.profile = LineProfile.SQUARE;
        l.r0 = l.r1 = 8;
        // Corner of the square's cross-section at the middle of the line: 8 out on both axes.
        assertTrue(l.contains(new BlockPos(5, 18, 8)), "square corner got rounded off");
        assertTrue(l.contains(new BlockPos(5, 2, -8)), "square corner got rounded off");
    }

    @Test
    void tubeEndsAreCutFlat() {
        LineShape l = straight(10);
        l.profile = LineProfile.SQUARE;
        l.solid = true;
        l.r0 = l.r1 = 2;
        assertTrue(l.contains(new BlockPos(0, 10, 0)));
        assertFalse(l.contains(new BlockPos(-2, 10, 0)), "cap should not bulge past the start");
        assertFalse(l.contains(new BlockPos(13, 10, 0)), "cap should not bulge past the end");
    }

    @Test
    void radiusTapersBetweenTheEnds() {
        LineShape l = straight(20);
        l.profile = LineProfile.CIRCLE;
        l.solid = true;
        l.r0 = 5;
        l.r1 = 1;
        assertTrue(l.contains(new BlockPos(1, 15, 0)), "fat start should reach 5 up");
        assertFalse(l.contains(new BlockPos(19, 15, 0)), "thin end should not");
    }

    @Test
    void twistRotatesTheProfile() {
        LineShape l = straight(10);
        l.profile = LineProfile.TRIANGLE;
        l.solid = true;
        l.r0 = l.r1 = 4;
        // Triangle points up (+n = world up here): its apex cell exists, the opposite one doesn't.
        BlockPos mid = new BlockPos(5, 10, 0);
        assertTrue(l.contains(mid.above(4)));
        assertFalse(l.contains(mid.below(4)));
        l.tw0 = l.tw1 = Math.PI; // half a turn: now it points down
        l.markDirty();
        assertTrue(l.contains(mid.below(4)));
        assertFalse(l.contains(mid.above(4)));
    }

    @Test
    void gradientRunsStartToEnd() {
        LineShape l = straight(10);
        l.ensureCells(true);
        assertTrue(l.fracOf(new BlockPos(0, 10, 0)) < 0.1);
        assertTrue(l.fracOf(new BlockPos(9, 10, 0)) > 0.85);
        RingShape.Segment seg = l.segmentFor(new BlockPos(5, 10, 0));
        assertNotNull(seg);
        assertEquals(10, seg.length());
        assertEquals(5, seg.index(), 1);
    }

    @Test
    void starProfileHasPointsAndNotches() {
        assertTrue(LineProfile.STAR.inside(4.2, 0, 4));      // top point (+u)
        assertFalse(LineProfile.STAR.inside(-4.2, 0, 4));    // notch between the bottom two points
        assertTrue(LineProfile.STAR.inside(0, 0, 4));
    }

    private static int exposedFaces(it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap cells) {
        int n = 0;
        for (long k : cells.keySet()) {
            for (var d : net.minecraft.core.Direction.values()) {
                if (!cells.containsKey(BlockPos.offset(k, d))) n++;
            }
        }
        return n;
    }

    @Test
    void mergedFacesCoverExactlyTheExposedCellFaces() {
        for (LineProfile prof : new LineProfile[]{LineProfile.CIRCLE, LineProfile.STAR, LineProfile.SQUARE}) {
            LineCurve c = new LineCurve(new Vec3(0.5, 10.5, 0.5), new Vec3(20.5, 14.5, 6.5));
            c.nodes.get(0).out = new Vec3(4, 8, 0);
            TubeBuild.Result r = TubeBuild.build(new TubeBuild.Params(c, prof, false, 4, 2, 0, 1));
            double area = 0;
            float[] f = r.faces();
            for (int q = 0; q < f.length; q += 12) {
                Vec3 a = new Vec3(f[q], f[q + 1], f[q + 2]), b = new Vec3(f[q + 3], f[q + 4], f[q + 5]),
                        d = new Vec3(f[q + 9], f[q + 10], f[q + 11]);
                double w = b.distanceTo(a) - 2 * TubeBuild.EXPAND, h = d.distanceTo(a) - 2 * TubeBuild.EXPAND;
                area += Math.round(w) * Math.round(h);
            }
            assertEquals(exposedFaces(r.cells()), area, 1e-6, prof + ": merged faces must tile the exposed faces");
        }
    }

    @Test
    void straightSquareTubeMergesToFewQuads() {
        TubeBuild.Result r = TubeBuild.build(new TubeBuild.Params(
                new LineCurve(new Vec3(0.5, 10.5, 0.5), new Vec3(40.5, 10.5, 0.5)),
                LineProfile.SQUARE, true, 4, 4, 0, 0));
        int quads = r.faces().length / 12;
        assertTrue(quads <= 6 * 2, "a straight solid square tube should be ~6 rectangles, got " + quads);
        int edgeQuads = r.edges().length / 12;
        assertTrue(edgeQuads <= 12 * 2, "its 12 edges should merge into runs, got " + edgeQuads + " quads");
    }
}
