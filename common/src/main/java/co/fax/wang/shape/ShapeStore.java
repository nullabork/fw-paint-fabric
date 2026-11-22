package co.fax.wang.shape;

import co.fax.wang.config.ConfigManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists the markers (lines and shapes) to {@code config/fw-paint-shapes.json}, keyed per
 * world + dimension so shapes from one save (or server/dimension) never bleed into another.
 * Failures fall back to no shapes rather than crashing.
 */
public final class ShapeStore {

    private static final Logger LOG = LoggerFactory.getLogger("gradient/shapes");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, WorldShapes>>() {}.getType();

    /** A ring as versions before the Shape tool saved it (read only: see {@link #fromRing}). */
    static final class StoredShape {
        String kind;
        int[] center;
        String axis;
        int[] ctrlA;
        int[] ctrlB;
        int rA;
        int rB;
        int height;
        double theta;
        boolean flip; // absent in older saves → false
        MarkerStyle style; // absent (older saves, default look) → the default
    }

    /** The box as versions before the Shape tool saved it (read only: see {@link #fromBox}). */
    static final class StoredBox {
        int[] cornerA;
        int[] cornerB;
        String axis;
        MarkerStyle style;
    }

    /**
     * Serialized line: nodes as [px,py,pz, inx,iny,inz, outx,outy,outz, radius, twist]. Saves
     * from before per-node sizes have 9-number nodes and only the end values r0/r1/tw0/tw1,
     * which are still written (first / last node) so an older build reads the ends back.
     */
    static final class StoredLine {
        double[][] nodes;
        String profile;
        boolean solid;
        String gradient;
        double r0, r1, tw0, tw1;
        MarkerStyle style;
        /** The rotate gimbal's two ring axes, [xx,xy,xz, yx,yy,yz]; absent → world X and Y. */
        double[] gimbal;
        /** Across-gradient "top" angle in radians (0 = the frame normal), and the gradient preview toggle. */
        double gradAngle;
        boolean gradPreview;
        /** A Shape-tool marker (see {@link LineShape#shape}) and its extras; absent = a plain line. */
        boolean shape, ball;
        /** 0 (absent) = the default: an even profile, a one-block wall. */
        double stretch, wall;
        /** Absent = capped. */
        Boolean capped;
        /** The drawn profile: where it is, the axis its plane faces, and its cells as a, b pairs. */
        int[] drawnAt;
        String drawnAxis;
        String drawnTop, drawnRight;
        /** Absent = off. */
        String dig;
        int[] drawn;
    }

    /** A stored line back to a shape (legacy end-only sizes spread over the nodes); null if < 2 nodes. */
    static LineShape decodeLine(StoredLine sl) {
        LineCurve c = new LineCurve();
        boolean perNode = true;
        for (double[] n : sl.nodes) {
            LineCurve.Node node = new LineCurve.Node(new Vec3(n[0], n[1], n[2]),
                    new Vec3(n[3], n[4], n[5]), new Vec3(n[6], n[7], n[8]));
            if (n.length >= 11) {
                node.r = n[9];
                node.tw = n[10];
            } else {
                perNode = false;
            }
            c.nodes.add(node);
        }
        if (c.nodes.size() < 2) return null;
        LineShape l = new LineShape(c);
        l.profile = LineProfile.valueOf(sl.profile);
        l.solid = sl.solid;
        for (LineGradient g : LineGradient.values()) {
            if (g.name().equals(sl.gradient)) l.gradient = g; // unknown/missing → default
        }
        if (sl.style != null) l.style = sl.style;
        if (Double.isFinite(sl.gradAngle)) l.gradAngle = sl.gradAngle;
        l.gradPreview = sl.gradPreview;
        l.shape = sl.shape && c.nodes.size() == 2;
        l.ball = sl.ball;
        if (sl.stretch > 0 && Double.isFinite(sl.stretch)) l.stretch = sl.stretch;
        if (sl.wall >= 1 && Double.isFinite(sl.wall)) l.wall = sl.wall;
        l.capped = sl.capped == null || sl.capped;
        try {
            l.dig = sl.dig == null ? LineShape.Dig.OFF : LineShape.Dig.valueOf(sl.dig);
        } catch (IllegalArgumentException e) {
            l.dig = LineShape.Dig.OFF;
        }
        l.drawn = ProfileDrawing.of(sl.drawnAt, sl.drawnAxis, sl.drawn, sl.drawnTop, sl.drawnRight);
        if (sl.gimbal != null && sl.gimbal.length >= 6) {
            Vec3 gx = new Vec3(sl.gimbal[0], sl.gimbal[1], sl.gimbal[2]);
            Vec3 gy = new Vec3(sl.gimbal[3], sl.gimbal[4], sl.gimbal[5]);
            // Only a sound pair is taken: two unit axes at right angles.
            if (Math.abs(gx.length() - 1) < 1e-3 && Math.abs(gy.length() - 1) < 1e-3 && Math.abs(gx.dot(gy)) < 1e-3) {
                l.gimbalX = gx;
                l.gimbalY = gy;
            }
        }
        if (!perNode) {
            // No sizes saved at all (never written by a released build) → defaults, not radius 0.
            boolean none = sl.r0 == 0 && sl.r1 == 0;
            l.applyLegacyEnds(none ? LineShape.DEFAULT_RADIUS : sl.r0, none ? LineShape.DEFAULT_RADIUS : sl.r1,
                    sl.tw0, sl.tw1);
        }
        return l;
    }

