package co.fax.wang.palette;

import java.util.Objects;

/**
 * One entry in a palette's strip: either a specific block (by item registry id) or an Automatic
 * wildcard resolved at placement time. Gson-serialized as {@code {"block": "..."} } or
 * {@code {"auto": "COLOR"}} — exactly one of the two is set.
 */
public final class PaletteSegment {

    /** Item registry id of the block, or empty when this is an automatic segment. */
    public String block = "";

    /** Automatic mode, or null when this is a concrete block segment. */
    public AutoMode auto = null;

    public PaletteSegment() {}

    public static PaletteSegment ofBlock(String itemId) {
        PaletteSegment s = new PaletteSegment();
        s.block = itemId == null ? "" : itemId;
        return s;
    }

    public static PaletteSegment ofAuto(AutoMode mode) {
        PaletteSegment s = new PaletteSegment();
        s.auto = mode;
        return s;
    }

    public boolean isAutomatic() {
        return auto != null;
    }

    /** Display label: the automatic label, or empty for blocks (hosts look up the item name). */
    public String autoLabel() {
        return auto == null ? "" : auto.label();
    }

    public PaletteSegment copy() {
        PaletteSegment s = new PaletteSegment();
        s.block = block;
        s.auto = auto;
        return s;
    }

    /** Canonical token for fingerprinting/equality: {@code auto:COLOR} or the block id. */
    public String token() {
        return auto != null ? "auto:" + auto.name() : block;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PaletteSegment other && token().equals(other.token());
    }

    @Override
    public int hashCode() {
        return Objects.hash(token());
    }
}
