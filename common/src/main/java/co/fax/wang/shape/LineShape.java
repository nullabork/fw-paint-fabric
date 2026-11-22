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
    /**
     * Which way is "top" for an Across gradient, as an angle (radians) round the line from each
     * slice's frame normal: 0 = that normal (straight up on a level line). The frame is the
     * untwisted one, so twisting the profile doesn't swing the gradient.
     */
    public double gradAngle;
    /** Draw the tube in the colours a Gradient fill would give it, instead of its plain tint. */
    public boolean gradPreview;
    /**
     * A Shape-tool marker rather than a line: a straight run between two nodes (its bottom and
     * top faces) with one profile all the way, edited by its faces and sides instead of by
     * bending. Everything else (rotating, gradients, the look, gravity) is the line's.
     */
    public boolean shape;
    /** A shape that swells from a point at each end to the full profile half way: a sphere or egg. */
    public boolean ball;
    /** How deep the profile is (along the frame's b) for its width (along n): 1 = even, else an oval / slab. */
    public double stretch = 1;
    /** Wall thickness of a hollow tube, in blocks (1 = the classic one-block skin). */
    public double wall = 1;
    /** A hollow tube's ends are closed. Off leaves it an open pipe. */
    public boolean capped = true;
    /** The picture swept along the line when its profile is {@link LineProfile#CUSTOM}; null until one is drawn. */
    public ProfileDrawing drawn;
    /** A shape's width / depth may shrink to a single block. */
    public static final double SHAPE_MIN_RADIUS = 0;
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

    /**
     * A new Shape-tool box filling the cells from {@code a} to {@code b} (both included): a
     * square profile standing upright, bottom face to top face.
     */
    public static LineShape box(BlockPos a, BlockPos b) {
        return prism(a, b, Direction.Axis.Y, false);
    }

    /**
     * A box-shaped shape filling the cells from {@code a} to {@code b}, its line running along
     * {@code axis} from the low side to the high side ({@code down}: the other way). Its width is
     * across the frame's normal there (X for an upright one, else Y) and its depth the third axis.
     */
    public static LineShape prism(BlockPos a, BlockPos b, Direction.Axis axis, boolean down) {
        Direction.Axis ua = axis == Direction.Axis.Y ? Direction.Axis.X : Direction.Axis.Y;
        Direction.Axis va = axis == Direction.Axis.Z ? Direction.Axis.X : Direction.Axis.Z;
        double[] lo = new double[3], hi = new double[3];
        for (Direction.Axis ax : Direction.Axis.values()) {
            lo[ax.ordinal()] = Math.min(a.get(ax), b.get(ax));
            hi[ax.ordinal()] = Math.max(a.get(ax), b.get(ax)) + 1;
        }
        double[] p0 = new double[3], p1 = new double[3];
        for (int k = 0; k < 3; k++) p0[k] = p1[k] = (lo[k] + hi[k]) / 2;
        p0[axis.ordinal()] = down ? hi[axis.ordinal()] : lo[axis.ordinal()];
        p1[axis.ordinal()] = down ? lo[axis.ordinal()] : hi[axis.ordinal()];
        LineShape l = new LineShape(new LineCurve(new Vec3(p0[0], p0[1], p0[2]), new Vec3(p1[0], p1[1], p1[2])));
        double eu = (hi[ua.ordinal()] - lo[ua.ordinal()]) / 2, ev = (hi[va.ordinal()] - lo[va.ordinal()]) / 2;
        l.setAllRadii(eu - 0.5);
        l.stretch = ev / eu;
        l.shape = true;
        l.profile = LineProfile.SQUARE;
        l.solid = true;
        l.gradient = LineGradient.ALONG; // bottom to top
        return l;
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
        return new TubeBuild.Params(curve.copy(), profile, solid, stretch, wall, capped, ball, shape,
                drawn == null ? null : drawn.copy());
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

    /**
     * What the marker does to digging (breaking blocks by hand). INSIDE: while any of it is
     * within your reach, only blocks in the marker break. OUTSIDE: blocks in the marker never
     * break. See {@link ShapeMarkers#digBlocked}.
     */
    public enum Dig { OFF, INSIDE, OUTSIDE }

    public Dig dig = Dig.OFF;

    /** True when one of the marker's cells is within {@code reach} of {@code eye} (measured to the block, as reach is). */
    public boolean cellInReach(Vec3 eye, double reach) {
        ensureCells(true);
        var cells = built.cells();
        if (cells.isEmpty()) return false;
        int r = (int) Math.ceil(reach) + 1;
        int ex = (int) Math.floor(eye.x), ey = (int) Math.floor(eye.y), ez = (int) Math.floor(eye.z);
        for (int x = ex - r; x <= ex + r; x++) {
            double dx = Math.max(0, Math.max(x - eye.x, eye.x - (x + 1)));
            for (int y = ey - r; y <= ey + r; y++) {
                double dy = Math.max(0, Math.max(y - eye.y, eye.y - (y + 1)));
                for (int z = ez - r; z <= ez + r; z++) {
                    double dz = Math.max(0, Math.max(z - eye.z, eye.z - (z + 1)));
                    if (dx * dx + dy * dy + dz * dz <= reach * reach && cells.containsKey(BlockPos.asLong(x, y, z))) return true;
                }
            }
        }
        return false;
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
    public Segment segmentFor(BlockPos cell) {
        double f = fracOf(cell);
        if (f < 0) return null;
        List<LineCurve.Node> ns = curve.nodes;
        int len = Math.max(1, (int) Math.round(length()));
        int idx = (int) Math.max(0, Math.min(len, Math.round(f * len)));
        return new Segment(BlockPos.containing(ns.get(0).p),
                BlockPos.containing(ns.get(ns.size() - 1).p), idx, len);
    }

    /** A cell's place on a start to end run: {@code index} of {@code length} steps from {@code start}. */
    public record Segment(BlockPos start, BlockPos end, int index, int length) {}

    // ---- gradient direction -------------------------------------------------------------------

    /** Cells within this far along the line of a slice count towards that slice's bottom and top. */
    static final double SLICE_SPREAD = 1.0;

    /** The "top" direction of slice {@code i}: its frame normal turned by {@link #gradAngle}. */
    public Vec3 sliceTop(int i) {
        LineCurve.Sample s = samples().get(i);
        return s.n().scale(Math.cos(gradAngle)).add(s.b().scale(Math.sin(gradAngle)));
    }

    /** The sample nearest {@code p}, searching outward from {@code from} (downhill in distance). */
    private int nearestSample(Vec3 p, int from) {
        List<LineCurve.Sample> ss = samples();
        int i = Math.max(0, Math.min(ss.size() - 1, from));
        double d = ss.get(i).pos().distanceToSqr(p);
        while (true) {
            double dl = i > 0 ? ss.get(i - 1).pos().distanceToSqr(p) : Double.MAX_VALUE;
            double dr = i < ss.size() - 1 ? ss.get(i + 1).pos().distanceToSqr(p) : Double.MAX_VALUE;
            if (dl < d && dl <= dr) {
                d = dl;
                i--;
            } else if (dr < d) {
                d = dr;
                i++;
            } else {
                return i;
            }
        }
    }

    /**
     * Each cell's position (0 … 1) on an Across gradient: the tube is cut into slices at right
     * angles to the line, and in every slice the gradient runs from its bottom-most cell (0) to
     * its top-most (1) along {@link #sliceTop}. So it follows the line round every bend, a thin
     * stretch gets the whole gradient just like a thick one, and a shell is coloured the same as
     * a solid tube. Cells are {@code cells} (normally the whole tube).
     */
    public double[] sliceFracs(List<BlockPos> cells) {
        List<LineCurve.Sample> ss = samples();
        int n = ss.size();
        double[] out = new double[cells.size()];
        if (n == 0) return out;
        Vec3[] tops = new Vec3[n];
        for (int i = 0; i < n; i++) tops[i] = sliceTop(i);
        double total = Math.max(1e-9, ss.get(n - 1).s());
        int[] at = new int[out.length];
        double[] h = new double[out.length], lo = new double[n], hi = new double[n];
        java.util.Arrays.fill(lo, Double.MAX_VALUE);
        java.util.Arrays.fill(hi, -Double.MAX_VALUE);
        for (int c = 0; c < out.length; c++) {
            Vec3 p = Vec3.atCenterOf(cells.get(c));
            // Start from where the tube build put the cell along the line, then settle on the
            // nearest sample (a wobbly line can pass the same spot twice; this keeps to its own).
            float f = built.cells().containsKey(cells.get(c).asLong()) ? built.cells().get(cells.get(c).asLong()) : -1;
            int guess = f < 0 ? n / 2 : sampleAtArc(f * total);
            int i = nearestSample(p, guess);
            at[c] = i;
            h[c] = p.subtract(ss.get(i).pos()).dot(tops[i]);
            lo[i] = Math.min(lo[i], h[c]);
            hi[i] = Math.max(hi[i], h[c]);
        }
        // A slice's bottom and top come from every cell a little way either side of it, so they
        // change smoothly along the line instead of jumping from one sample to the next.
        double[] wlo = new double[n], whi = new double[n];
        for (int i = 0; i < n; i++) {
            double a = Double.MAX_VALUE, b = -Double.MAX_VALUE;
            for (int j = i; j >= 0 && ss.get(i).s() - ss.get(j).s() <= SLICE_SPREAD; j--) {
                a = Math.min(a, lo[j]);
                b = Math.max(b, hi[j]);
            }
            for (int j = i + 1; j < n && ss.get(j).s() - ss.get(i).s() <= SLICE_SPREAD; j++) {
                a = Math.min(a, lo[j]);
                b = Math.max(b, hi[j]);
            }
            wlo[i] = a;
            whi[i] = b;
        }
        for (int c = 0; c < out.length; c++) {
            double span = whi[at[c]] - wlo[at[c]];
            out[c] = span < 1e-9 ? 0 : Math.max(0, Math.min(1, (h[c] - wlo[at[c]]) / span));
        }
        return out;
    }

    /** The sample at arc length {@code s} along the line (the first one at or past it). */
    public int sampleAtArc(double s) {
        List<LineCurve.Sample> ss = samples();
        int a = 0, b = ss.size() - 1;
        while (a < b) {
            int m = (a + b) >>> 1;
            if (ss.get(m).s() < s) a = m + 1;
            else b = m;
        }
        return a;
    }

    /**
     * Each cell's position (0 … 1) along this line's gradient, by its {@link LineGradient} mode:
     * across each slice (bottom → top), along the curve, or radially (line → skin, skin → line).
     */
    public double[] gradientFracs(List<BlockPos> cells) {
        double[] ts = new double[cells.size()];
        switch (gradient) {
            case ACROSS -> ts = sliceFracs(cells);
            case ALONG -> {
                for (int i = 0; i < ts.length; i++) ts[i] = Math.max(0, fracOf(cells.get(i)));
            }
            case INSIDE_OUT, OUTSIDE_IN -> {
                boolean in = gradient == LineGradient.OUTSIDE_IN;
                for (int i = 0; i < ts.length; i++) {
                    double r = radialFrac(cells.get(i));
                    ts[i] = in ? 1 - r : r;
                }
            }
        }
        return ts;
    }

    /** The current build (changes identity whenever the cells do): a key for caches of them. */
    Object builtKey() {
        return built;
    }

    /** The cells of the last finished build, without forcing a rebuild (rendering). */
    LongSet builtCellKeys() {
        return built.cells().keySet();
    }

    /** Where the control cluster sits along the line, as a sample index. */
    public int controlSample() {
        int last = samples().size() - 1;
        return (int) Math.round(Math.max(0, Math.min(last, ctrlAt)));
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
        double r = profile == LineProfile.NONE ? 1 : Math.max(0.5, skinAt(best));
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

    // ---- the skin ----------------------------------------------------------------------------

    /** The profile's half-width (along n') at sample {@code i}: 0 for a bare line, less towards a ball's ends. */
    public double halfWidth(int i) {
        if (profile == LineProfile.NONE || (profile == LineProfile.CUSTOM && (drawn == null || drawn.isEmpty()))) return 0;
        double e = radiusAt(i) + 0.5;
        if (ball) {
            double f = frac(i);
            e *= Math.sqrt(Math.max(0, 1 - (2 * f - 1) * (2 * f - 1)));
        }
        return e;
    }

    /** The furthest the skin gets from the line at sample {@code i} (a square's corner counts as its side). */
    public double skinAt(int i) {
        return halfWidth(i) * Math.max(1, stretch);
    }

    /** How far the skin is from the line at sample {@code i}, going in direction {@code d} (unit, across the line). */
    public double rimAlong(int i, Vec3 d) {
        double e = halfWidth(i);
        if (e <= 0) return 0;
        Vec3[] f = rotatedFrame(i);
        double cu = Math.abs(d.dot(f[0])), cv = Math.abs(d.dot(f[1])), ev = e * stretch;
        return switch (profile) {
            case CIRCLE -> 1 / Math.sqrt(cu * cu / (e * e) + cv * cv / (ev * ev));
            case SQUARE -> Math.min(cu < 1e-9 ? Double.MAX_VALUE : e / cu, cv < 1e-9 ? Double.MAX_VALUE : ev / cv);
            default -> Math.max(e, ev);
        };
    }

    /** The sample half way along (where a shape's side handles sit). */
    public int midSample() {
        return sampleAtArc(length() / 2);
    }

    /** A shape's height: the distance between its two faces. */
    public double height() {
        List<LineCurve.Node> ns = curve.nodes;
        return ns.get(0).p.distanceTo(ns.get(ns.size() - 1).p);
    }

    /** A shape's width handle: on its side half way up, along n'. */
    public Vec3 widthNode() {
        int i = midSample();
        return samples().get(i).pos().add(rotatedFrame(i)[0].scale(halfWidth(i)));
    }

    /** A shape's depth handle: on its side half way up, along b'. */
    public Vec3 depthNode() {
        int i = midSample();
        return samples().get(i).pos().add(rotatedFrame(i)[1].scale(halfWidth(i) * stretch));
    }

    /** A hollow shape's wall handle: on the inside of its wall, opposite the width handle. */
    public Vec3 wallNode() {
        int i = midSample();
        return samples().get(i).pos().add(rotatedFrame(i)[0].scale(-Math.max(0, halfWidth(i) - wall)));
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

    // The move / cog / rotate cubes ride together just off the marker's skin, at the spot nearest
    // where you stand, and line up side by side across your view. All of it comes from your
    // POSITION only, never from where you look, so they hold still under the crosshair.

    /** The cluster keeps at least NEAR from the eye; inside a tube it floats IDEAL in from you. */
    static final double CTRL_NEAR = 2.5, CTRL_IDEAL = 4;
    /** A new spot must be at least this much better than the current one to move there. */
    static final double CTRL_GAIN = 0.1;
    /** Centre-to-centre spacing of the three cubes. */
    static final double CTRL_SPACING = 0.8;
    /** Where the cluster is along the line and where it is heading, in sample-index units; -1 = unplaced. */
    private double ctrlAt = -1, ctrlGoal = -1;
    /** The cluster floats this far off the skin. */
    static final double CTRL_OFF = 1.5;
    /** A shape's cluster keeps this far from its two faces (so it is not down on the ground). */
    static final double CTRL_END = 1.0;
    /** Where you stood when a drag or open toolbar took hold of the cluster: it stays on that side. */
    private Vec3 heldEye;

    /** Is {@code eye} inside the tube? (Beyond an end, or with no profile, it is not.) */
    private boolean eyeInside(Vec3 eye) {
        List<LineCurve.Sample> ss = samples();
        if (ss.isEmpty()) return false;
        int k = nearestSample(eye, (int) Math.round(Math.max(0, ctrlAt)));
        LineCurve.Sample s = ss.get(k);
        Vec3 e = eye.subtract(s.pos());
        double along = e.dot(s.tan()), pad = shape ? 0 : 0.5;
        if ((k == 0 && along < -pad) || (k == ss.size() - 1 && along > pad)) return false;
        Vec3 d = e.subtract(s.tan().scale(along));
        double away = d.length();
        return away < 1e-3 ? halfWidth(k) > 0 : away < rimAlong(k, d.scale(1 / away));
    }

    /**
     * Beside sample {@code i}: CTRL_OFF off the skin on the side {@code eye} is on, so the cluster
     * comes round the tube with you and is never left underneath it or under the ground. With
     * the eye {@code inside} the tube (a big box you are working in), it floats CTRL_IDEAL in
     * from you towards the middle instead, and never out through the far wall.
     */
    private Vec3 besideSample(int i, Vec3 eye, boolean inside) {
        LineCurve.Sample s = samples().get(i);
        Vec3 d = eye.subtract(s.pos());
        d = d.subtract(s.tan().scale(d.dot(s.tan())));
        double away = d.length();
        d = away < 1e-3 ? rotatedFrame(i)[0].scale(-1) : d.scale(1 / away);
        double rim = rimAlong(i, d);
        double off;
        if (inside && away < rim) {
            double along = eye.subtract(s.pos()).dot(s.tan());
            double gap = Math.sqrt(Math.max(0, CTRL_IDEAL * CTRL_IDEAL - along * along));
            double far = Math.max(0, rimAlong(i, d.scale(-1)) - CTRL_OFF); // how far past the line it may go
            off = Math.max(-far, Math.min(Math.max(0, rim - CTRL_OFF), away - gap));
        } else if (away < rim) {
            off = rim + CTRL_OFF;
        } else {
            // Standing right against the skin: it comes in close to the skin rather than end up behind you.
            off = Math.max(rim + 0.3, Math.min(rim + CTRL_OFF, away - 1));
        }
        return s.pos().add(d.scale(off));
    }

    private Vec3 besideAt(double at, Vec3 eye, boolean inside) {
        int last = samples().size() - 1;
        at = Math.max(0, Math.min(last, at));
        int i = (int) Math.floor(at), j = Math.min(last, i + 1);
        return i == j ? besideSample(i, eye, inside)
                : LineCurve.lerp(besideSample(i, eye, inside), besideSample(j, eye, inside), at - i);
    }

    /** The samples the cluster may sit beside: all of a line; a shape's, short of its two faces. */
    private int[] ctrlRange() {
        int n = samples().size();
        if (!shape) return new int[]{0, n - 1};
        double len = length(), m = Math.min(CTRL_END, len / 2);
        int lo = sampleAtArc(m), hi = sampleAtArc(len - m);
        return lo <= hi ? new int[]{lo, hi} : new int[]{midSample(), midSample()};
    }

    /**
     * Choose where the cluster should sit for someone standing at {@code eye}: beside the spot of
     * the marker nearest them, but no nearer than CTRL_NEAR (standing right by it, the cluster
     * sits a little way along instead of in your face). It follows as you walk.
     * {@code hold} keeps the goal (a drag or an open toolbar is using the cluster).
     */
    public void aimControls(Vec3 eye, boolean hold) {
        int n = samples().size();
        if (n == 0) return;
        if (!hold) heldEye = null;
        else if (heldEye == null) heldEye = eye;
        if (hold && ctrlGoal >= 0 && ctrlGoal <= n - 1) return;
        boolean inside = eyeInside(eye);
        int[] range = ctrlRange();
        int lo = range[0], hi = range[1];
        int cur = ctrlGoal < 0 ? -1 : (int) Math.round(Math.max(lo, Math.min(hi, ctrlGoal)));
        int nearest = lo;
        double[] dist = new double[n];
        for (int i = lo; i <= hi; i++) {
            dist[i] = besideSample(i, eye, inside).distanceTo(eye);
            if (dist[i] < dist[nearest]) nearest = i;
        }
        // The nearest spot is too near: one a little way along on either side will do, so stay
        // on the side the cluster is already on.
        boolean sided = cur >= 0 && dist[nearest] < CTRL_NEAR;
        int best = nearest;
        double bestScore = Double.MAX_VALUE, curScore = Double.MAX_VALUE;
        for (int i = lo; i <= hi; i++) {
            double score = (dist[i] >= CTRL_NEAR ? dist[i] : 1000 - dist[i])
                    + (sided && (i >= nearest) != (cur >= nearest) ? 0.75 : 0);
            if (i == cur) curScore = score;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        // Only move for a real gain, so it does not jiggle between two equally good spots.
        if (cur >= 0 && ctrlGoal >= lo && ctrlGoal <= hi && curScore <= bestScore + CTRL_GAIN) return;
        ctrlGoal = best;
        if (ctrlAt < 0) ctrlAt = best;
    }

    /** The line was moved by {@code by} while the cluster is held: the cluster goes with it. */
    public void shiftHeld(Vec3 by) {
        if (heldEye != null) heldEye = heldEye.add(by);
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
     * Looking at a shape end on (standing on top of a box, or over a flat disc): the cluster
     * sits on that face, CTRL_OFF off it, a little towards its middle from you. Null when
     * {@code eye} is not over one of the shape's two faces.
     */
    private Vec3 faceSpot(Vec3 eye) {
        if (!shape || ball) return null;
        List<LineCurve.Node> ns = curve.nodes;
        Vec3 a = ns.get(0).p, b = ns.get(ns.size() - 1).p;
        double len = a.distanceTo(b);
        if (len < 1e-6) return null;
        Vec3 t = b.subtract(a).scale(1 / len);
        double along = eye.subtract(a).dot(t);
        boolean top = along > len;
        if (!top && along >= 0) return null;
        Vec3 face = top ? b : a, out = top ? t : t.scale(-1);
        int idx = top ? samples().size() - 1 : 0;
        Vec3 e = eye.subtract(face);
        double h = e.dot(out);
        Vec3 lat = e.subtract(out.scale(h));
        double away = lat.length();
        Vec3 dir = away < 1e-3 ? rotatedFrame(idx)[0] : lat.scale(1 / away);
        if (away >= rimAlong(idx, dir)) return null;
        double up = Math.min(CTRL_OFF, Math.max(0.3, h - 1));
        double shift = Math.sqrt(Math.max(0, CTRL_NEAR * CTRL_NEAR - (h - up) * (h - up)));
        return face.add(lat).add(dir.scale(-shift)).add(out.scale(up));
    }

    /**
     * The move, cog and rotate cubes for someone standing at {@code eye}: {move, cog, rotate},
     * side by side across their view (level with the horizon), so no one hides another.
     */
    public Vec3[] controls(Vec3 eye) {
        if (ctrlAt < 0) aimControls(eye, false);
        if (heldEye != null) eye = heldEye; // held: it stays where it was when taken hold of
        Vec3 c = faceSpot(eye);
        if (c == null) c = besideAt(Math.max(0, ctrlAt), eye, eyeInside(eye));
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
            double r = skinAt(i) * (profile == LineProfile.SQUARE ? Math.sqrt(2) : 1);
            best = Math.max(best, ss.get(i).pos().distanceTo(c) + r);
        }
        return best;
    }

    /** What a turn starts from: the curve, the first frame's normal, and the gimbal, as they were. */
    public record Pose(LineCurve curve, Vec3 n0, Vec3 gimbalX, Vec3 gimbalY, double gradAngle) {}

    public Pose pose() {
        List<LineCurve.Sample> ss = samples();
        return new Pose(curve.copy(), ss.isEmpty() ? new Vec3(0, 1, 0) : ss.get(0).n(), gimbalX, gimbalY, gradAngle);
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
            gradAngle = Math.IEEEremainder(from.gradAngle() + fix, 2 * Math.PI); // the gradient turns with it too
        }
        markDirty();
    }

    /**
     * Tilt one gimbal ring without touching the line: ring 0 (about {@link #gimbalY}) or ring 1
     * (about {@link #gimbalX}) leans by {@code angle} about the OTHER ring's axis, from where
     * {@code from} had it. The two axes stay at right angles, so with tilts and turns the rings can
     * be set to turn the line about any direction.
     */
    public void tiltGimbalFrom(Pose from, int ring, double angle) {
        if (ring == 0) gimbalY = LineCurve.turn(from.gimbalY(), from.gimbalX(), angle);
        else gimbalX = LineCurve.turn(from.gimbalX(), from.gimbalY(), angle);
    }

    /** World-space bounds of the line and its tube (for placing the toolbar): {min, max}. */
    public Vec3[] bounds() {
        double x0 = Double.MAX_VALUE, y0 = x0, z0 = x0, x1 = -x0, y1 = x1, z1 = x1;
        List<LineCurve.Sample> ss = samples();
        for (int i = 0; i < ss.size(); i++) {
            Vec3 p = ss.get(i).pos();
            double r = profile == LineProfile.NONE ? 0.2 : skinAt(i);
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
