package co.fax.wang;

/**
 * What the single paint tool paints. Switched in-game with the paint-type keybind (only while the
 * tool is held) and shown as the top line of the HUD helper text.
 */
public enum PaintType {
    GRADIENT("Gradient"),
    NOISE("Noise"),
    SOLID("Solid");

    private final String label;

    PaintType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** The next paint type, wrapping. */
    public PaintType next() {
        PaintType[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
