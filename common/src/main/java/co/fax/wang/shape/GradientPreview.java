package co.fax.wang.shape;

import co.fax.wang.PalettePreview;
import co.fax.wang.palette.Palette;
import co.fax.wang.palette.PaletteStore;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A line tube shown as the gradient a Gradient fill would give it: every outside face of every
 * cell wears the texture of the palette block for that cell's position on the line's gradient
 * (its mode and angle). Automatic segments have no block until they are painted, so their cells
 * get that segment's flat colour instead. Display only: nothing is placed, and the palette's
 * chaos, variation and step wobble are left out.
 *
 * <p>Built per line and kept until the tube, the gradient settings, the palette or the block
 * models change; a tube that is mid-rebuild keeps its last picture, and rebuilds are spaced out
 * so dragging a node or the gradient angle stays smooth.
 */
final class GradientPreview {

    private GradientPreview() {}

    /** Tubes with more cells than this keep their plain look (the picture would cost too much). */
    static final int MAX_CELLS = 60_000;
    /** Positions sampled along the palette. */
    private static final int RAMP = 256;
    /** A changed line waits this long between rebuilds. */
    private static final long REBUILD_MS = 120;

    /**
     * The cells of one palette segment: {@code cells} holds x, y, z (relative to the origin) and
     * a mask of the outside faces (bit = Direction.ordinal()) for each. {@code skin} is the
     * segment's block look, or null to draw them flat in {@code color}.
     */
    record Group(BlockSkin.Skin skin, int color, int[] cells) {
        int count() {
            return cells.length / 4;
        }
    }

    record Faces(BlockPos origin, List<Group> groups) {}

    private record Key(Object built, double angle, LineGradient gradient, String palette, Object models) {}

    private record Entry(Key key, Faces faces, long at) {}

    private static final Map<LineShape, Entry> CACHE = new WeakHashMap<>();

    /** The picture for {@code l}, or null when there is nothing to show (no palette, too big). */
    static Faces of(LineShape l) {
        Palette p = PaletteStore.active();
        if (p == null || p.segments.isEmpty()) return null;
        Entry e = CACHE.get(l);
        Minecraft mc = Minecraft.getInstance();
        Object models = mc.getModelManager() == null ? null : mc.getModelManager().getBlockStateModelSet();
        Key key = new Key(l.builtKey(), l.gradAngle, l.gradient, p.contentKey(), models);
        if (e != null && e.key.equals(key)) return e.faces;
        long now = System.currentTimeMillis();
        // Mid-rebuild, or only just rebuilt: keep showing the last one.
        if (e != null && (l.cellsDirty() || now - e.at < REBUILD_MS)) return e.faces;
        if (l.cellsDirty()) return null;
        Faces f = build(l, p);
        CACHE.put(l, new Entry(key, f, now));
        return f;
    }

    private static Faces build(LineShape l, Palette p) {
        LongSet keys = l.builtCellKeys();
        if (keys.isEmpty() || keys.size() > MAX_CELLS) return null;
        PalettePreview.Band[] ramp = PalettePreview.bands(p, RAMP);
        if (ramp.length == 0) return null;
        List<BlockPos> cells = new ArrayList<>(keys.size());
        for (long k : keys) cells.add(BlockPos.of(k));
        double[] ts = l.gradientFracs(cells);
        BlockPos o = l.renderOrigin();
        Map<PalettePreview.Band, IntArrayList> byBand = new IdentityHashMap<>();
        List<PalettePreview.Band> order = new ArrayList<>();
        for (int c = 0; c < cells.size(); c++) {
            BlockPos cell = cells.get(c);
            int mask = 0;
            for (Direction d : Direction.values()) {
                if (!keys.contains(cell.relative(d).asLong())) mask |= 1 << d.ordinal(); // an outside face
            }
            if (mask == 0) continue;
            PalettePreview.Band band = ramp[Math.max(0, Math.min(RAMP - 1, (int) (ts[c] * RAMP)))];
            IntArrayList list = byBand.get(band);
            if (list == null) {
                list = new IntArrayList();
                byBand.put(band, list);
                order.add(band);
            }
            list.add(cell.getX() - o.getX());
            list.add(cell.getY() - o.getY());
            list.add(cell.getZ() - o.getZ());
            list.add(mask);
        }
        List<Group> groups = new ArrayList<>(order.size());
        for (PalettePreview.Band band : order) {
            BlockSkin.Skin skin = band.block() == null ? null : BlockSkin.of(band.block());
            groups.add(new Group(skin, band.color(), byBand.get(band).toIntArray()));
        }
        return new Faces(o, groups);
    }
}
