package co.fax.wang.shape;

import co.fax.wang.Gradient;
import co.fax.wang.PlacementMode;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The line marker (Line mode): creation, editing and rendering of {@link LineShape}s. Behaviour
 * is specified in docs/design/line-marker.md. In short:
 *
 * <ul>
 *   <li>Line mode: LMB the start (aimed surface point, or Marker reach out at the sky), LMB the
 *       end. RMB cancels a half-made line.</li>
 *   <li>Editing, also only in Line mode (elsewhere lines just show and constrain painting): drag
 *       nodes (cubes) and curve handles (spheres) on the plane through the point facing the eye
 *       (rebuilt each tick); scroll while dragging pushes the point further / nearer. Handles
 *       snap onto their node when dragged close; the modifier mirrors the opposite handle, Alt
 *       locks a drag to an axis. Double-click a node: sharp ↔ smooth; double-click the line:
 *       insert a node. MMB a middle node removes it, anything else on the line deletes the line
 *       (modifier + MMB: always the whole line).</li>
 *   <li>With a profile, every node has a size cube (green) and a twist cube (blue) — shown on the
 *       two ends and on the node last aimed at. Drag = that node, modifier+drag = every node,
 *       RMB = reset that node, modifier+RMB = reset all; modifier+scroll on a node grows / shrinks
 *       it.</li>
 *   <li>A white move cube, a grey cog and a blue rotate cube ride together beside the line. They
 *       slide along it to stay a few blocks from where you stand and line up side by side across
 *       your view (from your position, not where you look), so they are always in reach and never
 *       hide each other. The cog opens the line's toolbar ({@link MarkerToolbar}): profile,
 *       shell / solid, gradient, flip, delete.</li>
 *   <li>The rotate cube turns rotate mode on and off: two rings (the gimbal, in axis colours)
 *       go round the whole line; grab either anywhere and pull to turn the line about its
 *       centre. The gimbal turns with the line and is saved with it.</li>
 * </ul>
 *
 * State lives here; {@link ShapeMarkers} decides each tick who owns the aim, forwards
 * tick/render/scroll and owns persistence.
 */
public final class LineMarkers {

    private LineMarkers() {}

    // ---- visuals --------------------------------------------------------------------------
    private static final int LINE = 0xFFFFB830;
    private static final int LINE_SELECTED = 0xFFFFFFFF;
    private static final int PREVIEW = 0xC0FFB830;
    private static final int STALK = Gizmo.alpha(co.fax.wang.ui.Ui.BEND, 0xC0);
    private static final int TUBE_FILL = 0x33FFA000;
    private static final int TUBE_EDGE = 0xB0FFB830;
    private static final int TUBE_EDGE_SELECTED = 0xC0FFFFFF;
    // The on-top ghost pass draws through everything, so it stays faint (same as ring ghosts).
    private static final int TUBE_GHOST = 0x5CFFB830;
    private static final int TUBE_GHOST_SELECTED = 0xA8FFFFFF;

    private static final double NODE_HALF = 0.15;   // main node cube half-size
    private static final double HANDLE_R = 0.12;    // curve handle sphere radius
    private static final double PICK = 0.3;         // grab radius around nodes and handles
    private static final double BODY_PICK = 0.35;   // ray-to-curve distance that targets a line
    private static final double SNAP = 0.35;        // handle within this of its node → zero
    private static final double PARK = 0.6;         // where a zero handle is shown/grabbed
    private static final double CTRL_HALF = Gizmo.CTRL_HALF;
    private static final long DOUBLE_CLICK_MS = 350;

    /** What the crosshair is on; SIZE / TWIST carry their node's index. */
    private enum Kind { NODE, HANDLE_IN, HANDLE_OUT, SIZE, TWIST, MOVE, COG, ROTATE, GIMBAL, BODY }

    /** The gimbal rings sit this far outside the line's furthest reach. */
    private static final double RING_MARGIN = 1;
    /** Added to a ring's aim distance, so every real control anywhere beats it. */
    private static final double RING_BEHIND = 1e6;
    /** Holding the modifier turns in steps of this (radians). */
    private static final double TURN_STEP = Math.toRadians(15);
    /** Gimbal ring colours: index 0 turns about the gimbal's Y axis, 1 about its X axis. */
    private static final int[] RING_COLORS = {
            Gizmo.axisColor(net.minecraft.core.Direction.Axis.Y), Gizmo.axisColor(net.minecraft.core.Direction.Axis.X)};

    private record Aim(LineShape line, Kind kind, int index, double dist) {}

    /** Toolbar profile buttons, left to right (NONE = a bare line). */
    private static final LineProfile[] PROFILES = {
            LineProfile.NONE, LineProfile.CIRCLE, LineProfile.SQUARE, LineProfile.TRIANGLE, LineProfile.STAR};
    /**
     * Gradient button glyphs, 7×5: '1'…'4' are faint → full, '4' marking where the gradient
     * starts (across: the near side, drawn at the top; along: the start node, on the left).
     */
    private static final String[][] GRADIENT_GLYPHS = {
            {"4444444", "3333333", "2222222", "1111111", "1111111"},   // ACROSS
            {"4433211", "4433211", "4433211", "4433211", "4433211"},   // ALONG
            {"1111111", "1233321", "1234321", "1233321", "1111111"},   // INSIDE_OUT
            {"4444444", "4322234", "4321234", "4322234", "4444444"}};  // OUTSIDE_IN

    static final List<LineShape> lines = new ArrayList<>();
    private static LineShape selected;
    private static Vec3 pendingStart;
    private static Aim aimed;

    // Drag state: the view plane fixed at the click.
    private static LineShape dragLine;
    private static Kind dragKind;
    private static int dragIndex;
    private static Vec3 grabOffset; // control position minus the aim point at the grab
    private static Vec3 dragOrigin; // where the dragged control was when grabbed
    /** Alt axis lock for node / move drags: the world axis it slides along, or null. */
    private static net.minecraft.core.Direction.Axis lockAxis;
    private static final double LOCK_PICK = 0.3; // movement needed before Alt picks an axis
    private static final double GUIDE_LEN = 96;  // half-length of the axis guide line
    private static long lastBodyClickMs, lastNodeClickMs;
    private static LineShape lastNodeLine;
    private static int lastNodeIndex;
    /** Right-drag of a handle: it slides along {@link #radialDir} through its node. */
    private static boolean dragRadial;
    private static Vec3 radialDir;
    private static LineShape lastBodyLine;
    /** The selected line's node last aimed at: its size + twist cubes stay out. -1 = none. */
    private static int selNode = -1;
    private static long frame;
    /** The line in rotate mode (its gimbal rings are out), or null. */
    private static LineShape rotating;
    // A ring drag: the line as it was at the grab, and the ring being pulled.
    private static LineShape.Pose turnPose;
    private static Vec3 turnCentre, turnAxis;
    private static double turnRadius, turnAngle0, turnBy;
    /** Axis lock was down at the grab: the ring itself leans about the other ring's axis, the line stays. */
    private static boolean turnTilt;
    /** The grabbed spot (from the centre), and the circle it travels on, which the pull is read against. */
    private static Vec3 turnGrab, turnOn;
    private static double turnOnRadius;
    private static long lastSlideNanos;

