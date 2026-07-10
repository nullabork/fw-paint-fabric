package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
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
 * Runtime state + behaviour for the block-marking tool (the {@link ToolMode#MARKER} mode).
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
    private static final List<BlockPos> dragLine = new ArrayList<>();

    /** True when marking is currently live (held tool in MARKER mode). Markers are shared by tools. */
    public static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        return Gradient.currentMode(mc) == ToolMode.MARKER;
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
        if (mc.player == null || mc.level == null || mc.gui.screen() != null || !active()) {
            cancelDrag();
            return;
        }
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
                else if (target != null) updateDrag(target);
            }
            case END -> {
                if (!right) commitDrag();
                else if (target != null) updateDrag(target);
            }
        }
    }

    private static BlockHitResult targetedHit(Minecraft mc) {
        if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            return bhr;
        }
        return null;
    }

    private static void beginDrag(Kind kind, BlockPos origin, Direction face) {
        dragKind = kind;
        dragOrigin = origin.immutable();
        dragFace = face;
        // The whole drag adds or removes, decided by the origin: starting on a marked block removes.
        Set<BlockPos> set = (kind == Kind.START) ? startMarkers : endMarkers;
        dragRemoving = set.contains(dragOrigin);
        dragLine.clear();
        dragLine.add(dragOrigin);
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

    private static void cancelDrag() {
        dragKind = Kind.NONE;
        dragOrigin = null;
        dragFace = null;
        dragRemoving = false;
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

    public static void render(LevelRenderContext ctx) {
        // The auto-end face preview can show before any marker exists (e.g. a fresh world).
        boolean preview = ConfigManager.get().autoPlaceEnd && active();
        if (!preview && startMarkers.isEmpty() && endMarkers.isEmpty() && dragLine.isEmpty()) return;

        // Submit with a FRESH identity PoseStack (as vanilla's submitFeatures does for block
        // entities + the block outline) and let the collector apply the camera rotation. Using the
        // context's own poseStack places geometry against a different basis than the collector
        // renders with, which shows up as a one-frame drift while moving. Geometry is made
        // camera-relative by translating each box by (worldPos - cameraPos).
        PoseStack ps = new PoseStack();
        SubmitNodeCollector col = ctx.submitNodeCollector();
        Vec3 cam = ctx.levelState().cameraRenderState.pos;

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

        if (preview) renderAutoEndPreview(col, ps, cam);
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
        if (face == null) return;
        ps.pushPose();
        ps.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
        col.submitCustomGeometry(ps, RenderTypes.debugFilledBox(), (pose, vc) -> emitFacePreview(pose, vc, face));
        ps.popPose();
    }

    /** The face tint + normal arrow, in block-local coordinates (the pose already sits at the block). */
    private static void emitFacePreview(PoseStack.Pose pose, VertexConsumer vc, Direction face) {
        Vec3 n = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        // Two unit tangents spanning the face.
        Vec3 u = n.x != 0 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 v = n.z != 0 ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1);
        Vec3 c = new Vec3(0.5, 0.5, 0.5).add(n.scale(0.5 + EXPAND)); // face centre, just off the surface

        quadDS(vc, pose, FACE_FILL,
                c.subtract(u.scale(0.5)).subtract(v.scale(0.5)),
                c.add(u.scale(0.5)).subtract(v.scale(0.5)),
                c.add(u.scale(0.5)).add(v.scale(0.5)),
                c.subtract(u.scale(0.5)).add(v.scale(0.5)));

        // The arrow: a thin crossed shaft along the normal plus a crossed triangle head, so it
        // reads as an arrow from any viewing angle.
        Vec3 shaft = n.scale(0.35), tip = n.scale(0.5);
        for (Vec3 t : new Vec3[]{u, v}) {
            Vec3 w = t.scale(0.015), hw = t.scale(0.07);
            quadDS(vc, pose, ARROW, c.subtract(w), c.add(w), c.add(w).add(shaft), c.subtract(w).add(shaft));
            quadDS(vc, pose, ARROW, c.subtract(hw).add(shaft), c.add(hw).add(shaft), c.add(tip), c.add(tip));
        }
    }

    /** Double-sided quad from Vec3 corners (emitted once per winding so it shows from both sides). */
    private static void quadDS(VertexConsumer vc, PoseStack.Pose pose, int argb, Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        quad(vc, pose, argb, (float) a.x, (float) a.y, (float) a.z, (float) b.x, (float) b.y, (float) b.z,
                (float) c.x, (float) c.y, (float) c.z, (float) d.x, (float) d.y, (float) d.z);
        quad(vc, pose, argb, (float) d.x, (float) d.y, (float) d.z, (float) c.x, (float) c.y, (float) c.z,
                (float) b.x, (float) b.y, (float) b.z, (float) a.x, (float) a.y, (float) a.z);
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
