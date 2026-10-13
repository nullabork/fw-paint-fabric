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
 *   <li>{@code FWPAINT_DEBUG_RADIUS=<r>} / {@code FWPAINT_DEBUG_LEN=<blocks>}: the demo line's
 *       start radius and length (perf tests on big tubes).</li>
 *   <li>{@code FWPAINT_DEBUG_WIGGLE=1}: keep "dragging" the demo line's end node in a small
 *       circle every tick (never saved) — pair with {@code FWPAINT_PERF=1}, which logs the line
 *       work's cost once a second (rebuilds, resampling, tube vertex emission, vertex counts).</li>
 *   <li>{@code FWPAINT_DEBUG_SKY=1}: first lifts the player 25 blocks into creative flight at
 *       noon (needs cheats in the dev world), so the scene is clear of terrain.</li>
 * </ul>
 */
public final class DebugRender {

    private static final String LINE = System.getenv("FWPAINT_DEBUG_LINE");
    /** FWPAINT_DEBUG_WIGGLE=1: drag the demo line's end node in a small circle (perf test). */
    private static final boolean WIGGLE = System.getenv("FWPAINT_DEBUG_WIGGLE") != null;
    private static final double RADIUS = System.getenv("FWPAINT_DEBUG_RADIUS") != null
            ? Double.parseDouble(System.getenv("FWPAINT_DEBUG_RADIUS")) : 2.5;
    private static final double LEN = System.getenv("FWPAINT_DEBUG_LEN") != null
            ? Double.parseDouble(System.getenv("FWPAINT_DEBUG_LEN")) : 14;
    private static int wiggleTicks;
    private static final boolean SKY = System.getenv("FWPAINT_DEBUG_SKY") != null;
    private static boolean lifted;
    private static boolean spawned;

    private DebugRender() {}

    /** Called each client tick from MarkerManager. */
    public static void tick(Minecraft mc) {
        if (LINE == null || mc.player == null || mc.level == null) return;
        if (spawned) {
            if (WIGGLE) LineMarkers.debugWiggle(wiggleTicks++);
            return;
        }
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
        Vec3 mid = eye.add(flat.scale(14 + LEN / 4)).add(0, 1, 0);
        LineCurve c = new LineCurve(mid.add(right.scale(-LEN / 2)), mid.add(right.scale(LEN / 2)));
        c.nodes.get(0).out = new Vec3(0, 6, 0).add(right.scale(2));
        c.nodes.get(1).in = new Vec3(0, -4, 0).add(right.scale(-2));
        LineShape l = new LineShape(c);
        l.profile = LineProfile.valueOf(LINE);
        l.r0 = RADIUS;
        l.r1 = Math.max(1, RADIUS * 0.6);
        l.tw1 = Math.PI / 2;
        LineMarkers.debugAdd(l);
    }
}
