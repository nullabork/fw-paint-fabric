package co.fax.wang.shape;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2FloatMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Builds a line marker's selected cells and their draw geometry from an immutable snapshot of
 * the line — no shared state, so {@link LineShape} can run it on a worker thread while a node is
 * being dragged and swap the result in when it lands.
 *
 * <p>Cells: a bare line is the 6-connected chain of cells the curve passes through. A tube is
 * built slice by slice: each curve sample owns a thin slab across the curve (its normal plane ±
 * half the gap to its neighbours measured at the profile's rim, so slabs meet on the outside of
 * bends; the end slabs stop half a block past the ends — flat caps). Candidate cells come from a
 * grid laid across each slab; a cell is in when its centre is in some sample's slab and inside the
 * profile there (radius and twist interpolated). Shell keeps the cells with an outside neighbour.
 *
 * <p>Geometry: exposed faces are greedy-merged into rectangles per plane, and convex edges are
 * merged into straight runs, so a big tube draws a few vertices per surface patch rather than
 * per cell.
 */
final class TubeBuild {

    /** Sample spacing for bare lines (fine: every cell the curve touches). */
    static final double PATH_STEP = 0.25;
    /** Sample spacing for tubes: slabs widen to cover the gaps, so this can be coarse. */
    static final double TUBE_STEP = 0.5;
    /** In-plane spacing of the candidate grid: ≤ √½ so every cell a slice cuts gets a point. */
    static final double GRID = 0.7;
    /** Safety cap on selected cells so a huge tube can't stall the client. */
    static final int MAX_CELLS = 400_000;
    static final float EXPAND = 0.005f; // lift off surfaces (matches WorldDraw)
    static final float EDGE_W = 0.02f;  // half-width of the edge highlight quads

    /** Everything a build reads, copied so the worker never sees a half-edited line. */
    record Params(LineCurve curve, LineProfile profile, boolean solid, double r0, double r1,
                  double tw0, double tw1) {}

    /** A finished build: cells (→ fraction along the line), geometry relative to origin. */
    record Result(Long2FloatOpenHashMap cells, BlockPos origin, float[] faces, float[] edges, long nanos) {
        static final Result EMPTY = new Result(new Long2FloatOpenHashMap(), BlockPos.ZERO, new float[0], new float[0], 0);
    }

    private TubeBuild() {}

    static Result build(Params p) {
        long t0 = System.nanoTime();
        Long2FloatOpenHashMap cells = p.profile() == LineProfile.NONE
                ? pathCells(p.curve().sample(PATH_STEP))
                : tubeCells(p, p.curve().sample(TUBE_STEP));
        BlockPos origin = originOf(cells);
        FloatList faces = new FloatArrayList(), edges = new FloatArrayList();
        buildGeometry(cells, origin, faces, edges);
        return new Result(cells, origin, faces.toFloatArray(), edges.toFloatArray(), System.nanoTime() - t0);
    }

    private static double frac(List<LineCurve.Sample> ss, int i) {
        double l = ss.get(ss.size() - 1).s();
        return l <= 0 ? 0 : ss.get(i).s() / l;
    }

    // ---- cells ----------------------------------------------------------------------------

    static Long2FloatOpenHashMap pathCells(List<LineCurve.Sample> ss) {
        Long2FloatOpenHashMap out = new Long2FloatOpenHashMap();
        BlockPos prev = null;
        for (int i = 0; i < ss.size(); i++) {
            BlockPos c = BlockPos.containing(ss.get(i).pos());
            float f = (float) frac(ss, i);
            if (prev != null && !prev.equals(c)) {
                // Step one axis at a time so the chain stays 6-connected (no diagonal gaps).
                BlockPos walk = prev;
                for (Direction.Axis ax : Direction.Axis.values()) {
                    int d = c.get(ax) - walk.get(ax);
                    if (d == 0) continue;
                    walk = walk.relative(ax, Integer.signum(d));
                    if (!walk.equals(c) && !out.containsKey(walk.asLong())) out.put(walk.asLong(), f);
                }
            }
            if (!out.containsKey(c.asLong())) out.put(c.asLong(), f);
            prev = c;
        }
        return out;
    }

