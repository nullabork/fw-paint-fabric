package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
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
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Gradient block choice, shared by every placement mode: gathers the candidate palette from the
 * configured source, builds the ramp (endpoint range + max steps + the fixed sanity budget), and
 * picks the block for a gradient fraction {@code t} — applying the Curve, Chaos, Variation (band
 * groups), and Step length (boundary wobble) settings exactly like the settings-screen preview.
 *
 * <p>The wobble boundaries are rolled once per {@code wobbleKey} (a marker segment, an
 * outside-marker column, or a 3D fill centre) so a fill stays coherent while it runs, and re-roll
 * when the key, step count, or slider changes.
 */
public final class GradientChoice {

    private GradientChoice() {}

    /** A candidate block: its inventory slot, the block, and its gradient value. */
    public record Palette(int slot, Block block, int rgb) {}

    /** Resolved picker endpoints (the [S]/[E] blocks, falling back to lightest/darkest). */
    public record Endpoints(Block start, Block end) {}

    private static final Random RANDOM = new Random();

    // Step-length wobble: randomised band boundaries, kept stable per key so steps stay coherent.
    private static Object wobbleKey;
    private static int wobbleCount;
    private static double wobbleStrength;
    private static double[] wobbleBounds; // ascending boundaries in curved-t space, length count-1

    // ---- palette ----------------------------------------------------------------------------------

    /** Candidate blocks from the configured source, valued relative to {@code startBlock}. */
    public static List<Palette> sourcePalette(LocalPlayer player, Block startBlock) {
        NonNullList<ItemStack> items = player.getInventory().getNonEquipmentItems();
        int from, to;
        switch (ConfigManager.get().source) {
            case HOTBAR -> { from = 0; to = 9; }
            case INVENTORY -> { from = 9; to = 36; }
            default -> { from = 0; to = 36; } // HOTBAR_AND_INVENTORY
        }
        to = Math.min(to, items.size());

        List<String> excluded = ConfigManager.get().excludedBlocks;
        List<Palette> palette = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        for (int slot = from; slot < to; slot++) {
            ItemStack st = items.get(slot);
            if (!(st.getItem() instanceof BlockItem bi)) continue;
            Block b = bi.getBlock();
            if (b.defaultBlockState().isAir() || !seen.add(b)) continue; // dedupe; hotbar-first slot wins
            Identifier id = BuiltInRegistries.ITEM.getKey(st.getItem());
            if (id != null && excluded.contains(id.toString())) continue; // user-excluded block
            palette.add(new Palette(slot, b, rgbOf(b, startBlock)));
        }
        return palette;
    }

    /** Gradient value of {@code block} (relative to {@code startBlock} for the diff modes). */
    public static int rgbOf(Block block, Block startBlock) {
        GradientConfig cfg = ConfigManager.get();
        return BlockTextures.gradientValue(block, startBlock, cfg.gradientMode, cfg.pixelPercent);
    }

    /**
     * The picker's [S]/[E] endpoint blocks. Missing or unresolvable endpoints fall back to the
     * lightest/darkest source block (like the noise tool), so a gradient can always run outside
     * markers. Returns null when the source has no placeable blocks at all.
     */
    public static Endpoints pickerEndpoints(LocalPlayer player) {
        GradientConfig cfg = ConfigManager.get();
        List<Palette> palette = sourcePalette(player, null);
        if (palette.isEmpty()) return null;
        Block start = Gradient.blockOfItemId(cfg.orderStartBlock);
        Block end = Gradient.blockOfItemId(cfg.orderEndBlock);
        if (start == null || end == null) {
            Block lightest = null, darkest = null;
            double lo = Double.MAX_VALUE, hi = -1;
            for (Palette p : palette) {
                double l = BlockTextures.baseLuminance(p.block());
                if (l > hi) { hi = l; lightest = p.block(); }
                if (l < lo) { lo = l; darkest = p.block(); }
            }
            if (start == null) start = lightest;
            if (end == null) end = darkest;
        }
        return (start == null || end == null) ? null : new Endpoints(start, end);
    }

    /**
     * How many distinct steps the picker gradient has (the outside-marker gradient's length in
     * blocks — one block per step). Pick mode counts its numbered groups instead.
     */
    public static int stepCount(LocalPlayer player, Endpoints eps) {
        GradientConfig cfg = ConfigManager.get();
        if (cfg.gradientMode.isPick()) {
            List<Palette> palette = sourcePalette(player, null);
            return pickGroups(palette, cfg).size();
        }
        List<Palette> palette = sourcePalette(player, eps.start());
        if (palette.isEmpty()) return 0;
        int startRgb = rgbOf(eps.start(), eps.start());
        int endRgb = rgbOf(eps.end(), eps.start());
        int n = palette.size();
        int[] rgbs = new int[n];
        Set<Integer> forced = new HashSet<>();
        List<String> required = cfg.requiredBlocks;
        for (int i = 0; i < n; i++) {
            rgbs[i] = palette.get(i).rgb();
            Identifier id = BuiltInRegistries.ITEM.getKey(palette.get(i).block().asItem());
            if (id != null && required.contains(id.toString())) forced.add(i);
        }
        int[] eligible = GradientRamp.gradientOrder(
                rgbs, startRgb, endRgb, cfg.gradientMode, GradientRamp.STEP_ELIGIBILITY_BUDGET, forced);
        return GradientRamp.subsample(eligible, cfg.maxSteps).length;
    }

