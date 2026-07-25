package co.fax.wang.palette;

import com.google.gson.annotations.SerializedName;

/**
 * How the editor orders a palette's segments. The four sort values re-sort the strip on click
 * (Automatic segments sort as a middle grey); CUSTOM is entered only by manually reordering
 * segments (drag or arrow keys) and can't be clicked into. Old saves used COLOR/BRIGHTNESS —
 * the alternate names keep them loading.
 */
public enum PaletteOrder {
    @SerializedName(value = "COLOR_ASC", alternate = {"COLOR"})
    COLOR_ASC("Colour asc"),
    COLOR_DESC("Colour desc"),
    @SerializedName(value = "BRIGHTNESS_ASC", alternate = {"BRIGHTNESS"})
    BRIGHTNESS_ASC("Brightness asc"),
    BRIGHTNESS_DESC("Brightness desc"),
    CUSTOM("Custom");

    private final String label;

    PaletteOrder(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** The next sort value in the cycle, skipping CUSTOM (manual reordering selects it). */
    public PaletteOrder nextSort() {
        return switch (this) {
            case COLOR_ASC -> COLOR_DESC;
            case COLOR_DESC -> BRIGHTNESS_ASC;
            case BRIGHTNESS_ASC -> BRIGHTNESS_DESC;
            case BRIGHTNESS_DESC, CUSTOM -> COLOR_ASC;
        };
    }

    public boolean byBrightness() {
        return this == BRIGHTNESS_ASC || this == BRIGHTNESS_DESC;
    }

    public boolean descending() {
        return this == COLOR_DESC || this == BRIGHTNESS_DESC;
    }
}