    static StoredLine encodeLine(LineShape l) {
        StoredLine sl = new StoredLine();
        List<LineCurve.Node> ns = l.curve.nodes;
        sl.nodes = new double[ns.size()][];
        for (int i = 0; i < sl.nodes.length; i++) {
            LineCurve.Node n = ns.get(i);
            sl.nodes[i] = new double[]{n.p.x, n.p.y, n.p.z, n.in.x, n.in.y, n.in.z,
                    n.out.x, n.out.y, n.out.z, n.r, n.tw};
        }
        sl.profile = l.profile.name();
        sl.solid = l.solid;
        sl.gradient = l.gradient.name();
        sl.style = stored(l.style);
        sl.gradAngle = l.gradAngle;
        sl.gradPreview = l.gradPreview;
        sl.shape = l.shape;
        sl.ball = l.ball;
        sl.stretch = l.stretch == 1 ? 0 : l.stretch;
        sl.wall = l.wall == 1 ? 0 : l.wall;
        sl.capped = l.capped ? null : Boolean.FALSE;
        sl.dig = l.dig == LineShape.Dig.OFF ? null : l.dig.name();
        if (l.drawn != null && !l.drawn.isEmpty()) {
            BlockPos o = l.drawn.origin;
            sl.drawnAt = new int[]{o.getX(), o.getY(), o.getZ()};
            sl.drawnAxis = l.drawn.normal.name();
            sl.drawnTop = l.drawn.top.name();
            sl.drawnRight = l.drawn.right.name();
            sl.drawn = l.drawn.flat();
        }
        Vec3 gx = l.gimbalX, gy = l.gimbalY;
        boolean turned = gx.distanceToSqr(new Vec3(1, 0, 0)) > 1e-12 || gy.distanceToSqr(new Vec3(0, 1, 0)) > 1e-12;
        sl.gimbal = turned ? new double[]{gx.x, gx.y, gx.z, gy.x, gy.y, gy.z} : null;
        sl.r0 = ns.get(0).r;
        sl.r1 = ns.get(ns.size() - 1).r;
        sl.tw0 = ns.get(0).tw;
        sl.tw1 = ns.get(ns.size() - 1).tw;
        return sl;
    }

    private static final class WorldShapes {
        List<StoredShape> shapes;
        StoredBox box;
        List<StoredLine> lines;
    }

    private static Map<String, WorldShapes> data;

    private ShapeStore() {}

    private static Path path() {
        return ConfigManager.configDir().resolve("fw-paint-shapes.json");
    }

    private static Map<String, WorldShapes> data() {
        if (data == null) {
            Map<String, WorldShapes> loaded = null;
            try {
                if (Files.exists(path())) {
                    loaded = GSON.fromJson(Files.readString(path()), MAP_TYPE);
                }
            } catch (Exception e) {
                LOG.warn("Failed to read {} — shapes reset. Cause: {}", path(), e.toString());
            }
            data = loaded != null ? loaded : new HashMap<>();
        }
        return data;
    }

