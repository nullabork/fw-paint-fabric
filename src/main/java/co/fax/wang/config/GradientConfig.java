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

    // ---- tools (the mod is active only while holding one of these items) ------------------------

    /** Registry id of the gradient tool item. Empty = none. */
    public String gradientTool = "";
    /** Registry id of the noise tool item. Empty = none. */
    public String noiseTool = "";

    /** Per-tool activation mode (Marker / Place / Disabled). */
    public ToolMode gradientToolMode = ToolMode.DISABLED;
    public ToolMode noiseToolMode = ToolMode.DISABLED;

    // ---- markers --------------------------------------------------------------------------------

    /** Max distance (blocks) allowed between a start and end marker. */
    public int maxMarkerDistance = 64;
    /** When on, placing a start marker also drops an end marker along the look direction. */
    public boolean autoPlaceEnd = false;

    // ---- gradient settings ----------------------------------------------------------------------

    /** Whether the gradient is measured by colour, brightness, or a texture analysis. */
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

    /** Item ids the user has force-excluded from the palette (red). */
    public List<String> excludedBlocks = new ArrayList<>();
    /** Item ids the user has marked must-use (green): force-eligible past the deviation budget. */
    public List<String> requiredBlocks = new ArrayList<>();

    // ---- noise fill -----------------------------------------------------------------------------

    /** Seed for noise generation (persisted). Empty = 0. */
    public String noiseSeed = "";
    /** Noise function used to drive the fill. */
    public co.fax.wang.NoiseType noiseType = co.fax.wang.NoiseType.SMOOTH;
    /** Per-axis feature sizes (blocks) of the noise. */
    public double noiseScaleX = 12.0;
    public double noiseScaleY = 12.0;
    public double noiseScaleZ = 12.0;
    /** When on, the three axis scales stay locked together (one Scale slider). */
    public boolean noiseLock = true;
    /** Block-ordering mode for the noise tool (separate from the gradient tool's). */
    public GradientMode noiseGradientMode = GradientMode.COLOR;

    // ---- misc -----------------------------------------------------------------------------------

    /** When on, the mod logs debug detail (e.g. the preview block order) to the game log. */
    public boolean debug = false;
}
