package co.fax.wang.shape;

import co.fax.wang.Gradient;
import co.fax.wang.PlacementMode;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The line marker (Marker line mode): creation, editing and rendering of {@link LineShape}s.
 * Behaviour is specified in docs/design/line-marker.md. In short:
 *
 * <ul>
 *   <li>Marker line mode: left-click the start (aimed surface point, or 16 blocks out at the
 *       sky), left-click the end. Right-click cancels a half-made line.</li>
 *   <li>Any mode with the tool in hand: drag nodes (cubes) and curve handles (spheres) on the
 *       plane through the point facing the eye (rebuilt each tick); scroll while dragging
 *       pushes the point further / nearer. Handles snap onto their node when dragged close; Alt
 *       mirrors the opposite handle. Double-click the line to insert a node; middle-click a
 *       middle node to remove it, anything else on the line to delete the line.</li>
 *   <li>The selected line shows floating buttons (profile + shell/solid), and with a profile its
 *       size and twist nodes at each end plus a move node at its side.</li>
 * </ul>
 *
 * State lives here; {@link ShapeMarkers} forwards tick/render/scroll and owns persistence.
 */
public final class LineMarkers {

    private LineMarkers() {}

    // ---- visuals --------------------------------------------------------------------------
    private static final int LINE = 0xFFFFB830;
    private static final int LINE_SELECTED = 0xFFFFFFFF;
    private static final int PREVIEW = 0xC0FFB830;
    private static final int NODE_START = 0xE040E0D0;
    private static final int NODE_END = 0xE0FFB000;
    private static final int NODE_MID = 0xE0FFFFFF;
    private static final int AIMED = 0xFFFFFFFF;
    private static final int HANDLE = 0xE0FF55FF;
    private static final int HANDLE_PARKED = 0x80FF55FF;
    private static final int STALK = 0xC0FF55FF;
    private static final int SIZE = 0xD055FF55;
    private static final int TWIST = 0xD05599FF;
    private static final int MOVE = 0xD0E0E0E0;
    private static final int TUBE_FILL = 0x33FFA000;
    private static final int TUBE_EDGE = 0xD8FFB830;
    private static final int TUBE_EDGE_SELECTED = 0xF0FFFFFF;
    private static final int BTN_BG = 0xC0202020;
    private static final int BTN_HOVER = 0xE0505050;
    private static final int BTN_ACTIVE = 0xE0FFB830;
    private static final int ICON = 0xFFFFFFFF;
    private static final int ICON_ON_ACTIVE = 0xFF202020;

    private static final double NODE_HALF = 0.15;   // main node cube half-size
    private static final double HANDLE_R = 0.12;    // curve handle sphere radius
    private static final double PICK = 0.3;         // grab radius around nodes and handles
    private static final double BODY_PICK = 0.35;   // ray-to-curve distance that targets a line
    private static final double SNAP = 0.35;        // handle within this of its node → zero
    private static final double PARK = 0.6;         // where a zero handle is shown/grabbed
    private static final double CTRL_HALF = 0.25;   // size / twist / move node half-size
    private static final double BTN = 0.8;          // button size (blocks)
    private static final double BTN_GAP = 0.15;
    private static final double DEFAULT_DIST = 16;  // sky-aimed points land this far out
    private static final long DOUBLE_CLICK_MS = 350;

    private enum Kind { NODE, HANDLE_IN, HANDLE_OUT, SIZE_START, SIZE_END, TWIST_START, TWIST_END, MOVE, BUTTON, BODY }

    private record Aim(LineShape line, Kind kind, int index, double dist) {}

    /** Floating buttons, left to right. */
    private static final LineProfile[] BUTTON_PROFILES = {
            LineProfile.CIRCLE, LineProfile.SQUARE, LineProfile.TRIANGLE, LineProfile.STAR};
    private static final int BUTTON_COUNT = BUTTON_PROFILES.length + 1; // + shell/solid

    static final List<LineShape> lines = new ArrayList<>();
    private static LineShape selected;
    private static Vec3 pendingStart;
    private static Aim aimed;

    // Drag state: the view plane fixed at the click.
    private static LineShape dragLine;
    private static Kind dragKind;
    private static int dragIndex;
    private static Vec3 grabOffset; // control position minus the aim point at the grab
    private static long lastBodyClickMs;
    private static LineShape lastBodyLine;
    private static long frame;

