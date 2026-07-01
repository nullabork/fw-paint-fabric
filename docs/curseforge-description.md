# FW Paint

**Paint with blocks.** FW Paint is a client-side Fabric mod that builds smooth **colour / brightness
gradients** and fills regions with **3D noise patterns**, using the blocks already in your inventory.
It places blocks the legit, multiplayer-safe way (no cheats), so it works on servers too.

![Noise fill](https://raw.githubusercontent.com/nullabork/fw-paint-fabric/master/docs/images/noise%20placed.png)

## What it does

Mark out a region in the world, choose how blocks should be ordered, and FW Paint fills it for you:

- **Gradient tool** — a smooth blend from one block to another across a line or wall.
- **Noise tool** — a natural, blotchy pattern driven by a seedable 3D noise field (great for rock,
  terrain, and abstract textures).

Everything is driven from one screen (press **K**); the blocks come straight from your hotbar/inventory.

## How it works

1. **Mark the area** — hold your tool in **Marker** mode and click to place **start** markers
   (turquoise) and **end** markers (amber). They bound the region you fill.
2. **Choose blocks + style** — open the screen (**K**), pick how the gradient is ordered, and tune it.
3. **Fill it** — switch to **Place** mode and click in the region.

![Markers](https://raw.githubusercontent.com/nullabork/fw-paint-fabric/master/docs/images/top%20bottom%20makers.png)

## The gradient tool

Order your blocks into a smooth ramp between a start and end. Choose how "distance" is measured
(average colour, brightness, the colour of a texture's darkest/lightest pixels, or how *different*
each texture is from the start), the **curve** (ease in/out, steps), **chaos** (for a hand-made
look), and how many distinct steps to use.

![Gradient tab](https://raw.githubusercontent.com/nullabork/fw-paint-fabric/master/docs/images/defineing%20gradient.png)
![Gradient result](https://raw.githubusercontent.com/nullabork/fw-paint-fabric/master/docs/images/slightly%20chaotic%20gradient.png)

## The noise tool

The noise tool samples a **3D noise field** at each block's position and maps it onto your block
order — valleys get one end of the gradient, peaks the other. Pick a noise type (Smooth / Perlin /
Fractal), a **seed**, and the feature **scale**; a live preview shows exactly which blocks appear.

![Noise tab](https://raw.githubusercontent.com/nullabork/fw-paint-fabric/master/docs/images/define%20noise.png)

## Pick mode

For full manual control, set the order mode to **Pick**: select a block and press **+ / −** to
number it. Numbered blocks (low → high) become the exact sequence used.

## Keybinds

- **K** — open / close the FW Paint screen
- **G** — cycle the held tool's mode (Marker → Place → Disabled)

Both are rebindable under Options → Controls → Key Binds → MISC.

## Requirements

- **Minecraft 26.2**
- **Fabric Loader** 0.19.3+
- **[Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api)** for 26.2 (required)
- **Java 25**

Client-side only — you don't need it on the server.

---

*Open source (MIT) — [github.com/nullabork/fw-paint-fabric](https://github.com/nullabork/fw-paint-fabric)*
