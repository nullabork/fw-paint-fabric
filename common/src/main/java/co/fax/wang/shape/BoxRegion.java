package co.fax.wang.shape;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The one-piece box marker: two corners spanning an axis-aligned region, replacing the old
 * Marker-corners columns of individual start/end markers. The face clicked when anchoring
 * corner A gives the box its gradient axis — corner A's plane along that axis is the START
 * side and corner B's plane the END side, so gradients painted inside the box run from the
 * face you started on to the opposite side. Placement inside the box is restricted to it.
 */
public final class BoxRegion {

    public final BlockPos cornerA;
    public final BlockPos cornerB;
    /** Gradient axis: the axis of corner A's clicked face. */
    public final Direction.Axis axis;

    public BoxRegion(BlockPos cornerA, BlockPos cornerB, Direction.Axis axis) {
        this.cornerA = cornerA.immutable();
        this.cornerB = cornerB.immutable();
        this.axis = axis;
    }

    public BlockPos min() {
        return new BlockPos(Math.min(cornerA.getX(), cornerB.getX()),
                Math.min(cornerA.getY(), cornerB.getY()),
                Math.min(cornerA.getZ(), cornerB.getZ()));
    }

    public BlockPos max() {
        return new BlockPos(Math.max(cornerA.getX(), cornerB.getX()),
                Math.max(cornerA.getY(), cornerB.getY()),
                Math.max(cornerA.getZ(), cornerB.getZ()));
    }

    /** True when the cell lies inside the box (corners inclusive). */
    public boolean contains(BlockPos cell) {
        BlockPos mn = min(), mx = max();
        return cell.getX() >= mn.getX() && cell.getX() <= mx.getX()
                && cell.getY() >= mn.getY() && cell.getY() <= mx.getY()
                && cell.getZ() >= mn.getZ() && cell.getZ() <= mx.getZ();
    }

    // ---- edits (a box is immutable: each returns the edited copy) -----------------------------

    /** The same corners with the gradient along {@code a}. */
    public BoxRegion withAxis(Direction.Axis a) {
        return new BoxRegion(cornerA, cornerB, a);
    }

    /** Start and end swapped: the gradient runs the other way. */
    public BoxRegion flipped() {
        return new BoxRegion(cornerB, cornerA, axis);
    }

    public BoxRegion moved(int dx, int dy, int dz) {
        return new BoxRegion(cornerA.offset(dx, dy, dz), cornerB.offset(dx, dy, dz), axis);
    }

    /** The cell coordinate of the box's {@code face} side (max on a positive face, min on a negative). */
    public int faceCoord(Direction face) {
        return face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? max().get(face.getAxis()) : min().get(face.getAxis());
    }

    /**
     * The box with its {@code face} side moved to cell coordinate {@code coord} (clamped so the
     * box never turns inside out). Whichever corner holds that side moves; on a box flat along the
     * axis, corner B takes the positive side and A the negative.
     */
    public BoxRegion withFace(Direction face, int coord) {
        Direction.Axis ax = face.getAxis();
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        int a = cornerA.get(ax), b = cornerB.get(ax);
        boolean moveB = positive ? b >= a : b < a;
        int other = moveB ? a : b;
        coord = positive ? Math.max(other, coord) : Math.min(other, coord);
        BlockPos na = moveB ? cornerA : with(cornerA, ax, coord);
        BlockPos nb = moveB ? with(cornerB, ax, coord) : cornerB;
        return new BoxRegion(na, nb, axis);
    }

    private static BlockPos with(BlockPos p, Direction.Axis ax, int v) {
        return switch (ax) {
            case X -> new BlockPos(v, p.getY(), p.getZ());
            case Y -> new BlockPos(p.getX(), v, p.getZ());
            case Z -> new BlockPos(p.getX(), p.getY(), v);
        };
    }

    /**
     * The cell's gradient segment through the box: from its projection on corner A's plane
     * (START) to its projection on corner B's plane (END) along the box axis. Null when the
     * cell is outside the box or the box is flat on the axis (no direction).
     */
    public RingShape.Segment segmentFor(BlockPos cell) {
        if (!contains(cell)) return null;
        int a = cornerA.get(axis), b = cornerB.get(axis);
        int length = Math.abs(b - a);
        if (length == 0) return null;
        BlockPos start = RingShape.offset(cell, axis, a - cell.get(axis));
        BlockPos end = RingShape.offset(cell, axis, b - cell.get(axis));
        return new RingShape.Segment(start, end, Math.abs(cell.get(axis) - a), length);
    }
}
