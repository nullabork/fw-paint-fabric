package co.fax.wang.shape;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Gravity for a line: the line as one rigid body — a chain of balls along its curve, each as fat
 * as the tube there — dropped onto the world's blocks. It falls, hits, tumbles and slides until
 * it lies still, which is where a branch of that shape would come to rest on that ground.
 *
 * <p>Plain impulse physics: gravity, one deepest contact per ball against the block cubes around
 * it, bounce-free impulses with friction, then a nudge out of whatever is still overlapping. It
 * knows nothing about Minecraft beyond {@link Solid}, so it runs in tests. The result is a turn
 * ({@link #axis}, {@link #angle}) about where the body's centre started ({@link #start}) plus a
 * shift ({@link #shift}) — exactly what {@link LineShape#turnFrom} and a translate apply.
 */
final class LinePhysics {

    /** Whether the block cell (x, y, z) is solid ground. */
    interface Solid {
        boolean at(int x, int y, int z);
    }

    /** Blocks per second squared. */
    static final double GRAVITY = 22;
    static final double DT = 1.0 / 60;
    /** Substeps run per game tick (20 a second), so the fall plays at real speed. */
    static final int STEPS_PER_TICK = 3;
    private static final double MAX_SPEED = 20;       // keeps a ball from skipping through a block in one step
    private static final double FRICTION = 0.7;
    private static final double LINEAR_KEEP = 0.998, ANGULAR_KEEP = 0.985; // per step: a little drag settles it
    private static final double SLOP = 0.01;          // overlap left alone (stops resting contacts flickering)
    private static final double REST_SPEED = 0.25, REST_SPIN = 0.25;
    private static final int REST_TICKS = 12;

    private final int n;
    private final double[] ox, oy, oz, rad, mass; // balls: offset from the centre (world axes, now), radius, mass
    private final double total;
    /** Where the body's centre of mass was when it was let go. */
    final Vec3 start;
    private double px, py, pz, vx, vy, vz, wx, wy, wz;
    private double qw = 1, qx, qy, qz; // how far it has turned since the start
    private int restTicks;
    private boolean touching;

    private record Contact(double rx, double ry, double rz, double nx, double ny, double nz, double depth) {}

    /** A body from ball centres and radii (at least one). */
    LinePhysics(List<Vec3> centres, double[] radii) {
        n = centres.size();
        ox = new double[n];
        oy = new double[n];
        oz = new double[n];
        rad = radii.clone();
        mass = new double[n];
        double m = 0, cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < n; i++) {
            mass[i] = rad[i] * rad[i] * rad[i];
            m += mass[i];
            cx += centres.get(i).x * mass[i];
            cy += centres.get(i).y * mass[i];
            cz += centres.get(i).z * mass[i];
        }
        total = m;
        px = cx / m;
        py = cy / m;
        pz = cz / m;
        start = new Vec3(px, py, pz);
        for (int i = 0; i < n; i++) {
            ox[i] = centres.get(i).x - px;
            oy[i] = centres.get(i).y - py;
            oz[i] = centres.get(i).z - pz;
        }
    }

    /** The body for a line: a ball at each end and every tube-radius or so between. */
    static LinePhysics of(LineShape l) {
        List<LineCurve.Sample> ss = l.samples();
        List<Vec3> centres = new ArrayList<>();
        List<Double> radii = new ArrayList<>();
        double lastS = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < ss.size(); i++) {
            double r = l.profile == LineProfile.NONE ? 0.5 : l.radiusAt(i) + 0.5;
            if (i == ss.size() - 1 || ss.get(i).s() - lastS >= Math.max(0.75, r)) {
                centres.add(ss.get(i).pos());
                radii.add(r);
                lastS = ss.get(i).s();
            }
        }
        if (centres.isEmpty()) return null;
        double[] r = new double[radii.size()];
        for (int i = 0; i < r.length; i++) r[i] = radii.get(i);
        return new LinePhysics(centres, r);
    }

    // ---- result ---------------------------------------------------------------------------------

    /** How far the centre has moved since the start. */
    Vec3 shift() {
        return new Vec3(px - start.x, py - start.y, pz - start.z);
    }

    /** The turn since the start, as an angle (radians) about {@link #axis}. */
    double angle() {
        return 2 * Math.acos(Math.max(-1, Math.min(1, qw)));
    }

    Vec3 axis() {
        double s = Math.sqrt(qx * qx + qy * qy + qz * qz);
        return s < 1e-9 ? new Vec3(0, 1, 0) : new Vec3(qx / s, qy / s, qz / s);
    }

    /** The lowest point of any ball. */
    double bottom() {
        double b = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) b = Math.min(b, py + oy[i] - rad[i]);
        return b;
    }

    /** Ball {@code i}'s centre now (tests). */
    Vec3 ball(int i) {
        return new Vec3(px + ox[i], py + oy[i], pz + oz[i]);
    }

    int balls() {
        return n;
    }

    /** True once it has lain still on something for a moment. */
    boolean atRest() {
        return restTicks >= REST_TICKS;
    }

    // ---- stepping -------------------------------------------------------------------------------

    /**
     * Before letting go: a body that starts inside the ground is lifted straight up until none of
     * its balls' centres are in a block (at most {@code limit} blocks). Returns how far it rose.
     */
    int popUp(Solid world, int limit) {
        int rose = 0;
        while (rose < limit && buried(world)) {
            py += 1;
            rose++;
        }
        return rose;
    }

    private boolean buried(Solid world) {
        for (int i = 0; i < n; i++) {
            if (world.at((int) Math.floor(px + ox[i]), (int) Math.floor(py + oy[i]), (int) Math.floor(pz + oz[i]))) {
                return true;
            }
        }
        return false;
    }

    /** One game tick of falling. */
    void tick(Solid world) {
        for (int s = 0; s < STEPS_PER_TICK; s++) step(world);
        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz), spin = Math.sqrt(wx * wx + wy * wy + wz * wz);
        restTicks = touching && speed < REST_SPEED && spin < REST_SPIN ? restTicks + 1 : 0;
    }

    private void step(Solid world) {
        vy -= GRAVITY * DT;
        vx *= LINEAR_KEEP;
        vy *= LINEAR_KEEP;
        vz *= LINEAR_KEEP;
        wx *= ANGULAR_KEEP;
        wy *= ANGULAR_KEEP;
        wz *= ANGULAR_KEEP;
        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (speed > MAX_SPEED) {
            vx *= MAX_SPEED / speed;
            vy *= MAX_SPEED / speed;
            vz *= MAX_SPEED / speed;
        }
        px += vx * DT;
        py += vy * DT;
        pz += vz * DT;
        turn(wx * DT, wy * DT, wz * DT);

        List<Contact> contacts = contacts(world);
        touching = !contacts.isEmpty();
        if (!touching) return;
        double[] inv = inverseInertia();
        for (int it = 0; it < 6; it++) {
            for (Contact c : contacts) impulse(c, inv);
        }
        // Whatever still overlaps is pushed apart (a shift only; the impulses handle the turning).
        double cx = 0, cy = 0, cz = 0;
        for (int it = 0; it < 3; it++) {
            for (Contact c : contacts) {
                double need = c.depth - SLOP - (cx * c.nx + cy * c.ny + cz * c.nz);
                if (need <= 0) continue;
                cx += c.nx * need * 0.8;
                cy += c.ny * need * 0.8;
                cz += c.nz * need * 0.8;
            }
        }
        px += cx;
        py += cy;
        pz += cz;
    }

    /** Turn the body by the small rotation vector (ax, ay, az): every ball offset, and the running total. */
    private void turn(double ax, double ay, double az) {
        double a = Math.sqrt(ax * ax + ay * ay + az * az);
        if (a < 1e-12) return;
        double kx = ax / a, ky = ay / a, kz = az / a, c = Math.cos(a), s = Math.sin(a);
        for (int i = 0; i < n; i++) {
            double x = ox[i], y = oy[i], z = oz[i];
            double dot = kx * x + ky * y + kz * z;
            ox[i] = x * c + (ky * z - kz * y) * s + kx * dot * (1 - c);
            oy[i] = y * c + (kz * x - kx * z) * s + ky * dot * (1 - c);
            oz[i] = z * c + (kx * y - ky * x) * s + kz * dot * (1 - c);
        }
        double h = a / 2, sw = Math.cos(h), sh = Math.sin(h);
        double dx = kx * sh, dy = ky * sh, dz = kz * sh;
        double nw = sw * qw - dx * qx - dy * qy - dz * qz;
        double nx = sw * qx + dx * qw + dy * qz - dz * qy;
        double ny = sw * qy - dx * qz + dy * qw + dz * qx;
        double nz = sw * qz + dx * qy - dy * qx + dz * qw;
        double len = Math.sqrt(nw * nw + nx * nx + ny * ny + nz * nz);
        qw = nw / len;
        qx = nx / len;
        qy = ny / len;
        qz = nz / len;
        if (qw < 0) { // keep the short way round
            qw = -qw;
            qx = -qx;
            qy = -qy;
            qz = -qz;
        }
    }

    /** Each ball's deepest overlap with a solid block around it. */
    private List<Contact> contacts(Solid world) {
        List<Contact> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double x = px + ox[i], y = py + oy[i], z = pz + oz[i], r = rad[i];
            double best = 0, bnx = 0, bny = 0, bnz = 0, bcx = 0, bcy = 0, bcz = 0;
            int x0 = (int) Math.floor(x - r), x1 = (int) Math.floor(x + r);
            int y0 = (int) Math.floor(y - r), y1 = (int) Math.floor(y + r);
            int z0 = (int) Math.floor(z - r), z1 = (int) Math.floor(z + r);
            for (int bx = x0; bx <= x1; bx++) {
                for (int by = y0; by <= y1; by++) {
                    for (int bz = z0; bz <= z1; bz++) {
                        // The block's point nearest the ball's centre.
                        double cx = Math.max(bx, Math.min(bx + 1, x));
                        double cy = Math.max(by, Math.min(by + 1, y));
                        double cz = Math.max(bz, Math.min(bz + 1, z));
                        double dx = x - cx, dy = y - cy, dz = z - cz;
                        double d2 = dx * dx + dy * dy + dz * dz;
                        if (d2 >= r * r || !world.at(bx, by, bz)) continue;
                        double d = Math.sqrt(d2), depth, nx, ny, nz;
                        if (d > 1e-6) {
                            depth = r - d;
                            nx = dx / d;
                            ny = dy / d;
                            nz = dz / d;
                        } else {
                            // The centre itself is inside the block: out through its top.
                            depth = r + (by + 1 - y);
                            nx = 0;
                            ny = 1;
                            nz = 0;
                            cy = by + 1;
                        }
                        if (depth > best) {
                            best = depth;
                            bnx = nx;
                            bny = ny;
                            bnz = nz;
                            bcx = cx;
                            bcy = cy;
                            bcz = cz;
                        }
                    }
                }
            }
            if (best > 0) out.add(new Contact(bcx - px, bcy - py, bcz - pz, bnx, bny, bnz, best));
        }
        return out;
    }

    /** The inverse of the body's inertia about its centre, as it is turned now (row-major 3×3). */
    private double[] inverseInertia() {
        double a = 0, b = 0, c = 0, d = 0, e = 0, f = 0; // xx yy zz xy xz yz
        for (int i = 0; i < n; i++) {
            double x = ox[i], y = oy[i], z = oz[i], m = mass[i];
            double own = 0.4 * m * rad[i] * rad[i]; // each ball's own inertia keeps a straight line turnable
            a += m * (y * y + z * z) + own;
            b += m * (x * x + z * z) + own;
            c += m * (x * x + y * y) + own;
            d -= m * x * y;
            e -= m * x * z;
            f -= m * y * z;
        }
        double det = a * (b * c - f * f) - d * (d * c - f * e) + e * (d * f - b * e);
        double k = 1 / det;
        return new double[]{
                (b * c - f * f) * k, (e * f - d * c) * k, (d * f - e * b) * k,
                (e * f - d * c) * k, (a * c - e * e) * k, (d * e - a * f) * k,
                (d * f - e * b) * k, (d * e - a * f) * k, (a * b - d * d) * k};
    }

    /** Stop the body moving into a contact, and let friction slow it sliding across it. */
    private void impulse(Contact c, double[] inv) {
        // Velocity of the contact point.
        double ux = vx + wy * c.rz - wz * c.ry, uy = vy + wz * c.rx - wx * c.rz, uz = vz + wx * c.ry - wy * c.rx;
        double un = ux * c.nx + uy * c.ny + uz * c.nz;
        if (un >= 0) return;
        double j = -un / effective(c, inv, c.nx, c.ny, c.nz);
        push(c, inv, c.nx * j, c.ny * j, c.nz * j);
        // Friction, against what is left of the sideways sliding.
        ux = vx + wy * c.rz - wz * c.ry;
        uy = vy + wz * c.rx - wx * c.rz;
        uz = vz + wx * c.ry - wy * c.rx;
        un = ux * c.nx + uy * c.ny + uz * c.nz;
        double tx = ux - un * c.nx, ty = uy - un * c.ny, tz = uz - un * c.nz;
        double slide = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (slide < 1e-9) return;
        tx /= slide;
        ty /= slide;
        tz /= slide;
        double jt = Math.min(FRICTION * j, slide / effective(c, inv, tx, ty, tz));
        push(c, inv, -tx * jt, -ty * jt, -tz * jt);
    }

    /** How hard the body resists being pushed at the contact along (dx, dy, dz): 1/m + … */
    private double effective(Contact c, double[] inv, double dx, double dy, double dz) {
        double ax = c.ry * dz - c.rz * dy, ay = c.rz * dx - c.rx * dz, az = c.rx * dy - c.ry * dx; // r × d
        double ix = inv[0] * ax + inv[1] * ay + inv[2] * az;
        double iy = inv[3] * ax + inv[4] * ay + inv[5] * az;
        double iz = inv[6] * ax + inv[7] * ay + inv[8] * az;
        double bx = iy * c.rz - iz * c.ry, by = iz * c.rx - ix * c.rz, bz = ix * c.ry - iy * c.rx; // (I⁻¹ a) × r
        return 1 / total + bx * dx + by * dy + bz * dz;
    }

    /** Apply the impulse (jx, jy, jz) at the contact. */
    private void push(Contact c, double[] inv, double jx, double jy, double jz) {
        vx += jx / total;
        vy += jy / total;
        vz += jz / total;
        double ax = c.ry * jz - c.rz * jy, ay = c.rz * jx - c.rx * jz, az = c.rx * jy - c.ry * jx; // r × j
        wx += inv[0] * ax + inv[1] * ay + inv[2] * az;
        wy += inv[3] * ax + inv[4] * ay + inv[5] * az;
        wz += inv[6] * ax + inv[7] * ay + inv[8] * az;
    }
}
