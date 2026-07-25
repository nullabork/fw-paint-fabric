package co.fax.wang.palette;

/**
 * How a placed gradient decides its length. MIN_BLOCKS realises the segment ratios in the fewest
 * blocks and never stretches toward an end block; FILL_SPACE expands to fill from the placed
 * block to the end marker (or first non-air block); SET_STEPS stretches/shrinks to a fixed
 * step count.
 */
public enum SizingMode {
    MIN_BLOCKS("Min blocks"),
    FILL_SPACE("Fill space"),
    SET_STEPS("Set steps");

    private final String label;

    SizingMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
