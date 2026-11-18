package co.fax.wang.shape;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LinePhysicsTest {

    /** Flat ground: every block below y = 0. */
    private static final LinePhysics.Solid FLAT = (x, y, z) -> y < 0;

    private static LineShape tube(Vec3 a, Vec3 b, double r) {
        LineCurve c = new LineCurve(a, b);
        c.nodes.get(0).r = r;
        c.nodes.get(1).r = r;
        LineShape l = new LineShape(c);
        l.profile = LineProfile.CIRCLE;
        return l;
    }

    /** Run until it rests; fails if it never does. */
    private static int settle(LinePhysics p, LinePhysics.Solid world) {
        for (int t = 0; t < 1200; t++) {
            p.tick(world);
            if (p.atRest()) return t;
        }
        return fail("never came to rest; bottom at " + p.bottom());
    }

    @Test
    void aLevelLineDropsStraightDownOntoFlatGround() {
        LinePhysics p = LinePhysics.of(tube(new Vec3(0.5, 20.5, 0.5), new Vec3(16.5, 20.5, 0.5), 1.5));
        settle(p, FLAT);
        assertEquals(0, p.bottom(), 0.1); // lying on the ground, not in it or above it
        assertEquals(0, p.shift().x, 0.3);
        assertEquals(0, p.shift().z, 0.3);
        assertTrue(p.angle() < 0.1, "it had no reason to turn: " + p.angle());
    }

    @Test
    void aLineDroppedOnItsEndFallsOverAndLiesFlat() {
        // Steeply tilted: it lands on its low end, topples, and ends up level along the ground.
        LinePhysics p = LinePhysics.of(tube(new Vec3(0.5, 6.5, 0.5), new Vec3(6.5, 22.5, 0.5), 1));
        settle(p, FLAT);
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = 0; i < p.balls(); i++) {
            lo = Math.min(lo, p.ball(i).y);
            hi = Math.max(hi, p.ball(i).y);
        }
        assertTrue(hi - lo < 0.5, "balls should end at one height, spread was " + (hi - lo));
        assertEquals(0, p.bottom(), 0.15);
        assertTrue(p.angle() > 0.8, "it turned to lie down: " + p.angle());
    }

    @Test
    void aLineAcrossAStepRestsLeaningOnIt() {
        // Ground at y < 0, and a 4-high step for x >= 10: the line lands with one end up on the step.
        LinePhysics.Solid step = (x, y, z) -> y < 0 || (x >= 10 && y < 4);
        LinePhysics p = LinePhysics.of(tube(new Vec3(0.5, 14.5, 0.5), new Vec3(18.5, 14.5, 0.5), 1));
        settle(p, step);
        Vec3 first = p.ball(0), last = p.ball(p.balls() - 1);
        assertTrue(last.y - first.y > 1.5, "the end over the step sits higher: " + first.y + " vs " + last.y);
        for (int i = 0; i < p.balls(); i++) { // nothing has sunk into the ground or the step
            Vec3 b = p.ball(i);
            assertFalse(step.at((int) Math.floor(b.x), (int) Math.floor(b.y), (int) Math.floor(b.z)), "ball " + i + " at " + b);
        }
    }

    @Test
    void aBuriedLinePopsUpBeforeItDrops() {
        LinePhysics p = LinePhysics.of(tube(new Vec3(0.5, -6.5, 0.5), new Vec3(10.5, -6.5, 0.5), 1));
        int rose = p.popUp(FLAT, 64);
        assertTrue(rose >= 6 && rose <= 8, "rose " + rose);
        settle(p, FLAT);
        assertEquals(0, p.bottom(), 0.1);
    }

    @Test
    void theResultTurnsTheLineItself() {
        LineShape l = tube(new Vec3(0.5, 6.5, 0.5), new Vec3(6.5, 22.5, 0.5), 1);
        LinePhysics p = LinePhysics.of(l);
        Vec3 firstBall = p.ball(0);
        settle(p, FLAT);
        LineShape.Pose from = l.pose();
        l.turnFrom(from, p.start, p.axis(), p.angle());
        l.curve.translate(p.shift());
        l.markDirty();
        // The line's start node is where the body's first ball ended up.
        assertEquals(0, l.curve.nodes.get(0).p.distanceTo(p.ball(0)), 1e-6);
        assertTrue(firstBall.distanceTo(p.ball(0)) > 1);
    }
}
