package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
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
 * The unified placer: every paint type (Solid / Gradient / Noise) through every placing mode
 * (Single / Face / 3D Fill), inside or outside markers. All placement goes through
 * {@link BlockPlacement} (multiplayer-safe use-block interactions), queued and rate-limited.
 *
 * <p><b>Single</b>: one column out of the clicked face, resuming from its first air gap.
 * <b>Face</b>: the clicked face plus every interconnected, reachable block face on the same plane
 * (or, with a start marker behind the clicked column, the connected coplanar marker group) —
 * all columns extrude together. <b>3D Fill</b>: a connected blob growing out of the clicked face.
 * Everywhere, end markers stop a column/fill even when they sit in air, and marker space
 * constrains 3D fills (start inside → stay inside; start outside → stay outside).
 *
 * <p>Blocks are chosen per cell: Solid uses its match mode; Noise samples the noise field; the
 * Gradient uses the marker segment the cell sits on (stretched start→end, per-side picker
 * fallback when a marker floats in air) or, outside markers, the picker gradient advanced one
 * block per step with progress remembered in {@link GradientCaches}.
 */
public final class PaintPlacer {

    private PaintPlacer() {}

    private static final double REACH = 6.0;
    private static final int PLACE_PER_TICK = 8;   // rate limit across all modes
    private static final int LAYER_INTERVAL = 2;   // ticks between column layers while held
    private static final int GROW_INTERVAL = 3;    // ticks between 3D growth steps while held
    private static final int HOLD_DELAY = 4;       // ~200ms after the first layer before the hold
                                                   // repeats — just enough time to release for one
    private static final int MAX_RADIUS = 8;       // 3D fill cap — beyond reach anyway
    private static final int MAX_TRIES = 64;       // per-cell retries waiting for a support block
    private static final int SCAN_LIMIT = 64;      // marker / first-air search distance along a column
    private static final int MAX_FACES = 256;      // face-flood safety cap
    private static final int MAX_FILL = 8192;      // in-marker noise region fill cap

    private static final int GREEN_FILL = 0x5555FF55;  // translucent green — placement face preview
    private static final int GREEN_ARROW = 0xFF55FF55;

    private static boolean lastUseDown = false;

    // ---- press state ------------------------------------------------------------------------------

    private static boolean active;            // a press resolved into something to place
    private static boolean regionFill;        // in-marker noise flood: finishes even after release
    private static PaintType type;
    private static PlacementMode mode;
    private static Direction dir;             // face normal of the initial click
    private static Block solidChosen;         // Solid: block resolved from the match mode at press
    private static List<List<Block>> noiseOrder = List.of();
    private static Block pickerStart, pickerEnd; // Gradient: resolved picker endpoints
    private static int outsideSteps;             // Gradient: picker ramp step count
    private static GradientCaches.Fill3D activeFill; // Gradient 3D: the cache entry being extended

    // Column state (Single + Face): each column advances its own front.
    private static final class Column {
        final BlockPos base;
        int next;      // offset of the column's front (first air) from base
        int progress;  // outside-marker gradient: next step index to place

        Column(BlockPos base, int next, int progress) {
            this.base = base;
            this.next = next;
            this.progress = progress;
        }
    }

    private static final List<Column> columns = new ArrayList<>();
    private static boolean markerDriven; // Face flood came from the start-marker plane
    private static int layerCooldown;

    // 3D fill state.
    private static BlockPos center;
    private static int radius;
    private static int maxRadius;
    private static int growCooldown;
    private static boolean spaceConstrained;  // any start markers exist
    private static boolean startedInSpace;    // whether the fill began inside the marker space
    private static final Set<BlockPos> visited = new HashSet<>(); // cells accepted by the fill

    /** Gradient context of a queued cell (null for Solid/Noise). segS/segE null → picker endpoint. */
    private record GradCtx(double t, Object wobbleKey, BlockPos segS, BlockPos segE, int step) {}

    private record Pending(BlockPos cell, GradCtx g, int tries) {}

    private static final ArrayDeque<Pending> queue = new ArrayDeque<>();

    /** Identity of an outside-marker column, for wobble coherence. */
    private record ColKey(BlockPos base, Direction dir) {}

