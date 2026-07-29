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
        String activePalette = "";  // the active GRADIENT item
        String activePattern = ""; // the active PATTERN item (kinds keep separate selections)
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

    /** The live palette list (both kinds), in stored (cycle) order. */
    public static List<Palette> all() {
        return get().palettes;
    }

    /** The items of one kind, in stored (cycle) order. */
    public static List<Palette> allOf(PaletteKind kind) {
        List<Palette> out = new ArrayList<>();
        for (Palette p : get().palettes) {
            if (p.kind == kind) out.add(p);
        }
        return out;
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

    /** The active GRADIENT palette, or null when none is set / it was deleted. */
    public static Palette active() {
        Palette p = byId(get().activePalette);
        return p != null && p.kind == PaletteKind.GRADIENT ? p : null;
    }

    /** The active PATTERN, or null when none is set / it was deleted. */
    public static Palette activePattern() {
        Palette p = byId(get().activePattern);
        return p != null && p.kind == PaletteKind.PATTERN ? p : null;
    }

    /** The active item of a kind (see {@link #active()} / {@link #activePattern()}). */
    public static Palette activeOf(PaletteKind kind) {
        return kind == PaletteKind.PATTERN ? activePattern() : active();
    }

    /** Make {@code id} its kind's active item (each kind keeps its own selection). */
    public static void setActive(String id) {
        Palette p = byId(id);
        if (p == null) return;
        if (p.kind == PaletteKind.PATTERN) get().activePattern = p.id;
        else get().activePalette = p.id;
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
     * Delete by id. If it was its kind's active, the next item of that kind becomes active
     * (none when that kind empties).
     */
    public static void delete(String id) {
        List<Palette> list = get().palettes;
        Palette victim = byId(id);
        if (victim == null) return;
        List<Palette> sameKind = allOf(victim.kind);
        int kindIdx = sameKind.indexOf(victim);
        list.remove(victim);
        boolean wasActive = victim.kind == PaletteKind.PATTERN
                ? get().activePattern.equals(id) : get().activePalette.equals(id);
        if (wasActive) {
            sameKind.remove(victim);
            String next = sameKind.isEmpty() ? ""
                    : sameKind.get(Math.min(kindIdx, sameKind.size() - 1)).id;
            if (victim.kind == PaletteKind.PATTERN) get().activePattern = next;
            else get().activePalette = next;
        }
        save();
    }

    /**
     * Step a kind's active item forward/backward through that kind's list (the cycle keybind —
     * filtered by the current paint type). No-op when the kind has no items; starts at its
     * first item when none is active. Returns the new active item.
     */
    public static Palette cycleActive(int dir, PaletteKind kind) {
        List<Palette> list = allOf(kind);
        if (list.isEmpty()) return null;
        int idx = 0;
        Palette current = activeOf(kind);
        if (current != null) {
            idx = Math.floorMod(list.indexOf(current) + dir, list.size());
        }
        Palette next = list.get(idx);
        if (kind == PaletteKind.PATTERN) get().activePattern = next.id;
        else get().activePalette = next.id;
        save();
        return next;
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
