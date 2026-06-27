package co.fax.wang.config;

import co.fax.wang.ToolMode;

/**
 * Persisted settings, saved as JSON in the Fabric config dir by {@link ConfigManager}.
 * Gson fills missing fields from these defaults, so adding a field stays backwards-compatible.
 */
public class GradientConfig {
    /** Registry id of the chosen tool item, e.g. {@code "minecraft:diamond_pickaxe"}. Empty = none. */
    public String selectedTool = "";

    /** Current activation mode. */
    public ToolMode mode = ToolMode.DISABLED;
}
