# Placement rework — paint type × placement mode

Agreed 2026-07-12. Splits the old per-tool mode system into two independent axes and makes
every combination work both inside and outside markers.

## The two axes

- **Paint type** (what blocks get chosen): Solid / Gradient / Noise.
  - Switched by the existing paint-type keybind (V) *and* a new **Use** button on each tool tab.
- **Placement mode** (where blocks go): **Marker → Single → Face → 3D Fill → Disabled**,
  one global mode cycled by the existing cycle keybind (G). No more per-paint-type modes —
  the old inconsistent Marker/Place vs Marker/Extrude/3D cycles collapse into this one list.
  - Two more marker-selection modes (*Marker auto*, *Marker cube*) come later, as additional
    entries; they don't replace anything. Not part of this build.

Markers stop being a prerequisite: they only **constrain** (columns stop at end markers, fills
respect marker space, face selection can't leave a marker group it started in) and, for
gradients, **provide endpoints** when a placed cell lies on a start→end segment.

## Placement modes

### Single
One column out of the clicked face, along its normal. Click = one block, hold = keep
extending. Resumes from the column's first air gap; end markers stop it (even in air); a
column whose path already crossed its end marker never resumes past it.

### Face
Click a face → find every **interconnected, reachable block on the same plane as that face**
(spread over the 8 in-plane neighbours, transitively) whose face is exposed, and extrude all
of those columns together, layer by layer while held. Works for tops of walls (same Y) and
wall sides (same X/Z plane — clicking a wall's side makes the wall thicker, clicking its top
makes it taller).

- Outside markers: the flood only picks blocks whose face is **on the clicked plane** — a
  column that's already one block ahead isn't included (start from the lowest block).
- Inside markers (a start marker behind the clicked column): the flood is the connected
  coplanar start-marker group (the existing wall-extrude behaviour); each column resumes from
  its own first air gap.
- **Fill voids first** (new Settings toggle, default ON): in-marker face fills advance only
  the most-behind columns each layer until all fronts are level, then stack together. Off =
  all columns advance at once (old behaviour).

### 3D Fill
Grows a connected blob out of the clicked face (existing Solid 3D mechanics: through air
only; start-marker space and end markers contain it). Now works for all three paint types.

## Gradient block choice (per cell)

- Cell lies on a colinear start→end segment (and "From: Markers"):
  t = position along the segment (stretched start→end, markers = first/last blocks).
  Endpoints = the real marker blocks — **per side**: a marker sitting in air falls back to
  the picker's [S]/[E] block for that side only.
- Anywhere else (or "From: Block list"): endpoints are the picker's [S]/[E]; the gradient is
  **one block per step** (steps = the built ramp's step count), advancing along the column.
  When a column's gradient runs out, that column stops. All gradient-tab sliders apply.
- 3D Fill: always picker endpoints; the clicked centre is the start, t = distance from
  centre / step count; growth stops when the last step's shell is done (spheres).

### Session caches (gradient outside markers)
So a gradient can continue across clicks with nothing to measure against:

- Column cache (per placement mode): placed cell → (step index, direction). On click, the
  cell behind the column's air front is looked up; a hit continues the gradient, else it
  starts at step 0.
- 3D cache: list of fills (centre + placed cells). Clicking a block that belongs to a fill
  continues that fill from its original centre; clicking any other block starts a new fill.
  Caches only ever contain blocks we placed, so membership is never ambiguous.
- Invalidation: whole-cache clear when the picker gradient changes (fingerprint over
  endpoints/mode/curve/sliders/exclusions) or after **Gradient memory** idle seconds without
  placing (new Settings slider, 10 s – 5 min, default 60 s).

## Helper text (HUD)

Line 1 paint type, line 2 placement mode (as before), then sourcing lines that always tell
the truth about what the *next click* would use:

- Gradient + Single/Face: "Selected from markers" when the aimed column runs between real
  marker blocks; "Selected from picker" otherwise (including From: Block list). Mixed
  endpoints (one marker in air) show two lines: "Start from marker" / "End from picker" (or
  vice versa). The mode never has to be changed by hand — it adapts.
- Gradient + 3D, and Noise always: "Selected from picker".
- Solid: no sourcing line.

## In-world previews

- The blue tint + arrow (auto end marker) is generalised into a shared face-overlay renderer.
- **Green** tint + arrow in paint modes, showing exactly what will extrude and which way:
  - Single: the aimed face.
  - Face: every face in the flood, each at its column's current front.
  - 3D Fill: every open side of the aimed block.

## Settings additions

- **Placement** button (replaces the three per-type mode buttons).
- **Fill voids first** toggle, default ON, yellow hint "Face mode in markers: level, then stack".
- **Gradient memory** slider (10 s – 5 min, default 60 s) — outside-marker gradients forget
  their progress after this idle time.

## Noise

Unchanged block choice (always picker order + noise field). Available in Single / Face / 3D.
3D Fill aimed into a marked region keeps the old instant region flood-fill; otherwise it
grows a blob like Solid.

## Code shape

- `PlacementMode` replaces `ToolMode`; config gets `placementMode` (old per-type fields dropped).
- `PaintPlacer` (new) = all Single/Face/3D input, targeting, queueing, previews, caches;
  replaces `SolidPlacer` + `GradientPlacer`'s tick paths.
- `GradientChoice` (new) = the palette/pick/wobble machinery extracted from `GradientPlacer`.
- `NoisePlacer` slims to static helpers (ordering, region tests) shared by `PaintPlacer`.
- `FaceOverlay` (new) = shared face tint + arrow geometry (blue for auto-end, green for paint).
