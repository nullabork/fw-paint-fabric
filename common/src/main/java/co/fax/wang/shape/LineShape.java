package co.fax.wang.shape;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One line marker: its {@link LineCurve}, the profile swept along it (with per-end radius and
 * twist), and the block cells that sweep selects — the region painting is constrained to.
 *
 * <p>The tube is built as a distance-field sweep (see docs/design/line-marker.md): every
 * candidate cell is matched to its closest curve sample, expressed in that sample's
 * rotation-minimising frame (rotated by the interpolated twist), and tested against the profile
 * at the interpolated radius. Tight bends merge instead of leaving gaps between slices; the ends
 * are cut flat. Shell mode keeps the cells with an outside neighbour, Solid keeps them all. A
 * bare line (profile NONE) selects the 6-connected chain of cells the curve passes through.
 */
public final class LineShape {

    /** Sample spacing along the curve (blocks). */
    static final double STEP = 0.25;
    /** Safety cap on selected cells so a huge tube can't stall the client. */
    static final int MAX_CELLS = 250_000;

    public final LineCurve curve;
    public LineProfile profile = LineProfile.NONE;
    public boolean solid;
    /** Profile radius at the start / end (interpolated along the line). */
    public double r0 = 2, r1 = 2;
    /** Profile twist (radians) at the start / end (interpolated along the line). */
    public double tw0, tw1;

    private List<LineCurve.Sample> samples;
    private double length;
    private boolean samplesDirty = true, cellsDirty = true;

    /** Selected cells → fraction along the line (0 start … 1 end) for gradients. */
    private Map<Long, Float> cells = Map.of();
    // Render cache: cells relative to `origin` with their exposed-face masks.
    private BlockPos origin = BlockPos.ZERO;
    private int[] rx = new int[0], ry = new int[0], rz = new int[0], rmask = new int[0];

    public LineShape(LineCurve curve) {
        this.curve = curve;
    }

    /** The curve or a parameter changed: samples and cells must be rebuilt. */
    public void markDirty() {
        samplesDirty = true;
        cellsDirty = true;
    }

    public List<LineCurve.Sample> samples() {
        if (samplesDirty) {
            samples = curve.sample(STEP);
            length = samples.isEmpty() ? 0 : samples.get(samples.size() - 1).s();
            samplesDirty = false;
        }
        return samples;
    }

    public double length() {
        samples();
        return length;
    }

    /** Fraction along the line of sample {@code i}. */
    double frac(int i) {
        double l = length();
        return l <= 0 ? 0 : samples().get(i).s() / l;
    }

    public double radiusAt(double f) {
        return r0 + (r1 - r0) * f;
    }

    public double twistAt(double f) {
        return tw0 + (tw1 - tw0) * f;
    }

    /** Twist-rotated in-plane axes (n', b') of sample {@code i}. */
    public Vec3[] rotatedFrame(int i) {
        LineCurve.Sample s = samples().get(i);
        double th = twistAt(frac(i)), c = Math.cos(th), sn = Math.sin(th);
        Vec3 n = s.n().scale(c).add(s.b().scale(sn));
        Vec3 b = s.b().scale(c).subtract(s.n().scale(sn));
        return new Vec3[]{n, b};
    }

    // ---- cells ------------------------------------------------------------------------------

    /** Rebuild the selected cells if stale ({@code allowRebuild} false = keep the old ones). */
    public void ensureCells(boolean allowRebuild) {
        if (!cellsDirty || !allowRebuild) return;
        cellsDirty = false;
        List<LineCurve.Sample> ss = samples();
        Map<Long, Float> sel = profile == LineProfile.NONE ? pathCells(ss) : tubeCells(ss);
        cells = sel;
        buildRenderCache();
    }