    static Long2FloatOpenHashMap tubeCells(Params p, List<LineCurve.Sample> ss) {
        Long2FloatOpenHashMap inside = new Long2FloatOpenHashMap();
        int n = ss.size(), last = n - 1;
        for (int i = 0; i < n && inside.size() < MAX_CELLS; i++) {
            LineCurve.Sample s = ss.get(i);
            Vec3 c = s.pos(), t = s.tan();
            double fr = frac(ss, i);
            double r = p.r0() + (p.r1() - p.r0()) * fr;
            double th = p.tw0() + (p.tw1() - p.tw0()) * fr, ct = Math.cos(th), st = Math.sin(th);
            Vec3 nn = s.n().scale(ct).add(s.b().scale(st));
            Vec3 bb = s.b().scale(ct).subtract(s.n().scale(st));
            double ext = (r + 0.5) * Math.sqrt(2); // farthest profile point (square corner)
            // Slab half-thickness: half the distance to each neighbour at the rim, padded.
            double gap = 0;
            for (int j : new int[]{i - 1, i + 1}) {
                if (j < 0 || j > last) continue;
                LineCurve.Sample o = ss.get(j);
                double ang = Math.acos(Math.max(-1, Math.min(1, t.dot(o.tan()))));
                gap = Math.max(gap, c.distanceTo(o.pos()) + ext * ang);
            }
            double h = Math.max(0.3, gap * 0.6);
            double lo = i == 0 ? -0.5 : -h, hi = i == last ? 0.5 : h;
            int steps = (int) Math.ceil(ext / GRID);
            double lim2 = (ext + 1) * (ext + 1);
            for (double a = lo; a <= hi + 1e-9; a += GRID) {
                double bx = c.x + t.x * a, by = c.y + t.y * a, bz = c.z + t.z * a;
                for (int iu = -steps; iu <= steps; iu++) {
                    double u = iu * GRID;
                    for (int iv = -steps; iv <= steps; iv++) {
                        double v = iv * GRID;
                        if (u * u + v * v > lim2) continue;
                        int cx = (int) Math.floor(bx + nn.x * u + bb.x * v);
                        int cy = (int) Math.floor(by + nn.y * u + bb.y * v);
                        int cz = (int) Math.floor(bz + nn.z * u + bb.z * v);
                        long key = BlockPos.asLong(cx, cy, cz);
                        if (inside.containsKey(key)) continue;
                        double dx = cx + 0.5 - c.x, dy = cy + 0.5 - c.y, dz = cz + 0.5 - c.z;
                        double along = dx * t.x + dy * t.y + dz * t.z;
                        if (along < lo || along > hi) continue;
                        double pu = dx * nn.x + dy * nn.y + dz * nn.z;
                        double pv = dx * bb.x + dy * bb.y + dz * bb.z;
                        if (p.profile().inside(pu, pv, r)) inside.put(key, (float) fr);
                    }
                }
            }
        }
        if (p.solid()) return inside;
        // Shell: only the cells with an outside face neighbour.
        Long2FloatOpenHashMap shell = new Long2FloatOpenHashMap();
        for (Long2FloatMap.Entry e : inside.long2FloatEntrySet()) {
            long k = e.getLongKey();
            for (Direction dir : Direction.values()) {
                if (!inside.containsKey(BlockPos.offset(k, dir))) {
                    shell.put(k, e.getFloatValue());
                    break;
                }
            }
        }
        return shell;
    }

    private static BlockPos originOf(Long2FloatOpenHashMap cells) {
        if (cells.isEmpty()) return BlockPos.ZERO;
        int mx = Integer.MAX_VALUE, my = Integer.MAX_VALUE, mz = Integer.MAX_VALUE;
        LongIterator it = cells.keySet().iterator();
        while (it.hasNext()) {
            long k = it.nextLong();
            mx = Math.min(mx, BlockPos.getX(k));
            my = Math.min(my, BlockPos.getY(k));
            mz = Math.min(mz, BlockPos.getZ(k));
        }
        return new BlockPos(mx, my, mz);
    }