    // ---- queries ------------------------------------------------------------------------------

    /** True while a line control/button is aimed or a drag is live: clicks belong to lines. */
    static boolean controlAimed() {
        return dragLine != null || (aimed != null && aimed.kind != Kind.BODY);
    }

    /** True while anything of a line (body included) is under the crosshair or being dragged. */
    static boolean anythingAimed() {
        return dragLine != null || aimed != null;
    }

    /** Dev bench hook ({@code DebugRender}): add a line and select it, without saving. */
    public static void debugAdd(LineShape l) {
        lines.add(l);
        selected = l;
    }

    static void clear() {
        lines.clear();
        selected = null;
        pendingStart = null;
        aimed = null;
        dragLine = null;
    }

    static void resetTransient() {
        selected = null;
        pendingStart = null;
        aimed = null;
        dragLine = null;
    }

    // ---- tick ---------------------------------------------------------------------------------

    /**
     * Input for lines. Edges are passed in so ShapeMarkers keeps one source of truth; returns true
     * when a line consumed this tick's clicks (ShapeMarkers then skips its own handling).
     */
    static boolean tick(Minecraft mc, PlacementMode pm, boolean handles,
                        boolean attack, boolean attackEdge, boolean useEdge, boolean pickEdge) {
        boolean create = pm == PlacementMode.MARKER_LINE;
        if (!create) pendingStart = null;
        if (!handles) {
            aimed = null;
            dragLine = null;
            return false;
        }
        updateAim(mc);
        if (aimed != null && dragLine == null) selected = aimed.line;

        boolean saveNeeded = false;
        boolean consumed = false;

        if (dragLine != null) {
            if (attack) {
                applyDrag(mc);
            } else {
                dragLine = null;
                saveNeeded = true;
            }
            consumed = true;
        }

        if (attackEdge && dragLine == null) {
            // A bare line body only takes clicks in Marker line mode (double-click to add a
            // node) — elsewhere it mustn't swallow clicks meant for markers behind it.
            if (aimed != null && (aimed.kind != Kind.BODY || create)) {
                consumed = true;
                switch (aimed.kind) {
                    case BUTTON -> {
                        pressButton(aimed.line, aimed.index);
                        saveNeeded = true;
                    }
                    case BODY -> {
                        long now = System.currentTimeMillis();
                        if (lastBodyLine == aimed.line && now - lastBodyClickMs < DOUBLE_CLICK_MS) {
                            insertNodeAtAim(mc, aimed.line);
                            lastBodyLine = null;
                            saveNeeded = true;
                        } else {
                            lastBodyLine = aimed.line;
                            lastBodyClickMs = now;
                        }
                    }
                    default -> beginDrag(mc, aimed);
                }
            } else if (create) {
                consumed = true;
                creationClick(mc);
            }
        }
        if (useEdge && create && pendingStart != null) {
            pendingStart = null;
            overlay(mc, "FW Paint — line cancelled");
            consumed = true;
        }
        if (pickEdge && aimed != null) {
            consumed = true;
            LineShape l = aimed.line;
            int last = l.curve.nodes.size() - 1;
            if (aimed.kind == Kind.NODE && aimed.index > 0 && aimed.index < last) {
                l.curve.nodes.remove(aimed.index);
                l.markDirty();
            } else {
                lines.remove(l);
                if (selected == l) selected = null;
                overlay(mc, "FW Paint — line removed");
            }
            aimed = null;
            saveNeeded = true;
        }
        if (saveNeeded) ShapeMarkers.saveLines();
        return consumed || (create && attackEdge);
    }

    /** Mouse wheel while dragging: push the dragged point a block further from / nearer to you. */
    static boolean onScroll(double deltaY) {
        if (dragLine == null) return false;
        Minecraft mc = Minecraft.getInstance();
        Vec3 anchor = controlPos(dragLine, dragKind, dragIndex);
        Vec3 away = anchor.subtract(Raycast.eye(mc));
        if (away.lengthSqr() < 1e-6) return true;
        Vec3 target = anchor.add(away.normalize().scale(deltaY > 0 ? 1 : -1));
        if (target.distanceTo(Raycast.eye(mc)) < 1) return true; // don't pull it through your head
        applyTarget(dragLine, target);
        return true;
    }

