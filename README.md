# Gradient

A client-side [Fabric](https://fabricmc.net/) mod for **Minecraft 26.2** that builds smooth
**gradients out of blocks**. Mark a start and end block, choose how the gradient is measured
(colour, brightness, or texture analysis), and fill the space between with the blocks from your
inventory ordered into a gradient — placed the legit, multiplayer-safe way.

## Build / run

```bash
./gradlew build       # -> build/libs/gradient-1.0.0.jar  (also runs tests)
./gradlew runClient   # dev Minecraft 26.2 with the mod loaded
./gradlew test        # unit tests
```

Requires **JDK 25** (Minecraft 26.2). To install into a real instance, drop
`build/libs/gradient-1.0.0.jar` into a 26.2 Fabric instance's `mods/` folder (it must already
contain Fabric API for 26.2). See `fabric-26.2-mod-starter.md` for the 26.2 API notes.

## Get the latest build

- **Pre-built jars** are attached to each [GitHub Release](../../releases) and to every CI run under
  the [Actions tab](../../actions) (download the `gradient-jar` artifact).
- **Build it yourself** (needs JDK 25):

  ```bash
  git clone <repo-url>
  cd gradient
  ./gradlew build          # jar at build/libs/gradient-1.0.0.jar
  ```

Releases are automated via GitHub Actions (`.github/workflows/build.yml`): every push builds and
runs the tests, and pushing a tag like `v1.0.0` publishes a Release with the jar attached.

## Quick start

1. Press **K** to open the Gradient screen. On the **Settings** tab, pick a **Tool** (any item — you
   must be holding it for the mod to be active) and set the **Mode**.
2. **Marker mode** (`Active: Marker`): hold the tool and **left-click** blocks to place turquoise
   **start** markers, **right-click** for amber **end** markers.
3. **Place mode** (`Active: Place`): aim at the empty gap between a start and end marker and
   **hold right-click** — the gap fills with a gradient of blocks from your inventory.

## Keybinds

| Key | Action |
|---|---|
| **K** | Open / close the Gradient screen |
| **G** | Cycle mode: `Active: Marker` → `Active: Place` → `Disabled` |

Both are rebindable under Options → Controls → Key Binds → MISC. The mod only acts while you hold
the configured **Tool** item and the mode isn't `Disabled`.

## Markers (Marker mode)

- **Left-click** = start marker (turquoise), **right-click** = end marker (amber).
- **Click-and-drag** marks a straight line locked to one axis. Dragging (or clicking) **toggles**:
  start on an unmarked block adds, start on a marked block removes.
- While dragging you see an **edge outline**; on release the blocks become **filled faces**.
- An end marker is only allowed where a start marker is **axis-aligned and within 16 blocks**.
  Removing a start marker auto-removes any end markers it orphaned.
- Markers are **saved per world + dimension** (`config/gradient_markers.json`) and reload when you
  re-enter a world. **Clear Markers** (Settings tab) wipes them for the current world.

## Placement (Place mode)

Aim at the empty cells between a start and end marker and **right-click** (hold to fill ~10/s). Each
placement picks the next block of the gradient and chains outward from the start marker. Placement is
client-side and multiplayer-safe (Litematica-style): the chosen block is selected/swapped into the
hand and a normal "use on block" interaction is sent, so the server validates it. A block is read
from the **Source** (hotbar / inventory / both); main-inventory blocks are swapped into the hotbar
for the placement and swapped back.

## The gradient

Open the screen (**K**) → **Configure** tab. The left list shows the **placement sequence** in order:

- **white** = blocks that will be placed (top = start `[S]`, bottom = end `[E]`),
- **blue** = source blocks not in this preview (outside the budget/range, cut by Max steps, or
  dropped by Chaos this round),
- **red** = manually excluded.

Click a texture: **left** = preview start, **right** = preview end, **middle** = toggle exclude.
Exclusions persist and apply to real placement too.

### Settings (live-update the preview)

| Setting | What it does |
|---|---|
| **Gradient mode** | How a block's gradient value is measured (see below) |
| **Curve** | Easing of the gradient: Linear / Ease In / Ease Out / Ease In-Out / Step |
| **Source** | Where palette blocks come from: Hotbar / Inventory / Hotbar + Inv |
| **Deviation** | How far a block's colour may stray from the gradient line before it's dropped |
| **Chaos** | Dithering: chance a placement repeats the previous step or skips one (0 = clean) |
| **Max steps** | Cap on distinct blocks used (1–16) — a maximum, not a target |
| **Pixel %** | (Top-% modes only) fraction of each texture's pixels analysed |

### Gradient modes

Blocks are valued from their **actual texture pixels** (read from the texture PNG, cached):

- **Color** / **Brightness** — average colour / brightness of the whole texture.
- **Top % Dark Color** / **Top % Dark** — analyse only the darkest *Pixel %* of pixels, ordering by
  their colour / brightness (e.g. gradient by a texture's shadows).
- **Top % Light Color** / **Top % Light** — same for the lightest pixels.
- **B&W Diff** / **Color Diff** — order by how **different** each block's texture is from the start
  block (grayscale or full colour), so similar textures sit near the start and different ones near
  the end.

## Config

Stored in the instance's `config/` directory:

- `gradient.json` — tool, mode, and all gradient settings.
- `gradient_markers.json` — markers, keyed per world + dimension.

The **Settings** tab has a **Debug logging** toggle; when on, the gradient preview order is logged
(grep the game log for `[preview]`).

## Source overview

| File | Responsibility |
|---|---|
| `Gradient` | Client entry point: keybinds, interaction callbacks, HUD, shared mode state |
| `ToolMode`, `GradientMode`, `CurveFunction`, `GradientSource` | Settings enums |
| `MarkerManager` | Marker placement, drag logic, in-world rendering |
| `MarkerStore` | Per-world marker persistence |
| `GradientPlacer` | Place-mode targeting + the Litematica-style block placement |
| `GradientRamp` | Pure gradient-selection maths (ordering, deviation, distribution, chaos) |
| `TextureStats` | Pure texture pixel analysis (averages, top-%, signatures, diff) |
| `BlockTextures` | Reads block texture pixels and derives each block's gradient value |
| `GradientScreen` | The two-tab UI (Configure + Settings) |
| `config/` | Config model + JSON load/save |

## Tests

`./gradlew test` — `GradientRamp` and `TextureStats` carry the gradient/texture maths and are fully
unit-tested (ordering, deviation budget, range, max-steps, curves, chaos, pixel analysis, diffs).
