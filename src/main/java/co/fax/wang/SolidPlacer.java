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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Solid paint's two placement modes. Both start from the block face under the crosshair when
 * right-click is first pressed, and both place via {@link BlockPlacement} (multiplayer-safe).
 *
 * <p><b>Extrude</b>: builds straight columns in the direction of the clicked face, one layer per
 * interval while held, until a column is obstructed, hits an end marker, or leaves reach. If the
 * clicked column intersects a start marker behind it (the marker itself, or anywhere down the
 * axis — so re-clicking a half-built wall still counts), every start marker connected to that one
 * in the same plane perpendicular to the direction extrudes together, each column continuing from
 * its own first air cell. Scattered end markers stop only the column that runs into them.
 *
 * <p><b>3D Fill</b>: a connected fill (through air, face-adjacent) that grows outward from the
 * clicked face — a tap places the first shell, holding grows it layer by layer. Start markers are
 * cubes whose constraint extends from all six faces: a cell is "in the marker space" when it lies
 * on an axis line through a marker (within the marker-distance setting). A fill that starts inside
 * the space never leaves it, and one that starts outside never enters it; end markers are physical
 * blocks, so the fill stops at them naturally.
 *
 * <p>The placed block comes from the Solid tab's match mode: the ✓ block, the exact clicked block,
 * or the closest colour/brightness match in the source slots. Excluded blocks are never placed —
 * exact-matching an excluded block flashes a warning above the hotbar instead.
 */
public final class SolidPlacer {

    private SolidPlacer() {}

    private static final double REACH = 6.0;
    private static final int PLACE_PER_TICK = 8;   // rate limit, matches the noise fill
    private static final int LAYER_INTERVAL = 2;   // ticks between extrude layers while held
    private static final int GROW_INTERVAL = 3;    // ticks between fill growth steps while held
    private static final int MAX_RADIUS = 8;       // fill cap — beyond reach anyway
    private static final int MAX_TRIES = 64;       // per-cell retries waiting for a support block
    private static final int SCAN_LIMIT = 64;      // marker / first-air search distance along a column

    private static boolean lastUseDown = false;

    private static Block chosen;              // block resolved from the match mode at press time
    private static Direction dir;             // face normal of the initial click

    // Extrude state: each column advances its own front (they can start at different heights).
    private static final class Column {
        final BlockPos base;
        int next;
        Column(BlockPos base, int next) { this.base = base; this.next = next; }
    }
    private static final List<Column> columns = new ArrayList<>();
    private static int layerCooldown;

    // 3D Fill state.
    private static BlockPos center;
    private static int radius;
    private static int growCooldown;
    private static boolean spaceConstrained;  // any start markers exist
    private static boolean startedInSpace;    // whether the fill began inside the marker space
    private static final Set<BlockPos> visited = new HashSet<>(); // cells accepted by the fill

    private record Pending(BlockPos cell, int tries) {}
    private static final ArrayDeque<Pending> queue = new ArrayDeque<>();

    // ---- input (client tick) --------------------------------------------------------------------

    public static void tick(Minecraft mc) {
        GradientConfig cfg = ConfigManager.get();
        ToolMode mode = Gradient.currentMode(mc);
        if (mc.player == null || mc.level == null || mc.gui.screen() != null
                || cfg.activePaintType != PaintType.SOLID
                || (mode != ToolMode.EXTRUDE && mode != ToolMode.FLOOD3D)) {
            reset();
            lastUseDown = false;
            return;
        }
        boolean down = mc.options.keyUse.isDown();
        if (down && !lastUseDown) start(mc, mode);
        if (down && chosen != null) {
            if (mode == ToolMode.EXTRUDE) tickExtrude(mc);
            else tickFill(mc);
        } else if (!down && lastUseDown) {
            reset(); // release ends the operation; the next press starts fresh
        }
        lastUseDown = down;
    }

    // ---- start ------------------------------------------------------------------------------------

