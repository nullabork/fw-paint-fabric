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
 * Persists shape markers (rings + the box) to {@code config/fw-paint-shapes.json}, keyed per
 * world + dimension so shapes from one save (or server/dimension) never bleed into another.
 * Failures fall back to no shapes rather than crashing. (Same pattern as MarkerStore.)
 */
public final class ShapeStore {

    private static final Logger LOG = LoggerFactory.getLogger("gradient/shapes");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, WorldShapes>>() {}.getType();

    /** Serialized shape: positions as [x, y, z], kind/axis as names. */
    private static final class StoredShape {
        String kind;
        int[] center;
        String axis;
        int[] ctrlA;
        int[] ctrlB;
        int rA;
        int rB;
        int height;
        double theta;
    }

    private static final class StoredBox {
        int[] cornerA;
        int[] cornerB;
        String axis;
    }

    /** Serialized line: nodes as [px,py,pz, inx,iny,inz, outx,outy,outz]. */
    private static final class StoredLine {
        double[][] nodes;
        String profile;
        boolean solid;
        String gradient;
        double r0, r1, tw0, tw1;
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

    /** Loads the shapes, lines + box saved for {@code key} (null key → empty/none). */
    public static BoxRegion loadInto(String key, List<RingShape> shapes, List<LineShape> lines) {
        shapes.clear();
        lines.clear();
        if (key == null) return null;
        WorldShapes stored = data().get(key);
        if (stored == null) return null;
        if (stored.lines != null) {
            for (StoredLine sl : stored.lines) {
                try {
                    LineCurve c = new LineCurve();
                    for (double[] n : sl.nodes) {
                        c.nodes.add(new LineCurve.Node(new Vec3(n[0], n[1], n[2]),
                                new Vec3(n[3], n[4], n[5]), new Vec3(n[6], n[7], n[8])));
                    }
                    if (c.nodes.size() < 2) continue;
                    LineShape l = new LineShape(c);
                    l.profile = LineProfile.valueOf(sl.profile);
                    l.solid = sl.solid;
                    for (LineGradient g : LineGradient.values()) {
                        if (g.name().equals(sl.gradient)) l.gradient = g; // unknown/missing → default
                    }
                    l.r0 = sl.r0;
                    l.r1 = sl.r1;
                    l.tw0 = sl.tw0;
                    l.tw1 = sl.tw1;
                    lines.add(l);
                } catch (Exception e) {
                    LOG.warn("Skipping malformed stored line in {}: {}", key, e.toString());
                }
            }
        }
        if (stored.shapes != null) {
            for (StoredShape s : stored.shapes) {
                try {
                    shapes.add(RingShape.restore(
                            RingShape.Kind.valueOf(s.kind),
                            pos(s.center), Direction.Axis.valueOf(s.axis),
                            pos(s.ctrlA), pos(s.ctrlB), s.rA, s.rB, s.height, s.theta));
                } catch (Exception e) {
                    LOG.warn("Skipping malformed stored shape in {}: {}", key, e.toString());
                }
            }
        }
        if (stored.box != null) {
            try {
                return new BoxRegion(pos(stored.box.cornerA), pos(stored.box.cornerB),
                        Direction.Axis.valueOf(stored.box.axis));
            } catch (Exception e) {
                LOG.warn("Skipping malformed stored box in {}: {}", key, e.toString());
            }
        }
        return null;
    }

    /** Saves the shapes, lines + box for {@code key} (removing the entry entirely when empty). */
    public static void store(String key, List<RingShape> shapes, BoxRegion box, List<LineShape> lines) {
        if (key == null) return;
        if (shapes.isEmpty() && box == null && lines.isEmpty()) {
            data().remove(key);
        } else {
            WorldShapes stored = new WorldShapes();
            stored.shapes = new ArrayList<>(shapes.size());
            for (RingShape shape : shapes) {
                StoredShape s = new StoredShape();
                s.kind = shape.kind.name();
                s.center = arr(shape.center);
                s.axis = shape.normal.name();
                s.ctrlA = arr(shape.ctrlA);
                s.ctrlB = arr(shape.ctrlB);
                s.rA = shape.rA;
                s.rB = shape.rB;
                s.height = shape.height;
                s.theta = shape.theta;
                stored.shapes.add(s);
            }
            stored.lines = new ArrayList<>(lines.size());
            for (LineShape l : lines) {
                StoredLine sl = new StoredLine();
                sl.nodes = new double[l.curve.nodes.size()][];
                for (int i = 0; i < sl.nodes.length; i++) {
                    LineCurve.Node n = l.curve.nodes.get(i);
                    sl.nodes[i] = new double[]{n.p.x, n.p.y, n.p.z, n.in.x, n.in.y, n.in.z, n.out.x, n.out.y, n.out.z};
                }
                sl.profile = l.profile.name();
                sl.solid = l.solid;
                sl.gradient = l.gradient.name();
                sl.r0 = l.r0;
                sl.r1 = l.r1;
                sl.tw0 = l.tw0;
                sl.tw1 = l.tw1;
                stored.lines.add(sl);
            }
            if (box != null) {
                StoredBox b = new StoredBox();
                b.cornerA = arr(box.cornerA);
                b.cornerB = arr(box.cornerB);
                b.axis = box.axis.name();
                stored.box = b;
            }
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

    private static BlockPos pos(int[] p) {
        return new BlockPos(p[0], p[1], p[2]);
    }

    private static int[] arr(BlockPos p) {
        return new int[]{p.getX(), p.getY(), p.getZ()};
    }
}
