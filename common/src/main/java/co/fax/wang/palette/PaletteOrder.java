package co.fax.wang.palette;

/**
 * How the editor orders a palette's segments. COLOR/BRIGHTNESS re-sort on selection; CUSTOM is
 * entered only by manually reordering segments (drag or arrow keys) and can't be clicked into.
 */
public enum PaletteOrder {
    COLOR("Colour"),
    BRIGHTNESS("Brightness"),
    CUSTOM("Custom");

    private final String label;

    PaletteOrder(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
