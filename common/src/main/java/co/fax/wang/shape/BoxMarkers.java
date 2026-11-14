package co.fax.wang.shape;

import co.fax.wang.Gradient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The box marker (Box mode): one {@link BoxRegion} per world. Created with LMB, LMB (first corner,
 * opposite corner — either may be a block or, aimed at nothing, a point at Marker reach); RMB
 * cancels a half-made box. The face clicked for the first corner picks the gradient axis (open
 * air: Y); the first corner's side is the START.
 *
 * <p>Editing (paint tool in hand, any mode): six green face handles drag their face along its axis
 * (RMB pushes it out a block, modifier+RMB pulls it in), the white move cube in the middle drags
 * the whole box (Alt locks it to an axis), scroll pushes / pulls the box along the aimed face's
 * axis (its gradient axis from the move cube) and modifier+scroll grows / shrinks that face. The
 * first corner shows the start colour and the opposite corner the end colour. The cog opens the
 * box's toolbar: gradient axis X | Y | Z, flip, rendering (its {@link MarkerStyle}), delete.
 */
final class BoxMarkers {

    private BoxMarkers() {}

    /** The box's toolbar / cog owner (there is one box, but edits replace the region object). */
    static final Object OWNER = new Object() {
        @Override
        public String toString() {
            return "box";
        }
    };

    private static final int BOX_WALL = 0x2260A0FF;
    private static final int BOX_EDGE = 0xC0E0E0E0;
    private static final int BOX_GHOST = 0x5CE0E0E0;
    private static final int BOX_START = Gizmo.alpha(co.fax.wang.ui.Ui.START, 0x44); // gradient start plane
    private static final int BOX_END = Gizmo.alpha(co.fax.wang.ui.Ui.END, 0x44);     // gradient end plane
    private static final int HOVER = 0x5560A0FF;    // box mode: where the next corner goes
    private static final double HANDLE_OUT = 0.6;   // face handles float this far off their face
    private static final double CORNER_HALF = 0.18;

    private enum Kind { FACE, MOVE, COG }

    static BoxRegion box;
    /** How the box is drawn (the Rendering page of its toolbar); a new box starts with the default look. */
    static MarkerStyle style = new MarkerStyle();

    // Creation (Box mode): the first corner, pending until the second LMB.
    private static BlockPos pendingA;
    private static Direction.Axis pendingAxis;

    // Aim + drag.
    private static Kind aimedKind;
    private static Direction aimedFace;
    private static double aimedDist = Double.POSITIVE_INFINITY;
    private static Kind dragKind;
    private static Direction dragFace;
    private static BoxRegion dragStart;   // the box as it was at the grab (move drags offset it)
    private static Vec3 dragGrab;         // the view-plane hit at the grab
    private static Direction.Axis lockAxis;

    static void reset() {
        pendingA = null;
        pendingAxis = null;
        aimedKind = null;
        dragKind = null;
    }

    static boolean creating() {
        return pendingA != null;
    }

    /** Leaving Box mode drops a half-made box. */
    static void cancelCreation() {
        pendingA = null;
        pendingAxis = null;
    }

    static boolean dragging() {
        return dragKind != null;
    }

    static boolean controlAimed() {
        return dragKind != null || aimedKind != null;
    }

    static boolean cogAimed() {
        return dragKind == null && aimedKind == Kind.COG;
    }

    static double controlDist() {
        return dragKind != null ? 0 : aimedKind != null ? aimedDist : Double.POSITIVE_INFINITY;
    }

    static void dropAim() {
        if (dragKind == null) aimedKind = null;
    }

    static void remove() {
        box = null;
        style = new MarkerStyle();
        dragKind = null;
        aimedKind = null;
        MarkerText.say("Box removed");
        ShapeMarkers.saveLines();
    }

    // ---- geometry of the controls ---------------------------------------------------------------

    /** World centre of a face, floated {@link #HANDLE_OUT} off it: where its handle sits. */
    private static Vec3 facePos(BoxRegion b, Direction d) {
        BlockPos mn = b.min(), mx = b.max();
        double cx = (mn.getX() + mx.getX() + 1) / 2.0, cy = (mn.getY() + mx.getY() + 1) / 2.0,
                cz = (mn.getZ() + mx.getZ() + 1) / 2.0;
        double hx = (mx.getX() - mn.getX() + 1) / 2.0 + HANDLE_OUT, hy = (mx.getY() - mn.getY() + 1) / 2.0 + HANDLE_OUT,
                hz = (mx.getZ() - mn.getZ() + 1) / 2.0 + HANDLE_OUT;
        return new Vec3(cx + d.getStepX() * hx, cy + d.getStepY() * hy, cz + d.getStepZ() * hz);
    }