    private static void creationClick(Minecraft mc) {
        Vec3 p = aimPoint(mc);
        if (pendingStart == null) {
            pendingStart = p;
            overlay(mc, "FW Paint — line: click the end point");
            return;
        }
        if (p.distanceToSqr(pendingStart) < 0.01) return;
        LineShape l = new LineShape(new LineCurve(pendingStart, p));
        lines.add(l);
        selected = l;
        pendingStart = null;
        ShapeMarkers.saveLines();
        overlay(mc, "FW Paint — line set: drag nodes, double-click to add one, pick a shape above it");
    }

    /** The exact aimed surface point, or {@link #DEFAULT_DIST} out along the view at the sky. */
    private static Vec3 aimPoint(Minecraft mc) {
        BlockHitResult hit = Raycast.aimedBlock(mc);
        if (hit != null) return hit.getLocation();
        return Raycast.eye(mc).add(Raycast.look(mc).scale(DEFAULT_DIST));
    }

    private static void pressButton(LineShape l, int index) {
        if (index < BUTTON_PROFILES.length) {
            LineProfile p = BUTTON_PROFILES[index];
            l.profile = l.profile == p ? LineProfile.NONE : p; // clicking the active one removes it
        } else {
            l.solid = !l.solid;
        }
        l.markDirty();
    }

    private static void insertNodeAtAim(Minecraft mc, LineShape l) {
        List<LineCurve.Sample> ss = l.samples();
        LineCurve.RayHit hit = LineCurve.closestToRay(ss, Raycast.eye(mc), Raycast.look(mc));
        if (hit == null) return;
        LineCurve.Sample a = ss.get(hit.sampleIndex()), b = ss.get(hit.sampleIndex() + 1);
        int seg = b.seg();
        double ta = a.seg() == seg ? a.t() : 0;
        double t = ta + (b.t() - ta) * hit.frac();
        if (t <= 1e-3 || t >= 1 - 1e-3) return; // on an existing node already
        l.curve.insertNode(seg, t);
        l.markDirty();
    }

    // ---- drag ---------------------------------------------------------------------------------

    private static void beginDrag(Minecraft mc, Aim a) {
        Vec3 target = controlPos(a.line, a.kind, a.index);
        if (target == null) return;
        dragLine = a.line;
        dragKind = a.kind;
        dragIndex = a.index;
        Vec3 hit = planeHit(mc, target);
        grabOffset = hit == null ? Vec3.ZERO : target.subtract(hit);
    }

    /**
     * Follow the aim: the drag plane passes through the control's current position, facing the
     * eye (perpendicular to eye → point). Rebuilt every tick, so turning on the spot doesn't tilt
     * it, but walking around the point does.
     */
    private static void applyDrag(Minecraft mc) {
        Vec3 hit = planeHit(mc, controlPos(dragLine, dragKind, dragIndex));
        if (hit == null) return;
        applyTarget(dragLine, hit.add(grabOffset));
    }

    /** Move the dragged control to {@code target} (each kind interprets it its own way). */
    private static void applyTarget(LineShape l, Vec3 target) {
        List<LineCurve.Node> ns = l.curve.nodes;
        switch (dragKind) {
            case NODE -> ns.get(dragIndex).p = target;
            case HANDLE_IN, HANDLE_OUT -> {
                LineCurve.Node n = ns.get(dragIndex);
                Vec3 h = target.subtract(n.p);
                if (h.length() < SNAP) h = Vec3.ZERO;
                boolean in = dragKind == Kind.HANDLE_IN;
                if (in) n.in = h;
                else n.out = h;
                if (altDown()) {
                    if (in) n.out = h.scale(-1);
                    else n.in = h.scale(-1);
                }
            }
            case SIZE_START, SIZE_END -> {
                boolean end = dragKind == Kind.SIZE_END;
                LineCurve.Sample s = endSample(l, end);
                Vec3 d = target.subtract(s.pos());
                Vec3 inPlane = d.subtract(s.tan().scale(d.dot(s.tan())));
                double r = Math.max(0.5, Math.min(64, inPlane.length() - 0.5));
                if (end) l.r1 = r;
                else l.r0 = r;
            }
            case TWIST_START, TWIST_END -> {
                boolean end = dragKind == Kind.TWIST_END;
                LineCurve.Sample s = endSample(l, end);
                Vec3 d = target.subtract(s.pos());
                // The twist node sits a quarter turn (+b') from the profile's +n' direction.
                double angle = Math.atan2(d.dot(s.b()), d.dot(s.n())) - Math.PI / 2;
                if (end) l.tw1 = angle;
                else l.tw0 = angle;
            }
            case MOVE -> l.curve.translate(target.subtract(l.moveNode()));
            default -> { }
        }
        l.markDirty();
    }

