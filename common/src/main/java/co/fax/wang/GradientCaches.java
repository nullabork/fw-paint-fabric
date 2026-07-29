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

    /**
     * One pattern placement: the plane it was painted into (origin cell + width-axis step +
     * extrusion axis) and the cells placed for it. A later press adjacent to any placed cell
     * continues this plane so the drawing lines up.
     */
    public static final class PatternPlacement {
        public final BlockPos origin;
        public final int[] widthStep;   // {dx, dy, dz}, dy always 0
        public final Direction extrusion;
        final Set<BlockPos> placed = new HashSet<>();

        PatternPlacement(BlockPos origin, int[] widthStep, Direction extrusion) {
            this.origin = origin;
            this.widthStep = widthStep;
            this.extrusion = extrusion;
        }
    }

    private static final Map<PlacementMode, Map<BlockPos, ColEntry>> columns = new EnumMap<>(PlacementMode.class);
    private static final List<Fill3D> fills = new ArrayList<>();
    private static final List<PatternPlacement> patterns = new ArrayList<>();
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
        patterns.clear();
    }

    /** The pattern placement with a placed cell adjacent to {@code pos} (26-neighbourhood), or null. */
    public static PatternPlacement patternNear(BlockPos pos) {
        for (PatternPlacement pl : patterns) {
            if (pl.placed.contains(pos)) return pl;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (pl.placed.contains(pos.offset(dx, dy, dz))) return pl;
                    }
                }
            }
        }
        return null;
    }

    public static PatternPlacement newPattern(BlockPos origin, int[] widthStep, Direction extrusion) {
        PatternPlacement pl = new PatternPlacement(origin.immutable(), widthStep, extrusion);
        patterns.add(pl);
        return pl;
    }

    public static void recordPattern(PatternPlacement pl, BlockPos cell) {
        pl.placed.add(cell.immutable());
        lastPlaceMs = System.currentTimeMillis();
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

    /**
     * Everything that changes what gets placed — any change invalidates the caches. Keyed off the
     * active palette's full content (which includes its id, so switching palettes always clears)
     * plus the global missing-block policy.
     */
    private static String fingerprintOf(GradientConfig cfg) {
        co.fax.wang.palette.Palette active = co.fax.wang.palette.PaletteStore.active();
        co.fax.wang.palette.Palette pattern = co.fax.wang.palette.PaletteStore.activePattern();
        return (active == null ? "<none>" : active.contentKey()) + '|'
                + (pattern == null ? "<none>" : pattern.contentKey()) + '|'
                + cfg.missingBlockPolicy + '|' + cfg.perpSnapDegrees;
    }
}