    private static Vec3 centre(BoxRegion b) {
        BlockPos mn = b.min(), mx = b.max();
        return new Vec3((mn.getX() + mx.getX() + 1) / 2.0, (mn.getY() + mx.getY() + 1) / 2.0,
                (mn.getZ() + mx.getZ() + 1) / 2.0);
    }

    /**
     * The outer box vertex at corner A (or B): on each axis the side that corner holds — where
     * the corners share a coordinate, A takes the low side and B the high one.
     */
    private static Vec3 cornerVertex(BoxRegion b, boolean cornerA) {
        double[] v = new double[3];
        Direction.Axis[] axes = Direction.Axis.values();
        for (int i = 0; i < 3; i++) {
            int a = b.cornerA.get(axes[i]), c = b.cornerB.get(axes[i]);
            v[i] = cornerA ? (a <= c ? a : a + 1) : (c >= a ? c + 1 : c);
        }
        return new Vec3(v[0], v[1], v[2]);
    }

    /** Cog: just off the box's top corner nearest the camera. */
    static Vec3 cogPos(BoxRegion b, Vec3 eye) {
        BlockPos mn = b.min(), mx = b.max();
        boolean lowX = Math.abs(eye.x - mn.getX()) < Math.abs(eye.x - (mx.getX() + 1));
        boolean lowZ = Math.abs(eye.z - mn.getZ()) < Math.abs(eye.z - (mx.getZ() + 1));
        double x = lowX ? mn.getX() - 0.6 : mx.getX() + 1.6;
        double z = lowZ ? mn.getZ() - 0.6 : mx.getZ() + 1.6;
        return new Vec3(x, mx.getY() + 1.6, z);
    }

    // ---- aim --------------------------------------------------------------------------------

    static void updateAim(Minecraft mc, boolean handles) {
        if (dragKind != null) return; // the drag owns the aim until release
        aimedKind = null;
        aimedDist = Double.POSITIVE_INFINITY;
        if (!handles || box == null) return;
        Vec3 eye = Raycast.eye(mc), dir = Raycast.look(mc);
        for (Direction d : Direction.values()) {
            double t = Gizmo.rayCube(eye, dir, facePos(box, d), Gizmo.CTRL_HALF);
            if (t >= 0 && t < aimedDist) {
                aimedDist = t;
                aimedKind = Kind.FACE;
                aimedFace = d;
            }
        }
        double t = Gizmo.rayCube(eye, dir, centre(box), Gizmo.CTRL_HALF);
        if (t >= 0 && t < aimedDist) {
            aimedDist = t;
            aimedKind = Kind.MOVE;
        }
        if (ShapeMarkers.cogShown(OWNER)) {
            t = Gizmo.rayCube(eye, dir, cogPos(box, eye), Gizmo.CTRL_HALF);
            if (t >= 0 && t < aimedDist) {
                aimedDist = t;
                aimedKind = Kind.COG;
            }
        }
    }

    /** True when the view ray passes through the box (the "looked at" read). */
    static boolean looked(Minecraft mc) {
        return box != null && rayHits(mc, box);
    }

    static boolean rayHits(Minecraft mc, BoxRegion b) {
        Vec3 eye = Raycast.eye(mc);
        Vec3 dir = Raycast.look(mc);
        BlockPos mn = b.min(), mx = b.max();
        return ShapeMath.rayBoxIntersect(eye.x, eye.y, eye.z, dir.x, dir.y, dir.z,
                mn.getX(), mn.getY(), mn.getZ(),
                mx.getX() + 1, mx.getY() + 1, mx.getZ() + 1) >= 0;
    }

    static String hint() {
        if (aimedKind == null) return null;
        String mod = MarkerText.mod(), alt = MarkerText.axis();
        return switch (aimedKind) {
            case FACE -> "Face · drag resize · RMB +1 · " + mod + "+RMB −1 · scroll push/pull · "
                    + mod + "+scroll resize · MMB delete box";
            case MOVE -> "Move · drag move · " + alt + " axis lock · scroll push/pull · MMB delete box";
            case COG -> "Cog · LMB options · MMB delete box";
        };
    }

    // ---- input ------------------------------------------------------------------------------

    /** LMB on an aimed face handle / move cube: grab it. */
    static void grab(Minecraft mc) {
        if (box == null || aimedKind == null || aimedKind == Kind.COG) return;
        dragKind = aimedKind;
        dragFace = aimedFace;
        dragStart = box;
        dragGrab = Gizmo.viewPlaneHit(Raycast.eye(mc), Raycast.look(mc), centre(box));
        lockAxis = null;
        ShapeMarkers.touch(OWNER);
    }

