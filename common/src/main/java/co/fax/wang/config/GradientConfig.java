package co.fax.wang.config;

import co.fax.wang.GradientSource;
import co.fax.wang.PaintType;
import co.fax.wang.PlacementMode;
import co.fax.wang.SolidMatch;

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

    /**
     * Where blocks go (Marker / Single / Face / 3D Fill / Disabled) — one global mode shared by
     * all paint types, cycled with the mode keybind.
     */
    public PlacementMode placementMode = PlacementMode.DISABLED;

    /**
     * Colour maths for ordering/matching across all tools: true = perceptual (Oklab — sorts and
     * matches like the eye sees), false = classic luma + raw RGB (pre-1.3 behaviour). Mirrored to
     * {@code GradientRamp.perceptual} by ConfigManager and the Settings toggle.
     */
    public boolean perceptualColor = true;

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
    /**
     * When on, each placed start marker scans out from the clicked face and drops an end marker on
     * the first non-air block (or at max marker distance if it's all air). A dragged line of starts
     * uses the face the drag began on, producing a matching line of ends.
     */
    public boolean autoPlaceEnd = false;

    // ---- placement behaviour ----------------------------------------------------------------------

    /**
     * Face mode inside markers: when on, the fill advances only the most-behind columns each
     * layer until every column's front is level, then all stack together (fill voids first).
     * Off = every column advances at once. Outside markers the face flood always starts from the
     * clicked plane, so this only matters in-marker.
     */
    public boolean faceFillVoids = true;

    /**
     * "Paint memory": idle seconds before free-hand paint progress is forgotten — gradient
     * columns, 3D fills, AND pattern placements all share this timer (the session caches drop
     * and the next click starts fresh). Also cleared when the active palette/pattern changes.
     */
    public int gradientCacheSeconds = 600;

    // ---- shared block source --------------------------------------------------------------------

    /**
     * Where the Solid tool's candidate blocks come from. (Gradient/noise painting reads the
     * per-palette source instead — see {@code Palette.source}.)
     */
    public GradientSource source = GradientSource.HOTBAR_AND_INVENTORY;

    // ---- solid paint ------------------------------------------------------------------------------

    /** How the Solid paint chooses the block it places. */
    public SolidMatch solidMatch = SolidMatch.SELECTED;
    /** Item id of the ✓ block placed in "Selected block" match mode. Empty = none. */
    public String solidBlock = "";
    /** Item ids excluded from Solid placement (never placed, even on exact match). */
    public List<String> solidExcludedBlocks = new ArrayList<>();

    // ---- perpendicular snapping -----------------------------------------------------------------

    /**
     * Angular snapping (degrees, 45 or 90) applied wherever a direction is derived from the
     * player's facing: the pattern plane's width axis and the Face Perpendicular run.
     */
    public int perpSnapDegrees = 45;

    // ---- palettes -------------------------------------------------------------------------------

    /**
     * What happens when a palette's explicitly-defined blocks aren't all available at paint time:
     * refuse to place (default) or skip the unavailable segments. Global Settings-tab toggle.
     */
    public co.fax.wang.palette.MissingBlockPolicy missingBlockPolicy =
            co.fax.wang.palette.MissingBlockPolicy.DONT_PAINT;

    // ---- misc -----------------------------------------------------------------------------------

    /** When on, the mod logs debug detail (e.g. the preview block order) to the game log. */
    public boolean debug = false;
}
