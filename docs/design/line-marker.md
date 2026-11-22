# Line marker + Place in air

Design notes for the **Marker line** shape marker (wheel → Place → Shape markers) and the
**Place in air** setting it relies on. Decisions confirmed with the user on 2026-10-13.

## Place in air

Vanilla's server places a block into the *clicked* cell when that cell is replaceable (air,
water, tall grass): `BlockPlaceContext.replaceClicked`. `handleUseItemOn` only checks reach and
that the hit point lies within the clicked cell — no neighbour is required. So a cell with no
solid neighbour is placed by sending a use-item-on with `blockPos = cell`, hit location = its
centre. Same packet Litematica's Easy Place uses.

- Settings toggle **Place in air**, default **On**. Off restores the old behaviour (cells wait
  for a neighbour) for servers whose anti-cheat flags supportless placement.
- Cells with a real neighbour still click against it (natural orientation, least surprising).
- Shape walls as faces: `PaintPlacer.paintHit` walks the view ray (Amanatides–Woo) within reach;
  the first step from a shape cell to a non-shape cell (before any real block) is a hit on that
  outside cell's face. For that press `virtualWalls` makes cells outside every shape count as
  walls, so face floods, perpendicular runs and 3D fills treat the shape like a hollow of blocks.
- A click that hits nothing places **one block** in the cell at the edge of block reach along the
  view (a green cell previews it), facing the player. Gradient/Noise use the palette's first
  colour; Solid needs a ✓ block (nothing to match); Pattern anchors its corner there.

## Line

A cubic Bézier spline through **main nodes**. Each node stores its position (off-grid, any
`Vec3`) and two **curve handles** stored *relative* to the node, so moving a node carries its
handles. A fresh line is two nodes with zero handles = a straight line.

### Creating

1. In Marker line mode, left-click sets the start: the centre of the grid cell in front of the
   aimed face (where a block placed there would sit); aiming at sky puts it in the cell 16
   blocks out. Main nodes always live on grid-cell centres — new points, double-click-inserted
   nodes, and released node / move drags snap there (handles and end controls stay free) — so
   straight lines are easy.
2. A live straight preview follows the aim; the second left-click sets the end.
   Right-click cancels a half-made line.

### Editing (the marker modes — Line / Spline or Shape — paint tool in hand)

Outside the marker modes a line only shows (curve + tube, no nodes, handles or buttons) and
keeps constraining painting; clicks pass straight through it, so it can't be nudged by accident.


- **Looking at a line** highlights it (ray-to-curve distance), like other shape markers.
- **Main nodes** are small cubes; **curve handles** are small spheres joined to their node by a
  thin stalk. Handles show on the looked/selected line.
- **Drag** (hold left) a node or handle: it moves on the plane through the dragged point,
  perpendicular to the line from the eye to that point. The plane is rebuilt every tick, so
  turning on the spot doesn't tilt it but walking around the point does. **Scroll while
  dragging** pushes the point a block further away / nearer along that eye → point line.
- Two rebindable modifiers (Controls → FW Paint):
  - **Axis lock** (default Alt) + drag a main node, handle or the move node: locks it to the
    world axis it's mostly moving along (picked after ~0.3 blocks, kept until released); it
    follows the point on that axis nearest the view ray, and a long axis-coloured guide line
    shows the track.
  - **Link** (default Ctrl) + drag a handle: mirrors the opposite handle through the node
    (smooth corner); + a size node: both ends get the same radius; + a twist node: both ends
    get the same twist. It shares Ctrl with clear-connected by default (26.3 keeps a list of
    mappings per key, so both report held).
- A handle dragged within ~0.35 blocks of its node **snaps** onto it (zero length → sharp corner).
- **Right-drag** a handle: it slides along its own direction from the node (angle kept, length
  follows the point on that ray nearest the view); Link mirrors the opposite handle.
- **Double-click a main node**: any handles → sharp corner (both zeroed); sharp → smooth
  Catmull-Rom handles (± a sixth of the neighbour-to-neighbour span, doubled at the ends).
- **Double-click** the line inserts a node at the point on the curve closest to the view ray. The
  segment is split with de Casteljau, so the curve's shape is unchanged.
