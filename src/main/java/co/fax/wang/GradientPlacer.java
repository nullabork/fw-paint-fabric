package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Place-mode logic: right-click fills the next block of the colour/brightness gradient running
 * between a start and end marker.
 *
 * <p>Placement is done the client-side, multiplayer-safe way (as Litematica does): the chosen block
 * is selected into the hand and a normal "use item on the adjacent block's face" interaction is
 * sent, so the server validates and places it. A block therefore needs an existing neighbour to
 * place against — the fill chains outward from the markers (which are real blocks), one block per
 * right-click, always extending from the start side toward the end.
 *
 * <p>Blocks come from the configured Source (hotbar, main inventory, or both). A hotbar block is
 * placed by selecting its slot; a main-inventory block is swapped into the held hotbar slot for the
 * placement and swapped back afterwards.
 */
public final class GradientPlacer {

    private GradientPlacer() {}

    private static final double REACH = 6.0;
    private static final double STEP = 0.1;
    private static final int PLACE_INTERVAL = 2; // ticks between placements while holding (~10/s)

    private static final Random RANDOM = new Random();

    private static boolean lastUseDown = false;
    private static int cooldown = 0;

    // Step-length wobble: randomised band boundaries, kept stable for the whole fill of a segment
    // so its steps stay coherent. Regenerated when the segment, step count, or slider changes.
    private static Segment wobbleSeg;
    private static int wobbleCount;
    private static double wobbleStrength;
    private static double[] wobbleBounds; // ascending boundaries in curved-t space, length count-1

    /** True while we are issuing our own placement interaction (so the click-cancel callbacks pass). */
    public static boolean isPlacing() {
        return BlockPlacement.isPlacing();
    }

    // ---- input (client tick) --------------------------------------------------------------------

    public static void tick(Minecraft mc) {
        // Only Gradient paint in PLACE mode fills gradients (Noise paint has its own placer).
        if (mc.player == null || mc.level == null || mc.gui.screen() != null
                || ConfigManager.get().activePaintType != PaintType.GRADIENT
                || Gradient.currentMode(mc) != ToolMode.PLACE) {
            lastUseDown = false;
            cooldown = 0;
            return;
        }
        boolean down = mc.options.keyUse.isDown();
        if (down) {
            if (!lastUseDown) {
                placeOnce(mc, true); // initial press: place now and report any problem
                cooldown = PLACE_INTERVAL;
            } else if (--cooldown <= 0) {
                placeOnce(mc, false); // hold-to-fill: place silently on the cooldown
                cooldown = PLACE_INTERVAL;
            }
        } else {
            cooldown = 0;
        }
        lastUseDown = down;
    }

    /**
     * Place one gradient block. Returns false if nothing could be placed; {@code announce} controls
     * whether the reason is flashed to the action bar (true on the initial click, false while held
     * so repeats don't spam once the line fills up).
     */
    private static boolean placeOnce(Minecraft mc, boolean announce) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;

        Segment seg = raycastSegment(mc, level);
        if (seg == null) {
            if (announce) player.sendOverlayMessage(Component.literal("Gradient: no marker line in view"));
            return false;
        }
        Front front = chainFront(seg, level);
        if (front == null) {
            if (announce) player.sendOverlayMessage(Component.literal("Gradient: line is full"));
            return false;
        }

        // Gradient endpoints: the real blocks at the markers, or — when "From: Block list" — the
        // [S]/[E] endpoints from the settings screen, so placement matches the preview exactly.
        GradientConfig cfg = ConfigManager.get();
        Block startBlock = level.getBlockState(seg.s()).getBlock();
        Block endBlock = level.getBlockState(seg.e()).getBlock();
        if (!cfg.gradientFromMarkers && !cfg.gradientMode.isPick()) {
            Block s = Gradient.blockOfItemId(cfg.orderStartBlock);
            Block e = Gradient.blockOfItemId(cfg.orderEndBlock);
            if (s == null || e == null) {
                if (announce) player.sendOverlayMessage(Component.literal(
                        "Gradient: no [S]/[E] in the block list — open settings (K) or switch to From: Markers"));
                return false;
            }
            startBlock = s;
            endBlock = e;
        }

        // The start block is the reference for diff-based gradient modes.
        List<Palette> palette = sourcePalette(player, startBlock);
        if (palette.isEmpty()) {
            if (announce) player.sendOverlayMessage(Component.literal("Gradient: no placeable blocks in source"));
            return false;
        }

