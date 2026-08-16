package co.fax.wang.shape;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * In-world drawing primitives for the shape markers (ported from fw-rule): translucent filled
 * block boxes with per-face culling, convex-edge highlight lines, one-piece region walls and
 * outlines, and composite-VoxelShape outlines. All geometry is camera-relative — each submit
 * translates a fresh pose by (worldPos - cameraPos), matching how vanilla submits entities so
 * there is no frame drift (same pattern as MarkerManager's own emitters).
 */
public final class WorldDraw {

    private static final float EXPAND = 0.005f; // lift off surfaces to avoid z-fighting
    /**
     * Region walls/edges are drawn twice: a shell just OUTSIDE the boundary and one inset
     * INSIDE it. A region flush against solid blocks hides the outer shell beneath the block
     * texture — the inset shell stays visible from inside (e.g. a hollow room), and vice versa.
     */
    private static final float REGION_INSET = 0.06f;
    private static final float LINE_WIDTH = 2.0f;

    private WorldDraw() {}

    /** Translucent filled box over the whole block at {@code pos}. */
    public static void filledBlock(SubmitNodeCollector col, PoseStack ps, Vec3 cam, BlockPos pos, int argb) {
        filledBlockFaces(col, ps, cam, pos, argb, 0x3F);
    }

    /**
     * Like {@link #filledBlock} but only the faces whose bit is set in {@code faceMask}
     * (bit index = {@code Direction.ordinal()}: DOWN, UP, NORTH, SOUTH, WEST, EAST). Shape
     * renderers use this to cull faces between adjacent cells so only the outer surface of a
     * multi-block shape is drawn.
     */
    public static void filledBlockFaces(SubmitNodeCollector col, PoseStack ps, Vec3 cam,
                                        BlockPos pos, int argb, int faceMask) {
        if (faceMask == 0) return;
        ps.pushPose();
        ps.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
        col.submitCustomGeometry(ps, RenderTypes.debugFilledBox(),
                (pose, vc) -> emitMaskedBox(pose, vc, argb, faceMask));
        ps.popPose();
    }

    /**
     * Edge outline of an arbitrary composite {@link VoxelShape}, anchored at {@code origin},
     * drawn after translucent terrain so it stays visible over water.
     */
    public static void compositeOutline(SubmitNodeCollector col, PoseStack ps, Vec3 cam,
                                        BlockPos origin, VoxelShape shape, int argb) {
        ps.pushPose();
        ps.translate(origin.getX() - cam.x, origin.getY() - cam.y, origin.getZ() - cam.z);
        col.submitShapeOutline(ps, shape, RenderTypes.lines(), argb, LINE_WIDTH, true);
        ps.popPose();
    }

    /**
     * The occluded-only counterpart of {@link #compositeOutline}: vanilla's secondary-outline
     * pipeline draws with an INVERTED depth test, so exactly the parts hidden behind terrain
     * render — submitted faint, it reads as a ghost of the shape through the ground.
     */
    /**
     * See-through edge lines for a block-aligned box: crossed thin quads on all 12 edges,
     * drawn with the depth-IGNORING text-background pipeline (POSITION_COLOR, textureless) —
     * 26.2 is reversed-Z and ships no occluded-only world pipeline, so an on-top pass is how
     * buried markers stay findable. Submitted at modest alpha it reads as a faint cage
     * through terrain and a slight glow over the visible parts.
     */
    public static void boxEdgesSeeThrough(SubmitNodeCollector col, PoseStack ps, Vec3 cam,
                                          BlockPos min, int sx, int sy, int sz, int argb) {
        ps.pushPose();
        ps.translate(min.getX() - cam.x, min.getY() - cam.y, min.getZ() - cam.z);
        col.submitCustomGeometry(ps, RenderTypes.textBackgroundSeeThrough(),
                (pose, vc) -> emitEdgesBounds(pose, vc, argb, 0x3F,
                        -EXPAND, -EXPAND, -EXPAND, sx + EXPAND, sy + EXPAND, sz + EXPAND));
        ps.popPose();
    }

    /** The depth-ignoring render type for see-through passes (see {@link #boxEdgesSeeThrough}). */
    public static net.minecraft.client.renderer.rendertype.RenderType seeThrough() {
        return RenderTypes.textBackgroundSeeThrough();
    }

    /**
     * Translucent walls of the block-aligned box from {@code min} spanning (sx, sy, sz)
     * blocks. Faces are emitted double-sided so the region also reads from inside it.
     */
    public static void filledRegion(SubmitNodeCollector col, PoseStack ps, Vec3 cam,
                                    BlockPos min, int sx, int sy, int sz, int argb) {
        ps.pushPose();
        ps.translate(min.getX() - cam.x, min.getY() - cam.y, min.getZ() - cam.z);
        col.submitCustomGeometry(ps, RenderTypes.debugFilledBox(),
                (pose, vc) -> emitRegionBox(pose, vc, sx, sy, sz, argb));
        ps.popPose();
    }

    /**
     * 1px edge outline of the block-aligned box from {@code min} — outer + inset layer,
     * drawn after translucent terrain.
     */
    public static void regionOutline(SubmitNodeCollector col, PoseStack ps, Vec3 cam,
                                     BlockPos min, int sx, int sy, int sz, int argb) {
        ps.pushPose();
        ps.translate(min.getX() - cam.x, min.getY() - cam.y, min.getZ() - cam.z);
        col.submitShapeOutline(ps, Shapes.box(0, 0, 0, sx, sy, sz),
                RenderTypes.lines(), argb, LINE_WIDTH, true);
        col.submitShapeOutline(ps, Shapes.box(REGION_INSET, REGION_INSET, REGION_INSET,
                        sx - REGION_INSET, sy - REGION_INSET, sz - REGION_INSET),
                RenderTypes.lines(), argb, LINE_WIDTH, true);
        ps.popPose();
    }

    /**
     * Bright edge lines for the block at {@code pos}, drawn only where two faces in
     * {@code faceMask} meet (convex surface edges). Shape renderers pass the same mask they
     * fill with, which traces the composite shape's outer/inner rims and jagged steps —
     * making the silhouette readable through the translucent fills.
     */
    public static void blockEdges(SubmitNodeCollector col, PoseStack ps, Vec3 cam,
                                  BlockPos pos, int argb, int faceMask) {
        if (Integer.bitCount(faceMask) < 2) return;
        ps.pushPose();
        ps.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
        col.submitCustomGeometry(ps, RenderTypes.debugFilledBox(),
                (pose, vc) -> emitEdges(pose, vc, argb, faceMask));
        ps.popPose();
    }

    // ---- geometry emitters (block-local coordinates) --------------------------------------

    /**
     * Masked box faces for a cell at offset (ox, oy, oz) from the pose origin. Public so a
     * shape can batch its whole band into ONE geometry submission instead of one per cell.
     */
    public static void emitCellFaces(PoseStack.Pose pose, VertexConsumer vc,
                                     float ox, float oy, float oz, int argb, int mask) {
        emitMaskedBoxBounds(pose, vc, argb, mask,
                ox - EXPAND, oy - EXPAND, oz - EXPAND,
                ox + 1 + EXPAND, oy + 1 + EXPAND, oz + 1 + EXPAND);
    }

    /** Convex-edge highlight lines for a cell at offset (ox, oy, oz); see {@link #blockEdges}. */
    public static void emitCellEdges(PoseStack.Pose pose, VertexConsumer vc,
                                     float ox, float oy, float oz, int argb, int mask) {
        if (Integer.bitCount(mask) < 2) return;
        emitEdgesBounds(pose, vc, argb, mask,
                ox - EXPAND, oy - EXPAND, oz - EXPAND,
                ox + 1 + EXPAND, oy + 1 + EXPAND, oz + 1 + EXPAND);
    }

    private static void emitEdges(PoseStack.Pose pose, VertexConsumer vc, int argb, int mask) {
        emitEdgesBounds(pose, vc, argb, mask, -EXPAND, -EXPAND, -EXPAND,
                1 + EXPAND, 1 + EXPAND, 1 + EXPAND);
    }

    private static void emitEdgesBounds(PoseStack.Pose pose, VertexConsumer vc, int argb, int mask,
                                        float ax, float ay, float az, float bx, float by, float bz) {
        float w = 0.02f;
        Direction[] dirs = Direction.values();
        for (int i = 0; i < 6; i++) {
            if ((mask & (1 << i)) == 0) continue;
            for (int j = i + 1; j < 6; j++) {
                if ((mask & (1 << j)) == 0) continue;
                Direction d1 = dirs[i], d2 = dirs[j];
                if (d1.getAxis() == d2.getAxis()) continue;
                // The edge segment shared by the two faces, spanning the remaining axis.
                double[] lo = {ax, ay, az}, hi = {bx, by, bz};
                pin(lo, hi, d1, ax, ay, az, bx, by, bz);
                pin(lo, hi, d2, ax, ay, az, bx, by, bz);
                Vec3 e1 = new Vec3(lo[0], lo[1], lo[2]);
                Vec3 e2 = new Vec3(hi[0], hi[1], hi[2]);
                // Crossed thin quads along the edge so the line reads from any angle.
                Vec3 u = axisUnit(d1.getAxis()).scale(w);
                Vec3 v = axisUnit(d2.getAxis()).scale(w);
                quadDS(vc, pose, argb, e1.subtract(u), e2.subtract(u), e2.add(u), e1.add(u));
                quadDS(vc, pose, argb, e1.subtract(v), e2.subtract(v), e2.add(v), e1.add(v));
            }
        }
    }

    /** Clamps the edge segment to {@code d}'s side of the box on {@code d}'s axis. */
    private static void pin(double[] lo, double[] hi, Direction d,
                            float ax, float ay, float az, float bx, float by, float bz) {
        int idx = switch (d.getAxis()) { case X -> 0; case Y -> 1; case Z -> 2; };
        double side = d.getAxisDirection().getStep() > 0
                ? (idx == 0 ? bx : idx == 1 ? by : bz)
                : (idx == 0 ? ax : idx == 1 ? ay : az);
        lo[idx] = side;
        hi[idx] = side;
    }

    private static Vec3 axisUnit(Direction.Axis axis) {
        return switch (axis) {
            case X -> new Vec3(1, 0, 0);
            case Y -> new Vec3(0, 1, 0);
            case Z -> new Vec3(0, 0, 1);
        };
    }

    /** Unit box with per-face culling; bit index = Direction.ordinal() (DOWN..EAST). */
    private static void emitMaskedBox(PoseStack.Pose pose, VertexConsumer vc, int argb, int mask) {
        emitMaskedBoxBounds(pose, vc, argb, mask, -EXPAND, -EXPAND, -EXPAND,
                1 + EXPAND, 1 + EXPAND, 1 + EXPAND);
    }

    private static void emitMaskedBoxBounds(PoseStack.Pose pose, VertexConsumer vc, int argb, int mask,
                                            float ax, float ay, float az, float bx, float by, float bz) {
        if ((mask & 0x01) != 0) quad(vc, pose, argb, ax, ay, az, bx, ay, az, bx, ay, bz, ax, ay, bz); // down
        if ((mask & 0x02) != 0) quad(vc, pose, argb, ax, by, az, ax, by, bz, bx, by, bz, bx, by, az); // up
        if ((mask & 0x04) != 0) quad(vc, pose, argb, ax, ay, az, ax, by, az, bx, by, az, bx, ay, az); // north
        if ((mask & 0x08) != 0) quad(vc, pose, argb, ax, ay, bz, bx, ay, bz, bx, by, bz, ax, by, bz); // south
        if ((mask & 0x10) != 0) quad(vc, pose, argb, ax, ay, az, ax, ay, bz, ax, by, bz, ax, by, az); // west
        if ((mask & 0x20) != 0) quad(vc, pose, argb, bx, ay, az, bx, by, az, bx, by, bz, bx, ay, bz); // east
    }

    private static void emitRegionBox(PoseStack.Pose pose, VertexConsumer vc,
                                      float sx, float sy, float sz, int argb) {
        // Outer shell (just outside the boundary) + inset shell (inside it) — see REGION_INSET.
        emitBoxFaces(pose, vc, argb, -EXPAND, sx + EXPAND, -EXPAND, sy + EXPAND, -EXPAND, sz + EXPAND);
        emitBoxFaces(pose, vc, argb, REGION_INSET, sx - REGION_INSET,
                REGION_INSET, sy - REGION_INSET, REGION_INSET, sz - REGION_INSET);
    }

    private static void emitBoxFaces(PoseStack.Pose pose, VertexConsumer vc, int argb,
                                     float a, float bx, float ay, float by, float az, float bz) {
        emitFace(pose, vc, argb, a, ay, az, bx, ay, az, bx, ay, bz, a, ay, bz);  // down
        emitFace(pose, vc, argb, a, by, az, a, by, bz, bx, by, bz, bx, by, az);  // up
        emitFace(pose, vc, argb, a, ay, az, a, by, az, bx, by, az, bx, ay, az);  // north
        emitFace(pose, vc, argb, a, ay, bz, bx, ay, bz, bx, by, bz, a, by, bz);  // south
        emitFace(pose, vc, argb, a, ay, az, a, ay, bz, a, by, bz, a, by, az);    // west
        emitFace(pose, vc, argb, bx, ay, az, bx, by, az, bx, by, bz, bx, ay, bz); // east
    }

    /** One region face, emitted double-sided so it reads from inside the box too. */
    private static void emitFace(PoseStack.Pose pose, VertexConsumer vc, int argb,
                                 float x1, float y1, float z1, float x2, float y2, float z2,
                                 float x3, float y3, float z3, float x4, float y4, float z4) {
        quad(vc, pose, argb, x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4);
        quad(vc, pose, argb, x4, y4, z4, x3, y3, z3, x2, y2, z2, x1, y1, z1);
    }

    /** Double-sided quad from Vec3 corners (emitted once per winding so it shows from both sides). */
    private static void quadDS(VertexConsumer vc, PoseStack.Pose pose, int argb, Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        quadV(vc, pose, argb, a, b, c, d);
        quadV(vc, pose, argb, d, c, b, a);
    }

    private static void quadV(VertexConsumer vc, PoseStack.Pose pose, int argb, Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        vc.addVertex(pose, (float) a.x, (float) a.y, (float) a.z).setColor(argb);
        vc.addVertex(pose, (float) b.x, (float) b.y, (float) b.z).setColor(argb);
        vc.addVertex(pose, (float) c.x, (float) c.y, (float) c.z).setColor(argb);
        vc.addVertex(pose, (float) d.x, (float) d.y, (float) d.z).setColor(argb);
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
