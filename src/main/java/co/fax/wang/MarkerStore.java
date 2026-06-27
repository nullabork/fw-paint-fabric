package co.fax.wang;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Persists markers to {@code config/gradient_markers.json}, keyed per world + dimension so markers
 * from one save (or server/dimension) never bleed into another. Failures fall back to no markers
 * rather than crashing.
 */
public final class MarkerStore {

    private MarkerStore() {}

    private static final Logger LOG = LoggerFactory.getLogger("gradient/markers");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("gradient_markers.json");
    private static final Type MAP_TYPE = new TypeToken<Map<String, WorldMarkers>>() {}.getType();

    /** Serialized per-world record: each position is a {@code [x, y, z]} array. */
    private static final class WorldMarkers {
        int[][] starts = new int[0][];
        int[][] ends = new int[0][];
    }

    private static Map<String, WorldMarkers> data;

    private static Map<String, WorldMarkers> data() {
        if (data == null) {
            Map<String, WorldMarkers> loaded = null;
            try {
                if (Files.exists(PATH)) {
                    loaded = GSON.fromJson(Files.readString(PATH), MAP_TYPE);
                }
            } catch (Exception e) {
                LOG.warn("Failed to read {} — markers reset. Cause: {}", PATH, e.toString());
            }
            data = (loaded != null) ? loaded : new HashMap<>();
        }
        return data;
    }

    /** Replace the contents of {@code starts}/{@code ends} with the markers saved for {@code key}. */
    public static void loadInto(String key, Set<BlockPos> starts, Set<BlockPos> ends) {
        starts.clear();
        ends.clear();
        if (key == null) return;
        WorldMarkers wm = data().get(key);
        if (wm == null) return;
        addAll(wm.starts, starts);
        addAll(wm.ends, ends);
    }

    /** Save the markers for {@code key} (removing the entry entirely when both sets are empty). */
    public static void store(String key, Set<BlockPos> starts, Set<BlockPos> ends) {
        if (key == null) return;
        if (starts.isEmpty() && ends.isEmpty()) {
            data().remove(key);
        } else {
            WorldMarkers wm = new WorldMarkers();
            wm.starts = toArray(starts);
            wm.ends = toArray(ends);
            data().put(key, wm);
        }
        save();
    }

    private static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(data()));
        } catch (IOException e) {
            LOG.warn("Failed to write {}: {}", PATH, e.toString());
        }
    }

    private static void addAll(int[][] from, Set<BlockPos> into) {
        if (from == null) return;
        for (int[] p : from) {
            if (p != null && p.length == 3) into.add(new BlockPos(p[0], p[1], p[2]));
        }
    }

    private static int[][] toArray(Set<BlockPos> set) {
        int[][] a = new int[set.size()][];
        int i = 0;
        for (BlockPos p : set) a[i++] = new int[]{p.getX(), p.getY(), p.getZ()};
        return a;
    }
}
