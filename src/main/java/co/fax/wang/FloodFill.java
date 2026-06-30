package co.fax.wang;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure 3D flood fill (no Minecraft deps, so it's unit-testable). Breadth-first from a seed cell
 * through "air" cells bounded by a region, returning the cells in fill order — each paired with the
 * already-listed neighbour it attaches to. That ordering means every cell can be placed against a
 * block that already exists by the time it's reached (so nothing is placed floating).
 */
public final class FloodFill {

    private FloodFill() {}

    /** A cell to fill at (x,y,z), placed against the support neighbour at (sx,sy,sz). */
    public record Cell(int x, int y, int z, int sx, int sy, int sz) {}

    public interface AirTest { boolean isAir(int x, int y, int z); }
    public interface Region { boolean contains(int x, int y, int z); }

    private static final int[][] DIRS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    /**
     * @param seed   starting air cell (must be air + in region).
     * @param support the solid block the seed attaches to (e.g. the looked-at block).
     * @param maxCells safety cap on how many cells to return.
     */
    public static List<Cell> compute(int[] seed, int[] support, AirTest air, Region region, int maxCells) {
        List<Cell> out = new ArrayList<>();
        if (!region.contains(seed[0], seed[1], seed[2]) || !air.isAir(seed[0], seed[1], seed[2])) return out;

        Set<Long> visited = new HashSet<>();
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        queue.add(new Cell(seed[0], seed[1], seed[2], support[0], support[1], support[2]));
        visited.add(key(seed[0], seed[1], seed[2]));

        while (!queue.isEmpty() && out.size() < maxCells) {
            Cell c = queue.poll();
            out.add(c);
            for (int[] d : DIRS) {
                int nx = c.x() + d[0], ny = c.y() + d[1], nz = c.z() + d[2];
                if (!region.contains(nx, ny, nz) || !air.isAir(nx, ny, nz)) continue;
                if (!visited.add(key(nx, ny, nz))) continue;
                queue.add(new Cell(nx, ny, nz, c.x(), c.y(), c.z())); // attach to the cell that found it
            }
        }
        return out;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF)) | ((long) (y & 0x1FFFFF) << 21) | ((long) (z & 0x1FFFFF) << 42);
    }
}
