package co.fax.wang.config;

import co.fax.wang.CurveFunction;
import co.fax.wang.GradientMode;
import co.fax.wang.GradientSource;
import co.fax.wang.ToolMode;

import java.util.ArrayList;
import java.util.List;

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

    /** Dithering amount 0..1: 0 = clean (always the next step), higher = more repeat/skip noise. */
    public double chaos = 0.0;

    /** How far a block may stray from the gradient and still be used, 0..1 (fraction of full range). */
    public double deviationBudget = 0.43;

    /** Maximum number of distinct gradient steps (1..16). A cap, not a target. */
    public int maxSteps = 16;

    /** Fraction of texture pixels (0..1) analysed by the "top %" gradient modes. */
    public double pixelPercent = 0.5;

    /** Item ids the user has excluded from the palette — never used for gradients or placement. */
    public List<String> excludedBlocks = new ArrayList<>();

    /** When on, the mod logs debug detail (e.g. the preview block order) to the game log. */
    public boolean debug = false;
}
