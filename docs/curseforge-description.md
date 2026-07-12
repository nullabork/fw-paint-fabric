# FW Paint

Open source (MIT) — [github.com/nullabork/fw-paint-fabric](https://github.com/nullabork/fw-paint-fabric)

**Paint with blocks.** FW Paint is a client-side Fabric mod with three block-painting tools — **Solid**
(one block: walls, columns, volumes), **Gradient** (smooth colour/brightness blends), and **Noise**
(natural, blotchy 3D patterns) — all using the blocks already in your inventory. What you paint and
where you place it are independent: any paint type works through any placement mode, with or without
markers. Placement is done the legit, multiplayer-safe way (normal block-place interactions the
server validates), so it works on servers.

## Quick start

1. Press **K** → **Settings** tab → click **Paint tool** and pick any item (e.g. a stick). The mod is
   only active while you hold that item.
2. Hold the tool. Press **V** to switch paint type (Solid / Gradient / Noise), **G** to cycle the
   placement mode. The helper text (top-left) always shows both.
3. Aim at a block face and right-click to paint — a tap places one layer, holding keeps going. The
   green face tint + arrows show exactly what the click will grow, and which way.
4. Markers are optional: they bound what you paint and give gradients their endpoints. Marker modes
   place them (left-click starts, right-click ends).
5. Lost? The **Help** tab (last in the K screen) is a drill-down in-game manual — every topic shows
   a one-line summary you can expand for the details. It always shows your current key bindings.

## Keybinds

- **K** — open / close the FW Paint screen
- **V** — switch paint type: Solid → Gradient → Noise (or the **Use** button on each tool tab)
- **G** — cycle placement mode: Marker → Marker corners → Marker draw → Single → Face → 3D Fill →
  Disabled
- **L-Ctrl** (hold + click) — a marker-removing click clears the whole connected plane

All rebindable under Options → Controls → Key Binds → MISC — the in-game Help always shows the
current bindings.

## Placement modes

Where blocks go, shared by all three paint types. A **green face tint + arrow** previews every face
the next click grows from; while you hold, it locks to the operation and rides the advancing fronts.

- **Single** — one column straight out of the clicked face. Re-clicking a half-built column
  continues from its first air gap.
- **Face** — every connected, reachable block on the clicked face's plane extrudes together: click
  the top of a wall to make it taller, its side to make it thicker. With a start marker behind the
  clicked column, the selection is the connected marker group instead (one hold builds the whole
  marked wall). **Fill voids first** (Settings, on by default) levels the lowest reachable columns
  before stacking new layers.
- **3D Fill** — a connected blob growing out of the clicked face; tap for the first shell, hold to
  grow. Walls, end markers, and start-marker space contain it.
- Everywhere: an **end marker stops a column or fill even when it floats in air**, and a column
  that already reached its marker never resumes past it.

## Markers

Turquoise **start** and amber **end** blocks that aim and bound every tool. Markers aren't real
blocks, so every marker mode reaches out to the **Marker dist** setting — far beyond block reach.
Saved per world and dimension; **Clear Markers** (Settings) wipes them.

- **Marker** — left-click/right-click to mark, click-drag for straight axis-locked lines. Starting
  on a marked block removes instead.
- **Marker corners** — two clicks mark a whole volume: left-click the first corner (its clicked
  face sets the column direction; a thin box follows your aim), right-click the opposite corner —
  start markers fill the first plane, end markers the opposite one, a pair per column. With Auto
  end marker on, each column hugs the terrain instead, and columns with nothing paintable inside
  the box are skipped.
- **Marker draw** — a freehand pencil: hold and sweep to spatter start markers over whatever the
  crosshair touches, right-click for ends. Auto ends scan along the face the stroke began on.
- Clicking a marked block toggles it off in **any** marker mode; hold **Ctrl** to clear its whole
  connected plane at once.
- **Auto end marker** (Settings) — placing a start marker scans out from **the face you clicked**
  and marks the first non-air block as the end (all air → max distance), with a blue face + arrow
  preview while aiming.

## Solid tool

Places one kind of block through any placement mode. The **Match** button picks what:

- **Selected block** — always places the ✓ block from your list.
- **Exact block** — places the same block as the one whose face you click (never places excluded
  blocks — you get a warning above the hotbar instead).
- **Closest colour** / **Closest brightness** — the source block nearest the clicked block by
  average texture colour / brightness.
- **Solid tab list**: **✓** picks the one block to place, **✗** marks exclusions.

## Gradient tool

A smooth blend from one block to another — along markers or free-hand.

- **Between markers**: mark a start and an end block, click the face the line runs out of and
  hold. The gradient stretches start→end; the marker blocks count as its first and last blocks.
- **Anywhere else**: the gradient comes from the picker list ([S] → [E]), one block per step,
  growing out of whatever face you click — the column stops when the last step is placed.
  **Gradient memory** remembers each placed block's step, so re-clicking a half-finished gradient
  continues it (cleared when the picker gradient changes, or after the Settings idle slider —
  default 1 min).
- **3D gradients**: sphere-ish blends from the centre outward. Each fill remembers its centre —
  click a block that belongs to one and the same sphere keeps growing.
- The helper text always says where the endpoints come from: "Selected from markers", "Selected
  from picker", or a split pair when one marker floats in air (that side falls back to the picker).
- **Order modes** (how blocks are sorted): Color, Brightness, Top % Dark/Light (tuned by the
  **Pixel %** slider), B&W/Colour Diff, or **Pick** (fully manual numbering).
- **Shaping**: **Curve** (Linear / Ease In / Ease Out / Ease In-Out / Step), **Variation** (similar
  blocks randomly stand in for a step — shown as small icons on its row), **Chaos** (chance to
  repeat/skip a step), **Step length** (steps randomly run longer/shorter), **Max steps**.

## Noise tool

Fills with a natural pattern driven by a seedable 3D noise field — valleys get one end of your
block order, peaks the other. Always ordered from the picker list.

- **Region fill**: bound a region with start/end marker pairs, switch to 3D Fill, right-click an
  empty spot inside — it flood-fills the whole region at once.
- Or paint it anywhere: Single columns, Face surfaces, and free 3D blobs all work.
- **Noise settings**: type (Smooth / Perlin / Fractal), **Seed**, per-axis **Scale** (with Lock
  XYZ), plus its own Order / Variation / Chaos / Max steps — and a **live preview** grid you can
  drag to pan.

## The block list (all tabs)

- Colours: **white** = in the placement sequence, **blue** = eligible but not picked, **green** =
  must-use / the ✓ block, **red** = excluded. [S]/[E] tags mark the current endpoints.
- Buttons (gradient/noise tabs): **[S]** set start, **[E]** set end, **✓** must-use, **✗** exclude.
  A yellow key under the list explains the active buttons.
- **Shortcuts**: **double-click** un-excludes / toggles ✓; **middle-click** un-ticks / toggles
  exclusion. Scroll the mouse wheel over the list.
- **Source** (above each list) picks where blocks come from: Hotbar / Inventory / both.

## Settings

- **Placement** — the placement mode (same as G).
- **Marker dist** — marker range: max start↔end distance, the marking raycast reach, the auto
  end scan, and Solid 3D marker-space size.
- **Auto end marker**, **Fill voids first**, **Gradient memory** — see above.
- **Move helper text…** — nudge the HUD lines anywhere, with a live preview.
- Each tool tab has a **Use** button; the screen opens on the active paint type's tab.

## Requirements

- **Minecraft 26.2** · **Fabric Loader 0.19.3+** · **Fabric API** for 26.2 · **Java 25**
- Client-side only — nothing to install on the server.
- Placement sends normal "use block" interactions the server validates (Litematica-style); an
  aggressive anti-cheat may rate-limit very fast fills.

---

*Open source (MIT) — [github.com/nullabork/fw-paint-fabric](https://github.com/nullabork/fw-paint-fabric)*
