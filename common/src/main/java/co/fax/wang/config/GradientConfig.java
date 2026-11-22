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
     * Where blocks go (Single / Face / 3D Fill / markers / shapes / Off — persisted as DISABLED) —
     * one global mode shared by all paint types, picked on the Place tab or the wheel.
     */
    public PlacementMode placementMode = PlacementMode.DISABLED;

    /**
     * Colour maths for ordering/matching across all tools: true = perceptual (Oklab — sorts and
     * matches like the eye sees), false = classic luma + raw RGB (pre-1.3 behaviour). Mirrored to
     * {@code GradientRamp.perceptual} by ConfigManager and the Settings toggle.
     */
    public boolean perceptualColor = true;

    // ---- HUD --------------------------------------------------------------------------------------

    /** HUD position (px from the top-left), movable via Settings › HUD › "Move HUD…". */
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

    /** "Marker reach": max distance (blocks) allowed between a start and end marker. */
    public int maxMarkerDistance = 64;

    // ---- placement behaviour ----------------------------------------------------------------------

    /**
     * Face mode inside markers: when on, the fill advances only the most-behind columns each
     * layer until every column's front is level, then all stack together (fill voids first).
     * Off = every column advances at once. Outside markers the face flood always starts from the
     * clicked plane, so this only matters in-marker.
     */
    public boolean faceFillVoids = true;

    /**
     * Place in air: a cell with no solid neighbour to click against is placed by "clicking" the
     * air cell itself (the server places into a replaceable clicked cell, so no support block is
     * needed). Off = such cells wait until a neighbour lands, as before. Some anti-cheat plugins
     * flag supportless placement, hence the toggle.
     */
    public boolean placeInAir = true;

    /**
     * Only place inside markers: a click that would start painting outside every marker while
     * right against one (the outer skin of a box, ring or tube, or beside a start→end run) places
     * nothing. Clicks away from markers paint as normal. Off by default.
     */
    public boolean onlyInsideMarkers = false;

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

    // ---- wheel ----------------------------------------------------------------------------------

    /** The wheel's Recent bar: the last picks, newest first (at most 20). */
    public List<WheelPress> wheelHistory = new ArrayList<>();

    // ---- palettes -------------------------------------------------------------------------------

    /**
     * What happens when a palette's explicitly-defined blocks aren't all available at paint time:
     * refuse to place (default) or skip the unavailable segments. Global Settings-tab toggle.
     */
    public co.fax.wang.palette.MissingBlockPolicy missingBlockPolicy =
            co.fax.wang.palette.MissingBlockPolicy.DONT_PAINT;
}
