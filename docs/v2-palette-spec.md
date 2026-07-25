# FW Paint v2.0.0-beta — Palette Spec

Status: **DRAFT — awaiting review**. This document specifies the v2 redesign: the Gradient and
Noise Paint tabs are replaced by a single **Palette** tab where users create, save, and reuse
named block palettes that drive both gradient and noise painting.

---

## 1. Goals

- Gradients become **named, saved, reusable objects** ("palettes") instead of one implicit global
  configuration scattered across config fields.
- Gradient composition becomes **explicit**: the user picks exactly which blocks are in the
  gradient and in what order, instead of the mod deriving an order from everything in the
  inventory minus exclusions.
- One palette drives **both gradient painting and noise painting** (and carries its own
  noise settings).
- New **Automatic segments** act as wildcards that resolve to real blocks at placement time,
  replacing the old From:Markers toggle and most of the old gradient-mode zoo.
- Multiple palettes can be saved, switched between in-game via keybind, and shown on the HUD.

## 2. Terminology (proposed — please confirm)

| Term | Meaning |
|---|---|
| **Palette** | A saved, named preset: ordered segments + all gradient/noise settings. The single name used everywhere (UI, code, docs). |
| **Segment** | One entry in a palette's strip: either a specific block or an Automatic segment. Has a size (% of the strip). |
| **Stop** | The draggable boundary handle between two segments (same mechanic as today's curve-strip handles). |
| **Automatic segment** | A wildcard segment (`Automatic colour` or `Automatic brightness`) resolved to real blocks at placement time. |
| **Active palette** | The one currently selected for painting, shown on the HUD, cycled with the new keybind. |

> Rejected names for the record: "preset", "gradient set", "palette item". Everything below says
> "palette".

## 3. Tab changes at a glance

| v1 tab | v2 |
|---|---|
| Gradient | **Removed** — replaced by Palette tab |
| Noise Paint | **Removed** — folded into Palette (noise type/scale live on each palette) |
| — | **Palette** (new): list view + editor view |
| Solid | Unchanged |
| Finder | Unchanged |
| Settings | Keeps only global settings (placement mode, marker distance, auto end marker, fill voids, gradient memory, clear markers, HUD position, debug, colour match, paint-tool picker). All per-gradient settings move into the palette editor. |
| Help | Content rewritten to match v2 (separate task, same drill-down panel) |

The bottom bar's "Use Gradient / Use Noise Paint" buttons and the V key still cycle
`PaintType { GRADIENT, NOISE, SOLID }` — gradient and noise painting both consume the active
palette; solid ignores it.

---

## 4. Palette tab — list view

The default view of the Palette tab. Full content width (no three-column layout).

### 4.1 Layout

```
[FW Paint]                  Solid  » Palette  Finder  Settings  Help
┌────────────────────────────────────────────────────────────────┐
│  [ + New ]  [ Use ]  [ Edit ]  [ Delete ]                      │
├────────────────────────────────────────────────────────────────┤
│ ┌────────────────────────────────────────────────────────────┐ │
│ │ Sunset fade                    [🟥][🟧][🟨][⬜][🟦]        │ │   ← collapsed row
│ ├────────────────────────────────────────────────────────────┤ │
│ │ Deepslate mix                  [▪][▪][▪][▪]                │ │   ← red border
│ │   missing blocks                                           │ │
│ ├────────────────────────────────────────────────────────────┤ │
│ │ Beach                          [🟨][🟨][⬜][🟦]  (selected) │ │   ← white outline,
│ │  ┌ Summary ────────────────────────────────────────┐       │ │     expanded
│ │  │ Order: Colour   Curve: Ease-in   Sizing: Fill   │       │ │
│ │  │ Variation 43%  Chaos 0%  Step len 0%            │       │ │
│ │  │ Noise: Perlin, scale 12 (locked)                │       │ │
│ │  │ Source: Hotbar + Inv                            │       │ │
│ │  │ Missing blocks:                                 │       │ │
│ │  │   [▪] Red Sandstone                             │       │ │
│ │  │   [▪] Cut Sandstone                             │       │ │
│ │  └─────────────────────────────────────────────────┘       │ │
│ └────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────┘
                       [ Use Gradient ]  [ Done ]
```

### 4.2 Rows

- Scrollable vertical list of all saved palettes. Each collapsed row shows:
  - **Left**: palette name.
  - **Right**: the palette's block sprites lined up horizontally, in segment order
    (Automatic segments render as a small crosshatch icon).
- Collapsed rows show a small **▸ expand chevron** at their right edge.
- **Click a row (or its chevron)** → it becomes *selected*: white outline, and it expands in
  place to show a summary of all its settings (order mode, curve, sizing mode,
  variation/chaos/step length, noise type + scale, source, and segment list). **Only one row
  is ever expanded** — expanding a row collapses the previously expanded one. Clicking the
  selected row again collapses it (it stays selected; chevron returns).
- Pressing **Use** on a palette also auto-expands its summary row.
- **Missing blocks**: a palette whose explicitly-defined blocks are not all available from its
  configured source (hotbar / inventory per its Source setting) gets a **red border** and a
  small "missing blocks" line at the bottom of the collapsed row. The expanded summary lists
  the missing blocks vertically — sprite + block name. (Automatic segments never count as
  missing.) Availability is re-checked when the screen opens and when inventory changes.

### 4.3 Buttons (above the list)

- **New** — opens the editor with a blank palette.
- **Use** — makes the selected palette the *active* palette (updates HUD) and auto-expands its
  summary row. Disabled when nothing is selected.
- **Edit** — opens the editor loaded with the selected palette. Disabled when nothing selected.
- **Delete** — confirmation popup ("Delete '<name>'? [Delete] [Cancel]"). Deleting the active
  palette makes the next palette in the list active (or none, showing a HUD hint to create one).

---

## 5. Palette editor

Entered via New/Edit. It's a temporary mode of the Palette tab (the tab bar stays visible but
switching tabs or pressing Done acts like **Cancel**, with an "unsaved changes" confirm if dirty).

