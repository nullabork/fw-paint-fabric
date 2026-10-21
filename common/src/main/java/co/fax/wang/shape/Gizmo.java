package co.fax.wang.shape;

import co.fax.wang.ui.Ui;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

/**
 * The in-world control kit every marker draws with: camera-relative emitters for cubes, spheres,
 * ribbons, dashed leaders, camera-facing billboards and tiny pixel glyphs, the ray tests that pick
 * them, and the control colours — one colour per verb (what dragging the control does), taken
 * from {@link Ui}: Start, End, Move, Size, Turn, Bend, Options.
 *
 * <p>Controls are drawn twice (see {@link #twoPass}): depth-tested, then a faint on-top ghost
 * through the see-through pipeline so buried controls stay readable and grabbable.
 */
final class Gizmo {

    private Gizmo() {}

    // ---- colours by verb -------------------------------------------------------------------

    static final int START = alpha(Ui.START, 0xE0);
    static final int END = alpha(Ui.END, 0xE0);
    static final int MOVE = alpha(Ui.MOVE, 0xD0);
    static final int SIZE = alpha(Ui.SIZE, 0xD0);
    static final int TURN = alpha(Ui.TURN, 0xD0);
    static final int BEND = alpha(Ui.BEND, 0xE0);
    static final int BEND_PARKED = alpha(Ui.BEND, 0x80);
    static final int OPTIONS = alpha(Ui.OPTIONS, 0xE0);
    /** Any control while the crosshair is on it. */
    static final int AIMED = 0xFFFFFFFF;
    /** The dark icon floated on a control cube. */
    static final int ICON = 0xFF202020;

