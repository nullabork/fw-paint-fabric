package co.fax.wang.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads/saves {@link GradientConfig} as JSON in the Fabric config directory. Failures are logged
 * (never silently swallowed) and fall back to defaults so a corrupt file can't crash the client.
 * Gson ships with Minecraft, so this needs no extra dependency.
 */
public final class ConfigManager {

    private static final Logger LOG = LoggerFactory.getLogger("gradient/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("gradient.json");

    private static GradientConfig config;

    private ConfigManager() {}

    public static GradientConfig get() {
        if (config == null) {
            load();
        }
        return config;
    }

    public static void load() {
        GradientConfig loaded = null;
        try {
            if (Files.exists(PATH)) {
                loaded = GSON.fromJson(Files.readString(PATH), GradientConfig.class);
            }
        } catch (Exception e) {
            LOG.warn("Failed to read {} — using defaults. Cause: {}", PATH, e.toString());
        }
        config = (loaded != null) ? loaded : new GradientConfig();
    }

    public static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(get()));
        } catch (IOException e) {
            LOG.warn("Failed to write {}: {}", PATH, e.toString());
        }
    }
}
