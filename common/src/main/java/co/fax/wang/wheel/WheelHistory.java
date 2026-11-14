package co.fax.wang.wheel;

import co.fax.wang.Gradient;
import co.fax.wang.PaintType;
import co.fax.wang.PlaceOptions;
import co.fax.wang.PlacementMode;
import co.fax.wang.SolidMatch;
import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import co.fax.wang.config.WheelPress;
import co.fax.wang.palette.Palette;
import co.fax.wang.palette.PaletteKind;
import co.fax.wang.palette.PaletteStore;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * The wheel's Recent bar: the last {@link #MAX} picks, newest first, saved in the config so they
 * outlive a relaunch. Picking one again re-applies everything it needs — a Solid block picked
 * while you now paint Gradient switches the paint back to Solid and "This block" too — and moves
 * it to the front. One entry per thing: picking it again moves the old entry rather than adding a
 * second one (an option keeps one entry, holding the state it was last set to).
 */
final class WheelHistory {

    static final int MAX = 20;

    static final String PAINT = "PAINT", PALETTE = "PALETTE", PATTERN = "PATTERN", BLOCK = "BLOCK",
            MATCH = "MATCH", MODE = "MODE", OPTION = "OPTION";
    static final String DONT_MATCH = "dontMatch", FILL_VOIDS = "fillVoids", PLACE_IN_AIR = "placeInAir",
            PERP_SNAP = "perpSnap";

    private WheelHistory() {}

    static List<WheelPress> list() {
        return ConfigManager.get().wheelHistory;
    }

    /** Remember a pick made on the ring at {@code path}. Options store the state they now have. */
    static void record(WheelPress template, String path) {
        WheelPress p = template.copy();
        p.path = path;
        if (OPTION.equals(p.kind)) {
            p.value = p.value + "=" + optionState(p.value);
            p.label = optionLabel(p.value);
        }
        put(p);
    }

    /** Re-apply a remembered pick (with whatever paint type it needs) and move it to the front. */
    static void apply(WheelPress p) {
        GradientConfig cfg = ConfigManager.get();
        switch (p.kind) {
            case PAINT -> Gradient.setPaintType(PaintType.valueOf(p.value));
            case PALETTE, PATTERN -> {
                Palette pal = PaletteStore.byId(p.value);
                if (pal == null) break;
                PaintType type = PATTERN.equals(p.kind) ? PaintType.PATTERN : paintOr(p.paint, PaintType.GRADIENT);
                if (cfg.activePaintType != type) Gradient.setPaintType(type);
                Gradient.setActivePalette(pal);
            }
            case BLOCK -> {
                if (cfg.activePaintType != PaintType.SOLID) Gradient.setPaintType(PaintType.SOLID);
                Gradient.setSolidBlock(p.value);
            }
            case MATCH -> {
                if (cfg.activePaintType != PaintType.SOLID) Gradient.setPaintType(PaintType.SOLID);
                Gradient.setSolidMatch(SolidMatch.valueOf(p.value));
            }
            case MODE -> Gradient.setPlacementMode(PlacementMode.valueOf(p.value));
            case OPTION -> {
                String[] kv = p.value.split("=", 2);
                if (kv.length == 2 && !kv[1].equals(optionState(kv[0]))) toggle(kv[0]);
            }
            default -> { }
        }
        put(p.copy());
    }

    /** True while this pick is what the wheel currently has set. */
    static boolean isActive(WheelPress p) {
        GradientConfig cfg = ConfigManager.get();
        return switch (p.kind) {
            case PAINT -> cfg.activePaintType.name().equals(p.value);
            case PALETTE -> {
                Palette a = PaletteStore.activeOf(PaletteKind.GRADIENT);
                yield cfg.activePaintType == paintOr(p.paint, PaintType.GRADIENT) && a != null && a.id.equals(p.value);
            }
            case PATTERN -> {
                Palette a = PaletteStore.activePattern();
                yield cfg.activePaintType == PaintType.PATTERN && a != null && a.id.equals(p.value);
            }
            case BLOCK -> cfg.activePaintType == PaintType.SOLID && cfg.solidMatch == SolidMatch.SELECTED
                    && cfg.solidBlock.equals(p.value);
            case MATCH -> cfg.activePaintType == PaintType.SOLID && cfg.solidMatch.name().equals(p.value);
            case MODE -> cfg.placementMode.name().equals(p.value);
            case OPTION -> {
                String[] kv = p.value.split("=", 2);
                yield kv.length == 2 && kv[1].equals(optionState(kv[0]));
            }
            default -> false;
        };
    }

