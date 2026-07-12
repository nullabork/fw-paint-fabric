package co.fax.wang;

import co.fax.wang.config.GradientConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Session memory for gradients placed <b>outside</b> markers, so a gradient can continue across
 * clicks with no marker pair to measure against. Only ever contains blocks we actually placed.
 *
 * <ul>
 *   <li><b>Column caches</b> (one per placement mode): placed cell → (step index, direction).
 *       A new column looks up the cell behind its air front — a hit continues the gradient from
 *       the next step, a miss starts at step 0.</li>
 *   <li><b>3D fills</b>: centre + the cells placed for it. Clicking a block that belongs to a
 *       fill continues that fill from its original centre; any other block starts a new fill.
 *       Fills can never claim each other's blocks because membership is recorded at placement.</li>
 * </ul>
 *
 * <p>Everything clears when the picker gradient changes (fingerprint over the settings that shape
 * the gradient) or after {@code gradientCacheSeconds} idle seconds without placing.
 */
public final class GradientCaches {

    private GradientCaches() {}

    private record ColEntry(int step, Direction dir) {}

    /** One outside-marker 3D gradient: its fixed centre and the cells placed for it so far. */
    public static final class Fill3D {
        public final BlockPos center;
        final Set<BlockPos> placed = new HashSet<>();

        Fill3D(BlockPos center) {
            this.center = center;
        }
    }

    private static final Map<PlacementMode, Map<BlockPos, ColEntry>> columns = new EnumMap<>(PlacementMode.class);
    private static final List<Fill3D> fills = new ArrayList<>();
    private static String fingerprint = "";
    private static long lastPlaceMs;

    /** Drop stale state: called at the start of every placement press. */
    public static void touch(GradientConfig cfg) {
        String fp = fingerprintOf(cfg);
        long now = System.currentTimeMillis();
        if (!fp.equals(fingerprint) || now - lastPlaceMs > cfg.gradientCacheSeconds * 1000L) {
            clear();
        }
        fingerprint = fp;
    }

    public static void clear() {
        columns.clear();
        fills.clear();
    }

    /** Step the column starting after {@code prev} should place next (0 = fresh gradient). */
    public static int columnProgress(PlacementMode mode, BlockPos prev, Direction dir) {
        ColEntry e = columns.getOrDefault(mode, Map.of()).get(prev);
        return (e != null && e.dir() == dir) ? e.step() + 1 : 0;
    }

    public static void recordColumn(PlacementMode mode, BlockPos cell, int step, Direction dir) {
        columns.computeIfAbsent(mode, k -> new HashMap<>()).put(cell.immutable(), new ColEntry(step, dir));
        lastPlaceMs = System.currentTimeMillis();
    }

    /** The 3D fill {@code pos} was placed by, or null. */
    public static Fill3D fillContaining(BlockPos pos) {
        for (Fill3D f : fills) {
            if (f.placed.contains(pos)) return f;
        }
        return null;
    }

    public static Fill3D newFill(BlockPos center) {
        Fill3D f = new Fill3D(center.immutable());
        fills.add(f);
        return f;
    }

    public static void recordFill(Fill3D fill, BlockPos cell) {
        fill.placed.add(cell.immutable());
        lastPlaceMs = System.currentTimeMillis();
    }

    /** Everything that changes what the picker gradient IS — any change invalidates the caches. */
    private static String fingerprintOf(GradientConfig cfg) {
        return cfg.orderStartBlock + '|' + cfg.orderEndBlock + '|' + cfg.gradientMode + '|'
                + cfg.curve + '|' + cfg.maxSteps + '|' + cfg.deviationBudget + '|' + cfg.chaos + '|'
                + cfg.stepWobble + '|' + cfg.pixelPercent + '|' + cfg.source + '|'
                + cfg.pickNumbers.hashCode() + '|' + cfg.excludedBlocks.hashCode() + '|'
                + cfg.requiredBlocks.hashCode();
    }
}
