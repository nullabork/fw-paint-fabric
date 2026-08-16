package co.fax.wang;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Marked-region geometry for the in-marker noise flood fill, used by {@link PaintPlacer}. The
 * per-cell block choice itself lives in {@link PaletteChoice#noiseSlot} (driven by the active
 * palette); input handling lives in {@link PaintPlacer}.
 */
public final class NoisePlacer {

    private NoisePlacer() {}

    private static final double REACH = 6.0;

    /** True if (x,y,z) lies strictly between some colinear start→end marker pair within maxDist. */
    static boolean inMarkedSegment(int x, int y, int z, int maxDist) {
        for (BlockPos s : MarkerManager.startMarkers) {
            for (BlockPos e : MarkerManager.endMarkers) {
                if (betweenColinear(s.getX(), s.getY(), s.getZ(), e.getX(), e.getY(), e.getZ(), x, y, z, maxDist)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether (cx,cy,cz) is strictly between a colinear (single-axis) start/end pair within maxDist. */
    static boolean betweenColinear(int sx, int sy, int sz, int ex, int ey, int ez,
                                   int cx, int cy, int cz, int maxDist) {
        int dx = ex - sx, dy = ey - sy, dz = ez - sz;
        int axes = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
        if (axes != 1) return false; // start and end must share a single axis
        int len = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
        if (len > maxDist) return false;
        if (dx != 0) {
            if (cy != sy || cz != sz) return false;
            int i = (cx - sx) * Integer.signum(dx);
            return i > 0 && i < len;
        }
        if (dy != 0) {
            if (cx != sx || cz != sz) return false;
            int i = (cy - sy) * Integer.signum(dy);
            return i > 0 && i < len;
        }
        if (cx != sx || cy != sy) return false;
        int i = (cz - sz) * Integer.signum(dz);
        return i > 0 && i < len;
    }

    /** First air cell along the look ray that is in the region and has a solid neighbour to build on. */
    static int[][] raycastSeed(Minecraft mc, FloodFill.Region region, FloodFill.AirTest air) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0f);
        BlockPos last = null;
        for (double d = 0; d <= REACH; d += 0.1) {
            BlockPos c = BlockPos.containing(eye.x + look.x * d, eye.y + look.y * d, eye.z + look.z * d);
            if (c.equals(last)) continue;
            last = c;
            if (!region.contains(c.getX(), c.getY(), c.getZ()) || !air.isAir(c.getX(), c.getY(), c.getZ())) continue;
            for (Direction dir : Direction.values()) {
                BlockPos n = c.relative(dir);
                if (!Gradient.emptyCell(mc.level.getBlockState(n))) {
                    return new int[][]{{c.getX(), c.getY(), c.getZ()}, {n.getX(), n.getY(), n.getZ()}};
                }
            }
        }
        return null;
    }
}