    // ---- geometry ---------------------------------------------------------------------------

    /** Face index = Direction.ordinal(): DOWN, UP, NORTH, SOUTH, WEST, EAST. Axis 0 x, 1 y, 2 z. */
    static int axisOf(int face) {
        return face < 2 ? 1 : face < 4 ? 2 : 0;
    }

    /** The two in-plane axes (u, v) of a face, in increasing axis order. */
    private static int[] planeAxes(int face) {
        return switch (axisOf(face)) {
            case 0 -> new int[]{1, 2};
            case 1 -> new int[]{0, 2};
            default -> new int[]{0, 1};
        };
    }

    private static long pack3(int a, int b, int c) {
        return ((long) (a & 0x1FFFFF) << 42) | ((long) (b & 0x1FFFFF) << 21) | (c & 0x1FFFFF);
    }

    private static int unpack(long key, int slot) { // slot 0 = high
        int v = (int) ((key >>> (42 - 21 * slot)) & 0x1FFFFF);
        return (v << 11) >> 11; // sign-extend 21 bits
    }

    @SuppressWarnings("unchecked") // generic arrays of fastutil maps
    static void buildGeometry(Long2FloatOpenHashMap cells, BlockPos origin, FloatList faces, FloatList edges) {
        // Exposed faces grouped by (face, plane coordinate) → set of in-plane (u, v) cells.
        Long2ObjectOpenHashMap<LongOpenHashSet>[] planes = new Long2ObjectOpenHashMap[6];
        for (int f = 0; f < 6; f++) planes[f] = new Long2ObjectOpenHashMap<>();
        // Convex edges grouped by (face pair, the two pinned coordinates) → positions along the edge.
        Long2ObjectOpenHashMap<IntArrayList>[] edgeRuns = new Long2ObjectOpenHashMap[36];
        Direction[] dirs = Direction.values();
        LongIterator it = cells.keySet().iterator();
        while (it.hasNext()) {
            long k = it.nextLong();
            int x = BlockPos.getX(k) - origin.getX(), y = BlockPos.getY(k) - origin.getY(), z = BlockPos.getZ(k) - origin.getZ();
            int[] xyz = {x, y, z};
            int mask = 0;
            for (Direction d : dirs) {
                if (!cells.containsKey(BlockPos.offset(k, d))) mask |= 1 << d.ordinal();
            }
            if (mask == 0) continue;
            for (int f = 0; f < 6; f++) {
                if ((mask & (1 << f)) == 0) continue;
                int[] pa = planeAxes(f);
                planes[f].computeIfAbsent(xyz[axisOf(f)], q -> new LongOpenHashSet())
                        .add(((long) xyz[pa[0]] << 32) | (xyz[pa[1]] & 0xFFFFFFFFL));
            }
            if (Integer.bitCount(mask) < 2) continue;
            for (int i = 0; i < 6; i++) {
                if ((mask & (1 << i)) == 0) continue;
                for (int j = i + 1; j < 6; j++) {
                    if ((mask & (1 << j)) == 0 || axisOf(i) == axisOf(j)) continue;
                    int along = 3 - axisOf(i) - axisOf(j);
                    int slot = i * 6 + j;
                    if (edgeRuns[slot] == null) edgeRuns[slot] = new Long2ObjectOpenHashMap<>();
                    edgeRuns[slot].computeIfAbsent(pack3(xyz[axisOf(i)], xyz[axisOf(j)], 0), q -> new IntArrayList())
                            .add(xyz[along]);
                }
            }
        }
        for (int f = 0; f < 6; f++) {
            for (var e : planes[f].long2ObjectEntrySet()) greedyFaces(faces, f, (int) e.getLongKey(), e.getValue());
        }
        for (int slot = 0; slot < 36; slot++) {
            if (edgeRuns[slot] == null) continue;
            int i = slot / 6, j = slot % 6;
            for (var e : edgeRuns[slot].long2ObjectEntrySet()) {
                int ci = unpack(e.getLongKey(), 0), cj = unpack(e.getLongKey(), 1);
                int[] ks = e.getValue().toIntArray();
                java.util.Arrays.sort(ks);
                int start = 0;
                for (int q = 1; q <= ks.length; q++) {
                    if (q == ks.length || ks[q] != ks[q - 1] + 1) {
                        edgeRun(edges, i, j, ci, cj, ks[start], ks[q - 1]);
                        start = q;
                    }
                }
            }
        }
    }

