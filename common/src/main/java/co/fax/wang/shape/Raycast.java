package co.fax.wang.shape;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * Long-range aiming for the shape markers (ported from fw-rule). Rays originate at the render
 * CAMERA (not the player's eyes): identical in first person, and it keeps the tools working
 * from detached cameras — freecam mods, third person — where the crosshair is the camera's,
 * not the player's. Block markers keep their own Marker-dist-limited raycast; shapes are
 * placed and edited at sight range.
 */
public final class Raycast {

    /** How far the shape tools' aim reaches, in blocks. */
    public static final int REACH = 512;

    private Raycast() {}

    /** Where the aim ray starts: the render camera (player eye until it initializes). */
    public static Vec3 eye(Minecraft mc) {
        Camera camera = mc.gameRenderer.mainCamera();
        return camera.isInitialized() ? camera.position() : mc.player.getEyePosition();
    }

    /** The aim ray's direction: where the camera looks. */
    public static Vec3 look(Minecraft mc) {
        Camera camera = mc.gameRenderer.mainCamera();
        if (camera.isInitialized()) {
            Vector3fc f = camera.forwardVector();
            return new Vec3(f.x(), f.y(), f.z());
        }
        return mc.player.getViewVector(1.0f);
    }

    /** The block the crosshair is on within {@link #REACH}, or null. */
    public static BlockHitResult aimedBlock(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        Vec3 eye = eye(mc);
        Vec3 to = eye.add(look(mc).scale(REACH));
        BlockHitResult hit = mc.level.clip(new ClipContext(
                eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit != null && hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }
}
