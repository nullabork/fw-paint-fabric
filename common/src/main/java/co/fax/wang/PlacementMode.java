package co.fax.wang;

/**
 * Where blocks go — the placement axis, independent of {@link PaintType} (what blocks get
 * chosen). One global mode, picked on the Place tab or the wheel's Place ring (both laid out
 * from {@link #groups()}), and shown on the in-game HUD. Every paint type works with every
 * placement mode, inside or outside markers.
 *
 * <p>Colours are ARGB and include the alpha byte ({@code 0xFF......}) — without it text renders
 * fully transparent (see the 26.2 GUI notes).
 */
public enum PlacementMode {
    MARKER("Marker", "Active: Marker", 0xFFFFAA00),                          // orange
    MARKER_BOX("Box", "Active: Box", 0xFFFFD24D),             // gold — one-piece box region
    MARKER_DRAW("Marker draw", "Active: Marker draw", 0xFFFFA07A),          // salmon
    MARKER_CIRCLE("Circle", "Active: Circle", 0xFFFF8A3C),    // orange — donut ring region
    MARKER_SQUARE("Square", "Active: Square", 0xFF7BD88A),    // green — square ring region
    MARKER_LINE("Line", "Active: Line", 0xFFFFE066),          // yellow — curve / swept tube
    SINGLE("Single", "Active: Single", 0xFF55FF55),     // green
    // Persisted by enum name: FACE keeps its name so saved configs stay on it (label only changed).
    FACE("Face any", "Active: Face any", 0xFF55FFFF),   // aqua — every exposed face on the plane
    FACE_TARGET("Face target", "Active: Face target", 0xFF4DD9C0), // teal — only the clicked block's type
    FACE_PERP("Face perp", "Active: Face perp", 0xFF7FDBFF), // light blue — 1-wide snapped run
    FILL3D("3D Fill", "Active: 3D Fill", 0xFFFF55FF),   // magenta
    DISABLED("Off", "Off", 0xFFAAAAAA);       // grey

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
                || this == MARKER_CIRCLE || this == MARKER_SQUARE || this == MARKER_LINE;
    }

    /** A titled group of modes: the Place tab's sections and the wheel's Place ring share these. */
    public record Group(String title, String description, java.util.List<PlacementMode> modes) {}

    private static final java.util.List<Group> GROUPS = java.util.List.of(
            new Group("Blocks", "Click a block, paint from it.",
                    java.util.List.of(SINGLE, FACE, FACE_TARGET, FACE_PERP, FILL3D)),
            new Group("Markers", "Start and end blocks that aim and bound painting.",
                    java.util.List.of(MARKER, MARKER_DRAW, DISABLED)),
            new Group("Shapes", "Boxes, rings and lines that shape what gets painted.",
                    java.util.List.of(MARKER_BOX, MARKER_CIRCLE, MARKER_SQUARE, MARKER_LINE)));

    /** Every mode exactly once, in the order the Place tab and the wheel show them. */
    public static java.util.List<Group> groups() {
        return GROUPS;
    }

    /** The next mode, wrapping. */
    public PlacementMode next() {
        PlacementMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
