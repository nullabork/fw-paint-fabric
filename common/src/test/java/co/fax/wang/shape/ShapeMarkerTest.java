package co.fax.wang.shape;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shape-tool markers: a straight two-node line with a profile, built by the tube builder. */
class ShapeMarkerTest {

    private static int count(LineShape l) {
        return l.cellKeys().size();
    }

    @Test
    void aBoxIsExactlyTheCellsBetweenItsCorners() {
        LineShape l = LineShape.box(new BlockPos(3, 10, -2), new BlockPos(7, 12, 4));
        assertTrue(l.shape);
        assertEquals(5 * 3 * 7, count(l));
        for (int x = 2; x <= 8; x++) {
            for (int y = 9; y <= 13; y++) {
                for (int z = -3; z <= 5; z++) {
                    boolean in = x >= 3 && x <= 7 && y >= 10 && y <= 12 && z >= -2 && z <= 4;
                    assertEquals(in, l.contains(new BlockPos(x, y, z)), x + " " + y + " " + z);
                }
            }
        }
        assertEquals(3, l.height(), 1e-9);
    }

    @Test
    void aBoxOneBlockEachWayIsOneCell() {
        LineShape l = LineShape.box(new BlockPos(0, 0, 0), new BlockPos(0, 0, 0));
        assertEquals(1, count(l));
        assertTrue(l.contains(new BlockPos(0, 0, 0)));
    }

    @Test
    void aBoxLyingAlongAnyAxisIsExact() {
        for (Direction.Axis ax : Direction.Axis.values()) {
            for (boolean down : new boolean[]{false, true}) {
                LineShape l = LineShape.prism(new BlockPos(1, 2, 3), new BlockPos(4, 7, 9), ax, down);
                assertEquals(4 * 6 * 7, count(l), ax + " " + down);
                assertTrue(l.contains(new BlockPos(1, 2, 3)) && l.contains(new BlockPos(4, 7, 9)));
                assertFalse(l.contains(new BlockPos(5, 7, 9)) || l.contains(new BlockPos(1, 1, 3)));
            }
        }
    }

    @Test
    void aHollowBoxHasWallsOfTheSetThicknessAndCanBeLeftOpen() {
        LineShape l = LineShape.box(new BlockPos(0, 0, 0), new BlockPos(9, 9, 9));
        l.solid = false;
        l.markDirty();
        assertEquals(1000 - 512, count(l), "one-block walls, capped");
        l.wall = 2;
        l.markDirty();
        assertEquals(1000 - 216, count(l), "two-block walls, capped");
        l.capped = false;
        l.markDirty();
        assertEquals(1000 - 6 * 6 * 10, count(l), "two-block walls, open top and bottom");
        assertFalse(l.contains(new BlockPos(5, 0, 5)));
        assertTrue(l.contains(new BlockPos(1, 0, 5)));
    }

    @Test
    void aSphereIsABallAndAHollowOneHasNothingInTheMiddle() {
        LineShape l = LineShape.box(new BlockPos(0, 0, 0), new BlockPos(10, 10, 10));
        l.profile = LineProfile.CIRCLE;
        l.ball = true;
        l.markDirty();
        Vec3 c = new Vec3(5.5, 5.5, 5.5);
        for (int x = -1; x <= 11; x++) {
            for (int y = -1; y <= 11; y++) {
                for (int z = -1; z <= 11; z++) {
                    double d = new Vec3(x + 0.5, y + 0.5, z + 0.5).distanceTo(c);
                    if (d < 4.6) assertTrue(l.contains(new BlockPos(x, y, z)), "inside " + x + " " + y + " " + z);
                    if (d > 6.4) assertFalse(l.contains(new BlockPos(x, y, z)), "outside " + x + " " + y + " " + z);
                }
            }
        }
        l.solid = false;
        l.markDirty();
        assertFalse(l.contains(new BlockPos(5, 5, 5)));
        assertTrue(l.contains(new BlockPos(5, 0, 5)) && l.contains(new BlockPos(0, 5, 5)));
    }