    private static void start(Minecraft mc, ToolMode mode) {
        reset();
        LocalPlayer player = mc.player;
        HitResult hr = mc.hitResult;
        if (!(hr instanceof BlockHitResult bhr) || hr.getType() != HitResult.Type.BLOCK) {
            player.sendOverlayMessage(Component.literal("Solid: aim at a block face"));
            return;
        }
        BlockPos clicked = bhr.getBlockPos();
        dir = bhr.getDirection();
        Block ref = mc.level.getBlockState(clicked).getBlock();
        chosen = chooseBlock(player, ref);
        if (chosen == null) {
            player.sendOverlayMessage(Component.literal(noBlockMessage(ref)));
            return;
        }

        if (mode == ToolMode.EXTRUDE) {
            layerCooldown = LAYER_INTERVAL;
            // The clicked column counts as marker-driven if a start marker sits anywhere behind the
            // face along its axis — so continuing a half-built wall still extrudes the whole face.
            BlockPos seed = findMarkerBehind(clicked, dir);
            List<BlockPos> bases = (seed != null) ? planeMarkers(seed, dir) : List.of(clicked);
            for (BlockPos base : bases) {
                int first = firstAirOffset(mc, base);
                if (first > 0) columns.add(new Column(base, first));
            }
            enqueueLayer(mc);
        } else {
            center = clicked.relative(dir);
            if (!mc.level.getBlockState(center).isAir()) {
                player.sendOverlayMessage(Component.literal("Solid: no space to fill there"));
                chosen = null;
                return;
            }
            radius = 0;
            growCooldown = GROW_INTERVAL;
            spaceConstrained = !MarkerManager.startMarkers.isEmpty();
            startedInSpace = spaceConstrained && inMarkerSpace(center);
            visited.add(center);
            queue.add(new Pending(center, 0));
            grow(mc);
        }
        drain(mc);
    }

    /** The first start marker on the clicked column's axis, behind the face (or the block itself). */
    private static BlockPos findMarkerBehind(BlockPos clicked, Direction dir) {
        for (int k = 0; k <= SCAN_LIMIT; k++) {
            BlockPos p = clicked.relative(dir.getOpposite(), k);
            if (MarkerManager.startMarkers.contains(p)) return p;
        }
        return null;
    }

    /** Offset (≥1) of the first air cell out from {@code base} along the direction, or -1. */
    private static int firstAirOffset(Minecraft mc, BlockPos base) {
        for (int k = 1; k <= SCAN_LIMIT; k++) {
            if (mc.level.getBlockState(base.relative(dir, k)).isAir()) return k;
        }
        return -1;
    }

