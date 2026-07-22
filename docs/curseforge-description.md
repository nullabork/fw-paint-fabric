# FW Paint

Open source (MIT) — [github.com/nullabork/fw-paint-fabric](https://github.com/nullabork/fw-paint-fabric)

**Paint with blocks.** A client-side mod (Fabric **and** NeoForge) with three paints — **Solid**
(one block: walls, columns, volumes), **Gradient** (smooth colour/brightness blends), **Noise**
(natural, blotchy 3D patterns) — all using the blocks already in your inventory, plus a **Finder**
tab that ranks every block in the game by colour. Any paint works through any placement mode, with
or without markers. Placement is the legit, multiplayer-safe way (normal block-place interactions
the server validates), so it works on servers.

## Quick start

1. Press **K** → **Settings** → **Paint tool**: pick any item (e.g. a stick). The mod is active
   only while you hold it.
2. **V** switches paint type, **G** cycles placement mode — the helper text (top-left) shows both.
3. Right-click a block face to paint: a tap places one layer, holding keeps going. The green face
   tint + arrows preview exactly what the click will grow, and which way.
4. Markers are optional: they bound what you paint and give gradients their endpoints.
5. Lost? The **Help** tab is a drill-down in-game manual, always showing your current keybinds.

## Keybinds

- **K** — open / close the FW Paint screen
- **V** — switch paint type (or the **Use** button on each tool tab)
- **G** — cycle placement mode: Marker → Marker corners → Marker draw → Single → Face → 3D Fill →
  Disabled
- **L-Ctrl** (hold + click) — a marker-removing click clears the whole connected plane
- All rebindable under Options → Controls → Key Binds → MISC.

## Placement modes

Where blocks go, shared by all three paints. The **green face tint + arrow** previews every face
the next click grows from, and rides the advancing fronts while you hold.

- **Single** — one column out of the clicked face; re-clicking continues from its first air gap.
- **Face** — the whole connected plane extrudes together: click a wall's top to raise it, its side
  to thicken it. A start marker behind the clicked column selects the connected marker group
  instead. **Fill voids first** (Settings) levels the lowest columns before stacking new layers.
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

## Solid tool

One kind of block through any mode; the **Match** button picks which: **Selected block** (the ✓
block in your list), **Exact block** (copies the clicked block), **Closest colour / brightness**
(nearest inventory match to the clicked block, measured as the eye sees it — Oklab). Blocks marked
**✗** are never placed, even on an exact match.

## Gradient tool

A smooth blend from one block to another — along markers or free-hand.

- **Between markers**: mark a start and end block, click the face the line runs out of and hold —
  the gradient stretches start→end, marker blocks included as its first and last steps.
- **Anywhere else**: the picker list's [S]→[E] steps grow out of the clicked face, one block per
  step. **Gradient memory** continues a half-finished gradient when re-clicked (cleared when the
  picker changes or after the Settings idle timer).
- **3D gradients**: sphere-ish blends from the centre out; each fill remembers its centre.
- The helper text always says where the endpoints come from (markers, picker, or a split pair).
- **Order modes**: Color, Brightness, Top % Dark/Light (tuned by **Pixel %**), B&W/Colour Diff, or
  **Pick** (fully manual numbering). Perceptual colour (Oklab) by default — **Color match** in
  Settings switches to the classic maths.
- **Shaping**: **Curve** (drawn as its shape on the button: Linear / Ease In / Ease Out /
  Ease In-Out / Step), **Variation** (similar blocks stand in for a step), **Chaos** (repeat/skip
  dither), **Step length** (steps randomly run longer/shorter), **Max steps**.
- **The curve strip**: the thin middle column stacks every step as a band of its block — band
  height = that step's share of the fill. **Drag the side handles** to reshape the shares: the
  curve flips to **C** (custom) and placement uses exactly what you drew. Cycling the curve snaps
  back to a preset shape.
- **Live preview**: an open-topped cylinder placed with the real pipeline — top rim = start,
  bottom = end, every column rolling its own Chaos / Step length / Variation. **⛶** expands it
  full screen (✗ or Esc closes).

## Noise tool

A seedable 3D noise field: valleys get one end of your block order, peaks the other. Always
ordered from the picker list.

- **Region fill**: bound a region with start/end marker pairs, switch to 3D Fill, right-click an
  empty spot inside — the whole region floods at once. Or paint anywhere: Single / Face / 3D.
- **Settings**: type (Smooth / Perlin / Fractal), **Seed**, per-axis **Scale** with Lock XYZ, plus
  its own Order / **Curve + strip** / Variation / Chaos / Max steps.
- **Live preview**: an isometric cube sampled at real world coordinates starting at your feet —
  exactly what painting that spot would place. Drag a face to pan along it (side faces pan up and
  down too); **⛶** expands it full screen (✗ or Esc closes).

## Finder tab

Every placeable block in the game ranked by colour — discover blocks beyond your inventory.

- **Sort: Color** — a rainbow (grays dark→light, then hue families) — or **Brightness**.
- Click a block to centre it among its nearest colour neighbours, or pick a **pure colour** in the
  hue/saturation field (+ brightness slider) — a coloured marker line shows where it falls. The
  target button re-centres after scrolling.
- Colours come from each block's actual textures — resource packs included.

## The block list (all tabs)

- **White** = placement sequence, **blue** = eligible but unused, **green** = must-use / ✓,
  **red** = excluded; [S]/[E] tag the endpoints. A yellow key under the list explains the buttons.
- Buttons: **[S]** / **[E]** set endpoints, **✓** must-use, **✗** exclude. **Double-click**
  toggles ✓, **middle-click** toggles exclusion; mouse wheel scrolls.
- **Source** (above each list): Hotbar / Inventory / both.

## Settings

- **Placement**, **Marker dist**, **Auto end marker**, **Fill voids first**, **Gradient memory**
  — see above. **Color match**: Perceptual (Oklab) or Classic.
- **Move helper text…** repositions the HUD lines with a live preview. Each tool tab has a
  **Use** button; the screen opens on the active paint's tab.

## Requirements

- **Minecraft 26.2** · **Java 25** · pick your loader's file:
  - **Fabric** — Fabric Loader 0.19.3+ and **Fabric API** for 26.2
  - **NeoForge** — no other dependency
- Client-side only — nothing to install on the server.
- Placement sends normal "use block" interactions the server validates (Litematica-style); an
  aggressive anti-cheat may rate-limit very fast fills.

---

*Open source (MIT) — [github.com/nullabork/fw-paint-fabric](https://github.com/nullabork/fw-paint-fabric)*
