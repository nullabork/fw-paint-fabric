package co.fax.wang;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Finder tab's "precompiled" colour lists: every placeable block in the game with its average
 * texture colour, pre-sorted by the two {@link ColorOrder} orderings. Built once, lazily, on first
 * use and cached for the session (block colours come from the live resource manager, so they can't
 * be baked at build time — resource packs change them). After the one-time build, switching sort
 * mode or filtering just reads these lists; nothing is recomputed.
 */
public final class ColorIndex {

    private ColorIndex() {}

    /** One placeable block with its computed colour and both sort keys. */
    public record Entry(Block block, ItemStack stack, String id, String name, int rgb,
                        double luminance, long colorKey, long brightnessKey) {}

    private static List<Entry> colorList;      // colorKey ascending (the rainbow)
    private static List<Entry> brightnessList; // brightnessKey ascending (dark → light)
    private static List<Entry> nameList;       // display name ascending (for the picker)
    private static long[] colorKeyArr, brightnessKeyArr;
    private static Map<String, Entry> idMap;

    public static List<Entry> byColor() { ensureBuilt(); return colorList; }
    public static List<Entry> byBrightness() { ensureBuilt(); return brightnessList; }
    public static List<Entry> byName() { ensureBuilt(); return nameList; }

    /** Sort keys parallel to {@link #byColor()}, for {@link ColorOrder#insertionIndex}. */
    public static long[] colorKeys() { ensureBuilt(); return colorKeyArr; }
    /** Sort keys parallel to {@link #byBrightness()}. */
    public static long[] brightnessKeys() { ensureBuilt(); return brightnessKeyArr; }

    public static Entry byId(String id) { ensureBuilt(); return idMap.get(id); }

    /** Drop the index — for a future resource-reload hook (nothing calls this yet). */
    public static void invalidate() {
        colorList = brightnessList = nameList = null;
        colorKeyArr = brightnessKeyArr = null;
        idMap = null;
    }

    private static void ensureBuilt() {
        if (colorList != null) return;
        List<Entry> entries = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (!(item instanceof BlockItem blockItem)) continue;
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null || id.toString().equals("minecraft:air")) continue;
            ItemStack stack = new ItemStack(item);
            int rgb = BlockTextures.gradientValue(blockItem.getBlock(), null, GradientMode.COLOR, 0.5) & 0xFFFFFF;
            entries.add(new Entry(blockItem.getBlock(), stack, id.toString(),
                    stack.getHoverName().getString(), rgb, TextureStats.luminance(rgb),
                    ColorOrder.colorSortKey(rgb), ColorOrder.brightnessSortKey(rgb)));
        }

        List<Entry> byColor = new ArrayList<>(entries);
        byColor.sort(Comparator.comparingLong(Entry::colorKey).thenComparing(Entry::id));
        List<Entry> byBrightness = new ArrayList<>(entries);
        byBrightness.sort(Comparator.comparingLong(Entry::brightnessKey).thenComparing(Entry::id));
        List<Entry> byName = new ArrayList<>(entries);
        byName.sort(Comparator.comparing(Entry::name).thenComparing(Entry::id));

        long[] colorKeys = new long[byColor.size()];
        long[] brightnessKeys = new long[byBrightness.size()];
        Map<String, Entry> ids = new HashMap<>();
        for (int i = 0; i < byColor.size(); i++) {
            colorKeys[i] = byColor.get(i).colorKey();
            brightnessKeys[i] = byBrightness.get(i).brightnessKey();
            ids.put(entries.get(i).id(), entries.get(i));
        }

        colorList = List.copyOf(byColor);
        brightnessList = List.copyOf(byBrightness);
        nameList = List.copyOf(byName);
        colorKeyArr = colorKeys;
        brightnessKeyArr = brightnessKeys;
        idMap = ids;
    }
}