    @Test
    void stretchMakesAnOvalCylinder() {
        LineShape l = LineShape.box(new BlockPos(0, 0, 0), new BlockPos(20, 3, 8)); // 21 wide, 9 deep
        l.profile = LineProfile.CIRCLE;
        l.markDirty();
        assertTrue(l.contains(new BlockPos(0, 1, 4)) && l.contains(new BlockPos(20, 1, 4)));
        assertTrue(l.contains(new BlockPos(10, 1, 0)) && l.contains(new BlockPos(10, 1, 8)));
        assertFalse(l.contains(new BlockPos(0, 1, 0)) || l.contains(new BlockPos(10, 1, 9)));
    }

    @Test
    void anOldRingComesBackAsAnOpenHollowTube() {
        ShapeStore.StoredShape s = new ShapeStore.StoredShape();
        s.kind = "CIRCLE";
        s.center = new int[]{0, 64, 0};
        s.axis = "Y";
        s.rA = 6;
        s.rB = 4;
        s.height = 3;
        LineShape l = ShapeStore.fromRing(s);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                long r = Math.round(Math.hypot(x, z));
                for (int y = 63; y <= 67; y++) {
                    boolean in = r >= 4 && r <= 6 && y >= 64 && y <= 66;
                    assertEquals(in, l.contains(new BlockPos(x, y, z)), x + " " + y + " " + z);
                }
            }
        }
        s.height = -3; // downwards from the centre's layer
        l = ShapeStore.fromRing(s);
        assertTrue(l.contains(new BlockPos(5, 62, 0)) && l.contains(new BlockPos(5, 64, 0)));
        assertFalse(l.contains(new BlockPos(5, 65, 0)));
        assertEquals(64, l.curve.nodes.get(0).p.y - 1, 1e-9, "starts at the ring's base layer");
    }

    @Test
    void anOldBoxComesBackAsABoxStartingAtCornerA() {
        ShapeStore.StoredBox b = new ShapeStore.StoredBox();
        b.cornerA = new int[]{10, 5, 10};
        b.cornerB = new int[]{4, 8, 12};
        b.axis = "X";
        LineShape l = ShapeStore.fromBox(b);
        assertEquals(7 * 4 * 3, count(l));
        assertEquals(11, l.curve.nodes.get(0).p.x, 1e-9, "its start is corner A's side");
        assertEquals(4, l.curve.nodes.get(1).p.x, 1e-9);
        assertEquals(LineGradient.ALONG, l.gradient);
    }

    @Test
    void shapeSettingsAreSaved() {
        LineShape l = LineShape.box(new BlockPos(0, 0, 0), new BlockPos(5, 3, 9));
        l.solid = false;
        l.wall = 2;
        l.capped = false;
        l.ball = true;
        LineShape back = ShapeStore.decodeLine(ShapeStore.encodeLine(l));
        assertTrue(back.shape && back.ball && !back.capped);
        assertEquals(2, back.wall, 1e-9);
        assertEquals(l.stretch, back.stretch, 1e-9);
        // and a plain line stays plain
        LineShape line = ShapeStore.decodeLine(ShapeStore.encodeLine(
                new LineShape(new LineCurve(new Vec3(0, 0, 0), new Vec3(5, 0, 0)))));
        assertFalse(line.shape || line.ball);
        assertTrue(line.capped);
        assertEquals(1, line.wall, 1e-9);
        assertEquals(1, line.stretch, 1e-9);
    }

    @Test
    void theControlsSitJustOffTheSkinOnTheSideYouStand() {
        LineShape l = new LineShape(new LineCurve(new Vec3(0.5, 10.5, 0.5), new Vec3(40.5, 10.5, 0.5)));
        l.profile = LineProfile.CIRCLE;
        l.setAllRadii(4);
        for (Vec3 eye : new Vec3[]{new Vec3(20, 30, 0.5), new Vec3(20, 10.5, 20), new Vec3(20, 10.5, -20)}) {
            l.aimControls(eye, false);
            Vec3 cog = l.controls(eye)[1];
            Vec3 off = new Vec3(0, cog.y - 10.5, cog.z - 0.5);
            Vec3 toEye = new Vec3(0, eye.y - 10.5, eye.z - 0.5).normalize();
            assertEquals(4.5 + LineShape.CTRL_OFF, off.length(), 1e-6, "just off the skin");
            assertEquals(1, off.normalize().dot(toEye), 1e-6, "on the side of " + eye);
        }
    }

    @Test
    void theControlsOfABoxYouStandInFloatInsideIt() {
        LineShape l = LineShape.box(new BlockPos(0, 0, 0), new BlockPos(40, 20, 40));
        Vec3 eye = new Vec3(10, 5, 12);
        l.aimControls(eye, false);
        Vec3 cog = l.controls(eye)[1];
        assertTrue(l.contains(BlockPos.containing(cog)), "inside the box: " + cog);
    }

    @Test
    void theClusterSitsJustOffTheNearestFaceAndFollowsWithoutJumping() {
        // A 9 x 6 x 8 box standing on the ground (y 0..6). Walk all the way round it on the ground,
        // near and far: the cluster is always outside the box on your side, well above the ground,
        // never in your face, and never jumps as you take a step.
        LineShape l = LineShape.box(new BlockPos(0, 0, 0), new BlockPos(8, 5, 7));
        for (double ring : new double[]{0.8, 2, 5, 12, 30}) {
            Vec3 was = null;
            for (int k = 0; k <= 400; k++) {
                double a = k * Math.PI * 2 / 400;
                // a rounded-square path `ring` blocks out from the walls
                double cx = 4.5, cz = 4, hx = 4.5 + ring, hz = 4 + ring;
                double c = Math.cos(a), sn = Math.sin(a), m = Math.max(Math.abs(c) / hx, Math.abs(sn) / hz);
                Vec3 eye = new Vec3(cx + c / m, 1.62, cz + sn / m);
                l.aimControls(eye, false);
                l.slideControls(10);
                Vec3 cog = l.controls(eye)[1];
                String at = "ring " + ring + " step " + k + " cog " + cog;
                assertTrue(cog.y >= 0.9 && cog.y <= 5.1, "off the ground and below the top: " + at);
                boolean outside = cog.x < -0.2 || cog.x > 9.2 || cog.z < -0.2 || cog.z > 8.2;
                assertTrue(outside, "outside the box: " + at);
                assertTrue(cog.distanceTo(eye) >= 0.9, "not in your face: " + at);
                assertTrue(cog.distanceTo(new Vec3(cx, cog.y, cz)) <= eye.distanceTo(new Vec3(cx, eye.y, cz)) + 1.6
                        || ring < 2, "between you and the box: " + at);
                if (was != null) assertTrue(was.distanceTo(cog) < 3.5, "jumped " + was.distanceTo(cog) + ": " + at);
                was = cog;
            }
        }
        // Standing on top of it: the cluster is on the top face, not inside the box under your feet.
        Vec3 onTop = new Vec3(3, 7.62, 3);
        l.aimControls(onTop, false);
        Vec3 cog = l.controls(onTop)[1];
        assertTrue(cog.y > 6.2 && cog.distanceTo(onTop) >= LineShape.CTRL_NEAR - 1e-6, "on the top face: " + cog);
        // A flat disc lying on the ground, seen from above: on its top, not under it.
        LineShape disc = LineShape.box(new BlockPos(0, 0, 0), new BlockPos(20, 0, 20));
        Vec3 over = new Vec3(6, 4, 9);
        disc.aimControls(over, false);
        assertTrue(disc.controls(over)[1].y > 1.2, "above the disc: " + disc.controls(over)[1]);
    }

    @Test
    void digModesLimitWhatBreaks() {
        // A filled 5 x 5 x 5 box at x 0..4, y 0..4, z 0..4.
        LineShape box = LineShape.box(new BlockPos(0, 0, 0), new BlockPos(4, 4, 4));
        java.util.List<LineShape> all = java.util.List.of(box);
        BlockPos in = new BlockPos(2, 2, 2), out = new BlockPos(7, 2, 2);
        Vec3 near = new Vec3(8.5, 2.5, 2.5), far = new Vec3(40.5, 2.5, 2.5);
        double reach = 4.5;
        // Off: nothing is stopped.
        assertNull(ShapeMarkers.digVerdict(all, in, near, reach));
        assertNull(ShapeMarkers.digVerdict(all, out, near, reach));
        // Dig inside: near it only its blocks break; out of reach of it digging is normal.
        box.dig = LineShape.Dig.INSIDE;
        assertNull(ShapeMarkers.digVerdict(all, in, near, reach));
        assertNotNull(ShapeMarkers.digVerdict(all, out, near, reach));
        assertNull(ShapeMarkers.digVerdict(all, new BlockPos(44, 2, 2), far, reach));
        assertTrue(box.cellInReach(near, reach) && !box.cellInReach(far, reach));
        assertTrue(box.cellInReach(new Vec3(9.4, 2.5, 2.5), reach), "reach is measured to the block's face");
        assertFalse(box.cellInReach(new Vec3(9.6, 2.5, 2.5), reach));
        // Dig outside: its blocks never break, everything else does.
        box.dig = LineShape.Dig.OUTSIDE;
        assertNotNull(ShapeMarkers.digVerdict(all, in, near, reach));
        assertNull(ShapeMarkers.digVerdict(all, out, near, reach));
        // Two Dig inside markers side by side: a block in either breaks.
        box.dig = LineShape.Dig.INSIDE;
        LineShape other = LineShape.box(new BlockPos(6, 0, 0), new BlockPos(8, 4, 4));
        other.dig = LineShape.Dig.INSIDE;
        assertNull(ShapeMarkers.digVerdict(java.util.List.of(box, other), out, near, reach));
        assertNotNull(ShapeMarkers.digVerdict(java.util.List.of(box, other), new BlockPos(5, 2, 2), near, reach));
        // A Dig outside marker overlapping a Dig inside one wins where they overlap.
        other.dig = LineShape.Dig.OUTSIDE;
        assertNotNull(ShapeMarkers.digVerdict(java.util.List.of(box, other), out, near, reach));
        // Saved with the marker.
        assertEquals(LineShape.Dig.OUTSIDE, ShapeStore.decodeLine(ShapeStore.encodeLine(other)).dig);
        assertEquals(LineShape.Dig.OFF, ShapeStore.decodeLine(ShapeStore.encodeLine(LineShape.box(in, out))).dig);
    }

    // ---- drawn profiles ---------------------------------------------------------------------

    /** An L drawn on a wall facing Z: three cells up, and a foot beside the bottom one. */
    private static ProfileDrawing ell() {
        ProfileDrawing d = new ProfileDrawing();
        d.start(new BlockPos(100, 50, 7), Direction.Axis.Z);
        d.add(new BlockPos(100, 51, 7));
        d.add(new BlockPos(100, 52, 7));
        d.add(new BlockPos(101, 50, 7));
        return d;
    }

    @Test
    void aDrawnProfileIsSweptOneCellToOneBlock() {
        LineShape l = new LineShape(new LineCurve(new Vec3(0.5, 10.5, 0.5), new Vec3(0.5, 10.5, 10.5)));
        l.profile = LineProfile.CUSTOM;
        l.drawn = ell();
        l.solid = true;
        l.setAllRadii(l.drawn.natural() - 0.5);
        assertEquals(1.5, l.drawn.natural(), 1e-9);
        assertEquals(4 * 11, count(l), "four cells a slice, eleven slices");
        int tall = 0, foot = 0;
        for (int x = -3; x <= 3; x++) {
            for (int y = 7; y <= 13; y++) {
                if (!l.contains(new BlockPos(x, y, 5))) continue;
                if (y == 9) foot++;
                else tall++;
            }
        }
        assertEquals(2, foot, "the bottom row is two wide");
        assertEquals(2, tall, "the two above it are one wide");
        // The same way round as it was drawn: the tall part on the low-x side, the foot out to +x,
        // whichever way the line runs through the picture.
        LineShape back = new LineShape(new LineCurve(new Vec3(0.5, 10.5, 10.5), new Vec3(0.5, 10.5, 0.5)));
        back.profile = LineProfile.CUSTOM;
        back.drawn = ell();
        back.solid = true;
        back.setAllRadii(back.drawn.natural() - 0.5);
        for (LineShape s : new LineShape[]{l, back}) {
            int tallX = Integer.MIN_VALUE, footX = Integer.MIN_VALUE, topY = Integer.MIN_VALUE, lowY = Integer.MAX_VALUE;
            for (int x = -3; x <= 3; x++) {
                for (int y = 7; y <= 13; y++) {
                    if (!s.contains(new BlockPos(x, y, 5))) continue;
                    topY = Math.max(topY, y);
                    lowY = Math.min(lowY, y);
                }
            }
            for (int x = -3; x <= 3; x++) {
                if (s.contains(new BlockPos(x, topY, 5))) tallX = x;
                if (s.contains(new BlockPos(x, lowY, 5))) footX = Math.max(footX, x);
            }
            assertEquals(3, topY - lowY + 1, "three tall");
            assertEquals(tallX + 1, footX, "the foot sticks out to +x at the bottom");
        }
    }

    @Test
    void aPictureOnTheFloorStandsUpWithItsFarSideOnTop() {
        // Drawn on the floor facing north (-Z): a stem three cells away from the drawer, and a
        // foot to the right (east) of its near end. Swept along a line running east it stands up:
        // the stem's far end on top, the foot at the bottom.
        ProfileDrawing d = new ProfileDrawing();
        d.start(new BlockPos(100, 50, 7), Direction.Axis.Y, new Vec3(0, -0.8, -0.6));
        d.add(new BlockPos(100, 50, 6));
        d.add(new BlockPos(100, 50, 5));
        d.add(new BlockPos(101, 50, 7));
        LineShape l = new LineShape(new LineCurve(new Vec3(0.5, 10.5, 0.5), new Vec3(10.5, 10.5, 0.5)));
        l.profile = LineProfile.CUSTOM;
        l.drawn = d;
        l.solid = true;
        l.setAllRadii(d.natural() - 0.5);
        assertEquals(4 * 11, count(l));
        int top = 0, bottom = 0;
        for (int z = -3; z <= 3; z++) {
            if (l.contains(new BlockPos(5, 11, z))) top++;
            if (l.contains(new BlockPos(5, 9, z))) bottom++;
        }
        assertEquals(1, top, "the stem's far end is on top");
        assertEquals(2, bottom, "the foot is at the bottom");
    }

    @Test
    void aDrawnProfileScalesWithTheRadiusAndIsSaved() {
        LineShape l = new LineShape(new LineCurve(new Vec3(0.5, 10.5, 0.5), new Vec3(0.5, 10.5, 10.5)));
        l.profile = LineProfile.CUSTOM;
        l.drawn = ell();
        l.solid = true;
        l.setAllRadii(2 * l.drawn.natural() - 0.5); // twice the size: each drawn cell is 2 x 2 blocks
        assertEquals(16 * 11, count(l));
        LineShape back = ShapeStore.decodeLine(ShapeStore.encodeLine(l));
        assertEquals(LineProfile.CUSTOM, back.profile);
        assertEquals(4, back.drawn.size());
        assertTrue(back.drawn.has(new BlockPos(101, 50, 7)) && !back.drawn.has(new BlockPos(101, 51, 7)));
        assertEquals(16 * 11, count(back));
    }

    @Test
    void anEmptyDrawnProfileIsJustTheLine() {
        LineShape l = new LineShape(new LineCurve(new Vec3(0.5, 10.5, 0.5), new Vec3(0.5, 10.5, 10.5)));
        l.profile = LineProfile.CUSTOM;
        assertEquals(11, count(l));
    }

    @Test
    void sketchingFindsTheCellOnThePicturesPlane() {
        ProfileDrawing d = ell();
        BlockPos c = d.cellOnPlane(new Vec3(103.5, 54.5, 20), new Vec3(0, 0, -1));
        assertEquals(new BlockPos(103, 54, 7), c);
        assertTrue(d.add(c) && d.has(c));
        assertFalse(d.add(new BlockPos(103, 54, 8)), "off the plane");
        assertTrue(d.remove(c) && !d.has(c));
    }
}
