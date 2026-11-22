package co.fax.wang.shape;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * A line's drawn profile: a flat, one-block-thick picture made of block cells somewhere in the
 * world, which the line sweeps along itself instead of a circle or square. The picture stays
 * where it was drawn (moving the line doesn't move it); only its shape matters to the tube.
 *
 * <p>Cells are kept as (a, b) offsets from {@link #origin} along the plane's two axes: a along
 * {@link #uAxis} (up, for a picture drawn on a wall), b along {@link #vAxis}. Swept, the
 * picture is centred on the line (the middle of its bounding box) with a pointing along the
 * line's normal, and scaled so the longer half of that box is the tube's radius there, so at
 * its natural size (see {@link #natural}) one drawn cell is one block of tube.
 */
public final class ProfileDrawing {

    /** No picture is wider than this, or has more cells than {@link #MAX_CELLS}. */
    public static final int MAX_SPAN = 96, MAX_CELLS = 8192;

    /** The world cell that is (0, 0), or null until the first cell is drawn. */
    public BlockPos origin;
    /** The axis the picture's plane faces along. */
    public Direction.Axis normal = Direction.Axis.Y;
    /**
     * Which way was up and which was right for whoever drew it (world directions in the plane):
     * up and the right of the way they faced for a wall; away from them and to their right for a
     * picture on a floor, like a sheet of paper on a table. The sweep stands the picture up the
     * same way round (see {@link #orient}).
     */
    public Direction top = Direction.UP, right = Direction.EAST;
    private final LongOpenHashSet cells = new LongOpenHashSet();
    // How the swept section's (u, v) turn into the picture's top / right (set by orient).
    private double tu = 1, tv = 0, ru = 0, rv = 1;
    // Bounding box of the cells, kept up to date.
    private int aMin, aMax, bMin, bMax;

    private static long key(int a, int b) {
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }

    public Direction.Axis uAxis() {
        return normal == Direction.Axis.Y ? Direction.Axis.X : Direction.Axis.Y;
    }

    public Direction.Axis vAxis() {
        return normal == Direction.Axis.Z ? Direction.Axis.X : Direction.Axis.Z;
    }

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    public int size() {
        return cells.size();
    }

    /** True when (a, b) is a drawn cell. */
    public boolean has(int a, int b) {
        return cells.contains(key(a, b));
    }

    /** True when world cell {@code p} is one of the picture's. */
    public boolean has(BlockPos p) {
        return origin != null && onPlane(p) && has(p.get(uAxis()) - origin.get(uAxis()), p.get(vAxis()) - origin.get(vAxis()));
    }

    /** True when world cell {@code p} is in the picture's one-block layer. */
    public boolean onPlane(BlockPos p) {
        return p.get(normal) == origin.get(normal);
    }

    /** Start a picture: its first cell and the way its plane faces (drawn looking north, or west at an X wall). */
    public void start(BlockPos cell, Direction.Axis facing) {
        start(cell, facing, switch (facing) {
            case X -> new Vec3(-1, 0, 0);
            case Y -> new Vec3(0, -1, -1);
            case Z -> new Vec3(0, 0, -1);
        });
    }

    /** Start a picture: its first cell, the way its plane faces, and the way whoever draws it is looking. */
    public void start(BlockPos cell, Direction.Axis facing, Vec3 look) {
        cells.clear();
        origin = cell.immutable();
        normal = facing;
        Direction f; // the way they face, squared up
        if (facing == Direction.Axis.Y) {
            f = Math.abs(look.x) > Math.abs(look.z) ? (look.x > 0 ? Direction.EAST : Direction.WEST)
                    : (look.z > 0 ? Direction.SOUTH : Direction.NORTH);
            top = f;
        } else {
            f = facing == Direction.Axis.X ? (look.x > 0 ? Direction.EAST : Direction.WEST)
                    : (look.z > 0 ? Direction.SOUTH : Direction.NORTH);
            top = Direction.UP;
        }
        right = f.getClockWise();
        tu = 1;
        tv = 0;
        ru = 0;
        rv = 1;
        add(0, 0);
    }

    private static Vec3 vec(Direction d) {
        return new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
    }

    private static Vec3 across(Vec3 v, Vec3 tan) {
        return v.subtract(tan.scale(v.dot(tan)));
    }

    /**
     * Set the picture across a line whose first slice has tangent {@code tan} and frame
     * ({@code n}, {@code b}). A line that runs through the picture's plane sweeps it exactly as
     * it was drawn. One that runs along the plane stands it up: its top goes up (or, for a line
     * going straight up a wall picture, the way the drawer faced). Its right stays to the right;
     * when the drawer's right runs along the line there is no telling, and it reads the right
     * way round looking along the line from its start. The frame carries that along the line.
     */
    public void orient(Vec3 tan, Vec3 n, Vec3 b) {
        Vec3 t = vec(top), r = vec(right), ti = null, up = new Vec3(0, 1, 0), faced = t.cross(r);
        boolean through = Math.abs(tan.get(normal)) >= 0.7;
        for (Vec3 c : through ? new Vec3[]{t, up, faced, n} : new Vec3[]{up, faced, t, n}) {
            Vec3 a = across(c, tan);
            if (a.length() >= 0.35) {
                ti = a.normalize();
                break;
            }
        }
        if (ti == null) return;
        Vec3 ri = tan.cross(ti);
        if (ri.dot(r) < -0.2) ri = ri.scale(-1);
        tu = ti.dot(n);
        tv = ti.dot(b);
        ru = ri.dot(n);
        rv = ri.dot(b);
    }

    /** Draw world cell {@code p} (it must lie on the picture's plane). False when nothing changed. */
    public boolean add(BlockPos p) {
        if (origin == null || !onPlane(p)) return false;
        return add(p.get(uAxis()) - origin.get(uAxis()), p.get(vAxis()) - origin.get(vAxis()));
    }

    public boolean add(int a, int b) {
        if (cells.size() >= MAX_CELLS) return false;
        if (!cells.isEmpty() && (Math.max(aMax, a) - Math.min(aMin, a) >= MAX_SPAN
                || Math.max(bMax, b) - Math.min(bMin, b) >= MAX_SPAN)) {
            return false;
        }
        if (!cells.add(key(a, b))) return false;
        rebound();
        return true;
    }

    /** Rub out world cell {@code p}. False when it wasn't drawn. */
    public boolean remove(BlockPos p) {
        if (origin == null || !onPlane(p)) return false;
        if (!cells.remove(key(p.get(uAxis()) - origin.get(uAxis()), p.get(vAxis()) - origin.get(vAxis())))) return false;
        rebound();
        return true;
    }

    private void rebound() {
        aMin = bMin = Integer.MAX_VALUE;
        aMax = bMax = Integer.MIN_VALUE;
        for (long k : cells) {
            int a = (int) (k >> 32), b = (int) k;
            aMin = Math.min(aMin, a);
            aMax = Math.max(aMax, a);
            bMin = Math.min(bMin, b);
            bMax = Math.max(bMax, b);
        }
    }

    /** Half the longer side of the picture's bounding box, in blocks: the radius at which it sweeps 1 : 1. */
    public double natural() {
        return cells.isEmpty() ? 0 : Math.max(aMax - aMin + 1, bMax - bMin + 1) / 2.0;
    }

    /**
     * True when the profile-plane point (u, v) falls on a drawn cell, with the picture scaled so
     * its {@link #natural} half-size is {@code e}.
     */
    public boolean inside(double u, double v, double e) {
        double n = natural();
        if (n <= 0 || e <= 0) return false;
        double k = n / e;
        // A hair over, so a point exactly on a cell boundary (an even-sized picture on a grid-
        // aligned line) falls the same way every time whatever the rounding in (u, v).
        double up = u * tu + v * tv, across = u * ru + v * rv;
        double pa, pb;
        if (top.getAxis() == uAxis()) {
            pa = up * sign(top);
            pb = across * sign(right);
        } else {
            pa = across * sign(right);
            pb = up * sign(top);
        }
        int a = (int) Math.floor(pa * k + (aMin + aMax + 1) / 2.0 + 1e-6);
        int b = (int) Math.floor(pb * k + (bMin + bMax + 1) / 2.0 + 1e-6);
        return a >= aMin && a <= aMax && b >= bMin && b <= bMax && has(a, b);
    }

    private static int sign(Direction d) {
        return d.getStepX() + d.getStepY() + d.getStepZ();
    }

    /** The world cell of the picture's plane the ray from {@code eye} along {@code dir} passes through, or null. */
    public BlockPos cellOnPlane(Vec3 eye, Vec3 dir) {
        if (origin == null) return null;
        double d = dir.get(normal);
        if (Math.abs(d) < 1e-6) return null;
        double t = (origin.get(normal) + 0.5 - eye.get(normal)) / d; // the middle of the one-block layer
        if (t <= 0) return null;
        Vec3 p = eye.add(dir.scale(t));
        BlockPos c = BlockPos.containing(p);
        return switch (normal) { // exactly on the layer, whatever the rounding did
            case X -> new BlockPos(origin.getX(), c.getY(), c.getZ());
            case Y -> new BlockPos(c.getX(), origin.getY(), c.getZ());
            case Z -> new BlockPos(c.getX(), c.getY(), origin.getZ());
        };
    }

    /** Every drawn cell as a world position. */
    public java.util.List<BlockPos> worldCells() {
        java.util.List<BlockPos> out = new java.util.ArrayList<>(cells.size());
        if (origin == null) return out;
        for (long k : cells) out.add(origin.relative(uAxis(), (int) (k >> 32)).relative(vAxis(), (int) k));
        return out;
    }

    /** The cells as [a0, b0, a1, b1, …] (for saving). */
    public int[] flat() {
        int[] out = new int[cells.size() * 2];
        int i = 0;
        for (long k : cells) {
            out[i++] = (int) (k >> 32);
            out[i++] = (int) k;
        }
        return out;
    }

    /** A picture from saved parts; null when they make no sense. */
    public static ProfileDrawing of(int[] origin, String axis, int[] flat, String top, String right) {
        if (origin == null || origin.length < 3 || flat == null || flat.length < 2) return null;
        ProfileDrawing d = new ProfileDrawing();
        d.origin = new BlockPos(origin[0], origin[1], origin[2]);
        try {
            d.normal = Direction.Axis.valueOf(axis);
        } catch (RuntimeException e) {
            return null;
        }
        d.start(d.origin, d.normal);
        d.cells.clear();
        try {
            Direction t = Direction.valueOf(top), r = Direction.valueOf(right);
            if (t.getAxis() != d.normal && r.getAxis() != d.normal && t.getAxis() != r.getAxis()) {
                d.top = t;
                d.right = r;
            }
        } catch (RuntimeException e) {
            // an older save: the squared-up default from start() stands
        }
        for (int i = 0; i + 1 < flat.length; i += 2) d.add(flat[i], flat[i + 1]);
        return d.isEmpty() ? null : d;
    }

    public ProfileDrawing copy() {
        ProfileDrawing d = new ProfileDrawing();
        d.origin = origin;
        d.normal = normal;
        d.top = top;
        d.right = right;
        d.tu = tu;
        d.tv = tv;
        d.ru = ru;
        d.rv = rv;
        d.cells.addAll(cells);
        d.aMin = aMin;
        d.aMax = aMax;
        d.bMin = bMin;
        d.bMax = bMax;
        return d;
    }
}
