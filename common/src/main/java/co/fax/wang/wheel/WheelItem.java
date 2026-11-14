package co.fax.wang.wheel;

import co.fax.wang.config.WheelPress;
import co.fax.wang.ui.Ui;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * One entry on the wheel: either a category (fans its children out into the next ring) or a leaf
 * whose {@code action} runs on click. A leaf shows its {@code label} — or, when {@code icon} is
 * set, a 16px item sprite (the label then shows in the description line on hover).
 * {@code selected} reports live whether this leaf is the current choice. {@code missing} marks
 * something whose blocks aren't all to hand: red, with {@code note} ("2 missing") under it.
 * {@code description} is the one-liner along the bottom while hovered. {@code press} is what the
 * Recent bar remembers when this leaf is clicked (null for leaves it shouldn't remember, like
 * "More…").
 */
public record WheelItem(String label, int labelColor, ItemStack icon, BooleanSupplier selected,
                        Runnable action, boolean missing, String note, List<WheelItem> children,
                        String description, WheelPress press) {

    /** This item with a hover description. */
    public WheelItem describe(String text) {
        return new WheelItem(label, labelColor, icon, selected, action, missing, note, children, text, press);
    }

    /** This item, remembered on the Recent bar as {@code press} when clicked. */
    public WheelItem remembers(WheelPress p) {
        return new WheelItem(label, labelColor, icon, selected, action, missing, note, children, description, p);
    }

    /** A selectable leaf: clicking runs {@code action}; {@code selected} drives the highlight. */
    public static WheelItem leaf(String label, int labelColor, BooleanSupplier selected, Runnable action) {
        return new WheelItem(label, labelColor, null, selected, action, false, null, List.of(), null, null);
    }

    /** A leaf drawn as a 16px item sprite; its name shows in the description line while hovered. */
    public static WheelItem iconLeaf(String label, ItemStack icon, BooleanSupplier selected, Runnable action) {
        return new WheelItem(label, 0xFFFFFFFF, icon, selected, action, false, null, List.of(), null, null);
    }

    /** An icon leaf whose block isn't to hand: still selectable, red slot, crossed, "missing" under it. */
    public static WheelItem missingIconLeaf(String label, ItemStack icon, BooleanSupplier selected,
                                            Runnable action) {
        return new WheelItem(label, Ui.MISSING, icon, selected, action, true, "missing", List.of(), null, null);
    }

    /** A palette/pattern leaf missing {@code count} of its blocks: red label, "N missing" under it. */
    public static WheelItem missingLeaf(String label, int count, BooleanSupplier selected, Runnable action) {
        return new WheelItem(label, Ui.MISSING, null, selected, action, true, count + " missing", List.of(),
                null, null);
    }

    /** A category slot that fans its children out into the next ring. */
    public static WheelItem category(String label, WheelItem... children) {
        return new WheelItem(label, 0xFFFFFFFF, null, null, null, false, null, List.of(children), null, null);
    }

    public boolean isCategory() {
        return !children.isEmpty();
    }

    public boolean isSelected() {
        return selected != null && selected.getAsBoolean();
    }

    /** True when one of this category's DIRECT children is the current selection. */
    public boolean hasSelectedChild() {
        for (WheelItem c : children) {
            if (c.isSelected()) return true;
        }
        return false;
    }
}
