package co.fax.wang.shape;

/**
 * Which way a Gradient fill runs through a line's tube. Picked per line with the second row of
 * floating buttons; Solid and Noise ignore it.
 */
public enum LineGradient {
    /** From the side of the tube you aimed into, across to the far side (the default). */
    ACROSS("Across"),
    /** From the start node (turquoise) to the end node (amber), along the curve. */
    ALONG("Along"),
    /** From the line at the centre out to the tube's skin. */
    INSIDE_OUT("Inside out"),
    /** From the tube's skin in to the line at the centre. */
    OUTSIDE_IN("Outside in");

    public final String label;

    LineGradient(String label) {
        this.label = label;
    }
}
