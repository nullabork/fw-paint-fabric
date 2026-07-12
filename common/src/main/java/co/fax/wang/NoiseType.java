package co.fax.wang;

/** The noise function used by the noise-fill tool. Cycled by a button on the Noise Paint tab. */
public enum NoiseType {
    SMOOTH("Smooth"),     // interpolated value noise — soft blobs
    PERLIN("Perlin"),     // gradient noise — natural ridges
    FRACTAL("Fractal");   // multi-octave (fBm) — layered detail

    private final String displayName;

    NoiseType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public NoiseType next() {
        NoiseType[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
