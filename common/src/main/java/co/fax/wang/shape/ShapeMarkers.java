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
    private static final int RING_EDGE_LOOKED = 0xF0FFFFFF; // edges go white while looked at
    // The on-top ghost pass draws through EVERYTHING (26.2 has no occluded-only pipeline), so
    // it stays at modest alpha: a faint cage through terrain, a slight glow where visible.
    private static final int RING_GHOST = 0x5CFFB830;       // edge cage through terrain
    private static final int RING_GHOST_LOOKED = 0xA8FFFFFF;
    private static final int CONTROL_GHOST = 0x6660A0FF;    // control column cage
    private static final int CORNER_GHOST = 0x6640C860;
    private static final int BOX_GHOST = 0x5CE0E0E0;
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

    // Box creation state (Marker box mode): corner A pending until right-click commits B.
    private static BlockPos boxCornerA;
    private static Direction.Axis boxAxis;

    /** The shape the crosshair is on (silhouette ray-hit — works through terrain), or null. */
    private static RingShape lookedShape;

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
        clearBoxPending();
        clearPending();
        aimedShape = null;
        dragShape = null;
        lookedShape = null;
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
            clearBoxPending();
            clearPending();
            aimedShape = null;
            dragShape = null;
            lookedShape = null;
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

    private static void clearBoxPending() {
        boxCornerA = null;
        boxAxis = null;
    }

    /**
     * Mouse-wheel hook from the loader shells. While the crosshair is on a shape — any part of
     * its band, not just a control — scrolling slides it along its extrusion axis (scroll up =
     * positive normal). Once a scroll-grab starts it survives aim slips for a short grace
     * window. Returns true when consumed (the shells then cancel the vanilla hotbar scroll).
     */
    public static boolean onScroll(double deltaY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || deltaY == 0) return false;
        if (!Gradient.holdingPaintTool(mc) || mc.gui.screen() != null) return false;
        // Sticky grab first: mid-scroll aim slips don't switch shapes.
        RingShape target = scrollGrace > 0 && scrollShape != null ? scrollShape
                : aimedShape != null ? aimedShape : lookedShape;
        if (target == null) return false;
        if (Gradient.clearConnectedDown()) {
            // Modifier + scroll: extrude — up grows a layer along +normal, down shrinks it
            // (past one layer it flips out the other side, like right-click extrude).
            target.extrude(deltaY > 0 ? 1 : -1);
        } else {
            target.moveCenterTo(RingShape.offset(target.center, target.normal, deltaY > 0 ? 1 : -1));
        }
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
        // needed to CREATE regions (box corners, ring clicks).
        boolean handles = Gradient.holdingPaintTool(mc) && !screenOpen;
        boolean boxCreate = pm == PlacementMode.MARKER_BOX && !screenOpen;
        boolean ringCreate = ringMode(pm) && !screenOpen;

        if (!boxCreate) clearBoxPending(); // leaving box mode drops the pending corner
        if (!ringCreate) clearPending(); // leaving the mode mid-creation drops the half-built shape
        if (!handles) {
            dragShape = null;
            aimedShape = null;
            lookedShape = null;
            scrollShape = null;
            scrollGrace = 0;
            lastAttack = mc.options.keyAttack.isDown();
            lastUse = mc.options.keyUse.isDown();
            lastPick = mc.options.keyPickItem.isDown();
            return;
        }

        updateAimedControl(mc);
        updateLookedShape(mc);

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
                // Left click sets (or moves) corner A; its face is the gradient axis.
                BlockHitResult hit = Raycast.aimedBlock(mc);
                if (hit != null) {
                    boxCornerA = hit.getBlockPos().immutable();
                    boxAxis = hit.getDirection().getAxis();
                    overlay(mc, "FW Paint — Box: right-click the opposite corner");
                }
            } else if (ringCreate) {
                creationClick(mc, pm);
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
        if (use && !lastUse) {
            if (ringCreate && pendingCenter != null) {
                clearPending();                // right-click cancels a part-built shape
                overlay(mc, "FW Paint — shape cancelled");
            } else if (boxCreate && boxCornerA != null) {
                BlockHitResult hit = Raycast.aimedBlock(mc);
                if (hit != null) {             // right click commits the opposite corner
                    box = new BoxRegion(boxCornerA, hit.getBlockPos(), boxAxis);
                    clearBoxPending();
                    saveCurrent();
                    overlay(mc, "FW Paint — Box marker set (start on the first corner's side)");
                }
            } else if (aimedShape != null) {
                aimedShape.extrude(Gradient.clearConnectedDown() ? -1 : 1);
                saveCurrent();
            }
        }
        // Middle-click removes whatever marker is under the crosshair, no matter the kind or
        // mode: the shape whose band (or control) is looked at, the box, or a block marker.
        if (pick && !lastPick) {
            RingShape target = aimedShape != null ? aimedShape : lookedShape;
            if (target != null) {
                shapes.remove(target);
                if (scrollShape == target) scrollShape = null;
                if (lookedShape == target) lookedShape = null;
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

    /**
     * The shape whose band the view ray crosses (nearest first) — a precise silhouette ray
     * test, not a world raycast, so it finds shapes buried under terrain too. While dragging,
     * the dragged shape owns the highlight.
     */
    private static void updateLookedShape(Minecraft mc) {
        if (dragShape != null) {
            lookedShape = dragShape;
            return;
        }
        lookedShape = null;
        Vec3 eye = Raycast.eye(mc);
        Vec3 end = eye.add(Raycast.look(mc).scale(Raycast.REACH));
        double best = Double.MAX_VALUE;
        for (RingShape s : shapes) {
            var hit = s.silhouette(false).clip(eye, end, s.silhouetteOrigin());
            if (hit != null && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                double d = hit.getLocation().distanceToSqr(eye);
                if (d < best) {
                    best = d;
                    lookedShape = s;
                }
            }
        }
    }

    private static boolean rayHitsBox(Minecraft mc, BoxRegion b) {
        Vec3 eye = Raycast.eye(mc);
        Vec3 dir = Raycast.look(mc);
        BlockPos mn = b.min(), mx = b.max();
        return ShapeMath.rayBoxIntersect(eye.x, eye.y, eye.z, dir.x, dir.y, dir.z,
                mn.getX(), mn.getY(), mn.getZ(),
                mx.getX() + 1, mx.getY() + 1, mx.getZ() + 1) >= 0;
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
            // Looking at any part of the shape turns its edges white (the "selected" read) —
            // the whole shape is then scrollable, draggable via controls, and middle-click
            // deletable. Rebuilding the cached geometry is throttled while dragging.
            boolean looked = handles && (shape == lookedShape || shape == aimedShape);
            boolean allowRebuild = shape != dragShape || frame % 4 == 0;
            drawRing(col, ps, cam, shape, shape.layerLo(), shape.layerHi(), RING_FILL,
                    looked ? RING_EDGE_LOOKED : RING_EDGE, allowRebuild);
            if (looked) {
                WorldDraw.compositeOutline(col, ps, cam, shape.silhouetteOrigin(),
                        shape.silhouette(false), RING_EDGE_LOOKED);
            }
            // Faint on-top edge cage, always visible, so a buried shape never gets lost.
            drawRingGhost(col, ps, cam, shape, looked ? RING_GHOST_LOOKED : RING_GHOST);
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

    /** The committed box (and the pending-corner preview) as one region with tinted planes. */
    private static void renderBox(SubmitNodeCollector col, PoseStack ps, Vec3 cam,
                                  Minecraft mc, boolean handles, boolean boxCreate) {
        BlockHitResult hit = boxCreate ? Raycast.aimedBlock(mc) : null;
        if (boxCreate && aimedShape == null && hit != null) {
            WorldDraw.filledBlock(col, ps, cam, hit.getBlockPos(), BOX_HOVER);
        }
        boolean pending = boxCreate && boxCornerA != null;
        BoxRegion draw = pending
                ? new BoxRegion(boxCornerA, hit != null ? hit.getBlockPos() : boxCornerA, boxAxis)
                : box;
        if (draw == null) return;

        BlockPos mn = draw.min(), mx = draw.max();
        int sx = mx.getX() - mn.getX() + 1;
        int sy = mx.getY() - mn.getY() + 1;
        int sz = mx.getZ() - mn.getZ() + 1;
        WorldDraw.filledRegion(col, ps, cam, mn, sx, sy, sz, BOX_WALL);
        // The white outline shows while placing the second corner, or while the tool is in
        // hand and the crosshair is on the box; a faint ghost always reads through terrain.
        if (pending || (handles && rayHitsBox(mc, draw))) {
            WorldDraw.regionOutline(col, ps, cam, mn, sx, sy, sz, BOX_EDGE);
        }
        WorldDraw.boxEdgesSeeThrough(col, ps, cam, mn, sx, sy, sz, BOX_GHOST);

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
        int span = hi - lo + 1;
        BlockPos colLo = RingShape.offset(cell, shape.normal, lo);
        int sx = shape.normal == Direction.Axis.X ? span : 1;
        int sy = shape.normal == Direction.Axis.Y ? span : 1;
        int sz = shape.normal == Direction.Axis.Z ? span : 1;
        if (aimed) {
            WorldDraw.regionOutline(col, ps, cam, colLo, sx, sy, sz, CONTROL_OUTLINE);
        }
        // Faint on-top cage, always visible, so buried controls stay findable and targetable.
        WorldDraw.boxEdgesSeeThrough(col, ps, cam, colLo, sx, sy, sz,
                aimed ? CONTROL_AIMED : (corner ? CORNER_GHOST : CONTROL_GHOST));
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
        drawRing(col, ps, cam, preview, 0, 0, RING_PREVIEW, 0, true);
    }

    /**
     * The shape's ring band on every layer [hLo..hHi] along its normal, batched into ONE
     * geometry submission from the shape's cached raster (no per-frame membership trig, no
     * per-cell submits). Faces shared with a neighboring ring cell — sideways within the band,
     * or up/down between layers — are culled, so only the outer surface of the donut/tube is
     * drawn; {@code edgeArgb} lines every convex edge where two visible faces meet.
     */
    private static void drawRing(SubmitNodeCollector col, PoseStack ps, Vec3 cam, RingShape shape,
                                 int hLo, int hHi, int fillArgb, int edgeArgb, boolean allowRebuild) {
        shape.ensureGeometry(allowRebuild);
        int n = shape.rasterSize();
        if (n == 0) return;
        Direction.Axis normal = shape.normal;
        Direction.Axis uAxis = shape.uAxis();
        BlockPos c = shape.center;
        ps.pushPose();
        ps.translate(c.getX() - cam.x, c.getY() - cam.y, c.getZ() - cam.z);
        col.submitCustomGeometry(ps, net.minecraft.client.renderer.rendertype.RenderTypes.debugFilledBox(),
                (pose, vc) -> {
                    for (int i = 0; i < n; i++) {
                        int du = shape.rasterDu(i), dv = shape.rasterDv(i), m4 = shape.rasterMask(i);
                        for (int k = hLo; k <= hHi; k++) {
                            int mask = 0;
                            for (Direction d : Direction.values()) {
                                int step = d.getAxisDirection().getStep();
                                boolean neighbor;
                                if (d.getAxis() == normal) {
                                    int kk = k + step;
                                    neighbor = kk >= hLo && kk <= hHi;
                                } else if (d.getAxis() == uAxis) {
                                    neighbor = (m4 & (step > 0 ? 1 : 2)) != 0;
                                } else {
                                    neighbor = (m4 & (step > 0 ? 4 : 8)) != 0;
                                }
                                if (!neighbor) mask |= 1 << d.ordinal();
                            }
                            if (mask == 0) continue;
                            float ox = cellOffset(Direction.Axis.X, normal, uAxis, k, du, dv);
                            float oy = cellOffset(Direction.Axis.Y, normal, uAxis, k, du, dv);
                            float oz = cellOffset(Direction.Axis.Z, normal, uAxis, k, du, dv);
                            WorldDraw.emitCellFaces(pose, vc, ox, oy, oz, fillArgb, mask);
                            if (edgeArgb != 0) {
                                WorldDraw.emitCellEdges(pose, vc, ox, oy, oz, edgeArgb, mask);
                            }
                        }
                    }
                });
        ps.popPose();
    }

    /** The world-axis offset of a raster cell: k along the normal, (du, dv) in-plane. */
    private static float cellOffset(Direction.Axis axis, Direction.Axis normal,
                                    Direction.Axis uAxis, int k, int du, int dv) {
        if (axis == normal) return k;
        return axis == uAxis ? du : dv;
    }

    /**
     * The band's convex-edge lines re-emitted through the depth-ignoring see-through pipeline:
     * one batched submission tracing the shape's cage over terrain, so it never gets lost
     * underground. Uses the cached raster; visible-face masks mirror {@link #drawRing}.
     */
    private static void drawRingGhost(SubmitNodeCollector col, PoseStack ps, Vec3 cam,
                                      RingShape shape, int argb) {
        shape.ensureGeometry(false);
        int n = shape.rasterSize();
        if (n == 0) return;
        int hLo = shape.layerLo(), hHi = shape.layerHi();
        Direction.Axis normal = shape.normal;
        Direction.Axis uAxis = shape.uAxis();
        BlockPos c = shape.center;
        ps.pushPose();
        ps.translate(c.getX() - cam.x, c.getY() - cam.y, c.getZ() - cam.z);
        col.submitCustomGeometry(ps, WorldDraw.seeThrough(), (pose, vc) -> {
            for (int i = 0; i < n; i++) {
                int du = shape.rasterDu(i), dv = shape.rasterDv(i), m4 = shape.rasterMask(i);
                for (int k = hLo; k <= hHi; k++) {
                    int mask = 0;
                    for (Direction d : Direction.values()) {
                        int step = d.getAxisDirection().getStep();
                        boolean neighbor;
                        if (d.getAxis() == normal) {
                            int kk = k + step;
                            neighbor = kk >= hLo && kk <= hHi;
                        } else if (d.getAxis() == uAxis) {
                            neighbor = (m4 & (step > 0 ? 1 : 2)) != 0;
                        } else {
                            neighbor = (m4 & (step > 0 ? 4 : 8)) != 0;
                        }
                        if (!neighbor) mask |= 1 << d.ordinal();
                    }
                    if (mask == 0) continue;
                    float ox = cellOffset(Direction.Axis.X, normal, uAxis, k, du, dv);
                    float oy = cellOffset(Direction.Axis.Y, normal, uAxis, k, du, dv);
                    float oz = cellOffset(Direction.Axis.Z, normal, uAxis, k, du, dv);
                    WorldDraw.emitCellEdges(pose, vc, ox, oy, oz, argb, mask);
                }
            }
        });
        ps.popPose();
    }
}
