package co.fax.wang.config;

import co.fax.wang.CurveFunction;
import co.fax.wang.GradientMode;
import co.fax.wang.GradientSource;
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

    /** Whether the gradient is measured by colour or by apparent brightness. */
    public GradientMode gradientMode = GradientMode.COLOR;

    /** Easing curve applied to gradient progress. */
    public CurveFunction curve = CurveFunction.LINEAR;

    /** Where the candidate-block palette is sourced from. */
    public GradientSource source = GradientSource.HOTBAR_AND_INVENTORY;

    /** Clean = always the logical next step; unclean = occasional repeat/skip for a noisier blend. */
    public boolean clean = true;
}
