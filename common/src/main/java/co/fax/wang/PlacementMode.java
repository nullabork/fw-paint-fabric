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
    MARKER_BOX("Marker box", "Active: Marker box", 0xFFFFD24D),             // gold — one-piece box region
    MARKER_DRAW("Marker draw", "Active: Marker draw", 0xFFFFA07A),          // salmon
    MARKER_CIRCLE("Marker circle", "Active: Marker circle", 0xFFFF8A3C),    // orange — donut ring region
    MARKER_SQUARE("Marker square", "Active: Marker square", 0xFF7BD88A),    // green — square ring region
    SINGLE("Single", "Active: Single", 0xFF55FF55),     // green
    // Persisted by enum name: FACE keeps its name so saved configs stay on it (label only changed).
    FACE("Face any", "Active: Face any", 0xFF55FFFF),   // aqua — every exposed face on the plane
    FACE_TARGET("Face target", "Active: Face target", 0xFF4DD9C0), // teal — only the clicked block's type
    FACE_PERP("Face perp", "Active: Face perp", 0xFF7FDBFF), // light blue — 1-wide snapped run
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

    /** Bare mode name (for buttons: "Placement: Face any"). */
    public String shortName() {
        return shortName;
    }

    /** HUD line ("Active: Face any"). */
    public String displayName() {
        return displayName;
    }

    /** ARGB colour for HUD/screen text. */
    public int color() {
        return color;
    }

    /** True for the modes that place paint (not markers, not disabled). */
    public boolean places() {
        return this == SINGLE || isFace() || this == FACE_PERP || this == FILL3D;
    }

    /** Face any / Face target: the whole connected surface extrudes as columns. */
    public boolean isFace() {
        return this == FACE || this == FACE_TARGET;
    }

    /** True for the marker-selection modes (drag lines, box, freehand, shape regions). */
    public boolean isMarker() {
        return this == MARKER || this == MARKER_BOX || this == MARKER_DRAW
                || this == MARKER_CIRCLE || this == MARKER_SQUARE;
    }

    /** The next mode, wrapping. */
    public PlacementMode next() {
        PlacementMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
