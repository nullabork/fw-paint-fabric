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
        l.setAllRadii(3);
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
        l.setAllRadii(8);
        // Corner of the square's cross-section at the middle of the line: 8 out on both axes.
        assertTrue(l.contains(new BlockPos(5, 18, 8)), "square corner got rounded off");
        assertTrue(l.contains(new BlockPos(5, 2, -8)), "square corner got rounded off");
    }

    @Test
    void tubeEndsAreCutFlat() {
        LineShape l = straight(10);
        l.profile = LineProfile.SQUARE;
        l.solid = true;
        l.setAllRadii(2);
        assertTrue(l.contains(new BlockPos(0, 10, 0)));
        assertFalse(l.contains(new BlockPos(-2, 10, 0)), "cap should not bulge past the start");
        assertFalse(l.contains(new BlockPos(13, 10, 0)), "cap should not bulge past the end");
    }

    @Test
    void radiusTapersBetweenTheEnds() {
        LineShape l = straight(20);
        l.profile = LineProfile.CIRCLE;
        l.solid = true;
        l.curve.nodes.get(0).r = 5;
        l.curve.nodes.get(1).r = 1;
        assertTrue(l.contains(new BlockPos(1, 15, 0)), "fat start should reach 5 up");
        assertFalse(l.contains(new BlockPos(19, 15, 0)), "thin end should not");
    }

    @Test
    void twistRotatesTheProfile() {
        LineShape l = straight(10);
        l.profile = LineProfile.TRIANGLE;
        l.solid = true;
        l.setAllRadii(4);
        // Triangle points up (+n = world up here): its apex cell exists, the opposite one doesn't.
        BlockPos mid = new BlockPos(5, 10, 0);
        assertTrue(l.contains(mid.above(4)));
        assertFalse(l.contains(mid.below(4)));
        l.setAllTwists(Math.PI); // half a turn: now it points down
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
            TubeBuild.Result r = TubeBuild.build(new TubeBuild.Params(withEnds(c, 4, 2, 0, 1), prof, false));
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
                withEnds(new LineCurve(new Vec3(0.5, 10.5, 0.5), new Vec3(40.5, 10.5, 0.5)), 4, 4, 0, 0),
                LineProfile.SQUARE, true));
        int quads = r.faces().length / 12;
        assertTrue(quads <= 6 * 2, "a straight solid square tube should be ~6 rectangles, got " + quads);
        int edgeQuads = r.edges().length / 12;
        assertTrue(edgeQuads <= 12 * 2, "its 12 edges should merge into runs, got " + edgeQuads + " quads");
    }

    // ---- per-node radius / twist ------------------------------------------------------------

    private static LineCurve withEnds(LineCurve c, double r0, double r1, double tw0, double tw1) {
        c.nodes.get(0).r = r0;
        c.nodes.get(c.nodes.size() - 1).r = r1;
        c.nodes.get(0).tw = tw0;
        c.nodes.get(c.nodes.size() - 1).tw = tw1;
        return c;
    }

    @Test
    void radiusEasesBetweenNodesWithASmoothstep() {
        LineCurve c = new LineCurve(new Vec3(0, 0, 0), new Vec3(10, 0, 0));
        c.nodes.get(0).r = 2;
        c.nodes.get(1).r = 6;
        assertEquals(2, c.radiusAt(0, 0), 1e-9);
        assertEquals(6, c.radiusAt(0, 1), 1e-9);
        assertEquals(4, c.radiusAt(0, 0.5), 1e-9);              // symmetric: halfway at the middle
        assertEquals(2 + 4 * 0.028, c.radiusAt(0, 0.1), 1e-9);  // flat out of the node (3t² − 2t³)
        assertEquals(6 - 4 * 0.028, c.radiusAt(0, 0.9), 1e-9);
    }

    @Test
    void aMiddleNodeMakesABulge() {
        LineCurve c = new LineCurve(new Vec3(0.5, 10.5, 0.5), new Vec3(20.5, 10.5, 0.5));
        c.insertNode(0, 0.5);
        c.nodes.get(1).p = new Vec3(10.5, 10.5, 0.5);
        c.nodes.get(1).in = new Vec3(-3, 0, 0);
        c.nodes.get(1).out = new Vec3(3, 0, 0);
        c.nodes.get(0).r = c.nodes.get(2).r = 1;
        c.nodes.get(1).r = 6;
        LineShape l = new LineShape(c);
        l.profile = LineProfile.CIRCLE;
        l.solid = true;
        assertTrue(l.contains(new BlockPos(10, 16, 0)), "the middle bulges out to 6");
        assertFalse(l.contains(new BlockPos(1, 16, 0)), "the start stays thin");
        assertFalse(l.contains(new BlockPos(19, 16, 0)), "the end stays thin");
    }

    @Test
    void insertedNodeTakesTheSizeAlreadyThere() {
        LineCurve c = withEnds(new LineCurve(new Vec3(0, 0, 0), new Vec3(10, 0, 0)), 2, 6, 0, 1);
        double r = c.radiusAt(0, 0.3), tw = c.twistAt(0, 0.3);
        int idx = c.insertNode(0, 0.3);
        assertEquals(r, c.nodes.get(idx).r, 1e-9);
        assertEquals(tw, c.nodes.get(idx).tw, 1e-9);
        // The ends are untouched.
        assertEquals(2, c.nodes.get(0).r, 1e-9);
        assertEquals(6, c.nodes.get(2).r, 1e-9);
    }

    @Test
    void removingANodeKeepsTheOthersSizes() {
        LineCurve c = new LineCurve(new Vec3(0, 0, 0), new Vec3(10, 0, 0));
        c.insertNode(0, 0.5);
        c.insertNode(1, 0.5);
        for (int i = 0; i < 4; i++) c.nodes.get(i).r = 1 + i;
        c.nodes.remove(1);
        assertEquals(1, c.nodes.get(0).r);
        assertEquals(3, c.nodes.get(1).r);
        assertEquals(4, c.nodes.get(2).r);
    }

    @Test
    void sizeCubesSitOnTheirOwnNode() {
        LineCurve c = new LineCurve(new Vec3(0.5, 10.5, 0.5), new Vec3(20.5, 10.5, 0.5));
        c.insertNode(0, 0.5);
        c.nodes.get(1).r = 5;
        LineShape l = new LineShape(c);
        l.profile = LineProfile.CIRCLE;
        Vec3 mid = c.nodes.get(1).p;
        // n' is world-up on a level line: the cube floats r + 0.5 above its node.
        assertEquals(mid.add(0, 5.5, 0).distanceTo(l.sizeNode(1)), 0, 1e-6);
        assertEquals(c.nodes.get(0).p.add(0, 2.5, 0).distanceTo(l.sizeNode(0)), 0, 1e-6);
    }

    @Test
    void reverseSwapsTheEndsAndKeepsTheCurve() {
        LineCurve c = withEnds(new LineCurve(new Vec3(0, 0, 0), new Vec3(10, 0, 0)), 1, 5, 0, 0);
        c.nodes.get(0).out = new Vec3(2, 5, 0);
        LineCurve before = c.copy();
        LineShape l = new LineShape(c);
        l.reverse();
        assertEquals(new Vec3(10, 0, 0), c.nodes.get(0).p);
        assertEquals(5, c.nodes.get(0).r);
        assertEquals(1, c.nodes.get(1).r);
        assertTrue(c.point(0, 0.3).distanceTo(before.point(0, 0.7)) < 1e-9, "same curve, run backwards");
    }

    // ---- save migration -----------------------------------------------------------------------

    @Test
    void legacyEndSizesSpreadOverTheNodes() {
        ShapeStore.StoredLine sl = new ShapeStore.StoredLine();
        // Old 9-number nodes: start, a node a quarter of the way along, end.
        sl.nodes = new double[][]{{0, 0, 0, 0, 0, 0, 0, 0, 0}, {5, 0, 0, 0, 0, 0, 0, 0, 0},
                {20, 0, 0, 0, 0, 0, 0, 0, 0}};
        sl.profile = "CIRCLE";
        sl.gradient = "ALONG";
        sl.r0 = 2;
        sl.r1 = 6;
        sl.tw0 = 0;
        sl.tw1 = 2;
        LineShape l = ShapeStore.decodeLine(sl);
        assertNotNull(l);
        assertEquals(LineGradient.ALONG, l.gradient);
        assertEquals(2, l.curve.nodes.get(0).r, 1e-9);
        assertEquals(6, l.curve.nodes.get(2).r, 1e-9);
        assertEquals(3, l.curve.nodes.get(1).r, 1e-6);   // old linear blend at 1/4 of the way
        assertEquals(0.5, l.curve.nodes.get(1).tw, 1e-6);
        assertEquals(2, l.curve.nodes.get(2).tw, 1e-9);
    }

    @Test
    void perNodeSizesRoundTripAndStillWriteTheEnds() {
        LineCurve c = new LineCurve(new Vec3(0.5, 0.5, 0.5), new Vec3(10.5, 0.5, 0.5));
        c.insertNode(0, 0.5);
        c.nodes.get(0).r = 1.5;
        c.nodes.get(1).r = 7;
        c.nodes.get(2).r = 3;
        c.nodes.get(1).tw = 0.75;
        LineShape l = new LineShape(c);
        l.profile = LineProfile.STAR;
        l.solid = true;
        ShapeStore.StoredLine sl = ShapeStore.encodeLine(l);
        assertEquals(1.5, sl.r0);   // an older build reads the end sizes back
        assertEquals(3, sl.r1);
        LineShape back = ShapeStore.decodeLine(sl);
        assertEquals(3, back.curve.nodes.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(c.nodes.get(i).r, back.curve.nodes.get(i).r, 1e-12);
            assertEquals(c.nodes.get(i).tw, back.curve.nodes.get(i).tw, 1e-12);
            assertEquals(c.nodes.get(i).p, back.curve.nodes.get(i).p);
        }
        assertEquals(LineProfile.STAR, back.profile);
        assertTrue(back.solid);
    }

    @Test
    void legacySaveJsonLoads() {
        String json = "{\"nodes\":[[0.5,0.5,0.5,0,0,0,0,0,0],[10.5,0.5,0.5,0,0,0,0,0,0]],"
                + "\"profile\":\"SQUARE\",\"solid\":false,\"gradient\":\"ACROSS\","
                + "\"r0\":4.0,\"r1\":1.0,\"tw0\":0.0,\"tw1\":0.5}";
        ShapeStore.StoredLine sl = new com.google.gson.Gson().fromJson(json, ShapeStore.StoredLine.class);
        LineShape l = ShapeStore.decodeLine(sl);
        assertEquals(4, l.curve.nodes.get(0).r);
        assertEquals(1, l.curve.nodes.get(1).r);
        assertEquals(0.5, l.curve.nodes.get(1).tw);
    }

    private static LineShape bentTube() {
        LineCurve c = new LineCurve(new Vec3(0.5, 10.5, 0.5), new Vec3(12.5, 14.5, 6.5));
        c.nodes.get(0).out = new Vec3(4, 0, 1);
        c.nodes.get(1).in = new Vec3(0, -3, -2);
        c.nodes.get(0).tw = 0.3;
        c.nodes.get(1).tw = 1.1;
        LineShape l = new LineShape(c);
        l.profile = LineProfile.SQUARE;
        return l;
    }

    @Test
    void turningALineCarriesItsProfileAndGimbalRigidly() {
        LineShape l = bentTube();
        Vec3 centre = l.curve.centre();
        Vec3 axis = new Vec3(1, 2, -0.5).normalize();
        double angle = 1.234;
        List<LineCurve.Sample> before = List.copyOf(l.samples());
        Vec3[][] frames = new Vec3[before.size()][];
        for (int i = 0; i < frames.length; i++) frames[i] = l.rotatedFrame(i);
        double reach = l.reach();

        l.turnFrom(l.pose(), centre, axis, angle);

        List<LineCurve.Sample> after = l.samples();
        assertEquals(before.size(), after.size());
        for (int i = 0; i < after.size(); i += 7) {
            Vec3 want = centre.add(LineCurve.turn(before.get(i).pos().subtract(centre), axis, angle));
            assertEquals(0, want.distanceTo(after.get(i).pos()), 1e-6, "sample " + i + " position");
            // The profile's own axes (twist included) turn with the line: a square stays square-on.
            assertEquals(0, LineCurve.turn(frames[i][0], axis, angle).distanceTo(l.rotatedFrame(i)[0]), 1e-6,
                    "sample " + i + " profile");
        }
        assertEquals(0, centre.distanceTo(l.curve.centre()), 1e-9); // it turns about its middle
        assertEquals(reach, l.reach(), 1e-6);
        assertEquals(0, l.gimbalX.dot(l.gimbalY), 1e-9);
        assertEquals(0, LineCurve.turn(new Vec3(1, 0, 0), axis, angle).distanceTo(l.gimbalX), 1e-9);
    }

    @Test
    void tiltingARingLeavesTheLineAndTheOtherRingAlone() {
        LineShape l = bentTube();
        LineShape.Pose from = l.pose();
        Vec3 first = l.curve.nodes.get(0).p;
        double tw = l.curve.nodes.get(0).tw;
        l.tiltGimbalFrom(from, 0, Math.toRadians(30));
        assertEquals(0, first.distanceTo(l.curve.nodes.get(0).p), 0); // the line has not moved
        assertEquals(tw, l.curve.nodes.get(0).tw, 0);
        assertEquals(0, l.gimbalX.distanceTo(from.gimbalX()), 0); // the other ring's axis holds
        assertEquals(Math.cos(Math.toRadians(30)), l.gimbalY.dot(from.gimbalY()), 1e-9);
        assertEquals(0, l.gimbalX.dot(l.gimbalY), 1e-9); // still at right angles
        l.tiltGimbalFrom(l.pose(), 1, Math.toRadians(50));
        assertEquals(0, l.gimbalX.dot(l.gimbalY), 1e-9);
        assertEquals(1, l.gimbalX.length(), 1e-9);
        LineShape back = ShapeStore.decodeLine(ShapeStore.encodeLine(l)); // a tilt alone is saved
        assertEquals(0, l.gimbalX.distanceTo(back.gimbalX), 1e-12);
        assertEquals(0, l.gimbalY.distanceTo(back.gimbalY), 1e-12);
    }

    @Test
    void turnedGimbalIsSavedWithTheLine() {
        LineShape l = bentTube();
        assertNull(ShapeStore.encodeLine(l).gimbal); // never turned: nothing extra in the save
        l.turnFrom(l.pose(), l.curve.centre(), new Vec3(0, 1, 0), Math.PI / 2);
        LineShape back = ShapeStore.decodeLine(ShapeStore.encodeLine(l));
        assertEquals(0, l.gimbalX.distanceTo(back.gimbalX), 1e-12);
        assertEquals(0, l.gimbalY.distanceTo(back.gimbalY), 1e-12);
    }

    @Test
    void controlClusterStaysInReachAndSideBySide() {
        LineShape l = straight(40); // along +X at y 10.5, z 0.5
        Vec3 eye = new Vec3(30, 11, 3);
        l.aimControls(eye, false);
        Vec3[] c = l.controls(eye);
        double d = c[1].distanceTo(eye);
        assertTrue(d >= LineShape.CTRL_NEAR && d <= LineShape.CTRL_FAR, "cluster " + d + " from the eye");
        // Side by side across the view: evenly spaced on a level line at right angles to eye -> cog.
        Vec3 across = c[2].subtract(c[0]);
        assertEquals(2 * LineShape.CTRL_SPACING, across.length(), 1e-9);
        assertEquals(0, across.y, 1e-9);
        assertEquals(0, across.dot(c[1].subtract(eye)), 1e-9);
        // Standing still (or only looking around) never moves it.
        Vec3 was = c[1];
        l.aimControls(eye, false);
        assertEquals(0, was.distanceTo(l.controls(eye)[1]), 1e-12);
        // Walk well along the line: it comes after you.
        Vec3 far = new Vec3(5, 11, 3);
        l.aimControls(far, false);
        l.slideControls(10);
        double d2 = l.controls(far)[1].distanceTo(far);
        assertTrue(d2 >= LineShape.CTRL_NEAR && d2 <= LineShape.CTRL_FAR, "cluster " + d2 + " after walking");
    }

    @Test
    void rayCircleFindsTheGrabbedAngleFaceOnAndEdgeOn() {
        Vec3 c = new Vec3(0, 0, 0), axis = new Vec3(0, 1, 0);
        Vec3[] uv = Gizmo.planeAxes(axis);
        double a = 0.9, r = 5;
        Vec3 on = uv[0].scale(Math.cos(a) * r).add(uv[1].scale(Math.sin(a) * r));
        for (Vec3 eye : new Vec3[]{new Vec3(0, 20, 0), new Vec3(30, 0.001, 4)}) {
            double[] h = Gizmo.rayCircle(eye, on.subtract(eye).normalize(), c, axis, r);
            assertEquals(0, h[0], 0.01); // the ring is a 64-gon
            assertEquals(a, h[2], 1e-3);
        }
    }

    @Test
    void controlClusterHoldsStillWhenNoSpotIsInRange() {
        // The whole line is further than CTRL_FAR, then nearer than CTRL_NEAR: standing still must
        // not move the cluster from call to call (it used to hop from side to side of you).
        for (Vec3 eye : new Vec3[]{new Vec3(20, 30, 14), new Vec3(20.3, 9.6, 0.5)}) {
            LineShape l = straight(40);
            l.aimControls(eye, false);
            l.slideControls(10);
            Vec3 was = l.controls(eye)[1];
            for (int k = 0; k < 6; k++) {
                l.aimControls(eye, false);
                l.slideControls(10);
                assertEquals(0, was.distanceTo(l.controls(eye)[1]), 1e-12, "moved on call " + k + " at " + eye);
            }
        }
    }
}
