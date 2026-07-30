package co.fax.wang.palette;

import co.fax.wang.CurveFunction;
import co.fax.wang.GradientSource;
import co.fax.wang.NoiseType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A saved, named preset driving both gradient and noise painting: an ordered list of segments
 * (blocks or Automatic wildcards) plus every per-gradient setting. Persisted by
 * {@link PaletteStore} in {@code fw-paint-palettes.json}; everything here survives sessions —
 * only the player's inventory can differ later, which the UI surfaces as "missing blocks"
 * states without ever mutating the palette.
 */
public final class Palette {

    /** Stable slug id, assigned at creation and never changed by renames. */
    public String id = "";

    /** Display name — unique across the store. */
    public String name = "";

    /** What this item is. Absent in pre-2.1 saves — Gson leaves the GRADIENT default. */
    public PaletteKind kind = PaletteKind.GRADIENT;

    /** Where placement (and Automatic resolution / variation swaps) draws blocks from. */
    public GradientSource source = GradientSource.HOTBAR_AND_INVENTORY;

    /** Segment ordering mode; CUSTOM is reached only by manual reordering in the editor. */
    public PaletteOrder order = PaletteOrder.COLOR_ASC;

    /** Easing curve for the strip; CUSTOM is reached only by dragging a stop. */
    public CurveFunction curve = CurveFunction.LINEAR;

    /**
     * Segment boundary fractions, ascending, size = segments − 1. Meaningful while
     * {@link #curve} is CUSTOM; otherwise derived from the curve.
     */
    public List<Double> stops = new ArrayList<>();

    /** The strip, top to bottom. Empty for a brand-new palette. */
    public List<PaletteSegment> segments = new ArrayList<>();

    /** Item ids never chosen when an Automatic segment resolves (right-click red in the editor). */
    public List<String> autoExclude = new ArrayList<>();

    /** Similar blocks swap within each segment, 0..1. */
    public double variation = 0.43;

    /** Chance to repeat or skip a step, 0..1. */
    public double chaos = 0.0;

    /** Chance steps run longer or shorter, 0..1. */
    public double stepWobble = 0.0;

    /** How a placed gradient decides its length. */
    public SizingMode sizing = SizingMode.FILL_SPACE;

    /** Step count used when {@link #sizing} is SET_STEPS (1..16). */
    public int steps = 8;

    // ---- noise (used only when noise painting) --------------------------------------------------

    public NoiseType noiseType = NoiseType.SMOOTH;
    public double noiseScaleX = 12.0;
    public double noiseScaleY = 12.0;
    public double noiseScaleZ = 12.0;
    public boolean noiseLock = true;
    public String noiseSeed = "";

    // ---- pattern fields (kind == PATTERN only) --------------------------------------------------

    /** Grid size in cells, 1..32 each. */
    public int width = 8;
    public int height = 8;

    /**
     * Row-major cells, start row first, {@code width * height} entries; each an item id or ""
     * for a hole (placement skips holes).
     */
    public List<String> cells = new ArrayList<>();

    /** How the pattern repeats past its edges. */
    public PatternTiling tiling = PatternTiling.NONE;

    /**
     * Experimental pattern variation (§5.0 of the v2.1 spec): 0 = off; 1–3 = a placed cell may
     * swap to a uniformly-random block within this many positions of its block in the
     * Oklab-ordered source list.
     */
    public int patternVariation = 0;

    /** Chance (percent, 1–100) that a cell actually swaps when variation is on. */
    public int patternVariationChance = 100;

    /**
     * The cell placement starts from (the plus marker in the editor), or −1/−1 for the default
     * top-left — lets a fresh stroke begin from anywhere in the drawing (e.g. its centre).
     */
    public int startU = -1;
    public int startV = -1;

    /**
     * When true the pattern's START edge is its bottom row — placement advances through the
     * drawing bottom-up, so painting off the ground doesn't come out upside-down.
     */
    public boolean startAtBottom = false;