    // ---- queries ------------------------------------------------------------------------------

    /** True while a line control is aimed or a drag is live: clicks belong to lines. */
    static boolean controlAimed() {
        return dragLine != null || (aimed != null && aimed.kind != Kind.BODY);
    }

    /** Ray distance to the aimed line control (0 while dragging), or +∞ when none. */
    static double controlDist() {
        if (dragLine != null) return 0;
        if (aimed == null || aimed.kind == Kind.BODY) return Double.POSITIVE_INFINITY;
        // A gimbal ring is huge: it loses to any other marker's control, however far that is.
        return aimed.kind == Kind.GIMBAL ? RING_BEHIND + aimed.dist : aimed.dist;
    }

    /** True while a gimbal ring (not being pulled) holds the aim: toolbar buttons win over it. */
    static boolean ringAimed() {
        return dragLine == null && aimed != null && aimed.kind == Kind.GIMBAL;
    }

    static boolean dragging() {
        return dragLine != null;
    }

    /** The line whose cog is under the crosshair, or null. */
    static LineShape cogAimed() {
        return aimed != null && aimed.kind == Kind.COG ? aimed.line : null;
    }

    /** The line under the crosshair (any part, body included), or null. */
    static LineShape lookedLine() {
        return dragLine != null ? dragLine : aimed != null ? aimed.line : null;
    }

    /** The line looked at for the HUD: its curve or a control, else any of its tube's cells. */
    static LineShape hudLine(Minecraft mc) {
        LineShape l = lookedLine();
        return l != null ? l : tubeLookedInto(mc);
    }

    /** Another marker's control won the aim this tick: forget ours (unless dragging). */
    static void dropAim() {
        if (dragLine == null) aimed = null;
    }

    /** Half-made line in progress (Line mode). */
    static boolean creating() {
        return pendingStart != null;
    }

    /**
     * Dev-only ({@link co.fax.wang.DebugScript}'s {@code aim ring K DEG}): the point {@code deg}
     * degrees round gimbal ring {@code ring} of the line in rotate mode, or null when none is.
     */
    public static Vec3 debugRingPoint(int ring, double deg) {
        if (rotating == null) return null;
        Vec3[] uv = Gizmo.planeAxes(ringAxis(rotating, ring));
        double a = Math.toRadians(deg), r = ringRadius(rotating);
        return rotating.curve.centre().add(uv[0].scale(Math.cos(a) * r)).add(uv[1].scale(Math.sin(a) * r));
    }

    /** Dev bench hook ({@code DebugRender}): add a line and select it, without saving. */
    public static void debugAdd(LineShape l) {
        lines.add(l);
        selected = l;
    }

    static void clear() {
        lines.clear();
        resetTransient();
    }

    static void resetTransient() {
        selected = null;
        selNode = -1;
        pendingStart = null;
        aimed = null;
        dragLine = null;
        rotating = null;
    }

    /** Delete a line (MMB, or the toolbar's bin). */
    static void remove(LineShape l) {
        lines.remove(l);
        if (selected == l) selected = null;
        if (aimed != null && aimed.line == l) aimed = null;
        if (dragLine == l) dragLine = null;
        if (rotating == l) rotating = null;
        MarkerText.say("Line removed");
        ShapeMarkers.saveLines();
    }

    // ---- tick ---------------------------------------------------------------------------------

    /**
     * Find the line control (or line body) under the crosshair. Lines are only editable in Line
     * mode; elsewhere they just show and constrain painting (no aiming, dragging or deleting).
     */
    static void updateAim(Minecraft mc, PlacementMode pm, boolean handles) {
        boolean create = pm == PlacementMode.MARKER_LINE;
        if (!create) pendingStart = null;
        if (!handles || (!create && !debugHold)) {
            if (dragLine != null && !debugHold) { // released by leaving: finish the drag properly
                snapOnRelease(dragLine);
                ShapeMarkers.saveLines();
            }
            aimed = null;
            dragLine = null;
            return;
        }
        // The move / cog / rotate cluster follows where you stand (held by a drag or open toolbar).
        Vec3 eye = Raycast.eye(mc);
        for (LineShape l : lines) l.aimControls(eye, dragLine == l || MarkerToolbar.isOpen(l));
        computeAim(mc);
        if (aimed != null && dragLine == null) {
            if (selected != aimed.line) selNode = -1;
            selected = aimed.line;
            if (aimed.kind == Kind.NODE) selNode = aimed.index;
        }
    }

    /**
     * Input for lines once ShapeMarkers has decided who owns this tick's aim: {@code owns} is
     * false when another marker's control or the toolbar took it (only a live drag continues).
     * Returns true when a line consumed this tick's clicks.
     */
    static boolean tick(Minecraft mc, PlacementMode pm, boolean owns,
                        boolean attack, boolean attackEdge, boolean use, boolean useEdge, boolean pickEdge) {
        long perfT0 = PERF ? System.nanoTime() : 0;
        try {
            return tickInner(mc, pm, owns, attack, attackEdge, use, useEdge, pickEdge);
        } finally {
            if (PERF) {
                perfTicks++;
                perfTickNanos += System.nanoTime() - perfT0;
            }
        }
    }

    private static boolean tickInner(Minecraft mc, PlacementMode pm, boolean owns,
                                     boolean attack, boolean attackEdge, boolean use, boolean useEdge, boolean pickEdge) {
        boolean create = pm == PlacementMode.MARKER_LINE;
        if (!create && !debugHold) return false;

        boolean saveNeeded = false;
        boolean consumed = false;

        if (dragLine != null) {
            if (debugHold) {
                return true; // dev bench drag: the bench moves the node itself
            } else if (dragRadial ? use : attack) {
                applyDrag(mc);
            } else {
                snapOnRelease(dragLine);
                dragLine = null;
                saveNeeded = true;
            }
            consumed = true;
        }
        if (!owns) {
            if (saveNeeded) ShapeMarkers.saveLines();
            return consumed;
        }

        if (attackEdge && dragLine == null) {
            if (aimed != null && aimed.kind != Kind.COG) { // the cog is ShapeMarkers' (toolbar)
                consumed = true;
                ShapeMarkers.touch(aimed.line);
                switch (aimed.kind) {
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
                    case NODE -> {
                        // Double-click a main node: toggle sharp corner <-> smooth curve.
                        long now = System.currentTimeMillis();
                        if (lastNodeLine == aimed.line && lastNodeIndex == aimed.index
                                && now - lastNodeClickMs < DOUBLE_CLICK_MS) {
                            toggleSmooth(aimed.line, aimed.index);
                            lastNodeLine = null;
                            saveNeeded = true;
                        } else {
                            lastNodeLine = aimed.line;
                            lastNodeIndex = aimed.index;
                            lastNodeClickMs = now;
                            beginDrag(mc, aimed, false);
                        }
                    }
                    case ROTATE -> {
                        rotating = rotating == aimed.line ? null : aimed.line;
                        MarkerText.say(rotating != null
                                ? "Line rotate on — drag a ring to turn the line · " + MarkerText.mod() + " 15° steps"
                                : "Line rotate off");
                    }
                    case GIMBAL -> beginTurn(mc, aimed);
                    default -> beginDrag(mc, aimed, false);
                }
            } else if (aimed == null) {
                consumed = true;
                creationClick(mc);
            }
        }
        if (useEdge && dragLine == null && aimed != null
                && (aimed.kind == Kind.HANDLE_IN || aimed.kind == Kind.HANDLE_OUT)) {
            // Right-drag a handle: slide it in/out along its own direction (angle kept).
            beginDrag(mc, aimed, true);
            consumed = true;
        } else if (useEdge && dragLine == null && aimed != null && resetNode(aimed)) {
            saveNeeded = true;
            consumed = true;
        } else if (useEdge && pendingStart != null) {
            pendingStart = null;
            MarkerText.say("Line cancelled");
            consumed = true;
        }
        if (pickEdge && aimed != null && dragLine == null && aimed.kind != Kind.GIMBAL) {
            consumed = true;
            LineShape l = aimed.line;
            int last = l.curve.nodes.size() - 1;
            if (aimed.kind == Kind.NODE && aimed.index > 0 && aimed.index < last && !Gradient.modifierDown()) {
                // The smallest piece first: a middle node (the others keep their own sizes).
                l.curve.nodes.remove(aimed.index);
                if (l == selected) selNode = -1;
                l.markDirty();
                MarkerText.say("Line: node removed");
                saveNeeded = true;
            } else {
                remove(l); // saves
            }
            aimed = null;
        }
        if (saveNeeded) ShapeMarkers.saveLines();
        return consumed || attackEdge;
    }