    /** Each tick while LMB is held on a grab; release saves. */
    static void tickDrag(Minecraft mc, boolean attack) {
        if (dragKind == null) return;
        if (!attack || box == null) {
            dragKind = null;
            ShapeMarkers.saveLines();
            return;
        }
        Vec3 eye = Raycast.eye(mc), dir = Raycast.look(mc);
        if (dragKind == Kind.FACE) {
            // The face follows the point on its own axis line nearest the view ray.
            Vec3 p = Gizmo.onAxis(eye, dir, facePos(dragStart, dragFace), dragFace.getAxis());
            double along = component(p, dragFace.getAxis()) - dragFace.getAxisDirection().getStep() * HANDLE_OUT;
            int coord = dragFace.getAxisDirection() == Direction.AxisDirection.POSITIVE
                    ? (int) Math.round(along) - 1 : (int) Math.round(along);
            box = dragStart.withFace(dragFace, coord);
        } else if (dragGrab != null) {
            Vec3 hit = Gizmo.viewPlaneHit(eye, dir, centre(dragStart));
            if (hit == null) return;
            Vec3 d = hit.subtract(dragGrab);
            if (Gradient.axisLockDown()) {
                if (lockAxis == null) {
                    if (d.length() < 0.3) return;
                    lockAxis = Gizmo.dominantAxis(d);
                }
                d = Gizmo.onAxis(eye, dir, centre(dragStart), lockAxis).subtract(centre(dragStart));
            } else {
                lockAxis = null;
            }
            box = dragStart.moved((int) Math.round(d.x), (int) Math.round(d.y), (int) Math.round(d.z));
        }
    }

    /** RMB on an aimed face handle: push it out a block (modifier: pull it in). */
    static boolean quickAction() {
        if (box == null || aimedKind != Kind.FACE) return false;
        int step = Gradient.modifierDown() ? -1 : 1;
        box = box.withFace(aimedFace, box.faceCoord(aimedFace) + aimedFace.getAxisDirection().getStep() * step);
        ShapeMarkers.touch(OWNER);
        ShapeMarkers.saveLines();
        return true;
    }

