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
 * The line and shape markers (Line / Spline and Shape modes): creation, editing and rendering
 * of {@link LineShape}s. A shape is a straight two-node line with a profile, edited by its faces
 * and sides ({@link LineShape#shape}); it shares everything else with a line: the control
 * cluster, the rotate gimbal, the gradient options, the look, gravity. Both are edited in either
 * marker mode; the mode only decides what a click on nothing makes. Line behaviour is specified
 * in docs/design/line-marker.md. In short:
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
    private enum Kind {
        NODE, HANDLE_IN, HANDLE_OUT, SIZE, TWIST, MOVE, COG, ROTATE, GIMBAL, GRAD_T, GRAD_B, BODY,
        /** A shape's side handles: how wide, how deep, and (hollow) how thick its wall is. */
        WIDTH, DEPTH, WALL
    }

    /** The gradient-angle tool: its plane, ring and the T / B grips. */
    private static final int ANGLE = 0xFFFF5FC8, ANGLE_PLANE = 0x38FF5FC8;
    /** The angle ring sits this far outside the tube, this far along the line from the cog. */
    private static final double ANGLE_MARGIN = 1, ANGLE_ALONG = 2.5;
    /** How see-through the gradient look is until the line's face opacity is set. */
    private static final int PREVIEW_ALPHA = 0xB0;
    /** Physics page: the button that opens it (a weight on the ground), gravity, and undo. */
    private static final String[] PHYSICS_GLYPH = {
            "..XXX..", "..XXX..", ".XXXXX.", ".XXXXX.", "XXXXXXX", ".......", "XXXXXXX"};
    private static final String[] DIG_GLYPH = {
            ".XXXXX.", "X...XX.", "...XXX.", "..XX.X.", ".XX..X.", "XX.....", "X......"};
    /** The pick with a line through it: what is inside is kept. */
    private static final String[] DIG_OUT_GLYPH = {
            ".XXXXXX", "X...XX.", "...XXX.", "..XXXX.", ".XXX.X.", "XXX....", "XX....."};
    private static final String[] GRAVITY_GLYPH = {
            "...X...", "...X...", "...X...", "X..X..X", ".X.X.X.", "..XXX..", "...X..."};
    private static final String[] BACK_GLYPH = {
            ".......", "..X....", ".X.....", "XXXXXXX", ".X.....", "..X....", "......."};
    /** A drop gives up after this many ticks (it is left where it has got to). */
    private static final int DROP_MAX_TICKS = 20 * 45;
    /** A buried line is lifted at most this far to find open air. */
    private static final int POP_UP_LIMIT = 384;
    private static final String[] ANGLE_GLYPH = {
            "XXXXXXX", "...X...", "...X...", ".......", "XXXX...", "X..XX..", "XXXXX.."};
    /** Shape toolbar: the sphere, and the lid of a capped hollow shape. */
    private static final String[] SPHERE_GLYPH = {
            "..XXX..", ".X...X.", "X.....X", "XXXXXXX", "X.....X", ".X...X.", "..XXX.."};
    private static final String[] CAP_GLYPH = {
            "XXXXXXX", ".......", "X.....X", "X.....X", "X.....X", "X.....X", "X.....X"};
    /** A shape is at least this tall. */
    private static final double MIN_HEIGHT = 1;

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
            LineProfile.NONE, LineProfile.CIRCLE, LineProfile.SQUARE, LineProfile.TRIANGLE, LineProfile.STAR,
            LineProfile.CUSTOM};
    /** Toolbar: the drawn profile (a hand-made outline) and its edit pencil. */
    private static final String[] DRAWN_GLYPH = {
            ".XXX...", "X...X..", "X....XX", "X.....X", ".X....X", ".X..XX.", "..XX..."};
    private static final String[] PENCIL_GLYPH = {
            ".....XX", "....XXX", "...XXX.", "..XXX..", ".XXX...", "XXX....", "XX....."};
    /** A drawn profile's cells while it is being edited, and the cell the next click would change. */
    private static final int SKETCH = 0x7040E0FF, SKETCH_NEXT = 0xE0FFFFFF;
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
    /** The line whose drawn profile is being edited (its picture shows, clicks draw on it), or null. */
    private static LineShape sketching;
    /** A sketch stroke is held: 1 drawing with LMB, 2 rubbing out with LMB, 3 rubbing out with RMB; 0 none. */
    private static int stroke;
    /** Shape mode: the first corner of a half-made box. */
    private static BlockPos pendingCorner;
    /** A shape's face being dragged slides along this (unit, away from the other face). */
    private static Vec3 faceDir;
    /** Where the line's first node was when a move drag began (a shape lands whole blocks from there). */
    private static Vec3 moveStart;
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
    /** The line whose gradient-angle tool is out, or null. It goes away when you do anything else. */
    private static LineShape angling;
    /** The slice the angle tool sits on (held still while a grip is dragged). */
    private static int angleSample;
    /** A line falling under gravity: the body, and the line as it was when let go. */
    private record Drop(LinePhysics body, LineShape.Pose from, int[] ticks) {}

    private static final java.util.Map<LineShape, Drop> drops = new java.util.IdentityHashMap<>();
    /** Where each line was before its last drop (Undo drop puts it back). Forgotten with the line. */
    private static final java.util.Map<LineShape, LineShape.Pose> beforeDrop = new java.util.WeakHashMap<>();
    /** This tick is a painting mode with Gradient picked: lines show a cog for their gradient options. */
    private static boolean paintOptions;

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
        return pendingStart != null || pendingCorner != null;
    }

    /** "Shape" or "Line", for messages about {@code l}. */
    static String nm(LineShape l) {
        return l.shape ? "Shape" : "Line";
    }

    /** Whether there are any lines (not shapes) / any shapes. */
    static boolean anyShape(boolean shape) {
        for (LineShape l : lines) {
            if (l.shape == shape) return true;
        }
        return false;
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
        pendingCorner = null;
        aimed = null;
        dragLine = null;
        rotating = null;
        angling = null;
        sketching = null;
        stroke = 0;
        drops.clear();
    }

    /** Delete a line (MMB, or the toolbar's bin). */
    static void remove(LineShape l) {
        lines.remove(l);
        if (selected == l) selected = null;
        if (aimed != null && aimed.line == l) aimed = null;
        if (dragLine == l) dragLine = null;
        if (rotating == l) rotating = null;
        if (angling == l) angling = null;
        if (sketching == l) sketching = null;
        drops.remove(l);
        MarkerText.say(nm(l) + " removed");
        ShapeMarkers.saveLines();
    }

    // ---- tick ---------------------------------------------------------------------------------

    /**
     * Find the line control (or line body) under the crosshair. Lines are only editable in Line
     * mode; elsewhere they just show and constrain painting (no aiming, dragging or deleting).
     */
    static void updateAim(Minecraft mc, PlacementMode pm, boolean handles) {
        boolean create = pm.isMarker(); // lines and shapes are edited in either marker mode
        paintOptions = handles && gradientPainting(pm);
        if (pm != PlacementMode.MARKER_LINE) pendingStart = null;
        if (pm != PlacementMode.MARKER_SHAPE) pendingCorner = null;
        if (!handles || (!create && !paintOptions && !debugHold)) {
            if (dragLine != null && !debugHold) { // released by leaving: finish the drag properly
                snapOnRelease(dragLine);
                ShapeMarkers.saveLines();
            }
            aimed = null;
            dragLine = null;
            angling = null;
            endSketch();
            return;
        }
        if (!create) endSketch();
        if (sketching != null && (!lines.contains(sketching) || sketching.profile != LineProfile.CUSTOM)) endSketch();
        if (angling != null && !lines.contains(angling)) angling = null;
        // The move / cog / rotate cluster follows where you stand (held by a drag or open toolbar).
        Vec3 eye = Raycast.eye(mc);
        for (LineShape l : lines) l.aimControls(eye, dragLine == l || MarkerToolbar.isOpen(l));
        if (!create && !debugHold) {
            computePaintAim(mc);
            return;
        }
        computeAim(mc);
        if (aimed != null && dragLine == null) {
            if (selected != aimed.line) selNode = -1;
            selected = aimed.line;
            if (aimed.kind == Kind.NODE) selNode = aimed.index;
        } else if (aimed == null && dragLine == null) {
            // Looking into a tube or shape selects it (its handles come out), unless the one
            // already selected is in the way of the crosshair too.
            LineShape into = tubeToSelect(mc);
            if (into != null && into != selected) {
                selected = into;
                selNode = -1;
            }
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
        boolean create = pm.isMarker();
        if (!create && !debugHold) return paintOptions && paintTick(mc, owns, attack, attackEdge, useEdge);

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
        if (stroke != 0) {
            // A sketch stroke: keeps drawing (or rubbing out) under the crosshair while held.
            if (sketching != null && (stroke == 3 ? use : attack)) {
                sketchAt(mc, stroke != 1);
            } else {
                stroke = 0;
                saveNeeded = true;
            }
            consumed = true;
        }
        if (!owns) {
            if (saveNeeded) ShapeMarkers.saveLines();
            return consumed;
        }

        if (attackEdge && dragLine == null) {
            // Doing anything else puts the gradient-angle tool away.
            if (aimed == null || !isGrip(aimed.kind)) angling = null;
            if (aimed != null && aimed.kind != Kind.COG) { // the cog is ShapeMarkers' (toolbar)
                consumed = true;
                ShapeMarkers.touch(aimed.line);
                switch (aimed.kind) {
                    case BODY -> {
                        long now = System.currentTimeMillis();
                        if (aimed.line.shape) {
                            // a shape has no nodes to add
                        } else if (lastBodyLine == aimed.line && now - lastBodyClickMs < DOUBLE_CLICK_MS) {
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
                        if (aimed.line.shape) {
                            beginDrag(mc, aimed, false); // a face: drag changes the height
                        } else if (lastNodeLine == aimed.line && lastNodeIndex == aimed.index
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
                                ? nm(aimed.line) + " rotate on — drag a ring to turn it · " + MarkerText.mod() + " 15° steps"
                                : nm(aimed.line) + " rotate off");
                    }
                    case GIMBAL -> beginTurn(mc, aimed);
                    case GRAD_T, GRAD_B -> beginAngle(aimed);
                    default -> beginDrag(mc, aimed, false);
                }
            } else if (aimed == null && sketching != null) {
                // Editing a drawn profile: clicks on nothing draw it (starting on a drawn cell rubs out).
                consumed = true;
                BlockPos at = sketchCell(mc, sketching.drawn);
                stroke = at != null && sketching.drawn != null && sketching.drawn.has(at) ? 2 : 1;
                sketchAt(mc, stroke == 2);
            } else if (aimed == null) {
                consumed = true;
                if (pm == PlacementMode.MARKER_SHAPE) shapeClick(mc);
                else if (pm == PlacementMode.MARKER_LINE) creationClick(mc);
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
        } else if (useEdge && dragLine == null && aimed == null && sketching != null) {
            stroke = 3; // RMB rubs out
            sketchAt(mc, true);
            consumed = true;
        } else if (useEdge && pendingStart != null) {
            pendingStart = null;
            MarkerText.say("Line cancelled");
            consumed = true;
        } else if (useEdge && pendingCorner != null) {
            pendingCorner = null;
            MarkerText.say("Shape cancelled");
            consumed = true;
        }
        if (pickEdge && aimed == null && dragLine == null && selected != null && selected.shape
                && tubeToSelect(mc) == selected) {
            // A shape has no line to aim at: MMB anywhere in the selected one deletes it.
            remove(selected);
            consumed = true;
        }
        if (pickEdge && aimed != null && dragLine == null && aimed.kind != Kind.GIMBAL && !isGrip(aimed.kind)) {
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

    /** True for the gradient-angle tool's two grips. */
    private static boolean isGrip(Kind k) {
        return k == Kind.GRAD_T || k == Kind.GRAD_B;
    }

    /** A painting mode (not a marker mode) with Gradient picked: when lines offer their gradient options. */
    private static boolean gradientPainting(PlacementMode pm) {
        return pm.places() && co.fax.wang.config.ConfigManager.get().activePaintType == co.fax.wang.PaintType.GRADIENT;
    }

    /**
     * Lines while painting with Gradient: nothing can be reshaped, but the gradient-angle grips
     * drag, and any other click (painting included) puts the angle tool away. True when a line
     * used this tick's clicks.
     */
    private static boolean paintTick(Minecraft mc, boolean owns, boolean attack, boolean attackEdge, boolean useEdge) {
        if (dragLine != null) {
            if (attack) {
                applyDrag(mc);
            } else {
                dragLine = null;
                ShapeMarkers.saveLines();
            }
            return true;
        }
        if (!owns) return false;
        if (attackEdge && aimed != null && isGrip(aimed.kind)) {
            beginAngle(aimed);
            return true;
        }
        if ((attackEdge || useEdge) && angling != null) {
            angling = null;
            MarkerText.say("Gradient angle put away");
        }
        return false;
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
            case WIDTH, DEPTH -> {
                // Even again: as deep as it is wide (the handle clicked keeps its size).
                double e = l.curve.nodes.get(0).r + 0.5;
                if (a.kind == Kind.DEPTH) l.setAllRadii(Math.max(LineShape.SHAPE_MIN_RADIUS, e * l.stretch - 0.5));
                l.stretch = 1;
                MarkerText.say("Shape: width and depth made equal");
            }
            case WALL -> {
                l.wall = 1;
                MarkerText.say("Shape: wall back to 1 block");
            }
            default -> {
                return false;
            }
        }
        l.markDirty();
        ShapeMarkers.touch(l);
        return true;
    }

    /** Set a shape's height by moving face {@code node} (the other face stays). */
    private static void setHeight(LineShape l, int node, double h) {
        List<LineCurve.Node> ns = l.curve.nodes;
        LineCurve.Node n = ns.get(node), o = ns.get(ns.size() - 1 - node);
        Vec3 d = n.p.subtract(o.p);
        d = d.lengthSqr() < 1e-9 ? new Vec3(0, node == 0 ? -1 : 1, 0) : d.normalize();
        n.p = o.p.add(d.scale(Math.max(MIN_HEIGHT, h)));
    }

    /** A shape's wall is at least a block and never more than fills it. */
    private static double clampWall(LineShape l, double w) {
        double e = l.curve.nodes.get(0).r + 0.5;
        return Math.max(1, Math.min(Math.max(1, Math.min(e, e * l.stretch)), w));
    }

    /** "5 × 3 × 8" (width, depth, height in blocks). */
    static String sizeOf(LineShape l) {
        double e = l.curve.nodes.get(0).r + 0.5;
        return fmt(2 * e) + " × " + fmt(2 * e * l.stretch) + " × " + fmt(l.height());
    }

    /**
     * Mouse wheel. While dragging: push the dragged point a block further from / nearer to you.
     * Aiming a node (or its size cube) without dragging: the modifier grows / shrinks that
     * node's radius half a block a notch; plain scroll pushes the node itself.
     */
    static boolean onScroll(double deltaY) {
        Minecraft mc = Minecraft.getInstance();
        if (dragLine == null && aimed != null && aimed.line.shape && (aimed.kind == Kind.NODE
                || aimed.kind == Kind.WIDTH || aimed.kind == Kind.DEPTH || aimed.kind == Kind.WALL)) {
            // A shape: scroll a face to make it a block taller / shorter, a wall handle to thicken
            // it; the modifier (or a side handle) grows / shrinks the whole profile.
            LineShape l = aimed.line;
            int step = deltaY > 0 ? 1 : -1;
            if (aimed.kind == Kind.WALL) {
                l.wall = clampWall(l, Math.rint(l.wall) + step);
                MarkerText.say("Shape: wall " + fmt(l.wall) + " thick");
            } else if (aimed.kind == Kind.NODE && !Gradient.modifierDown()) {
                setHeight(l, aimed.index, Math.rint(l.height()) + step);
                MarkerText.say("Shape: " + sizeOf(l));
            } else {
                l.setAllRadii(Math.max(LineShape.SHAPE_MIN_RADIUS,
                        Math.min(LineShape.MAX_RADIUS, l.curve.nodes.get(0).r + 0.5 * step)));
                l.wall = clampWall(l, l.wall);
                MarkerText.say("Shape: " + sizeOf(l));
            }
            l.markDirty();
            ShapeMarkers.touch(l);
            ShapeMarkers.saveLines();
            return true;
        }
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
        if (dragKind == Kind.GIMBAL || isGrip(dragKind)) return true; // a ring drag has no depth to push
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

    // ---- drawn profile ----------------------------------------------------------------------------

    /** Stop editing a drawn profile (keeping what was drawn). */
    private static void endSketch() {
        if (sketching == null) return;
        sketching = null;
        if (stroke != 0) ShapeMarkers.saveLines();
        stroke = 0;
    }

    /**
     * The cell a sketch click lands on. A picture already begun: where the crosshair crosses its
     * plane. A new one: the cell in front of the aimed face (or at Marker reach in the open).
     */
    private static BlockPos sketchCell(Minecraft mc, ProfileDrawing d) {
        if (d != null && !d.isEmpty()) {
            // Aimed at a block: the cell in front of that face when it is in the picture's layer
            // (drawing along a wall or floor lands exactly where you point), or the block itself
            // when it stands in the layer.
            BlockHitResult hit = Raycast.aimedBlock(mc);
            if (hit != null) {
                BlockPos front = hit.getBlockPos().relative(hit.getDirection());
                if (d.onPlane(front)) return front;
                if (d.onPlane(hit.getBlockPos())) return hit.getBlockPos();
            }
            BlockPos c = d.cellOnPlane(Raycast.eye(mc), Raycast.look(mc));
            return c != null && Vec3.atCenterOf(c).distanceTo(Raycast.eye(mc)) <= Raycast.REACH ? c : null;
        }
        return BlockPos.containing(aimPoint(mc));
    }

    /** Draw (or rub out) the cell under the crosshair on the sketched line's picture; the tube follows. */
    private static void sketchAt(Minecraft mc, boolean erase) {
        LineShape l = sketching;
        if (l.drawn == null) l.drawn = new ProfileDrawing();
        ProfileDrawing d = l.drawn;
        double was = d.natural();
        boolean changed;
        if (d.isEmpty()) {
            if (erase) return;
            // The first cell fixes the plane: flat against the face clicked, or facing you in the open.
            BlockHitResult hit = Raycast.aimedBlock(mc);
            d.start(BlockPos.containing(aimPoint(mc)),
                    hit != null ? hit.getDirection().getAxis() : Gizmo.dominantAxis(Raycast.look(mc)),
                    Raycast.look(mc));
            changed = true;
        } else {
            BlockPos c = sketchCell(mc, d);
            if (c == null) return;
            changed = erase ? d.remove(c) : d.add(c);
        }
        if (!changed) return;
        // The tube keeps its proportion to the picture: 1 cell = 1 block until a node is resized.
        double now = d.natural();
        for (LineCurve.Node n : l.curve.nodes) {
            n.r = was > 0 && now > 0 ? Math.min(LineShape.MAX_RADIUS, (n.r + 0.5) * now / was - 0.5) : Math.max(0, now - 0.5);
        }
        l.markDirty();
        ShapeMarkers.touch(l);
    }

    /** What clicks do while a drawn profile is being edited (the action bar), or null. */
    static String sketchHint() {
        if (sketching == null) return null;
        ProfileDrawing d = sketching.drawn;
        return d == null || d.isEmpty()
                ? "Draw the profile · LMB a block face to start (it stays flat against that face)"
                : "Profile · " + d.size() + " blocks · LMB draw · RMB rub out · pencil when done";
    }

    private static void drawSketch(SubmitNodeCollector col, PoseStack ps, Vec3 cam, Minecraft mc) {
        ProfileDrawing d = sketching.drawn;
        List<BlockPos> cells = d == null ? List.of() : d.worldCells();
        BlockPos next = aimed == null && !ShapeMarkers.controlAimed() ? sketchCell(mc, d) : null;
        Gizmo.twoPass(col, ps, cam, e -> {
            if (!e.ghost()) {
                for (BlockPos c : cells) Gizmo.cube(e, Vec3.atCenterOf(c), 0.46, SKETCH);
            }
            for (BlockPos c : cells) {
                if (e.ghost()) Gizmo.cube(e, Vec3.atCenterOf(c), 0.08, SKETCH_NEXT);
            }
            if (next != null) {
                Vec3 lo = new Vec3(next.getX(), next.getY(), next.getZ());
                boxFrame(e, lo, lo.add(1, 1, 1), 0.02, SKETCH_NEXT);
            }
        });
    }

    /**
     * Shape mode's click on nothing: the first corner, then the opposite corner. The two make a
     * box (its toolbar turns it into anything else), standing upright.
     */
    private static void shapeClick(Minecraft mc) {
        BlockPos cell = BlockPos.containing(aimPoint(mc));
        if (pendingCorner == null) {
            pendingCorner = cell;
            MarkerText.step("Shape", 2, 2, "LMB the opposite corner");
            return;
        }
        LineShape l = LineShape.box(pendingCorner, cell);
        lines.add(l);
        selected = l;
        selNode = -1;
        pendingCorner = null;
        ShapeMarkers.touch(l);
        ShapeMarkers.saveLines();
        MarkerText.say("Shape set · " + sizeOf(l) + " — drag its faces and sides, LMB its cog for options");
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

    /**
     * The gradient buttons, shared by both toolbars: the four directions. (Seeing the gradient
     * on the line and setting its angle live on the Rendering page.)
     */
    private static void gradientButtons(LineShape l, List<MarkerToolbar.Button> row) {
        LineGradient[] gs = LineGradient.values();
        for (int i = 0; i < gs.length; i++) {
            LineGradient g = gs[i];
            String what = switch (g) {
                case ACROSS -> l.shape ? "from side to side (Angle sets which side is the top)"
                        : "across the line, bottom to top of every slice (Angle sets which way is top)";
                case ALONG -> l.shape ? "from the bottom face to the top face" : "from the start node to the end node";
                case INSIDE_OUT -> l.shape ? "from the middle out to the skin" : "from the line out to the skin";
                case OUTSIDE_IN -> l.shape ? "from the skin in to the middle" : "from the skin in to the line";
            };
            row.add(new MarkerToolbar.Button(MarkerToolbar.pixels(GRADIENT_GLYPHS[i], 0.08), l.gradient == g, false,
                    "Gradient " + g.label.toLowerCase(java.util.Locale.ROOT) + ": " + what
                            + (l.gradient == g ? " (on)" : ""), () -> {
                        // Only changes how a later Gradient fill runs — the tube itself is unchanged.
                        l.gradient = g;
                        if (g != LineGradient.ACROSS && angling == l) angling = null;
                        changed(l, nm(l) + " gradient: " + g.label.toLowerCase(java.util.Locale.ROOT));
                    }));
        }
    }

    /**
     * The gradient-angle toggle (on the Rendering page, beside Show as gradient): brings out the
     * pink plane with its T / B grips, or puts it away.
     */
    static MarkerToolbar.Button angleButton(LineShape l) {
        return new MarkerToolbar.Button(MarkerToolbar.pixels(ANGLE_GLYPH, 0.085), angling == l, false,
                "Gradient angle: choose which way is top for the across gradient" + (angling == l ? " (on)" : ""), () -> {
                    if (angling == l) {
                        angling = null;
                        MarkerText.say("Gradient angle put away");
                        return;
                    }
                    angling = l;
                    angleSample = angleSampleFor(l);
                    if (l.gradient != LineGradient.ACROSS) {
                        l.gradient = LineGradient.ACROSS; // the angle only means something across the line
                        ShapeMarkers.saveLines();
                    }
                    ShapeMarkers.touch(l);
                    MarkerText.say("Gradient angle — drag T (top) or B (bottom) round the ring · "
                            + MarkerText.mod() + " 15° steps");
                });
    }

    /**
     * The toolbar a line's cog opens while painting with Gradient: how the gradient runs through
     * it, and its Rendering page (the line can't be reshaped from here). Null when that isn't what is going on.
     */
    static MarkerToolbar.Spec paintSpec(LineShape l, PlacementMode pm) {
        if (!lines.contains(l) || !gradientPainting(pm)) return null;
        if (MarkerToolbar.stylePage()) {
            Vec3[] sb = l.bounds();
            return new MarkerToolbar.Spec(null, StylePage.rows(l, l.style, TUBE_FILL, TUBE_EDGE, () -> {
                ShapeMarkers.touch(l);
                ShapeMarkers.saveLines();
            }), cluster(l)[1], sb[0], sb[1], List.of());
        }
        List<MarkerToolbar.Button> row = new ArrayList<>();
        gradientButtons(l, row);
        row.add(StylePage.openButton(l.style));
        row.add(helpButton(l));
        Vec3[] b = l.bounds();
        return new MarkerToolbar.Spec(null, List.of(row), cluster(l)[1], b[0], b[1], List.of());
    }

    /** The Physics page: Back · Gravity (drop the line, or stop it falling) · Undo drop. */
    private static List<MarkerToolbar.Button> physicsRow(LineShape l) {
        List<MarkerToolbar.Button> row = new ArrayList<>();
        row.add(new MarkerToolbar.Button(MarkerToolbar.pixels(BACK_GLYPH, 0.085), false, false,
                "Back to the options", () -> MarkerToolbar.showPhysics(false)));
        boolean falling = drops.containsKey(l);
        row.add(new MarkerToolbar.Button(MarkerToolbar.pixels(GRAVITY_GLYPH, 0.085), falling, false,
                falling ? "Gravity (on): it is falling · LMB stop it where it is"
                        : "Gravity: let it fall, tumble and settle where it would come to rest "
                                + "(buried, it pops up first)",
                () -> {
                    if (drops.containsKey(l)) stopDrop(l, nm(l) + ": gravity off — left where it was");
                    else startDrop(Minecraft.getInstance(), l);
                }));
        row.add(new MarkerToolbar.Button(MarkerToolbar.REDO, false, false,
                beforeDrop.containsKey(l) ? "Undo drop: put it back where it was before gravity"
                        : "Undo drop: nothing to undo yet", () -> undoDrop(l)));
        return row;
    }

    // ---- gravity ---------------------------------------------------------------------------------

    /** What gravity collides with: any block with a collision shape (so not water, grass or air). */
    private static LinePhysics.Solid ground(Minecraft mc) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        return (x, y, z) -> {
            if (mc.level == null) return false;
            at.set(x, y, z);
            return !mc.level.getBlockState(at).getCollisionShape(mc.level, at).isEmpty();
        };
    }

    /** Let {@code l} go: remember where it was, lift it clear if it is buried, and start it falling. */
    private static void startDrop(Minecraft mc, LineShape l) {
        LinePhysics body = LinePhysics.of(l);
        if (body == null || mc.level == null) return;
        LineShape.Pose from = l.pose();
        beforeDrop.put(l, from);
        int rose = body.popUp(ground(mc), POP_UP_LIMIT);
        drops.put(l, new Drop(body, from, new int[1]));
        if (rotating == l) rotating = null;
        if (angling == l) angling = null;
        ShapeMarkers.touch(l);
        MarkerText.say(rose > 0 ? nm(l) + ": gravity on — lifted " + rose + " blocks clear of the ground, now falling"
                : nm(l) + ": gravity on — falling");
    }

    /** End {@code l}'s fall where it is (no-op when it isn't falling); {@code msg} is said if given. */
    private static void stopDrop(LineShape l, String msg) {
        if (drops.remove(l) == null) return;
        ShapeMarkers.saveLines();
        if (msg != null) MarkerText.say(msg);
    }

    private static void undoDrop(LineShape l) {
        LineShape.Pose from = beforeDrop.remove(l);
        if (from == null) return;
        drops.remove(l);
        l.turnFrom(from, l.curve.centre(), new Vec3(0, 1, 0), 0);
        changed(l, nm(l) + ": put back where it was before gravity");
    }

    /** Put {@code l} where its falling body is now. */
    private static void applyDrop(LineShape l, Drop d) {
        l.turnFrom(d.from, d.body.start, d.body.axis(), d.body.angle());
        l.curve.translate(d.body.shift());
        l.markDirty();
    }

    /** Every falling line moves on a tick; one that has settled, or can't, stops. */
    static void tickPhysics(Minecraft mc) {
        if (drops.isEmpty() || mc.level == null || mc.isPaused()) return;
        LinePhysics.Solid world = ground(mc);
        double floor = mc.level.getMinY() - 16;
        for (var it = drops.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            LineShape l = e.getKey();
            Drop d = e.getValue();
            if (!lines.contains(l)) {
                it.remove();
                continue;
            }
            d.body.tick(world);
            if (d.body.bottom() < floor) {
                // Nothing under it (the void, or chunks that aren't loaded): don't lose the line.
                it.remove();
                l.turnFrom(d.from, l.curve.centre(), new Vec3(0, 1, 0), 0);
                beforeDrop.remove(l);
                ShapeMarkers.saveLines();
                MarkerText.say(nm(l) + ": nothing to land on — put back where it was");
                continue;
            }
            applyDrop(l, d);
            boolean rest = d.body.atRest();
            if (rest || ++d.ticks[0] >= DROP_MAX_TICKS) {
                it.remove();
                ShapeMarkers.saveLines();
                MarkerText.say(rest ? nm(l) + ": came to rest" : nm(l) + ": still moving after 45 s — left where it is");
            }
        }
    }

    /** Dev-only ({@link co.fax.wang.DebugScript}'s {@code drop}): start gravity on the last line made. */
    public static boolean debugDrop() {
        if (lines.isEmpty()) return false;
        startDrop(Minecraft.getInstance(), lines.get(lines.size() - 1));
        return true;
    }

    /** The line's toolbar: profile · shell | solid, then the gradients · preview · angle · flip, then rendering · delete · help. */
    static MarkerToolbar.Spec toolbarSpec(LineShape l) {
        if (!lines.contains(l)) return null;
        List<MarkerToolbar.Button> top = new ArrayList<>(), middle = new ArrayList<>(), bottom = new ArrayList<>();
        List<List<MarkerToolbar.Button>> rows = List.of(top, middle, bottom);
        if (MarkerToolbar.stylePage()) {
            rows = StylePage.rows(l, l.style, TUBE_FILL, TUBE_EDGE, () -> {
                ShapeMarkers.touch(l);
                ShapeMarkers.saveLines();
            });
        } else if (MarkerToolbar.physicsPage()) {
            rows = List.of(physicsRow(l));
        }
        if (l.shape) shapeButtons(l, top);
        for (LineProfile p : l.shape ? new LineProfile[0] : PROFILES) {
            MarkerToolbar.Icon icon = p == LineProfile.NONE ? MarkerToolbar.LINE_BARE
                    : p == LineProfile.CUSTOM ? MarkerToolbar.pixels(DRAWN_GLYPH, 0.085) : MarkerToolbar.outline(p.outline(1));
            String name = p == LineProfile.NONE ? "Bare line"
                    : p == LineProfile.CUSTOM ? "Drawn profile: sweep a shape you draw yourself" : cap(p.name()) + " tube";
            top.add(new MarkerToolbar.Button(icon, l.profile == p, false,
                    name + (l.profile == p ? " (on)" : ""), () -> {
                        l.profile = p;
                        l.markDirty();
                        if (p == LineProfile.CUSTOM && (l.drawn == null || l.drawn.isEmpty())) {
                            // Nothing drawn yet: straight into drawing it.
                            sketching = l;
                            ShapeMarkers.touch(l);
                            ShapeMarkers.saveLines();
                            MarkerText.say("Draw the profile — LMB blocks on one flat face, RMB rubs out; LMB the pencil when done");
                            return;
                        }
                        if (p != LineProfile.CUSTOM && sketching == l) sketching = null;
                        changed(l, "Line: " + (p == LineProfile.CUSTOM ? "drawn profile" : name.toLowerCase(java.util.Locale.ROOT)));
                    }));
        }
        if (!l.shape && l.profile == LineProfile.CUSTOM) {
            top.add(new MarkerToolbar.Button(MarkerToolbar.pixels(PENCIL_GLYPH, 0.085), sketching == l, false,
                    sketching == l ? "Edit profile (on): LMB draws, RMB rubs out · LMB here when done"
                            : "Edit profile: show the drawn shape and change it", () -> {
                        sketching = sketching == l ? null : l;
                        ShapeMarkers.touch(l);
                        MarkerText.say(sketching == l
                                ? "Editing the profile — LMB drag draws, RMB drag rubs out, where it was drawn"
                                : "Profile set");
                    }));
        }
        String what = l.shape ? "shape" : "tube";
        top.add(new MarkerToolbar.Button(MarkerToolbar.HOLLOW, !l.solid, false,
                (l.shape ? "Hollow: only the shape's walls (drag the wall handle to thicken them)"
                        : "Shell: only the tube's skin") + (!l.solid ? " (on)" : ""), () -> {
                    l.solid = false;
                    l.markDirty();
                    changed(l, nm(l) + ": hollow " + what);
                }));
        top.add(new MarkerToolbar.Button(MarkerToolbar.DISC, l.solid, false,
                (l.shape ? "Filled: the whole shape" : "Solid: the whole tube") + (l.solid ? " (on)" : ""), () -> {
                    l.solid = true;
                    l.markDirty();
                    changed(l, nm(l) + ": filled " + what);
                }));
        if (!l.solid && !l.ball && l.profile != LineProfile.NONE) {
            top.add(new MarkerToolbar.Button(MarkerToolbar.pixels(CAP_GLYPH, 0.085), l.capped, false,
                    l.capped ? "Capped (on): the ends are closed · LMB open them" : "Capped: close the open ends", () -> {
                        l.capped = !l.capped;
                        l.markDirty();
                        changed(l, nm(l) + (l.capped ? ": ends capped" : ": ends open"));
                    }));
        }
        gradientButtons(l, middle);
        middle.add(new MarkerToolbar.Button(MarkerToolbar.FLIP, false, false,
                l.shape ? "Flip: swap the bottom and top faces" : "Flip: swap the start and end nodes", () -> {
                    l.reverse();
                    changed(l, nm(l) + " flipped — it now starts at the other end");
                }));
        bottom.add(StylePage.openButton(l.style));
        bottom.add(new MarkerToolbar.Button(MarkerToolbar.pixels(PHYSICS_GLYPH, 0.085), drops.containsKey(l), false,
                "Physics: drop it onto the ground with gravity", () -> MarkerToolbar.showPhysics(true)));
        bottom.add(digButton(l));
        bottom.add(new MarkerToolbar.Button(MarkerToolbar.BIN, false, true,
                "Delete this " + nm(l).toLowerCase(java.util.Locale.ROOT) + " (same as MMB)", () -> remove(l)));
        bottom.add(helpButton(l));
        Vec3[] b = l.bounds();
        return new MarkerToolbar.Spec(null, rows, cluster(l)[1], b[0], b[1], controlsOf(l));
    }

    /** Dig mode, one button that steps Off → Dig inside → Dig outside → Off. */
    private static MarkerToolbar.Button digButton(LineShape l) {
        String it = nm(l).toLowerCase(java.util.Locale.ROOT);
        String label = switch (l.dig) {
            case OFF -> "Dig: off (you dig as normal) · LMB dig only inside this " + it;
            case INSIDE -> "Dig inside (on): within reach of it, only blocks inside this " + it + " break · LMB switch to Dig outside";
            case OUTSIDE -> "Dig outside (on): blocks inside this " + it + " can't be broken · LMB turn Dig off";
        };
        return new MarkerToolbar.Button(MarkerToolbar.pixels(l.dig == LineShape.Dig.OUTSIDE ? DIG_OUT_GLYPH : DIG_GLYPH, 0.085),
                l.dig != LineShape.Dig.OFF, false, label, () -> {
                    l.dig = LineShape.Dig.values()[(l.dig.ordinal() + 1) % 3];
                    changed(l, nm(l) + switch (l.dig) {
                        case OFF -> ": dig off — you dig as normal";
                        case INSIDE -> ": dig inside — near it, only blocks inside it break";
                        case OUTSIDE -> ": dig outside — blocks inside it can't be broken";
                    });
                });
    }

    /** Dev-only ({@link co.fax.wang.DebugScript}'s {@code dig}): set the last marker's dig mode. */
    public static boolean debugDig(String mode) {
        if (lines.isEmpty()) return false;
        lines.get(lines.size() - 1).dig = LineShape.Dig.valueOf(mode.toUpperCase(java.util.Locale.ROOT));
        return true;
    }

    private static MarkerToolbar.Button helpButton(LineShape l) {
        return new MarkerToolbar.Button(MarkerToolbar.QUESTION, false, false,
                l.shape ? "Help: how shapes work" : "Help: how lines work", () -> Minecraft.getInstance()
                        .setScreenAndShow(co.fax.wang.GradientScreen.atHelpTopic(l.shape ? "Marker shape" : "Marker line")));
    }

    /** A shape's kinds: Box · Cylinder · Triangle · Star · Sphere (each keeps the size it has). */
    private static void shapeButtons(LineShape l, List<MarkerToolbar.Button> row) {
        Object[][] kinds = {
                {"Box", LineProfile.SQUARE, false, MarkerToolbar.outline(LineProfile.SQUARE.outline(1))},
                {"Cylinder", LineProfile.CIRCLE, false, MarkerToolbar.outline(LineProfile.CIRCLE.outline(1))},
                {"Triangle", LineProfile.TRIANGLE, false, MarkerToolbar.outline(LineProfile.TRIANGLE.outline(1))},
                {"Star", LineProfile.STAR, false, MarkerToolbar.outline(LineProfile.STAR.outline(1))},
                {"Sphere", LineProfile.CIRCLE, true, MarkerToolbar.pixels(SPHERE_GLYPH, 0.085)}};
        for (Object[] k : kinds) {
            String name = (String) k[0];
            LineProfile p = (LineProfile) k[1];
            boolean ball = (Boolean) k[2];
            boolean on = l.profile == p && l.ball == ball;
            row.add(new MarkerToolbar.Button((MarkerToolbar.Icon) k[3], on, false, name + (on ? " (on)" : ""), () -> {
                l.profile = p;
                l.ball = ball;
                l.markDirty();
                changed(l, "Shape: " + name.toLowerCase(java.util.Locale.ROOT));
            }));
        }
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
            if (!l.shape && l.profile != LineProfile.NONE && cubesShown(l, i)) {
                out.add(l.sizeNode(i));
                out.add(l.twistNode(i));
            }
        }
        if (l.shape) {
            out.add(l.widthNode());
            out.add(l.depthNode());
            if (!l.solid) out.add(l.wallNode());
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
        if (isGrip(aimed.kind)) {
            String which = aimed.kind == Kind.GRAD_T ? "top" : "bottom";
            return dragLine == l
                    ? "Gradient " + which + " · angle " + Math.round(Math.toDegrees(l.gradAngle)) + "° · "
                            + MarkerText.mod() + " 15° steps"
                    : "Gradient " + which + " · drag round the ring to set which way the gradient runs · "
                            + MarkerText.mod() + " 15° steps";
        }
        if (paintOptions) return aimed.kind == Kind.COG ? "Cog · LMB gradient options for this line" : null;
        String mod = MarkerText.mod(), alt = MarkerText.axis();
        int last = l.curve.nodes.size() - 1;
        if (l.shape) {
            String del = " · MMB delete shape";
            return switch (aimed.kind) {
                case NODE -> (aimed.index == 0 ? "Bottom face" : "Top face") + " · " + sizeOf(l)
                        + " · drag or scroll change the height · " + mod + "+scroll resize" + del;
                case WIDTH -> "Width · " + sizeOf(l) + " · drag resize · " + mod + "+drag keep its proportions · RMB make even" + del;
                case DEPTH -> "Depth · " + sizeOf(l) + " · drag resize · " + mod + "+drag keep its proportions · RMB make even" + del;
                case WALL -> "Wall · " + fmt(l.wall) + " thick · drag or scroll change · RMB reset" + del;
                case MOVE -> "Move · drag move · " + alt + " axis lock" + del;
                case COG -> "Cog · LMB options" + del;
                case ROTATE -> rotating == l ? "Rotate · on · LMB turn the rings off" + del
                        : "Rotate · LMB show the rings that turn the whole shape" + del;
                case GIMBAL -> dragLine != l
                        ? "Rotate ring · drag turn the shape · " + alt + "+drag tilt this ring only · " + mod + " 15° steps"
                        : (turnTilt ? "Rotate ring · tilted " : "Rotate ring · turned ")
                                + Math.round(Math.toDegrees(turnBy)) + "° · " + mod + " 15° steps";
                case BODY -> "Shape · " + sizeOf(l) + del;
                default -> null;
            };
        }
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
            default -> null; // (grips answered above; the rest are a shape's)
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
        stopDrop(a.line, null); // taking hold of a falling line catches it
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
        List<LineCurve.Node> ns = a.line.curve.nodes;
        moveStart = ns.get(0).p;
        if (a.line.shape && a.kind == Kind.NODE) {
            Vec3 d = ns.get(a.index).p.subtract(ns.get(ns.size() - 1 - a.index).p);
            faceDir = d.lengthSqr() < 1e-9 ? new Vec3(0, a.index == 0 ? -1 : 1, 0) : d.normalize();
        }
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
        if (isGrip(dragKind)) {
            applyAngle(mc);
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
        boolean lockable = (dragKind == Kind.NODE && !dragLine.shape) || dragKind == Kind.MOVE
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
            case NODE -> {
                if (l.shape) {
                    // A face slides along the shape's own axis: only the height changes.
                    LineCurve.Node o = ns.get(ns.size() - 1 - dragIndex);
                    double h = Math.max(MIN_HEIGHT, target.subtract(o.p).dot(faceDir));
                    ns.get(dragIndex).p = o.p.add(faceDir.scale(h));
                } else {
                    ns.get(dragIndex).p = target;
                }
            }
            case WIDTH, DEPTH, WALL -> {
                int mid = l.midSample();
                Vec3 d = target.subtract(l.samples().get(mid).pos());
                Vec3[] f = l.rotatedFrame(mid);
                double e = ns.get(0).r + 0.5, ev = e * l.stretch;
                boolean both = Gradient.modifierDown(); // the modifier keeps the proportions
                double min = LineShape.SHAPE_MIN_RADIUS + 0.5, max = LineShape.MAX_RADIUS + 0.5;
                if (dragKind == Kind.WALL) {
                    l.wall = clampWall(l, e - Math.abs(d.dot(f[0])));
                } else if (dragKind == Kind.WIDTH) {
                    double ne = Math.max(min, Math.min(max, Math.abs(d.dot(f[0]))));
                    l.setAllRadii(ne - 0.5);
                    if (!both) l.stretch = Math.max(min, ev) / ne; // the depth stays as it is
                } else {
                    double nv = Math.max(min, Math.min(max, Math.abs(d.dot(f[1]))));
                    if (both) l.setAllRadii(Math.max(min, nv / l.stretch) - 0.5);
                    else l.stretch = nv / e;
                }
                l.wall = clampWall(l, l.wall);
            }
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
            case MOVE -> {
                // The held cluster goes with the line, so the move cube lands exactly on the target
                // (and an axis-locked move stays dead on its axis).
                Vec3 by = target.subtract(cluster(l)[0]);
                l.curve.translate(by);
                l.shiftHeld(by);
            }
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
        stopDrop(l, null);
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

    // ---- gradient angle -------------------------------------------------------------------------

    /**
     * Where the angle tool sits for {@code l}: the slice a little way along the line from its
     * control cluster (so the two never sit on top of each other), towards the longer end.
     */
    private static int angleSampleFor(LineShape l) {
        List<LineCurve.Sample> ss = l.samples();
        if (ss.isEmpty()) return 0;
        int at = l.controlSample();
        double s = ss.get(at).s(), total = ss.get(ss.size() - 1).s();
        double want = total - s >= s ? s + ANGLE_ALONG : s - ANGLE_ALONG;
        return l.sampleAtArc(Math.max(0, Math.min(total, want)));
    }

    /** The slice the angle tool is on right now: held while a grip is dragged, else following the cluster. */
    private static int angleSlice(LineShape l) {
        int last = l.samples().size() - 1;
        if (!(dragLine == l && isGrip(dragKind)) && !MarkerToolbar.isOpen(l)) angleSample = angleSampleFor(l);
        return Math.max(0, Math.min(last, angleSample));
    }

    private static double angleRadius(LineShape l, int i) {
        return l.skinAt(i) * (l.profile == LineProfile.SQUARE ? Math.sqrt(2) : 1) + ANGLE_MARGIN;
    }

    /** The T (top) or B (bottom) grip's position. */
    private static Vec3 gripPos(LineShape l, boolean top) {
        int i = angleSlice(l);
        Vec3 dir = l.sliceTop(i);
        return l.samples().get(i).pos().add(dir.scale(top ? angleRadius(l, i) : -angleRadius(l, i)));
    }

    /** Grab a gradient-angle grip: it follows the crosshair round the ring until release. */
    private static void beginAngle(Aim a) {
        angleSample = angleSlice(a.line);
        dragLine = a.line;
        dragKind = a.kind;
        dragIndex = 0;
        dragRadial = false;
        lockAxis = null;
        ShapeMarkers.touch(a.line);
    }

    /**
     * The grabbed grip goes to the spot on the ring nearest the crosshair; the gradient's top is
     * wherever T then is (B is always straight across from it).
     */
    private static void applyAngle(Minecraft mc) {
        LineShape l = dragLine;
        int i = Math.max(0, Math.min(l.samples().size() - 1, angleSample));
        LineCurve.Sample s = l.samples().get(i);
        double at = Gizmo.rayCircle(Raycast.eye(mc), Raycast.look(mc), s.pos(), s.tan(), angleRadius(l, i))[2];
        Vec3[] uv = Gizmo.planeAxes(s.tan());
        Vec3 d = uv[0].scale(Math.cos(at)).add(uv[1].scale(Math.sin(at)));
        double a = Math.atan2(d.dot(s.b()), d.dot(s.n()));
        if (dragKind == Kind.GRAD_B) a += Math.PI;
        if (Gradient.modifierDown()) a = Math.rint(a / TURN_STEP) * TURN_STEP;
        l.gradAngle = Math.IEEEremainder(a, 2 * Math.PI);
    }

    /**
     * The gradient-angle tool: a pink plane cutting the line at right angles, a ring round it,
     * and the T / B grips on the ring joined by a line — the gradient runs from B to T.
     */
    private static void drawAngleTool(SubmitNodeCollector col, PoseStack ps, Vec3 cam, LineShape l, Vec3[] axes) {
        int i = angleSlice(l);
        LineCurve.Sample s = l.samples().get(i);
        double r = angleRadius(l, i), w = 0.03 + r * 0.002;
        Vec3 c = s.pos(), t = gripPos(l, true), b = gripPos(l, false);
        Vec3[] uv = Gizmo.planeAxes(s.tan());
        Gizmo.twoPass(col, ps, cam, e -> {
            int n = 32;
            for (int k = 0; k < n; k++) {
                double a0 = 2 * Math.PI * k / n, a1 = 2 * Math.PI * (k + 1) / n;
                Vec3 p0 = c.add(uv[0].scale(Math.cos(a0) * r)).add(uv[1].scale(Math.sin(a0) * r));
                Vec3 p1 = c.add(uv[0].scale(Math.cos(a1) * r)).add(uv[1].scale(Math.sin(a1) * r));
                Gizmo.quad(e, ANGLE_PLANE, c, p0, p1, p1);
                if (!e.ghost()) Gizmo.quad(e, ANGLE_PLANE, p1, p1, p0, c); // seen from either side
            }
            Gizmo.circle(e, c, s.tan(), r, w, ANGLE);
            Gizmo.segmentRibbon(e, b, t, w, ANGLE);
            Gizmo.controlCube(e, t, CTRL_HALF, isAimed(l, Kind.GRAD_T, 0) ? Gizmo.AIMED : ANGLE, Gizmo.T_ICON, axes);
            Gizmo.controlCube(e, b, CTRL_HALF, isAimed(l, Kind.GRAD_B, 0) ? Gizmo.AIMED : ANGLE, Gizmo.B_ICON, axes);
        });
    }

    /**
     * Dev-only ({@link co.fax.wang.DebugScript}'s {@code aim grip T|B DEG}): where the T or B grip
     * of the line whose angle tool is out would be with the gradient angle at {@code deg} (NaN =
     * where it is now), or null when no angle tool is out.
     */
    public static Vec3 debugGripPoint(boolean top, double deg) {
        if (angling == null) return null;
        if (Double.isNaN(deg)) return gripPos(angling, top);
        double keep = angling.gradAngle;
        angling.gradAngle = Math.toRadians(deg);
        Vec3 p = gripPos(angling, top);
        angling.gradAngle = keep;
        return p;
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
        if (l.shape) {
            // A shape keeps whole-block sizes: its height, its width and depth, its wall; and a
            // move lands it a whole number of blocks from where it was.
            switch (dragKind) {
                case NODE -> setHeight(l, dragIndex, Math.rint(l.height()));
                case WIDTH, DEPTH -> {
                    double e = ns.get(0).r + 0.5, ev = e * l.stretch;
                    double se = Math.max(0.5, Math.rint(e * 2) / 2), sv = Math.max(0.5, Math.rint(ev * 2) / 2);
                    l.setAllRadii(se - 0.5);
                    l.stretch = sv / se;
                    l.wall = clampWall(l, l.wall);
                }
                case WALL -> l.wall = clampWall(l, Math.rint(l.wall));
                case MOVE -> {
                    if (moveStart == null) return;
                    Vec3 d = ns.get(0).p.subtract(moveStart);
                    l.curve.translate(new Vec3(Math.rint(d.x) - d.x, Math.rint(d.y) - d.y, Math.rint(d.z) - d.z));
                }
                default -> {
                    return;
                }
            }
            l.markDirty();
            return;
        }
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
            case WIDTH -> l.widthNode();
            case DEPTH -> l.depthNode();
            case WALL -> l.wallNode();
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

    /**
     * Aiming while painting with Gradient: only the gradient-angle grips and a shown cog can be
     * picked; looking into a tube targets its line (so its cog comes out) without owning clicks.
     */
    private static void computePaintAim(Minecraft mc) {
        if (dragLine != null) {
            aimed = new Aim(dragLine, dragKind, dragIndex, 0);
            return;
        }
        Vec3 eye = Raycast.eye(mc), dir = Raycast.look(mc);
        Aim best = null;
        if (angling != null) {
            best = closer(best, angling, Kind.GRAD_T, 0, Gizmo.rayCube(eye, dir, gripPos(angling, true), CTRL_HALF));
            best = closer(best, angling, Kind.GRAD_B, 0, Gizmo.rayCube(eye, dir, gripPos(angling, false), CTRL_HALF));
        }
        for (LineShape l : lines) {
            if (ShapeMarkers.cogShown(l)) {
                best = closer(best, l, Kind.COG, 0, Gizmo.rayCube(eye, dir, l.controls(eye)[1], CTRL_HALF));
            }
        }
        if (best == null) {
            LineShape into = tubeLookedInto(mc);
            if (into == null) {
                double bestGap = BODY_PICK;
                for (LineShape l : lines) {
                    LineCurve.RayHit h = LineCurve.closestToRay(l.samples(), eye, dir);
                    if (h != null && h.distance() < bestGap && h.rayT() < Raycast.REACH) {
                        bestGap = h.distance();
                        into = l;
                    }
                }
            }
            if (into != null) best = new Aim(into, Kind.BODY, 0, 0);
        }
        aimed = best;
    }

    private static void computeAim(Minecraft mc) {
        if (dragLine != null) {
            aimed = new Aim(dragLine, dragKind, dragIndex, 0);
            return;
        }
        Vec3 eye = Raycast.eye(mc), dir = Raycast.look(mc);
        Aim best = null;
        if (angling != null) {
            best = closer(best, angling, Kind.GRAD_T, 0, Gizmo.rayCube(eye, dir, gripPos(angling, true), CTRL_HALF));
            best = closer(best, angling, Kind.GRAD_B, 0, Gizmo.rayCube(eye, dir, gripPos(angling, false), CTRL_HALF));
        }
        for (LineShape l : lines) {
            boolean sel = l == selected;
            List<LineCurve.Node> ns = l.curve.nodes;
            for (int i = 0; i < ns.size(); i++) {
                best = closer(best, l, Kind.NODE, i, Gizmo.raySphere(eye, dir, ns.get(i).p, PICK));
                if (sel && !l.shape) {
                    if (hasHandle(l, i, true)) {
                        best = closer(best, l, Kind.HANDLE_IN, i, Gizmo.raySphere(eye, dir, handlePos(l, i, true), PICK));
                    }
                    if (hasHandle(l, i, false)) {
                        best = closer(best, l, Kind.HANDLE_OUT, i, Gizmo.raySphere(eye, dir, handlePos(l, i, false), PICK));
                    }
                }
            }
            if (sel) {
                if (l.shape) {
                    best = closer(best, l, Kind.WIDTH, 0, Gizmo.rayCube(eye, dir, l.widthNode(), CTRL_HALF));
                    best = closer(best, l, Kind.DEPTH, 0, Gizmo.rayCube(eye, dir, l.depthNode(), CTRL_HALF));
                    if (!l.solid) best = closer(best, l, Kind.WALL, 0, Gizmo.rayCube(eye, dir, l.wallNode(), CTRL_HALF));
                } else if (l.profile != LineProfile.NONE) {
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
        double r = Math.rint(v * 10) / 10; // to a tenth, so a drag's 14.999 reads 15
        return r == Math.rint(r) ? String.valueOf((long) r) : String.format(java.util.Locale.ROOT, "%.1f", r);
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
            boolean editing = handles && pm.isMarker();
            // Painting with Gradient: no editing, but the line's cog (gradient options) and angle tool show.
            boolean options = handles && !editing && gradientPainting(pm);
            if (editing || options) l.slideControls(dt);
            boolean sel = editing && l == selected;
            boolean cog = (editing || options) && ShapeMarkers.cogShown(l);
            l.ensureCells(false); // stale tubes rebuild in the background; the last one keeps drawing
            // The tube's edges go white only while it's looked at (crosshair in it, or on one of
            // its parts) — a selected line elsewhere in view keeps the plain amber edges.
            boolean looked = l == lookedTube || (handles && aimed != null && aimed.line == l);
            // The line's own look: looked-at edges stay white but keep its edge opacity.
            drawTube(col, ps, cam, l, l.style.edge(looked ? TUBE_EDGE_SELECTED : TUBE_EDGE, !looked),
                    l.style.ghost(looked ? TUBE_GHOST_SELECTED : TUBE_GHOST, !looked));
            // Twice: depth-tested (weaves through terrain), then a faint on-top ghost so the
            // line and its controls stay readable — and grabbable — behind blocks.
            Vec3[] cluster = editing || options ? l.controls(eye) : null;
            Gizmo.twoPass(col, ps, cam, e -> drawParts(e, l, sel, editing, cog, axes, cluster));
            if (editing && l == rotating) drawGimbal(col, ps, cam, l);
            if ((editing || options) && l == angling && !l.samples().isEmpty()) drawAngleTool(col, ps, cam, l, axes);
        }
        if (sketching != null && handles && pm.isMarker()) drawSketch(col, ps, cam, mc);
        if (dragLine != null && lockAxis != null && dragOrigin != null) {
            // Alt axis lock: the track the dragged node slides on, coloured by axis.
            Vec3 u = Gizmo.axisUnit(lockAxis);
            Vec3 a = dragOrigin.subtract(u.scale(GUIDE_LEN)), b = dragOrigin.add(u.scale(GUIDE_LEN));
            int c = Gizmo.axisColor(lockAxis);
            Gizmo.twoPass(col, ps, cam, e -> Gizmo.segmentRibbon(e, a, b, 0.025, c));
        }
        if (pm == PlacementMode.MARKER_LINE && handles && dragLine == null && sketching == null
                && !ShapeMarkers.controlAimed()) {
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
        if (pm == PlacementMode.MARKER_SHAPE && handles && dragLine == null && sketching == null
                && !ShapeMarkers.controlAimed()) {
            // The corner the next click sets, and (second click) the box the two would make.
            BlockPos here = BlockPos.containing(aimPoint(mc)), first = pendingCorner == null ? here : pendingCorner;
            Vec3 lo = new Vec3(Math.min(here.getX(), first.getX()), Math.min(here.getY(), first.getY()),
                    Math.min(here.getZ(), first.getZ()));
            Vec3 hi = new Vec3(Math.max(here.getX(), first.getX()) + 1, Math.max(here.getY(), first.getY()) + 1,
                    Math.max(here.getZ(), first.getZ()) + 1);
            boolean second = pendingCorner != null;
            Gizmo.twoPass(col, ps, cam, e -> {
                boxFrame(e, lo, hi, second ? 0.035 : 0.02, PREVIEW);
                if (second) Gizmo.cube(e, Vec3.atCenterOf(first), NODE_HALF, Gizmo.START);
                Gizmo.cube(e, Vec3.atCenterOf(here), NODE_HALF, second ? Gizmo.END : Gizmo.START);
            });
        }
    }

    /** The twelve edges of the box from {@code lo} to {@code hi}. */
    private static void boxFrame(Gizmo.Em e, Vec3 lo, Vec3 hi, double w, int argb) {
        double[] x = {lo.x, hi.x}, y = {lo.y, hi.y}, z = {lo.z, hi.z};
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                Gizmo.segmentRibbon(e, new Vec3(x[0], y[a], z[b]), new Vec3(x[1], y[a], z[b]), w, argb);
                Gizmo.segmentRibbon(e, new Vec3(x[a], y[0], z[b]), new Vec3(x[a], y[1], z[b]), w, argb);
                Gizmo.segmentRibbon(e, new Vec3(x[a], y[b], z[0]), new Vec3(x[a], y[b], z[1]), w, argb);
            }
        }
    }

    /**
     * The tube to select by looking into it: the selected one when the crosshair passes through
     * it before any terrain (so a shape inside or behind it doesn't steal the selection), else the
     * first one the crosshair runs into. Null when it meets none.
     */
    private static LineShape tubeToSelect(Minecraft mc) {
        if (lines.isEmpty() || mc.level == null) return null;
        Vec3 eye = Raycast.eye(mc), dir = Raycast.look(mc);
        BlockPos last = null;
        LineShape first = null;
        for (double d = 0; d <= Raycast.REACH; d += 0.1) {
            BlockPos c = BlockPos.containing(eye.add(dir.scale(d)));
            if (c.equals(last)) continue;
            last = c;
            if (selected != null && selected.containsBuilt(c)) return selected;
            if (first == null) {
                for (LineShape l : lines) {
                    if (l.containsBuilt(c)) {
                        first = l;
                        break;
                    }
                }
            }
            if (!Gradient.emptyCell(mc.level.getBlockState(c))) break;
        }
        return first;
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
        if (!editing) { // outside Line mode: just the line (and its tube), plus its cog while painting a gradient
            if (cog) Gizmo.cog(e, cluster[1], isAimed(l, Kind.COG, 0), axes);
            return;
        }
        List<LineCurve.Node> ns = l.curve.nodes;
        for (int i = 0; i < ns.size(); i++) {
            // Start and end nodes in the start / end colours, so the paint direction shows.
            int c = i == 0 ? Gizmo.START : i == ns.size() - 1 ? Gizmo.END : Gizmo.MOVE;
            boolean a = isAimed(l, Kind.NODE, i);
            Gizmo.cube(e, ns.get(i).p, a ? NODE_HALF * 1.3 : NODE_HALF, a ? Gizmo.AIMED : c);
        }
        if (cog) Gizmo.cog(e, cluster[1], isAimed(l, Kind.COG, 0), axes);
        if (!sel) return;
        for (int i = 0; i < (l.shape ? 0 : ns.size()); i++) {
            for (boolean in : new boolean[]{true, false}) {
                if (!hasHandle(l, i, in)) continue;
                Kind k = in ? Kind.HANDLE_IN : Kind.HANDLE_OUT;
                Vec3 hp = handlePos(l, i, in);
                boolean parked = handleParked(l, i, in);
                if (!parked) Gizmo.segmentRibbon(e, ns.get(i).p, hp, 0.015, STALK);
                Gizmo.sphere(e, hp, HANDLE_R, isAimed(l, k, i) ? Gizmo.AIMED : parked ? Gizmo.BEND_PARKED : Gizmo.BEND);
            }
        }
        if (l.shape) {
            Gizmo.controlCube(e, l.widthNode(), CTRL_HALF, isAimed(l, Kind.WIDTH, 0) ? Gizmo.AIMED : Gizmo.SIZE,
                    Gizmo.RESIZE_ICON, axes);
            Gizmo.controlCube(e, l.depthNode(), CTRL_HALF, isAimed(l, Kind.DEPTH, 0) ? Gizmo.AIMED : Gizmo.SIZE,
                    Gizmo.RESIZE_ICON, axes);
            if (!l.solid) {
                Gizmo.controlCube(e, l.wallNode(), CTRL_HALF, isAimed(l, Kind.WALL, 0) ? Gizmo.AIMED
                        : Gizmo.lighten(Gizmo.SIZE), Gizmo.RESIZE_ICON, axes);
            }
        } else if (l.profile != LineProfile.NONE) {
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
        // Gradient preview: the tube's faces in the active palette's colours replace its tint / block look.
        GradientPreview.Faces gp = l.gradPreview ? GradientPreview.of(l) : null;
        BlockSkin.Skin found = gp != null ? null : BlockSkin.of(l.style.block);
        BlockSkin.Skin skin = found != null && blockFaces(fv) <= BlockSkin.MAX_FACES ? found : null;
        int fill = skin != null || gp != null ? 0 : l.style.face(TUBE_FILL);
        boolean through = l.style.facesThrough;
        if (gp != null) {
            // See-through unless the line's face opacity says otherwise (100% = solid blocks).
            int alpha = l.style.faceOpacity == MarkerStyle.UNSET ? PREVIEW_ALPHA : l.style.blockAlpha();
            float gx = (float) (gp.origin().getX() - cam.x), gy = (float) (gp.origin().getY() - cam.y),
                    gz = (float) (gp.origin().getZ() - cam.z);
            Vec3 camLocal = new Vec3(-gx, -gy, -gz);
            for (GradientPreview.Group g : alpha == 0 ? List.<GradientPreview.Group>of() : gp.groups()) {
                int[] c = g.cells();
                ps.pushPose();
                ps.translate(gx, gy, gz);
                if (g.skin() != null) {
                    // The segment's own block texture on each of its cells.
                    BlockSkin.submit(col, ps, g.skin(), alpha, through, camLocal, sink -> {
                        for (int i = 0; i < c.length; i += 4) BlockSkin.cell(sink, c[i], c[i + 1], c[i + 2], c[i + 3]);
                    });
                } else {
                    // No block to show (an Automatic segment): its flat colour.
                    int argb = (alpha << 24) | (g.color() & 0xFFFFFF);
                    col.submitCustomGeometry(ps, RenderTypes.debugFilledBox(), (pose, vc) -> {
                        for (int i = 0; i < c.length; i += 4) {
                            BlockSkin.cell((f, w, a0, a1, b0, b1) -> flatFace(pose, vc, f, w, a0, b0, argb),
                                    c[i], c[i + 1], c[i + 2], c[i + 3]);
                        }
                    });
                }
                ps.popPose();
            }
        }
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

    /** One untextured block-cell face (as {@link BlockSkin#cell} reports it) in a flat colour. */
    private static void flatFace(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer vc,
                                 int face, float w, int a, int b, int argb) {
        int axis = TubeBuild.axisOf(face);
        int pa = axis == 0 ? 1 : 0, pb = axis == 2 ? 1 : 2;
        float[] p = new float[3];
        p[axis] = w;
        for (int k = 0; k < 4; k++) {
            p[pa] = a + ((k == 1 || k == 2) ? 1 : 0);
            p[pb] = b + (k >= 2 ? 1 : 0);
            vc.addVertex(pose, p[0], p[1], p[2]).setColor(argb);
        }
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
