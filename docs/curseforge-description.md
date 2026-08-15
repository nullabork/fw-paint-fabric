# FW Paint

Open source (MIT) — [github.com/nullabork/fw-paint-fabric](https://github.com/nullabork/fw-paint-fabric)

![One palette, two paints: a gradient wall and its noise counterpart](https://raw.githubusercontent.com/nullabork/fw-paint-fabric/master/docs/images/gradient-vs-noise-same-palette-walls-v2.0.0-beta.1.jpg)

**Paint with blocks.** A client-side mod (Fabric **and** NeoForge) with four paints — **Solid**
(one block: walls, columns, volumes), **Gradient** (smooth colour/brightness blends), **Noise**
(natural, blotchy 3D patterns), and **Pattern** (hand-drawn 2D block grids painted as planes) —
driven by **Palettes**: named, saved block ramps and patterns you build once and reuse
everywhere. A **Finder** tab ranks every block in the game by colour. Any paint works through
any placement mode, with or without markers. Placement is the legit, multiplayer-safe way
(normal block-place interactions the server validates), so it works on servers.

> **v2 beta.** Palettes replace v1's per-tool block picker. v1 gradient settings are not
> migrated — build your first palette on the Palette tab and you're going again in a minute.

## Quick start

1. Press **K** → **Settings** → **Paint tool**: pick any item (e.g. a stick). The mod is active
   only while you hold it.
2. On the **Palette** tab press **+ Gradient**: double-click (or drag) blocks — or the **Auto**
   wildcards — into the strip, then **Save**. Saving makes it the active palette.
3. **K** lands on the **Paint** tab: buttons for paint type, placement mode, and the active
   palette (in-game: hold **G** for the selector wheel, **B** cycles palettes).
4. Right-click a block face to paint: a tap places one layer, holding keeps going. The green face
   tint + arrows preview exactly what the click will grow, and which way.
5. Markers are optional: they bound what you paint and anchor Automatic segments.
6. Lost? The **Help** tab is a drill-down in-game manual, always showing your current keybinds.

## Keybinds

- **K** — open / close the FW Paint screen (lands on the Paint quick-controls tab)
- **G** (hold) — the selector wheel: **Paint** fans out the paint types (Solid / Gradient /
  Pattern / Noise), **Markers** the marker tools (Marker / Marker corners / Marker draw),
  **Placement** where blocks go (Single / Face / Face perp / 3D Fill), **Palette** the
  palettes/patterns for the current paint type (or, with Solid paint, Closest colour + your
  inventory's blocks in colour order), and **Disabled** turns the tool off. Long lists scroll
  as a carousel (arrows or mouse wheel). Click to choose — set several in one hold — then
  release
- **B** — cycle the active palette or pattern (whichever the paint type uses)
- **L-Ctrl** (hold + click) — a marker-removing click clears the whole connected plane
- All rebindable under Options → Controls → Key Binds → MISC.

## Palettes

One palette drives **both gradient and noise painting**. The Palette tab lists every palette
you've saved — name, its segment sprites, an expandable summary, and a red border when its blocks
aren't in your inventory (by default painting refuses then; a Settings toggle can skip the
missing segments instead). **Use / Edit / Delete** above the list; the HUD shows the active
palette and **B** cycles them in-game.

**The editor**: two live previews on top (a gradient cylinder and a noise cube, each expandable),
your colour-sorted inventory blocks in the middle, and the **segment strip** on the right:

- **Double-click or drag** blocks into the strip; drag a segment up/down to reorder, sideways out
  to remove; drag the pointed **stop handles** to resize segments — placement uses exactly the
  shares you drew (the Curve turns **C**ustom).
- **Order** and **Curve** icon-buttons above the strip: sort by colour or brightness
  (asc/desc), pick an easing (Linear / Ease In / Ease Out / Ease In-Out / Step) — or shape your
  own by hand.
- **Auto segments** — the crosshatched wildcards: *Auto colour* and *Auto brightness* resolve to
  a real inventory block **when you paint**, deterministically: at the strip's start they read
  the block you started on; at the end they scan to an end marker or the first solid block — and
  if there's nothing to find, they pick the *opposite* of your start (lightest↔darkest for
  brightness, the furthest colour for colour) so you still get a full ramp. A strip of nothing
  but Autos is a reusable "gradient from whatever I'm standing on".
  Right-click a block in the list to ban it from Auto picks.
- **Sizing**: **Min blocks** (shortest run that fits the ratios), **Fill space** (out to the end
  marker / first block), or **Set steps** (fixed length).
- **Variation** (a ±1–±3 colour-neighbour window + a swap chance — cells shimmer to nearby
  colours from your inventory), **Chaos** (repeat/skip dither), **Step length** (steps randomly
  run longer/shorter) — plus the palette's own noise type / scale / seed.
- Hover any control (or click its circled-?) for help describing what it's currently set to.

## Patterns

![A patterned wall painted stroke by stroke, variance shimmer included](https://raw.githubusercontent.com/nullabork/fw-paint-fabric/master/docs/images/pattern-wall-placed-variation-v2.1.0-beta.1.jpg)

Draw a block grid by hand (up to **32×32**) and paint it as a plane. **+ Pattern** on the
Palette tab opens the editor: left-click a block (its sprite rides your cursor), hold and scrub
to draw — press a same-block cell to erase, **Shift-drag** for straight/45° lines,
**right-click** to flood fill, **middle-click** to eyedrop, **Ctrl-click** to set where
placement starts (empty cells are holes). A live iso preview (expandable) shows the wall,
variance included; **Start: Top/Bottom** keeps ground-up painting upright. Painting advances
out of the clicked face, the width crossing a plane snapped from your facing (45°/90°) —
adjacent strokes **continue the drawing** instead of restarting, so you can build a patterned
wall column by column. Tiling: none / sides / start–end / both. Patterns are their own paint
type — **B** cycles your patterns while it's active.

## Placement modes

Where blocks go, shared by all three paints. The **green face tint + arrow** previews every face
the next click grows from, and rides the advancing fronts while you hold.

- **Single** — one column out of the clicked face; re-clicking continues from its first air gap.
- **Face** — the whole connected plane extrudes together: click a wall's top to raise it, its side
  to thicken it. A start marker behind the clicked column selects the connected marker group
  instead. **Fill voids first** (Settings) levels the lowest columns before stacking new layers.
- **Face perp** — a 1-block-wide run through the clicked block crossing your view (perpendicular to your look),
  snapped to 45° or 90° (the **Perp snap** setting) — diagonal stair-stepped lines included.
  Inside markers only the marked blocks along the run are selected.
- **3D Fill** — a connected blob out of the clicked face; tap for the first shell, hold to grow.
  Contained by walls, end markers, and start-marker space.
- Everywhere: an **end marker stops a column or fill even floating in air**, and a finished column
  never resumes past it.

## Markers

Turquoise **starts** and amber **ends** aim and bound every tool. They aren't real blocks: every
marker mode reaches to the **Marker dist** setting, far beyond block reach. Saved per world and
dimension; **Clear Markers** (Settings) wipes them.

- **Marker** — left-click starts, right-click ends; click-drag for straight axis-locked lines;
  starting on a marked block removes instead.
- **Marker corners** — two clicks mark a volume: left-click one corner (its face sets the column
  direction, a thin box follows your aim), right-click the opposite — starts fill one plane, ends
  the other. With Auto end on, columns hug the terrain and empty columns are skipped.
- **Marker draw** — a freehand pencil: sweep to spatter starts over whatever the crosshair
  touches, right-click for ends (auto ends scan along the stroke's starting face).
- Clicking a marked block toggles it off in any marker mode; **Ctrl** clears its connected plane.
- **Auto end marker** (Settings) — each start scans out from the clicked face and marks the first
  non-air block (all air → max distance), with a blue face + arrow preview.

## Gradient painting

The active palette blended along your build — between markers or free-hand.

- **Between markers**: mark a start and end, click the face the line runs out of and hold — the
  palette stretches start→end, Automatic segments anchoring to the real marker blocks.
- **Anywhere else**: the gradient grows out of the clicked face, sized by the palette's Sizing
  mode. **Paint memory** continues a half-finished gradient when re-clicked (cleared when you
  switch or edit palettes, or after the Settings idle timer); a *finished* gradient starts a
  fresh one on top.
- **3D gradients**: sphere-ish blends from the centre out; each fill remembers its centre. 3D
  needs the strip to end in a real block (Auto at the start is fine).
- Honest ramps: running out of a palette block stops the paint with an "out of X" message — no
  silent substitutions.

## Noise painting

A seedable 3D noise field: valleys get the top of your palette's strip, peaks the bottom — with
the palette's noise type (Smooth / Perlin / Fractal), per-axis Scale, and Seed.

- **Region fill**: bound a region with start/end marker pairs, switch to 3D Fill, right-click an
  empty spot inside — the whole region floods at once. Or paint anywhere: Single / Face / 3D.
- The editor's noise cube previews it at real world coordinates starting at your feet — exactly
  what painting that spot would place. Drag a face to pan.

## Solid tool

One kind of block through any mode. **Left-click** a block in the list to select it (again to
clear), **right-click** to exclude it from the closest-match modes; the big preview shows your
pick (or a **?** when the mode decides at click time). **Match**: **Selected block**, **Exact
block** (copies the clicked block), **Closest colour / brightness** (nearest inventory match,
measured as the eye sees it — Oklab).

## Finder tab

Every placeable block in the game ranked by colour — discover blocks beyond your inventory.

- **Sort: Color** — a rainbow (grays dark→light, then hue families) — or **Brightness**.
- Click a block to centre it among its nearest colour neighbours, or pick a **pure colour** in the
  hue/saturation field (+ brightness slider) — a coloured marker line shows where it falls. The
  target button re-centres after scrolling.
- Colours come from each block's actual textures — resource packs included.

## Settings

- **Placement**, **Marker dist**, **Auto end marker**, **Fill voids first**, **Paint memory**
  — see above. **Missing blocks**: refuse to paint or skip absent segments. **Color match**:
  Perceptual (Oklab) or Classic.
- **Move helper text…** repositions the HUD lines with a live preview.

## Requirements

- **Minecraft 26.2** · **Java 25** · pick your loader's file:
  - **Fabric** — Fabric Loader 0.19.3+ and **Fabric API** for 26.2
  - **NeoForge** — no other dependency
- Client-side only — nothing to install on the server.
- Placement sends normal "use block" interactions the server validates (Litematica-style); an
  aggressive anti-cheat may rate-limit very fast fills.

---

*Open source (MIT) — [github.com/nullabork/fw-paint-fabric](https://github.com/nullabork/fw-paint-fabric)*
