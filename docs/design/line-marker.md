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

### Editing (any mode, paint tool in hand)

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
- **Move node** beside the middle of the line: drag to move the whole line (drag-plane rules).

### Painting

The tube is a region like boxes and rings: `ShapeMarkers.contains` and `segmentFor` include it.
Gradients run **start → end along the curve** (segment index from arc length). **3D Fill aimed
into a tube floods the whole tube** for every paint type; with Place in air on, no scaffolding
is needed.

### Persistence

Lines save with the other shapes in `fw-paint-shapes.json` (per world + dimension).
