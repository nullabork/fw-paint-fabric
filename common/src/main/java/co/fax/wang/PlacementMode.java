package co.fax.wang;

/**
 * Where blocks go — the placement axis, independent of {@link PaintType} (what blocks get
 * chosen). One global mode, cycled (wrapping) by the "cycle mode" keybind and the Settings
 * screen's Placement button, and shown on the in-game HUD. Every paint type works with every
 * placement mode, inside or outside markers.
 *
 * <p>Colours are ARGB and include the alpha byte ({@code 0xFF......}) — without it text renders
 * fully transparent (see the 26.2 GUI notes).
 */
public enum PlacementMode {
    MARKER("Marker", "Active: Marker", 0xFFFFAA00),                          // orange
    MARKER_CORNERS("Marker corners", "Active: Marker corners", 0xFFFFD24D), // gold
    MARKER_DRAW("Marker draw", "Active: Marker draw", 0xFFFFA07A),          // salmon
    SINGLE("Single", "Active: Single", 0xFF55FF55),     // green
    FACE("Face", "Active: Face", 0xFF55FFFF),           // aqua
    FILL3D("3D Fill", "Active: 3D Fill", 0xFFFF55FF),   // magenta
    DISABLED("Disabled", "Disabled", 0xFFAAAAAA);       // grey

    private final String shortName;
    private final String displayName;
    private final int color;

    PlacementMode(String shortName, String displayName, int color) {
        this.shortName = shortName;
        this.displayName = displayName;
        this.color = color;
    }

    /** Bare mode name (for buttons: "Placement: Face"). */
    public String shortName() {
        return shortName;
    }

    /** HUD line ("Active: Face"). */
    public String displayName() {
        return displayName;
    }

    /** ARGB colour for HUD/screen text. */
    public int color() {
        return color;
    }

    /** True for the modes that place paint (not markers, not disabled). */
    public boolean places() {
        return this == SINGLE || this == FACE || this == FILL3D;
    }

    /** True for the marker-selection modes (drag lines, corner volumes, freehand). */
    public boolean isMarker() {
        return this == MARKER || this == MARKER_CORNERS || this == MARKER_DRAW;
    }

    /** The next mode, wrapping. */
    public PlacementMode next() {
        PlacementMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