    // Preview + HUD sourcing, recomputed every tick while a placing mode is live.
    private static final List<BlockPos> previewPos = new ArrayList<>();
    private static final List<Direction> previewDir = new ArrayList<>();
    private static List<String> sourcing = List.of();

    /** Extra HUD lines describing where the next click's gradient endpoints come from. */
    public static List<String> sourcingLines() {
        return sourcing;
    }

    // ---- input (client tick) ----------------------------------------------------------------------

    public static void tick(Minecraft mc) {
        GradientConfig cfg = ConfigManager.get();
        PlacementMode pm = Gradient.currentPlacement(mc);
        boolean live = mc.player != null && mc.level != null && mc.gui.screen() == null && pm.places();
        if (!live) {
            reset();
            lastUseDown = false;
            previewPos.clear();
            previewDir.clear();
            sourcing = List.of();
            return;
        }
        updatePreviewAndSourcing(mc, cfg, pm);

        boolean down = mc.options.keyUse.isDown();
        if (down && !lastUseDown) start(mc, cfg, pm);
        if (active && down) {
            if (mode == PlacementMode.FILL3D && !regionFill) tickFill(mc);
            else if (mode != PlacementMode.FILL3D) tickColumns(mc);
        } else if (!down && lastUseDown && !regionFill) {
            reset(); // release ends the operation; the next press starts fresh
        }
        if (!queue.isEmpty()) drain(mc);
        if (regionFill && queue.isEmpty()) reset(); // a region fill finishes on its own
        lastUseDown = down;
    }

    // ---- start ------------------------------------------------------------------------------------

    private static void start(Minecraft mc, GradientConfig cfg, PlacementMode pm) {
        reset();
        type = cfg.activePaintType;
        mode = pm;
        GradientCaches.touch(cfg); // fingerprint change / idle timeout → forget old gradients

        // Noise 3D aimed into a marked region keeps the classic instant region flood-fill.
        if (type == PaintType.NOISE && mode == PlacementMode.FILL3D && tryNoiseRegionFill(mc)) {
            return;
        }

        LocalPlayer player = mc.player;
        HitResult hr = mc.hitResult;
        if (!(hr instanceof BlockHitResult bhr) || hr.getType() != HitResult.Type.BLOCK) {
            overlay(mc, type.label() + ": aim at a block face");
            return;
        }
        BlockPos clicked = bhr.getBlockPos();
        dir = bhr.getDirection();

        if (!prepareChoice(mc, player, clicked)) return;

        if (mode == PlacementMode.FILL3D) startFill3D(mc, clicked);
        else startColumns(mc, clicked);
        if (active) drain(mc);
    }

    /** Resolve the press-wide block choice state; flashes a message and returns false on failure. */
    private static boolean prepareChoice(Minecraft mc, LocalPlayer player, BlockPos clicked) {
        switch (type) {
            case SOLID -> {
                Block ref = mc.level.getBlockState(clicked).getBlock();
                solidChosen = chooseSolidBlock(player, ref);
                if (solidChosen == null) {
                    overlay(mc, noSolidBlockMessage(ref));
                    return false;
                }
            }
            case NOISE -> {
                noiseOrder = NoisePlacer.computeOrder(player);
                if (noiseOrder.isEmpty()) {
                    overlay(mc, "Noise: no placeable blocks in source");
                    return false;
                }
            }
            case GRADIENT -> {
                GradientChoice.Endpoints eps = GradientChoice.pickerEndpoints(player);
                if (eps == null) {
                    overlay(mc, "Gradient: no placeable blocks in source");
                    return false;
                }
                pickerStart = eps.start();
                pickerEnd = eps.end();
                outsideSteps = GradientChoice.stepCount(player, eps);
                if (outsideSteps <= 0) {
                    overlay(mc, ConfigManager.get().gradientMode.isPick()
                            ? "Gradient: number some blocks (Pick mode)"
                            : "Gradient: no usable gradient steps");
                    return false;
                }
            }
        }
        return true;
    }

    // ---- columns (Single + Face) --------------------------------------------------------------------

