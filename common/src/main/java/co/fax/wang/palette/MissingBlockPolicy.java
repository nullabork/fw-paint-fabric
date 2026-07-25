package co.fax.wang.palette;

/**
 * Global behaviour when a palette's explicitly-defined blocks aren't all available from its
 * source at paint time: refuse to place anything, or drop the unavailable segments and paint
 * with the rest.
 */
public enum MissingBlockPolicy {
    DONT_PAINT("Don't paint"),
    SKIP_MISSING("Skip missing");

    private final String label;

    MissingBlockPolicy(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
