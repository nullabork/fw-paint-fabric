package co.fax.wang.shape;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One shape marker: a circle or square ring (ported from fw-rule). A center control, two
 * radius controls (equal radii = a one-block-wide outline, different radii = a band between
 * them — the wall thickness), the plane it lies in (normal axis, taken from the face clicked
 * when placing the center), a signed extrude height turning the outline into a cylinder/tube,
 * and — squares only — a rotation around the center (dragging a derived green corner node
 * sets it; the rotated outline rasterizes with jagged block edges). Circles measure radius by
 * rounded Euclidean distance (the classic Minecraft circle), squares by Chebyshev distance in
 * their rotated local frame.
 *
 * <p>The radii are captured when a radius control is placed or dragged ({@link #rA}/{@link
 * #rB}) rather than derived live from the control cells — that way rotating the shape spins
 * the blue controls around with it without warping the band.
 *
 * <p>FW Paint semantics on top of the fw-rule model: the shape is a paint REGION. The base
 * plane (layer 0 — where the shape was placed) is the gradient START side and the far end of
 * the extrusion is the END side; {@link #segmentFor} exposes a cell's position along that
 * axis for gradient anchoring, and {@link #contains} restricts placement to the band so
 * cylinders and square donuts paint with exactly the drawn wall thickness.
 */
public final class RingShape {

    public enum Kind { CIRCLE, SQUARE }

    public final Kind kind;
    public BlockPos center;
    public final Direction.Axis normal;
    public BlockPos ctrlA;
    public BlockPos ctrlB;
    /** Band radii captured from the controls when set (see class doc). */
    public int rA, rB;
    /** Signed extrude height, never 0: +h = layers 0..h-1 along +normal, -h = downward. */
    public int height = 1;
    /** Rotation around the center in the shape plane, radians. Squares only; circles keep 0. */
    public double theta;

    public RingShape(Kind kind, BlockPos center, Direction.Axis normal, BlockPos ctrlA, BlockPos ctrlB) {
        this.kind = kind;
        this.center = center.immutable();
        this.normal = normal;
        this.ctrlA = ctrlA.immutable();
        this.ctrlB = ctrlB.immutable();
        this.rA = radiusOf(ctrlA);
        this.rB = radiusOf(ctrlB);
    }

    /** Rebuilds a shape from persisted state (see {@code ShapeStore}). */
    public static RingShape restore(Kind kind, BlockPos center, Direction.Axis normal,
                                    BlockPos ctrlA, BlockPos ctrlB, int rA, int rB,
                                    int height, double theta) {
        RingShape s = new RingShape(kind, center, normal, ctrlA, ctrlB);
        s.rA = rA;
        s.rB = rB;
        s.height = height == 0 ? 1 : height;
        s.theta = theta;
        return s;
    }

    /** The two in-plane axes spanning the shape's plane. */
    public Direction.Axis uAxis() {
        return normal == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X;
    }

    public Direction.Axis vAxis() {
        return normal == Direction.Axis.Z ? Direction.Axis.Y : Direction.Axis.Z;
    }

    /** In-plane radius of a cell in this shape's metric (rotation-aware for squares). */
    public int radiusOf(BlockPos ctrl) {
        int du = ctrl.get(uAxis()) - center.get(uAxis());
        int dv = ctrl.get(vAxis()) - center.get(vAxis());
        if (kind == Kind.CIRCLE) return (int) Math.round(Math.hypot(du, dv));
        double c = Math.cos(-theta), s = Math.sin(-theta);
        double u = du * c - dv * s, v = du * s + dv * c;
        return (int) Math.round(Math.max(Math.abs(u), Math.abs(v)));
    }

    /** Moves a radius control and captures its new radius. */
    public void setRadiusControl(boolean controlA, BlockPos cell) {
        if (controlA) {
            ctrlA = cell.immutable();
            rA = radiusOf(cell);
        } else {
            ctrlB = cell.immutable();
            rB = radiusOf(cell);
        }
    }

    public int rMin() {
        return Math.min(rA, rB);
    }

    public int rMax() {
        return Math.max(rA, rB);
    }

    /** True when (du, dv) in-plane offsets from the center fall on the ring band. */
    public boolean onRing(int du, int dv) {
        return kind == Kind.CIRCLE
                ? ShapeMath.onRing(du, dv, rMin(), rMax())
                : ShapeMath.onRotatedSquareRing(du, dv, theta, rMin(), rMax());
    }

    /** In-plane cell scan bound for rasterizing: rotated square corners reach rMax·√2. */
    public int scanBound() {
        return kind == Kind.CIRCLE ? rMax() + 1 : (int) Math.ceil(rMax() * Math.sqrt(2)) + 1;
    }

    // ---- paint-region semantics (FW Paint addition) ----------------------------------------

    /** Layer offset of a cell along the normal from the base plane (0 = the base plane). */
    public int layerOf(BlockPos cell) {
        return cell.get(normal) - center.get(normal);
    }

    /** True when the cell sits on the ring band within the extrusion. */
    public boolean contains(BlockPos cell) {
        int k = layerOf(cell);
        if (k < layerLo() || k > layerHi()) return false;
        int du = cell.get(uAxis()) - center.get(uAxis());
        int dv = cell.get(vAxis()) - center.get(vAxis());
        return onRing(du, dv);
    }

    /**
     * The cell's gradient segment through the extrusion: from its projection on the base
     * plane (START — the side the shape was placed from) to its projection on the far layer
     * (END), with the cell's index along it. Null when the cell is outside the shape or the
     * extrusion is a single layer (no direction).
     */
    public Segment segmentFor(BlockPos cell) {
        if (!contains(cell)) return null;
        int k = layerOf(cell);
        int far = height > 0 ? layerHi() : layerLo(); // the extrusion's far end
        if (far == 0) return null;
        BlockPos start = offset(cell, normal, -k);          // projection onto the base plane
        BlockPos end = offset(cell, normal, far - k);       // projection onto the far layer
        return new Segment(start, end, Math.abs(k), Math.abs(far));
    }

    /** A cell's start→end run through a region: index 0 = start plane, length = far plane. */
    public record Segment(BlockPos start, BlockPos end, int index, int length) {}

    // ---- square corner nodes (derived, drive rotation) ------------------------------------

    /** Local corner sign pairs, index 0..3: (+,+), (-,+), (-,-), (+,-). */
    private static final int[][] CORNER_SIGNS = {{1, 1}, {-1, 1}, {-1, -1}, {1, -1}};

    /** The 4 derived green corner cells of the outer square, rotated by {@link #theta}. */
    public BlockPos cornerCell(int i) {
        double r = rMax();
        double lu = CORNER_SIGNS[i][0] * r, lv = CORNER_SIGNS[i][1] * r;
        double c = Math.cos(theta), s = Math.sin(theta);
        return cellFromOffset(lu * c - lv * s, lu * s + lv * c);
    }

    /** The corner's angle in the unrotated local frame (±45°, ±135°). */
    public double cornerBaseAngle(int i) {
        return Math.atan2(CORNER_SIGNS[i][1], CORNER_SIGNS[i][0]);
    }

    /** Cell at an exact in-plane offset from the center (rounded to the grid). */
    public BlockPos cellFromOffset(double u, double v) {
        int x = center.getX(), y = center.getY(), z = center.getZ();
        int ru = (int) Math.round(u), rv = (int) Math.round(v);
        x += uAxis() == Direction.Axis.X ? ru : (vAxis() == Direction.Axis.X ? rv : 0);
        y += uAxis() == Direction.Axis.Y ? ru : (vAxis() == Direction.Axis.Y ? rv : 0);
        z += uAxis() == Direction.Axis.Z ? ru : (vAxis() == Direction.Axis.Z ? rv : 0);
        return new BlockPos(x, y, z);
    }

    /** Exact in-plane offset (u, v) of a cell from the center. */
    public double[] offsetOf(BlockPos cell) {
        return new double[]{cell.get(uAxis()) - center.get(uAxis()),
                cell.get(vAxis()) - center.get(vAxis())};
    }

    /**
     * Sets the rotation and spins the blue radius controls around with it (from their exact
     * offsets captured at drag start, so repeated updates don't accumulate rounding drift).
     * The captured radii are untouched — rotation never changes the band.
     */
    public void applyRotation(double newTheta, double dragStartTheta, double[] offA, double[] offB) {
        theta = newTheta;
        double d = newTheta - dragStartTheta;
        double c = Math.cos(d), s = Math.sin(d);
        ctrlA = cellFromOffset(offA[0] * c - offA[1] * s, offA[0] * s + offA[1] * c);
        ctrlB = cellFromOffset(offB[0] * c - offB[1] * s, offB[0] * s + offB[1] * c);
    }

    // ---- cached geometry: silhouette + band raster --------------------------------------------

    private VoxelShape silhouetteCache;
    private long silhouetteKey = Long.MIN_VALUE;
    private int silhouetteBound; // bound the cache was built with — the origin must match it
    // Band raster, built with the silhouette: every band cell's in-plane offset plus a 4-bit
    // neighbor mask (bit0 +u, bit1 -u, bit2 +v, bit3 -v: that neighbor is ALSO in the band).
    // Renderers iterate this instead of re-running the trig membership test per cell per frame.
    private int[] rasterDu = new int[0], rasterDv = new int[0];
    private byte[] rasterMask = new byte[0];

    /**
     * Rebuilds the cached silhouette + raster when the geometry key (radii, height, kind,
     * rotation) changed. The caches are center-relative (moving/scrolling never rebuilds);
     * {@code allowRebuild=false} serves stale caches instead (drag throttling).
     */
    public void ensureGeometry(boolean allowRebuild) {
        long key = ((long) rMin() * 31 + rMax()) * 31 + height;
        key = key * 31 + kind.ordinal();
        key ^= Double.doubleToLongBits(theta);
        if (silhouetteCache != null && (key == silhouetteKey || !allowRebuild)) return;

        int bound = scanBound();
        silhouetteBound = bound;
        int span = layerHi() - layerLo() + 1;
        java.util.List<VoxelShape> boxes = new java.util.ArrayList<>();
        int cap = (2 * bound + 1) * (2 * bound + 1);
        int[] cdu = new int[cap], cdv = new int[cap];
        byte[] cmask = new byte[cap];
        int cells = 0;
        for (int dv = -bound; dv <= bound; dv++) {
            int runStart = Integer.MIN_VALUE;
            for (int du = -bound; du <= bound + 1; du++) {
                boolean on = du <= bound && onRing(du, dv);
                if (on) {
                    int m = (onRing(du + 1, dv) ? 1 : 0) | (onRing(du - 1, dv) ? 2 : 0)
                            | (onRing(du, dv + 1) ? 4 : 0) | (onRing(du, dv - 1) ? 8 : 0);
                    cdu[cells] = du;
                    cdv[cells] = dv;
                    cmask[cells] = (byte) m;
                    cells++;
                }
                if (on && runStart == Integer.MIN_VALUE) {
                    runStart = du;
                } else if (!on && runStart != Integer.MIN_VALUE) {
                    boxes.add(runBox(runStart, du, dv, bound, span));
                    runStart = Integer.MIN_VALUE;
                }
            }
        }
        silhouetteCache = mergeBalanced(boxes, 0, boxes.size());
        rasterDu = java.util.Arrays.copyOf(cdu, cells);
        rasterDv = java.util.Arrays.copyOf(cdv, cells);
        rasterMask = java.util.Arrays.copyOf(cmask, cells);
        silhouetteKey = key;
    }

    /**
     * Balanced pairwise union — {@link Shapes#or} left-folded over hundreds of run boxes is
     * quadratic and was the visible hitch while rotating a big shape; a merge tree is cheap.
     */
    private static VoxelShape mergeBalanced(java.util.List<VoxelShape> boxes, int lo, int hi) {
        if (hi - lo == 0) return Shapes.empty();
        if (hi - lo == 1) return boxes.get(lo);
        int mid = (lo + hi) >>> 1;
        return Shapes.or(mergeBalanced(boxes, lo, mid), mergeBalanced(boxes, mid, hi));
    }

    /**
     * A merged {@link VoxelShape} of the whole ring band × extrusion, anchored at
     * {@link #silhouetteOrigin()} — its outline traces the composite shape's true edges and
     * is submitted after translucent terrain so it reads over water.
     */
    public VoxelShape silhouette(boolean allowRebuild) {
        ensureGeometry(allowRebuild);
        return silhouetteCache;
    }

    public int rasterSize() {
        return rasterDu.length;
    }

    public int rasterDu(int i) {
        return rasterDu[i];
    }

    public int rasterDv(int i) {
        return rasterDv[i];
    }

    /** 4-bit in-plane neighbor mask: bit0 +u, bit1 -u, bit2 +v, bit3 -v in the band. */
    public int rasterMask(int i) {
        return rasterMask[i];
    }

    /** World cell the silhouette shape's (0,0,0) corner sits on (matches the cached build). */
    public BlockPos silhouetteOrigin() {
        int bound = silhouetteCache != null ? silhouetteBound : scanBound();
        BlockPos p = center;
        p = offset(p, uAxis(), -bound);
        p = offset(p, vAxis(), -bound);
        p = offset(p, normal, layerLo());
        return p;
    }

    /** One horizontal run of ring cells [du0, du1) at dv, spanning the full extrusion. */
    private VoxelShape runBox(int du0, int du1, int dv, int bound, int span) {
        double u0 = du0 + bound, u1 = du1 + bound;
        double v0 = dv + bound, v1 = dv + 1.0 + bound;
        return switch (normal) {
            case Y -> Shapes.box(u0, 0, v0, u1, span, v1); // u = X, v = Z
            case X -> Shapes.box(0, u0, v0, span, u1, v1); // u = Y, v = Z
            case Z -> Shapes.box(u0, v0, 0, u1, v1, span); // u = X, v = Y
        };
    }

    static BlockPos offset(BlockPos p, Direction.Axis axis, int k) {
        return switch (axis) {
            case X -> p.offset(k, 0, 0);
            case Y -> p.offset(0, k, 0);
            case Z -> p.offset(0, 0, k);
        };
    }

    // ---- extrusion ---------------------------------------------------------------------------

    /** Lowest layer offset along the normal (0 for positive heights). */
    public int layerLo() {
        return Math.min(0, height + 1);
    }

    /** Highest layer offset along the normal (0 for negative heights). */
    public int layerHi() {
        return Math.max(0, height - 1);
    }

    /** Moves the whole shape (center + radius controls) so the center lands on {@code newCenter}. */
    public void moveCenterTo(BlockPos newCenter) {
        BlockPos delta = newCenter.subtract(center);
        center = center.offset(delta).immutable();
        ctrlA = ctrlA.offset(delta).immutable();
        ctrlB = ctrlB.offset(delta).immutable();
    }

    /**
     * Grows/shrinks the extrusion by {@code d} (±1). Height skips 0: shrinking a one-tall
     * shape flips it to grow out the other side of the base plane.
     */
    public void extrude(int d) {
        height += d;
        if (height == 0) height += d;
    }
}