    // ---- choice -----------------------------------------------------------------------------------

    /**
     * Choose a block for a fill cell at gradient fraction {@code t}. The gradient always builds the
     * fullest ramp available (the Variation slider never adds/removes steps), distributes it evenly
     * along the fill, then picks randomly within the step's band group (its block + similar blocks
     * per the Variation slider). At variation 0 the placement is fully deterministic; Chaos, when
     * set, still nudges the step.
     */
    public static Palette pick(List<Palette> palette, GradientConfig cfg,
                               int startRgb, int endRgb, double t, Object key) {
        if (cfg.gradientMode.isPick()) return pickModeChoice(palette, cfg, t, key);
        int n = palette.size();
        int[] rgbs = new int[n];
        Set<Integer> forced = new HashSet<>(); // must-use (green) blocks bypass the sanity budget
        List<String> required = ConfigManager.get().requiredBlocks;
        for (int i = 0; i < n; i++) {
            rgbs[i] = palette.get(i).rgb();
            Identifier id = BuiltInRegistries.ITEM.getKey(palette.get(i).block().asItem());
            if (id != null && required.contains(id.toString())) forced.add(i);
        }
        int[] eligible = GradientRamp.gradientOrder(
                rgbs, startRgb, endRgb, cfg.gradientMode, GradientRamp.STEP_ELIGIBILITY_BUDGET, forced);
        int[] steps = GradientRamp.subsample(eligible, cfg.maxSteps);
        if (steps.length == 0) return null;
        List<int[]> groups = GradientRamp.bandGroups(rgbs, steps, eligible, cfg.gradientMode, cfg.deviationBudget);

        double tc = cfg.curve.apply(t);
        // Chaos = how often a placement deviates (repeat the previous step or skip ahead one), but
        // never the start/end cells (t == 0 or 1), so the gradient still begins and ends correctly.
        if (cfg.chaos > 0 && t > 0.0 && t < 1.0 && RANDOM.nextDouble() < cfg.chaos) {
            double stepFrac = steps.length > 1 ? 1.0 / (steps.length - 1) : 0.1;
            tc = clamp01(RANDOM.nextBoolean() ? tc - stepFrac : tc + stepFrac);
        }

        int[] group = groups.get(stepIndex(cfg, key, steps.length, tc));
        return palette.get(group[RANDOM.nextInt(group.length)]);
    }

    /**
     * Step index for a curved fraction, with the step-length wobble applied. Wobble shifts the
     * boundary between two steps (by up to half a band), so making one step longer automatically
     * makes its neighbour shorter — the gradient still starts and ends on time.
     */
    private static int stepIndex(GradientConfig cfg, Object key, int count, double tc) {
        if (cfg.stepWobble <= 0 || count <= 1) return GradientRamp.rampIndex(count, tc);
        if (!Objects.equals(key, wobbleKey) || count != wobbleCount || cfg.stepWobble != wobbleStrength) {
            double band = 1.0 / count;
            wobbleBounds = new double[count - 1];
            for (int k = 0; k < count - 1; k++) {
                double off = RANDOM.nextDouble() < cfg.stepWobble
                        ? (RANDOM.nextDouble() - 0.5) * band : 0.0;
                wobbleBounds[k] = (k + 1) * band + off;
            }
            wobbleKey = key;
            wobbleCount = count;
            wobbleStrength = cfg.stepWobble;
        }
        int pos = 0;
        while (pos < wobbleBounds.length && tc >= wobbleBounds[pos]) pos++;
        return pos;
    }

    /**
     * Pick mode: distribute exactly the user-numbered blocks (low→high) across the fill — no
     * gradient computed, max-steps/deviation ignored. Chaos still nudges the step; ties are
     * randomised. Returns null if no numbered block is available.
     */
    private static Palette pickModeChoice(List<Palette> palette, GradientConfig cfg, double t, Object key) {
        List<List<Palette>> groups = pickGroups(palette, cfg);
        if (groups.isEmpty()) return null;

        double tc = cfg.curve.apply(t);
        if (cfg.chaos > 0 && t > 0.0 && t < 1.0 && RANDOM.nextDouble() < cfg.chaos) {
            double stepFrac = groups.size() > 1 ? 1.0 / (groups.size() - 1) : 0.1;
            tc = clamp01(RANDOM.nextBoolean() ? tc - stepFrac : tc + stepFrac);
        }
        List<Palette> group = groups.get(stepIndex(cfg, key, groups.size(), tc));
        return group.get(RANDOM.nextInt(group.size())); // randomise ties
    }

    /** Palette entries grouped by their pick number (low→high); unnumbered blocks are dropped. */
    private static List<List<Palette>> pickGroups(List<Palette> palette, GradientConfig cfg) {
        java.util.TreeMap<Integer, List<Palette>> byNumber = new java.util.TreeMap<>();
        for (Palette p : palette) {
            Identifier id = BuiltInRegistries.ITEM.getKey(p.block().asItem());
            int num = id == null ? 0 : cfg.pickNumbers.getOrDefault(id.toString(), 0);
            if (num > 0) byNumber.computeIfAbsent(num, k -> new ArrayList<>()).add(p);
        }
        return new ArrayList<>(byNumber.values());
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
