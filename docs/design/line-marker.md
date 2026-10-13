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

1. In Marker line mode, left-click sets the start: the exact point the crosshair hits on a block
   (not grid-snapped); aiming at sky puts it 16 blocks out.
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
- A handle dragged within ~0.35 blocks of its node **snaps** onto it (zero length → sharp corner).
- **Alt + drag** a handle mirrors the opposite handle through the node (smooth corner).
- **Double-click** the line inserts a node at the point on the curve closest to the view ray. The
  segment is split with de Casteljau, so the curve's shape is unchanged.
- **Middle-click** a middle node removes it; middle-click anywhere else on the line deletes the line.

### Profile (tube)

Floating **billboard buttons** sit above the start end (offset so they never cover a handle),
always face the camera, and draw on top of terrain. Buttons: **circle, square, triangle, star**
(click the active one again to remove the profile) and **Shell / Solid**.

The tube is the profile swept along the curve:

- For each candidate cell, find the closest point on the curve (parameter *s*), build the
  rotation-minimising frame (T, N, B) there, rotate by the interpolated twist, and test the cell
  centre's (u, v) against the profile at the interpolated radius. This is a distance-field
  sweep: tight bends merge naturally instead of leaving gaps between stacked slices.
- Ends are cut flat (cells beyond an end along the tangent are outside).
- **Shell** (default) keeps inside cells with an outside 6-neighbour; **Solid** keeps them all.

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