- **Middle-click** a middle node removes it; middle-click anywhere else on the line deletes the line.

### Profile (tube)

Floating **billboard buttons** sit above *both* ends (a row each, offset so they never cover a handle),
always face the camera, and draw on top of terrain. Buttons: **circle, square, triangle, star**
(click the active one again to remove the profile), **Shell / Solid**, and **?** (opens the
Help tab at the Marker line page: a five-step TL;DR, then cheat-sheet / shapes / painting / tips
sub-pages, one short line per item).

The tube is the profile swept along the curve, built slice by slice (`TubeBuild`):

- Each curve sample (every 0.5 blocks) owns a thin slab across the curve: its normal plane ± half
  the gap to its neighbours *measured at the profile's rim*, so slabs meet even on the outside
  of tight bends. The end slabs stop half a block past the ends (flat caps).
- Candidate cells come from a 0.7-spaced grid laid across each slab (≤ √½, so every cell a slice
  cuts gets a point). A cell is in when its centre lies in some sample's slab and inside the
  profile there, in that sample's rotation-minimising frame turned by the interpolated twist, at
  the interpolated radius. A union of slices: bends merge, nothing is missed.
- **Shell** (default) keeps inside cells with an outside 6-neighbour; **Solid** keeps them all.

Performance (measured with `FWPAINT_PERF`, see `DebugRender`):

- Builds run from an immutable snapshot on a background thread whenever the line changes; the
  previous result keeps drawing until the new one lands, so dragging a node on a huge tube never
  stalls a frame. Painting (`contains` / `segmentFor`) builds synchronously so it always sees the
  current shape.
- Draw geometry is precomputed per build: exposed faces greedy-merged into rectangles per
  plane, convex edges merged into straight runs and drawn as one strip set across the corner.
  A frame only copies floats out. (A 5k-cell tube went from ~6.6 ms/frame + 12 ms per rebuild on
  the render thread to ~1 ms/frame with rebuilds off-thread.)

### End controls (once a profile exists)

- **Size nodes** (coloured blocks) at each end on the profile rim: drag to set that end's radius
  independently — radius is interpolated along the line (tentacles).
- **Twist nodes** at each end: drag around the end to rotate the profile there; the twist is
  interpolated between the ends.
- **Move node** beside the line: drag to move the whole line (drag-plane rules).
- **Control cluster**: the move node, the cog and the rotate node ride together 1.5 blocks off
  the tube's skin, on the side the player stands (the direction from the line to the eye, across
  the line), so they come round the tube with the player and are never underneath it or under
  the ground. A player inside the tube (a big shape) gets them 4 blocks in from where they
  stand instead, never out through the far wall. Along the line they sit beside the spot
  nearest the player and follow as the player walks; only when that would be nearer than 2.5
  blocks do they sit a little way along instead (on the side they were already on). Standing
  right against the skin pulls them in to 0.3 off it, so they are never behind the player. A
  shape's cluster keeps a block clear of its two faces (off the ground), and a player over one
  of a shape's faces (standing on a box, above a flat disc) gets it on that face. They line up
  side by side across the player's view, level with the horizon. All of it depends only on the
  player's position, never on where they look, so the cluster holds still under the crosshair.
  A drag or the open toolbar holds it where it was when taken hold of; while the line is being
  moved the held cluster moves rigidly with it, so an axis-locked move stays exactly on its
  axis.
