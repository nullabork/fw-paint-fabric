package co.fax.wang;

/**
 * The activation state of the paint tool. Cycled (wrapping) by the "cycle mode" keybind and the
 * buttons on {@link GradientScreen}, and shown on the in-game HUD. Gradient/Noise paint cycle
 * Marker → Place → Disabled; Solid paint cycles Marker → Extrude → 3D Fill → Disabled.
 *
 * <p>Colours are ARGB and include the alpha byte ({@code 0xFF......}) — without it text renders
 * fully transparent (see the 26.2 GUI notes).
 */
public enum ToolMode {
    MARKER("Active: Marker", 0xFFFFAA00),     // orange
    PLACE("Active: Place", 0xFF55FF55),       // green
    EXTRUDE("Active: Extrude", 0xFF55FFFF),   // aqua
    FLOOD3D("Active: 3D Fill", 0xFFFF55FF),   // magenta
    DISABLED("Disabled", 0xFFAAAAAA);         // grey

    private static final ToolMode[] PLACE_CYCLE = {MARKER, PLACE, DISABLED};
    private static final ToolMode[] SOLID_CYCLE = {MARKER, EXTRUDE, FLOOD3D, DISABLED};

    private final String displayName;
    private final int color;

    ToolMode(String displayName, int color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String displayName() {
        return displayName;
    }

    /** ARGB colour for HUD/screen text. */
    public int color() {
        return color;
    }

    /** Next mode in the given paint type's cycle, wrapping; a mode outside the cycle resets to its first. */
    public ToolMode nextFor(PaintType type) {
        ToolMode[] cycle = (type == PaintType.SOLID) ? SOLID_CYCLE : PLACE_CYCLE;
        for (int i = 0; i < cycle.length; i++) {
            if (cycle[i] == this) return cycle[(i + 1) % cycle.length];
        }
        return cycle[0];
    }
}