Vertical layout, top to bottom: tab bar → **preview** → **name/save row** → the three columns.
The columns are like today's Gradient tab, but the **middle column is wider** and the **right
column is narrower** (helper text no longer needs inline space — see §5.6). Because the preview
row makes the page taller, the **entire editor content below the tab bar is vertically
scrollable** (mouse wheel + a slim scrollbar on the right edge) whenever it doesn't fit the
window height; the tab bar stays fixed.

### 5.1 Preview (directly under the tab bar, spans all columns)

- Centered horizontally, with a **max width** so it doesn't balloon on wide windows (roughly
  the v1 preview size; implementer picks the exact cap).
- Renders the palette as either the **gradient cylinder** or the **isometric noise cube**.
  Next to the existing **⛶ expand** button sits a new **preview toggle** that switches between
  the two (e.g. a small "G/N" button). The noise preview uses the palette's noise
  type/scale/seed and stays draggable-to-pan as in v1; the expand overlay expands whichever
  preview is active. The chosen preview mode is remembered (UI state, not saved on the palette).

### 5.2 Name row (spans all three columns, under the preview)

- **Left**: name text box (placeholder "Untitled").
- **Right**: **[ Save ]** **[ Cancel ]**.
- Save rules: empty name → auto-name `Untitled`, `Untitled (2)`, `Untitled (3)`… Names must be
  unique; saving with a name that collides with a *different* palette shows an inline error and
  does not save. Save persists everything (segments, stop positions, all sliders/toggles) and
  returns to the list view with the palette selected.

### 5.3 Left column — block source list

- **Source toggle** (unchanged): Hotbar | Hotbar + Inv | Inventory. This is saved per-palette and
  also defines the pool Automatic segments and variation swaps draw from at placement time.
- Below it: a vertically scrollable box — dark translucent background, white outline — listing
  the blocks from the chosen source with sprite + name, **sorted by colour** (existing
  `ColorOrder` colour ordering).
- **Two special rows pinned at the top**, crosshatch icon instead of a sprite:
  - `Automatic colour`
  - `Automatic brightness`
- **Double-click any row** to append it to the strip in the middle column as a new segment.
  The same block may be added more than once. No [S]/[E]/✓/✗ buttons — that whole assign-mode
  system is gone.
- **Right-click a block row** to toggle it as **excluded from Automatic segments**: its name
  turns red; right-click again restores white. Excluded blocks are never chosen when an
  Automatic segment resolves at placement time. The exclusion list is saved on the palette.
  (The two Automatic rows themselves can't be excluded. Whether the exclusion also applies to
  the Variation swap pool is open — see §12.)

### 5.4 Middle column — the segment strip

The heart of the editor. Vertical strip, same rendering style as today's curve strip (iso-tiled
block sprites per segment, draggable boundary handles), plus:

- **Empty state**: a new palette has no segments — the strip shows only vertical
  (rotated 90°) placeholder text: *"select a block"*.
- **Adding**: double-clicking in the left list appends a segment at the bottom; existing
  segments rescale proportionally to make room (equal share for the newcomer).