- **Rotate node** (blue, globe icon): LMB toggles rotate mode for that line. Two gimbal rings are
  drawn round the whole line (radius = the line's furthest reach + 1), centred on the average of
  its nodes: green turns about the gimbal's Y axis, red about its X axis; a ring goes white under
  the crosshair. LMB-drag a ring anywhere along it to turn the line rigidly about that axis
  through the centre — nodes, handles, tube profile (every node's twist is compensated) and the
  gimbal's own axes, so the other ring turns too. With axis lock (Alt) held at the grab the drag
  is a **tilt** instead: only the grabbed ring's axis turns, about the other ring's axis, and the
  line is untouched (`LineShape.tiltGimbalFrom`), so a ring's plane can be re-aimed to any
  direction; the two axes stay at right angles. The pull is read against the circle the grabbed
  spot travels on about the turn axis. The modifier snaps to 15°. The gimbal axes are
  saved with the line (`gimbal` in the store). Rings lose the aim to every other control and to
  toolbar buttons. Nodes are not re-snapped to the grid after a turn.

### Painting

The tube is a region like boxes and rings: `ShapeMarkers.contains` and `segmentFor` include it.
Gradients use one ramp per line; each line picks its direction (`LineGradient`, second button
row): **Across** (default — per slice: each cell goes to its nearest curve sample, its height is
measured along that slice's "top" (the untwisted frame normal turned by the line's `gradAngle`),
and 0 … 1 runs from the lowest to the highest cell within a block either side of that slice along
the line; `LineShape.sliceFracs`. It no longer depends on where you look),
**Along** (arc-length fraction start → end), **Inside out** / **Outside in** (distance to the
nearest curve sample over the local radius). 3D Fill
inside a tube is the ordinary growing fill (centre = the aimed block, or the air cell at reach),
contained by the tube; with a Gradient it colours cells by the line's direction mode, normalised
over the whole tube, and may grow through all of it. With Place in air on, no scaffolding is
needed.

While a painting mode is active with Gradient picked, lines offer their gradient options without
being editable: looking into a tube brings out its cog, which opens a one-row toolbar
(`LineMarkers.paintSpec`) — the four directions and **Rendering**. The direction buttons also sit on the
middle row of the Line-mode toolbar; the two below are on the Rendering page (lines only).

- **Show as gradient** (`gradPreview`, saved; cleared by Reset): the tube's outside cell faces are drawn with the
  texture of the active palette's block for each cell's gradient position (Automatic segments,
  which have no block yet, in their flat colour); 69% opaque until the line's face opacity is set (`GradientPreview`, rebuilt when the
  tube, mode, angle or palette changes; tubes over 60 000 cells keep the plain tint).
- **Angle** (transient): a pink plane cuts the line 2.5 blocks along from the cluster, ringed, with
  **T** and **B** grips on the ring; dragging one sets `gradAngle` (saved; modifier = 15° steps),
  and turns Across on. Any other click, painting included, puts the tool away. A rigid turn of
  the line carries `gradAngle` with it.

### Physics (gravity)

The Line-mode toolbar's **Physics** button (bottom row) swaps in a page: Back · **Gravity** ·
**Undo drop**. Gravity finds a natural resting place for the marker before it is painted; it
moves the marker only and places nothing.

- `LinePhysics` treats the line as one rigid body: a ball at each end and every tube radius (min
  0.75 blocks) between, radius = tube radius + 0.5 (bare line 0.5), mass ∝ r³. Per substep (1/60 s,
  three a tick): gravity 22 blocks/s², a little drag, one deepest contact per ball against the
  block cubes it overlaps, six rounds of normal + friction impulses (no bounce), then a
  translation-only push out of remaining overlap. It rests after 12 ticks slow and touching.
- Ground = any block with a collision shape (so not water, plants or air), read client-side.
- A body with a ball centre inside a block is lifted a block at a time (up to 384) before it is
  let go ("pops up").
- Each tick the line is set to its pose at the grab turned and shifted to the body
  (`turnFrom` + translate), so tube profile, gimbal and gradient angle ride along. Nodes are not
  re-snapped to the grid.
- Ends: at rest (saved); Gravity pressed again (left where it is); grabbed by a drag or ring
  (caught); 45 s without settling (left); or falling 16 below the world's floor, e.g. over
  unloaded chunks (put back). **Undo drop** restores the pose from before the last drop (kept in
  memory only, not across restarts).

### Persistence

Lines save with the other shapes in `fw-paint-shapes.json` (per world + dimension).

## Drawn profile

A fifth profile, **Drawn**: the line sweeps a picture the player draws out of block cells.

- The picture (`ProfileDrawing`) is flat and one block thick, and lives in the world where it
  was drawn: an origin cell, the axis its plane faces, and (a, b) cell offsets. Moving or turning
  the line never moves it.
