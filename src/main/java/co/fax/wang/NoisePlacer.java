package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Noise tool placement: right-click flood-fills the air you're aiming at, bounded by the marker
 * region (the markers are only the boundary — they don't set colours). Each filled cell's block is
 * chosen by {@link Noise} sampled at its world xyz, mapped onto the noise tab's block ordering.
 * Placement is queued and rate-limited across ticks so a big fill doesn't spam packets.
 */
public final class NoisePlacer {

    private NoisePlacer() {}

    private static final int PLACE_PER_TICK = 8;
    private static final int MAX_FILL = 8192;
    private static final double REACH = 6.0;

    private static final Random RANDOM = new Random();
    private static boolean lastUseDown = false;
    private static final ArrayDeque<FloodFill.Cell> queue = new ArrayDeque<>();
    private static List<Block> order = new ArrayList<>();

    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gui.screen() != null
                || Gradient.heldTool(mc) != Gradient.HeldTool.NOISE
                || ConfigManager.get().noiseToolMode != ToolMode.PLACE) {
            lastUseDown = false;
            queue.clear();
            return;
        }
        boolean down = mc.options.keyUse.isDown();
        if (down && !lastUseDown) startFill(mc);
        lastUseDown = down;
        drain(mc);
    }

    /** True while we're issuing our own placement (so the click-cancel callbacks let it through). */
    public static boolean isPlacing() {
        return BlockPlacement.isPlacing();
    }

    // ---- start a fill ---------------------------------------------------------------------------

    private static void startFill(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (MarkerManager.startMarkers.isEmpty() || MarkerManager.endMarkers.isEmpty()) {
            player.sendOverlayMessage(Component.literal("Noise: place start + end markers to bound the fill"));
            return;
        }
        order = computeOrder(player);
        if (order.isEmpty()) {
            player.sendOverlayMessage(Component.literal("Noise: no placeable blocks in source"));
            return;
        }
        // A cell is fillable only if it lies between a colinear start→end marker pair (a row/column
        // bracketed by start at one end and end at the other). Adjacent segments let the flood spread;
        // gaps stop it (you must re-point past a gap).
        int maxDist = Math.max(1, ConfigManager.get().maxMarkerDistance);
        FloodFill.Region region = (x, y, z) -> inMarkedSegment(x, y, z, maxDist);
        FloodFill.AirTest air = (x, y, z) -> mc.level.getBlockState(new BlockPos(x, y, z)).isAir();

        int[][] seed = raycastSeed(mc, region, air);
        if (seed == null) {
            player.sendOverlayMessage(Component.literal("Noise: aim at an empty spot inside the markers"));
            return;
        }
        List<FloodFill.Cell> cells = FloodFill.compute(seed[0], seed[1], air, region, MAX_FILL);
        queue.clear();
        queue.addAll(cells);
        player.sendOverlayMessage(Component.literal("Noise: filling " + cells.size() + " blocks"));
    }

    /** True if (x,y,z) lies strictly between some colinear start→end marker pair within maxDist. */
    private static boolean inMarkedSegment(int x, int y, int z, int maxDist) {
        for (BlockPos s : MarkerManager.startMarkers) {
            for (BlockPos e : MarkerManager.endMarkers) {
                if (betweenColinear(s.getX(), s.getY(), s.getZ(), e.getX(), e.getY(), e.getZ(), x, y, z, maxDist)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether (cx,cy,cz) is strictly between a colinear (single-axis) start/end pair within maxDist. */
    static boolean betweenColinear(int sx, int sy, int sz, int ex, int ey, int ez,
                                   int cx, int cy, int cz, int maxDist) {
        int dx = ex - sx, dy = ey - sy, dz = ez - sz;
        int axes = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
        if (axes != 1) return false; // start and end must share a single axis
        int len = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
        if (len > maxDist) return false;
        if (dx != 0) {
            if (cy != sy || cz != sz) return false;
            int i = (cx - sx) * Integer.signum(dx);
            return i > 0 && i < len;
        }
        if (dy != 0) {
            if (cx != sx || cz != sz) return false;
            int i = (cy - sy) * Integer.signum(dy);
            return i > 0 && i < len;
        }
        if (cx != sx || cy != sy) return false;
        int i = (cz - sz) * Integer.signum(dz);
        return i > 0 && i < len;
    }

    /** First air cell along the look ray that is in the region and has a solid neighbour to build on. */
    private static int[][] raycastSeed(Minecraft mc, FloodFill.Region region, FloodFill.AirTest air) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0f);
        BlockPos last = null;
        for (double d = 0; d <= REACH; d += 0.1) {
            BlockPos c = BlockPos.containing(eye.x + look.x * d, eye.y + look.y * d, eye.z + look.z * d);
            if (c.equals(last)) continue;
            last = c;
            if (!region.contains(c.getX(), c.getY(), c.getZ()) || !air.isAir(c.getX(), c.getY(), c.getZ())) continue;
            for (Direction dir : Direction.values()) {
                BlockPos n = c.relative(dir);
                if (!mc.level.getBlockState(n).isAir()) {
                    return new int[][]{{c.getX(), c.getY(), c.getZ()}, {n.getX(), n.getY(), n.getZ()}};
                }
            }
        }
        return null;
    }

    // ---- drain the queue ------------------------------------------------------------------------

    private static void drain(Minecraft mc) {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        GradientConfig cfg = ConfigManager.get();
        long seed = noiseSeedLong(cfg);
        int placed = 0;
        while (!queue.isEmpty() && placed < PLACE_PER_TICK) {
            FloodFill.Cell c = queue.poll();
            BlockPos pos = new BlockPos(c.x(), c.y(), c.z());
            if (!level.getBlockState(pos).isAir()) continue; // already filled or obstructed
            BlockPos support = new BlockPos(c.sx(), c.sy(), c.sz());
            if (level.getBlockState(support).isAir()) continue; // support not solid (skipped/dropped)

            double t = Noise.sample(cfg.noiseType, c.x(), c.y(), c.z(), seed,
                    cfg.noiseScaleX, cfg.noiseScaleY, cfg.noiseScaleZ);
            if (cfg.noiseChaos > 0 && RANDOM.nextDouble() < cfg.noiseChaos) {
                double stepFrac = order.size() > 1 ? 1.0 / (order.size() - 1) : 0.1;
                t = clamp01(RANDOM.nextBoolean() ? t - stepFrac : t + stepFrac);
            }
            int idx = GradientRamp.rampIndex(order.size(), t);
            // Deviation: a per-placement chance to shift to a colour-adjacent block (±1 in the order),
            // so a step occasionally varies to a close block without changing the step count.
            if (cfg.noiseDeviation > 0 && RANDOM.nextDouble() < cfg.noiseDeviation) {
                idx = Math.max(0, Math.min(order.size() - 1, idx + (RANDOM.nextBoolean() ? 1 : -1)));
            }

            int slot = slotForNearest(player, idx);
            if (slot < 0) continue; // none of the gradient blocks are available
            Direction dir = Direction.getNearest(c.x() - c.sx(), c.y() - c.sy(), c.z() - c.sz(), Direction.UP);
            BlockPlacement.place(mc, player, slot, support, dir);
            placed++;
        }
    }

    /** Slot for the order block at {@code idx}, falling back to the nearest available index. */
    private static int slotForNearest(LocalPlayer player, int idx) {
        for (int step = 0; step < order.size(); step++) {
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                int i = idx + sgn * step;
                if (i < 0 || i >= order.size()) continue;
                int slot = BlockPlacement.findSlot(player, order.get(i));
                if (slot >= 0) return slot;
                if (step == 0) break; // avoid checking idx twice
            }
        }
        return -1;
    }

    // ---- ordering (valley→peak), mirrors the noise tab preview -----------------------------------

    private static List<Block> computeOrder(LocalPlayer player) {
        GradientConfig cfg = ConfigManager.get();
        List<Block> palette = gatherPalette(player);
        if (palette.isEmpty()) return List.of();

        Block startBlock = byId(cfg.orderStartBlock, palette);
        Block endBlock = byId(cfg.orderEndBlock, palette);
        if (startBlock == null || endBlock == null) {
            Block lightest = null, darkest = null;
            double lo = Double.MAX_VALUE, hi = -1;
            for (Block b : palette) {
                double l = BlockTextures.baseLuminance(b);
                if (l > hi) { hi = l; lightest = b; }
                if (l < lo) { lo = l; darkest = b; }
            }
            if (startBlock == null) startBlock = lightest;
            if (endBlock == null) endBlock = darkest;
        }

        GradientMode mode = cfg.noiseGradientMode;
        double pct = cfg.noisePixelPercent;
        int startRgb = BlockTextures.gradientValue(startBlock, startBlock, mode, pct);
        int endRgb = BlockTextures.gradientValue(endBlock, startBlock, mode, pct);
        int[] rgbs = new int[palette.size()];
        Set<Integer> forced = new HashSet<>();
        List<String> required = cfg.requiredBlocks;
        for (int i = 0; i < palette.size(); i++) {
            rgbs[i] = BlockTextures.gradientValue(palette.get(i), startBlock, mode, pct);
            Identifier id = BuiltInRegistries.ITEM.getKey(palette.get(i).asItem());
            if (id != null && required.contains(id.toString())) forced.add(i);
        }
        // Full budget: noise deviation is a placement-time variation, not a step-count filter, so the
        // number of steps depends only on max steps + the eligible blocks — not the deviation slider.
        int[] ord = GradientRamp.subsample(
                GradientRamp.gradientOrder(rgbs, startRgb, endRgb, mode, 1.0, forced), cfg.noiseMaxSteps);
        List<Block> out = new ArrayList<>();
        for (int i : ord) out.add(palette.get(i));
        return out;
    }

    private static List<Block> gatherPalette(LocalPlayer player) {
        NonNullList<ItemStack> items = player.getInventory().getNonEquipmentItems();
        List<String> excluded = ConfigManager.get().excludedBlocks;
        int from, to;
        switch (ConfigManager.get().source) {
            case HOTBAR -> { from = 0; to = 9; }
            case INVENTORY -> { from = 9; to = 36; }
            default -> { from = 0; to = 36; }
        }
        to = Math.min(to, items.size());
        List<Block> out = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        for (int slot = from; slot < to; slot++) {
            ItemStack st = items.get(slot);
            if (!(st.getItem() instanceof BlockItem bi)) continue;
            Block b = bi.getBlock();
            if (b.defaultBlockState().isAir() || !seen.add(b)) continue;
            Identifier id = BuiltInRegistries.ITEM.getKey(st.getItem());
            if (id != null && excluded.contains(id.toString())) continue;
            out.add(b);
        }
        return out;
    }

    private static Block byId(String id, List<Block> palette) {
        if (id == null || id.isEmpty()) return null;
        for (Block b : palette) {
            Identifier bid = BuiltInRegistries.ITEM.getKey(b.asItem());
            if (bid != null && bid.toString().equals(id)) return b;
        }
        return null;
    }

    private static long noiseSeedLong(GradientConfig cfg) {
        String s = cfg.noiseSeed.trim();
        if (s.isEmpty()) return 0L;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return s.hashCode(); }
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
