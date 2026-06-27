package co.fax.wang;

/**
 * The activation state of the Gradient tool. Cycled (in declaration order, wrapping) by both the
 * "cycle mode" keybind and the button on {@link GradientScreen}, and shown on the in-game HUD.
 *
 * <p>Colours are ARGB and include the alpha byte ({@code 0xFF......}) — without it text renders
 * fully transparent (see the 26.2 GUI notes).
 */
public enum ToolMode {
    MARKER("Active: Marker", 0xFFFFAA00),   // orange
    PLACE("Active: Place", 0xFF55FF55),     // green
    DISABLED("Disabled", 0xFFAAAAAA);       // grey

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

    /** Next mode in the cycle, wrapping back to the first. */
    public ToolMode next() {
        ToolMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
