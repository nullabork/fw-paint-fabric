package co.fax.wang.shape;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The line marker's curve: a cubic Bézier spline through off-grid main nodes. Each node keeps two
 * curve handles stored RELATIVE to it, so moving a node carries its handles along. Segment i runs
 * node i → node i+1 with control points (p_i, p_i + out_i, p_{i+1} + in_{i+1}, p_{i+1}); zero
 * handles give straight segments.
 *
 * <p>Pure geometry (no world access): sampling by arc length, rotation-minimising frames for the
 * tube sweep, closest point to a view ray, and shape-preserving node insertion.
 */
public final class LineCurve {

    /** A main node: position plus its in/out curve handles (relative offsets). */
    public static final class Node {
        public Vec3 p;
        public Vec3 in = Vec3.ZERO;
        public Vec3 out = Vec3.ZERO;

        public Node(Vec3 p) {
            this.p = p;
        }

        public Node(Vec3 p, Vec3 in, Vec3 out) {
            this.p = p;
            this.in = in;
            this.out = out;
        }

        Node copy() {
            return new Node(p, in, out);
        }
    }

    /**
     * One sample along the curve: segment + local t, arc length s from the start, position, and
     * the rotation-minimising frame (unit tangent t, normal n, binormal b).
     */
    public record Sample(int seg, double t, double s, Vec3 pos, Vec3 tan, Vec3 n, Vec3 b) {}

    public final List<Node> nodes = new ArrayList<>();

    public LineCurve(Vec3 start, Vec3 end) {
        nodes.add(new Node(start));
        nodes.add(new Node(end));
    }

    public LineCurve() {}

    public LineCurve copy() {
        LineCurve c = new LineCurve();
        for (Node n : nodes) c.nodes.add(n.copy());
        return c;
    }

    public int segments() {
        return Math.max(0, nodes.size() - 1);
    }

    /** Point on segment {@code seg} at local parameter {@code t} in [0, 1]. */
    public Vec3 point(int seg, double t) {
        Node a = nodes.get(seg), b = nodes.get(seg + 1);
        Vec3 p0 = a.p, p1 = a.p.add(a.out), p2 = b.p.add(b.in), p3 = b.p;
        double u = 1 - t;
        double w0 = u * u * u, w1 = 3 * u * u * t, w2 = 3 * u * t * t, w3 = t * t * t;
        return new Vec3(
                w0 * p0.x + w1 * p1.x + w2 * p2.x + w3 * p3.x,
                w0 * p0.y + w1 * p1.y + w2 * p2.y + w3 * p3.y,
                w0 * p0.z + w1 * p1.z + w2 * p2.z + w3 * p3.z);
    }

    /** Rough segment length from its control polygon (upper bound of the arc length). */
    private double hullLength(int seg) {
        Node a = nodes.get(seg), b = nodes.get(seg + 1);
        Vec3 p1 = a.p.add(a.out), p2 = b.p.add(b.in);
        return a.p.distanceTo(p1) + p1.distanceTo(p2) + p2.distanceTo(b.p);
    }

    /**
     * Samples every {@code maxStep} blocks or closer, start to end, with arc lengths and
     * rotation-minimising frames (double reflection, Wang et al. 2008). Consecutive duplicate
     * points (zero-length stretches) are dropped so every tangent is well defined.
     */
    public List<Sample> sample(double maxStep) {
        List<Vec3> pts = new ArrayList<>();
        List<int[]> segOf = new ArrayList<>();
        List<Double> tOf = new ArrayList<>();
        for (int i = 0; i < segments(); i++) {
            // Uniform t isn't uniform in space (zero handles ease in/out up to 1.5x), so oversample.
            int n = Math.max(1, (int) Math.ceil(hullLength(i) * 1.5 / maxStep));
            for (int k = (i == 0 ? 0 : 1); k <= n; k++) {
                double t = (double) k / n;
                Vec3 p = point(i, t);
                if (!pts.isEmpty() && pts.get(pts.size() - 1).distanceToSqr(p) < 1e-10) continue;
                pts.add(p);
                segOf.add(new int[]{i});
                tOf.add(t);
            }
        }
        List<Sample> out = new ArrayList<>(pts.size());
        if (pts.size() < 2) {
            if (pts.size() == 1) {
                out.add(new Sample(0, 0, 0, pts.get(0), new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)));
            }
            return out;
        }
        // Tangents by central differences on the samples (robust where handles are zero).
        Vec3[] tan = new Vec3[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            Vec3 prev = pts.get(Math.max(0, i - 1)), next = pts.get(Math.min(pts.size() - 1, i + 1));
            tan[i] = next.subtract(prev).normalize();
        }
        // Initial normal: world-up projected off the tangent (falls back to +X for vertical lines).
        Vec3 t0 = tan[0];
        Vec3 ref = Math.abs(t0.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 n = ref.subtract(t0.scale(ref.dot(t0))).normalize();
        double s = 0;
        for (int i = 0; i < pts.size(); i++) {
            if (i > 0) {
                Vec3 x0 = pts.get(i - 1), x1 = pts.get(i);
                s += x0.distanceTo(x1);
                // Double reflection carries the normal from frame i-1 to frame i without twist.
                Vec3 v1 = x1.subtract(x0);
                double c1 = v1.dot(v1);
                Vec3 nL = n.subtract(v1.scale(2 / c1 * v1.dot(n)));
                Vec3 tL = tan[i - 1].subtract(v1.scale(2 / c1 * v1.dot(tan[i - 1])));
                Vec3 v2 = tan[i].subtract(tL);
                double c2 = v2.dot(v2);
                n = c2 < 1e-12 ? nL : nL.subtract(v2.scale(2 / c2 * v2.dot(nL)));
                n = n.subtract(tan[i].scale(n.dot(tan[i]))).normalize(); // keep it orthonormal
            }
            Vec3 b = tan[i].cross(n);
            out.add(new Sample(segOf.get(i)[0], tOf.get(i), s, pts.get(i), tan[i], n, b));
        }
        return out;
    }

