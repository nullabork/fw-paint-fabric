package co.fax.wang.config;

import co.fax.wang.CurveFunction;
import co.fax.wang.GradientMode;
import co.fax.wang.GradientSource;
import co.fax.wang.PaintType;
import co.fax.wang.SolidMatch;
import co.fax.wang.ToolMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted settings, saved as JSON in the Fabric config dir by {@link ConfigManager}.
 * Gson fills missing fields from these defaults, so adding a field stays backwards-compatible.
 */
public class GradientConfig {

    // ---- tool (the mod is active only while holding this item) ----------------------------------

    /** Registry id of the paint tool item. Empty = none. */
    public String paintTool = "";

    /** What the tool currently paints — switched in-game with the paint-type keybind. */
    public PaintType activePaintType = PaintType.GRADIENT;

    /** Per-paint-type activation mode (Marker / Place / Extrude / Flood 3D / Disabled). */
    public ToolMode gradientToolMode = ToolMode.DISABLED;
    public ToolMode noiseToolMode = ToolMode.DISABLED;
    public ToolMode solidToolMode = ToolMode.DISABLED;

    // ---- HUD helper text --------------------------------------------------------------------------

    /** Helper-text position (px from the top-left), movable via the "Move helper text" screen. */
    public int hudX = 4;
    public int hudY = 4;

    /** Index into {@link #HUD_INCREMENTS} controlling how far the HUD move buttons nudge. */
    public int hudIncrementIndex = 0;

    /** Px steps the helper-text placement screen cycles through (transient: not serialized). */
    public static final transient int[] HUD_INCREMENTS = {50, 20, 10, 1};

    public int hudIncrement() {
        return HUD_INCREMENTS[Math.floorMod(hudIncrementIndex, HUD_INCREMENTS.length)];
    }

    public void cycleHudIncrement() {
        hudIncrementIndex = Math.floorMod(hudIncrementIndex + 1, HUD_INCREMENTS.length);
    }

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

    /**
     * Place-time gradient endpoints: true = the real blocks sitting at the markers in the world
     * ("Markers"); false = exactly the block list's [S]/[E] from the settings screen ("Block list"),
     * so what's placed matches the preview regardless of what the markers are made of.
     */
    public boolean gradientFromMarkers = true;

    /** Dithering amount 0..1: 0 = clean (always the next step), higher = more repeat/skip noise. */
    public double chaos = 0.0;

    /**
     * Chance 0..1 that each step's length is randomly wobbled: a boundary between two steps shifts,
     * so one step runs longer and its neighbour correspondingly shorter. 1 = every step wobbles.
     */
    public double stepWobble = 0.0;

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

    /** The picker's [S]/[E] ordering endpoints (block ids) — define valley→peak for noise placement. */
    public String orderStartBlock = "";
    public String orderEndBlock = "";

    /** Manual "Pick" order: block id → assigned number (1+). Lowest number = start, highest = end. */
    public java.util.Map<String, Integer> pickNumbers = new java.util.HashMap<>();

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
    /** Noise-tool band variation: similar blocks swap within a step (like the gradient's slider). */
    public double noiseDeviation = 0.43;
    public double noiseChaos = 0.0;
    public int noiseMaxSteps = 16;
    public double noisePixelPercent = 0.5;

    // ---- solid paint ------------------------------------------------------------------------------

    /** How the Solid paint chooses the block it places. */
    public SolidMatch solidMatch = SolidMatch.SELECTED;
    /** Item id of the ✓ block placed in "Selected block" match mode. Empty = none. */
    public String solidBlock = "";
    /** Item ids excluded from Solid placement (never placed, even on exact match). */
    public List<String> solidExcludedBlocks = new ArrayList<>();

    // ---- misc -----------------------------------------------------------------------------------

    /** When on, the mod logs debug detail (e.g. the preview block order) to the game log. */
    public boolean debug = false;
}