    /** The red note under a pick that can't paint as-is ("2 missing", "missing", "deleted"), or null. */
    static String note(Minecraft mc, WheelPress p) {
        switch (p.kind) {
            case PALETTE, PATTERN -> {
                Palette pal = PaletteStore.byId(p.value);
                if (pal == null) return "deleted";
                int n = pal.missingBlocks(WheelScreen.availableIds(mc, pal.source)).size();
                return n == 0 ? null : n + " missing";
            }
            case BLOCK -> {
                return WheelScreen.availableIds(mc, ConfigManager.get().source).contains(p.value) ? null : "missing";
            }
            default -> {
                return null;
            }
        }
    }

    /** The icon a pick shows instead of its name (Solid blocks), or null. */
    static ItemStack icon(WheelPress p) {
        if (!BLOCK.equals(p.kind)) return null;
        Block b = Gradient.blockOfItemId(p.value);
        return b == null ? null : new ItemStack(b.asItem());
    }

    /** The name to show: a palette's current name (it may have been renamed), else the stored one. */
    static String label(WheelPress p) {
        if (PALETTE.equals(p.kind) || PATTERN.equals(p.kind)) {
            Palette pal = PaletteStore.byId(p.value);
            if (pal != null) return pal.name;
        }
        return p.label;
    }

    private static void put(WheelPress p) {
        List<WheelPress> list = list();
        String key = key(p);
        list.removeIf(e -> key(e).equals(key));
        list.add(0, p);
        while (list.size() > MAX) list.remove(list.size() - 1);
        ConfigManager.save();
    }

    private static String key(WheelPress p) {
        String v = OPTION.equals(p.kind) ? p.value.split("=", 2)[0] : p.value;
        return p.kind + ":" + v + ":" + (PALETTE.equals(p.kind) ? p.paint : "");
    }

    private static PaintType paintOr(String name, PaintType fallback) {
        try {
            return name == null || name.isEmpty() ? fallback : PaintType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String optionState(String name) {
        GradientConfig cfg = ConfigManager.get();
        return switch (name) {
            case DONT_MATCH -> Gradient.dontMatchPalette ? "on" : "off";
            case FILL_VOIDS -> cfg.faceFillVoids ? "on" : "off";
            case PLACE_IN_AIR -> cfg.placeInAir ? "on" : "off";
            case PERP_SNAP -> String.valueOf(cfg.perpSnapDegrees);
            default -> "";
        };
    }

    private static void toggle(String name) {
        switch (name) {
            case DONT_MATCH -> Gradient.toggleDontMatchPalette();
            case FILL_VOIDS -> PlaceOptions.toggleFillVoids();
            case PLACE_IN_AIR -> PlaceOptions.togglePlaceInAir();
            case PERP_SNAP -> PlaceOptions.togglePerpSnap();
            default -> { }
        }
    }

    /** "Place in air: on", "Perp snap 90°" — from a stored {@code name=state}. */
    private static String optionLabel(String value) {
        String[] kv = value.split("=", 2);
        String state = kv.length == 2 ? kv[1] : "";
        return switch (kv[0]) {
            case DONT_MATCH -> "Don't match palette: " + state;
            case FILL_VOIDS -> "Fill voids first: " + state;
            case PLACE_IN_AIR -> "Place in air: " + state;
            case PERP_SNAP -> "Perp snap " + state + "°";
            default -> value;
        };
    }
}