    /**
     * A ring from before the Shape tool, as the shape it now is: a round or square tube along
     * the ring's axis, hollow with the ring's band as its wall and open at both ends (a ring
     * drawn out to its centre is solid).
     */
    static LineShape fromRing(StoredShape s) {
        Direction.Axis n = Direction.Axis.valueOf(s.axis);
        Direction.Axis ua = n == Direction.Axis.Y ? Direction.Axis.X : Direction.Axis.Y;
        Direction.Axis va = n == Direction.Axis.Z ? Direction.Axis.X : Direction.Axis.Z;
        BlockPos c = pos(s.center);
        int rMin = Math.min(s.rA, s.rB), rMax = Math.max(s.rA, s.rB), h = s.height == 0 ? 1 : s.height;
        BlockPos a = c.relative(ua, -rMax).relative(va, -rMax);
        BlockPos b = c.relative(ua, rMax).relative(va, rMax).relative(n, h > 0 ? h - 1 : h + 1);
        LineShape l = LineShape.prism(a, b, n, h < 0);
        boolean square = "SQUARE".equals(s.kind);
        l.profile = square ? LineProfile.SQUARE : LineProfile.CIRCLE;
        l.solid = rMin <= 0;
        l.wall = Math.max(1, rMax - rMin + 1);
        l.capped = false;
        if (square && Double.isFinite(s.theta)) l.setAllTwists(s.theta);
        if (s.flip) l.reverse();
        if (s.style != null) l.style = s.style;
        return l;
    }

    /** The one box from before the Shape tool, as a box shape whose line runs along the box's gradient axis. */
    static LineShape fromBox(StoredBox sb) {
        Direction.Axis axis = Direction.Axis.valueOf(sb.axis);
        BlockPos a = pos(sb.cornerA), b = pos(sb.cornerB);
        LineShape l = LineShape.prism(a, b, axis, a.get(axis) > b.get(axis));
        if (sb.style != null) l.style = sb.style;
        return l;
    }

    /**
     * Loads the lines and shapes saved for {@code key} (null key: none). Rings and the box
     * saved by versions before the Shape tool are turned into shapes here; the next save writes
     * them back as such.
     */
    public static void loadInto(String key, List<LineShape> lines) {
        lines.clear();
        if (key == null) return;
        WorldShapes stored = data().get(key);
        if (stored == null) return;
        if (stored.lines != null) {
            for (StoredLine sl : stored.lines) {
                try {
                    LineShape l = decodeLine(sl);
                    if (l != null) lines.add(l);
                } catch (Exception e) {
                    LOG.warn("Skipping malformed stored line in {}: {}", key, e.toString());
                }
            }
        }
        if (stored.shapes != null) {
            for (StoredShape s : stored.shapes) {
                try {
                    lines.add(fromRing(s));
                } catch (Exception e) {
                    LOG.warn("Skipping malformed stored ring in {}: {}", key, e.toString());
                }
            }
        }
        if (stored.box != null) {
            try {
                lines.add(fromBox(stored.box));
            } catch (Exception e) {
                LOG.warn("Skipping malformed stored box in {}: {}", key, e.toString());
            }
        }
    }

    /** Saves the lines and shapes for {@code key} (removing the entry entirely when there are none). */
    public static void store(String key, List<LineShape> lines) {
        if (key == null) return;
        if (lines.isEmpty()) {
            data().remove(key);
        } else {
            WorldShapes stored = new WorldShapes();
            stored.lines = new ArrayList<>(lines.size());
            for (LineShape l : lines) stored.lines.add(encodeLine(l));
            data().put(key, stored);
        }
        save();
    }

    private static void save() {
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(data()));
        } catch (IOException e) {
            LOG.warn("Failed to write {}: {}", path(), e.toString());
        }
    }

    /** A style as saved: left out entirely while it is the default look. */
    private static MarkerStyle stored(MarkerStyle st) {
        return st == null || st.isDefault() ? null : st;
    }

    private static BlockPos pos(int[] p) {
        return new BlockPos(p[0], p[1], p[2]);
    }

}
