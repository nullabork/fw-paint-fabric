package co.fax.wang;

/**
 * How the Solid paint chooses the block it places. SELECTED places the ✓ block from the Solid tab's
 * list; the other three derive the block from the one whose face you click when placing — an exact
 * inventory match, or the inventory block closest in average texture colour / brightness (excluded
 * blocks are never chosen). In all of these, markers are purely placement constraints.
 */
public enum SolidMatch {
    SELECTED("Selected block"),
    EXACT("Exact block"),
    CLOSEST_COLOR("Closest colour"),
    CLOSEST_BRIGHTNESS("Closest brightness");

    private final String displayName;

    SolidMatch(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public SolidMatch next() {
        SolidMatch[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
