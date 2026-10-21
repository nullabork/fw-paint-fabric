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

    /** Move node: beside the middle of the line, clear of the tube. */
    public Vec3 moveNode() {
        List<LineCurve.Sample> ss = samples();
        int i = ss.size() / 2;
        double r = profile == LineProfile.NONE ? 0 : radiusAt(i) + 0.5;
        return ss.get(i).pos().add(rotatedFrame(i)[0].scale(-(r + 1.5)));
    }

    /** Cog (options) cube: just past the move node along the line, so the two sit together. */
    public Vec3 cogNode() {
        List<LineCurve.Sample> ss = samples();
        return moveNode().add(ss.get(ss.size() / 2).tan().scale(0.8));
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