    private static void startColumns(Minecraft mc, BlockPos clicked) {
        List<BlockPos> bases;
        markerDriven = false;
        if (mode == PlacementMode.FACE) {
            BlockPos seed = findMarkerBehind(clicked, dir);
            if (seed != null) {
                bases = planeMarkers(seed, dir);
                markerDriven = true;
            } else {
                bases = floodFaces(mc, clicked, dir);
            }
        } else {
            bases = List.of(clicked);
        }
        layerCooldown = HOLD_DELAY; // a beat after layer one, so a tap can stay one layer
        for (BlockPos base : bases) {
            int first = firstAirOffset(mc, base, dir);
            if (first <= 0) continue;
            int progress = 0;
            if (type == PaintType.GRADIENT) {
                progress = GradientCaches.columnProgress(mode, base.relative(dir, first - 1), dir);
            }
            columns.add(new Column(base, first, progress));
        }
        active = !columns.isEmpty();
        if (active) enqueueLayer(mc);
    }

    private static void tickColumns(Minecraft mc) {
        if (columns.isEmpty()) return;
        if (!queue.isEmpty()) return; // let the current layer land before advancing
        if (--layerCooldown > 0) return;
        layerCooldown = LAYER_INTERVAL;
        enqueueLayer(mc);
    }

    /**
     * Advance columns one cell; obstruction, an end marker, lost reach, or (gradient, outside
     * markers) a finished gradient kills a column. With "fill voids first" on, an in-marker face
     * fill advances only the most-behind columns until every front is level.
     */
    private static void enqueueLayer(Minecraft mc) {
        boolean level = markerDriven && mode == PlacementMode.FACE && ConfigManager.get().faceFillVoids;
        int minNext = Integer.MAX_VALUE;
        if (level) {
            // Fill-voids only levels what the player can actually fill: a void column whose front
            // is out of reach must not gate every other column (it can't be placed anyway).
            for (Column c : columns) {
                if (outOfReach(mc, c.base.relative(dir, c.next))) continue;
                minNext = Math.min(minNext, c.next);
            }
        }
        Iterator<Column> it = columns.iterator();
        while (it.hasNext()) {
            Column c = it.next();
            if (level && c.next > minNext) continue; // waits for the lower columns to catch up
            BlockPos cell = c.base.relative(dir, c.next);
            // An end marker bounds the column even in air; a marker on a block is also caught by
            // the air check like any obstruction.
            if (MarkerManager.endMarkers.contains(cell)
                    || !mc.level.getBlockState(cell).isAir() || outOfReach(mc, cell)) {
                it.remove();
                continue;
            }
            GradCtx g = null;
            if (type == PaintType.GRADIENT) {
                g = gradCtxFor(cell, c);
                if (g == null) { // this column's gradient is complete
                    it.remove();
                    continue;
                }
            }
            queue.add(new Pending(cell, g, 0));
            c.next++;
        }
    }

    /** Gradient context for a column cell: marker segment t, or picker progress via the cache. */
    private static GradCtx gradCtxFor(BlockPos cell, Column c) {
        GradientConfig cfg = ConfigManager.get();
        Seg seg = segmentForCell(cell);
        if (seg != null) {
            // Between markers: stretch start→end. Endpoints resolve at placement time (per side:
            // the marker's real block, or the picker block when the marker is air / From: Block list).
            boolean fromMarkers = cfg.gradientFromMarkers;
            double t = (double) seg.index() / seg.length();
            Object key = List.of(seg.s(), seg.e());
            return new GradCtx(t, key, fromMarkers ? seg.s() : null, fromMarkers ? seg.e() : null, -1);
        }
        if (c.progress >= outsideSteps) return null;
        double t = outsideSteps <= 1 ? 0.0 : (double) c.progress / (outsideSteps - 1);
        GradCtx g = new GradCtx(t, new ColKey(c.base, dir), null, null, c.progress);
        c.progress++;
        return g;
    }

