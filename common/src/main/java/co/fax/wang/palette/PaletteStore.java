package co.fax.wang.palette;

import co.fax.wang.config.ConfigManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Loads/saves all palettes plus the active-palette pointer as JSON
 * ({@code fw-paint-palettes.json} in the loader's config dir — separate from
 * {@code gradient.json} so global settings and palette content don't churn each other).
 * Same failure posture as {@link ConfigManager}: log and fall back to an empty store.
 */
public final class PaletteStore {

    private static final Logger LOG = LoggerFactory.getLogger("gradient/palettes");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** On-disk shape of the palettes file. */
    private static final class Data {
        int version = 2;
        String activePalette = "";
        List<Palette> palettes = new ArrayList<>();
    }

    private static Data data;

    private PaletteStore() {}

    private static Path path() {
        return ConfigManager.configDir().resolve("fw-paint-palettes.json");
    }

    private static Data get() {
        if (data == null) {
            load();
        }
        return data;
    }

    public static void load() {
        Data loaded = null;
        try {
            if (Files.exists(path())) {
                loaded = GSON.fromJson(Files.readString(path()), Data.class);
            }
        } catch (Exception e) {
            LOG.warn("Failed to read {} — starting empty. Cause: {}", path(), e.toString());
        }
        data = (loaded != null) ? loaded : new Data();
        if (data.palettes == null) data.palettes = new ArrayList<>();
        data.palettes.removeIf(p -> p == null || p.id == null || p.id.isEmpty());
    }

    public static void save() {
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(get()));
        } catch (IOException e) {
            LOG.warn("Failed to write {}: {}", path(), e.toString());
        }
    }

    /** The live palette list, in stored (cycle) order. */
    public static List<Palette> all() {
        return get().palettes;
    }

    public static Palette byId(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Palette p : get().palettes) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    public static String activeId() {
        return get().activePalette;
    }

    /** The active palette, or null when none is set / it was deleted. */
    public static Palette active() {
        return byId(get().activePalette);
    }

    public static void setActive(String id) {
        get().activePalette = id == null ? "" : id;
        save();
    }

    /** Insert or replace (by id), then persist. New palettes go to the end of the list. */
    public static void upsert(Palette palette) {
        List<Palette> list = get().palettes;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(palette.id)) {
                list.set(i, palette);
                save();
                return;
            }
        }
        list.add(palette);
        save();
    }

    /**
     * Delete by id. If it was active, the next palette in the list becomes active (previous
     * when the last was deleted; none when the list empties).
     */
    public static void delete(String id) {
        List<Palette> list = get().palettes;
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return;
        list.remove(idx);
        if (get().activePalette.equals(id)) {
            get().activePalette = list.isEmpty() ? "" : list.get(Math.min(idx, list.size() - 1)).id;
        }
        save();
    }

    /**
     * Step the active palette forward/backward through the list (the cycle keybind). No-op when
     * empty; starts at the first palette when none is active. Returns the new active palette.
     */
    public static Palette cycleActive(int dir) {
        List<Palette> list = get().palettes;
        if (list.isEmpty()) return null;
        int idx = 0;
        Palette current = active();
        if (current != null) {
            idx = Math.floorMod(list.indexOf(current) + dir, list.size());
        }
        get().activePalette = list.get(idx).id;
        save();
        return list.get(idx);
    }

    // ---- pure naming helpers (unit-tested) ------------------------------------------------------

    /**
     * Resolve a display name: empty input becomes "Untitled"; a taken name gains the lowest free
     * " (n)" suffix (n from 2). A name already unique is returned as-is.
     */
    public static String uniqueName(String desired, Collection<String> takenNames) {
        String base = (desired == null || desired.isBlank()) ? "Untitled" : desired.strip();
        if (!containsIgnoreCase(takenNames, base)) return base;
        for (int n = 2; ; n++) {
            String candidate = base + " (" + n + ")";
            if (!containsIgnoreCase(takenNames, candidate)) return candidate;
        }
    }

    /** Case-insensitive name collision check (names are unique ignoring case). */
    public static boolean nameTaken(String name, Collection<String> takenNames) {
        return containsIgnoreCase(takenNames, name == null ? "" : name.strip());
    }

    /**
     * Derive a stable id slug from a display name: lowercase, runs of non-alphanumerics become
     * single dashes; collisions and empty results gain "-n" suffixes. Ids never change on rename.
     */
    public static String slugFor(String name, Collection<String> takenIds) {
        String slug = (name == null ? "" : name).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (slug.isEmpty()) slug = "palette";
        if (!takenIds.contains(slug)) return slug;
        for (int n = 2; ; n++) {
            String candidate = slug + "-" + n;
            if (!takenIds.contains(candidate)) return candidate;
        }
    }

    private static boolean containsIgnoreCase(Collection<String> haystack, String needle) {
        for (String s : haystack) {
            if (s != null && s.equalsIgnoreCase(needle)) return true;
        }
        return false;
    }
}
