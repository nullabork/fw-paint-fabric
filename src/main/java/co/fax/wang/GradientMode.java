package co.fax.wang;

/**
 * How the gradient between the start and end block is measured: by colour, or by perceived
 * (apparent) brightness only. Cycled by a button on {@link GradientScreen}.
 */
public enum GradientMode {
    COLOR("Color"),
    BRIGHTNESS("Brightness"); // apparent / perceived brightness

    private final String displayName;

    GradientMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public GradientMode next() {
        GradientMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
