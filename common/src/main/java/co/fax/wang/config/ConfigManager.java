package co.fax.wang.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Loads/saves {@link GradientConfig} as JSON in the loader's config directory (injected by the
 * loader entrypoint via {@link #init}). Failures are logged (never silently swallowed) and fall
 * back to defaults so a corrupt file can't crash the client. Gson ships with Minecraft, so this
 * needs no extra dependency.
 */
public final class ConfigManager {

    private static final Logger LOG = LoggerFactory.getLogger("gradient/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Set by the loader entry before anything reads config; "config" is a same-shape fallback. */
    private static Path configDir = Paths.get("config");

    private static GradientConfig config;

    private ConfigManager() {}

    /** Point at the loader's config directory and load. Called once from each loader entrypoint. */
    public static void init(Path dir) {
        configDir = dir;
        load();
    }

    private static Path path() {
        return configDir.resolve("gradient.json");
    }

    /** The loader's config directory (for the mod's other files, e.g. the marker store). */
    public static Path configDir() {
        return configDir;
    }

    public static GradientConfig get() {
        if (config == null) {
            load();
        }
        return config;
    }

    public static void load() {
        GradientConfig loaded = null;
        try {
            if (Files.exists(path())) {
                String text = Files.readString(path());
                loaded = GSON.fromJson(text, GradientConfig.class);
                migrateLegacyTools(loaded, text);
            }
        } catch (Exception e) {
            LOG.warn("Failed to read {} — using defaults. Cause: {}", path(), e.toString());
        }
        config = (loaded != null) ? loaded : new GradientConfig();
    }

    /**
     * Configs written before the single-tool merge had separate {@code gradientTool}/{@code
     * noiseTool} items. Adopt the gradient one (or the noise one if only that was set) as the paint
     * tool so an upgrade doesn't silently unassign the user's tool.
     */
    private static void migrateLegacyTools(GradientConfig cfg, String text) {
        if (cfg == null || !cfg.paintTool.isEmpty()) return;
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            String legacy = optString(o, "gradientTool");
            if (legacy.isEmpty()) legacy = optString(o, "noiseTool");
            if (!legacy.isEmpty()) {
                cfg.paintTool = legacy;
                LOG.info("Migrated legacy tool '{}' to the single paint tool", legacy);
            }
        } catch (Exception e) {
            LOG.warn("Legacy tool migration skipped: {}", e.toString());
        }
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    public static void save() {
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(get()));
        } catch (IOException e) {
            LOG.warn("Failed to write {}: {}", path(), e.toString());
        }
    }
}
