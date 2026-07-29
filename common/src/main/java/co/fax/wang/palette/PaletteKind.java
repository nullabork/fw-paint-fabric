package co.fax.wang.palette;

/** What kind of palette item this is: a gradient ramp, or a hand-drawn 2D pattern grid. */
public enum PaletteKind {
    GRADIENT("Gradient"),
    PATTERN("Pattern");

    private final String label;

    PaletteKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
