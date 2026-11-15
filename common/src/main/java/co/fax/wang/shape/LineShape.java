package co.fax.wang.shape;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.List;

/**
 * One line marker: its {@link LineCurve}, the profile swept along it (radius and twist set per
 * node, eased between them), and the block cells that sweep selects — the region painting is
 * constrained to.
 *
 * <p>The cells and their draw geometry are built by {@link TubeBuild} from a snapshot of this
 * line — synchronously when painting needs them, on a worker thread while the line is being
 * edited (the previous result keeps drawing until the new one lands).
 */
public final class LineShape {

    /** Sample spacing along the curve for controls, picking and drawing the line (blocks). */
    static final double STEP = 0.25;

    public final LineCurve curve;
    public LineProfile profile = LineProfile.NONE;
    public boolean solid;
    /** Which way a Gradient fill runs through the tube. */
    public LineGradient gradient = LineGradient.ACROSS;
    /** How this line's tube is drawn (its toolbar's Rendering page). */
    public MarkerStyle style = new MarkerStyle();
    /**
     * The rotate gimbal's two ring axes (unit, at right angles): the rings are drawn round these
     * and dragging one turns the line about it. They turn with the line, so the gimbal stays
     * relative to the shape, and are saved with it.
     */
    public Vec3 gimbalX = new Vec3(1, 0, 0), gimbalY = new Vec3(0, 1, 0);
    /** A new tube's radius (and what right-clicking a size cube resets to). */
    public static final double DEFAULT_RADIUS = LineCurve.DEFAULT_RADIUS;
    /** Size-cube drags keep a node's radius within this. */
    public static final double MIN_RADIUS = 0.5, MAX_RADIUS = 64;

    /** Dev perf counters (read + reset by LineMarkers' FWPAINT_PERF log). */
    static long perfRebuilds, perfRebuildNanos, perfSamples, perfSampleNanos, perfForced;

    private List<LineCurve.Sample> samples;
    /** Sample index sitting on each main node (rebuilt with the samples). */
    private int[] nodeSamples = new int[0];
    private double length;
    private boolean samplesDirty = true;

    /** Edit counter: every markDirty bumps it; a build result is current when it matches. */
    private long version = 1, builtVersion;
    /** The last applied build: cells (→ fraction along the line) and merged draw geometry. */
    private TubeBuild.Result built = TubeBuild.Result.EMPTY;
    // Background build hand-off (written by the worker, read on the client thread).
    private volatile TubeBuild.Result finished;
    private volatile long finishedVersion;
    private volatile boolean inFlight;

    public LineShape(LineCurve curve) {
        this.curve = curve;
    }

    /** The curve or a parameter changed: samples and cells must be rebuilt. */
    public void markDirty() {
        samplesDirty = true;
        version++;
    }

    public List<LineCurve.Sample> samples() {
        if (samplesDirty) {
            long t0 = System.nanoTime();
            samples = curve.sample(STEP);
            perfSamples++;
            perfSampleNanos += System.nanoTime() - t0;
            length = samples.isEmpty() ? 0 : samples.get(samples.size() - 1).s();
            // Node i > 0 ends segment i-1: the last sample of a segment ≤ i-1 sits on it (a
            // dropped duplicate leaves the previous sample, at the same spot).
            nodeSamples = new int[curve.nodes.size()];
            for (int i = 1; i < nodeSamples.length; i++) {
                int k = nodeSamples[i - 1];
                while (k + 1 < samples.size() && samples.get(k + 1).seg() <= i - 1) k++;
                nodeSamples[i] = k;
            }
            samplesDirty = false;
        }
        return samples;
    }

