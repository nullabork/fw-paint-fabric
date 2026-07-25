package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.palette.AutoMode;
import co.fax.wang.palette.MissingBlockPolicy;
import co.fax.wang.palette.Palette;
import co.fax.wang.palette.PaletteMath;
import co.fax.wang.palette.PaletteSegment;
import co.fax.wang.palette.PaletteStore;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * The v2 block-choice engine: resolves the <b>active palette</b> into a placeable ramp and picks
 * blocks per cell, for both gradient and noise painting.
 *
 * <p>A press calls {@link #prepare} once (validates the palette, gathers the source, applies the
 * missing-block policy), then {@link #resolveRamp} per context — a column, a marker segment, or a
 * 3D fill — supplying the colour anchors Automatic segments resolve against: the block the paint
 * started on, and the block found by the end scan (end marker / first non-air). Middle Automatic
 * segments interpolate between their resolved neighbours; resolution always widens until it finds
 * <i>something</i> in the (auto-exclusion-filtered) pool.
 */
public final class PaletteChoice {

    private PaletteChoice() {}

    private static final Random RANDOM = new Random();
    private static final int CELL_CAP = 64; // sizing cap — matches the placer's column scan limit

    /** A distinct source block with its registry id and average texture colour. */
    public record SourceEntry(Block block, String id, int rgb) {}

    /** Press-wide state. {@code error} non-null → nothing may be placed; show it and abort. */
    public static final class Prepared {
        public final Palette palette;
        public final List<SourceEntry> source;
        final List<SourceEntry> autoPool;         // source minus the palette's auto-exclusions
        final List<PaletteSegment> effective;     // segments minus skipped missing blocks
        public final String error;

        private Prepared(Palette palette, List<SourceEntry> source, List<SourceEntry> autoPool,
                         List<PaletteSegment> effective, String error) {
            this.palette = palette;
            this.source = source;
            this.autoPool = autoPool;
            this.effective = effective;
            this.error = error;
        }

        private static Prepared fail(Palette palette, String error) {
            return new Prepared(palette, List.of(), List.of(), List.of(), error);
        }
    }

    /** A resolved ramp: flat step blocks, their band boundaries, and variation swap groups. */
    public static final class Ramp {
        final Block[] blocks;
        final double[] bounds;           // internal flat boundaries in curved space
        final List<List<Block>> bands;   // per flat step: the block + its variation alternates

        private Ramp(Block[] blocks, double[] bounds, List<List<Block>> bands) {
            this.blocks = blocks;
            this.bounds = bounds;
            this.bands = bands;
        }

        public int steps() {
            return blocks.length;
        }

        public Block firstBlock() {
            return blocks.length == 0 ? null : blocks[0];
        }
    }

    // ---- press-time preparation -----------------------------------------------------------------

    /**
     * Validate the active palette against the player's inventory and the global missing-block
     * policy. {@code requiresStaticEnd} is set for 3D fills, which must know their colour range
     * up front (Automatic at the end can't scan in a blob).
     */
    public static Prepared prepare(LocalPlayer player, String typeLabel, boolean requiresStaticEnd) {
        Palette active = PaletteStore.active();
        if (active == null) {
            return Prepared.fail(null, typeLabel + ": no palette — press "
                    + Gradient.boundKey("open") + " to set one up");
        }
        Palette palette = active.copy(); // snapshot: mid-press edits can't shift the ramp
        if (palette.segments.isEmpty()) {
            return Prepared.fail(palette, "'" + palette.name + "' is empty — add blocks in the editor");
        }

        List<SourceEntry> source = gatherSource(player, palette);
        if (source.isEmpty()) {
            return Prepared.fail(palette, typeLabel + ": no placeable blocks in "
                    + palette.source.displayName());
        }

        Set<String> availableIds = new HashSet<>();
        for (SourceEntry e : source) availableIds.add(e.id());
        List<String> missing = palette.missingBlocks(availableIds);
        if (!missing.isEmpty() && ConfigManager.get().missingBlockPolicy == MissingBlockPolicy.DONT_PAINT) {
            return Prepared.fail(palette, "'" + palette.name + "': missing blocks ("
                    + missing.size() + ") — check the Palette tab");
        }

        List<PaletteSegment> effective = new ArrayList<>();
        for (PaletteSegment s : palette.segments) {
            if (!s.isAutomatic() && !availableIds.contains(s.block)) continue; // skip-missing
            effective.add(s);
        }
        if (effective.isEmpty()) {
            return Prepared.fail(palette, "'" + palette.name + "': none of its blocks are available");
        }
        if (requiresStaticEnd && effective.get(effective.size() - 1).isAutomatic()) {
            return Prepared.fail(palette, "'" + palette.name + "': 3D paint needs a defined end block");
        }

        List<SourceEntry> autoPool = new ArrayList<>();
        for (SourceEntry e : source) {
            if (!palette.autoExclude.contains(e.id())) autoPool.add(e);
        }
        if (autoPool.isEmpty()) autoPool = source; // everything excluded → ignore the exclusions

        return new Prepared(palette, source, autoPool, effective, null);
    }

    /** Distinct blocks from the palette's source range, hotbar-first, with id + average colour. */
    private static List<SourceEntry> gatherSource(LocalPlayer player, Palette palette) {
        NonNullList<ItemStack> items = player.getInventory().getNonEquipmentItems();
        int from, to;
        switch (palette.source) {
            case HOTBAR -> { from = 0; to = 9; }
            case INVENTORY -> { from = 9; to = 36; }
            default -> { from = 0; to = 36; }
        }
        to = Math.min(to, items.size());
        List<SourceEntry> out = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        for (int slot = from; slot < to; slot++) {
            ItemStack st = items.get(slot);
            if (!(st.getItem() instanceof BlockItem bi)) continue;
            Block b = bi.getBlock();
            if (b.defaultBlockState().isAir() || !seen.add(b)) continue;
            Identifier id = BuiltInRegistries.ITEM.getKey(st.getItem());
            out.add(new SourceEntry(b, id == null ? "" : id.toString(), colorOf(b)));
        }
        return out;
    }

    // ---- ramp resolution ------------------------------------------------------------------------

    /**
     * Resolve the palette for one paint context. {@code startAnchor} is the block the paint
     * started on (colour for Automatic-at-start); {@code endAnchor} the block the end scan found
     * (end marker / first non-air), or null when there is none. Returns null when an Automatic
     * segment has no colour to resolve against — the caller reports "can't resolve colours".
     */
    public static Ramp resolveRamp(Prepared p, Block startAnchor, Block endAnchor) {
        List<PaletteSegment> segs = p.effective;
        int n = segs.size();

        // Forward pass so a middle Automatic knows the next static block ahead of it.
        Block[] nextStatic = new Block[n];
        Block ahead = null;
        for (int i = n - 1; i >= 0; i--) {
            nextStatic[i] = ahead;
            if (!segs.get(i).isAutomatic()) {
                Block b = Gradient.blockOfItemId(segs.get(i).block);
                if (b != null) ahead = b;
            }
        }

        List<List<Block>> perSegment = new ArrayList<>(n);
        List<AutoMode> segMode = new ArrayList<>(n);
        Block prev = null; // last resolved block so far (Automatic-at-start falls back to the anchor)
        int i = 0;
        while (i < n) {
            PaletteSegment s = segs.get(i);
            if (!s.isAutomatic()) {
                Block b = Gradient.blockOfItemId(s.block);
                if (b != null) { // unresolvable id — treat like a skipped segment
                    perSegment.add(List.of(b));
                    segMode.add(null);
                    prev = b;
                }
                i++;
                continue;
            }
            // A run of consecutive Automatic segments: each resolves to exactly ONE block, its
            // target interpolated between the run's anchors at the segment's position — so a strip
            // of nothing but Automatics still forms a deterministic block distribution.
            int runEnd = i;
            while (runEnd + 1 < n && segs.get(runEnd + 1).isAutomatic()) runEnd++;
            Block from = prev != null ? prev : startAnchor;
            Block to = nextStatic[runEnd] != null ? nextStatic[runEnd] : endAnchor;
            int k = runEnd - i + 1;
            for (int j = 0; j < k; j++) {
                AutoMode mode = segs.get(i + j).auto;
                Block chosen = resolveAutoOne(p.autoPool, mode, from, to, (j + 1) / (double) (k + 1));
                if (chosen == null) return null; // no colour anywhere to resolve against
                perSegment.add(List.of(chosen));
                segMode.add(mode);
                prev = chosen;
            }
            i = runEnd + 1;
        }
        if (perSegment.isEmpty()) return null;

        double[] segBounds = PaletteMath.segmentBounds(
                perSegment.size(), p.palette.curve, p.palette.stops);
        int[] counts = new int[perSegment.size()];
        List<Block> flat = new ArrayList<>();
        List<AutoMode> flatMode = new ArrayList<>();
        for (int s = 0; s < perSegment.size(); s++) {
            counts[s] = perSegment.get(s).size();
            for (Block b : perSegment.get(s)) {
                flat.add(b);
                flatMode.add(segMode.get(s));
            }
        }
        double[] bounds = PaletteMath.flatBounds(segBounds, counts);

        List<List<Block>> bands = new ArrayList<>(flat.size());
        for (int f = 0; f < flat.size(); f++) {
            bands.add(bandFor(p, flat.get(f), flatMode.get(f)));
        }
        return new Ramp(flat.toArray(new Block[0]), bounds, bands);
    }

    /**
     * The single block an Automatic segment stands for: the pool block closest to the colour (or
     * brightness) interpolated between the anchors at {@code frac} along the run. Deterministic —
     * the same anchors and inventory always resolve the same block — so hand-crafted curves keep
     * their shape while the blocks are chosen for you. A single anchor makes every target that
     * anchor's colour; no anchor at all is unresolvable (null).
     */
    private static Block resolveAutoOne(List<SourceEntry> pool, AutoMode mode,
                                        Block from, Block to, double frac) {
        if ((from == null && to == null) || pool.isEmpty()) return null;
        GradientMode metric = mode == AutoMode.BRIGHTNESS ? GradientMode.BRIGHTNESS : GradientMode.COLOR;
        int target;
        if (from == null) target = colorOf(to);
        else if (to == null) target = colorOf(from);
        else target = lerpRgb(colorOf(from), colorOf(to), frac);
        return closestTo(pool, target, metric);
    }

    private static int lerpRgb(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static Block closestTo(List<SourceEntry> pool, int rgb, GradientMode metric) {
        SourceEntry best = pool.get(0);
        double bestD = Double.MAX_VALUE;
        for (SourceEntry e : pool) {
            double d = distance(e.rgb(), rgb, metric);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best.block();
    }

    /** Variation band: the step's block plus source blocks within the palette's variation range. */
    private static List<Block> bandFor(Prepared p, Block block, AutoMode mode) {
        double thresh = clamp01(p.palette.variation) * 127.5;
        List<Block> band = new ArrayList<>();
        band.add(block);
        if (thresh <= 0) return band;
        GradientMode metric = mode == AutoMode.BRIGHTNESS ? GradientMode.BRIGHTNESS : GradientMode.COLOR;
        int rgb = colorOf(block);
        for (SourceEntry e : p.source) {
            if (e.block() == block) continue;
            if (distance(e.rgb(), rgb, metric) <= thresh) band.add(e.block());
        }
        return band;
    }

    /** Colour/brightness distance in the space the mod's perceptual toggle selects. */
    private static double distance(int a, int b, GradientMode metric) {
        if (metric.usesBrightness()) return Math.abs(GradientRamp.brightness(a) - GradientRamp.brightness(b));
        if (GradientRamp.perceptual) return ColorOrder.oklabDist(a, b) * 255.0;
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return Math.sqrt((double) dr * dr + dg * dg + db * db);
    }

    /** Average texture colour used everywhere in palette resolution. */
    private static int colorOf(Block b) {
        return BlockTextures.gradientValue(b, null, GradientMode.COLOR, 0.5);
    }

    // ---- sizing ---------------------------------------------------------------------------------

    /**
     * Cells a column should span per the palette's sizing mode. {@code spaceAvailable} is the
     * measured distance to the end boundary (Fill space), or ≤0 when unknown — Fill space then
     * falls back to the minimal count so the gradient still completes.
     */
    public static int columnCells(Prepared p, Ramp r, int spaceAvailable) {
        return switch (p.palette.sizing) {
            case MIN_BLOCKS -> PaletteMath.minCells(r.bounds, CELL_CAP);
            case SET_STEPS -> Math.max(1, Math.min(CELL_CAP, p.palette.steps));
            case FILL_SPACE -> spaceAvailable > 0
                    ? Math.min(CELL_CAP, spaceAvailable)
                    : PaletteMath.minCells(r.bounds, CELL_CAP);
        };
    }

    // ---- per-cell choice ------------------------------------------------------------------------

    // Step-length wobble: jittered boundaries, stable per key (like v1's GradientChoice).
    private static Object wobbleKey;
    private static double[] wobbleBase;
    private static double wobbleStrength;
    private static double[] wobbleBounds;

    /**
     * Inventory slot for a gradient cell at fill fraction {@code t}: curve → chaos → wobbled
     * boundaries → flat step → random pick within the variation band, falling outward to the
     * nearest step that still has an available block. -1 when nothing can be placed.
     */
    public static int pickSlot(LocalPlayer player, Prepared p, Ramp r, double t, Object key) {
        if (r.steps() == 0) return -1;
        double tc = PaletteMath.curved(p.palette.curve, t);
        if (p.palette.chaos > 0 && t > 0.0 && t < 1.0 && RANDOM.nextDouble() < p.palette.chaos) {
            double stepFrac = r.steps() > 1 ? 1.0 / (r.steps() - 1) : 0.1;
            tc = clamp01(RANDOM.nextBoolean() ? tc - stepFrac : tc + stepFrac);
        }
        int idx = PaletteMath.indexFor(tc, wobbled(p, r, key));
        return slotForStep(player, p, r, idx);
    }

    /**
     * Inventory slot for a noise cell: sample the palette's noise field at the cell position,
     * map through the palette curve/stops (with the step-length wobble applied to the band
     * boundaries, rolled once per ramp so the pattern stays coherent), chaos-nudge the step.
     */
    public static int noiseSlot(LocalPlayer player, Prepared p, Ramp r, int x, int y, int z) {
        if (r.steps() == 0) return -1;
        Palette pal = p.palette;
        double t = Noise.sample(pal.noiseType, x, y, z, noiseSeedLong(pal),
                pal.noiseScaleX, pal.noiseScaleY, pal.noiseScaleZ);
        int idx = PaletteMath.indexFor(PaletteMath.curved(pal.curve, t), wobbled(p, r, r));
        if (pal.chaos > 0 && RANDOM.nextDouble() < pal.chaos) {
            idx = Math.max(0, Math.min(r.steps() - 1, RANDOM.nextBoolean() ? idx - 1 : idx + 1));
        }
        return slotForStep(player, p, r, idx);
    }

    /** Random available block in the step's band, widening to neighbouring steps when exhausted. */
    private static int slotForStep(LocalPlayer player, Prepared p, Ramp r, int idx) {
        for (int step = 0; step < r.steps(); step++) {
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                int i = idx + sgn * step;
                if (i < 0 || i >= r.steps()) continue;
                List<Block> band = new ArrayList<>(r.bands.get(i));
                Collections.shuffle(band, RANDOM);
                for (Block b : band) {
                    int slot = findSlot(player, b, p.palette);
                    if (slot >= 0) return slot;
                }
                if (step == 0) break; // idx itself only needs one look
            }
        }
        return -1;
    }

    /** First slot holding {@code block} anywhere in the palette's source range, or -1. */
    public static int findSlot(LocalPlayer player, Block block, Palette palette) {
        NonNullList<ItemStack> items = player.getInventory().getNonEquipmentItems();
        int from, to;
        switch (palette.source) {
            case HOTBAR -> { from = 0; to = 9; }
            case INVENTORY -> { from = 9; to = 36; }
            default -> { from = 0; to = 36; }
        }
        to = Math.min(to, items.size());
        for (int slot = from; slot < to; slot++) {
            ItemStack st = items.get(slot);
            if (!st.isEmpty() && st.getItem() instanceof BlockItem bi && bi.getBlock() == block) {
                return slot;
            }
        }
        return -1;
    }

    /** Boundaries with the palette's step-length wobble applied, rolled once per context key. */
    private static double[] wobbled(Prepared p, Ramp r, Object key) {
        double wobble = p.palette.stepWobble;
        if (wobble <= 0 || r.bounds.length == 0) return r.bounds;
        if (!Objects.equals(key, wobbleKey) || !java.util.Arrays.equals(wobbleBase, r.bounds)
                || wobble != wobbleStrength) {
            wobbleBounds = new double[r.bounds.length];
            for (int k = 0; k < r.bounds.length; k++) {
                double lo = k == 0 ? 0.0 : r.bounds[k - 1];
                double hi = k == r.bounds.length - 1 ? 1.0 : r.bounds[k + 1];
                double room = Math.min(r.bounds[k] - lo, hi - r.bounds[k]);
                double off = RANDOM.nextDouble() < wobble
                        ? (RANDOM.nextDouble() - 0.5) * room : 0.0;
                wobbleBounds[k] = r.bounds[k] + off;
            }
            wobbleKey = key;
            wobbleBase = r.bounds;
            wobbleStrength = wobble;
        }
        return wobbleBounds;
    }

    private static long noiseSeedLong(Palette pal) {
        String s = pal.noiseSeed == null ? "" : pal.noiseSeed.trim();
        if (s.isEmpty()) return 0L;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return s.hashCode();
        }
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