- **Labels**: to the left of the strip, each segment's block name is drawn horizontally,
  pointing at its segment ("Automatic colour"/"Automatic brightness" for automatic segments).
  - Name wider than the label gutter → auto-scrolling marquee (scrolls left, loops).
  - Segments too short for every label to sit beside its segment → labels stack vertically,
    packed with a few px margin, in correct strip order even if not perfectly aligned with
    their segment. When room returns (segment resized/reordered), labels realign.
  - Labels move live with their segment during a drag.
- **Stops** (boundary handles): drag to resize adjacent segments — same mechanic as v1.
  Dragging a stop switches the Curve toggle to **Custom**.
- **Reordering by drag**: hovering the body of a segment shows a ↕ move cursor. Click-drag
  moves the segment up/down, keeping its size (% share). While dragging, the segment visibly
  follows the cursor; when it overlaps a neighbour past the midpoint, the two **swap places**
  (only those two change order — everything else keeps its position). A segment can be dragged
  from top to bottom in one gesture (repeated swaps). Releasing the mouse commits.
  Reordering by drag switches the Order toggle to **Custom**.
- **Selection + keyboard**: a plain click (no drag) selects a segment — white outline. Up/Down
  arrow keys swap the selected segment with its neighbour, one step per press (also → Order:
  Custom). `Delete`/`Backspace` removes the selected segment (its share is redistributed
  proportionally).
- **Removal by drag-out**: while dragging a segment, pulling it far enough **sideways** out of
  the strip detaches it — the remaining segments *preview-snap together* (closing the gap,
  growing proportionally) while the dragged segment floats at the cursor. Releasing in this
  state removes it. Dragging back over the strip re-opens the gap and it can be dropped back
  in place.
- **Missing block**: a segment whose block isn't available from the palette's source is drawn
  with a **red outline**.

### 5.5 Above the strip — Order toggle and Curve toggle

Two stacked controls above the strip (order on top, curve under it):

- **Order: Colour | Brightness | Custom** — Colour/Brightness auto-sort the segments (by the
  existing perceptual colour ordering / Oklab lightness). Custom cannot be clicked into: it is
  entered only by manually reordering (drag or arrow keys). Clicking Colour/Brightness while
  in Custom re-sorts and leaves Custom.
- **Curve: Linear | Ease-in | Ease-out | Ease-in-out | Step | Custom** — unchanged cycle, but
  as in v1, Custom is only entered by dragging a stop. Changing curve away from Custom resets
  stops to that curve's distribution.

### 5.6 Right column — settings (narrower)

Kept (per-palette, saved with the palette):

- **Variation** slider — similar blocks swap within each segment (swap pool = palette source).
- **Chaos** slider.
- **Step length** slider.
- **Sizing toggle: Min blocks | Fill space | Set steps** — replaces the always-visible Max
  steps slider:
  - **Min blocks** — place the fewest blocks that realise the segment ratios; never expands
    to reach an end block (the gradient may stop before reaching a resolved end colour).
  - **Fill space** — expand the gradient to fill from the placed block to the end marker, or
    to the first non-air block when there's no end marker.
  - **Set steps** — a **Steps slider (1–16)** appears directly below the toggle; the gradient
    expands/contracts to fit that many steps.
- **Noise type: Smooth | Perlin | Fractal** and **Noise scale** slider(s) (+ Lock XYZ, Seed —
  carried over from the noise tab). Helper text: *"Only used when noise painting."*

(The preview lives at the top of the editor now — see §5.1.)

Removed from v1's right column:

- **From: Markers / Block list** toggle — gone. Endpoint behaviour is now expressed by putting
  (or not putting) Automatic segments at the ends of the strip (§6).
- **Gradient mode** (Colour / Brightness / Top-dark / Top-light / BW-diff / Colour-diff / Pick —
  9 modes) — gone. Reduced to the **Order: Colour | Brightness** toggle (§5.4); the diff/top
  modes and Pick mode are retired. (Pick mode's job — hand-ordering blocks — is exactly what
  the editor now does directly.)
- **Pixel %** slider — retired with the top-% modes.

### 5.7 Helper text → help popups

The always-visible yellow helper lines are removed from the editor. Instead every control gets a
small **(?)** circle icon after it; clicking it (or hovering the control for ~1s) shows the
yellow helper text as a small popup anchored at the mouse position. One popup at a time;
click-away or mouse-out dismisses it.

---

## 6. Automatic segments — placement semantics

An Automatic segment is a wildcard resolved when painting. `Automatic colour` matches by
perceptual colour distance (Oklab), `Automatic brightness` by lightness. Resolution always draws
from the palette's source (hotbar/inventory), minus the palette's auto-exclusion list (§5.3),
widening the acceptance threshold until *some* block qualifies — it always finds something if
the source has any non-excluded placeable block.