    /** Greedy-merge one plane's exposed faces into rectangles. */
    private static void greedyFaces(FloatList o, int face, int plane, LongOpenHashSet cellsUV) {
        int[] pa = planeAxes(face);
        int axis = axisOf(face);
        boolean positive = (face % 2) == 1;
        float w = positive ? plane + 1 + EXPAND : plane - EXPAND;
        LongOpenHashSet left = new LongOpenHashSet(cellsUV);
        long[] sorted = cellsUV.toLongArray();
        java.util.Arrays.sort(sorted); // by u then v
        for (long start : sorted) {
            if (!left.contains(start)) continue;
            int u0 = (int) (start >> 32), v0 = (int) start;
            int v1 = v0;
            while (left.contains(((long) u0 << 32) | ((v1 + 1) & 0xFFFFFFFFL))) v1++;
            int u1 = u0;
            outer:
            while (true) {
                for (int v = v0; v <= v1; v++) {
                    if (!left.contains(((long) (u1 + 1) << 32) | (v & 0xFFFFFFFFL))) break outer;
                }
                u1++;
            }
            for (int u = u0; u <= u1; u++) {
                for (int v = v0; v <= v1; v++) left.remove(((long) u << 32) | (v & 0xFFFFFFFFL));
            }
            float ua = u0 - EXPAND, ub = u1 + 1 + EXPAND, va = v0 - EXPAND, vb = v1 + 1 + EXPAND;
            float[][] corners = {{ua, va}, {ub, va}, {ub, vb}, {ua, vb}};
            for (float[] uv : corners) {
                float[] p = new float[3];
                p[axis] = w;
                p[pa[0]] = uv[0];
                p[pa[1]] = uv[1];
                o.add(p[0]);
                o.add(p[1]);
                o.add(p[2]);
            }
        }
    }

    /**
     * One merged convex edge between faces i and j, along the remaining axis from cell k0 to k1:
     * a single thin strip set across the corner (at 45° to both faces), so it reads from any
     * outside view at half the vertices of crossed quads.
     */
    private static void edgeRun(FloatList o, int i, int j, int ci, int cj, int k0, int k1) {
        int ai = axisOf(i), aj = axisOf(j), along = 3 - ai - aj;
        float[] lo = new float[3], hi = new float[3];
        lo[ai] = hi[ai] = (i % 2 == 1) ? ci + 1 + EXPAND : ci - EXPAND;
        lo[aj] = hi[aj] = (j % 2 == 1) ? cj + 1 + EXPAND : cj - EXPAND;
        lo[along] = k0 - EXPAND;
        hi[along] = k1 + 1 + EXPAND;
        // Width direction: across the corner, perpendicular to the edge and to the outward
        // bisector of the two face normals.
        float[] w = new float[3];
        float s = EDGE_W * 1.4f;
        w[ai] = (i % 2 == 1) ? s : -s;
        w[aj] = (j % 2 == 1) ? -s : s;
        float[][] q = {
                {lo[0] - w[0], lo[1] - w[1], lo[2] - w[2]}, {hi[0] - w[0], hi[1] - w[1], hi[2] - w[2]},
                {hi[0] + w[0], hi[1] + w[1], hi[2] + w[2]}, {lo[0] + w[0], lo[1] + w[1], lo[2] + w[2]}};
        for (float[] v : q) {
            o.add(v[0]);
            o.add(v[1]);
            o.add(v[2]);
        }
    }
}