    private Map<Long, Float> pathCells(List<LineCurve.Sample> ss) {
        Map<Long, Float> out = new HashMap<>();
        BlockPos prev = null;
        for (int i = 0; i < ss.size(); i++) {
            BlockPos c = BlockPos.containing(ss.get(i).pos());
            float f = (float) frac(i);
            if (prev != null && !prev.equals(c)) {
                // Step one axis at a time so the chain stays 6-connected (no diagonal gaps).
                BlockPos walk = prev;
                for (Direction.Axis ax : Direction.Axis.values()) {
                    int d = c.get(ax) - walk.get(ax);
                    if (d == 0) continue;
                    walk = walk.relative(ax, Integer.signum(d));
                    if (!walk.equals(c)) out.putIfAbsent(walk.asLong(), f);
                }
            }
            out.putIfAbsent(c.asLong(), f);
            prev = c;
        }
        return out;
    }

    private Map<Long, Float> tubeCells(List<LineCurve.Sample> ss) {
        // Pass 1: stamp each sample's neighbourhood, keeping the closest sample per cell.
        Map<Long, double[]> best = new HashMap<>(); // cell → {dist², sample index}
        for (int i = 0; i < ss.size(); i++) {
            Vec3 p = ss.get(i).pos();
            // Far enough for a square's corners (√2 × its half-width), not just a circle.
            double reach = (radiusAt(frac(i)) + 0.5) * Math.sqrt(2) + 1;
            int lx = (int) Math.floor(p.x - reach), hx = (int) Math.floor(p.x + reach);
            int ly = (int) Math.floor(p.y - reach), hy = (int) Math.floor(p.y + reach);
            int lz = (int) Math.floor(p.z - reach), hz = (int) Math.floor(p.z + reach);
            double reach2 = reach * reach;
            for (int x = lx; x <= hx; x++) {
                double dx = x + 0.5 - p.x;
                for (int y = ly; y <= hy; y++) {
                    double dy = y + 0.5 - p.y;
                    for (int z = lz; z <= hz; z++) {
                        double dz = z + 0.5 - p.z;
                        double d2 = dx * dx + dy * dy + dz * dz;
                        if (d2 > reach2) continue;
                        long key = BlockPos.asLong(x, y, z);
                        double[] cur = best.get(key);
                        if (cur == null) {
                            if (best.size() >= MAX_CELLS * 4) continue;
                            best.put(key, new double[]{d2, i});
                        } else if (d2 < cur[0]) {
                            cur[0] = d2;
                            cur[1] = i;
                        }
                    }
                }
            }
        }
        // Pass 2: test each cell against the profile in its closest sample's frame.
        Map<Long, Float> inside = new HashMap<>();
        int last = ss.size() - 1;
        for (Map.Entry<Long, double[]> e : best.entrySet()) {
            int i = (int) e.getValue()[1];
            LineCurve.Sample s = ss.get(i);
            long key = e.getKey();
            Vec3 c = new Vec3(BlockPos.getX(key) + 0.5, BlockPos.getY(key) + 0.5, BlockPos.getZ(key) + 0.5);
            Vec3 d = c.subtract(s.pos());
            double along = d.dot(s.tan());
            if ((i == 0 && along < -0.5) || (i == last && along > 0.5)) continue; // flat end caps
            Vec3[] f = rotatedFrame(i);
            double fr = frac(i);
            if (profile.inside(d.dot(f[0]), d.dot(f[1]), radiusAt(fr))) {
                inside.put(key, (float) fr);
                if (inside.size() >= MAX_CELLS) break;
            }
        }
        if (solid) return inside;
        // Shell: only the cells with an outside face neighbour.
        Map<Long, Float> shell = new HashMap<>();
        for (Map.Entry<Long, Float> e : inside.entrySet()) {
            long k = e.getKey();
            for (Direction dir : Direction.values()) {
                if (!inside.containsKey(BlockPos.offset(k, dir))) {
                    shell.put(k, e.getValue());
                    break;
                }
            }
        }
        return shell;
    }