    /**
     * The start markers connected to {@code seed} (touching, directly or transitively — diagonals
     * count) that lie in the same plane perpendicular to {@code dir}. Markers at a different
     * height/offset along the direction axis are not part of the shared face and are left out.
     */
    private static List<BlockPos> planeMarkers(BlockPos seed, Direction dir) {
        Direction.Axis axis = dir.getAxis();
        int plane = seed.get(axis);
        Set<BlockPos> inPlane = new HashSet<>();
        for (BlockPos p : MarkerManager.startMarkers) {
            if (p.get(axis) == plane) inPlane.add(p);
        }
        List<BlockPos> out = new ArrayList<>();
        ArrayDeque<BlockPos> bfs = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        bfs.add(seed);
        seen.add(seed);
        while (!bfs.isEmpty()) {
            BlockPos p = bfs.poll();
            out.add(p);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos n = p.offset(dx, dy, dz);
                        if (!seen.contains(n) && inPlane.contains(n)) {
                            seen.add(n);
                            bfs.add(n);
                        }
                    }
                }
            }
        }
        return out;
    }

    // ---- extrude ----------------------------------------------------------------------------------

    private static void tickExtrude(Minecraft mc) {
        drain(mc);
        if (!queue.isEmpty() || columns.isEmpty()) return;
        if (--layerCooldown > 0) return;
        layerCooldown = LAYER_INTERVAL;
        enqueueLayer(mc);
        drain(mc);
    }

    /** Advance every surviving column one cell; obstruction, an end marker, or lost reach kills it. */
    private static void enqueueLayer(Minecraft mc) {
        Iterator<Column> it = columns.iterator();
        while (it.hasNext()) {
            Column c = it.next();
            BlockPos cell = c.base.relative(dir, c.next);
            // End markers are physical blocks, so the air check stops a column at them too.
            if (!mc.level.getBlockState(cell).isAir() || outOfReach(mc, cell)) {
                it.remove();
                continue;
            }
            queue.add(new Pending(cell, 0));
            c.next++;
        }
    }

    // ---- 3d fill ----------------------------------------------------------------------------------

    private static void tickFill(Minecraft mc) {
        drain(mc);
        if (--growCooldown > 0) return;
        growCooldown = GROW_INTERVAL;
        if (radius < MAX_RADIUS) grow(mc);
    }

    /**
     * Grow the fill one layer: expand the connected region (through air, face-adjacent — so it can
     * never leak through walls or around marker boundaries) out to the new radius, closest cells
     * first so outer ones have something to place against.
     */
    private static void grow(Minecraft mc) {
        radius++;
        int r2 = radius * radius;
        ArrayDeque<BlockPos> bfs = new ArrayDeque<>(visited);
        List<BlockPos> fresh = new ArrayList<>();
        while (!bfs.isEmpty()) {
            BlockPos p = bfs.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (visited.contains(n)) continue;
                if (n.distSqr(center) > r2) continue;
                if (!mc.level.getBlockState(n).isAir()) continue; // walls + end markers block the fill
                if (spaceConstrained && inMarkerSpace(n) != startedInSpace) continue;
                if (outOfReach(mc, n)) continue;
                visited.add(n);
                fresh.add(n);
                bfs.add(n);
            }
        }
        fresh.sort(Comparator.comparingDouble(c -> c.distSqr(center)));
        for (BlockPos cell : fresh) {
            queue.add(new Pending(cell, 0));
        }
    }

    /**
     * A start marker is a cube whose constraining space extends out from all six faces: a cell is
     * in the marker space when it lies on an axis line through any start marker, within the
     * marker-distance setting. A fill that starts inside stays inside; one that starts outside
     * never enters.
     */
    private static boolean inMarkerSpace(BlockPos cell) {
        int maxDist = Math.max(1, ConfigManager.get().maxMarkerDistance);
        for (BlockPos m : MarkerManager.startMarkers) {
            int same = (cell.getX() == m.getX() ? 1 : 0)
                    + (cell.getY() == m.getY() ? 1 : 0)
                    + (cell.getZ() == m.getZ() ? 1 : 0);
            if (same < 2) continue;
            // Two coordinates match, so the Manhattan distance is the offset along the free axis.
            int dist = Math.abs(cell.getX() - m.getX()) + Math.abs(cell.getY() - m.getY())
                    + Math.abs(cell.getZ() - m.getZ());
            if (dist <= maxDist) return true;
        }
        return false;
    }

    // ---- placement --------------------------------------------------------------------------------

    /** Place queued cells, a few per tick; a cell with no solid neighbour yet is retried later. */
    private static void drain(Minecraft mc) {
        LocalPlayer player = mc.player;
        int placed = 0;
        int budget = queue.size(); // one pass — retries wait for the next tick
        while (!queue.isEmpty() && placed < PLACE_PER_TICK && budget-- > 0) {
            Pending p = queue.poll();
            if (!mc.level.getBlockState(p.cell()).isAir()) continue;
            BlockPos support = findSupport(mc, p.cell());
            if (support == null) {
                if (p.tries() + 1 < MAX_TRIES) queue.add(new Pending(p.cell(), p.tries() + 1));
                continue;
            }
            int slot = slotOf(player, chosen);
            if (slot < 0) {
                player.sendOverlayMessage(Component.literal(
                        "Solid: out of " + new ItemStack(chosen.asItem()).getHoverName().getString()));
                reset();
                return;
            }
            Direction face = Direction.getNearest(
                    p.cell().getX() - support.getX(), p.cell().getY() - support.getY(),
                    p.cell().getZ() - support.getZ(), Direction.UP);
            BlockPlacement.place(mc, player, slot, support, face);
            placed++;
        }
    }

    private static BlockPos findSupport(Minecraft mc, BlockPos cell) {
        for (Direction d : Direction.values()) {
            BlockPos n = cell.relative(d);
            if (!mc.level.getBlockState(n).isAir()) return n;
        }
        return null;
    }

    private static boolean outOfReach(Minecraft mc, BlockPos cell) {
        return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(cell)) > REACH * REACH;
    }

    // ---- block choice -----------------------------------------------------------------------------

    /** Resolve the block to place from the match mode, or null (the caller flashes a message). */
    private static Block chooseBlock(LocalPlayer player, Block ref) {
        GradientConfig cfg = ConfigManager.get();
        return switch (cfg.solidMatch) {
            case SELECTED -> Gradient.blockOfItemId(cfg.solidBlock);
            case EXACT -> (ref != null && !ref.defaultBlockState().isAir() && !isExcluded(ref)) ? ref : null;
            case CLOSEST_COLOR -> closest(player, ref, false);
            case CLOSEST_BRIGHTNESS -> closest(player, ref, true);
        };
    }

    private static String noBlockMessage(Block ref) {
        return switch (ConfigManager.get().solidMatch) {
            case SELECTED -> "Solid: no ✓ block — pick one in settings (K)";
            case EXACT -> isExcluded(ref)
                    ? "Solid: " + new ItemStack(ref.asItem()).getHoverName().getString() + " is excluded"
                    : "Solid: can't match that block";
            case CLOSEST_COLOR, CLOSEST_BRIGHTNESS -> "Solid: no placeable blocks in source";
        };
    }

    private static boolean isExcluded(Block b) {
        if (b == null) return false;
        Identifier id = BuiltInRegistries.ITEM.getKey(b.asItem());
        return id != null && ConfigManager.get().solidExcludedBlocks.contains(id.toString());
    }

    /** The non-excluded source block closest to {@code ref} by average texture colour or brightness. */
    private static Block closest(LocalPlayer player, Block ref, boolean byBrightness) {
        if (ref == null) return null;
        int refRgb = BlockTextures.gradientValue(ref, null, GradientMode.COLOR, 0.5);
        double refLum = TextureStats.luminance(refRgb);
        Block best = null;
        double bestD = Double.MAX_VALUE;
        Set<Block> seen = new HashSet<>();
        for (Block b : sourceBlocks(player)) {
            if (!seen.add(b) || isExcluded(b)) continue;
            int rgb = BlockTextures.gradientValue(b, null, GradientMode.COLOR, 0.5);
            double d = byBrightness ? Math.abs(TextureStats.luminance(rgb) - refLum) : rgbDist(rgb, refRgb);
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    private static double rgbDist(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return Math.sqrt((double) dr * dr + dg * dg + db * db);
    }

    private static List<Block> sourceBlocks(LocalPlayer player) {
        NonNullList<ItemStack> items = player.getInventory().getNonEquipmentItems();
        int from, to;
        switch (ConfigManager.get().source) {
            case HOTBAR -> { from = 0; to = 9; }
            case INVENTORY -> { from = 9; to = 36; }
            default -> { from = 0; to = 36; }
        }
        to = Math.min(to, items.size());
        List<Block> out = new ArrayList<>();
        for (int slot = from; slot < to; slot++) {
            if (items.get(slot).getItem() instanceof BlockItem bi && !bi.getBlock().defaultBlockState().isAir()) {
                out.add(bi.getBlock());
            }
        }
        return out;
    }

    /** Source slot currently holding {@code block}, or -1. Re-searched per placement (stacks deplete). */
    private static int slotOf(LocalPlayer player, Block block) {
        NonNullList<ItemStack> items = player.getInventory().getNonEquipmentItems();
        int from, to;
        switch (ConfigManager.get().source) {
            case HOTBAR -> { from = 0; to = 9; }
            case INVENTORY -> { from = 9; to = 36; }
            default -> { from = 0; to = 36; }
        }
        to = Math.min(to, items.size());
        for (int slot = from; slot < to; slot++) {
            ItemStack st = items.get(slot);
            if (!st.isEmpty() && st.getItem() instanceof BlockItem bi && bi.getBlock() == block) return slot;
        }
        return -1;
    }

    // ---- state ------------------------------------------------------------------------------------

    private static void reset() {
        chosen = null;
        dir = null;
        columns.clear();
        center = null;
        radius = 0;
        spaceConstrained = false;
        startedInSpace = false;
        visited.clear();
        queue.clear();
    }
}