    /** Closest approach between the view ray and the sampled polyline. */
    public record RayHit(int sampleIndex, double frac, double distance, double rayT) {}

    /**
     * The point of the sampled curve closest to the ray {@code origin + dir * r} (r ≥ 0), as the
     * polyline piece index + fraction along it, the gap between ray and curve, and the ray
     * distance. Null for an empty curve.
     */
    public static RayHit closestToRay(List<Sample> samples, Vec3 origin, Vec3 dir) {
        RayHit best = null;
        for (int i = 0; i + 1 < samples.size(); i++) {
            Vec3 a = samples.get(i).pos(), b = samples.get(i + 1).pos();
            double[] st = segmentRayClosest(a, b, origin, dir);
            Vec3 onSeg = a.add(b.subtract(a).scale(st[0]));
            Vec3 onRay = origin.add(dir.scale(st[1]));
            double d = onSeg.distanceTo(onRay);
            if (best == null || d < best.distance() - 1e-9) best = new RayHit(i, st[0], d, st[1]);
        }
        return best;
    }

    /** Parameters (u on segment ab in [0,1], r on the ray ≥ 0) of the closest pair of points. */
    static double[] segmentRayClosest(Vec3 a, Vec3 b, Vec3 o, Vec3 d) {
        Vec3 u = b.subtract(a), w = a.subtract(o);
        double uu = u.dot(u), ud = u.dot(d), dd = d.dot(d), uw = u.dot(w), dw = d.dot(w);
        double den = uu * dd - ud * ud;
        double s = den < 1e-12 ? 0 : (ud * dw - dd * uw) / den;
        s = clamp01(s);
        double r = (ud * s + dw) / dd;
        if (r < 0) {
            r = 0;
            s = uu < 1e-12 ? 0 : clamp01(-uw / uu);
        }
        return new double[]{s, r};
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /**
     * Insert a node at local parameter {@code t} of segment {@code seg} by de Casteljau
     * subdivision — the curve's shape is unchanged; the new node gets the split's handles and its
     * neighbours' facing handles shrink to match. Returns the new node's index.
     */
    public int insertNode(int seg, double t) {
        Node a = nodes.get(seg), b = nodes.get(seg + 1);
        Vec3 p0 = a.p, p1 = a.p.add(a.out), p2 = b.p.add(b.in), p3 = b.p;
        Vec3 p01 = lerp(p0, p1, t), p12 = lerp(p1, p2, t), p23 = lerp(p2, p3, t);
        Vec3 p012 = lerp(p01, p12, t), p123 = lerp(p12, p23, t);
        Vec3 mid = lerp(p012, p123, t);
        a.out = p01.subtract(p0);
        b.in = p23.subtract(p3);
        nodes.add(seg + 1, new Node(mid, p012.subtract(mid), p123.subtract(mid)));
        return seg + 1;
    }

    static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return new Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
    }

    /** Moves every node by {@code delta} (handles are relative, so they follow). */
    public void translate(Vec3 delta) {
        for (Node n : nodes) n.p = n.p.add(delta);
    }
}
