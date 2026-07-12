package co.fax.wang;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Shared in-world face overlay: a translucent tint over one block face plus a thin arrow along
 * its normal, showing which way something will project off that face. Used blue by the auto end
 * marker preview ({@link MarkerManager}) and green by the paint placement preview
 * ({@link PaintPlacer}). Geometry is emitted in block-local coordinates — the caller's pose is
 * translated to the block, camera-relative.
 */
public final class FaceOverlay {

    private static final float EXPAND = 0.005f; // lift off the surface to avoid z-fighting

    private FaceOverlay() {}

    /** Submit the tint + arrow for {@code pos}'s {@code face} (colours are ARGB with alpha). */
    public static void submit(SubmitNodeCollector col, PoseStack ps, Vec3 cam,
                              BlockPos pos, Direction face, int fillArgb, int arrowArgb) {
        if (face == null) return;
        ps.pushPose();
        ps.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
        col.submitCustomGeometry(ps, RenderTypes.debugFilledBox(),
                (pose, vc) -> emit(pose, vc, face, fillArgb, arrowArgb));
        ps.popPose();
    }

    private static void emit(PoseStack.Pose pose, VertexConsumer vc, Direction face,
                             int fillArgb, int arrowArgb) {
        Vec3 n = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        // Two unit tangents spanning the face.
        Vec3 u = n.x != 0 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 v = n.z != 0 ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1);
        Vec3 c = new Vec3(0.5, 0.5, 0.5).add(n.scale(0.5 + EXPAND)); // face centre, just off the surface

        quadDS(vc, pose, fillArgb,
                c.subtract(u.scale(0.5)).subtract(v.scale(0.5)),
                c.add(u.scale(0.5)).subtract(v.scale(0.5)),
                c.add(u.scale(0.5)).add(v.scale(0.5)),
                c.subtract(u.scale(0.5)).add(v.scale(0.5)));

        // The arrow: a thin crossed shaft along the normal plus a crossed triangle head, so it
        // reads as an arrow from any viewing angle.
        Vec3 shaft = n.scale(0.35), tip = n.scale(0.5);
        for (Vec3 t : new Vec3[]{u, v}) {
            Vec3 w = t.scale(0.015), hw = t.scale(0.07);
            quadDS(vc, pose, arrowArgb, c.subtract(w), c.add(w), c.add(w).add(shaft), c.subtract(w).add(shaft));
            quadDS(vc, pose, arrowArgb, c.subtract(hw).add(shaft), c.add(hw).add(shaft), c.add(tip), c.add(tip));
        }
    }

    /** Double-sided quad from Vec3 corners (emitted once per winding so it shows from both sides). */
    private static void quadDS(VertexConsumer vc, PoseStack.Pose pose, int argb, Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        quad(vc, pose, argb, a, b, c, d);
        quad(vc, pose, argb, d, c, b, a);
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose, int argb, Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        vc.addVertex(pose, (float) a.x, (float) a.y, (float) a.z).setColor(argb);
        vc.addVertex(pose, (float) b.x, (float) b.y, (float) b.z).setColor(argb);
        vc.addVertex(pose, (float) c.x, (float) c.y, (float) c.z).setColor(argb);
        vc.addVertex(pose, (float) d.x, (float) d.y, (float) d.z).setColor(argb);
    }
}