    private static LineCurve.Sample endSample(LineShape l, boolean end) {
        List<LineCurve.Sample> ss = l.samples();
        return ss.get(end ? ss.size() - 1 : 0);
    }

    /**
     * Where the view ray crosses the plane through {@code anchor} perpendicular to eye → anchor,
     * or null when parallel / behind.
     */
    private static Vec3 planeHit(Minecraft mc, Vec3 anchor) {
        Vec3 eye = Raycast.eye(mc), dir = Raycast.look(mc);
        Vec3 normal = anchor.subtract(eye);
        if (normal.lengthSqr() < 1e-6) return null;
        normal = normal.normalize();
        double den = dir.dot(normal);
        if (den < 1e-3) return null; // looking away from the point
        double t = anchor.subtract(eye).dot(normal) / den;
        if (t < 0 || t > Raycast.REACH) return null;
        return eye.add(dir.scale(t));
    }

    private static boolean altDown() {
        return InputConstants.isKeyDown(InputConstants.KEY_LALT) || InputConstants.isKeyDown(InputConstants.KEY_RALT);
    }

    // ---- aiming -------------------------------------------------------------------------------

    /** World position of a control (handles: their drawn position, parked ones included). */
    private static Vec3 controlPos(LineShape l, Kind kind, int index) {
        List<LineCurve.Node> ns = l.curve.nodes;
        return switch (kind) {
            case NODE -> ns.get(index).p;
            case HANDLE_IN -> handlePos(l, index, true);
            case HANDLE_OUT -> handlePos(l, index, false);
            case SIZE_START -> l.sizeNode(false);
            case SIZE_END -> l.sizeNode(true);
            case TWIST_START -> l.twistNode(false);
            case TWIST_END -> l.twistNode(true);
            case MOVE -> l.moveNode();
            default -> null;
        };
    }

    /** True when node {@code i} has an in (or out) handle at all (ends have only one each). */
    private static boolean hasHandle(LineShape l, int i, boolean in) {
        int last = l.curve.nodes.size() - 1;
        return in ? i > 0 : i < last;
    }

    /** A handle's drawn position: its real offset, or parked along the tangent when zero. */
    private static Vec3 handlePos(LineShape l, int i, boolean in) {
        LineCurve.Node n = l.curve.nodes.get(i);
        Vec3 h = in ? n.in : n.out;
        if (h.lengthSqr() > 1e-12) return n.p.add(h);
        return n.p.add(nodeTangent(l, i).scale(in ? -PARK : PARK));
    }

    private static boolean handleParked(LineShape l, int i, boolean in) {
        LineCurve.Node n = l.curve.nodes.get(i);
        return (in ? n.in : n.out).lengthSqr() <= 1e-12;
    }

    private static Vec3 nodeTangent(LineShape l, int i) {
        List<LineCurve.Node> ns = l.curve.nodes;
        Vec3 prev = ns.get(Math.max(0, i - 1)).p, next = ns.get(Math.min(ns.size() - 1, i + 1)).p;
        Vec3 t = next.subtract(prev);
        return t.lengthSqr() < 1e-12 ? new Vec3(1, 0, 0) : t.normalize();
    }

