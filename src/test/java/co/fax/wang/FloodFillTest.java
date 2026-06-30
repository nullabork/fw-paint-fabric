package co.fax.wang;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FloodFillTest {

    private static long k(int x, int y, int z) { return (x * 1_000_000L + y) * 1_000_000L + z; }

    @Test
    void fillsAConnectedRegionWithinBounds() {
        // 3x3x1 box, all air; region is x,z in [0,2], y==0.
        FloodFill.AirTest air = (x, y, z) -> true;
        FloodFill.Region region = (x, y, z) -> y == 0 && x >= 0 && x <= 2 && z >= 0 && z <= 2;
        List<FloodFill.Cell> cells = FloodFill.compute(new int[]{1, 0, 1}, new int[]{1, -1, 1}, air, region, 1000);
        assertEquals(9, cells.size());
    }

    @Test
    void respectsTheRegionBoundary() {
        // Infinite air, but region caps it to a 5-long line — fill must not escape.
        FloodFill.AirTest air = (x, y, z) -> true;
        FloodFill.Region region = (x, y, z) -> y == 0 && z == 0 && x >= 0 && x <= 4;
        List<FloodFill.Cell> cells = FloodFill.compute(new int[]{0, 0, 0}, new int[]{0, -1, 0}, air, region, 1000);
        assertEquals(5, cells.size());
        for (FloodFill.Cell c : cells) assertTrue(c.x() >= 0 && c.x() <= 4 && c.y() == 0 && c.z() == 0);
    }

    @Test
    void solidBlocksActAsWalls() {
        // A wall of non-air at x==2 splits the region; seeding left only fills the left side.
        FloodFill.AirTest air = (x, y, z) -> x != 2;
        FloodFill.Region region = (x, y, z) -> y == 0 && z == 0 && x >= 0 && x <= 4;
        List<FloodFill.Cell> cells = FloodFill.compute(new int[]{0, 0, 0}, new int[]{0, -1, 0}, air, region, 1000);
        assertEquals(2, cells.size()); // x=0,1 only
    }

    @Test
    void everyCellAttachesToAnAlreadyPlacedNeighbourOrTheSeedSupport() {
        FloodFill.AirTest air = (x, y, z) -> true;
        FloodFill.Region region = (x, y, z) -> y == 0 && x >= 0 && x <= 3 && z >= 0 && z <= 3;
        List<FloodFill.Cell> cells = FloodFill.compute(new int[]{0, 0, 0}, new int[]{0, -1, 0}, air, region, 1000);
        Set<Long> placed = new HashSet<>();
        placed.add(k(0, -1, 0)); // the external seed support
        for (FloodFill.Cell c : cells) {
            assertTrue(placed.contains(k(c.sx(), c.sy(), c.sz())),
                    "cell (" + c.x() + "," + c.y() + "," + c.z() + ") attaches to an un-placed support");
            placed.add(k(c.x(), c.y(), c.z()));
        }
    }

    @Test
    void honoursTheMaxCellCap() {
        FloodFill.AirTest air = (x, y, z) -> true;
        FloodFill.Region region = (x, y, z) -> true; // unbounded
        List<FloodFill.Cell> cells = FloodFill.compute(new int[]{0, 0, 0}, new int[]{0, -1, 0}, air, region, 50);
        assertEquals(50, cells.size());
    }

    @Test
    void emptyWhenSeedIsNotAirOrOutOfRegion() {
        FloodFill.Region region = (x, y, z) -> true;
        assertTrue(FloodFill.compute(new int[]{0, 0, 0}, new int[]{0, -1, 0}, (x, y, z) -> false, region, 100).isEmpty());
        FloodFill.AirTest air = (x, y, z) -> true;
        assertTrue(FloodFill.compute(new int[]{0, 0, 0}, new int[]{0, -1, 0}, air, (x, y, z) -> false, 100).isEmpty());
    }
}
