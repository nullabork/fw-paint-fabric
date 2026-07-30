package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import co.fax.wang.palette.PatternMath;
import com.mojang.blaze3d.vertex.PoseStack;
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
 * all columns extrude together. <b>Face perp</b>: a 1-block-wide run through the clicked block
 * crossing the player's look — perpendicular to it, snapped to the configured increment
 * (45° = stair-stepped diagonals); inside markers only the marked blocks on the run are selected.
 * <b>3D Fill</b>: a connected blob growing out of the clicked face.
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
    private static PaletteChoice.Prepared prepared; // Gradient/Noise: press-wide palette state
    private static PaletteChoice.Ramp noiseRamp;    // Noise: the one ramp resolved for this press
    private static PaletteChoice.Ramp ramp3d;       // Gradient 3D: the fill's resolved ramp
    private static GradientCaches.Fill3D activeFill; // Gradient 3D: the cache entry being extended
    private static PatternChoice.Prepared patternPrep;              // Pattern: press-wide state
    private static GradientCaches.PatternPlacement patternPlace;    // Pattern: the plane in use

    // Column state (Single + Face): each column advances its own front.
    private static final class Column {
        final BlockPos base;
        int next;      // offset of the column's front (first air) from base
        int progress;  // outside-marker gradient: next cell index to place
        PaletteChoice.Ramp ramp; // outside-marker gradient: this column's resolved ramp
        int cells;               // outside-marker gradient: column length per the sizing mode

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

    /** Gradient context of a queued cell (null for Solid/Noise): its ramp + fill fraction. */
    private record GradCtx(double t, Object wobbleKey, PaletteChoice.Ramp ramp, int step) {}

    /** Per-press ramps for marker segments (a null value = resolved to "can't", stays failed). */
    private static final java.util.Map<List<BlockPos>, PaletteChoice.Ramp> segRamps = new java.util.HashMap<>();

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
                // Noise needs its whole colour range up front (cells are picked by noise value,
                // not progression): resolve one ramp per press. Automatic-at-end scans along the
                // clicked column; 3D requires a static end (enforced by prepare).
                prepared = PaletteChoice.prepare(player, "Noise", mode == PlacementMode.FILL3D);
                if (prepared.error != null) {
                    overlay(mc, prepared.error);
                    return false;
                }
                Block startAnchor = anchorAt(mc, clicked);
                Block endAnchor = null;
                if (mode != PlacementMode.FILL3D) {
                    int first = firstAirOffset(mc, clicked, dir);
                    if (first > 0) endAnchor = scanEnd(mc, clicked, dir, first).colour();
                }
                noiseRamp = PaletteChoice.resolveRamp(prepared, startAnchor, endAnchor);
                if (noiseRamp == null) {
                    overlay(mc, cantResolveMessage());
                    return false;
                }
            }
            case GRADIENT -> {
                prepared = PaletteChoice.prepare(player, "Gradient", mode == PlacementMode.FILL3D);
                if (prepared.error != null) {
                    overlay(mc, prepared.error);
                    return false;
                }
                // Ramps resolve per column / marker segment / fill — anchors differ per context.
            }
            case PATTERN -> {
                patternPrep = PatternChoice.prepare(player);
                if (patternPrep.error != null) {
                    overlay(mc, patternPrep.error);
                    return false;
                }
                resolvePatternPlane(mc, player, clicked, ConfigManager.get());
            }
        }
        return true;
    }

    /**
     * Fix the pattern plane for this press: continue a cached placement the click touches
     * (so adjacent strokes line up), or anchor a new plane at the clicked column's front —
     * width axis from the player's facing snapped to the configured increment. A completed
     * no-wrap pattern under the click re-anchors a fresh plane on top (like a finished
     * gradient restarting).
     */
    private static void resolvePatternPlane(Minecraft mc, LocalPlayer player,
                                            BlockPos clicked, GradientConfig cfg) {
        int first = mode == PlacementMode.FILL3D ? 1
                : Math.max(1, firstAirOffset(mc, clicked, dir));
        BlockPos front = clicked.relative(dir, first);
        GradientCaches.PatternPlacement near = GradientCaches.patternNear(clicked);
        if (near == null) near = GradientCaches.patternNear(front);
        if (near != null) {
            // Completed (no start/end wrap) pattern right here → fresh pattern stacked on top.
            int[] d = {front.getX() - near.origin.getX(), front.getY() - near.origin.getY(),
                    front.getZ() - near.origin.getZ()};
            int v = PatternMath.vOf(d, new int[]{near.extrusion.getStepX(),
                    near.extrusion.getStepY(), near.extrusion.getStepZ()});
            boolean done = !patternPrep.pattern.tiling.wrapsStartEnd()
                    && near.extrusion == dir && v >= patternPrep.pattern.height;
            if (!done) {
                patternPlace = near;
                return;
            }
        }
        int[] facing = PatternMath.snappedFacingStep(player.getYRot(), cfg.perpSnapDegrees);
        int[] wstep = PatternMath.widthStep(facing,
                new int[]{dir.getStepX(), dir.getStepY(), dir.getStepZ()});
        patternPlace = GradientCaches.newPattern(front, wstep, dir);
    }

    /** The pattern cell id for a world position (see {@link PatternMath#cellFor}). */
    private static String patternCell(BlockPos cell) {
        int[] d = {cell.getX() - patternPlace.origin.getX(),
                cell.getY() - patternPlace.origin.getY(),
                cell.getZ() - patternPlace.origin.getZ()};
        int u = PatternMath.uOf(d, patternPlace.widthStep);
        int v = PatternMath.vOf(d, new int[]{patternPlace.extrusion.getStepX(),
                patternPlace.extrusion.getStepY(), patternPlace.extrusion.getStepZ()});
        return PatternMath.cellFor(patternPrep.pattern, u, v);
    }

    private static String cantResolveMessage() {
        String name = prepared != null && prepared.palette != null ? prepared.palette.name : "palette";
        return "'" + name + "': can't resolve colours — add a block to the palette";
    }

    /** The block at {@code pos} as a colour anchor, or null when it's air. */
    private static Block anchorAt(Minecraft mc, BlockPos pos) {
        var state = mc.level.getBlockState(pos);
        return state.isAir() ? null : state.getBlock();
    }

    /**
     * End scan out from a column front: the bounding cell (end marker — even in air — or first
     * non-air block) and the end colour (the first real block met; a marker floating in air keeps
     * scanning past itself for the colour).
     */
    private record EndScan(int boundary, Block colour) {}

    private static EndScan scanEnd(Minecraft mc, BlockPos base, Direction dir, int firstAir) {
        int boundary = -1;
        for (int k = firstAir; k <= SCAN_LIMIT; k++) {
            BlockPos p = base.relative(dir, k);
            var state = mc.level.getBlockState(p);
            if (boundary < 0 && (MarkerManager.endMarkers.contains(p) || !state.isAir())) {
                boundary = k;
            }
            if (!state.isAir()) return new EndScan(boundary, state.getBlock());
        }
        return new EndScan(boundary, null);
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
        } else if (mode == PlacementMode.FACE_PERP) {
            BlockPos seed = findMarkerBehind(clicked, dir);
            if (seed != null) {
                bases = perpRun(mc, seed, dir, p -> MarkerManager.startMarkers.contains(p));
                markerDriven = true;
            } else {
                bases = perpRun(mc, clicked, dir, p -> !mc.level.getBlockState(p).isAir()
                        && mc.level.getBlockState(p.relative(dir)).isAir()
                        && !MarkerManager.endMarkers.contains(p.relative(dir))
                        && !outOfReach(mc, p.relative(dir)));
            }
        } else {
            bases = List.of(clicked);
        }
        layerCooldown = HOLD_DELAY; // a beat after layer one, so a tap can stay one layer
        boolean unresolved = false;
        for (BlockPos base : bases) {
            int first = firstAirOffset(mc, base, dir);
            if (first <= 0) continue;
            Column col = new Column(base, first, 0);
            if (type == PaintType.GRADIENT) {
                col.progress = GradientCaches.columnProgress(mode, base.relative(dir, first - 1), dir);
                // Resolve this column's ramp: start anchor = the block the column grows from,
                // end anchor = the end-scan colour (marker / first non-air), when there is one.
                Block startAnchor = anchorAt(mc, base.relative(dir, first - 1));
                EndScan scan = scanEnd(mc, base, dir, first);
                col.ramp = PaletteChoice.resolveRamp(prepared, startAnchor, scan.colour());
                if (col.ramp == null) {
                    unresolved = true;
                    continue;
                }
                col.cells = PaletteChoice.columnCells(prepared, col.ramp,
                        scan.boundary() > 0 ? scan.boundary() - first : -1);
                // A finished gradient stays finished in the cache — but a fresh click on it
                // should start a NEW gradient on top, not be blocked by the old one's progress.
                if (col.progress >= col.cells) col.progress = 0;
            }
            columns.add(col);
        }
        active = !columns.isEmpty();
        if (!active && unresolved) overlay(mc, cantResolveMessage());
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
                g = gradCtxFor(mc, cell, c);
                if (g == null) { // this column's gradient is complete (or unresolvable here)
                    it.remove();
                    continue;
                }
            }
            if (type == PaintType.PATTERN) {
                String cellId = patternCell(cell);
                if (cellId == PatternMath.COLUMN_DONE) { // pattern complete, no wrap
                    it.remove();
                    continue;
                }
                if (PatternChoice.cellIsHole(patternPrep, cellId)) {
                    c.next++; // a hole: advance the front without placing anything
                    continue;
                }
            }
            queue.add(new Pending(cell, g, 0));
            c.next++;
        }
    }

    /** Gradient context for a column cell: marker-segment t, or the column's own sized ramp. */
    private static GradCtx gradCtxFor(Minecraft mc, BlockPos cell, Column c) {
        Seg seg = segmentForCell(cell);
        if (seg != null) {
            // Between markers: stretch start→end across the segment. Automatic segments resolve
            // against the real blocks at the marker cells (air marker → that side stays open).
            PaletteChoice.Ramp ramp = segmentRamp(mc, seg);
            if (ramp == null) return null;
            double t = (double) seg.index() / seg.length();
            return new GradCtx(t, List.of(seg.s(), seg.e()), ramp, -1);
        }
        if (c.ramp == null || c.progress >= c.cells) return null;
        double t = c.cells <= 1 ? 0.0 : (double) c.progress / (c.cells - 1);
        GradCtx g = new GradCtx(t, new ColKey(c.base, dir), c.ramp, c.progress);
        c.progress++;
        return g;
    }

    /** Resolve (and per-press cache) the ramp for a marker segment's start/end anchor blocks. */
    private static PaletteChoice.Ramp segmentRamp(Minecraft mc, Seg seg) {
        List<BlockPos> key = List.of(seg.s(), seg.e());
        if (segRamps.containsKey(key)) return segRamps.get(key);
        PaletteChoice.Ramp ramp =
                PaletteChoice.resolveRamp(prepared, anchorAt(mc, seg.s()), anchorAt(mc, seg.e()));
        segRamps.put(key, ramp);
        return ramp;
    }

    /**
     * Face Perpendicular: the 1-block-wide run through {@code seed} in the clicked plane, along
     * the player's look direction projected into that plane and snapped to the configured
     * increment (45° gives stair-stepped diagonal runs). Extends both ways from the seed while
     * {@code valid} accepts each step; the seed itself must pass too.
     */
    private static List<BlockPos> perpRun(Minecraft mc, BlockPos seed, Direction dir,
                                          java.util.function.Predicate<BlockPos> valid) {
        int[] step = perpStep(mc, dir);
        List<BlockPos> out = new ArrayList<>();
        if (valid.test(seed)) out.add(seed);
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            for (int k = 1; k <= MAX_FACES / 2; k++) {
                BlockPos p = seed.offset(step[0] * k * sgn, step[1] * k * sgn, step[2] * k * sgn);
                if (!valid.test(p)) break;
                out.add(p);
            }
        }
        return out;
    }

    /**
     * The in-plane step vector for the perpendicular run: the player's look vector projected
     * into the plane perpendicular to {@code dir}, its angle snapped to the configured
     * increment in that plane's 2D basis. Components are −1/0/+1, so 45° snapping yields
     * diagonal (corner-connected) steps.
     */
    private static int[] perpStep(Minecraft mc, Direction dir) {
        // Plane basis (two axes perpendicular to the face normal).
        int[] e1, e2;
        switch (dir.getAxis()) {
            case Y -> { e1 = new int[]{1, 0, 0}; e2 = new int[]{0, 0, 1}; }
            case X -> { e1 = new int[]{0, 0, 1}; e2 = new int[]{0, 1, 0}; }
            default -> { e1 = new int[]{1, 0, 0}; e2 = new int[]{0, 1, 0}; }
        }
        Vec3 look = mc.player.getViewVector(1.0f);
        double a = look.x * e1[0] + look.y * e1[1] + look.z * e1[2];
        double b = look.x * e2[0] + look.y * e2[1] + look.z * e2[2];
        if (Math.abs(a) < 1e-4 && Math.abs(b) < 1e-4) {
            a = 1; // looking dead-on along the normal — arbitrary in-plane direction
        }
        int snap = ConfigManager.get().perpSnapDegrees == 90 ? 90 : 45;
        double ang = Math.toDegrees(Math.atan2(b, a));
        double snapped = Math.toRadians(Math.round(ang / snap) * (double) snap);
        int c1 = (int) Math.round(Math.cos(snapped));
        int c2 = (int) Math.round(Math.sin(snapped));
        // The run crosses the view: rotate the snapped look 90° in-plane, so the selected line
        // is PERPENDICULAR to where you're looking (like a brush stroke across your vision).
        int r1 = -c2, r2 = c1;
        return new int[]{r1 * e1[0] + r2 * e2[0], r1 * e1[1] + r2 * e2[1], r1 * e1[2] + r2 * e2[2]};
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
            // 3D knows its whole range up front: start anchor = the clicked block; the end is
            // static (prepare enforces it), so no end anchor is needed.
            ramp3d = PaletteChoice.resolveRamp(prepared, anchorAt(mc, clicked), null);
            if (ramp3d == null) {
                overlay(mc, cantResolveMessage());
                return;
            }
            maxRadius = switch (prepared.palette.sizing) {
                case FILL_SPACE -> MAX_RADIUS; // grow until walls/markers stop it
                default -> Math.max(1, Math.min(16, PaletteChoice.columnCells(prepared, ramp3d, -1)));
            };
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
        return new GradCtx(t, center, ramp3d, -1);
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

        // Region fills are 3D: the palette must have a static end. The start anchor is the
        // support block the seed cell rests on.
        prepared = PaletteChoice.prepare(mc.player, "Noise", true);
        if (prepared.error != null) {
            overlay(mc, prepared.error);
            return true; // handled (with a message) — don't fall through
        }
        noiseRamp = PaletteChoice.resolveRamp(prepared,
                anchorAt(mc, new BlockPos(seed[1][0], seed[1][1], seed[1][2])), null);
        if (noiseRamp == null) {
            overlay(mc, cantResolveMessage());
            return true;
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
            boolean ranOut = false;
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
                case NOISE -> {
                    slot = PaletteChoice.noiseSlot(player, prepared, noiseRamp,
                            p.cell().getX(), p.cell().getY(), p.cell().getZ());
                    ranOut = slot < 0;
                }
                case GRADIENT -> {
                    if (p.g() == null || p.g().ramp() == null) {
                        slot = -1;
                    } else {
                        slot = PaletteChoice.pickSlot(player, prepared, p.g().ramp(), p.g().t(), p.g().wobbleKey());
                        ranOut = slot < 0;
                    }
                }
                case PATTERN -> {
                    String cellId = patternCell(p.cell());
                    if (cellId == PatternMath.COLUMN_DONE
                            || PatternChoice.cellIsHole(patternPrep, cellId)) {
                        slot = -1; // outside the pattern / a hole — nothing to place here
                    } else {
                        Block b = PatternChoice.varied(patternPrep, cellId);
                        slot = b == null ? -1 : PatternChoice.slotFor(player, patternPrep, b);
                        ranOut = b != null && slot < 0;
                    }
                }
                default -> slot = -1;
            }
            if (ranOut) {
                // The ramp/pattern is known (and cached) — running out of one of its blocks
                // stops the paint with an error rather than quietly substituting.
                Block missing = type == PaintType.PATTERN
                        ? PatternChoice.lastMissingBlock() : PaletteChoice.lastMissingBlock();
                String name = missing == null ? "a block"
                        : new ItemStack(missing.asItem()).getHoverName().getString();
                String pal = type == PaintType.PATTERN
                        ? (patternPrep != null && patternPrep.pattern != null ? patternPrep.pattern.name : "pattern")
                        : (prepared != null && prepared.palette != null ? prepared.palette.name : "palette");
                overlay(mc, "'" + pal + "': out of " + name);
                reset();
                return;
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
            if (type == PaintType.PATTERN && patternPlace != null) {
                GradientCaches.recordPattern(patternPlace, p.cell());
            }
        }
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
        boolean paletteAlways = cfg.activePaintType == PaintType.NOISE
                || (cfg.activePaintType == PaintType.GRADIENT && pm == PlacementMode.FILL3D);
        if (paletteAlways) src.add("Selected from palette");
        if (cfg.activePaintType == PaintType.PATTERN) {
            co.fax.wang.palette.Palette pat = co.fax.wang.palette.PaletteStore.activePattern();
            src.add(pat == null ? "No pattern selected" : "Pattern: " + pat.name);
        }

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
                case FACE_PERP -> {
                    BlockPos seed = findMarkerBehind(b, d);
                    List<BlockPos> bases = (seed != null)
                            ? perpRun(mc, seed, d, p -> MarkerManager.startMarkers.contains(p))
                            : perpRun(mc, b, d, p -> !mc.level.getBlockState(p).isAir()
                                    && mc.level.getBlockState(p.relative(d)).isAir()
                                    && !MarkerManager.endMarkers.contains(p.relative(d))
                                    && !outOfReach(mc, p.relative(d)));
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
                    && (pm == PlacementMode.SINGLE || pm == PlacementMode.FACE || pm == PlacementMode.FACE_PERP)) {
                src.addAll(gradientSourcingAt(mc, cfg, b, d));
            }
        } else if (cfg.activePaintType == PaintType.GRADIENT
                && (pm == PlacementMode.SINGLE || pm == PlacementMode.FACE || pm == PlacementMode.FACE_PERP)) {
            src.add("Selected from palette");
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

    /** What the aimed column's gradient anchors would be — the truth behind the HUD lines. */
    private static List<String> gradientSourcingAt(Minecraft mc, GradientConfig cfg, BlockPos b, Direction d) {
        int first = firstAirOffset(mc, b, d);
        BlockPos cell = b.relative(d, Math.max(1, first));
        Seg seg = segmentForCell(cell);
        if (seg == null) return List.of("Selected from palette");
        boolean sAir = mc.level.getBlockState(seg.s()).isAir();
        boolean eAir = mc.level.getBlockState(seg.e()).isAir();
        if (!sAir && !eAir) return List.of("Anchored to markers");
        if (sAir && eAir) return List.of("Selected from palette");
        return sAir
                ? List.of("Start from palette", "End anchored to marker")
                : List.of("Start anchored to marker", "End from palette");
    }

    /** Submit the green face tint + direction arrows (each loader's level-render submit hook). */
    public static void renderPreview(SubmitNodeCollector col, Vec3 cam) {
        if (previewPos.isEmpty()) return;
        PoseStack ps = new PoseStack();
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
        double refBright = GradientRamp.brightness(refRgb);
        Block best = null;
        double bestD = Double.MAX_VALUE;
        Set<Block> seen = new HashSet<>();
        for (Block b : solidSourceBlocks(player)) {
            if (!seen.add(b) || isSolidExcluded(b)) continue;
            int rgb = BlockTextures.gradientValue(b, null, GradientMode.COLOR, 0.5);
            double d = byBrightness ? Math.abs(GradientRamp.brightness(rgb) - refBright) : colorDist(rgb, refRgb);
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    /** Colour closeness per the perceptual toggle: Oklab distance, or classic sRGB distance. */
    private static double colorDist(int a, int b) {
        if (GradientRamp.perceptual) return ColorOrder.oklabDist(a, b);
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
        prepared = null;
        noiseRamp = null;
        ramp3d = null;
        segRamps.clear();
        activeFill = null;
        patternPrep = null;
        patternPlace = null;
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