    /** Index of the sample on main node {@code node}. */
    public int nodeSample(int node) {
        samples();
        return nodeSamples[Math.max(0, Math.min(nodeSamples.length - 1, node))];
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

    /** Tube radius at sample {@code i} (eased between the nodes either side). */
    public double radiusAt(int i) {
        LineCurve.Sample s = samples().get(i);
        return curve.radiusAt(s.seg(), s.t());
    }

    /** Profile twist (radians) at sample {@code i}. */
    public double twistAt(int i) {
        LineCurve.Sample s = samples().get(i);
        return curve.twistAt(s.seg(), s.t());
    }

    /** Every node to radius {@code r} (the modifier on a size drag). */
    public void setAllRadii(double r) {
        for (LineCurve.Node n : curve.nodes) n.r = r;
        markDirty();
    }

    /** Every node to twist {@code tw}. */
    public void setAllTwists(double tw) {
        for (LineCurve.Node n : curve.nodes) n.tw = tw;
        markDirty();
    }

    /** Reverse the line: start and end swap, the curve and every node's size stay put. */
    public void reverse() {
        java.util.Collections.reverse(curve.nodes);
        for (LineCurve.Node n : curve.nodes) {
            Vec3 in = n.in;
            n.in = n.out;
            n.out = in;
        }
        markDirty();
    }

    /**
     * Saves from before per-node sizes kept one radius and twist per END. Spread them over the
     * nodes: the ends get them exactly, middle nodes the old linear blend at their distance
     * along the line — so an old line keeps its start and end sizes.
     */
    public void applyLegacyEnds(double r0, double r1, double tw0, double tw1) {
        markDirty();
        List<LineCurve.Node> ns = curve.nodes;
        for (int i = 0; i < ns.size(); i++) {
            double f = i == 0 ? 0 : i == ns.size() - 1 ? 1 : frac(nodeSample(i));
            ns.get(i).r = r0 + (r1 - r0) * f;
            ns.get(i).tw = tw0 + (tw1 - tw0) * f;
        }
        markDirty();
    }

    /** Twist-rotated in-plane axes (n', b') of sample {@code i}. */
    public Vec3[] rotatedFrame(int i) {
        LineCurve.Sample s = samples().get(i);
        double th = twistAt(i), c = Math.cos(th), sn = Math.sin(th);
        Vec3 n = s.n().scale(c).add(s.b().scale(sn));
        Vec3 b = s.b().scale(c).subtract(s.n().scale(sn));
        return new Vec3[]{n, b};
    }

    // ---- cells ------------------------------------------------------------------------------

    /** One worker for every line's background tube builds (daemon: never blocks shutdown). */
    private static final java.util.concurrent.ExecutorService BUILDER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "FW Paint tube builder");
                t.setDaemon(true);
                return t;
            });

    private TubeBuild.Params snapshot() {
        return new TubeBuild.Params(curve.copy(), profile, solid);
    }

    private void apply(TubeBuild.Result r, long forVersion) {
        built = r;
        builtVersion = forVersion;
        perfRebuilds++;
        perfRebuildNanos += r.nanos();
    }

    /**
     * Bring the cells up to date. {@code sync} builds right here (painting, tests — they must see
     * the current shape). Otherwise (rendering) a stale shape is rebuilt on the worker thread
     * while the previous result keeps drawing, so dragging a node on a huge tube never stalls a
     * frame; the newest finished build is swapped in on a later call.
     */
    public void ensureCells(boolean sync) {
        TubeBuild.Result done = finished;
        if (done != null && finishedVersion > builtVersion) {
            finished = null;
            apply(done, finishedVersion);
        }
        if (builtVersion == version) return;
        if (sync) {
            apply(TubeBuild.build(snapshot()), version);
            return;
        }
        if (inFlight) return; // the running build will be followed by another if still stale
        inFlight = true;
        long v = version;
        TubeBuild.Params p = snapshot();
        BUILDER.execute(() -> {
            try {
                TubeBuild.Result r = TubeBuild.build(p);
                if (v > finishedVersion) {
                    finishedVersion = v;
                    finished = r;
                }
            } finally {
                inFlight = false;
            }
        });
    }

    /** True while the drawn tube lags the line (a rebuild is pending or running). */
    public boolean cellsDirty() {
        return builtVersion != version;
    }

    public boolean contains(BlockPos cell) {
        if (builtVersion != version) perfForced++;
        ensureCells(true);
        return built.cells().containsKey(cell.asLong());
    }

    /** {@link #contains} against the last finished build — never forces a rebuild (rendering). */
    public boolean containsBuilt(BlockPos cell) {
        return built.cells().containsKey(cell.asLong());
    }

    /** Fraction along the line (0 … 1) of a selected cell, or -1. */
    public double fracOf(BlockPos cell) {
        ensureCells(true);
        long k = cell.asLong();
        return built.cells().containsKey(k) ? built.cells().get(k) : -1;
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

    /**
     * Distance from {@code cell}'s centre to the line, as a fraction of the tube radius there
     * (0 on the line, about 1 at the skin; clamped to 0 … 1). A bare line counts as radius 1.
     */
    public double radialFrac(BlockPos cell) {
        List<LineCurve.Sample> ss = samples();
        Vec3 c = net.minecraft.world.phys.Vec3.atCenterOf(cell);
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < ss.size(); i++) {
            double d = ss.get(i).pos().distanceToSqr(c);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        double r = profile == LineProfile.NONE ? 1 : Math.max(0.5, radiusAt(best));
        return Math.min(1, Math.sqrt(bestD) / r);
    }

    /** Cells of the last finished build (may lag the line while it's being edited). */
    public int cellCount() {
        return built.cells().size();
    }

    public BlockPos renderOrigin() {
        return built.origin();
    }

    /** Merged face quads, xyz per vertex (4 per quad), relative to {@link #renderOrigin()}. */
    public float[] faceVerts() {
        return built.faces();
    }

    /** Merged edge-highlight quads, same layout as {@link #faceVerts()}. */
    public float[] edgeVerts() {
        return built.edges();
    }

    /** All selected cells, up to date (a copy-free view for flood fills). */
    public LongSet cellKeys() {
        ensureCells(true);
        return built.cells().keySet();
    }

    // ---- control positions ------------------------------------------------------------------

    /** Size cube of main node {@code node}: on the profile rim along n'. */
    public Vec3 sizeNode(int node) {
        int i = nodeSample(node);
        return samples().get(i).pos().add(rotatedFrame(i)[0].scale(curve.nodes.get(node).r + 0.5));
    }

    /** Twist cube of main node {@code node}: on the rim along b', a quarter turn from its size cube. */
    public Vec3 twistNode(int node) {
        int i = nodeSample(node);
        return samples().get(i).pos().add(rotatedFrame(i)[1].scale(curve.nodes.get(node).r + 0.5));
    }

    // The move / cog / rotate cubes ride together beside the line. They slide along it to stay
    // within easy reach of where you stand, and line up side by side across your view — both
    // from your POSITION only, never from where you look, so they hold still under the crosshair.

    /** The cluster keeps between these distances of the eye; outside them it slides to IDEAL. */
    static final double CTRL_NEAR = 2.5, CTRL_FAR = 5.5, CTRL_IDEAL = 4;
    /** A new spot must be at least this much nearer to CTRL_IDEAL than the current one to move there. */
    static final double CTRL_GAIN = 0.3;
    /** Centre-to-centre spacing of the three cubes. */
    static final double CTRL_SPACING = 0.8;
    /** Where the cluster is along the line and where it is heading, in sample-index units; -1 = unplaced. */
    private double ctrlAt = -1, ctrlGoal = -1;

    /** Beside sample {@code i}, clear of the tube. */
    private Vec3 besideSample(int i) {
        double r = profile == LineProfile.NONE ? 0 : radiusAt(i) + 0.5;
        return samples().get(i).pos().add(rotatedFrame(i)[0].scale(-(r + 1.5)));
    }

    private Vec3 besideAt(double at) {
        int last = samples().size() - 1;
        at = Math.max(0, Math.min(last, at));
        int i = (int) Math.floor(at), j = Math.min(last, i + 1);
        return i == j ? besideSample(i) : LineCurve.lerp(besideSample(i), besideSample(j), at - i);
    }

    /**
     * Choose where the cluster should sit for someone standing at {@code eye}: it stays where it
     * is while that is between CTRL_NEAR and CTRL_FAR away, else it heads for the spot along the
     * line that is CTRL_IDEAL away (or the nearest spot, when the whole line is further). Walking
     * up to it pushes it on ahead; walking off past it brings it round to your other side.
     * {@code hold} keeps the goal (a drag or an open toolbar is using the cluster).
     */
    public void aimControls(Vec3 eye, boolean hold) {
        int n = samples().size();
        if (n == 0) return;
        if (ctrlGoal >= 0 && ctrlGoal <= n - 1) {
            if (hold) return;
            double d = besideAt(ctrlGoal).distanceTo(eye);
            if (d >= CTRL_NEAR && d <= CTRL_FAR) return;
        }
        int cur = ctrlGoal < 0 ? n / 2 : (int) Math.round(Math.min(n - 1, ctrlGoal));
        boolean tooNear = ctrlGoal >= 0 && besideAt(cur).distanceTo(eye) < CTRL_NEAR;
        int nearest = 0;
        double nearestD = Double.MAX_VALUE;
        double[] dist = new double[n];
        for (int i = 0; i < n; i++) {
            dist[i] = besideSample(i).distanceTo(eye);
            if (dist[i] < nearestD) {
                nearestD = dist[i];
                nearest = i;
            }
        }
        // Too near: stay on the side it is on. Too far: prefer the other side of you (ahead).
        boolean wantAfter = tooNear == (cur >= nearest);
        int best = cur;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double score = Math.abs(dist[i] - CTRL_IDEAL) + ((i >= nearest) == wantAfter ? 0 : 0.75)
                    + Math.abs(i - cur) * 1e-4;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        // Only move for a real gain. When no spot along the line is in range (it is all too far,
        // or all too near) the side preference above would otherwise pick the other side of you
        // on every call, and the cluster would jiggle between two spots while you stand still.
        if (ctrlGoal >= 0 && Math.abs(dist[cur] - CTRL_IDEAL) <= Math.abs(dist[best] - CTRL_IDEAL) + CTRL_GAIN) return;
        ctrlGoal = best;
        if (ctrlAt < 0) ctrlAt = best;
    }

    /** Slide the cluster toward its goal ({@code dt} seconds since the last call). */
    public void slideControls(double dt) {
        if (ctrlGoal < 0) return;
        int last = samples().size() - 1;
        ctrlGoal = Math.min(last, ctrlGoal);
        if (ctrlAt < 0) ctrlAt = ctrlGoal;
        ctrlAt = Math.min(last, ctrlAt);
        double gap = ctrlGoal - ctrlAt;
        ctrlAt = Math.abs(gap) < 0.05 ? ctrlGoal : ctrlAt + gap * (1 - Math.exp(-Math.max(0, dt) * 10));
    }

    /**
     * The move, cog and rotate cubes for someone standing at {@code eye}: {move, cog, rotate},
     * side by side across their view (level with the horizon), so no one hides another.
     */
    public Vec3[] controls(Vec3 eye) {
        if (ctrlAt < 0) aimControls(eye, false);
        Vec3 c = besideAt(Math.max(0, ctrlAt));
        Vec3 f = c.subtract(eye);
        Vec3 right = new Vec3(-f.z, 0, f.x); // f × up
        right = right.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : right.normalize();
        return new Vec3[]{c.subtract(right.scale(CTRL_SPACING)), c, c.add(right.scale(CTRL_SPACING))};
    }

    // ---- rotation ---------------------------------------------------------------------------

    /** How far the line and its tube reach from {@link LineCurve#centre()} (the gimbal's radius). */
    public double reach() {
        Vec3 c = curve.centre();
        double best = 0;
        List<LineCurve.Sample> ss = samples();
        for (int i = 0; i < ss.size(); i++) {
            double r = profile == LineProfile.NONE ? 0 : radiusAt(i) + 0.5;
            best = Math.max(best, ss.get(i).pos().distanceTo(c) + r);
        }
        return best;
    }

    /** What a turn starts from: the curve, the first frame's normal, and the gimbal, as they were. */
    public record Pose(LineCurve curve, Vec3 n0, Vec3 gimbalX, Vec3 gimbalY) {}

    public Pose pose() {
        List<LineCurve.Sample> ss = samples();
        return new Pose(curve.copy(), ss.isEmpty() ? new Vec3(0, 1, 0) : ss.get(0).n(), gimbalX, gimbalY);
    }

    /**
     * Make this line {@code from} turned by {@code angle} about {@code axis} through
     * {@code centre} — the whole thing rigidly: curve, tube profile and gimbal. The curve's frames
     * start from world-up, not from the line, so a turned curve alone would leave a square or
     * star profile facing the old way; every node's twist takes up the difference.
     */
    public void turnFrom(Pose from, Vec3 centre, Vec3 axis, double angle) {
        curve.setTurned(from.curve(), centre, axis, angle);
        gimbalX = LineCurve.turn(from.gimbalX(), axis, angle);
        gimbalY = LineCurve.turn(from.gimbalY(), axis, angle);
        markDirty();
        List<LineCurve.Sample> ss = samples();
        if (!ss.isEmpty()) {
            Vec3 want = LineCurve.turn(from.n0(), axis, angle);
            double fix = Math.atan2(want.dot(ss.get(0).b()), want.dot(ss.get(0).n()));
            for (int i = 0; i < curve.nodes.size() && i < from.curve().nodes.size(); i++) {
                curve.nodes.get(i).tw = from.curve().nodes.get(i).tw + fix;
            }
        }
        markDirty();
    }

    /** World-space bounds of the line and its tube (for placing the toolbar): {min, max}. */
    public Vec3[] bounds() {
        double x0 = Double.MAX_VALUE, y0 = x0, z0 = x0, x1 = -x0, y1 = x1, z1 = x1;
        List<LineCurve.Sample> ss = samples();
        for (int i = 0; i < ss.size(); i++) {
            Vec3 p = ss.get(i).pos();
            double r = profile == LineProfile.NONE ? 0.2 : radiusAt(i) + 0.5;
            x0 = Math.min(x0, p.x - r);
            y0 = Math.min(y0, p.y - r);
            z0 = Math.min(z0, p.z - r);
            x1 = Math.max(x1, p.x + r);
            y1 = Math.max(y1, p.y + r);
            z1 = Math.max(z1, p.z + r);
        }
        return new Vec3[]{new Vec3(x0, y0, z0), new Vec3(x1, y1, z1)};
    }
}
