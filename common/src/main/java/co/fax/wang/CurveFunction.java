package co.fax.wang;

/**
 * Easing curve applied to gradient progress {@code t} (0 at the start marker, 1 at the end marker)
 * before mapping it onto the palette. Controls where the gradient "changes fastest". Cycled by a
 * button on {@link GradientScreen}.
 */
public enum CurveFunction {
    /** Constant rate of change. */
    LINEAR("Linear"),
    /** Shallow start, steep end. */
    EASE_IN("Ease In"),
    /** Steep start, shallow end. */
    EASE_OUT("Ease Out"),
    /** Shallow at both ends, steep in the middle. */
    EASE_IN_OUT("Ease In/Out"),
    /** Hard, quantised bands instead of a smooth blend. */
    STEP("Step");

    private static final int STEP_BANDS = 4;

    private final String displayName;

    CurveFunction(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Map progress {@code t} in [0,1] through this curve, returning a value in [0,1]. */
    public double apply(double t) {
        double x = Math.max(0.0, Math.min(1.0, t));
        return switch (this) {
            case LINEAR -> x;
            case EASE_IN -> x * x;
            case EASE_OUT -> x * (2.0 - x);
            case EASE_IN_OUT -> x * x * (3.0 - 2.0 * x); // smoothstep
            case STEP -> Math.round(x * STEP_BANDS) / (double) STEP_BANDS;
        };
    }

    public CurveFunction next() {
        CurveFunction[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
