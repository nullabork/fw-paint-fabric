package co.fax.wang.wheel;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * One entry on the selector wheel: either a category (fans its children out into the outer
 * ring) or a leaf whose {@code action} runs on click. A leaf renders its {@code label} — or,
 * when {@code icon} is set, a 16px item sprite instead (the label then appears under the wheel
 * on hover). {@code selected} reports live whether this leaf is the current choice (highlights
 * the slot); it reads config on every call so the highlight follows clicks instantly.
 * {@code missing} marks a selection whose backing thing is gone (e.g. the Solid block no longer
 * in the inventory): the slot stays selected but renders red with a cross.
 */
public record WheelItem(String label, int labelColor, ItemStack icon, BooleanSupplier selected,
                        Runnable action, boolean missing, List<WheelItem> children) {

    /** A selectable leaf: clicking runs {@code action}; {@code selected} drives the highlight. */
    public static WheelItem leaf(String label, int labelColor, BooleanSupplier selected, Runnable action) {
        return new WheelItem(label, labelColor, null, selected, action, false, List.of());
    }

    /** A leaf drawn as a 16px item sprite; its name shows under the wheel while hovered. */
    public static WheelItem iconLeaf(String label, ItemStack icon, BooleanSupplier selected, Runnable action) {
        return new WheelItem(label, 0xFFFFFFFF, icon, selected, action, false, List.of());
    }

    /** An icon leaf whose backing block is gone: stays selectable but renders red + crossed. */
    public static WheelItem missingIconLeaf(String label, ItemStack icon, BooleanSupplier selected,
                                            Runnable action) {
        return new WheelItem(label, 0xFFFF5555, icon, selected, action, true, List.of());
    }

    /** A text leaf missing some of its blocks (palette/pattern): red label, still selectable. */
    public static WheelItem missingLeaf(String label, BooleanSupplier selected, Runnable action) {
        return new WheelItem(label, 0xFFFF5555, null, selected, action, true, List.of());
    }

    /** A category slot that toggles its children out into the outer ring. */
    public static WheelItem category(String label, WheelItem... children) {
        return new WheelItem(label, 0xFFFFFFFF, null, null, null, false, List.of(children));
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