    private void buildRenderCache() {
        int n = cells.size();
        int mnx = Integer.MAX_VALUE, mny = Integer.MAX_VALUE, mnz = Integer.MAX_VALUE;
        for (long k : cells.keySet()) {
            mnx = Math.min(mnx, BlockPos.getX(k));
            mny = Math.min(mny, BlockPos.getY(k));
            mnz = Math.min(mnz, BlockPos.getZ(k));
        }
        origin = n == 0 ? BlockPos.ZERO : new BlockPos(mnx, mny, mnz);
        rx = new int[n];
        ry = new int[n];
        rz = new int[n];
        rmask = new int[n];
        int i = 0;
        for (long k : cells.keySet()) {
            int mask = 0;
            for (Direction d : Direction.values()) {
                if (!cells.containsKey(BlockPos.offset(k, d))) mask |= 1 << d.ordinal();
            }
            rx[i] = BlockPos.getX(k) - origin.getX();
            ry[i] = BlockPos.getY(k) - origin.getY();
            rz[i] = BlockPos.getZ(k) - origin.getZ();
            rmask[i] = mask;
            i++;
        }
    }

    public boolean contains(BlockPos cell) {
        ensureCells(true);
        return cells.containsKey(cell.asLong());
    }

    /** Fraction along the line (0 … 1) of a selected cell, or -1. */
    public double fracOf(BlockPos cell) {
        ensureCells(true);
        Float f = cells.get(cell.asLong());
        return f == null ? -1 : f;
    }

    /** Gradient segment for a selected cell: start → end along the curve, by arc length. */
    public RingShape.Segment segmentFor(BlockPos cell) {
        double f = fracOf(cell);
        if (f < 0) return null;
        List<LineCurve.Node> ns = curve.nodes;
        int len = Math.max(1, (int) Math.round(length()));
        int idx = (int) Math.max(0, Math.min(len, Math.round(f * len)));
        return new RingShape.Segment(BlockPos.containing(ns.get(0).p),
                BlockPos.containing(ns.get(ns.size() - 1).p), idx, len);
    }

    public int cellCount() {
        return cells.size();
    }

    public BlockPos renderOrigin() {
        return origin;
    }

    public int renderSize() {
        return rmask.length;
    }

    public int renderX(int i) { return rx[i]; }

    public int renderY(int i) { return ry[i]; }

    public int renderZ(int i) { return rz[i]; }

    public int renderMask(int i) { return rmask[i]; }

    /** All selected cells (a copy-free view for flood fills). */
    public java.util.Set<Long> cellKeys() {
        ensureCells(true);
        return cells.keySet();
    }

    // ---- control positions ------------------------------------------------------------------

    /** Size node at the start (end=false) or end: on the profile rim along n'. */
    public Vec3 sizeNode(boolean end) {
        int i = end ? samples().size() - 1 : 0;
        return samples().get(i).pos().add(rotatedFrame(i)[0].scale(radiusAt(end ? 1 : 0) + 0.5));
    }

    /** Twist node at the start/end: on the rim along b', a quarter turn from the size node. */
    public Vec3 twistNode(boolean end) {
        int i = end ? samples().size() - 1 : 0;
        return samples().get(i).pos().add(rotatedFrame(i)[1].scale(radiusAt(end ? 1 : 0) + 0.5));
    }

    /** Move node: beside the middle of the line, clear of the tube. */
    public Vec3 moveNode() {
        List<LineCurve.Sample> ss = samples();
        int i = ss.size() / 2;
        double r = profile == LineProfile.NONE ? 0 : radiusAt(frac(i)) + 0.5;
        return ss.get(i).pos().add(rotatedFrame(i)[0].scale(-(r + 1.5)));
    }

    /** Where the floating button row is centred: above the start, clear of the tube. */
    public Vec3 buttonAnchor() {
        double r = profile == LineProfile.NONE ? 0 : r0 + 0.5;
        return curve.nodes.get(0).p.add(0, r + 2.2, 0);
    }
}