    private static void updateAim(Minecraft mc) {
        if (dragLine != null) {
            aimed = new Aim(dragLine, dragKind, dragIndex, 0);
            return;
        }
        Vec3 eye = Raycast.eye(mc), dir = Raycast.look(mc);
        Aim best = null;
        Vec3[] cam = cameraAxes(mc);
        for (LineShape l : lines) {
            boolean sel = l == selected;
            List<LineCurve.Node> ns = l.curve.nodes;
            for (int i = 0; i < ns.size(); i++) {
                best = closer(best, l, Kind.NODE, i, raySphere(eye, dir, ns.get(i).p, PICK));
                if (sel) {
                    if (hasHandle(l, i, true)) {
                        best = closer(best, l, Kind.HANDLE_IN, i, raySphere(eye, dir, handlePos(l, i, true), PICK));
                    }
                    if (hasHandle(l, i, false)) {
                        best = closer(best, l, Kind.HANDLE_OUT, i, raySphere(eye, dir, handlePos(l, i, false), PICK));
                    }
                }
            }
            if (sel) {
                if (l.profile != LineProfile.NONE) {
                    best = closer(best, l, Kind.SIZE_START, 0, rayCube(eye, dir, l.sizeNode(false), CTRL_HALF));
                    best = closer(best, l, Kind.SIZE_END, 0, rayCube(eye, dir, l.sizeNode(true), CTRL_HALF));
                    best = closer(best, l, Kind.TWIST_START, 0, rayCube(eye, dir, l.twistNode(false), CTRL_HALF));
                    best = closer(best, l, Kind.TWIST_END, 0, rayCube(eye, dir, l.twistNode(true), CTRL_HALF));
                }
                best = closer(best, l, Kind.MOVE, 0, rayCube(eye, dir, l.moveNode(), CTRL_HALF));
                for (int k = 0; k < BUTTON_COUNT; k++) {
                    // Buttons draw on top of everything, so they win over anything behind them.
                    double t = rayButton(eye, dir, buttonCenter(l, k, cam), cam);
                    if (t >= 0) best = new Aim(l, Kind.BUTTON, k, 0);
                }
            }
        }
        if (best == null) {
            // Nothing grabbable: the nearest line body within pick distance of the ray.
            double bestGap = BODY_PICK;
            for (LineShape l : lines) {
                LineCurve.RayHit h = LineCurve.closestToRay(l.samples(), eye, dir);
                if (h != null && h.distance() < bestGap && h.rayT() < Raycast.REACH) {
                    bestGap = h.distance();
                    best = new Aim(l, Kind.BODY, 0, h.rayT());
                }
            }
        }
        aimed = best;
    }

    private static Aim closer(Aim best, LineShape l, Kind k, int i, double t) {
        if (t < 0) return best;
        if (best != null && best.kind == Kind.BUTTON) return best;
        return best == null || t < best.dist ? new Aim(l, k, i, t) : best;
    }

    private static double raySphere(Vec3 o, Vec3 d, Vec3 c, double r) {
        Vec3 oc = c.subtract(o);
        double t = oc.dot(d);
        if (t < 0) return -1;
        double d2 = oc.lengthSqr() - t * t;
        return d2 <= r * r ? t : -1;
    }

    private static double rayCube(Vec3 o, Vec3 d, Vec3 c, double h) {
        return ShapeMath.rayBoxIntersect(o.x, o.y, o.z, d.x, d.y, d.z,
                c.x - h, c.y - h, c.z - h, c.x + h, c.y + h, c.z + h);
    }

    /** Camera right/up axes for billboards: {right, up, forward}. */
    private static Vec3[] cameraAxes(Minecraft mc) {
        Vec3 f = Raycast.look(mc);
        Vec3 right = f.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : right.normalize();
        Vec3 up = right.cross(f).normalize();
        return new Vec3[]{right, up, f};
    }

    private static Vec3 buttonCenter(LineShape l, int k, Vec3[] cam) {
        double width = BUTTON_COUNT * BTN + (BUTTON_COUNT - 1) * BTN_GAP;
        double x = -width / 2 + BTN / 2 + k * (BTN + BTN_GAP);
        return l.buttonAnchor().add(cam[0].scale(x));
    }

    /** Ray distance to a camera-facing button quad, or -1. */
    private static double rayButton(Vec3 o, Vec3 d, Vec3 c, Vec3[] cam) {
        Vec3 n = cam[2];
        double den = d.dot(n);
        if (Math.abs(den) < 1e-6) return -1;
        double t = c.subtract(o).dot(n) / den;
        if (t < 0) return -1;
        Vec3 p = o.add(d.scale(t)).subtract(c);
        double h = BTN / 2;
        return Math.abs(p.dot(cam[0])) <= h && Math.abs(p.dot(cam[1])) <= h ? t : -1;
    }