    /**
     * Outside markers, Face mode: every interconnected, reachable block on the clicked face's
     * plane whose face is exposed. Spreads over the 8 in-plane neighbours; a block that is
     * already a step ahead (its face isn't on this plane any more) is left out.
     */
    private static List<BlockPos> floodFaces(Minecraft mc, BlockPos clicked, Direction dir) {
        Direction.Axis axis = dir.getAxis();
        // The two axes spanning the face plane.
        int ux = axis == Direction.Axis.X ? 0 : 1, uy = axis == Direction.Axis.X ? 1 : 0;
        int vy = axis == Direction.Axis.Z ? 1 : 0, vz = axis == Direction.Axis.Z ? 0 : 1;
        if (axis == Direction.Axis.Y) { vy = 0; vz = 1; }

        List<BlockPos> out = new ArrayList<>();
        ArrayDeque<BlockPos> bfs = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        bfs.add(clicked);
        seen.add(clicked);
        while (!bfs.isEmpty() && out.size() < MAX_FACES) {
            BlockPos p = bfs.poll();
            out.add(p);
            for (int du = -1; du <= 1; du++) {
                for (int dv = -1; dv <= 1; dv++) {
                    if (du == 0 && dv == 0) continue;
                    BlockPos n = p.offset(du * ux, du * uy + dv * vy, dv * vz);
                    if (seen.contains(n)) continue;
                    if (mc.level.getBlockState(n).isAir()) continue;          // needs a block to grow from
                    BlockPos front = n.relative(dir);
                    if (!mc.level.getBlockState(front).isAir()) continue;     // face not exposed on this plane
                    if (MarkerManager.endMarkers.contains(front)) continue;   // marker right on the face
                    if (outOfReach(mc, front)) continue;
                    seen.add(n);
                    bfs.add(n);
                }
            }
        }
        return out;
    }

    // ---- 3D fill ------------------------------------------------------------------------------------

    private static void startFill3D(Minecraft mc, BlockPos clicked) {
        BlockPos seed = clicked.relative(dir);
        center = seed;
        maxRadius = MAX_RADIUS;
        if (type == PaintType.GRADIENT) {
            // Clicking a block that belongs to a cached fill continues it from its original
            // centre; any other block starts a new fill (and a new cache entry).
            GradientCaches.Fill3D f = GradientCaches.fillContaining(clicked);
            if (f != null) center = f.center;
            else f = GradientCaches.newFill(seed);
            activeFill = f;
            maxRadius = Math.max(1, Math.min(16, outsideSteps));
        }
        if (!mc.level.getBlockState(seed).isAir()) {
            overlay(mc, type.label() + ": no space to fill there");
            return;
        }
        growCooldown = HOLD_DELAY; // a beat after the first shell, so a tap can stay one shell
        spaceConstrained = !MarkerManager.startMarkers.isEmpty();
        startedInSpace = spaceConstrained && inMarkerSpace(seed);
        visited.add(center);
        if (!seed.equals(center)) visited.add(seed); // resuming: grow outward from the click too
        radius = (int) Math.ceil(Math.sqrt(seed.distSqr(center)));
        if (mc.level.getBlockState(seed).isAir()) {
            queue.add(new Pending(seed, gradCtx3d(seed), 0));
        }
        active = true;
        grow(mc);
    }