Per position in the strip:

- **Automatic at the START**: the gradient's start colour is the block the paint started on —
  the clicked/placed block for that column, row, or 3D fill (per-column, exactly like today's
  marker-driven endpoint resolution).
- **Automatic at the END**: scan ahead along the paint direction for an **end marker**; if none,
  use the **first non-air block** hit. That block's colour is the end colour.
- **Automatic in the MIDDLE**: interpolate between the nearest *resolved* neighbours — the
  previous segment's block (static, or the last placed block if that was also automatic) and
  the next statically-defined segment's block (or, at the end, the resolved end block). The
  automatic span fills its share of the strip with the closest available blocks along that
  colour ramp.

Error cases (on-screen action-bar error, nothing is placed):

- Palette is *entirely* automatic segments and no start/end colour can be resolved (e.g. start
  block is air/unrecognisable and no end block found): **"<palette>: can't resolve colours —
  add a block or paint toward one."** (final wording TBD)
- **3D paints (gradient AND noise, Fill3D)** must know the full colour range up front. The end
  of the strip must resolve: either the last segment is a static block, or automatic-at-end can
  resolve against a marker/non-air boundary. A 3D paint that cannot resolve its end shows
  **"3D paint needs a defined end block."** Automatic at the *start* is fine (resolves from the
  clicked block).

Markers behave as in v1: painting inside marker space is still restricted by markers, and end
markers still terminate columns/fills.

## 7. Placement pipeline changes

- **Sourcing blocks**: when placing, blocks are pulled from **as many stacks/places as
  possible** across the allowed source — if a block exists in both hotbar and inventory (and
  source allows both), drain any of them, not just the first slot found.
- **Missing defined blocks**: by default, painting with a palette whose explicitly-defined
  blocks aren't all available **refuses to place** and shows an action-bar error naming the
  palette ("<palette>: missing blocks"). A **global toggle on the Settings tab** softens this:
  **"Missing blocks: Don't paint | Skip missing"** — *Skip missing* drops the unavailable
  segments and paints with the rest. Default: Don't paint.
- **Caching** (`GradientCaches`): column/3D caches work as today, but the fingerprint is
  additionally keyed by the **active palette id + its content hash** — switching or editing a
  palette invalidates in-progress gradients. Cache entries also remember the resolved
  start/end blocks per column as today.
- **Sizing modes** plug in where `maxSteps`/marker segments drive step count today:
  Min blocks / Fill space / Set steps per §5.6.
- Noise painting uses the palette's segment order as its ordered ramp (replacing
  `noiseGradientMode` ordering) and the palette's noise type/scale/seed settings.

## 8. HUD & keybinds

- **New HUD row** (below the existing paint-type + placement-mode lines, while GRADIENT or
  NOISE paint type is active): the active palette's segment sprites drawn **overlapping**
  (heavily squashed horizontally so the row stays compact) followed by the palette name.
  If no palette exists / none active: **"Press <K> to set up a palette"** (hotkey rendered
  from the actual binding). The same message flashes as an overlay when cycling paint type (V)
  onto Gradient/Noise with no palettes saved.
- **No paint tool assigned**: normally the HUD only renders while holding the paint tool. In
  the one case where *no tool is assigned at all*, the HUD instead shows
  **"Tool selection required — press <K> and go to Settings"**; additionally, opening the K
  screen while no tool is assigned lands on the **Settings tab** by default (where the tool
  picker lives) instead of the last-used tab.
- **Solid paint type**: instead of the palette row, show the solid choice — the selected
  block's sprite + name, or the match-mode text (e.g. "Match closest colour").
- **New keybind** (default: **unbound?** see Open Questions; suggest `B`): cycle the active
  palette through the saved list while holding the paint tool; flashes the palette name as an
  overlay message, updates the HUD row.

## 9. Data model & persistence