    /**
     * Right-click a size cube: that node back to the default radius; a twist cube: that node's
     * twist back to zero. Holding the modifier resets every node. False when {@code a} isn't
     * one of them.
     */
    private static boolean resetNode(Aim a) {
        LineShape l = a.line;
        boolean all = Gradient.modifierDown();
        switch (a.kind) {
            case SIZE -> {
                if (all) l.setAllRadii(LineShape.DEFAULT_RADIUS);
                else l.curve.nodes.get(a.index).r = LineShape.DEFAULT_RADIUS;
                MarkerText.say(all ? "Line: every node's size reset" : "Line: node size reset");
            }
            case TWIST -> {
                if (all) l.setAllTwists(0);
                else l.curve.nodes.get(a.index).tw = 0;
                MarkerText.say(all ? "Line: every node's twist reset" : "Line: node twist reset");
            }
            default -> {
                return false;
            }
        }
        l.markDirty();
        ShapeMarkers.touch(l);
        return true;
    }

    /**
     * Mouse wheel. While dragging: push the dragged point a block further from / nearer to you.
     * Aiming a node (or its size cube) without dragging: the modifier grows / shrinks that
     * node's radius half a block a notch; plain scroll pushes the node itself.
     */
    static boolean onScroll(double deltaY) {
        Minecraft mc = Minecraft.getInstance();
        if (dragLine == null && aimed != null && (aimed.kind == Kind.NODE || aimed.kind == Kind.SIZE)) {
            LineShape l = aimed.line;
            LineCurve.Node n = l.curve.nodes.get(aimed.index);
            if (Gradient.modifierDown()) {
                n.r = Math.max(LineShape.MIN_RADIUS, Math.min(LineShape.MAX_RADIUS, n.r + (deltaY > 0 ? 0.5 : -0.5)));
                l.markDirty();
                MarkerText.say("Line: node " + (aimed.index + 1) + " radius " + fmt(n.r));
            } else if (aimed.kind == Kind.NODE) {
                Vec3 away = n.p.subtract(Raycast.eye(mc));
                if (away.lengthSqr() < 1e-6) return true;
                Vec3 target = gridCenter(n.p.add(away.normalize().scale(deltaY > 0 ? 1 : -1)));
                if (target.distanceTo(Raycast.eye(mc)) < 1) return true;
                n.p = target;
                l.markDirty();
            } else {
                return false;
            }
            ShapeMarkers.touch(l);
            ShapeMarkers.saveLines();
            return true;
        }
        if (dragLine == null) return false;
        if (dragKind == Kind.GIMBAL) return true; // a ring drag has no depth to push
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
            MarkerText.step("Line", 2, 2, "LMB the end");
            return;
        }
        if (p.distanceToSqr(pendingStart) < 0.01) return;
        LineShape l = new LineShape(new LineCurve(pendingStart, p));
        lines.add(l);
        selected = l;
        selNode = -1;
        pendingStart = null;
        ShapeMarkers.touch(l);
        ShapeMarkers.saveLines();
        MarkerText.say("Line set — drag its nodes, double-click it to add one, LMB its cog for options");
    }

    /**
     * Where a new point goes: the centre of the grid cell in front of the aimed face (where a
     * block placed there would sit), or of the cell at Marker reach when aimed at the sky.
     */
    private static Vec3 aimPoint(Minecraft mc) {
        BlockHitResult hit = Raycast.aimedBlock(mc);
        if (hit != null) return Vec3.atCenterOf(hit.getBlockPos().relative(hit.getDirection()));
        return gridCenter(Raycast.eye(mc).add(Raycast.look(mc).scale(MarkerText.airReach())));
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
        int idx = l.curve.insertNode(seg, t); // takes the radius / twist already there
        LineCurve.Node n = l.curve.nodes.get(idx);
        n.p = gridCenter(n.p); // on the grid like every main node (handles keep the split's shape)
        if (l == selected) selNode = idx;
        l.markDirty();
    }

    // ---- toolbar -------------------------------------------------------------------------------

    /** The line's toolbar: profile · shell | solid, then the gradients · flip · rendering · delete · help. */
    static MarkerToolbar.Spec toolbarSpec(LineShape l) {
        if (!lines.contains(l)) return null;
        List<MarkerToolbar.Button> top = new ArrayList<>(), bottom = new ArrayList<>();
        List<List<MarkerToolbar.Button>> rows = List.of(top, bottom);
        if (MarkerToolbar.stylePage()) {
            rows = StylePage.rows(l, l.style, TUBE_FILL, TUBE_EDGE, () -> {
                ShapeMarkers.touch(l);
                ShapeMarkers.saveLines();
            });
        }
        for (LineProfile p : PROFILES) {
            MarkerToolbar.Icon icon = p == LineProfile.NONE ? MarkerToolbar.LINE_BARE : MarkerToolbar.outline(p.outline(1));
            String name = p == LineProfile.NONE ? "Bare line" : cap(p.name()) + " tube";
            top.add(new MarkerToolbar.Button(icon, l.profile == p, false,
                    name + (l.profile == p ? " (on)" : ""), () -> {
                        l.profile = p;
                        l.markDirty();
                        changed(l, "Line: " + name.toLowerCase(java.util.Locale.ROOT));
                    }));
        }
        top.add(new MarkerToolbar.Button(MarkerToolbar.HOLLOW, !l.solid, false,
                "Shell: only the tube's skin" + (!l.solid ? " (on)" : ""), () -> {
                    l.solid = false;
                    l.markDirty();
                    changed(l, "Line: shell tube");
                }));
        top.add(new MarkerToolbar.Button(MarkerToolbar.DISC, l.solid, false,
                "Solid: the whole tube" + (l.solid ? " (on)" : ""), () -> {
                    l.solid = true;
                    l.markDirty();
                    changed(l, "Line: solid tube");
                }));
        LineGradient[] gs = LineGradient.values();
        for (int i = 0; i < gs.length; i++) {
            LineGradient g = gs[i];
            String what = switch (g) {
                case ACROSS -> "from the side you aim into, across to the far side";
                case ALONG -> "from the start node to the end node";
                case INSIDE_OUT -> "from the line out to the skin";
                case OUTSIDE_IN -> "from the skin in to the line";
            };
            bottom.add(new MarkerToolbar.Button(MarkerToolbar.pixels(GRADIENT_GLYPHS[i], 0.08), l.gradient == g, false,
                    "Gradient " + g.label.toLowerCase(java.util.Locale.ROOT) + ": " + what
                            + (l.gradient == g ? " (on)" : ""), () -> {
                        // Only changes how a later Gradient fill runs — the tube itself is unchanged.
                        l.gradient = g;
                        changed(l, "Line gradient: " + g.label.toLowerCase(java.util.Locale.ROOT));
                    }));
        }
        bottom.add(new MarkerToolbar.Button(MarkerToolbar.FLIP, false, false,
                "Flip: swap the start and end nodes", () -> {
                    l.reverse();
                    changed(l, "Line flipped — it now starts at the other end");
                }));
        bottom.add(StylePage.openButton(l.style));
        bottom.add(new MarkerToolbar.Button(MarkerToolbar.BIN, false, true,
                "Delete this line (same as MMB)", () -> remove(l)));
        bottom.add(new MarkerToolbar.Button(MarkerToolbar.QUESTION, false, false,
                "Help: how lines work", () -> Minecraft.getInstance()
                        .setScreenAndShow(co.fax.wang.GradientScreen.atHelpTopic("Marker line"))));
        Vec3[] b = l.bounds();
        return new MarkerToolbar.Spec(null, rows, cluster(l)[1], b[0], b[1], controlsOf(l));
    }

    private static void changed(LineShape l, String msg) {
        ShapeMarkers.touch(l);
        ShapeMarkers.saveLines();
        MarkerText.say(msg);
    }

    /** Every control position of a line (the toolbar keeps clear of them). */
    private static List<Vec3> controlsOf(LineShape l) {
        List<Vec3> out = new ArrayList<>();
        List<LineCurve.Node> ns = l.curve.nodes;
        for (int i = 0; i < ns.size(); i++) {
            out.add(ns.get(i).p);
            if (l.profile != LineProfile.NONE && cubesShown(l, i)) {
                out.add(l.sizeNode(i));
                out.add(l.twistNode(i));
            }
        }
        Vec3[] c = cluster(l);
        out.add(c[0]);
        out.add(c[2]);
        return out;
    }

    /** The line's {move, cog, rotate} cubes as seen from where the camera is now. */
    private static Vec3[] cluster(LineShape l) {
        return l.controls(Raycast.eye(Minecraft.getInstance()));
    }

    private static String cap(String s) {
        return s.charAt(0) + s.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    // ---- aim hints -----------------------------------------------------------------------------

    /** What the aimed line control does, for the action bar; null when nothing is aimed. */
    static String hint() {
        if (aimed == null) return null;
        LineShape l = aimed.line;
        String mod = MarkerText.mod(), alt = MarkerText.axis();
        int last = l.curve.nodes.size() - 1;
        return switch (aimed.kind) {
            case NODE -> {
                boolean end = aimed.index == 0 || aimed.index == last;
                String name = aimed.index == 0 ? "Start node" : aimed.index == last ? "End node" : "Node " + (aimed.index + 1);
                yield name + " · drag move · " + alt + " axis lock · dbl-click "
                        + (hasAnyHandle(l, aimed.index) ? "sharp" : "smooth")
                        + (l.profile != LineProfile.NONE ? " · " + mod + "+scroll resize" : "")
                        + (end ? " · MMB delete line" : " · MMB delete node · " + mod + "+MMB delete line");
            }
            case HANDLE_IN, HANDLE_OUT -> "Handle · drag bend · RMB drag slide · " + mod + " mirror · "
                    + alt + " axis lock · MMB delete line";
            case SIZE -> "Node " + (aimed.index + 1) + " · radius " + fmt(l.curve.nodes.get(aimed.index).r)
                    + " · drag resize · " + mod + "+drag all nodes · " + mod + "+scroll resize · RMB reset · "
                    + mod + "+RMB reset all";
            case TWIST -> "Twist · drag turn · " + mod + "+drag all nodes · RMB reset · " + mod + "+RMB reset all";
            case MOVE -> "Move · drag move · " + alt + " axis lock · MMB delete line";
            case COG -> "Cog · LMB options · MMB delete line";
            case ROTATE -> rotating == l ? "Rotate · on · LMB turn the rings off · MMB delete line"
                    : "Rotate · LMB show the rings that turn the whole line · MMB delete line";
            case GIMBAL -> dragLine != l
                    ? "Rotate ring · drag turn the whole line · " + alt
                            + "+drag tilt this ring only · " + mod + " 15° steps"
                    : (turnTilt ? "Rotate ring · tilted " : "Rotate ring · turned ")
                            + Math.round(Math.toDegrees(turnBy)) + "° · " + mod + " 15° steps";
            case BODY -> "Line · dbl-click add a node · MMB delete line";
        };
    }

    private static boolean hasAnyHandle(LineShape l, int i) {
        LineCurve.Node n = l.curve.nodes.get(i);
        return n.in.lengthSqr() > 1e-12 || n.out.lengthSqr() > 1e-12;
    }

    // ---- drag ---------------------------------------------------------------------------------

    private static void beginDrag(Minecraft mc, Aim a, boolean radial) {
        Vec3 target = controlPos(a.line, a.kind, a.index);
        if (target == null) return;
        dragLine = a.line;
        dragKind = a.kind;
        dragIndex = a.index;
        dragRadial = radial;
        if (radial) {
            Vec3 d = target.subtract(a.line.curve.nodes.get(a.index).p);
            radialDir = d.lengthSqr() < 1e-9 ? nodeTangent(a.line, a.index) : d.normalize();
        }
        Vec3 hit = Gizmo.viewPlaneHit(Raycast.eye(mc), Raycast.look(mc), target);
        grabOffset = hit == null ? Vec3.ZERO : target.subtract(hit);
        dragOrigin = target;
        lockAxis = null;
        ShapeMarkers.touch(a.line);
    }

    /**
     * Follow the aim: the drag plane passes through the control's current position, facing the
     * eye (perpendicular to eye → point). Rebuilt every tick, so turning on the spot doesn't tilt
     * it, but walking around the point does.
     */
    private static void applyDrag(Minecraft mc) {
        if (dragKind == Kind.GIMBAL) {
            applyTurn(mc);
            return;
        }
        if (dragRadial) {
            // The handle's length follows the point on its ray (from the node) nearest the view.
            LineCurve.Node n = dragLine.curve.nodes.get(dragIndex);
            Vec3 far = n.p.add(radialDir.scale(Raycast.REACH));
            double s = LineCurve.segmentRayClosest(n.p, far, Raycast.eye(mc), Raycast.look(mc))[0];
            double len = s * Raycast.REACH;
            Vec3 h = len < SNAP ? Vec3.ZERO : radialDir.scale(len);
            boolean in = dragKind == Kind.HANDLE_IN;
            if (in) n.in = h;
            else n.out = h;
            if (Gradient.modifierDown()) {
                if (in) n.out = h.scale(-1);
                else n.in = h.scale(-1);
            }
            dragLine.markDirty();
            return;
        }
        Vec3 hit = Gizmo.viewPlaneHit(Raycast.eye(mc), Raycast.look(mc), controlPos(dragLine, dragKind, dragIndex));
        if (hit == null) return;
        Vec3 target = hit.add(grabOffset);
        boolean lockable = dragKind == Kind.NODE || dragKind == Kind.MOVE
                || dragKind == Kind.HANDLE_IN || dragKind == Kind.HANDLE_OUT;
        if (lockable && Gradient.axisLockDown()) {
            // Axis lock: slide along the world axis the drag mostly moves on (picked once, kept until
            // Alt is released), following the point on that axis nearest the view ray.
            if (lockAxis == null) {
                Vec3 d = target.subtract(dragOrigin);
                if (d.length() < LOCK_PICK) return;
                lockAxis = Gizmo.dominantAxis(d);
            }
            target = Gizmo.onAxis(Raycast.eye(mc), Raycast.look(mc), dragOrigin, lockAxis);
        } else {
            lockAxis = null;
        }
        applyTarget(dragLine, target);
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
                if (Gradient.modifierDown()) { // modifier: the opposite handle mirrors
                    if (in) n.out = h.scale(-1);
                    else n.in = h.scale(-1);
                }
            }
            case SIZE -> {
                LineCurve.Sample s = l.samples().get(l.nodeSample(dragIndex));
                Vec3 d = target.subtract(s.pos());
                Vec3 inPlane = d.subtract(s.tan().scale(d.dot(s.tan())));
                double r = Math.max(LineShape.MIN_RADIUS, Math.min(LineShape.MAX_RADIUS, inPlane.length() - 0.5));
                if (Gradient.modifierDown()) l.setAllRadii(r); // modifier: every node
                else ns.get(dragIndex).r = r;
            }
            case TWIST -> {
                LineCurve.Sample s = l.samples().get(l.nodeSample(dragIndex));
                Vec3 d = target.subtract(s.pos());
                // The twist node sits a quarter turn (+b') from the profile's +n' direction.
                double angle = Math.atan2(d.dot(s.b()), d.dot(s.n())) - Math.PI / 2;
                if (Gradient.modifierDown()) l.setAllTwists(angle); // modifier: every node
                else ns.get(dragIndex).tw = angle;
            }
            case MOVE -> l.curve.translate(target.subtract(cluster(l)[0]));
            default -> { }
        }
        l.markDirty();
    }

    // ---- rotate (gimbal rings) ------------------------------------------------------------------

    private static Vec3 ringAxis(LineShape l, int ring) {
        return ring == 0 ? l.gimbalY : l.gimbalX;
    }

    private static double ringRadius(LineShape l) {
        return l.reach() + RING_MARGIN;
    }

    /**
     * Grab a gimbal ring: the line is remembered as it is, and turns from that until release. With
     * axis lock held at the grab it is a tilt instead: the grabbed ring leans about the OTHER
     * ring's axis and the line stays put, which is how a ring's plane is re-aimed.
     */
    private static void beginTurn(Minecraft mc, Aim a) {
        LineShape l = a.line;
        Vec3 eye = Raycast.eye(mc), look = Raycast.look(mc), ringAxis = ringAxis(l, a.index);
        turnCentre = l.curve.centre();
        turnRadius = ringRadius(l);
        turnPose = l.pose();
        turnTilt = Gradient.axisLockDown();
        turnAxis = turnTilt ? ringAxis(l, 1 - a.index) : ringAxis;
        double at = Gizmo.rayCircle(eye, look, turnCentre, ringAxis, turnRadius)[2];
        Vec3[] uv = Gizmo.planeAxes(ringAxis);
        turnGrab = uv[0].scale(Math.cos(at) * turnRadius).add(uv[1].scale(Math.sin(at) * turnRadius));
        // The pull is read against the circle the grabbed spot travels on about the turn axis. For
        // a plain turn that is the ring itself; for a tilt it is a parallel of the other ring. A
        // spot grabbed near that axis barely travels, so there the full-size circle is used.
        double along = turnGrab.dot(turnAxis);
        Vec3 out = turnGrab.subtract(turnAxis.scale(along));
        boolean small = out.length() < turnRadius * 0.15;
        turnOn = small ? turnCentre : turnCentre.add(turnAxis.scale(along));
        turnOnRadius = small ? turnRadius : out.length();
        turnAngle0 = Gizmo.rayCircle(eye, look, turnOn, turnAxis, turnOnRadius)[2];
        turnBy = 0;
        dragLine = l;
        dragKind = Kind.GIMBAL;
        dragIndex = a.index;
        dragRadial = false;
        lockAxis = null;
        ShapeMarkers.touch(l);
    }

    /**
     * Follow the pull: the spot on the ring nearest the crosshair goes where the grabbed spot was,
     * turning the line (and the other ring) about the grabbed ring's axis through the centre, or,
     * for a tilt, leaning just the grabbed ring about the other ring's axis.
     */
    private static void applyTurn(Minecraft mc) {
        double now = Gizmo.rayCircle(Raycast.eye(mc), Raycast.look(mc), turnOn, turnAxis, turnOnRadius)[2];
        double by = now - turnAngle0;
        by -= 2 * Math.PI * Math.floor((by + Math.PI) / (2 * Math.PI)); // into -180° … 180°
        if (Gradient.modifierDown()) by = Math.rint(by / TURN_STEP) * TURN_STEP;
        if (by == turnBy) return;
        turnBy = by;
        if (turnTilt) dragLine.tiltGimbalFrom(turnPose, dragIndex, by);
        else dragLine.turnFrom(turnPose, turnCentre, turnAxis, by);
    }

    /**
     * Double-click on a main node: a node with any handle becomes a sharp corner (handles zeroed);
     * a sharp one gets smooth Catmull-Rom handles — along the line through its neighbours, a sixth
     * of that span each way — so the curve flows through it.
     */
    private static void toggleSmooth(LineShape l, int i) {
        List<LineCurve.Node> ns = l.curve.nodes;
        LineCurve.Node n = ns.get(i);
        if (n.in.lengthSqr() > 1e-12 || n.out.lengthSqr() > 1e-12) {
            n.in = Vec3.ZERO;
            n.out = Vec3.ZERO;
        } else {
            Vec3 prev = ns.get(Math.max(0, i - 1)).p, next = ns.get(Math.min(ns.size() - 1, i + 1)).p;
            Vec3 tan = next.subtract(prev).scale(1.0 / 6);
            if (i == 0 || i == ns.size() - 1) tan = tan.scale(2); // ends see only half the span
            if (i > 0) n.in = tan.scale(-1);
            if (i < ns.size() - 1) n.out = tan;
        }
        l.markDirty();
    }

    /** The centre of the block-grid cell containing {@code p}. */
    static Vec3 gridCenter(Vec3 p) {
        return new Vec3(Math.floor(p.x) + 0.5, Math.floor(p.y) + 0.5, Math.floor(p.z) + 0.5);
    }

    /**
     * Main nodes live on the block grid: a released node drag snaps that node to its cell centre,
     * a released move drag shifts the whole line so its start lands on one (the rest keep their
     * offsets). Handles and end controls stay free.
     */
    private static void snapOnRelease(LineShape l) {
        List<LineCurve.Node> ns = l.curve.nodes;
        if (dragKind == Kind.NODE) {
            LineCurve.Node n = ns.get(dragIndex);
            n.p = gridCenter(n.p);
        } else if (dragKind == Kind.MOVE) {
            Vec3 p0 = ns.get(0).p;
            l.curve.translate(gridCenter(p0).subtract(p0));
        } else {
            return;
        }
        l.markDirty();
    }

    // ---- aiming -------------------------------------------------------------------------------

    /** World position of a control (handles: their drawn position, parked ones included). */
    private static Vec3 controlPos(LineShape l, Kind kind, int index) {
        List<LineCurve.Node> ns = l.curve.nodes;
        return switch (kind) {
            case NODE -> ns.get(index).p;
            case HANDLE_IN -> handlePos(l, index, true);
            case HANDLE_OUT -> handlePos(l, index, false);
            case SIZE -> l.sizeNode(index);
            case TWIST -> l.twistNode(index);
            case MOVE -> cluster(l)[0];
            default -> null;
        };
    }

    /**
     * Whether node {@code i}'s size + twist cubes are out: always on the two ends, and on the
     * node of the selected line that was last aimed at (or whose cube is being dragged).
     */
    private static boolean cubesShown(LineShape l, int i) {
        if (i == 0 || i == l.curve.nodes.size() - 1) return true;
        if (l != selected) return false;
        return i == selNode || (dragLine == l && dragIndex == i);
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

    private static void computeAim(Minecraft mc) {
        if (dragLine != null) {
            aimed = new Aim(dragLine, dragKind, dragIndex, 0);
            return;
        }
        Vec3 eye = Raycast.eye(mc), dir = Raycast.look(mc);
        Aim best = null;
        for (LineShape l : lines) {
            boolean sel = l == selected;
            List<LineCurve.Node> ns = l.curve.nodes;
            for (int i = 0; i < ns.size(); i++) {
                best = closer(best, l, Kind.NODE, i, Gizmo.raySphere(eye, dir, ns.get(i).p, PICK));
                if (sel) {
                    if (hasHandle(l, i, true)) {
                        best = closer(best, l, Kind.HANDLE_IN, i, Gizmo.raySphere(eye, dir, handlePos(l, i, true), PICK));
                    }
                    if (hasHandle(l, i, false)) {
                        best = closer(best, l, Kind.HANDLE_OUT, i, Gizmo.raySphere(eye, dir, handlePos(l, i, false), PICK));
                    }
                }
            }
            if (sel) {
                if (l.profile != LineProfile.NONE) {
                    for (int i = 0; i < ns.size(); i++) {
                        if (!cubesShown(l, i)) continue;
                        best = closer(best, l, Kind.SIZE, i, Gizmo.rayCube(eye, dir, l.sizeNode(i), CTRL_HALF));
                        best = closer(best, l, Kind.TWIST, i, Gizmo.rayCube(eye, dir, l.twistNode(i), CTRL_HALF));
                    }
                }
                Vec3[] c = l.controls(eye);
                best = closer(best, l, Kind.MOVE, 0, Gizmo.rayCube(eye, dir, c[0], CTRL_HALF));
                best = closer(best, l, Kind.ROTATE, 0, Gizmo.rayCube(eye, dir, c[2], CTRL_HALF));
            }
            if (ShapeMarkers.cogShown(l)) {
                best = closer(best, l, Kind.COG, 0, Gizmo.rayCube(eye, dir, l.controls(eye)[1], CTRL_HALF));
            }
        }
        if (best == null && rotating != null && lines.contains(rotating)) {
            // Rotate mode: its two rings, wherever the crosshair passes close to one (they are
            // big, so every cube and node above wins over them).
            Vec3 c = rotating.curve.centre();
            double r = ringRadius(rotating), bestGap = Double.MAX_VALUE;
            for (int ring = 0; ring < 2; ring++) {
                double[] h = Gizmo.rayCircle(eye, dir, c, ringAxis(rotating, ring), r);
                if (h[1] < Raycast.REACH && h[0] < Math.max(PICK, h[1] * 0.02) && h[0] < bestGap) {
                    bestGap = h[0];
                    best = new Aim(rotating, Kind.GIMBAL, ring, h[1]);
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
        return best == null || t < best.dist ? new Aim(l, k, i, t) : best;
    }

    /** A radius for messages: "2", "2.5". */
    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    // ---- rendering ----------------------------------------------------------------------------

    // Dev perf log (FWPAINT_PERF): once a second, what the line work cost.
    private static final boolean PERF = System.getenv("FWPAINT_PERF") != null;
    private static long perfLastMs, perfFrames, perfEmitNanos, perfTicks, perfTickNanos;
    private static final org.slf4j.Logger PERF_LOG = org.slf4j.LoggerFactory.getLogger("fwpaint/perf");

    private static void perfReport() {
        long now = System.currentTimeMillis();
        if (now - perfLastMs < 1000) return;
        int cells = 0, faceV = 0, edgeV = 0;
        for (LineShape l : lines) {
            cells += l.cellCount();
            faceV += l.faceVerts().length / 3;
            edgeV += l.edgeVerts().length / 3;
        }
        PERF_LOG.info("frames={} ticks={} | rebuilds={} ({} forced by contains) {} ms | resamples={} {} ms"
                        + " | tube emit {} ms | tick {} ms | cells={} faceVerts={} edgeVerts={}",
                perfFrames, perfTicks, LineShape.perfRebuilds, LineShape.perfForced,
                LineShape.perfRebuildNanos / 1_000_000, LineShape.perfSamples, LineShape.perfSampleNanos / 1_000_000,
                perfEmitNanos / 1_000_000, perfTickNanos / 1_000_000, cells, faceV, edgeV);
        perfLastMs = now;
        perfFrames = perfTicks = perfEmitNanos = perfTickNanos = 0;
        LineShape.perfRebuilds = LineShape.perfRebuildNanos = LineShape.perfSamples = 0;
        LineShape.perfSampleNanos = LineShape.perfForced = 0;
    }

    /** Dev bench: pretend the selected line's end node is being dragged in a small circle. */
    private static boolean debugHold; // dev bench is "holding" a drag: never release / save

    public static void debugWiggle(int tick) {
        if (selected == null) return;
        debugHold = true;
        dragLine = selected;
        dragKind = Kind.NODE;
        dragIndex = selected.curve.nodes.size() - 1;
        LineCurve.Node n = selected.curve.nodes.get(dragIndex);
        n.p = n.p.add(Math.cos(tick * 0.2) * 0.3, Math.sin(tick * 0.2) * 0.3, 0);
        selected.markDirty();
    }

    static void render(SubmitNodeCollector col, Vec3 cam, boolean handles, PlacementMode pm) {
        Minecraft mc = Minecraft.getInstance();
        frame++;
        if (PERF) {
            perfFrames++;
            perfReport();
        }
        Vec3[] axes = Gizmo.cameraAxes(mc);
        PoseStack ps = new PoseStack();
        LineShape lookedTube = handles ? tubeLookedInto(mc) : null;
        long nowNanos = System.nanoTime();
        double dt = Math.min(0.25, (nowNanos - lastSlideNanos) / 1e9);
        lastSlideNanos = nowNanos;
        Vec3 eye = Raycast.eye(mc);
        for (LineShape l : lines) {
            boolean editing = handles && pm == PlacementMode.MARKER_LINE;
            if (editing) l.slideControls(dt);
            boolean sel = editing && l == selected;
            boolean cog = editing && ShapeMarkers.cogShown(l);
            l.ensureCells(false); // stale tubes rebuild in the background; the last one keeps drawing
            // The tube's edges go white only while it's looked at (crosshair in it, or on one of
            // its parts) — a selected line elsewhere in view keeps the plain amber edges.
            boolean looked = l == lookedTube || (handles && aimed != null && aimed.line == l);
            // The line's own look: looked-at edges stay white but keep its edge opacity.
            drawTube(col, ps, cam, l, l.style.edge(looked ? TUBE_EDGE_SELECTED : TUBE_EDGE, !looked),
                    l.style.ghost(looked ? TUBE_GHOST_SELECTED : TUBE_GHOST, !looked));
            // Twice: depth-tested (weaves through terrain), then a faint on-top ghost so the
            // line and its controls stay readable — and grabbable — behind blocks.
            Vec3[] cluster = editing ? l.controls(eye) : null;
            Gizmo.twoPass(col, ps, cam, e -> drawParts(e, l, sel, editing, cog, axes, cluster));
            if (editing && l == rotating) drawGimbal(col, ps, cam, l);
        }
        if (dragLine != null && lockAxis != null && dragOrigin != null) {
            // Alt axis lock: the track the dragged node slides on, coloured by axis.
            Vec3 u = Gizmo.axisUnit(lockAxis);
            Vec3 a = dragOrigin.subtract(u.scale(GUIDE_LEN)), b = dragOrigin.add(u.scale(GUIDE_LEN));
            int c = Gizmo.axisColor(lockAxis);
            Gizmo.twoPass(col, ps, cam, e -> Gizmo.segmentRibbon(e, a, b, 0.025, c));
        }
        if (pm == PlacementMode.MARKER_LINE && handles && dragLine == null && !ShapeMarkers.controlAimed()) {
            Vec3 p = aimPoint(mc);
            Vec3 start = pendingStart;
            Gizmo.twoPass(col, ps, cam, e -> {
                Gizmo.cube(e, p, NODE_HALF, start == null ? Gizmo.START : Gizmo.END);
                if (start != null) {
                    Gizmo.cube(e, start, NODE_HALF, Gizmo.START);
                    Gizmo.segmentRibbon(e, start, p, 0.035, PREVIEW);
                }
            });
        }
    }

    /**
     * The tube the crosshair runs into first (built cells only, so it never forces a rebuild),
     * stopping at terrain; null when none.
     */
    private static LineShape tubeLookedInto(Minecraft mc) {
        if (lines.isEmpty() || mc.level == null) return null;
        Vec3 eye = Raycast.eye(mc), dir = Raycast.look(mc);
        BlockPos last = null;
        for (double d = 0; d <= Raycast.REACH; d += 0.1) {
            BlockPos c = BlockPos.containing(eye.add(dir.scale(d)));
            if (c.equals(last)) continue;
            last = c;
            for (LineShape l : lines) {
                if (l.containsBuilt(c)) return l;
            }
            if (!Gradient.emptyCell(mc.level.getBlockState(c))) return null;
        }
        return null;
    }

    /** The curve, nodes, and (selected) handles + size/twist/move cubes into one pass. */
    private static void drawParts(Gizmo.Em e, LineShape l, boolean sel, boolean editing, boolean cog, Vec3[] axes,
                                  Vec3[] cluster) {
        drawCurve(e, l.samples(), 0.035, sel ? LINE_SELECTED : LINE);
        if (!editing) return; // outside Line mode: just the line (and its tube)
        List<LineCurve.Node> ns = l.curve.nodes;
        for (int i = 0; i < ns.size(); i++) {
            // Start and end nodes in the start / end colours, so the paint direction shows.
            int c = i == 0 ? Gizmo.START : i == ns.size() - 1 ? Gizmo.END : Gizmo.MOVE;
            boolean a = isAimed(l, Kind.NODE, i);
            Gizmo.cube(e, ns.get(i).p, a ? NODE_HALF * 1.3 : NODE_HALF, a ? Gizmo.AIMED : c);
        }
        if (cog) Gizmo.cog(e, cluster[1], isAimed(l, Kind.COG, 0), axes);
        if (!sel) return;
        for (int i = 0; i < ns.size(); i++) {
            for (boolean in : new boolean[]{true, false}) {
                if (!hasHandle(l, i, in)) continue;
                Kind k = in ? Kind.HANDLE_IN : Kind.HANDLE_OUT;
                Vec3 hp = handlePos(l, i, in);
                boolean parked = handleParked(l, i, in);
                if (!parked) Gizmo.segmentRibbon(e, ns.get(i).p, hp, 0.015, STALK);
                Gizmo.sphere(e, hp, HANDLE_R, isAimed(l, k, i) ? Gizmo.AIMED : parked ? Gizmo.BEND_PARKED : Gizmo.BEND);
            }
        }
        if (l.profile != LineProfile.NONE) {
            for (int i = 0; i < ns.size(); i++) {
                if (!cubesShown(l, i)) continue;
                Gizmo.controlCube(e, l.sizeNode(i), CTRL_HALF, isAimed(l, Kind.SIZE, i) ? Gizmo.AIMED : Gizmo.SIZE,
                        Gizmo.RESIZE_ICON, axes);
                Gizmo.controlCube(e, l.twistNode(i), CTRL_HALF, isAimed(l, Kind.TWIST, i) ? Gizmo.AIMED : Gizmo.TURN,
                        Gizmo.ROTATE_ICON, axes);
            }
        }
        Gizmo.controlCube(e, cluster[0], CTRL_HALF, isAimed(l, Kind.MOVE, 0) ? Gizmo.AIMED : Gizmo.MOVE,
                Gizmo.MOVE_ICON, axes);
        // Rotate: lit (lighter) while its rings are out.
        Gizmo.controlCube(e, cluster[2], CTRL_HALF, isAimed(l, Kind.ROTATE, 0) ? Gizmo.AIMED
                : l == rotating ? Gizmo.lighten(Gizmo.TURN) : Gizmo.TURN, Gizmo.GIMBAL_ICON, axes);
    }

    /**
     * Rotate mode's gimbal: two rings round the whole line, in their axis colours, white under
     * the crosshair or while pulled; a pulled ring also shows a spoke to where it is held.
     */
    private static void drawGimbal(SubmitNodeCollector col, PoseStack ps, Vec3 cam, LineShape l) {
        boolean turning = dragLine == l && dragKind == Kind.GIMBAL;
        Vec3 c = turning ? turnCentre : l.curve.centre();
        double r = turning ? turnRadius : ringRadius(l);
        double w = 0.03 + r * 0.002;
        Gizmo.twoPass(col, ps, cam, e -> {
            for (int ring = 0; ring < 2; ring++) {
                boolean lit = isAimed(l, Kind.GIMBAL, ring);
                Gizmo.circle(e, c, ringAxis(l, ring), r, lit ? w * 1.6 : w, lit ? Gizmo.AIMED : RING_COLORS[ring]);
            }
            if (turning) {
                Vec3 held = c.add(LineCurve.turn(turnGrab, turnAxis, turnBy));
                Gizmo.segmentRibbon(e, c, held, w, Gizmo.AIMED);
                Gizmo.cube(e, held, NODE_HALF, Gizmo.AIMED);
            }
            Gizmo.cube(e, c, NODE_HALF * 0.7, Gizmo.TURN);
        });
    }

    private static boolean isAimed(LineShape l, Kind k, int i) {
        return aimed != null && aimed.line == l && aimed.kind == k && aimed.index == i;
    }

    /**
     * The tube from its precomputed vertex arrays (see LineShape#buildRenderCache): one depth-
     * tested submission of faces + edges, and a faint on-top edge cage so its course stays
     * visible through terrain. Vertices are offset by (origin − camera) directly, identity pose.
     */
    private static void drawTube(SubmitNodeCollector col, PoseStack ps, Vec3 cam, LineShape l, int edge, int ghost) {
        float[] fv = l.faceVerts(), ev = l.edgeVerts();
        if (fv.length == 0 && ev.length == 0) return;
        var o = l.renderOrigin();
        float ox = (float) (o.getX() - cam.x), oy = (float) (o.getY() - cam.y), oz = (float) (o.getZ() - cam.z);
        BlockSkin.Skin found = BlockSkin.of(l.style.block);
        BlockSkin.Skin skin = found != null && blockFaces(fv) <= BlockSkin.MAX_FACES ? found : null;
        int fill = skin != null ? 0 : l.style.face(TUBE_FILL);
        boolean through = l.style.facesThrough;
        col.submitCustomGeometry(ps, RenderTypes.debugFilledBox(), (pose, vc) -> {
            long t0 = PERF ? System.nanoTime() : 0;
            if (fill >>> 24 != 0 && !through) {
                for (int i = 0; i < fv.length; i += 3) vc.addVertex(fv[i] + ox, fv[i + 1] + oy, fv[i + 2] + oz).setColor(fill);
            }
            if (edge >>> 24 != 0) {
                for (int i = 0; i < ev.length; i += 3) vc.addVertex(ev[i] + ox, ev[i + 1] + oy, ev[i + 2] + oz).setColor(edge);
            }
            if (PERF) perfEmitNanos += System.nanoTime() - t0;
        });
        if (skin != null && l.style.blockAlpha() != 0) {
            int alpha = l.style.blockAlpha();
            ps.pushPose();
            ps.translate(ox, oy, oz);
            BlockSkin.submit(col, ps, skin, alpha, through, new Vec3(-ox, -oy, -oz), sink -> tubeBlocks(sink, fv));
            ps.popPose();
        }
        if (through && fill >>> 24 != 0) {
            // Faces shown when obscured: the tint over everything (both windings + texel UV).
            col.submitCustomGeometry(ps, WorldDraw.seeThrough(), (pose, vc) -> {
                for (int q = 0; q + 12 <= fv.length; q += 12) {
                    for (int k = 0; k < 8; k++) {
                        int i = q + (k < 4 ? k : 7 - k) * 3;
                        vc.addVertex(fv[i] + ox, fv[i + 1] + oy, fv[i + 2] + oz).setUv(0.5f, 0.5f).setColor(fill);
                    }
                }
            });
        }
        if (ghost >>> 24 == 0) return;
        col.submitCustomGeometry(ps, WorldDraw.seeThrough(), (pose, vc) -> {
            long t0 = PERF ? System.nanoTime() : 0;
            // The see-through pipeline culls, so each quad goes in both windings (+ texel UV).
            for (int q = 0; q < ev.length; q += 12) {
                for (int k = 0; k < 4; k++) {
                    int i = q + k * 3;
                    vc.addVertex(ev[i] + ox, ev[i + 1] + oy, ev[i + 2] + oz).setUv(0.5f, 0.5f).setColor(ghost);
                }
                for (int k = 3; k >= 0; k--) {
                    int i = q + k * 3;
                    vc.addVertex(ev[i] + ox, ev[i + 1] + oy, ev[i + 2] + oz).setUv(0.5f, 0.5f).setColor(ghost);
                }
            }
            if (PERF) perfEmitNanos += System.nanoTime() - t0;
        });
    }

    /** How many block squares the merged face rectangles {@code fv} cover. */
    private static long blockFaces(float[] fv) {
        long n = 0;
        int[] r = new int[6];
        for (int q = 0; q + 12 <= fv.length; q += 12) {
            faceRect(fv, q, r);
            n += (long) (r[3] - r[2]) * (r[5] - r[4]);
        }
        return n;
    }

    /**
     * One merged face rectangle (4 vertices from {@code q}) back as cells: {@code r} gets
     * {side as Direction.ordinal(), unused, a0, a1, b0, b1} on the two in-plane axes (x, y, z
     * order), and the plane coordinate is returned. TubeBuild lifts faces {@code EXPAND} off the
     * cells: just above a whole number is a positive-facing side, just below a negative one.
     */
    private static float faceRect(float[] fv, int q, int[] r) {
        int axis = 0;
        for (int a = 0; a < 3; a++) {
            if (fv[q + a] == fv[q + 3 + a] && fv[q + a] == fv[q + 6 + a] && fv[q + a] == fv[q + 9 + a]) axis = a;
        }
        float w = fv[q + axis];
        boolean positive = w - Math.floor(w) < 0.5;
        r[0] = axis == 1 ? (positive ? 1 : 0) : axis == 2 ? (positive ? 3 : 2) : (positive ? 5 : 4);
        int pa = axis == 0 ? 1 : 0, pb = axis == 2 ? 1 : 2;
        float a0 = Float.MAX_VALUE, a1 = -Float.MAX_VALUE, b0 = Float.MAX_VALUE, b1 = -Float.MAX_VALUE;
        for (int k = 0; k < 4; k++) {
            a0 = Math.min(a0, fv[q + k * 3 + pa]);
            a1 = Math.max(a1, fv[q + k * 3 + pa]);
            b0 = Math.min(b0, fv[q + k * 3 + pb]);
            b1 = Math.max(b1, fv[q + k * 3 + pb]);
        }
        r[2] = Math.round(a0);
        r[3] = Math.round(a1);
        r[4] = Math.round(b0);
        r[5] = Math.round(b1);
        return w;
    }

    /** The tube shown as a block: every merged face rectangle as one textured square per cell. */
    private static void tubeBlocks(BlockSkin.RectSink sink, float[] fv) {
        int[] r = new int[6];
        for (int q = 0; q + 12 <= fv.length; q += 12) {
            float w = faceRect(fv, q, r);
            sink.rect(r[0], w, r[2], r[3], r[4], r[5]);
        }
    }

    private static void drawCurve(Gizmo.Em e, List<LineCurve.Sample> ss, double w, int argb) {
        for (int i = 0; i + 1 < ss.size(); i++) Gizmo.segmentRibbon(e, ss.get(i).pos(), ss.get(i + 1).pos(), w, argb);
    }
}