    /**
     * Scroll while aiming the box: push / pull it along the aimed face's axis (the gradient axis
     * from the move cube or the body); with the modifier, grow / shrink that face (body: the end
     * side). Returns true when consumed.
     */
    static boolean onScroll(Minecraft mc, double deltaY) {
        if (box == null || dragKind != null) return false;
        Direction face;
        if (aimedKind == Kind.FACE) {
            face = aimedFace;
        } else if (aimedKind == Kind.MOVE || (aimedKind == null && looked(mc))) {
            int a = box.cornerA.get(box.axis), b = box.cornerB.get(box.axis);
            face = Direction.fromAxisAndDirection(box.axis,
                    b >= a ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
        } else {
            return false;
        }
        int s = deltaY > 0 ? 1 : -1;
        if (Gradient.modifierDown()) {
            box = box.withFace(face, box.faceCoord(face) + face.getAxisDirection().getStep() * s);
        } else {
            Direction d = Direction.fromAxisAndDirection(face.getAxis(), Direction.AxisDirection.POSITIVE);
            box = box.moved(d.getStepX() * s, d.getStepY() * s, d.getStepZ() * s);
        }
        ShapeMarkers.touch(OWNER);
        ShapeMarkers.scrolled(); // saved once the scrolling settles
        return true;
    }

    /** LMB in Box mode on nothing else: first corner, then the opposite corner. */
    static void creationClick(Minecraft mc) {
        BlockHitResult hit = Raycast.aimedBlock(mc);
        BlockPos p = hit != null ? hit.getBlockPos().immutable() : airCell(mc);
        if (pendingA == null) {
            pendingA = p;
            pendingAxis = hit != null ? hit.getDirection().getAxis() : Direction.Axis.Y;
            MarkerText.step("Box", 2, 2, "LMB the opposite corner");
            return;
        }
        box = new BoxRegion(pendingA, p, pendingAxis);
        style = new MarkerStyle();
        cancelCreation();
        ShapeMarkers.touch(OWNER);
        ShapeMarkers.saveLines();
        MarkerText.say("Box set — the gradient starts on the first corner's side · LMB its cog for options");
    }

    /** RMB while making a box: cancel. */
    static boolean cancel() {
        if (pendingA == null) return false;
        cancelCreation();
        MarkerText.say("Box cancelled");
        return true;
    }

    /** The cell at Marker reach along the view, for a corner aimed at nothing. */
    static BlockPos airCell(Minecraft mc) {
        Vec3 p = Raycast.eye(mc).add(Raycast.look(mc).scale(MarkerText.airReach()));
        return BlockPos.containing(p);
    }

    private static double component(Vec3 v, Direction.Axis a) {
        return switch (a) {
            case X -> v.x;
            case Y -> v.y;
            case Z -> v.z;
        };
    }

    // ---- toolbar ----------------------------------------------------------------------------

    static MarkerToolbar.Spec toolbarSpec(Minecraft mc) {
        if (box == null) return null;
        BoxRegion b = box;
        List<MarkerToolbar.Button> row = new ArrayList<>();
        List<List<MarkerToolbar.Button>> rows = List.of(row);
        if (MarkerToolbar.stylePage()) {
            rows = StylePage.rows(OWNER, style, BOX_WALL, BOX_EDGE, () -> {
                ShapeMarkers.touch(OWNER);
                ShapeMarkers.saveLines();
            });
        }
        for (Direction.Axis a : Direction.Axis.values()) {
            String name = a.getName().toUpperCase(java.util.Locale.ROOT);
            row.add(new MarkerToolbar.Button(MarkerToolbar.letter(name.charAt(0)), b.axis == a, false,
                    "Gradient along " + name + (b.axis == a ? " (on)" : ""), () -> {
                        box = box.withAxis(a);
                        changed("Box: gradient along " + name);
                    }));
        }
        row.add(new MarkerToolbar.Button(MarkerToolbar.FLIP, false, false,
                "Flip: start the gradient on the other side", () -> {
                    box = box.flipped();
                    changed("Box flipped — the gradient starts on the other side");
                }));
        row.add(StylePage.openButton(style));
        row.add(new MarkerToolbar.Button(MarkerToolbar.BIN, false, true,
                "Delete this box (same as MMB)", BoxMarkers::remove));
        row.add(new MarkerToolbar.Button(MarkerToolbar.QUESTION, false, false,
                "Help: how the box works", () -> Minecraft.getInstance()
                        .setScreenAndShow(co.fax.wang.GradientScreen.atHelpTopic("Marker box"))));
        BlockPos mn = b.min(), mx = b.max();
        List<Vec3> controls = new ArrayList<>();
        for (Direction d : Direction.values()) controls.add(facePos(b, d));
        controls.add(centre(b));
        return new MarkerToolbar.Spec(null, rows, cogPos(b, Raycast.eye(mc)),
                new Vec3(mn.getX(), mn.getY(), mn.getZ()), new Vec3(mx.getX() + 1, mx.getY() + 1, mx.getZ() + 1),
                controls);
    }

    private static void changed(String msg) {
        ShapeMarkers.touch(OWNER);
        ShapeMarkers.saveLines();
        MarkerText.say(msg);
    }

    // ---- rendering --------------------------------------------------------------------------

    /** The committed box (or the half-made one following the aim) plus its controls. */
    static void render(SubmitNodeCollector col, PoseStack ps, Vec3 cam, Minecraft mc,
                       boolean handles, boolean boxCreate, boolean otherControlAimed) {
        BlockPos next = null;
        if (boxCreate && !otherControlAimed && aimedKind == null) {
            BlockHitResult hit = Raycast.aimedBlock(mc);
            next = hit != null ? hit.getBlockPos() : airCell(mc);
            WorldDraw.filledBlock(col, ps, cam, next, HOVER);
            WorldDraw.boxEdgesSeeThrough(col, ps, cam, next, 1, 1, 1, BOX_GHOST);
        }
        boolean pending = boxCreate && pendingA != null;
        BoxRegion draw = pending
                ? new BoxRegion(pendingA, next != null ? next : pendingA, pendingAxis)
                : box;
        if (draw == null) return;

        BlockPos mn = draw.min(), mx = draw.max();
        int sx = mx.getX() - mn.getX() + 1;
        int sy = mx.getY() - mn.getY() + 1;
        int sz = mx.getZ() - mn.getZ() + 1;
        // A half-made box keeps the default look; the committed one wears its style.
        MarkerStyle st = pending ? new MarkerStyle() : style;
        BlockSkin.Skin found = BlockSkin.of(st.block);
        boolean tooBig = 2L * ((long) sx * sy + (long) sy * sz + (long) sx * sz) > BlockSkin.MAX_FACES;
        BlockSkin.Skin skin = tooBig ? null : found;
        if (skin != null) {
            drawBlocks(col, ps, cam, mn, sx, sy, sz, skin, st.blockAlpha(), st.facesThrough);
        } else {
            int wall = st.face(BOX_WALL);
            if (wall >>> 24 == 0) { // faces hidden
            } else if (st.facesThrough) WorldDraw.filledRegionThrough(col, ps, cam, mn, sx, sy, sz, wall);
            else WorldDraw.filledRegion(col, ps, cam, mn, sx, sy, sz, wall);
        }
        // The white outline shows while placing the second corner, or while the tool is in
        // hand and the crosshair is on the box; a faint ghost always reads through terrain.
        int edge = st.edge(BOX_EDGE, true);
        if ((pending || (handles && (rayHits(mc, draw) || controlAimed()))) && edge >>> 24 != 0) {
            WorldDraw.regionOutline(col, ps, cam, mn, sx, sy, sz, edge);
        }
        int ghost = st.ghost(BOX_GHOST, true);
        if (ghost >>> 24 != 0) WorldDraw.boxEdgesSeeThrough(col, ps, cam, mn, sx, sy, sz, ghost);

        // Start/end plane slabs (skip when the box is flat on its axis — no direction — and
        // when it is shown as a block: they would tint the preview).
        int a = draw.cornerA.get(draw.axis), b = draw.cornerB.get(draw.axis);
        if (a != b && skin == null) {
            drawPlaneSlab(col, ps, cam, draw, a, BOX_START);
            drawPlaneSlab(col, ps, cam, draw, b, BOX_END);
        }
        if (pending || !handles) return;
        Vec3[] ax = Gizmo.cameraAxes(mc);
        boolean cog = ShapeMarkers.cogShown(OWNER);
        Vec3 eye = Raycast.eye(mc);
        Gizmo.twoPass(col, ps, cam, e -> {
            // The first corner in the start colour, the opposite one in the end colour.
            Gizmo.cube(e, cornerVertex(draw, true), CORNER_HALF, Gizmo.START);
            Gizmo.cube(e, cornerVertex(draw, false), CORNER_HALF, Gizmo.END);
            for (Direction d : Direction.values()) {
                boolean aimed = aimedKind == Kind.FACE && aimedFace == d || dragKind == Kind.FACE && dragFace == d;
                Gizmo.controlCube(e, facePos(draw, d), Gizmo.CTRL_HALF, aimed ? Gizmo.AIMED : Gizmo.SIZE,
                        Gizmo.RESIZE_ICON, ax);
            }
            boolean moveAimed = aimedKind == Kind.MOVE || dragKind == Kind.MOVE;
            Gizmo.controlCube(e, centre(draw), Gizmo.CTRL_HALF, moveAimed ? Gizmo.AIMED : Gizmo.MOVE, Gizmo.MOVE_ICON, ax);
            if (cog) Gizmo.cog(e, cogPos(draw, eye), aimedKind == Kind.COG, ax);
        });
        if (dragKind == Kind.MOVE && lockAxis != null) {
            Vec3 c = centre(dragStart), u = Gizmo.axisUnit(lockAxis);
            int argb = Gizmo.axisColor(lockAxis);
            Gizmo.twoPass(col, ps, cam, e -> Gizmo.segmentRibbon(e, c.subtract(u.scale(96)), c.add(u.scale(96)), 0.025, argb));
        }
    }

    /** The six sides of the box as block faces (see {@link BlockSkin}), one textured square per cell. */
    private static void drawBlocks(SubmitNodeCollector col, PoseStack ps, Vec3 cam, BlockPos mn,
                                   int sx, int sy, int sz, BlockSkin.Skin skin, int alpha, boolean through) {
        if (alpha == 0) return;
        float e = TubeBuild.EXPAND;
        ps.pushPose();
        ps.translate(mn.getX() - cam.x, mn.getY() - cam.y, mn.getZ() - cam.z);
        Vec3 camLocal = new Vec3(cam.x - mn.getX(), cam.y - mn.getY(), cam.z - mn.getZ());
        BlockSkin.submit(col, ps, skin, alpha, through, camLocal, sink -> {
            sink.rect(0, -e, 0, sx, 0, sz);      // down
            sink.rect(1, sy + e, 0, sx, 0, sz);  // up
            sink.rect(2, -e, 0, sx, 0, sy);      // north
            sink.rect(3, sz + e, 0, sx, 0, sy);  // south
            sink.rect(4, -e, 0, sy, 0, sz);      // west
            sink.rect(5, sx + e, 0, sy, 0, sz);  // east
        });
        ps.popPose();
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
}