        int startRgb = rgbOf(startBlock, startBlock);
        int endRgb = rgbOf(endBlock, startBlock);
        Palette chosen = pick(palette, cfg, startRgb, endRgb, front.t(), seg);
        if (chosen == null) { // Pick mode with no numbered blocks
            if (announce) player.sendOverlayMessage(Component.literal("Gradient: number some blocks (Pick mode)"));
            return false;
        }
        place(mc, player, chosen, front);
        return true;
    }

    // ---- targeting ------------------------------------------------------------------------------

    /** Step the look ray and return the first start→end segment any cell along it belongs to. */
    private static Segment raycastSegment(Minecraft mc, Level level) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0f);
        BlockPos last = null;
        for (double d = 0; d <= REACH; d += STEP) {
            BlockPos cell = BlockPos.containing(eye.x + look.x * d, eye.y + look.y * d, eye.z + look.z * d);
            if (cell.equals(last)) continue;
            last = cell;
            Segment seg = segmentForCell(cell);
            if (seg != null) return seg;
        }
        return null;
    }

    /** A segment is a colinear start→end pair (within the max marker distance) that {@code cell} sits between. */
    private static Segment segmentForCell(BlockPos cell) {
        for (BlockPos e : MarkerManager.endMarkers) {
            for (BlockPos s : MarkerManager.startMarkers) {
                int dx = e.getX() - s.getX(), dy = e.getY() - s.getY(), dz = e.getZ() - s.getZ();
                int axes = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
                if (axes != 1) continue; // must be a single-axis line
                int length = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                if (length > Math.max(1, ConfigManager.get().maxMarkerDistance)) continue;
                int ux = Integer.signum(dx), uy = Integer.signum(dy), uz = Integer.signum(dz);
                int i = segIndex(s, ux, uy, uz, cell);
                if (i > 0 && i < length) return new Segment(s, e, ux, uy, uz, length);
            }
        }
        return null;
    }

    /** Index of {@code cell} along the line from s (0) in direction (ux,uy,uz), or -1 if off-line. */
    private static int segIndex(BlockPos s, int ux, int uy, int uz, BlockPos cell) {
        if (ux != 0) {
            if (cell.getY() != s.getY() || cell.getZ() != s.getZ()) return -1;
            return (cell.getX() - s.getX()) * ux;
        }
        if (uy != 0) {
            if (cell.getX() != s.getX() || cell.getZ() != s.getZ()) return -1;
            return (cell.getY() - s.getY()) * uy;
        }
        if (cell.getX() != s.getX() || cell.getY() != s.getY()) return -1;
        return (cell.getZ() - s.getZ()) * uz;
    }

    /** First air cell whose start-side neighbour is solid — the next block to place, with its face. */
    private static Front chainFront(Segment seg, Level level) {
        for (int i = 1; i < seg.length(); i++) {
            BlockPos c = seg.s().offset(seg.ux() * i, seg.uy() * i, seg.uz() * i);
            if (!level.getBlockState(c).isAir()) continue;
            BlockPos n = seg.s().offset(seg.ux() * (i - 1), seg.uy() * (i - 1), seg.uz() * (i - 1));
            if (level.getBlockState(n).isAir()) continue; // need a solid block to place against
            Direction dir = Direction.getNearest(
                    c.getX() - n.getX(), c.getY() - n.getY(), c.getZ() - n.getZ(), Direction.UP);
            // The markers themselves are the gradient's first and last blocks (t=0 and t=1) — the
            // fill only covers the cells strictly between them, so the first fill cell doesn't
            // duplicate the start block against the marker.
            double t = (double) i / seg.length();
            return new Front(c, n, t, dir);
        }
        return null;
    }

    // ---- palette + gradient selection -----------------------------------------------------------

    /** Candidate blocks from the configured source, valued relative to {@code startBlock}. */
    private static List<Palette> sourcePalette(LocalPlayer player, Block startBlock) {
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
            int rgb = rgbOf(b, startBlock);
            palette.add(new Palette(slot, b, rgb));
        }
        return palette;
    }

    /**
     * Choose a block for a fill cell at gradient fraction {@code t}. The gradient always builds the
     * fullest ramp available (endpoint range + the fixed sanity budget + max steps — the Variation
     * slider never adds/removes steps), distributes it evenly along the fill, then picks randomly
     * within the step's band group (its block + similar blocks per the Variation slider). At
     * variation 0 the placement is fully deterministic; Chaos, when set, still nudges the step.
     */
    private static Palette pick(List<Palette> palette, GradientConfig cfg,
                                int startRgb, int endRgb, double t, Segment seg) {
        if (cfg.gradientMode.isPick()) return pickModeChoice(palette, cfg, t, seg);
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
        List<int[]> groups = GradientRamp.bandGroups(rgbs, steps, eligible, cfg.gradientMode, cfg.deviationBudget);

        double tc = cfg.curve.apply(t);
        // Chaos = how often a placement deviates (repeat the previous step or skip ahead one), but
        // never the start/end cells (t == 0 or 1), so the gradient still begins and ends correctly.
        if (cfg.chaos > 0 && t > 0.0 && t < 1.0 && RANDOM.nextDouble() < cfg.chaos) {
            double stepFrac = steps.length > 1 ? 1.0 / (steps.length - 1) : 0.1;
            tc = clamp01(RANDOM.nextBoolean() ? tc - stepFrac : tc + stepFrac);
        }

        int[] group = groups.get(stepIndex(cfg, seg, steps.length, tc));
        return palette.get(group[RANDOM.nextInt(group.length)]);
    }

    /**
     * Step index for a curved fraction, with the step-length wobble applied. Wobble shifts the
     * boundary between two steps (by up to half a band), so making one step longer automatically
     * makes its neighbour shorter — the gradient still starts and ends on time. Boundaries are
     * rolled once per segment/step-count so a fill stays coherent while you hold the button.
     */
    private static int stepIndex(GradientConfig cfg, Segment seg, int count, double tc) {
        if (cfg.stepWobble <= 0 || count <= 1) return GradientRamp.rampIndex(count, tc);
        if (!seg.equals(wobbleSeg) || count != wobbleCount || cfg.stepWobble != wobbleStrength) {
            double band = 1.0 / count;
            wobbleBounds = new double[count - 1];
            for (int k = 0; k < count - 1; k++) {
                double off = RANDOM.nextDouble() < cfg.stepWobble
                        ? (RANDOM.nextDouble() - 0.5) * band : 0.0;
                wobbleBounds[k] = (k + 1) * band + off;
            }
            wobbleSeg = seg;
            wobbleCount = count;
            wobbleStrength = cfg.stepWobble;
        }
        int pos = 0;
        while (pos < wobbleBounds.length && tc >= wobbleBounds[pos]) pos++;
        return pos;
    }

    /**
     * Pick mode: distribute exactly the user-numbered blocks (low→high) across the line — no gradient
     * computed, max-steps/deviation ignored. Chaos still nudges the step; ties are randomised. Returns
     * null if no numbered block is available.
     */
    private static Palette pickModeChoice(List<Palette> palette, GradientConfig cfg, double t, Segment seg) {
        // Group palette entries by their pick number (low→high).
        java.util.TreeMap<Integer, List<Palette>> byNumber = new java.util.TreeMap<>();
        for (Palette p : palette) {
            Identifier id = BuiltInRegistries.ITEM.getKey(p.block().asItem());
            int num = id == null ? 0 : cfg.pickNumbers.getOrDefault(id.toString(), 0);
            if (num > 0) byNumber.computeIfAbsent(num, k -> new ArrayList<>()).add(p);
        }
        if (byNumber.isEmpty()) return null;
        List<List<Palette>> groups = new ArrayList<>(byNumber.values());

        double tc = cfg.curve.apply(t);
        if (cfg.chaos > 0 && t > 0.0 && t < 1.0 && RANDOM.nextDouble() < cfg.chaos) {
            double stepFrac = groups.size() > 1 ? 1.0 / (groups.size() - 1) : 0.1;
            tc = clamp01(RANDOM.nextBoolean() ? tc - stepFrac : tc + stepFrac);
        }
        List<Palette> group = groups.get(stepIndex(cfg, seg, groups.size(), tc));
        return group.get(RANDOM.nextInt(group.size())); // randomise ties
    }

    // ---- placement ------------------------------------------------------------------------------

    private static void place(Minecraft mc, LocalPlayer player, Palette pal, Front front) {
        BlockPlacement.place(mc, player, pal.slot(), front.support(), front.dir());
    }

    // ---- colour helpers -------------------------------------------------------------------------

    private static int rgbOf(net.minecraft.world.level.block.Block block,
                            net.minecraft.world.level.block.Block startBlock) {
        GradientConfig cfg = ConfigManager.get();
        return BlockTextures.gradientValue(block, startBlock, cfg.gradientMode, cfg.pixelPercent);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    // ---- records --------------------------------------------------------------------------------

    private record Segment(BlockPos s, BlockPos e, int ux, int uy, int uz, int length) {}

    private record Front(BlockPos cell, BlockPos support, double t, Direction dir) {}

    private record Palette(int slot, Block block, int rgb) {}
}