New Gson model, one file `config/fw-paint-palettes.json` (separate from `gradient.json` so
global settings and content don't churn each other):

```jsonc
{
  "version": 2,
  "activePalette": "sunset-fade",          // palette id
  "palettes": [
    {
      "id": "sunset-fade",                 // slug, stable across renames
      "name": "Sunset fade",
      "source": "HOTBAR_AND_INVENTORY",
      "order": "CUSTOM",                   // COLOR | BRIGHTNESS | CUSTOM
      "curve": "EASE_IN",                  // + stops when CUSTOM
      "stops": [0.25, 0.5, 0.75],          // boundary fractions, size = segments-1
      "segments": [
        { "block": "minecraft:red_sandstone" },
        { "auto": "COLOR" },               // automatic segment
        { "block": "minecraft:sandstone" }
      ],
      "autoExclude": ["minecraft:granite"], // never picked by Automatic segments (§5.3)
      "variation": 0.43,
      "chaos": 0.0,
      "stepWobble": 0.0,
      "sizing": "FILL_SPACE",              // MIN_BLOCKS | FILL_SPACE | SET_STEPS
      "steps": 8,                          // only used when SET_STEPS
      "noiseType": "PERLIN",
      "noiseScaleX": 12, "noiseScaleY": 12, "noiseScaleZ": 12,
      "noiseLock": true,
      "noiseSeed": ""
    }
  ]
}
```

Everything on a palette persists across sessions. Inventory contents are the only thing that
can differ later — handled by the missing-blocks UI states (list row red border, segment red
outline), never by mutating the palette.

`GradientConfig` drops the retired fields (`gradientMode`, `gradientFromMarkers`, `pickNumbers`,
`requiredBlocks`, `excludedBlocks`, `orderStartBlock/EndBlock`, `pixelPercent`, all `noise*`
duplicates, `curve/curveBounds`, per-tool sliders) and keeps only global settings + solid tool
fields + `activePalette` pointer (or that lives in the palettes file — implementer's choice).
It gains one new global field: `missingBlockPolicy` (`DONT_PAINT` default | `SKIP_MISSING`) for
the Settings-tab toggle in §7.

## 10. Migration from v1

**Decided: no migration and no starter palette.** Old `gradient.json` gradient fields are
simply ignored/dropped. First run starts with an empty palette list — instead of shipping a
default (an all-Automatic starter is unintuitive and auto-picking blocks isn't the experience
we want to teach), we **guide the user**:

- Switching paint type to Gradient/Noise with no palettes → overlay + HUD row
  "Press <K> to set up a palette" (§8).
- No paint tool assigned → HUD shows "Tool selection required — press <K> and go to Settings",
  and the K screen opens on the Settings tab (§8).

## 11. Retired features (explicit list — confirm each)

- Gradient tab, Noise Paint tab (both replaced).
- Gradient modes: `TOP_DARK_COLOR`, `TOP_DARK`, `TOP_LIGHT_COLOR`, `TOP_LIGHT`, `BW_DIFF`,
  `COLOR_DIFF`, `PICK` + the `Pixel %` slider.
- `From: Markers / Block list` toggle (subsumed by Automatic segments).
- Picker assign modes `[S] [E] ✓ ✗`, required/excluded block lists, pick numbering.
- Always-visible yellow helper text in the editor (moves to (?) popups). The Solid and
  Settings tabs keep their current helper-text style unless you want (?) popups everywhere.
- Separate noise settings duplicated from gradient settings (noise variation/chaos/max-steps
  collapse into the palette's single set).

## 12. Decisions log & remaining questions

Resolved with Aaron (2026-07-25):

1. **Naming** — "Palette" everywhere. ✔
2. **Segment removal** — Delete/Backspace on selection **and** drag-out-sideways with
   preview-snap (§5.4). ✔
3. **Missing defined blocks at placement** — refuse to paint by default; global Settings
   toggle "Missing blocks: Don't paint | Skip missing" (§7). ✔
4. **Retired feature list (§11)** — confirmed. ✔
5. **Migration** — none, and **no starter palette**; guide via HUD/overlay hints and
   open-to-Settings when no tool assigned (§10, §8). ✔
6. **Save also activates** the palette; cycle keybind default `B`. ✔
7. **Order toggle** — Colour/Brightness purely re-sort the editor strip; Custom is entered
   only by manual reordering and is otherwise unreachable. ✔
8. **3D paints** — must resolve the full range up front; automatic-at-start resolves from the
   clicked block, the end must resolve statically or via marker/non-air scan. ✔

Still open (minor — spec'd defaults apply unless objected):

1. **Auto-exclude vs Variation** — does a right-click-excluded block (§5.3) also leave the
   Variation swap pool, or only Automatic-segment resolution? Spec default: Automatic only.
2. **Solid/Settings tab helper text** — stays always-visible for now; (?) popups are
   editor-only.
3. **Edit-while-active** — saving the active palette invalidates in-progress placement caches;
   Cancel keeps the old version.
4. **Palette list capacity** — unlimited, scrolling.

## 13. Out of scope for v2.0.0-beta

- Solid tab, Finder tab, marker system mechanics, placement modes (Single/Face/3D), the
  paint-tool item, HUD move screen — all unchanged (except HUD additions in §8).
- Sharing/importing palettes (would fall out of the JSON file naturally — later).
- Help tab content rewrite tracked as part of the release, not this spec.
