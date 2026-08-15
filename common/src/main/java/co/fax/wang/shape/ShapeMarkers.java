package co.fax.wang.shape;

import co.fax.wang.Gradient;
import co.fax.wang.PlacementMode;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime state for the region markers: the one-piece {@link BoxRegion} (Marker box mode) and
 * the {@link RingShape} circle/square donuts (Marker circle / Marker square modes). Ported
 * from the fw-rule shape tools and adapted to FW Paint's placement-mode model:
 *
 * <p><b>Box</b> (Marker box mode): press left on a block to anchor corner A — the clicked
 * face's axis becomes the gradient axis, corner A's side the START — drag, release to commit
 * corner B. One box at a time; a new drag replaces it. Middle-click aiming at the box deletes
 * it.
 *
 * <p><b>Rings</b> (Marker circle / square modes): three clicks create a shape — center (the
 * clicked face's axis is the plane normal; that base plane is the gradient START side),
 * radius A, radius B (equal radii = one-wide outline, different = a band, i.e. the wall
 * thickness). Aim at the blue center/radius controls to drag them (move / resize); squares
 * grow green corner nodes that drag to rotate. Right-click a control extrudes a layer along
 * the normal (clear-connected modifier = the other way); middle-click deletes the shape;
 * scrolling while aiming a control slides the shape along its normal. Multiple rings allowed.
 *
 * <p>Regions render whatever the mode, but their controls only respond while the matching
 * marker mode is selected — painting modes just get constrained by them (see ShapeRegions'
 * consumers in PaintPlacer). Persisted per world+dimension via {@link ShapeStore}.
 */
public final class ShapeMarkers {

    // Ring visuals (fw-rule palette).
    private static final int RING_FILL = 0x33FFA000;        // committed ring cells (kept faint)
    private static final int RING_EDGE = 0xD8FFB830;        // bright silhouette edges on the band
    private static final int RING_PREVIEW = 0x22FFA000;     // creation preview ring
    private static final int CONTROL_FILL = 0x7760A0FF;     // blue control blocks
    private static final int CONTROL_AIMED = 0x99A8CCFF;    // lighter blue when aimed
    private static final int CORNER_FILL = 0x7740C860;      // green square-corner nodes
    private static final int CORNER_AIMED = 0x99A0F0B0;     // lighter green when aimed
    private static final int CONTROL_OUTLINE = 0xFFFFFFFF;  // white 1px edges when aimed
    private static final int HOVER_CENTER = 0x5560A0FF;     // creation hover: pick center
    private static final int HOVER_RADIUS = 0x66FFA000;     // creation hover: pick radii

    // Box visuals: neutral walls; start/end planes tinted with the marker colours.
    private static final int BOX_WALL = 0x2260A0FF;
    private static final int BOX_EDGE = 0xFFE0E0E0;
    private static final int BOX_START = 0x4440E0D0;        // turquoise — gradient start plane
    private static final int BOX_END = 0x44FFB000;          // amber — gradient end plane
    private static final int BOX_HOVER = 0x5560A0FF;        // box mode: aimed anchor block

    private enum ControlKind { CENTER, RADIUS_A, RADIUS_B, CORNER }

    private static final List<RingShape> shapes = new ArrayList<>();
    private static BoxRegion box;

    // Box drag state (Marker box mode).
    private static BlockPos boxCornerA;
    private static Direction.Axis boxAxis;
    private static BlockPos boxHover;
    private static boolean boxDragging;

    // Ring creation state: center+normal set after click 1, ctrlA after click 2.
    private static BlockPos pendingCenter;
    private static Direction.Axis pendingNormal;
    private static BlockPos pendingCtrlA;

    // Targeting/drag state (only while a ring marker mode is active).
    private static RingShape aimedShape;
    private static ControlKind aimedKind;
    private static int aimedCorner;
    private static RingShape dragShape;
    private static ControlKind dragKind;
    // Rotation drag capture: the drag re-derives everything from these, so no rounding drift.
    private static double dragStartTheta;
    private static double dragCornerBaseAngle;
    private static double[] dragStartOffA, dragStartOffB;

    private static boolean lastAttack, lastUse, lastPick;
    private static String currentKey; // world+dimension the loaded regions belong to

    /** Ticks a scroll-grab survives without further scrolling before you must re-target. */
    private static final int SCROLL_GRACE_TICKS = 15;
    private static RingShape scrollShape;
    private static int scrollGrace;
    private static long frame; // render-frame counter (silhouette rebuild throttling)

    private ShapeMarkers() {}

    // ---- region queries (the contract PaintPlacer consumes) --------------------------------

    /** True when any region (box or ring) exists in this world. */
    public static boolean any() {
        return box != null || !shapes.isEmpty();
    }

    /** True when the cell is inside a region: the box volume, or a ring band × extrusion. */
    public static boolean contains(BlockPos cell) {
        if (box != null && box.contains(cell)) return true;
        for (RingShape s : shapes) {
            if (s.contains(cell)) return true;
        }
        return false;
    }

    /**
     * The cell's gradient segment through the region containing it (start plane → end plane),
     * or null when no region holds it or the region has no depth.
     */
    public static RingShape.Segment segmentFor(BlockPos cell) {
        if (box != null) {
            RingShape.Segment seg = box.segmentFor(cell);
            if (seg != null) return seg;
        }
        for (RingShape s : shapes) {
            RingShape.Segment seg = s.segmentFor(cell);
            if (seg != null) return seg;
        }
        return null;
    }

    /** Region count for the Settings clear-button label. */
    public static int count() {
        return shapes.size() + (box == null ? 0 : 1);
    }

    /**
     * True while the crosshair is on a shape control block (tool in hand): clicks then belong
     * to the shape — MarkerManager and PaintPlacer stand down.
     */
    public static boolean controlAimed() {
        return aimedShape != null;
    }

    /** Drops every region and any in-progress creation, and persists. */
    public static void clearAll() {
        shapes.clear();
        box = null;
        clearBoxDrag();
        clearPending();
        aimedShape = null;
        dragShape = null;
        scrollShape = null;
        scrollGrace = 0;
        saveCurrent();
    }

    // ---- world sync / persistence ----------------------------------------------------------

    private static void saveCurrent() {
        ShapeStore.store(currentKey, shapes, box);
    }

    private static void syncWorld(Minecraft mc) {
        String key = worldKey(mc);
        if (!java.util.Objects.equals(key, currentKey)) {
            currentKey = key;
            box = ShapeStore.loadInto(key, shapes); // null key (main menu) clears
            clearBoxDrag();
            clearPending();
            aimedShape = null;
            dragShape = null;
            scrollShape = null;
            scrollGrace = 0;
        }
    }

    /** Stable per-world, per-dimension key, or null when not in a world. */
    private static String worldKey(Minecraft mc) {
        if (mc.level == null) return null;
        String dim = mc.level.dimension().identifier().toString();
        var singleplayer = mc.getSingleplayerServer();
        if (singleplayer != null) return "sp:" + singleplayer.getWorldData().getLevelName() + "|" + dim;
        var server = mc.getCurrentServer();
        if (server != null) return "mp:" + server.ip + "|" + dim;
        return "local|" + dim;
    }

    private static void clearPending() {
        pendingCenter = null;
        pendingNormal = null;
        pendingCtrlA = null;
    }

    private static void clearBoxDrag() {
        boxCornerA = null;
        boxAxis = null;
        boxHover = null;
        boxDragging = false;
    }

    /**
     * Mouse-wheel hook from the loader shells. While aiming a ring control, scrolling slides
     * the whole shape along its extrusion axis (scroll up = positive normal). Once a scroll-
     * grab starts it survives aim slips for a short grace window. Returns true when consumed
     * (the shells then cancel the vanilla hotbar scroll).
     */
    public static boolean onScroll(double deltaY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || deltaY == 0) return false;
        if (!Gradient.holdingPaintTool(mc) || mc.gui.screen() != null) return false;
        // Sticky grab first: mid-scroll aim slips don't switch shapes.
        RingShape target = scrollGrace > 0 && scrollShape != null ? scrollShape : aimedShape;
        if (target == null) return false;
        target.moveCenterTo(RingShape.offset(target.center, target.normal, deltaY > 0 ? 1 : -1));
        scrollShape = target;
        scrollGrace = SCROLL_GRACE_TICKS;
        return true;
    }

    private static boolean ringMode(PlacementMode pm) {
        return pm == PlacementMode.MARKER_CIRCLE || pm == PlacementMode.MARKER_SQUARE;
    }

    // ---- client tick ------------------------------------------------------------------------

    /** Called from MarkerManager.tick with the resolved placement mode. */
    public static void tick(Minecraft mc, PlacementMode pm) {
        syncWorld(mc);
        if (mc.level == null || mc.player == null) return;

        if (scrollGrace > 0 && --scrollGrace == 0) { // scroll-grab decay: shape has settled
            scrollShape = null;
            saveCurrent();
        }

        boolean screenOpen = mc.gui.screen() != null;
        // Controls respond whenever the paint tool is in hand — the marker modes are only
        // needed to CREATE regions (box drag, ring clicks).
        boolean handles = Gradient.holdingPaintTool(mc) && !screenOpen;
        boolean boxCreate = pm == PlacementMode.MARKER_BOX && !screenOpen;
        boolean ringCreate = ringMode(pm) && !screenOpen;

        if (!boxCreate && boxDragging) clearBoxDrag(); // leaving box mode drops the half-drag
        if (!ringCreate) clearPending(); // leaving the mode mid-creation drops the half-built shape
        if (!handles) {
            dragShape = null;
            aimedShape = null;
            scrollShape = null;
            scrollGrace = 0;
            lastAttack = mc.options.keyAttack.isDown();
            lastUse = mc.options.keyUse.isDown();
            lastPick = mc.options.keyPickItem.isDown();
            return;
        }

        updateAimedControl(mc);

        boolean attack = mc.options.keyAttack.isDown();
        boolean use = mc.options.keyUse.isDown();
        boolean pick = mc.options.keyPickItem.isDown();

        if (attack && !lastAttack) {
            if (aimedShape != null) {          // grab a control to drag it — any mode
                dragShape = aimedShape;
                dragKind = aimedKind;
                if (dragKind == ControlKind.CORNER) { // rotation drag: capture the start pose
                    dragStartTheta = dragShape.theta;
                    dragCornerBaseAngle = dragShape.cornerBaseAngle(aimedCorner);
                    dragStartOffA = dragShape.offsetOf(dragShape.ctrlA);
                    dragStartOffB = dragShape.offsetOf(dragShape.ctrlB);
                }
            } else if (boxCreate) {
                BlockHitResult hit = Raycast.aimedBlock(mc);
                if (hit != null) {                        // anchor corner A; face = gradient axis
                    boxCornerA = hit.getBlockPos().immutable();
                    boxAxis = hit.getDirection().getAxis();
                    boxHover = boxCornerA;
                    boxDragging = true;
                }
            } else if (ringCreate) {
                creationClick(mc, pm);
            }
        }
        if (boxDragging) {
            BlockHitResult hit = Raycast.aimedBlock(mc);
            if (hit != null) boxHover = hit.getBlockPos().immutable(); // sky freezes the preview
            if (!attack) {                            // release commits wherever we last hovered
                box = new BoxRegion(boxCornerA, boxHover, boxAxis);
                clearBoxDrag();
                saveCurrent();
                overlay(mc, "FW Paint — Box marker set (start on the clicked side)");
            }
        }
        if (dragShape != null) {
            if (attack) {
                applyDrag(mc);
            } else {
                dragShape = null;              // release keeps whatever the drag set last
                saveCurrent();
            }
        }
        if (use && !lastUse && aimedShape != null) {
            aimedShape.extrude(Gradient.clearConnectedDown() ? -1 : 1);
            saveCurrent();
        }
        // Middle-click removes whatever marker is aimed at, no matter the kind or mode:
        // a shape (via its controls), the box, or a plain block marker.
        if (pick && !lastPick && !boxDragging) {
            if (aimedShape != null) {
                shapes.remove(aimedShape);
                if (scrollShape == aimedShape) scrollShape = null;
                aimedShape = null;
                dragShape = null;
                saveCurrent();
            } else if (box != null && rayHitsBox(mc, box)) {
                box = null;
                saveCurrent();
                overlay(mc, "FW Paint — Box marker removed");
            } else {
                BlockHitResult hit = Raycast.aimedBlock(mc);
                if (hit != null) co.fax.wang.MarkerManager.removeMarkerAt(hit.getBlockPos());
            }
        }

        lastAttack = attack;
        lastUse = use;
        lastPick = pick;
    }

    private static boolean rayHitsBox(Minecraft mc, BoxRegion b) {
        Vec3 eye = Raycast.eye(mc);
        Vec3 dir = Raycast.look(mc);
        BlockPos mn = b.min(), mx = b.max();
        return ShapeMath.rayBoxIntersect(eye.x, eye.y, eye.z, dir.x, dir.y, dir.z,
                mn.getX(), mn.getY(), mn.getZ(),
                mx.getX() + 1, mx.getY() + 1, mx.getZ() + 1) >= 0;
    }

    /** True when the view ray crosses the shape's bounding volume ("looking at" the region). */
    private static boolean rayHitsRing(Minecraft mc, RingShape s) {
        Vec3 eye = Raycast.eye(mc);
        Vec3 dir = Raycast.look(mc);
        int b = s.scanBound();
        BlockPos mn = RingShape.offset(RingShape.offset(RingShape.offset(
                s.center, s.uAxis(), -b), s.vAxis(), -b), s.normal, s.layerLo());
        int span = s.layerHi() - s.layerLo() + 1;
        int su = 2 * b + 1;
        double sx = s.normal == Direction.Axis.X ? span : su;
        double sy = s.normal == Direction.Axis.Y ? span : su;
        double sz = s.normal == Direction.Axis.Z ? span : su;
        return ShapeMath.rayBoxIntersect(eye.x, eye.y, eye.z, dir.x, dir.y, dir.z,
                mn.getX(), mn.getY(), mn.getZ(),
                mn.getX() + sx, mn.getY() + sy, mn.getZ() + sz) >= 0;
    }

    /** The three creation clicks: center (plane from the clicked face), radius A, radius B. */
    private static void creationClick(Minecraft mc, PlacementMode pm) {
        RingShape.Kind kind = pm == PlacementMode.MARKER_SQUARE
                ? RingShape.Kind.SQUARE : RingShape.Kind.CIRCLE;
        if (pendingCenter == null) {
            BlockHitResult hit = Raycast.aimedBlock(mc);
            if (hit == null) return;
            pendingCenter = hit.getBlockPos().immutable();
            pendingNormal = hit.getDirection().getAxis();
            overlay(mc, "FW Paint — " + kind.name().toLowerCase(java.util.Locale.ROOT)
                    + ": click the first radius (works over air)");
            return;
        }
        BlockPos onPlane = planeCell(mc, pendingCenter, pendingNormal);
        if (onPlane == null) return;
        if (pendingCtrlA == null) {
            pendingCtrlA = onPlane;
            overlay(mc, "FW Paint — click the second radius (same = one-wide, apart = a band)");
            return;
        }
        shapes.add(new RingShape(kind, pendingCenter, pendingNormal, pendingCtrlA, onPlane));
        clearPending();
        saveCurrent();
        overlay(mc, "FW Paint — shape set: right-click a control to extrude, drag to edit");
    }

    /** While holding a control: it follows the view ray's intersection with the base plane. */
    private static void applyDrag(Minecraft mc) {
        if (dragKind == ControlKind.CORNER) {
            // Rotation: the grabbed corner chases the exact aim point's in-plane angle.
            double[] uv = planePointUV(mc, dragShape);
            if (uv == null || (uv[0] == 0 && uv[1] == 0)) return;
            double newTheta = Math.atan2(uv[1], uv[0]) - dragCornerBaseAngle;
            dragShape.applyRotation(newTheta, dragStartTheta, dragStartOffA, dragStartOffB);
        } else {
            BlockPos onPlane = planeCell(mc, dragShape.center, dragShape.normal);
            if (onPlane == null) return;
            switch (dragKind) {
                case CENTER -> dragShape.moveCenterTo(onPlane);
                // Blue radius nodes may not land on the green corner cells.
                case RADIUS_A -> { if (!isCornerCell(dragShape, onPlane)) dragShape.setRadiusControl(true, onPlane); }
                case RADIUS_B -> { if (!isCornerCell(dragShape, onPlane)) dragShape.setRadiusControl(false, onPlane); }
                default -> { }
            }
        }
        aimedShape = dragShape; // keep the highlight on what's being dragged
        aimedKind = dragKind;
    }

    private static boolean isCornerCell(RingShape shape, BlockPos cell) {
        if (shape.kind != RingShape.Kind.SQUARE) return false;
        for (int i = 0; i < 4; i++) {
            if (shape.cornerCell(i).equals(cell)) return true;
        }
        return false;
    }

    /** Exact in-plane (u, v) of the aim point on {@code shape}'s base plane, or null. */
    private static double[] planePointUV(Minecraft mc, RingShape shape) {
        Vec3 eye = Raycast.eye(mc);
        Vec3 dir = Raycast.look(mc);
        double o = component(eye, shape.normal), d = component(dir, shape.normal);
        if (Math.abs(d) < 1e-6) return null;
        double t = (shape.center.get(shape.normal) + 0.5 - o) / d;
        if (t < 0 || t > Raycast.REACH) return null;
        Vec3 p = eye.add(dir.scale(t));
        return new double[]{
                component(p, shape.uAxis()) - (shape.center.get(shape.uAxis()) + 0.5),
                component(p, shape.vAxis()) - (shape.center.get(shape.vAxis()) + 0.5)};
    }

    /**
     * The cell where the view ray crosses the plane through {@code center}'s middle — this is
     * how radius clicks and drags work over open air at any distance.
     */
    private static BlockPos planeCell(Minecraft mc, BlockPos center, Direction.Axis axis) {
        Vec3 eye = Raycast.eye(mc);
        Vec3 dir = Raycast.look(mc);
        double o = component(eye, axis), d = component(dir, axis);
        if (Math.abs(d) < 1e-6) return null;
        double t = (center.get(axis) + 0.5 - o) / d;
        if (t < 0 || t > Raycast.REACH) return null;
        Vec3 p = eye.add(dir.scale(t));
        BlockPos cell = BlockPos.containing(p.x, p.y, p.z);
        // Snap exactly onto the base plane.
        return switch (axis) {
            case X -> new BlockPos(center.getX(), cell.getY(), cell.getZ());
            case Y -> new BlockPos(cell.getX(), center.getY(), cell.getZ());
            case Z -> new BlockPos(cell.getX(), cell.getY(), center.getZ());
        };
    }

    private static double component(Vec3 v, Direction.Axis axis) {
        return switch (axis) {
            case X -> v.x;
            case Y -> v.y;
            case Z -> v.z;
        };
    }

    /**
     * Nearest control the view ray passes through. Controls are full columns spanning the
     * shape's extrusion, and can float in open air (ray/AABB test, not a world raycast).
     */
    private static void updateAimedControl(Minecraft mc) {
        if (dragShape != null) return; // the drag owns the highlight until release
        Vec3 eye = Raycast.eye(mc);
        Vec3 dir = Raycast.look(mc);
        aimedShape = null;
        aimedKind = null;
        double best = Raycast.REACH;
        for (RingShape shape : shapes) {
            // CENTER first so it wins ties when controls share a block; blues beat corners.
            ControlKind[] kinds = {ControlKind.CENTER, ControlKind.RADIUS_B, ControlKind.RADIUS_A};
            BlockPos[] cells = {shape.center, shape.ctrlB, shape.ctrlA};
            for (int i = 0; i < kinds.length; i++) {
                double t = columnIntersect(eye, dir, shape, cells[i]);
                if (t >= 0 && t < best) {
                    best = t;
                    aimedShape = shape;
                    aimedKind = kinds[i];
                }
            }
            if (shape.kind == RingShape.Kind.SQUARE) {
                for (int i = 0; i < 4; i++) {
                    double t = columnIntersect(eye, dir, shape, shape.cornerCell(i));
                    if (t >= 0 && t < best) {
                        best = t;
                        aimedShape = shape;
                        aimedKind = ControlKind.CORNER;
                        aimedCorner = i;
                    }
                }
            }
        }
    }

    /** Ray distance to a control's full extrusion column, or -1. */
    private static double columnIntersect(Vec3 eye, Vec3 dir, RingShape shape, BlockPos cell) {
        BlockPos lo = RingShape.offset(cell, shape.normal, shape.layerLo());
        int span = shape.layerHi() - shape.layerLo() + 1;
        double sx = shape.normal == Direction.Axis.X ? span : 1;
        double sy = shape.normal == Direction.Axis.Y ? span : 1;
        double sz = shape.normal == Direction.Axis.Z ? span : 1;
        return ShapeMath.rayBoxIntersect(eye.x, eye.y, eye.z, dir.x, dir.y, dir.z,
                lo.getX(), lo.getY(), lo.getZ(),
                lo.getX() + sx, lo.getY() + sy, lo.getZ() + sz);
    }

    private static void overlay(Minecraft mc, String msg) {
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal(msg));
    }

    // ---- rendering (called from MarkerManager.render) --------------------------------------

    public static void render(SubmitNodeCollector col, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        PlacementMode pm = Gradient.currentPlacement(mc);
        boolean screenOpen = mc.gui.screen() != null;
        boolean handles = Gradient.holdingPaintTool(mc) && !screenOpen;
        boolean boxCreate = pm == PlacementMode.MARKER_BOX && !screenOpen;
        boolean ringCreate = ringMode(pm) && !screenOpen;
        PoseStack ps = new PoseStack();

        frame++;
        for (RingShape shape : shapes) {
            drawRing(col, ps, cam, shape, shape.layerLo(), shape.layerHi(), RING_FILL);
            // The bright merged-silhouette outline only shows while the tool is in hand and
            // the crosshair is on the region — the fills always render.
            if (handles && rayHitsRing(mc, shape)) {
                boolean allowRebuild = shape != dragShape || frame % 4 == 0;
                WorldDraw.compositeOutline(col, ps, cam, shape.silhouetteOrigin(),
                        shape.silhouette(allowRebuild), RING_EDGE);
            }
            drawControl(col, ps, cam, shape, ControlKind.RADIUS_A, -1, shape.ctrlA, handles);
            drawControl(col, ps, cam, shape, ControlKind.RADIUS_B, -1, shape.ctrlB, handles);
            drawControl(col, ps, cam, shape, ControlKind.CENTER, -1, shape.center, handles);
            if (shape.kind == RingShape.Kind.SQUARE) {
                for (int i = 0; i < 4; i++) { // derived green rotation nodes on the corners
                    drawControl(col, ps, cam, shape, ControlKind.CORNER, i, shape.cornerCell(i), handles);
                }
            }
        }

        renderBox(col, ps, cam, mc, handles, boxCreate);
        if (ringCreate) renderRingCreation(col, ps, cam, mc, pm);
    }

    /** The committed box (and the live drag preview) as one region with tinted start/end planes. */
    private static void renderBox(SubmitNodeCollector col, PoseStack ps, Vec3 cam,
                                  Minecraft mc, boolean handles, boolean boxCreate) {
        if (boxCreate && !boxDragging && aimedShape == null) {
            BlockHitResult hit = Raycast.aimedBlock(mc);
            if (hit != null) WorldDraw.filledBlock(col, ps, cam, hit.getBlockPos(), BOX_HOVER);
        }
        BoxRegion draw = boxDragging && boxCornerA != null
                ? new BoxRegion(boxCornerA, boxHover, boxAxis)
                : box;
        if (draw == null) return;

        BlockPos mn = draw.min(), mx = draw.max();
        int sx = mx.getX() - mn.getX() + 1;
        int sy = mx.getY() - mn.getY() + 1;
        int sz = mx.getZ() - mn.getZ() + 1;
        WorldDraw.filledRegion(col, ps, cam, mn, sx, sy, sz, BOX_WALL);
        // The white outline only shows while the tool is in hand and the crosshair is on the
        // box (or while it's being dragged out).
        if (boxDragging || (handles && rayHitsBox(mc, draw))) {
            WorldDraw.regionOutline(col, ps, cam, mn, sx, sy, sz, BOX_EDGE);
        }

        // Start/end plane slabs (skip when the box is flat on its axis — no direction).
        int a = draw.cornerA.get(draw.axis), b = draw.cornerB.get(draw.axis);
        if (a != b) {
            drawPlaneSlab(col, ps, cam, draw, a, BOX_START);
            drawPlaneSlab(col, ps, cam, draw, b, BOX_END);
        }
    }

    /** A one-block-thick tinted slab of the box at {@code plane} along the box axis. */
    private static void drawPlaneSlab(SubmitNodeCollector col, PoseStack ps, Vec3 cam,
                                      BoxRegion b, int plane, int argb) {
        BlockPos mn = b.min(), mx = b.max();
        int x0 = b.axis == Direction.Axis.X ? plane : mn.getX();
        int y0 = b.axis == Direction.Axis.Y ? plane : mn.getY();
        int z0 = b.axis == Direction.Axis.Z ? plane : mn.getZ();
        int sx = b.axis == Direction.Axis.X ? 1 : mx.getX() - mn.getX() + 1;
        int sy = b.axis == Direction.Axis.Y ? 1 : mx.getY() - mn.getY() + 1;
        int sz = b.axis == Direction.Axis.Z ? 1 : mx.getZ() - mn.getZ() + 1;
        WorldDraw.filledRegion(col, ps, cam, new BlockPos(x0, y0, z0), sx, sy, sz, argb);
    }

    /**
     * A control renders as a column (blue, or green for square corner nodes) spanning the
     * shape's extrusion — with the faces between stacked cells culled, so only the column's
     * outer surface is drawn.
     */
    private static void drawControl(SubmitNodeCollector col, PoseStack ps, Vec3 cam, RingShape shape,
                                    ControlKind kind, int cornerIndex, BlockPos cell, boolean canInteract) {
        boolean aimed = canInteract && shape == aimedShape && kind == aimedKind
                && (kind != ControlKind.CORNER || cornerIndex == aimedCorner);
        boolean corner = kind == ControlKind.CORNER;
        int fill = corner ? (aimed ? CORNER_AIMED : CORNER_FILL) : (aimed ? CONTROL_AIMED : CONTROL_FILL);
        int lo = shape.layerLo(), hi = shape.layerHi();
        for (int k = lo; k <= hi; k++) {
            int mask = 0;
            for (Direction d : Direction.values()) {
                if (d.getAxis() == shape.normal) {
                    int kk = k + d.getAxisDirection().getStep();
                    if (kk >= lo && kk <= hi) continue; // internal face between stacked cells
                }
                mask |= 1 << d.ordinal();
            }
            WorldDraw.filledBlockFaces(col, ps, cam, RingShape.offset(cell, shape.normal, k), fill, mask);
        }
        if (aimed) {
            int span = hi - lo + 1;
            WorldDraw.regionOutline(col, ps, cam, RingShape.offset(cell, shape.normal, lo),
                    shape.normal == Direction.Axis.X ? span : 1,
                    shape.normal == Direction.Axis.Y ? span : 1,
                    shape.normal == Direction.Axis.Z ? span : 1,
                    CONTROL_OUTLINE);
        }
    }

    /** Creation hover + live ring preview between clicks. */
    private static void renderRingCreation(SubmitNodeCollector col, PoseStack ps, Vec3 cam,
                                           Minecraft mc, PlacementMode pm) {
        if (pendingCenter == null) {
            if (aimedShape != null) return; // aiming a control: manipulation, not creation
            BlockHitResult hit = Raycast.aimedBlock(mc);
            if (hit != null) WorldDraw.filledBlock(col, ps, cam, hit.getBlockPos(), HOVER_CENTER);
            return;
        }
        WorldDraw.filledBlock(col, ps, cam, pendingCenter, CONTROL_FILL);
        BlockPos onPlane = planeCell(mc, pendingCenter, pendingNormal);
        if (onPlane != null) WorldDraw.filledBlock(col, ps, cam, onPlane, HOVER_RADIUS);
        if (pendingCtrlA != null) WorldDraw.filledBlock(col, ps, cam, pendingCtrlA, CONTROL_FILL);

        // Live preview: one-wide ring at the hovered radius, or the band vs radius A.
        if (onPlane == null) return;
        RingShape.Kind kind = pm == PlacementMode.MARKER_SQUARE
                ? RingShape.Kind.SQUARE : RingShape.Kind.CIRCLE;
        RingShape preview = new RingShape(kind, pendingCenter, pendingNormal,
                pendingCtrlA != null ? pendingCtrlA : onPlane, onPlane);
        drawRing(col, ps, cam, preview, 0, 0, RING_PREVIEW);
    }

    /**
     * Rasterizes the shape's ring band on every layer [hLo..hHi] along its normal. Faces
     * shared with a neighboring ring cell (sideways within the band, or up/down between
     * layers) are culled, so only the outer surface of the donut/tube is drawn.
     */
    private static void drawRing(SubmitNodeCollector col, PoseStack ps, Vec3 cam,
                                 RingShape shape, int hLo, int hHi, int argb) {
        int bound = shape.scanBound(); // rotated square corners reach past rMax
        Direction.Axis uAxis = shape.uAxis(), vAxis = shape.vAxis();
        for (int du = -bound; du <= bound; du++) {
            for (int dv = -bound; dv <= bound; dv++) {
                if (!shape.onRing(du, dv)) continue;
                for (int k = hLo; k <= hHi; k++) {
                    int mask = 0;
                    for (Direction d : Direction.values()) {
                        int step = d.getAxisDirection().getStep();
                        boolean neighborInShape;
                        if (d.getAxis() == shape.normal) {
                            int kk = k + step;
                            neighborInShape = kk >= hLo && kk <= hHi;
                        } else if (d.getAxis() == uAxis) {
                            neighborInShape = shape.onRing(du + step, dv);
                        } else {
                            neighborInShape = shape.onRing(du, dv + step);
                        }
                        if (!neighborInShape) mask |= 1 << d.ordinal();
                    }
                    BlockPos cell = cellAt(shape.center, shape.normal, k, uAxis, du, vAxis, dv);
                    WorldDraw.filledBlockFaces(col, ps, cam, cell, argb, mask);
                    // Bright lines wherever two visible faces meet: the band's outer/inner
                    // rims and jagged steps — keeps the silhouette readable through fills.
                    if (argb == RING_FILL) WorldDraw.blockEdges(col, ps, cam, cell, RING_EDGE, mask);
                }
            }
        }
    }

    private static BlockPos cellAt(BlockPos center, Direction.Axis normal, int k,
                                   Direction.Axis uAxis, int du, Direction.Axis vAxis, int dv) {
        int x = center.getX(), y = center.getY(), z = center.getZ();
        x += normal == Direction.Axis.X ? k : (uAxis == Direction.Axis.X ? du : dv);
        y += normal == Direction.Axis.Y ? k : (uAxis == Direction.Axis.Y ? du : dv);
        z += normal == Direction.Axis.Z ? k : (vAxis == Direction.Axis.Z ? dv : du);
        return new BlockPos(x, y, z);
    }
}
