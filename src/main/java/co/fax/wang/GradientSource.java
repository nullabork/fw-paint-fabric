package co.fax.wang;

/**
 * Where the palette of candidate blocks for a gradient is drawn from. Cycled by a button on
 * {@link GradientScreen}.
 */
public enum GradientSource {
    HOTBAR("Hotbar"),
    INVENTORY("Inventory"),
    HOTBAR_AND_INVENTORY("Hotbar + Inv");

    private final String displayName;

    GradientSource(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public GradientSource next() {
        GradientSource[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