- Picking Drawn with nothing drawn goes straight into editing; otherwise the toolbar's pencil
  toggles editing. While editing the picture shows (translucent cells), a white frame marks the
  cell the next click changes, and clicks on nothing draw instead of making a new marker:
  LMB-drag draws (rubs out when the stroke starts on a drawn cell), RMB-drag rubs out. Line
  controls still take the aim first.
- The first cell fixes the plane: the cell in front of the clicked face, flat against that
  face (in the open: at Marker reach, facing the player). Later clicks take the cell in front
  of the aimed face when that is in the picture's layer, else where the crosshair crosses the
  layer's mid-plane.
- The picture remembers which way was up and right for the drawer (`top`, `right`): up and
  the right of the way they faced for a wall; away from them and to their right on a floor,
  like paper on a table.
- Swept: the picture is centred on the line (middle of its bounding box) and set across the
  line's first slice by `ProfileDrawing.orient`; the frame carries that along the line. A line
  running through the picture's plane sweeps it exactly as drawn, whichever way the line
  runs. A line running along the plane stands it up: top goes up (for a line straight up a
  wall picture, the way the drawer faced), right stays right; when the drawer's right runs
  along the line it reads the right way round looking from the line's start. It is scaled so
  half its longer side is the node radius + ½. At that natural size one
  cell is one block. Drawing more keeps every node's proportion to the picture. Per-node sizes
  scale it (blocky when enlarged); twist, shell / solid, caps and gradients work as for any
  profile. Limits: 96 cells across, 8192 cells.

## Shapes (the Shape tool)

A shape is a `LineShape` with `shape = true`: a straight two-node line whose nodes are its
bottom and top **faces** (the tube stops exactly on them, no half-block overshoot), with one
profile all the way. It reuses the line's control cluster, rotate gimbal, gradient modes and
angle tool, Rendering page, gravity, saving and paint constraint. Only its handles differ.

- **Creation** (Shape mode): LMB a corner cell, LMB the opposite corner (a wire box previews);
  RMB cancels. The result is an upright box: square profile, filled, gradient Along.
- **Kinds** (toolbar): Box (square), Cylinder (circle), Triangle, Star, Sphere (circle + `ball`:
  the profile swells from nothing at each face to full size half way, so a taller one is an
  egg). Hollow / Filled; hollow has **Capped** (off = open ends) and a wall thickness.
- **Size**: `r` (both nodes) is the half-width along the frame normal, `stretch` the depth for
  that width. Handles sit half way up: Width, Depth, and (hollow) Wall on the inside of the
  wall. Dragging Width or Depth changes that side only (oval / slab); the modifier keeps the
  proportions. The faces drag along the shape's own axis (height, at least 1). On release
  sizes snap to whole blocks and a move lands a whole number of blocks from where it began.
  Scroll: a face ±1 height; modifier+scroll or a side handle ±1 width; the wall handle ±1.
  RMB a side handle makes width and depth equal; RMB the wall handle resets it to 1.
- **Selection**: looking into a marker selects it, unless the selected one is also in the way of
  the crosshair. MMB on a control, or anywhere in the selected shape, deletes it.
- **Old saves**: the box and rings of earlier versions are converted on load (`ShapeStore`):
  the box to a box shape along its gradient axis; a ring to a round or square tube along its
  axis, hollow with the band as its wall and uncapped.
- **Dig mode** (`LineShape.dig`, lines and shapes; the pick on the toolbar steps Off → Inside →
  Outside; saved as `dig`): limits breaking blocks by hand, whatever is held and whatever the
  placement mode. `ShapeMarkers.digVerdict`: a block in any Outside marker never breaks. Inside
  markers count only while one of their cells is within block reach of the eye (measured to the
  block, like reach); then a block breaks only if it is in an Inside marker. "In" is the
  marker's cells, so a hollow shape is its walls. Hooks: Fabric `AttackBlockCallback` (first
  hit, and held in creative; survival re-enters it when the target changes); NeoForge
  `InputEvent.InteractionKeyMappingTriggered` on attack with a block under the crosshair. It is
  client side: it stops this player's own breaking, nothing else.
- **Known limits**: gravity treats a shape as a chain of balls along its axis, so a wide flat
  shape settles as if it were round.
