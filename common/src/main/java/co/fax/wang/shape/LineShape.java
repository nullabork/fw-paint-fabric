package co.fax.wang.shape;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.List;

/**
 * One line marker: its {@link LineCurve}, the profile swept along it (with per-end radius and
 * twist), and the block cells that sweep selects — the region painting is constrained to.
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
    /** Profile radius at the start / end (interpolated along the line). */
    public double r0 = 2, r1 = 2;
    /** Profile twist (radians) at the start / end (interpolated along the line). */
    public double tw0, tw1;

    /** Dev perf counters (read + reset by LineMarkers' FWPAINT_PERF log). */
    static long perfRebuilds, perfRebuildNanos, perfSamples, perfSampleNanos, perfForced;

    private List<LineCurve.Sample> samples;
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

    /** One worker for every line's background tube builds (daemon: never blocks shutdown). */
    private static final java.util.concurrent.ExecutorService BUILDER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "FW Paint tube builder");
                t.setDaemon(true);
                return t;
            });

    private TubeBuild.Params snapshot() {
        return new TubeBuild.Params(curve.copy(), profile, solid, r0, r1, tw0, tw1);
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

    /** Where a floating button row is centred: above the start (or end), clear of the tube. */
    public Vec3 buttonAnchor(boolean end) {
        double r = profile == LineProfile.NONE ? 0 : (end ? r1 : r0) + 0.5;
        return curve.nodes.get(end ? curve.nodes.size() - 1 : 0).p.add(0, r + 2.2, 0);
    }
}
