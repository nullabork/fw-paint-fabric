package co.fax.wang;

/**
 * How a block's gradient value is derived. The first two use the block's whole-texture average; the
 * "top %" modes analyse only the darkest or lightest fraction of the texture's pixels (the fraction
 * is the Pixel % slider). "Colour" variants sort the gradient by colour; the others by brightness.
 */
public enum GradientMode {
    COLOR("Color"),
    BRIGHTNESS("Brightness"),
    TOP_DARK_COLOR("Top % Dark Color"),
    TOP_DARK("Top % Dark"),
    TOP_LIGHT_COLOR("Top % Light Color"),
    TOP_LIGHT("Top % Light"),
    BW_DIFF("B&W Diff"),
    COLOR_DIFF("Color Diff"),
    PICK("Pick");

    private final String displayName;

    GradientMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** True when the gradient is ordered by a single scalar (brightness or a diff) not a colour axis. */
    public boolean usesBrightness() {
        return this == BRIGHTNESS || this == TOP_DARK || this == TOP_LIGHT || this == BW_DIFF || this == COLOR_DIFF;
    }

    /** True when a block's value is its texture's difference from the start block. */
    public boolean isStartRelative() {
        return this == BW_DIFF || this == COLOR_DIFF;
    }

    /** True when only a fraction of the texture's pixels is analysed (needs the Pixel % slider). */
    public boolean usesPixelPercent() {
        return this == TOP_DARK_COLOR || this == TOP_DARK || this == TOP_LIGHT_COLOR || this == TOP_LIGHT;
    }

    /** True for the manual "Pick" mode: the order comes from user-assigned numbers, not texture analysis. */
    public boolean isPick() {
        return this == PICK;
    }

    /** For a "top %" mode: whether to take the lightest pixels (else the darkest). */
    public boolean selectsLightest() {
        return this == TOP_LIGHT_COLOR || this == TOP_LIGHT;
    }

    public GradientMode next() {
        GradientMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
