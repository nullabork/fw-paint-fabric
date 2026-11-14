package co.fax.wang.config;

/**
 * One remembered wheel pick, saved in {@link GradientConfig#wheelHistory}. {@code kind} says what
 * it sets: PAINT (a paint type), PALETTE / PATTERN (an id, with the paint type it was picked
 * under in {@code paint}), BLOCK (a Solid block item id), MATCH (a Solid match mode), MODE (a
 * placement mode) or OPTION ({@code name=state}, e.g. {@code placeInAir=on}). {@code path} is the
 * ring path shown above the button ("Place › Blocks"); {@code label} is the name at pick time.
 */
public final class WheelPress {
    public String kind = "";
    public String value = "";
    public String paint = "";
    public String path = "";
    public String label = "";

    public WheelPress() {}

    public WheelPress(String kind, String value, String paint, String label) {
        this.kind = kind;
        this.value = value;
        this.paint = paint == null ? "" : paint;
        this.label = label;
    }

    public WheelPress copy() {
        WheelPress p = new WheelPress(kind, value, paint, label);
        p.path = path;
        return p;
    }
}
