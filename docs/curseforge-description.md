# FW Paint

**Paint with blocks.** FW Paint is a client-side Fabric mod with three block-painting tools — **Solid**
(extrude walls and fill volumes), **Gradient** (smooth colour/brightness blends), and **Noise** (natural,
blotchy 3D patterns) — all using the blocks already in your inventory. Placement is done the legit,
multiplayer-safe way (normal block-place interactions the server validates), so it works on servers.

## Quick start

1. Press **K** → **Settings** tab → click **Paint tool** and pick any item (e.g. a stick). The mod is
   only active while you hold that item.
2. Hold the tool. Press **V** to switch paint type (Solid / Gradient / Noise), **G** to cycle the
   active type's mode. The helper text (top-left) always shows what you're holding and what it'll do.
3. **Marker** mode: left-click places **start** markers (turquoise), right-click places **end**
   markers (amber). Markers bound or constrain what you paint.
4. Switch to the type's action mode (Place / Extrude / 3D Fill) and right-click to paint.

## Keybinds

- **K** — open / close the FW Paint screen
- **V** — switch paint type: Solid → Gradient → Noise (only while holding the tool)
- **G** — cycle the active type's mode (only while holding the tool)
  - Solid: Marker → Extrude → 3D Fill → Disabled
  - Gradient / Noise: Marker → Place → Disabled

All rebindable under Options → Controls → Key Binds → MISC.

## The helper text

- Two lines, shown **only while the paint tool is in your hand**:
  - Line 1 — the active paint type (switch with **V**).
  - Line 2 — the active mode, coloured (orange Marker, green Place, aqua Extrude, magenta 3D Fill,
    grey Disabled).
- If it covers something, move it: **Settings tab → Move helper text…** — arrow buttons nudge it, the
  **Step** button cycles the nudge size (50/20/10/1 px), with a live preview.

## Solid tool

Places one kind of block — great for walls, columns, and filling volumes fast.

- **What it places** — the **Match** button on the Solid tab:
  - **Selected block** — always places the ✓ block from your list.
  - **Exact block** — places the same block as the one whose face you click (must be in your source;
    never places excluded blocks — you get a warning above the hotbar instead).
  - **Closest colour** / **Closest brightness** — the source block nearest the clicked block by
    average texture colour / brightness (excluded blocks are skipped).
- **Extrude mode** — builds straight lines out from the face you click:
  - No markers needed: right-click a face to place one block out from it; **hold** to keep extending
    that row/column until it's blocked or out of reach.
  - **Extrude a whole wall**: place start markers touching each other (a flat face — same plane
    relative to the direction you'll extrude), then click the face — every connected marker's column
    extrudes together, one layer at a time. Markers at a different height/offset aren't part of the
    face and stay put.
  - Re-clicking a half-built wall continues it — each column picks up from its own first air block.
  - **End markers** (optional, can be scattered): a column that runs into one stops; the rest continue.
- **3D Fill mode** — grows a connected blob of blocks outward from the face you click:
  - Tap = one small fill; **hold** = grows layer by layer. It spreads through open air only, so walls
    contain it.
  - **Start markers constrain it**: each marker casts a constraint space out from all six faces (up
    to the Marker-distance setting). A fill started **inside** that space can't leave it; a fill
    started **outside** can't enter it. End markers physically stop it.
- **Solid tab list**: **✓** picks the one block to place (press ✓, click a row — single selection,
  auto-disarms). **✗** marks rows excluded (stays armed until you click it again). The ✓ row is
  struck through while the Match mode isn't "Selected block".

## Gradient tool

A smooth blend from one block to another along a marked line.

- **Setup**: place a **start** and **end** marker in a straight line (Marker mode), then in **Place**
  mode right-click along the line — hold to fill block by block from the start side.
- **From: Markers / Block list** — where the gradient's endpoints come from:
  - **Markers** — the real blocks sitting at your markers (recalculated as you place).
  - **Block list** — exactly the [S]/[E] and preview order from the screen, no matter what the
    markers are made of.
- **Order modes** (how blocks are sorted): Color, Brightness, Top % Dark/Light (Colour or
  Brightness — analyses only each texture's darkest/lightest pixels, tuned by the **Pixel %**
  slider), B&W/Colour Diff (similarity to the start block), or **Pick** (fully manual).
- **Shaping**: **Curve** (Linear / Ease In / Ease Out / Ease In-Out / Step), **Deviation** (max
  colour distance a block may stray from the gradient before it's dropped), **Chaos** (chance to
  repeat/skip a step for a hand-made look), **Max steps** (cap on distinct blocks).
- **Pick mode**: click a row to +1 its number, right-click to −1 (0 removes it). Numbered blocks
  (low → high) become the exact sequence — same number = random pick between them.

## Noise tool

Fills a marked region with a natural pattern driven by a seedable 3D noise field — valleys get one
end of your block order, peaks the other.

- **Setup**: bound a region with start/end marker pairs (each column/row needs a start at one end and
  an end at the other), then in **Place** mode right-click an empty spot inside — it flood-fills the
  region with the pattern.
- **Noise settings**: type (Smooth / Perlin / Fractal), **Seed**, per-axis **Scale** (feature size,
  with a Lock XYZ toggle), plus its own Order / Deviation / Chaos / Max steps (separate from the
  gradient tool's).
- Here **Deviation** is a per-placement chance to vary a block to a colour-adjacent one — it doesn't
  change the step count.
- The tab shows a **live preview** grid — drag it to pan around the noise field.

## The block list (all tabs)

- Colours: **white** = in the placement sequence, **blue** = eligible but not picked, **green** =
  must-use / the ✓ block, **red** = excluded. [S]/[E] tags mark the current endpoints.
- Buttons (gradient/noise tabs): **[S]** set start, **[E]** set end, **✓** must-use, **✗** exclude.
  A yellow key under the list explains the active buttons; it changes in Pick mode.
- **Shortcuts on every list**: **double-click** un-excludes a red row / toggles ✓; **middle-click**
  un-ticks a green row / toggles exclusion.
- Shows 10½ rows max with a slim scrollbar — scroll the mouse wheel over it.
- **Source** (above each list) picks where blocks come from: Hotbar / Inventory / both.

## Markers & other settings

- Markers are saved per world and dimension; **Clear Markers** (Settings tab) wipes them.
- **Marker dist** — max start↔end distance for gradient/noise lines, and how far Solid marker
  constraints reach.
- **Auto end marker** — placing a start marker also drops an end marker along your look direction.
- Each paint type remembers its own mode; the Settings tab also has per-type mode buttons.

## Requirements

- **Minecraft 26.2** · **Fabric Loader 0.19.3+** · **Fabric API** for 26.2 · **Java 25**
- Client-side only — nothing to install on the server.
- Placement sends normal "use block" interactions the server validates (Litematica-style); an
  aggressive anti-cheat may rate-limit very fast fills.

---

*Open source (MIT) — [github.com/nullabork/fw-paint-fabric](https://github.com/nullabork/fw-paint-fabric)*