    /** The cell at (u, v) or "" — no tiling applied; callers wrap/clamp first. */
    public String cellAt(int u, int v) {
        if (u < 0 || u >= width || v < 0 || v >= height) return "";
        int idx = v * width + u;
        return idx < cells.size() ? cells.get(idx) : "";
    }

    /** Deep copy — the editor works on a copy so Cancel keeps the saved version intact. */
    public Palette copy() {
        Palette p = new Palette();
        p.id = id;
        p.name = name;
        p.kind = kind;
        p.width = width;
        p.height = height;
        p.cells = new ArrayList<>(cells);
        p.tiling = tiling;
        p.patternVariation = patternVariation;
        p.patternVariationChance = patternVariationChance;
        p.startU = startU;
        p.startV = startV;
        p.startAtBottom = startAtBottom;
        p.source = source;
        p.order = order;
        p.curve = curve;
        p.stops = new ArrayList<>(stops);
        p.segments = new ArrayList<>();
        for (PaletteSegment s : segments) p.segments.add(s.copy());
        p.autoExclude = new ArrayList<>(autoExclude);
        p.variation = variation;
        p.chaos = chaos;
        p.stepWobble = stepWobble;
        p.sizing = sizing;
        p.steps = steps;
        p.noiseType = noiseType;
        p.noiseScaleX = noiseScaleX;
        p.noiseScaleY = noiseScaleY;
        p.noiseScaleZ = noiseScaleZ;
        p.noiseLock = noiseLock;
        p.noiseSeed = noiseSeed;
        return p;
    }

    /**
     * Canonical content string for cache invalidation: any edit that could change what gets
     * placed changes this key. Includes the id so switching palettes always invalidates.
     */
    public String contentKey() {
        StringBuilder sb = new StringBuilder(id).append('|').append(kind).append('|')
                .append(source).append('|').append(order).append('|').append(curve).append('|');
        if (kind == PaletteKind.PATTERN) {
            sb.append(width).append('x').append(height).append('|').append(tiling).append('|')
                    .append(patternVariation).append('@').append(patternVariationChance).append('|')
                    .append(startU).append(',').append(startV).append('|')
                    .append(startAtBottom).append('|');
            for (String c : cells) sb.append(c).append(',');
        }
        for (double d : stops) sb.append(d).append(',');
        sb.append('|');
        for (PaletteSegment s : segments) sb.append(s.token()).append(',');
        sb.append('|');
        for (String e : autoExclude) sb.append(e).append(',');
        sb.append('|').append(variation).append('|').append(chaos).append('|').append(stepWobble)
                .append('|').append(sizing).append('|').append(steps)
                .append('|').append(noiseType).append('|').append(noiseScaleX).append('|')
                .append(noiseScaleY).append('|').append(noiseScaleZ).append('|').append(noiseLock)
                .append('|').append(noiseSeed);
        return sb.toString();
    }

    /** True if any segment is an Automatic wildcard. */
    public boolean hasAutomatic() {
        for (PaletteSegment s : segments) if (s.isAutomatic()) return true;
        return false;
    }

    /** True if every segment is an Automatic wildcard (or the strip is empty). */
    public boolean allAutomatic() {
        for (PaletteSegment s : segments) if (!s.isAutomatic()) return false;
        return true;
    }

    /**
     * Distinct explicitly-defined block ids not present in {@code availableIds} — strip order
     * for gradients, cell order for patterns. Automatic segments and holes never count.
     */
    public List<String> missingBlocks(Set<String> availableIds) {
        Set<String> missing = new LinkedHashSet<>();
        if (kind == PaletteKind.PATTERN) {
            for (String c : cells) {
                if (c != null && !c.isEmpty() && !availableIds.contains(c)) missing.add(c);
            }
        } else {
            for (PaletteSegment s : segments) {
                if (!s.isAutomatic() && !s.block.isEmpty() && !availableIds.contains(s.block)) {
                    missing.add(s.block);
                }
            }
        }
        return new ArrayList<>(missing);
    }
}
