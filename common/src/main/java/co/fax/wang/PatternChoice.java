package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.palette.MissingBlockPolicy;
import co.fax.wang.palette.Palette;
import co.fax.wang.palette.PaletteStore;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Press-time state and per-cell block choice for PATTERN painting. The geometry lives in
 * {@link co.fax.wang.palette.PatternMath}; this class validates the active pattern against the
 * inventory and applies the experimental Oklab-neighbour variation: the source blocks are held
 * in colour order, and variation N swaps a cell's block for a uniformly-random block within N
 * positions of it in that order.
 */
public final class PatternChoice {

    private PatternChoice() {}

    private static final Random RANDOM = new Random();

    /** Press-wide state. {@code error} non-null → nothing may be placed; show it and abort. */
    public static final class Prepared {
        public final Palette pattern;      // snapshot copy — mid-press edits can't shift cells
        public final String error;
        final List<Block> ordered;         // source blocks in Oklab colour order (variation)
        final Set<String> available;       // block-item ids present in the source
        final boolean skipMissing;         // global policy: treat missing-block cells as holes

        private Prepared(Palette pattern, String error, List<Block> ordered,
                         Set<String> available, boolean skipMissing) {
            this.pattern = pattern;
            this.error = error;
            this.ordered = ordered;
            this.available = available;
            this.skipMissing = skipMissing;
        }

        private static Prepared fail(Palette pattern, String error) {
            return new Prepared(pattern, error, List.of(), Set.of(), false);
        }
    }

    public static Prepared prepare(LocalPlayer player) {
        Palette active = PaletteStore.activePattern();
        if (active == null) {
            return Prepared.fail(null, "Pattern: no pattern — press "
                    + Gradient.boundKey("open") + " to set one up");
        }
        Palette pattern = active.copy();
        boolean any = false;
        for (String c : pattern.cells) {
            if (c != null && !c.isEmpty()) {
                any = true;
                break;
            }
        }
        if (!any) {
            return Prepared.fail(pattern, "'" + pattern.name + "' is empty — draw some cells");
        }

        record Entry(Block block, String id, int rgb) {}
        List<Entry> source = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        NonNullList<ItemStack> items = player.getInventory().getNonEquipmentItems();
        int from = pattern.source == GradientSource.INVENTORY ? 9 : 0;
        int to = Math.min(pattern.source == GradientSource.HOTBAR ? 9 : 36, items.size());
        for (int slot = from; slot < to; slot++) {
            ItemStack st = items.get(slot);
            if (!(st.getItem() instanceof BlockItem bi)) continue;
            Block b = bi.getBlock();
            if (b.defaultBlockState().isAir() || !seen.add(b)) continue;
            Identifier id = BuiltInRegistries.ITEM.getKey(st.getItem());
            source.add(new Entry(b, id == null ? "" : id.toString(),
                    BlockTextures.gradientValue(b, null, GradientMode.COLOR, 0.5)));
        }
        if (source.isEmpty()) {
            return Prepared.fail(pattern, "Pattern: no placeable blocks in "
                    + pattern.source.displayName());
        }

        Set<String> available = new HashSet<>();
        for (Entry e : source) available.add(e.id());
        boolean skipMissing = ConfigManager.get().missingBlockPolicy == MissingBlockPolicy.SKIP_MISSING;
        List<String> missing = pattern.missingBlocks(available);
        if (!skipMissing && !missing.isEmpty()) {
            Block b = Gradient.blockOfItemId(missing.get(0));
            String name = b == null ? missing.get(0)
                    : new ItemStack(b.asItem()).getHoverName().getString();
            String more = missing.size() > 1 ? " (+" + (missing.size() - 1) + " more)" : "";
            return Prepared.fail(pattern,
                    "'" + pattern.name + "': missing " + name + more);
        }

        source.sort(java.util.Comparator.comparingLong(e -> ColorOrder.colorSortKey(e.rgb())));
        List<Block> ordered = new ArrayList<>(source.size());
        for (Entry e : source) ordered.add(e.block());
        return new Prepared(pattern, null, ordered, available, skipMissing);
    }

    /** True when this cell id should be treated as a hole (missing block under Skip missing). */
    public static boolean cellIsHole(Prepared p, String cellId) {
        if (cellId == null || cellId.isEmpty()) return true;
        return p.skipMissing && !p.available.contains(cellId);
    }

    /**
     * The block a cell places, with variation applied: at N ≥ 1, with the pattern's variation
     * CHANCE (percent), the cell swaps to a uniformly-random OTHER block within N positions of
     * its block in the Oklab ordering; otherwise (or when the roll fails) the drawn block
     * places. Null when the cell's block isn't in the source (only under Skip missing).
     */
    public static Block varied(Prepared p, String cellId) {
        Block base = Gradient.blockOfItemId(cellId);
        if (base == null) return null;
        int n = Math.max(0, Math.min(3, p.pattern.patternVariation));
        int idx = p.ordered.indexOf(base);
        if (n == 0 || idx < 0) return idx < 0 && !p.available.contains(cellId) ? null : base;
        int chance = Math.max(1, Math.min(100, p.pattern.patternVariationChance));
        if (RANDOM.nextInt(100) >= chance) return base;
        int lo = Math.max(0, idx - n);
        int hi = Math.min(p.ordered.size() - 1, idx + n);
        if (hi <= lo) return base;
        int pick = lo + RANDOM.nextInt(hi - lo); // window minus the base block itself
        if (pick >= idx) pick++;
        return p.ordered.get(pick);
    }

    // The block a strict slot lookup most recently failed to find (for the "out of X" error).
    private static Block lastMissing;

    public static Block lastMissingBlock() {
        return lastMissing;
    }

    /**
     * Inventory slot for a resolved cell block; -1 (with {@link #lastMissingBlock()} set) when
     * the block ran out — the caller stops the paint rather than substituting.
     */
    public static int slotFor(LocalPlayer player, Prepared p, Block block) {
        int slot = PaletteChoice.findSlot(player, block, p.pattern);
        if (slot < 0) lastMissing = block;
        return slot;
    }
}
