package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime state + behaviour for the block-marking tool (the {@link PlacementMode#MARKER} mode).
 *
 * <p>Marking is only live while the player holds the configured tool and the mode is MARKER. Left
 * click places <b>start</b> markers (turquoise), right click places <b>end</b> markers (amber).
 * Holding and dragging marks a straight line locked to one axis; while dragging the line is shown
 * as an <b>edge outline</b>, and on release the committed blocks become <b>filled faces</b>.
 *
 * <p>A drag (or single click) selects or deselects based on the block it starts on: starting on an
 * unmarked block adds the line, starting on a marked block removes it. An end marker is only
 * allowed on a block that is axis-aligned with an existing start marker and within
 * the configured max distance of it; removing a start marker auto-deselects any end marker
 * left without a valid start.
 *
 * <p>All state is client-side and per-session (not persisted). Tick + render both run on the client
 * thread, so the collections need no synchronisation.
 */
public final class MarkerManager {

    private MarkerManager() {}

    // Semi-opaque fills (committed) + opaque outlines (drag preview). ARGB, alpha byte required.
    private static final int START_FILL = 0x6640E0D0; // turquoise
    private static final int START_LINE = 0xFF40E0D0;
    private static final int END_FILL = 0x66FFB000;   // amber / orange-yellow
    private static final int END_LINE = 0xFFFFB000;
    private static final int FACE_FILL = 0x5560A0FF;  // translucent blue — auto-end scan face
    private static final int ARROW = 0xFF60A0FF;      // the scan-direction arrow off that face
    private static final float EXPAND = 0.005f;        // grow the box slightly to avoid z-fighting
    private static final float LINE_WIDTH = 2.0f;

    private static int maxEndDistance() {              // an end marker must be ≤ this from a start
        return Math.max(1, ConfigManager.get().maxMarkerDistance);
    }

    private enum Kind { NONE, START, END }

    public static final Set<BlockPos> startMarkers = new LinkedHashSet<>();
    public static final Set<BlockPos> endMarkers = new LinkedHashSet<>();

    private static String currentKey = null; // world+dimension key the loaded markers belong to

    private static Kind dragKind = Kind.NONE;
    private static BlockPos dragOrigin;
    private static Direction dragFace; // clicked face of the origin (drives the auto end marker)
    private static boolean dragRemoving; // whole drag removes (origin was marked) vs adds
    private static boolean dragConsumed; // ctrl group-clear already happened; ignore until release
    private static final List<BlockPos> dragLine = new ArrayList<>();

    // Marker corners state: first corner + its clicked face (the column axis of the volume).
    private static BlockPos cornerA;
    private static Direction cornerFace;
    private static boolean lastLeft, lastRight; // press-edge detection (corners mode)

    // Marker draw (freehand) stroke state.
    private static Kind strokeKind = Kind.NONE;
    private static boolean strokeRemoving;
    private static boolean strokeConsumed;
    private static boolean strokeRemovedStart;
    private static Direction strokeFace; // face the stroke began on — locks the auto-end direction

    /** True when marking is currently live (held tool in a marker mode). Markers are shared by tools. */
    public static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        return Gradient.currentPlacement(mc).isMarker();
    }

    /** Remove every marker (used by the settings screen's Clear Markers button) and persist. */
    public static void clearAll() {
        startMarkers.clear();
        endMarkers.clear();
        cancelDrag();
        saveCurrent();
    }

    // ---- persistence ----------------------------------------------------------------------------

    private static void syncWorld(Minecraft mc) {
        String key = worldKey(mc);
        if (!Objects.equals(key, currentKey)) {
            currentKey = key;
            MarkerStore.loadInto(key, startMarkers, endMarkers); // null key → clears (e.g. main menu)
            cancelDrag();
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

    private static void saveCurrent() {
        MarkerStore.store(currentKey, startMarkers, endMarkers);
    }

    // ---- input / drag (client tick) -------------------------------------------------------------

    public static void tick(Minecraft mc) {
        syncWorld(mc); // load this world's saved markers when the world/dimension changes
        PlacementMode pm = (mc.player == null || mc.level == null || mc.gui.screen() != null)
                ? PlacementMode.DISABLED : Gradient.currentPlacement(mc);
        if (!pm.isMarker()) {
            cancelDrag();
            clearCorner();     // leaving corners mode drops the pending first corner + volume
            endStroke(mc);
            lastLeft = lastRight = false;
            return;
        }
        if (pm != PlacementMode.MARKER_CORNERS) clearCorner();
        if (pm != PlacementMode.MARKER_DRAW) endStroke(mc);
        switch (pm) {
            case MARKER -> tickDrag(mc);
            case MARKER_CORNERS -> tickCorners(mc);
            case MARKER_DRAW -> tickFreehand(mc);
            default -> { }
        }
    }

    private static void tickDrag(Minecraft mc) {
        BlockHitResult hit = targetedHit(mc);
        BlockPos target = hit == null ? null : hit.getBlockPos();
        boolean left = mc.options.keyAttack.isDown();
        boolean right = mc.options.keyUse.isDown();

        switch (dragKind) {
            case NONE -> {
                if (left && target != null) beginDrag(Kind.START, target, hit.getDirection());
                else if (right && target != null) beginDrag(Kind.END, target, hit.getDirection());
            }
            case START -> {
                if (!left) commitDrag();
                else if (!dragConsumed && target != null) updateDrag(target);
            }
            case END -> {
                if (!right) commitDrag();
                else if (!dragConsumed && target != null) updateDrag(target);
            }
        }
    }

    /**
     * The aimed block for marking. Markers aren't placed blocks, so the ray reaches out to the
     * Marker-dist setting instead of block-place reach — every marker mode can mark at range.
     */
    static BlockHitResult targetedHit(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        double reach = Math.max(4.5, maxEndDistance());
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(mc.player.getViewVector(1.0f).scale(reach));
        BlockHitResult hit = mc.level.clip(new net.minecraft.world.level.ClipContext(
                eye, end, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
        return hit != null && hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private static void beginDrag(Kind kind, BlockPos origin, Direction face) {
        dragKind = kind;
        dragOrigin = origin.immutable();
        dragFace = face;
        // The whole drag adds or removes, decided by the origin: starting on a marked block removes.
        Set<BlockPos> set = (kind == Kind.START) ? startMarkers : endMarkers;
        dragRemoving = set.contains(dragOrigin);
        dragLine.clear();
        // Removing with the clear-connected modifier held wipes the whole connected plane at once.
        if (dragRemoving && Gradient.clearConnectedDown()) {
            removeConnectedPlane(kind, dragOrigin, face);
            dragConsumed = true; // swallow the rest of the hold
            return;
        }
        dragLine.add(dragOrigin);
    }

    /** Remove {@code origin} plus every same-kind marker connected to it in the clicked face's plane. */
    private static void removeConnectedPlane(Kind kind, BlockPos origin, Direction face) {
        Set<BlockPos> set = (kind == Kind.START) ? startMarkers : endMarkers;
        for (BlockPos p : connectedPlane(set, origin, face.getAxis())) {
            set.remove(p);
        }
        if (kind == Kind.START) endMarkers.removeIf(p -> !isEndAllowed(p));
        saveCurrent();
    }

    /** Markers in {@code set} connected to {@code seed} (diagonals count) sharing its {@code axis} plane. */
    private static List<BlockPos> connectedPlane(Set<BlockPos> set, BlockPos seed, Direction.Axis axis) {
        int plane = seed.get(axis);
        List<BlockPos> out = new ArrayList<>();
        java.util.ArrayDeque<BlockPos> bfs = new java.util.ArrayDeque<>();
        Set<BlockPos> seen = new java.util.HashSet<>();
        bfs.add(seed);
        seen.add(seed);
        while (!bfs.isEmpty()) {
            BlockPos p = bfs.poll();
            out.add(p);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos n = p.offset(dx, dy, dz);
                        if (n.get(axis) != plane || seen.contains(n) || !set.contains(n)) continue;
                        seen.add(n);
                        bfs.add(n);
                    }
                }
            }
        }
        return out;
    }

    /** Rebuild the preview line from the origin to {@code target}, locked to the dominant axis. */
    private static void updateDrag(BlockPos target) {
        dragLine.clear();
        int dx = target.getX() - dragOrigin.getX();
        int dy = target.getY() - dragOrigin.getY();
        int dz = target.getZ() - dragOrigin.getZ();
        int adx = Math.abs(dx), ady = Math.abs(dy), adz = Math.abs(dz);

        int ux = 0, uy = 0, uz = 0, steps;
        if (adx >= ady && adx >= adz) { ux = Integer.signum(dx); steps = adx; }
        else if (ady >= adz)          { uy = Integer.signum(dy); steps = ady; }
        else                          { uz = Integer.signum(dz); steps = adz; }

        for (int i = 0; i <= steps; i++) {
            dragLine.add(new BlockPos(
                    dragOrigin.getX() + ux * i,
                    dragOrigin.getY() + uy * i,
                    dragOrigin.getZ() + uz * i));
        }
    }

    private static void commitDrag() {
        if (dragConsumed) { // the ctrl group-clear already did the work
            cancelDrag();
            return;
        }
        if (dragKind != Kind.NONE && !dragLine.isEmpty()) {
            Set<BlockPos> set = (dragKind == Kind.START) ? startMarkers : endMarkers;
            boolean removedStart = false;
            for (BlockPos p : dragLine) {
                if (dragRemoving) {
                    if (set.remove(p) && dragKind == Kind.START) removedStart = true;
                } else if (dragKind == Kind.START) {
                    set.add(p);
                } else if (isEndAllowed(p)) {
                    set.add(p);
                }
            }
            // Removing start markers can orphan end markers — drop any that lost their start.
            if (removedStart) endMarkers.removeIf(p -> !isEndAllowed(p));

            // Auto-place end: every added start marker drops an end marker out from the clicked
            // face (a drag uses the face the drag started on, so a swept line gets a matching
            // line of ends).
            if (!dragRemoving && dragKind == Kind.START && ConfigManager.get().autoPlaceEnd) {
                for (BlockPos p : dragLine) autoPlaceEndFor(p, dragFace);
            }
            saveCurrent();
        }
        cancelDrag();
    }

    /**
     * Auto end marker: scan out from {@code start} along the clicked face's normal and mark the
     * first non-air block (click a floor's top face and it finds the ceiling; click a wall's side
     * and it scans out sideways). Nothing but air within the max marker distance → the marker is
     * dropped at max distance instead, so the feature still works out in the open.
     */
    private static void autoPlaceEndFor(BlockPos start, Direction face) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || face == null) return;
        int max = maxEndDistance();
        for (int k = 1; k <= max; k++) {
            BlockPos p = start.relative(face, k);
            if (!mc.level.getBlockState(p).isAir()) {
                endMarkers.add(p);
                return;
            }
        }
        endMarkers.add(start.relative(face, max));
    }

    // ---- marker corners ---------------------------------------------------------------------------

    /**
     * Corners mode: left click sets the first (start-side) corner and remembers the clicked face —
     * that face's axis is the column direction. While a corner is pending, a 1px box is drawn from
     * it to the aimed block. Right click sets the opposite corner and fills the volume: start
     * markers over the first corner's plane, end markers over the opposite plane, one colinear
     * pair per column. With Auto end marker on, each column adapts to the terrain instead: the
     * start sits on the last solid block before the column's first air gap, the end on the first
     * non-air block after it (or the far plane when it's all air).
     */
    private static void tickCorners(Minecraft mc) {
        BlockHitResult hit = targetedHit(mc);
        boolean left = mc.options.keyAttack.isDown();
        boolean right = mc.options.keyUse.isDown();
        if (left && !lastLeft && hit != null) {
            BlockPos target = hit.getBlockPos();
            if (startMarkers.contains(target)) {
                // Marked blocks toggle off in every marker mode (Ctrl = the connected plane).
                toggleOff(Kind.START, target, hit.getDirection());
            } else {
                cornerA = target.immutable(); // re-clicking simply moves the corner
                cornerFace = hit.getDirection();
            }
        }
        if (right && !lastRight && hit != null) {
            if (cornerA != null) {
                commitCorners(mc, hit.getBlockPos());
                clearCorner();
            } else if (endMarkers.contains(hit.getBlockPos())) {
                toggleOff(Kind.END, hit.getBlockPos(), hit.getDirection());
            } else {
                overlay(mc, "Corners: left-click the first corner first");
            }
        }
        lastLeft = left;
        lastRight = right;
    }

    /** Toggle one marker off — or, with the clear-connected modifier held, its whole plane. */
    private static void toggleOff(Kind kind, BlockPos pos, Direction face) {
        if (Gradient.clearConnectedDown()) {
            removeConnectedPlane(kind, pos, face);
            return;
        }
        Set<BlockPos> set = (kind == Kind.START) ? startMarkers : endMarkers;
        set.remove(pos);
        if (kind == Kind.START) endMarkers.removeIf(p -> !isEndAllowed(p));
        saveCurrent();
    }

    private static void clearCorner() {
        cornerA = null;
        cornerFace = null;
    }

    private static void commitCorners(Minecraft mc, BlockPos cornerB) {
        Direction.Axis axis = cornerFace.getAxis();
        int a0 = cornerA.get(axis), b0 = cornerB.get(axis);
        int sign = Integer.signum(b0 - a0);
        if (sign == 0) sign = cornerFace.getAxisDirection().getStep(); // flat box: fall back to the face
        int max = maxEndDistance();
        if (Math.abs(b0 - a0) > max) {
            b0 = a0 + sign * max; // a pair can't be further apart than Marker dist
            overlay(mc, "Corners: depth clamped to Marker dist (" + max + ")");
        }
        Direction along = Direction.fromAxisAndDirection(axis,
                sign > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
        int len = Math.abs(b0 - a0);
        if (len == 0) { // start and end would be the same block in every column
            overlay(mc, "Corners: the box has no depth — pick a further corner");
            return;
        }

        // The two axes spanning the marker planes.
        Direction.Axis uAxis = axis == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X;
        Direction.Axis vAxis = axis == Direction.Axis.Z ? Direction.Axis.Y : Direction.Axis.Z;
        int u1 = Math.min(cornerA.get(uAxis), cornerB.get(uAxis));
        int u2 = Math.max(cornerA.get(uAxis), cornerB.get(uAxis));
        int v1 = Math.min(cornerA.get(vAxis), cornerB.get(vAxis));
        int v2 = Math.max(cornerA.get(vAxis), cornerB.get(vAxis));

        boolean auto = ConfigManager.get().autoPlaceEnd;
        int placed = 0;
        for (int u = u1; u <= u2; u++) {
            for (int v = v1; v <= v2; v++) {
                BlockPos startCell = cellAt(axis, a0, uAxis, u, vAxis, v);
                BlockPos endCell = cellAt(axis, b0, uAxis, u, vAxis, v);
                if (auto) {
                    if (!placeAutoColumn(mc, startCell, endCell, along, len)) continue;
                } else {
                    startMarkers.add(startCell);
                    endMarkers.add(endCell);
                }
                placed++;
            }
        }
        saveCurrent();
        overlay(mc, "Corners: marked " + placed + " column" + (placed == 1 ? "" : "s"));
    }

    private static BlockPos cellAt(Direction.Axis axis, int a, Direction.Axis uAxis, int u,
                                   Direction.Axis vAxis, int v) {
        int x = axis == Direction.Axis.X ? a : (uAxis == Direction.Axis.X ? u : v);
        int y = axis == Direction.Axis.Y ? a : (uAxis == Direction.Axis.Y ? u : v);
        int z = axis == Direction.Axis.Z ? a : (vAxis == Direction.Axis.Z ? v : u);
        return new BlockPos(x, y, z);
    }

    /**
     * Terrain-adaptive corners column (Auto end marker on): the start marker sits on the last
     * solid block before the column's first air gap, the end marker on the first non-air block
     * after it — or on the far plane when the rest is all air. Everything stays strictly inside
     * the drawn volume: a start plane already in air (no start block inside), a fully buried
     * column, or a column whose start and end would collapse onto the same block is skipped.
     */
    private static boolean placeAutoColumn(Minecraft mc, BlockPos startCell, BlockPos endCell,
                                           Direction along, int len) {
        if (mc.level.getBlockState(startCell).isAir()) return false; // no start block inside the volume
        BlockPos start = null;
        for (int k = 1; k <= len; k++) { // climb to the last solid block before the air gap
            BlockPos p = startCell.relative(along, k);
            if (mc.level.getBlockState(p).isAir()) {
                start = p.relative(along.getOpposite());
                break;
            }
        }
        if (start == null) return false; // fully buried column — nothing to paint

        int steps = Math.min(maxEndDistance(),
                Math.abs(endCell.get(along.getAxis()) - start.get(along.getAxis())));
        if (steps <= 0) return false; // the start reached the far plane — no room for an end
        BlockPos end = null;
        for (int k = 1; k <= steps; k++) {
            BlockPos p = start.relative(along, k);
            if (!mc.level.getBlockState(p).isAir()) {
                end = p;
                break;
            }
        }
        if (end == null) end = start.relative(along, steps); // all air → the far plane
        if (end.equals(start)) return false;
        startMarkers.add(start);
        if (isEndAllowed(end)) endMarkers.add(end);
        return true;
    }

    // ---- marker draw (freehand) ---------------------------------------------------------------------

    /**
     * Freehand mode: hold left to paint start markers over whatever the crosshair touches, hold
     * right for end markers (only where a start marker lines up). A stroke that begins on a
     * marked block erases instead — with the clear-connected modifier held, the whole connected
     * plane goes at once.
     */
    private static void tickFreehand(Minecraft mc) {
        BlockHitResult hit = targetedHit(mc);
        boolean left = mc.options.keyAttack.isDown();
        boolean right = mc.options.keyUse.isDown();
        if (strokeKind == Kind.NONE) {
            if (left && hit != null) beginStroke(mc, Kind.START, hit);
            else if (right && hit != null) beginStroke(mc, Kind.END, hit);
        } else if ((strokeKind == Kind.START && !left) || (strokeKind == Kind.END && !right)) {
            endStroke(mc);
        } else if (!strokeConsumed && hit != null) {
            applyStroke(hit);
        }
    }

    private static void beginStroke(Minecraft mc, Kind kind, BlockHitResult hit) {
        strokeKind = kind;
        Set<BlockPos> set = (kind == Kind.START) ? startMarkers : endMarkers;
        strokeRemoving = set.contains(hit.getBlockPos());
        strokeRemovedStart = false;
        strokeConsumed = false;
        strokeFace = hit.getDirection();
        if (strokeRemoving && Gradient.clearConnectedDown()) {
            removeConnectedPlane(kind, hit.getBlockPos(), hit.getDirection());
            strokeConsumed = true; // swallow the rest of the hold
            return;
        }
        applyStroke(hit);
    }

    private static void applyStroke(BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos().immutable();
        Set<BlockPos> set = (strokeKind == Kind.START) ? startMarkers : endMarkers;
        if (strokeRemoving) {
            if (set.remove(pos) && strokeKind == Kind.START) strokeRemovedStart = true;
        } else if (strokeKind == Kind.START) {
            // Auto ends scan along the face the stroke BEGAN on — brushing over a block's side
            // mid-sweep must not shoot an end marker off sideways.
            if (set.add(pos) && ConfigManager.get().autoPlaceEnd) {
                autoPlaceEndFor(pos, strokeFace);
            }
        } else if (isEndAllowed(pos)) {
            set.add(pos);
        }
    }

    private static void endStroke(Minecraft mc) {
        if (strokeKind == Kind.NONE) return;
        if (strokeRemovedStart) endMarkers.removeIf(p -> !isEndAllowed(p));
        saveCurrent();
        strokeKind = Kind.NONE;
        strokeRemoving = false;
        strokeConsumed = false;
        strokeRemovedStart = false;
        strokeFace = null;
    }

    private static void overlay(Minecraft mc, String msg) {
        if (mc.player != null) {
            mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(msg));
        }
    }

    private static void cancelDrag() {
        dragKind = Kind.NONE;
        dragOrigin = null;
        dragFace = null;
        dragRemoving = false;
        dragConsumed = false;
        dragLine.clear();
    }

    /**
     * An end marker is allowed only where a start marker is axis-aligned (shares ≥2 coordinates, so
     * same row/column) AND within the configured max marker distance along that line.
     */
    private static boolean isEndAllowed(BlockPos p) {
        int max = maxEndDistance();
        for (BlockPos s : startMarkers) {
            int eq = (s.getX() == p.getX() ? 1 : 0)
                    + (s.getY() == p.getY() ? 1 : 0)
                    + (s.getZ() == p.getZ() ? 1 : 0);
            if (eq >= 2) {
                // Colinear, so exactly one axis differs; Manhattan distance == distance along it.
                int dist = Math.abs(s.getX() - p.getX())
                        + Math.abs(s.getY() - p.getY())
                        + Math.abs(s.getZ() - p.getZ());
                if (dist <= max) return true;
            }
        }
        return false;
    }

    // ---- rendering (level render event) ---------------------------------------------------------

    /** Called from each loader's level-render submit hook with the collector + camera position. */
    public static void render(SubmitNodeCollector col, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        PlacementMode pm = Gradient.currentPlacement(mc);
        // The auto-end face preview can show before any marker exists (e.g. a fresh world).
        // Corners mode has its own volume preview instead.
        boolean preview = ConfigManager.get().autoPlaceEnd && active()
                && pm != PlacementMode.MARKER_CORNERS;
        boolean corners = pm == PlacementMode.MARKER_CORNERS && cornerA != null;
        if (!preview && !corners
                && startMarkers.isEmpty() && endMarkers.isEmpty() && dragLine.isEmpty()) return;

        // Submit with a FRESH identity PoseStack (as vanilla's submitFeatures does for block
        // entities + the block outline) and let the collector apply the camera rotation. Using the
        // event's own poseStack places geometry against a different basis than the collector
        // renders with, which shows up as a one-frame drift while moving. Geometry is made
        // camera-relative by translating each box by (worldPos - cameraPos).
        PoseStack ps = new PoseStack();

        // Committed markers — filled translucent faces.
        for (BlockPos p : startMarkers) filled(col, ps, cam, p, START_FILL);
        for (BlockPos p : endMarkers) filled(col, ps, cam, p, END_FILL);

        // In-progress drag — edge outline preview of the blocks the commit will affect.
        if (dragKind != Kind.NONE) {
            int lineColor = (dragKind == Kind.START) ? START_LINE : END_LINE;
            Set<BlockPos> set = (dragKind == Kind.START) ? startMarkers : endMarkers;
            for (BlockPos p : dragLine) {
                boolean affected = dragRemoving
                        ? set.contains(p)                                  // will be removed
                        : (dragKind == Kind.START || isEndAllowed(p));     // will be added
                if (affected) outline(col, ps, cam, p, lineColor);
            }
        }

        if (corners) renderCornersPreview(mc, col, ps, cam);
        if (preview) renderAutoEndPreview(col, ps, cam);
    }

    /**
     * Corners mode with a pending first corner: the corner glows turquoise, the aimed block amber,
     * and a 1px box spans the volume that the second click will fill with marker columns.
     */
    private static void renderCornersPreview(Minecraft mc, SubmitNodeCollector col, PoseStack ps, Vec3 cam) {
        filled(col, ps, cam, cornerA, START_FILL);
        BlockHitResult hit = targetedHit(mc);
        if (hit == null) return;
        BlockPos b = hit.getBlockPos();
        if (!b.equals(cornerA)) filled(col, ps, cam, b, END_FILL);
        boxOutline(col, ps, cam, cornerA, b, 0xFFE0E0E0);
    }

    /** 1px outline of the block-aligned box spanning corners {@code a} and {@code b}. */
    private static void boxOutline(SubmitNodeCollector col, PoseStack ps, Vec3 cam,
                                   BlockPos a, BlockPos b, int argb) {
        int minX = Math.min(a.getX(), b.getX()), minY = Math.min(a.getY(), b.getY()),
                minZ = Math.min(a.getZ(), b.getZ());
        int sx = Math.abs(a.getX() - b.getX()) + 1, sy = Math.abs(a.getY() - b.getY()) + 1,
                sz = Math.abs(a.getZ() - b.getZ()) + 1;
        ps.pushPose();
        ps.translate(minX - cam.x, minY - cam.y, minZ - cam.z);
        col.submitShapeOutline(ps, Shapes.box(0, 0, 0, sx, sy, sz),
                RenderTypes.lines(), argb, LINE_WIDTH, false);
        ps.popPose();
    }

    // ---- auto-end face preview -------------------------------------------------------------------

    /**
     * With auto end marker on, show which face the end-marker scan will leave from: the aimed face
     * is tinted translucent blue with a thin arrow along its normal. Hidden on blocks that are
     * already start markers (clicking those removes instead of scanning). During an adding start
     * drag the preview runs down the whole line, using the face the drag began on.
     */
    private static void renderAutoEndPreview(SubmitNodeCollector col, PoseStack ps, Vec3 cam) {
        if (dragKind == Kind.END || (dragKind != Kind.NONE && dragRemoving)) return;
        if (dragKind == Kind.START) {
            for (BlockPos p : dragLine) facePreview(col, ps, cam, p, dragFace);
            return;
        }
        BlockHitResult hit = targetedHit(Minecraft.getInstance());
        if (hit == null || startMarkers.contains(hit.getBlockPos())) return;
        facePreview(col, ps, cam, hit.getBlockPos(), hit.getDirection());
    }

    private static void facePreview(SubmitNodeCollector col, PoseStack ps, Vec3 cam, BlockPos pos, Direction face) {
        FaceOverlay.submit(col, ps, cam, pos, face, FACE_FILL, ARROW);
    }

    private static void outline(SubmitNodeCollector col, PoseStack ps, Vec3 cam, BlockPos pos, int argb) {
        ps.pushPose();
        ps.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
        col.submitShapeOutline(ps, Shapes.block(), RenderTypes.lines(), argb, LINE_WIDTH, false);
        ps.popPose();
    }

    private static void filled(SubmitNodeCollector col, PoseStack ps, Vec3 cam, BlockPos pos, int argb) {
        ps.pushPose();
        ps.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
        col.submitCustomGeometry(ps, RenderTypes.debugFilledBox(), (pose, vc) -> emitBox(pose, vc, argb));
        ps.popPose();
    }

    private static void emitBox(PoseStack.Pose pose, VertexConsumer vc, int argb) {
        float a = -EXPAND, b = 1 + EXPAND;
        quad(vc, pose, argb, a, a, a, b, a, a, b, a, b, a, a, b); // down  (y=a)
        quad(vc, pose, argb, a, b, a, a, b, b, b, b, b, b, b, a); // up    (y=b)
        quad(vc, pose, argb, a, a, a, a, b, a, b, b, a, b, a, a); // north (z=a)
        quad(vc, pose, argb, a, a, b, b, a, b, b, b, b, a, b, b); // south (z=b)
        quad(vc, pose, argb, a, a, a, a, a, b, a, b, b, a, b, a); // west  (x=a)
        quad(vc, pose, argb, b, a, a, b, b, a, b, b, b, b, a, b); // east  (x=b)
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose, int argb,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4) {
        vc.addVertex(pose, x1, y1, z1).setColor(argb);
        vc.addVertex(pose, x2, y2, z2).setColor(argb);
        vc.addVertex(pose, x3, y3, z3).setColor(argb);
        vc.addVertex(pose, x4, y4, z4).setColor(argb);
    }
}
