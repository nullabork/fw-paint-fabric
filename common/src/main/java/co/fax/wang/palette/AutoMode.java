package co.fax.wang.palette;

/**
 * How an automatic segment matches blocks at placement time: by perceptual colour distance
 * (Oklab) or by lightness alone.
 */
public enum AutoMode {
    COLOR("Automatic colour"),
    BRIGHTNESS("Automatic brightness");

    private final String label;

    AutoMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
