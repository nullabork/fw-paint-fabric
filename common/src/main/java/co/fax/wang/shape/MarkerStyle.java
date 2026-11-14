package co.fax.wang.shape;

/**
 * How one region marker (box, ring or line) is drawn, set from the Rendering page of its toolbar:
 * the colour and opacity of its faces and of its edge lines, and optionally a block whose textures
 * the faces wear instead of a flat tint (a preview only — nothing is placed), and whether edges
 * and faces still show where blocks are in front of them. Every field starts
 * unset, meaning the marker kind's own default. Saved with the marker (see {@link ShapeStore}).
 */
public final class MarkerStyle {

    public static final int UNSET = -1;

    /** 0xRRGGBB, or {@link #UNSET}. */
    public int faceColor = UNSET, edgeColor = UNSET;
    /** 0–100 percent, or {@link #UNSET}. */
    public int faceOpacity = UNSET, edgeOpacity = UNSET;
    /** Item id of the block the faces are textured with; empty = flat tint. */
    public String block = "";
    /** Edge lines also show through blocks in front of them (the faint cage). On by default. */
    public boolean edgesThrough = true;
    /** Faces also show through blocks in front of them. Off by default. */
    public boolean facesThrough = false;

    public boolean isDefault() {
        return faceColor == UNSET && edgeColor == UNSET && faceOpacity == UNSET && edgeOpacity == UNSET
                && !hasBlock() && edgesThrough && !facesThrough;
    }

    public boolean hasBlock() {
        return block != null && !block.isEmpty();
    }

    public void reset() {
        faceColor = edgeColor = faceOpacity = edgeOpacity = UNSET;
        block = "";
        edgesThrough = true;
        facesThrough = false;
    }

    /** The face tint: {@code def} with this style's colour and opacity where set. */
    public int face(int def) {
        return apply(def, faceColor, faceOpacity);
    }

    /**
     * An edge colour: {@code def} with this style's opacity, and its colour too when
     * {@code recolor} (the white looked-at edges keep their white).
     */
    public int edge(int def, boolean recolor) {
        return apply(def, recolor ? edgeColor : UNSET, edgeOpacity);
    }

    /**
     * The see-through cage drawn over terrain: the edge colour, never stronger than {@code def}
     * and at most half the edge opacity, so turning edges down turns the cage down with them.
     * Fully clear (draw nothing) when edges are set not to show through blocks.
     */
    public int ghost(int def, boolean recolor) {
        if (!edgesThrough) return 0;
        int argb = apply(def, recolor ? edgeColor : UNSET, UNSET);
        if (edgeOpacity == UNSET) return argb;
        int a = Math.min(def >>> 24, alphaOf(edgeOpacity) / 2);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** Alpha (0–255) of block-textured faces: the face opacity, fully solid when unset. */
    public int blockAlpha() {
        return faceOpacity == UNSET ? 255 : alphaOf(faceOpacity);
    }

    /** The face opacity as a percent, {@code def}'s when unset (solid for a block). */
    public int facePercent(int def) {
        if (faceOpacity != UNSET) return faceOpacity;
        return hasBlock() ? 100 : percentOf(def);
    }

    public int edgePercent(int def) {
        return edgeOpacity != UNSET ? edgeOpacity : percentOf(def);
    }

    /** {@code percent} nudged one 10% step up or down, kept in 0–100. */
    public static int step(int percent, int dir) {
        int snapped = (percent + 5) / 10 * 10;
        return Math.max(0, Math.min(100, snapped + dir * 10));
    }

    private static int apply(int def, int rgb, int percent) {
        int a = percent == UNSET ? def >>> 24 : alphaOf(percent);
        return (a << 24) | ((rgb == UNSET ? def : rgb) & 0xFFFFFF);
    }

    private static int alphaOf(int percent) {
        return Math.round(Math.max(0, Math.min(100, percent)) * 2.55f);
    }

    private static int percentOf(int argb) {
        return Math.round((argb >>> 24) / 2.55f);
    }
}