    /** {@code argb} with its alpha byte replaced. */
    static int alpha(int argb, int a) {
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** Halfway to white, same alpha (the aimed read of a filled control). */
    static int lighten(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return (argb & 0xFF000000) | ((r + 255) / 2 << 16) | ((g + 255) / 2 << 8) | ((b + 255) / 2);
    }

    // ---- icons (7×7 unless noted, rows top to bottom; 'X' solid) ---------------------------

    static final String[] MOVE_ICON = {
            "...X...", "..XXX..", ".X.X.X.", "XXXXXXX", ".X.X.X.", "..XXX..", "...X..."};
    static final String[] RESIZE_ICON = {
            "XXX....", "XX.....", "X.X....", "...X...", "....X.X", ".....XX", "....XXX"};
    static final String[] ROTATE_ICON = {
            "..XXX.X", ".X...XX", "X...XXX", "X......", "X.....X", ".X...X.", "..XXX.."};
    static final String[] COG_ICON = {
            "..X.X..", ".XXXXX.", "XXX.XXX", ".X...X.", "XXX.XXX", ".XXXXX.", "..X.X.."};

    /** Size of the small control cubes (size / twist / move / cog), half-extent in blocks. */
    static final double CTRL_HALF = 0.25;

    // ---- emitters --------------------------------------------------------------------------

    /**
     * Where geometry goes: the depth-tested pass, or the on-top ghost pass. The ghost pass rides
     * the see-through text pipeline, so its vertices carry the white-texel UV, every quad is
     * emitted in both windings (that pipeline culls back faces), and alpha is scaled down.
     */
    record Em(PoseStack.Pose pose, VertexConsumer vc, Vec3 cam, boolean ghost) {}

    private static final float GHOST_ALPHA = 0.4f;

    /** Submit {@code draw} twice: depth-tested, then as the faint see-through ghost. */
    static void twoPass(SubmitNodeCollector col, PoseStack ps, Vec3 cam, Consumer<Em> draw) {
        col.submitCustomGeometry(ps, RenderTypes.debugFilledBox(), (pose, vc) -> draw.accept(new Em(pose, vc, cam, false)));
        col.submitCustomGeometry(ps, WorldDraw.seeThrough(), (pose, vc) -> draw.accept(new Em(pose, vc, cam, true)));
    }

    /**
     * Submit {@code draw} once, on top of everything (toolbars). Billboards draw at full alpha;
     * {@link #quad}-based shapes get the ghost alpha.
     */
    static void onTop(SubmitNodeCollector col, PoseStack ps, Vec3 cam, Consumer<Em> draw) {
        col.submitCustomGeometry(ps, WorldDraw.seeThrough(), (pose, vc) -> draw.accept(new Em(pose, vc, cam, true)));
    }

    static void v(Em e, Vec3 p, int argb) {
        VertexConsumer x = e.vc().addVertex(e.pose(),
                (float) (p.x - e.cam().x), (float) (p.y - e.cam().y), (float) (p.z - e.cam().z));
        if (e.ghost()) x = x.setUv(0.5f, 0.5f);
        x.setColor(argb);
    }

    static void quad(Em e, int argb, Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        if (e.ghost()) {
            int alpha = Math.round(((argb >>> 24) & 0xFF) * GHOST_ALPHA);
            argb = (alpha << 24) | (argb & 0xFFFFFF);
        }
        v(e, a, argb);
        v(e, b, argb);
        v(e, c, argb);
        v(e, d, argb);
        if (e.ghost()) {
            v(e, d, argb);
            v(e, c, argb);
            v(e, b, argb);
            v(e, a, argb);
        }
    }

    /** A thin crossed ribbon a→b (reads as a line from any angle). */
    static void segmentRibbon(Em e, Vec3 a, Vec3 b, double w, int argb) {
        Vec3 d = b.subtract(a);
        if (d.lengthSqr() < 1e-12) return;
        d = d.normalize();
        Vec3 e1 = d.cross(Math.abs(d.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize().scale(w);
        Vec3 e2 = d.cross(e1).normalize().scale(w);
        quad(e, argb, a.subtract(e1), b.subtract(e1), b.add(e1), a.add(e1));
        quad(e, argb, a.subtract(e2), b.subtract(e2), b.add(e2), a.add(e2));
    }

    /** A dashed ribbon a→b: {@code dash}-long pieces with {@code gap} between them. */
    static void dashed(Em e, Vec3 a, Vec3 b, double w, double dash, double gap, int argb) {
        double len = a.distanceTo(b);
        if (len < 1e-6) return;
        Vec3 dir = b.subtract(a).scale(1 / len);
        for (double s = 0; s < len; s += dash + gap) {
            segmentRibbon(e, a.add(dir.scale(s)), a.add(dir.scale(Math.min(len, s + dash))), w, argb);
        }
    }

    static void cube(Em e, Vec3 c, double h, int argb) {
        double x0 = c.x - h, x1 = c.x + h, y0 = c.y - h, y1 = c.y + h, z0 = c.z - h, z1 = c.z + h;
        quad(e, argb, new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x1, y0, z1), new Vec3(x0, y0, z1));
        quad(e, argb, new Vec3(x0, y1, z0), new Vec3(x0, y1, z1), new Vec3(x1, y1, z1), new Vec3(x1, y1, z0));
        quad(e, argb, new Vec3(x0, y0, z0), new Vec3(x0, y1, z0), new Vec3(x1, y1, z0), new Vec3(x1, y0, z0));
        quad(e, argb, new Vec3(x0, y0, z1), new Vec3(x1, y0, z1), new Vec3(x1, y1, z1), new Vec3(x0, y1, z1));
        quad(e, argb, new Vec3(x0, y0, z0), new Vec3(x0, y0, z1), new Vec3(x0, y1, z1), new Vec3(x0, y1, z0));
        quad(e, argb, new Vec3(x1, y0, z0), new Vec3(x1, y1, z0), new Vec3(x1, y1, z1), new Vec3(x1, y0, z1));
    }

    /** Low-poly UV sphere (8 slices × 6 stacks). */
    static void sphere(Em e, Vec3 c, double r, int argb) {
        int slices = 8, stacks = 6;
        for (int i = 0; i < stacks; i++) {
            double p0 = Math.PI * i / stacks - Math.PI / 2, p1 = Math.PI * (i + 1) / stacks - Math.PI / 2;
            for (int j = 0; j < slices; j++) {
                double a0 = 2 * Math.PI * j / slices, a1 = 2 * Math.PI * (j + 1) / slices;
                quad(e, argb, sp(c, r, p0, a0), sp(c, r, p0, a1), sp(c, r, p1, a1), sp(c, r, p1, a0));
            }
        }
    }

    private static Vec3 sp(Vec3 c, double r, double lat, double lon) {
        double cl = Math.cos(lat);
        return new Vec3(c.x + r * cl * Math.cos(lon), c.y + r * Math.sin(lat), c.z + r * cl * Math.sin(lon));
    }

    /**
     * A control cube plus a small dark icon facing the camera, floated just in front of the cube
     * so it reads on whichever side you see.
     */
    static void controlCube(Em e, Vec3 c, double half, int argb, String[] icon, Vec3[] ax) {
        cube(e, c, half, argb);
        // Toward the eye (not along the view axis), so it stays centred on off-centre cubes.
        Vec3 toEye = e.cam().subtract(c);
        Vec3 front = toEye.lengthSqr() < 1e-6 ? c : c.add(toEye.normalize().scale(half * 1.8));
        double px = half * 2 * 0.8 / icon[0].length(); // icon spans 80% of the cube
        int cols = icon[0].length();
        for (int ry = 0; ry < icon.length; ry++) {
            for (int rx = 0; rx < cols; rx++) {
                if (icon[ry].charAt(rx) != 'X') continue;
                Vec3 p = front.add(ax[0].scale((rx - (cols - 1) / 2.0) * px))
                        .add(ax[1].scale(((icon.length - 1) / 2.0 - ry) * px));
                Vec3 r = ax[0].scale(px / 2), u = ax[1].scale(px / 2);
                quad(e, ICON, p.subtract(r).subtract(u), p.add(r).subtract(u), p.add(r).add(u), p.subtract(r).add(u));
            }
        }
    }

    /** The grey cog (options) cube of a marker. */
    static void cog(Em e, Vec3 c, boolean aimed, Vec3[] ax) {
        controlCube(e, c, CTRL_HALF, aimed ? AIMED : OPTIONS, COG_ICON, ax);
    }

    // ---- billboards (toolbar buttons; always the on-top pass) -------------------------------

    static void vt(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, Vec3 p, int argb) {
        vc.addVertex(pose, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z))
                .setUv(0.5f, 0.5f).setColor(argb);
    }

    static void billboardQuad(Em e, Vec3 c, Vec3[] ax, double hw, double hh, int argb) {
        Vec3 r = ax[0].scale(hw), u = ax[1].scale(hh);
        vt(e.vc(), e.pose(), e.cam(), c.subtract(r).subtract(u), argb);
        vt(e.vc(), e.pose(), e.cam(), c.add(r).subtract(u), argb);
        vt(e.vc(), e.pose(), e.cam(), c.add(r).add(u), argb);
        vt(e.vc(), e.pose(), e.cam(), c.subtract(r).add(u), argb);
    }

    /** Filled outline as a fan from the centre: (u, v) → (screen up, screen right). */
    static void fan(Em e, Vec3 c, Vec3[] ax, double[][] pts, int argb) {
        for (int i = 0; i < pts.length; i++) {
            double[] a = pts[i], b = pts[(i + 1) % pts.length];
            Vec3 pa = c.add(ax[1].scale(a[0])).add(ax[0].scale(a[1]));
            Vec3 pb = c.add(ax[1].scale(b[0])).add(ax[0].scale(b[1]));
            // Wound counter-clockwise as seen from the camera (the see-through pipeline culls).
            vt(e.vc(), e.pose(), e.cam(), c, argb);
            vt(e.vc(), e.pose(), e.cam(), pb, argb);
            vt(e.vc(), e.pose(), e.cam(), pa, argb);
            vt(e.vc(), e.pose(), e.cam(), pa, argb);
        }
    }

    /**
     * A tiny pixel-art glyph, centred, each pixel a billboard square: 'X' is solid, '1'…'4' are
     * faint → full alpha (gradient icons), anything else is empty.
     */
    static void pixelGlyph(Em e, Vec3 c, Vec3[] ax, String[] rows, double px, int argb) {
        int cols = rows[0].length();
        for (int ry = 0; ry < rows.length; ry++) {
            for (int rx = 0; rx < cols; rx++) {
                char ch = rows[ry].charAt(rx);
                int a = ch == 'X' || ch == '4' ? 0xFF : ch == '3' ? 0xB0 : ch == '2' ? 0x70 : ch == '1' ? 0x38 : 0;
                if (a == 0) continue;
                int shade = (((argb >>> 24) * a / 0xFF) << 24) | (argb & 0xFFFFFF);
                double ox = (rx - (cols - 1) / 2.0) * px, oy = ((rows.length - 1) / 2.0 - ry) * px;
                billboardQuad(e, c.add(ax[0].scale(ox)).add(ax[1].scale(oy)), ax, px / 2, px / 2, shade);
            }
        }
    }

    /** A flat ring (annulus) facing the camera. */
    static void ring(Em e, Vec3 c, Vec3[] ax, double ro, double ri, int argb) {
        int n = 24;
        for (int i = 0; i < n; i++) {
            double a0 = 2 * Math.PI * i / n, a1 = 2 * Math.PI * (i + 1) / n;
            vt(e.vc(), e.pose(), e.cam(), ringPt(c, ax, ro, a0), argb);
            vt(e.vc(), e.pose(), e.cam(), ringPt(c, ax, ro, a1), argb);
            vt(e.vc(), e.pose(), e.cam(), ringPt(c, ax, ri, a1), argb);
            vt(e.vc(), e.pose(), e.cam(), ringPt(c, ax, ri, a0), argb);
        }
    }

    private static Vec3 ringPt(Vec3 c, Vec3[] ax, double r, double a) {
        return c.add(ax[0].scale(Math.cos(a) * r)).add(ax[1].scale(Math.sin(a) * r));
    }

    // ---- picking ---------------------------------------------------------------------------

    /** Camera right/up axes for billboards: {right, up, forward}. */
    static Vec3[] cameraAxes(Minecraft mc) {
        Vec3 f = Raycast.look(mc);
        Vec3 right = f.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : right.normalize();
        Vec3 up = right.cross(f).normalize();
        return new Vec3[]{right, up, f};
    }

    /** Ray distance to a sphere, or -1. */
    static double raySphere(Vec3 o, Vec3 d, Vec3 c, double r) {
        Vec3 oc = c.subtract(o);
        double t = oc.dot(d);
        if (t < 0) return -1;
        double d2 = oc.lengthSqr() - t * t;
        return d2 <= r * r ? t : -1;
    }

    /** Ray distance to an axis-aligned cube of half-size {@code h}, or -1. */
    static double rayCube(Vec3 o, Vec3 d, Vec3 c, double h) {
        return ShapeMath.rayBoxIntersect(o.x, o.y, o.z, d.x, d.y, d.z,
                c.x - h, c.y - h, c.z - h, c.x + h, c.y + h, c.z + h);
    }

    /** Ray distance to a camera-facing rectangle (half-extents hw × hh), or -1. */
    static double rayBillboard(Vec3 o, Vec3 d, Vec3 c, Vec3[] cam, double hw, double hh) {
        Vec3 n = cam[2];
        double den = d.dot(n);
        if (Math.abs(den) < 1e-6) return -1;
        double t = c.subtract(o).dot(n) / den;
        if (t < 0) return -1;
        Vec3 p = o.add(d.scale(t)).subtract(c);
        return Math.abs(p.dot(cam[0])) <= hw && Math.abs(p.dot(cam[1])) <= hh ? t : -1;
    }

    /**
     * Where the view ray crosses the plane through {@code anchor} perpendicular to eye → anchor,
     * or null when parallel / behind (how free drags follow the crosshair).
     */
    static Vec3 viewPlaneHit(Vec3 eye, Vec3 dir, Vec3 anchor) {
        Vec3 normal = anchor.subtract(eye);
        if (normal.lengthSqr() < 1e-6) return null;
        normal = normal.normalize();
        double den = dir.dot(normal);
        if (den < 1e-3) return null; // looking away from the point
        double t = anchor.subtract(eye).dot(normal) / den;
        if (t < 0 || t > Raycast.REACH) return null;
        return eye.add(dir.scale(t));
    }

    /** The point on the world axis line through {@code origin} nearest the view ray. */
    static Vec3 onAxis(Vec3 eye, Vec3 dir, Vec3 origin, net.minecraft.core.Direction.Axis axis) {
        Vec3 u = axisUnit(axis);
        Vec3 a = origin.subtract(u.scale(Raycast.REACH)), b = origin.add(u.scale(Raycast.REACH));
        double s = LineCurve.segmentRayClosest(a, b, eye, dir)[0];
        return a.add(b.subtract(a).scale(s));
    }

    static Vec3 axisUnit(net.minecraft.core.Direction.Axis a) {
        return switch (a) {
            case X -> new Vec3(1, 0, 0);
            case Y -> new Vec3(0, 1, 0);
            case Z -> new Vec3(0, 0, 1);
        };
    }

    /** The world axis a drag offset mostly moves along (Alt axis lock picks it once). */
    static net.minecraft.core.Direction.Axis dominantAxis(Vec3 d) {
        double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
        return ax >= ay && ax >= az ? net.minecraft.core.Direction.Axis.X
                : ay >= az ? net.minecraft.core.Direction.Axis.Y : net.minecraft.core.Direction.Axis.Z;
    }

    /** Axis-lock guide colour: X red, Y green, Z blue. */
    static int axisColor(net.minecraft.core.Direction.Axis a) {
        return switch (a) {
            case X -> 0xE0FF5555;
            case Y -> 0xE055FF55;
            case Z -> 0xE05599FF;
        };
    }
}