    private static void tickFill(Minecraft mc) {
        if (--growCooldown > 0) return;
        growCooldown = GROW_INTERVAL;
        if (radius < maxRadius) grow(mc);
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
                if (!mc.level.getBlockState(n).isAir()) continue; // walls block the fill
                if (MarkerManager.endMarkers.contains(n)) continue; // end markers bound it even in air
                if (spaceConstrained && inMarkerSpace(n) != startedInSpace) continue;
                if (outOfReach(mc, n)) continue;
                visited.add(n);
                fresh.add(n);
                bfs.add(n);
            }
        }
        fresh.sort(Comparator.comparingDouble(c -> c.distSqr(center)));
        for (BlockPos cell : fresh) {
            queue.add(new Pending(cell, gradCtx3d(cell), 0));
        }
    }

    /** 3D gradient: t = distance from the fill centre over the step count (start inside, end out). */
    private static GradCtx gradCtx3d(BlockPos cell) {
        if (type != PaintType.GRADIENT) return null;
        double t = Math.min(1.0, Math.sqrt(cell.distSqr(center)) / Math.max(1, maxRadius));
        return new GradCtx(t, center, null, null, -1);
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

    // ---- in-marker noise region fill ----------------------------------------------------------------

    /** The classic noise fill: flood the marked region the crosshair points into, all at once. */
    private static boolean tryNoiseRegionFill(Minecraft mc) {
        if (MarkerManager.startMarkers.isEmpty() || MarkerManager.endMarkers.isEmpty()) return false;
        int maxDist = Math.max(1, ConfigManager.get().maxMarkerDistance);
        FloodFill.Region region = (x, y, z) -> NoisePlacer.inMarkedSegment(x, y, z, maxDist);
        FloodFill.AirTest air = (x, y, z) -> mc.level.getBlockState(new BlockPos(x, y, z)).isAir();
        int[][] seed = NoisePlacer.raycastSeed(mc, region, air);
        if (seed == null) return false; // not aimed into a region — fall through to the blob fill

        noiseOrder = NoisePlacer.computeOrder(mc.player);
        if (noiseOrder.isEmpty()) {
            overlay(mc, "Noise: no placeable blocks in source");
            return true; // handled (with a message) — don't fall through
        }
        List<FloodFill.Cell> cells = FloodFill.compute(seed[0], seed[1], air, region, MAX_FILL);
        for (FloodFill.Cell c : cells) {
            queue.add(new Pending(new BlockPos(c.x(), c.y(), c.z()), null, 0));
        }
        regionFill = true;
        active = true;
        overlay(mc, "Noise: filling " + cells.size() + " blocks");
        return true;
    }

    // ---- placement ----------------------------------------------------------------------------------

    /** Place queued cells, a few per tick; a cell with no solid neighbour yet is retried later. */
    private static void drain(Minecraft mc) {
        LocalPlayer player = mc.player;
        GradientConfig cfg = ConfigManager.get();
        int placed = 0;
        int budget = queue.size(); // one pass — retries wait for the next tick
        while (!queue.isEmpty() && placed < PLACE_PER_TICK && budget-- > 0) {
            Pending p = queue.poll();
            if (!mc.level.getBlockState(p.cell()).isAir()) continue;
            BlockPos support = findSupport(mc, p.cell());
            if (support == null) {
                if (p.tries() + 1 < MAX_TRIES) queue.add(new Pending(p.cell(), p.g(), p.tries() + 1));
                continue;
            }
            int slot;
            switch (type) {
                case SOLID -> {
                    slot = slotOf(player, solidChosen);
                    if (slot < 0) {
                        overlay(mc, "Solid: out of "
                                + new ItemStack(solidChosen.asItem()).getHoverName().getString());
                        reset();
                        return;
                    }
                }
                case NOISE -> slot = NoisePlacer.slotForCell(player, noiseOrder,
                        p.cell().getX(), p.cell().getY(), p.cell().getZ());
                case GRADIENT -> slot = gradientSlot(mc, player, cfg, p.g());
                default -> slot = -1;
            }
            if (slot < 0) continue;
            Direction face = Direction.getNearest(
                    p.cell().getX() - support.getX(), p.cell().getY() - support.getY(),
                    p.cell().getZ() - support.getZ(), Direction.UP);
            BlockPlacement.place(mc, player, slot, support, face);
            placed++;
            if (type == PaintType.GRADIENT && p.g() != null) {
                if (p.g().step() >= 0) GradientCaches.recordColumn(mode, p.cell(), p.g().step(), dir);
                else if (activeFill != null) GradientCaches.recordFill(activeFill, p.cell());
            }
        }
    }

    /** Resolve the gradient block for a queued cell (endpoints per side: marker block or picker). */
    private static int gradientSlot(Minecraft mc, LocalPlayer player, GradientConfig cfg, GradCtx g) {
        if (g == null) return -1;
        Block startB = endpointBlock(mc, g.segS(), pickerStart);
        Block endB = endpointBlock(mc, g.segE(), pickerEnd);
        if (startB == null || endB == null) return -1;
        List<GradientChoice.Palette> palette = GradientChoice.sourcePalette(player, startB);
        if (palette.isEmpty()) return -1;
        int startRgb = GradientChoice.rgbOf(startB, startB);
        int endRgb = GradientChoice.rgbOf(endB, startB);
        GradientChoice.Palette pal = GradientChoice.pick(palette, cfg, startRgb, endRgb, g.t(), g.wobbleKey());
        return pal == null ? -1 : pal.slot();
    }

    /** The real block at a marker cell, or the picker fallback when it's air (or no marker side). */
    private static Block endpointBlock(Minecraft mc, BlockPos markerPos, Block picker) {
        if (markerPos == null) return picker;
        var state = mc.level.getBlockState(markerPos);
        return state.isAir() ? picker : state.getBlock();
    }

    // ---- marker segments ------------------------------------------------------------------------

    /** A colinear start→end pair {@code cell} sits strictly between, with its position along it. */
    private record Seg(BlockPos s, BlockPos e, int index, int length) {}

    private static Seg segmentForCell(BlockPos cell) {
        int maxDist = Math.max(1, ConfigManager.get().maxMarkerDistance);
        for (BlockPos e : MarkerManager.endMarkers) {
            for (BlockPos s : MarkerManager.startMarkers) {
                int dx = e.getX() - s.getX(), dy = e.getY() - s.getY(), dz = e.getZ() - s.getZ();
                int axes = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
                if (axes != 1) continue; // must be a single-axis line
                int length = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                if (length > maxDist) continue;
                int i = segIndex(s, Integer.signum(dx), Integer.signum(dy), Integer.signum(dz), cell);
                if (i > 0 && i < length) return new Seg(s, e, i, length);
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

    // ---- preview + HUD sourcing -------------------------------------------------------------------

    /**
     * Recompute the green face preview (what the next click would extrude, and which way) and the
     * gradient sourcing lines for the HUD. Runs every tick while a placing mode is live.
     */
    private static void updatePreviewAndSourcing(Minecraft mc, GradientConfig cfg, PlacementMode pm) {
        // While the button is held, the selection is locked to what the click resolved — the
        // preview must not wander with the crosshair. Column faces advance with each column's
        // front; a 3D fill keeps its press-time faces; sourcing lines stay as at press.
        if (active && mc.options.keyUse.isDown()) {
            if (mode == PlacementMode.SINGLE || mode == PlacementMode.FACE) {
                previewPos.clear();
                previewDir.clear();
                for (Column c : columns) addFrontPreview(mc, c.base, dir);
            }
            return;
        }
        previewPos.clear();
        previewDir.clear();
        List<String> src = new ArrayList<>();
        boolean pickerAlways = cfg.activePaintType == PaintType.NOISE
                || (cfg.activePaintType == PaintType.GRADIENT && pm == PlacementMode.FILL3D);
        if (pickerAlways) src.add("Selected from picker");

        // Paint stays at normal block reach (the crosshair hit) — only markers target further.
        BlockHitResult hit = (mc.hitResult instanceof BlockHitResult bhr
                && mc.hitResult.getType() == HitResult.Type.BLOCK) ? bhr : null;
        if (hit != null) {
            BlockPos b = hit.getBlockPos();
            Direction d = hit.getDirection();
            switch (pm) {
                case SINGLE -> addFrontPreview(mc, b, d);
                case FACE -> {
                    BlockPos seed = findMarkerBehind(b, d);
                    List<BlockPos> bases = (seed != null) ? planeMarkers(seed, d) : floodFaces(mc, b, d);
                    for (BlockPos base : bases) addFrontPreview(mc, base, d);
                }
                case FILL3D -> {
                    for (Direction dd : Direction.values()) {
                        if (mc.level.getBlockState(b.relative(dd)).isAir()) {
                            previewPos.add(b);
                            previewDir.add(dd);
                        }
                    }
                }
                default -> { }
            }
            if (cfg.activePaintType == PaintType.GRADIENT
                    && (pm == PlacementMode.SINGLE || pm == PlacementMode.FACE)) {
                src.addAll(gradientSourcingAt(mc, cfg, b, d));
            }
        } else if (cfg.activePaintType == PaintType.GRADIENT
                && (pm == PlacementMode.SINGLE || pm == PlacementMode.FACE)) {
            src.add("Selected from picker");
        }
        sourcing = src;
    }

    /** Preview a column at its current front (the face new blocks will grow out of). */
    private static void addFrontPreview(Minecraft mc, BlockPos base, Direction d) {
        int first = firstAirOffset(mc, base, d);
        if (first <= 0) return;
        previewPos.add(base.relative(d, first - 1));
        previewDir.add(d);
    }

    /** What the aimed column's gradient endpoints would be — the truth behind the HUD lines. */
    private static List<String> gradientSourcingAt(Minecraft mc, GradientConfig cfg, BlockPos b, Direction d) {
        if (!cfg.gradientFromMarkers) return List.of("Selected from picker");
        int first = firstAirOffset(mc, b, d);
        BlockPos cell = b.relative(d, Math.max(1, first));
        Seg seg = segmentForCell(cell);
        if (seg == null) return List.of("Selected from picker");
        boolean sAir = mc.level.getBlockState(seg.s()).isAir();
        boolean eAir = mc.level.getBlockState(seg.e()).isAir();
        if (!sAir && !eAir) return List.of("Selected from markers");
        if (sAir && eAir) return List.of("Selected from picker");
        return sAir
                ? List.of("Start selected from picker", "End selected from marker")
                : List.of("Start selected from marker", "End selected from picker");
    }

    /** Submit the green face tint + direction arrows (registered on COLLECT_SUBMITS). */
    public static void renderPreview(LevelRenderContext ctx) {
        if (previewPos.isEmpty()) return;
        PoseStack ps = new PoseStack();
        SubmitNodeCollector col = ctx.submitNodeCollector();
        Vec3 cam = ctx.levelState().cameraRenderState.pos;
        for (int i = 0; i < previewPos.size(); i++) {
            FaceOverlay.submit(col, ps, cam, previewPos.get(i), previewDir.get(i), GREEN_FILL, GREEN_ARROW);
        }
    }

    // ---- shared column helpers ----------------------------------------------------------------------

    /** The first start marker on the clicked column's axis, behind the face (or the block itself). */
    private static BlockPos findMarkerBehind(BlockPos clicked, Direction dir) {
        for (int k = 0; k <= SCAN_LIMIT; k++) {
            BlockPos p = clicked.relative(dir.getOpposite(), k);
            if (MarkerManager.startMarkers.contains(p)) return p;
        }
        return null;
    }

    /**
     * Offset (≥1) of the first air cell out from {@code base} along {@code dir}, or -1. An end
     * marker on the way means the column already reached its end — re-clicking the face must not
     * resume it on the far side of the marker, so a crossed column doesn't seed at all.
     */
    private static int firstAirOffset(Minecraft mc, BlockPos base, Direction dir) {
        for (int k = 1; k <= SCAN_LIMIT; k++) {
            BlockPos p = base.relative(dir, k);
            if (MarkerManager.endMarkers.contains(p)) return -1;
            if (mc.level.getBlockState(p).isAir()) return k;
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

    private static void overlay(Minecraft mc, String msg) {
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal(msg));
    }

    // ---- solid block choice ---------------------------------------------------------------------

    /** Resolve the block to place from the match mode, or null (the caller flashes a message). */
    private static Block chooseSolidBlock(LocalPlayer player, Block ref) {
        GradientConfig cfg = ConfigManager.get();
        return switch (cfg.solidMatch) {
            case SELECTED -> Gradient.blockOfItemId(cfg.solidBlock);
            case EXACT -> (ref != null && !ref.defaultBlockState().isAir() && !isSolidExcluded(ref)) ? ref : null;
            case CLOSEST_COLOR -> closest(player, ref, false);
            case CLOSEST_BRIGHTNESS -> closest(player, ref, true);
        };
    }

    private static String noSolidBlockMessage(Block ref) {
        return switch (ConfigManager.get().solidMatch) {
            case SELECTED -> "Solid: no ✓ block — pick one in settings (K)";
            case EXACT -> isSolidExcluded(ref)
                    ? "Solid: " + new ItemStack(ref.asItem()).getHoverName().getString() + " is excluded"
                    : "Solid: can't match that block";
            case CLOSEST_COLOR, CLOSEST_BRIGHTNESS -> "Solid: no placeable blocks in source";
        };
    }

    private static boolean isSolidExcluded(Block b) {
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
        for (Block b : solidSourceBlocks(player)) {
            if (!seen.add(b) || isSolidExcluded(b)) continue;
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

    private static List<Block> solidSourceBlocks(LocalPlayer player) {
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

    // ---- state --------------------------------------------------------------------------------------

    private static void reset() {
        active = false;
        regionFill = false;
        solidChosen = null;
        noiseOrder = List.of();
        pickerStart = null;
        pickerEnd = null;
        outsideSteps = 0;
        activeFill = null;
        dir = null;
        columns.clear();
        markerDriven = false;
        center = null;
        radius = 0;
        maxRadius = MAX_RADIUS;
        spaceConstrained = false;
        startedInSpace = false;
        visited.clear();
        queue.clear();
    }
}
