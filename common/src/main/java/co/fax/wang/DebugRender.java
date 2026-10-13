package co.fax.wang;

import co.fax.wang.shape.LineCurve;
import co.fax.wang.shape.LineMarkers;
import co.fax.wang.shape.LineProfile;
import co.fax.wang.shape.LineShape;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Dev-only visual test bench, off (and free) unless an environment variable is set — lets a
 * screenshot of a freshly loaded dev world show features without anyone clicking:
 *
 * <ul>
 *   <li>{@code FWPAINT_DEBUG_LINE=<profile>} (NONE / CIRCLE / SQUARE / TRIANGLE / STAR): once in a
 *       world, spawns a curved, tapering, selected demo line of that profile in front of the
 *       camera.</li>
 *   <li>{@code FWPAINT_DEBUG_SKY=1}: first lifts the player 25 blocks into creative flight at
 *       noon (needs cheats in the dev world), so the scene is clear of terrain.</li>
 * </ul>
 */
public final class DebugRender {

    private static final String LINE = System.getenv("FWPAINT_DEBUG_LINE");
    private static final boolean SKY = System.getenv("FWPAINT_DEBUG_SKY") != null;
    private static boolean lifted;
    private static boolean spawned;

    private DebugRender() {}

    /** Called each client tick from MarkerManager. */
    public static void tick(Minecraft mc) {
        if (LINE == null || spawned || mc.player == null || mc.level == null) return;
        if (mc.player.tickCount < 40) return; // let the world (and saved shapes) load first
        if (SKY && !lifted) {
            lifted = true;
            mc.player.connection.sendCommand("gamemode creative");
            mc.player.connection.sendCommand("time set noon");
            mc.player.connection.sendCommand("tp @s ~ ~25 ~ ~ 10");
            return;
        }
        if (SKY && mc.player.tickCount < 80) {
            mc.player.getAbilities().flying = true;
            return;
        }
        spawned = true;
        Vec3 eye = co.fax.wang.shape.Raycast.eye(mc), f = co.fax.wang.shape.Raycast.look(mc);
        Vec3 flat = new Vec3(f.x, 0, f.z).normalize();
        Vec3 right = flat.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 mid = eye.add(flat.scale(14)).add(0, 1, 0);
        LineCurve c = new LineCurve(mid.add(right.scale(-7)), mid.add(right.scale(7)));
        c.nodes.get(0).out = new Vec3(0, 6, 0).add(right.scale(2));
        c.nodes.get(1).in = new Vec3(0, -4, 0).add(right.scale(-2));
        LineShape l = new LineShape(c);
        l.profile = LineProfile.valueOf(LINE);
        l.r0 = 2.5;
        l.r1 = 1;
        l.tw1 = Math.PI / 2;
        LineMarkers.debugAdd(l);
    }
}
