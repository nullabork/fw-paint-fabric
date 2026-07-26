package co.fax.wang;

/**
 * How blocks are compared/ordered in colour maths: by full colour, or by brightness alone. In v2
 * this is an internal metric selector (Automatic segments, variation bands, ramp ordering) — the
 * old per-tool mode toggle is gone; a palette's Order toggle covers the user-facing choice.
 */
public enum GradientMode {
    COLOR("Color"),
    BRIGHTNESS("Brightness");

    private final String displayName;

    GradientMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** True when ordering runs on a single brightness scalar instead of a colour axis. */
    public boolean usesBrightness() {
        return this == BRIGHTNESS;
    }
}
