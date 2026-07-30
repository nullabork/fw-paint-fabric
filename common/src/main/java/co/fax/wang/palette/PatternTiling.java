package co.fax.wang.palette;

/**
 * How a pattern repeats when painting runs past its edges: {@code SIDES} wraps across the width
 * axis, {@code START_END} wraps along the extrusion (height) axis; without the respective wrap,
 * cells beyond the width place nothing and columns stop after the pattern's height.
 */
public enum PatternTiling {
    NONE("None"),
    SIDES("Sides"),
    START_END("Start/end"),
    START_END_SIDES("Start/end + sides");

    private final String label;

    PatternTiling(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean wrapsSides() {
        return this == SIDES || this == START_END_SIDES;
    }

    public boolean wrapsStartEnd() {
        return this == START_END || this == START_END_SIDES;
    }

    public PatternTiling next() {
        PatternTiling[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