    private static void overlay(Minecraft mc, String msg) {
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal(msg));
    }

    // ---- rendering ----------------------------------------------------------------------------

    static void render(SubmitNodeCollector col, Vec3 cam, boolean handles, PlacementMode pm) {
        Minecraft mc = Minecraft.getInstance();
        frame++;
        Vec3[] axes = cameraAxes(mc);
        PoseStack ps = new PoseStack();
        for (LineShape l : lines) {
            boolean sel = handles && l == selected;
            boolean dragging = l == dragLine;
            l.ensureCells(!dragging || frame % 4 == 0);
            drawTube(col, ps, cam, l, sel ? TUBE_EDGE_SELECTED : TUBE_EDGE);
            col.submitCustomGeometry(ps, RenderTypes.debugFilledBox(), (pose, vc) -> {
                drawCurve(pose, vc, cam, l.samples(), 0.035, sel ? LINE_SELECTED : LINE);
                List<LineCurve.Node> ns = l.curve.nodes;
                for (int i = 0; i < ns.size(); i++) {
                    int c = i == 0 ? NODE_START : i == ns.size() - 1 ? NODE_END : NODE_MID;
                    boolean a = isAimed(l, Kind.NODE, i);
                    cube(pose, vc, cam, ns.get(i).p, a ? NODE_HALF * 1.3 : NODE_HALF, a ? AIMED : c);
                }
                if (!sel) return;
                for (int i = 0; i < ns.size(); i++) {
                    for (boolean in : new boolean[]{true, false}) {
                        if (!hasHandle(l, i, in)) continue;
                        Kind k = in ? Kind.HANDLE_IN : Kind.HANDLE_OUT;
                        Vec3 hp = handlePos(l, i, in);
                        boolean parked = handleParked(l, i, in);
                        if (!parked) segmentRibbon(pose, vc, cam, ns.get(i).p, hp, 0.015, STALK);
                        sphere(pose, vc, cam, hp, HANDLE_R, isAimed(l, k, i) ? AIMED : parked ? HANDLE_PARKED : HANDLE);
                    }
                }
                if (l.profile != LineProfile.NONE) {
                    cube(pose, vc, cam, l.sizeNode(false), CTRL_HALF, isAimed(l, Kind.SIZE_START, 0) ? AIMED : SIZE);
                    cube(pose, vc, cam, l.sizeNode(true), CTRL_HALF, isAimed(l, Kind.SIZE_END, 0) ? AIMED : SIZE);
                    cube(pose, vc, cam, l.twistNode(false), CTRL_HALF, isAimed(l, Kind.TWIST_START, 0) ? AIMED : TWIST);
                    cube(pose, vc, cam, l.twistNode(true), CTRL_HALF, isAimed(l, Kind.TWIST_END, 0) ? AIMED : TWIST);
                }
                cube(pose, vc, cam, l.moveNode(), CTRL_HALF, isAimed(l, Kind.MOVE, 0) ? AIMED : MOVE);
            });
            if (sel) drawButtons(col, ps, cam, l, axes);
        }
        if (pm == PlacementMode.MARKER_LINE && handles && dragLine == null) {
            Vec3 p = aimPoint(mc);
            Vec3 start = pendingStart;
            col.submitCustomGeometry(ps, RenderTypes.debugFilledBox(), (pose, vc) -> {
                cube(pose, vc, cam, p, NODE_HALF, start == null ? NODE_START : NODE_END);
                if (start != null) {
                    cube(pose, vc, cam, start, NODE_HALF, NODE_START);
                    segmentRibbon(pose, vc, cam, start, p, 0.035, PREVIEW);
                }
            });
        }
    }

    private static boolean isAimed(LineShape l, Kind k, int i) {
        return aimed != null && aimed.line == l && aimed.kind == k && aimed.index == i;
    }

    /** The selected cells as one batched submission: exposed faces + their convex edges. */
    private static void drawTube(SubmitNodeCollector col, PoseStack ps, Vec3 cam, LineShape l, int edge) {
        int n = l.renderSize();
        if (n == 0) return;
        var o = l.renderOrigin();
        ps.pushPose();
        ps.translate(o.getX() - cam.x, o.getY() - cam.y, o.getZ() - cam.z);
        col.submitCustomGeometry(ps, RenderTypes.debugFilledBox(), (pose, vc) -> {
            for (int i = 0; i < n; i++) {
                int mask = l.renderMask(i);
                if (mask == 0) continue;
                float x = l.renderX(i), y = l.renderY(i), z = l.renderZ(i);
                WorldDraw.emitCellFaces(pose, vc, x, y, z, TUBE_FILL, mask);
                WorldDraw.emitCellEdges(pose, vc, x, y, z, edge, mask, false);
            }
        });
        ps.popPose();
    }

    /** Camera-facing buttons drawn on top of everything (see-through pipeline). */
    private static void drawButtons(SubmitNodeCollector col, PoseStack ps, Vec3 cam, LineShape l, Vec3[] ax) {
        col.submitCustomGeometry(ps, WorldDraw.seeThrough(), (pose, vc) -> {
            for (int k = 0; k < BUTTON_COUNT; k++) {
                Vec3 c = buttonCenter(l, k, ax);
                boolean active = k < BUTTON_PROFILES.length ? l.profile == BUTTON_PROFILES[k] : false;
                boolean hover = isAimed(l, Kind.BUTTON, k);
                int bg = active ? BTN_ACTIVE : hover ? BTN_HOVER : BTN_BG;
                billboardQuad(pose, vc, cam, c, ax, BTN / 2, BTN / 2, bg);
                // Icon a hair toward the camera so it sorts over its background.
                Vec3 ic = c.subtract(ax[2].scale(0.01));
                int fg = active ? ICON_ON_ACTIVE : ICON;
                double r = BTN * 0.32;
                if (k < BUTTON_PROFILES.length) {
                    fan(pose, vc, cam, ic, ax, BUTTON_PROFILES[k].outline(r), fg);
                } else if (l.solid) {
                    fan(pose, vc, cam, ic, ax, LineProfile.CIRCLE.outline(r), fg);   // filled disc
                } else {
                    ring(pose, vc, cam, ic, ax, r, r * 0.6, fg);                     // hollow ring
                }
            }
        });
    }

    // ---- geometry emitters (camera-relative, identity pose) ------------------------------------

    private static void v(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, Vec3 p, int argb) {
        vc.addVertex(pose, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).setColor(argb);
    }

    private static void vt(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, Vec3 p, int argb) {
        vc.addVertex(pose, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z))
                .setUv(0.5f, 0.5f).setColor(argb);
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, int argb, Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        v(vc, pose, cam, a, argb);
        v(vc, pose, cam, b, argb);
        v(vc, pose, cam, c, argb);
        v(vc, pose, cam, d, argb);
    }

    private static void drawCurve(PoseStack.Pose pose, VertexConsumer vc, Vec3 cam,
                                  List<LineCurve.Sample> ss, double w, int argb) {
        for (int i = 0; i + 1 < ss.size(); i++) segmentRibbon(pose, vc, cam, ss.get(i).pos(), ss.get(i + 1).pos(), w, argb);
    }

    /** A thin crossed ribbon a→b (reads as a line from any angle; depth-tested). */
    private static void segmentRibbon(PoseStack.Pose pose, VertexConsumer vc, Vec3 cam, Vec3 a, Vec3 b, double w, int argb) {
        Vec3 d = b.subtract(a);
        if (d.lengthSqr() < 1e-12) return;
        d = d.normalize();
        Vec3 e1 = d.cross(Math.abs(d.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize().scale(w);
        Vec3 e2 = d.cross(e1).normalize().scale(w);
        quad(vc, pose, cam, argb, a.subtract(e1), b.subtract(e1), b.add(e1), a.add(e1));
        quad(vc, pose, cam, argb, a.subtract(e2), b.subtract(e2), b.add(e2), a.add(e2));
    }

    private static void cube(PoseStack.Pose pose, VertexConsumer vc, Vec3 cam, Vec3 c, double h, int argb) {
        double x0 = c.x - h, x1 = c.x + h, y0 = c.y - h, y1 = c.y + h, z0 = c.z - h, z1 = c.z + h;
        quad(vc, pose, cam, argb, new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x1, y0, z1), new Vec3(x0, y0, z1));
        quad(vc, pose, cam, argb, new Vec3(x0, y1, z0), new Vec3(x0, y1, z1), new Vec3(x1, y1, z1), new Vec3(x1, y1, z0));
        quad(vc, pose, cam, argb, new Vec3(x0, y0, z0), new Vec3(x0, y1, z0), new Vec3(x1, y1, z0), new Vec3(x1, y0, z0));
        quad(vc, pose, cam, argb, new Vec3(x0, y0, z1), new Vec3(x1, y0, z1), new Vec3(x1, y1, z1), new Vec3(x0, y1, z1));
        quad(vc, pose, cam, argb, new Vec3(x0, y0, z0), new Vec3(x0, y0, z1), new Vec3(x0, y1, z1), new Vec3(x0, y1, z0));
        quad(vc, pose, cam, argb, new Vec3(x1, y0, z0), new Vec3(x1, y1, z0), new Vec3(x1, y1, z1), new Vec3(x1, y0, z1));
    }

    /** Low-poly UV sphere (8 slices × 6 stacks). */
    private static void sphere(PoseStack.Pose pose, VertexConsumer vc, Vec3 cam, Vec3 c, double r, int argb) {
        int slices = 8, stacks = 6;
        for (int i = 0; i < stacks; i++) {
            double p0 = Math.PI * i / stacks - Math.PI / 2, p1 = Math.PI * (i + 1) / stacks - Math.PI / 2;
            for (int j = 0; j < slices; j++) {
                double a0 = 2 * Math.PI * j / slices, a1 = 2 * Math.PI * (j + 1) / slices;
                quad(vc, pose, cam, argb,
                        sp(c, r, p0, a0), sp(c, r, p0, a1), sp(c, r, p1, a1), sp(c, r, p1, a0));
            }
        }
    }

    private static Vec3 sp(Vec3 c, double r, double lat, double lon) {
        double cl = Math.cos(lat);
        return new Vec3(c.x + r * cl * Math.cos(lon), c.y + r * Math.sin(lat), c.z + r * cl * Math.sin(lon));
    }

    private static void billboardQuad(PoseStack.Pose pose, VertexConsumer vc, Vec3 cam, Vec3 c, Vec3[] ax,
                                      double hw, double hh, int argb) {
        Vec3 r = ax[0].scale(hw), u = ax[1].scale(hh);
        vt(vc, pose, cam, c.subtract(r).subtract(u), argb);
        vt(vc, pose, cam, c.add(r).subtract(u), argb);
        vt(vc, pose, cam, c.add(r).add(u), argb);
        vt(vc, pose, cam, c.subtract(r).add(u), argb);
    }

    /** Filled outline as a fan from the centre: (u, v) → (screen up, screen right). */
    private static void fan(PoseStack.Pose pose, VertexConsumer vc, Vec3 cam, Vec3 c, Vec3[] ax, double[][] pts, int argb) {
        for (int i = 0; i < pts.length; i++) {
            double[] a = pts[i], b = pts[(i + 1) % pts.length];
            Vec3 pa = c.add(ax[1].scale(a[0])).add(ax[0].scale(a[1]));
            Vec3 pb = c.add(ax[1].scale(b[0])).add(ax[0].scale(b[1]));
            // Wound counter-clockwise as seen from the camera (the see-through pipeline culls).
            vt(vc, pose, cam, c, argb);
            vt(vc, pose, cam, pb, argb);
            vt(vc, pose, cam, pa, argb);
            vt(vc, pose, cam, pa, argb);
        }
    }

    private static void ring(PoseStack.Pose pose, VertexConsumer vc, Vec3 cam, Vec3 c, Vec3[] ax,
                             double ro, double ri, int argb) {
        int n = 24;
        for (int i = 0; i < n; i++) {
            double a0 = 2 * Math.PI * i / n, a1 = 2 * Math.PI * (i + 1) / n;
            vt(vc, pose, cam, ringPt(c, ax, ro, a0), argb);
            vt(vc, pose, cam, ringPt(c, ax, ro, a1), argb);
            vt(vc, pose, cam, ringPt(c, ax, ri, a1), argb);
            vt(vc, pose, cam, ringPt(c, ax, ri, a0), argb);
        }
    }

    private static Vec3 ringPt(Vec3 c, Vec3[] ax, double r, double a) {
        return c.add(ax[0].scale(Math.cos(a) * r)).add(ax[1].scale(Math.sin(a) * r));
    }
}
