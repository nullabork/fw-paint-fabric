package co.fax.wang.shape;

import co.fax.wang.MarkerManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Block-marker groups: start markers that touch (diagonals included) on one plane, plus the end
 * markers paired with them — exactly what the old modifier-click clear treated as connected. Each
 * group gets one grey cog, one block off the marked face at the group's corner nearest the player,
 * shown only for the group under the crosshair and the one last touched. The cog opens the group's
 * toolbar: Re-place ends (Auto end marker for just this group, even with the setting off) · Flip
 * (swap starts and ends) · Delete ends · Delete group.
 *
 * <p>Also owns the middle-click delete of block markers: MMB removes the one marker under the
 * crosshair, modifier+MMB its whole group.
 *
 * <p>The group's plane comes from the face its markers were clicked on (this session), else the
 * direction of its paired ends, else the aimed face.
 */
public final class GroupMarkers {

    private GroupMarkers() {}

    /**
     * A group's toolbar / cog owner: named by its lowest start marker and its face, so aiming any
     * member finds the same one.
     */
    record Owner(BlockPos seed, Direction face) {}

    /** One group: its plane's face (the side the ends go), start markers and paired end markers. */
    record Group(Owner owner, Direction face, List<BlockPos> starts, List<BlockPos> ends) {}

    // Aim (group modes only): the block marker under the crosshair, and a cog.
    private static BlockPos aimedMarker;
    private static Direction aimedFace;
    private static Owner aimedCog;
    private static double cogDist = Double.POSITIVE_INFINITY;

    // Groups recomputed only when the markers change.
    private static final Map<Object, Group> cache = new HashMap<>();
    private static int cacheVersion = -1;

    // ---- groups -----------------------------------------------------------------------------

    private static Map<Object, Group> cache() {
        if (cacheVersion != MarkerManager.version) {
            cache.clear();
            cacheVersion = MarkerManager.version;
        }
        return cache;
    }

    /** The group a start or end marker at {@code marker} belongs to, or null if it is neither. */
    static Group at(BlockPos marker, Direction hint) {
        if (marker == null) return null;
        Object key = java.util.List.of(marker, hint == null ? "" : hint);
        Map<Object, Group> c = cache();
        if (c.containsKey(key)) return c.get(key);
        Group g = compute(marker, hint);
        c.put(key, g);
        return g;
    }

    /** The group an owner names (null when its seed is no longer a start marker). */
    static Group of(Owner o) {
        if (o == null || !MarkerManager.startMarkers.contains(o.seed())) return null;
        return at(o.seed(), o.face());
    }

    private static Group compute(BlockPos marker, Direction hint) {
        Set<BlockPos> starts = MarkerManager.startMarkers, ends = MarkerManager.endMarkers;
        BlockPos seed = marker;
        Direction face;
        if (starts.contains(marker)) {
            face = faceOf(marker, hint);
        } else if (ends.contains(marker)) {
            // An end belongs to the group of its nearest lined-up start.
            Direction toStart = nearestAlong(marker, starts);
            if (toStart == null) return null;
            int k = distanceAlong(marker, toStart, starts);
            seed = marker.relative(toStart, k);
            face = toStart.getOpposite();
            Direction recorded = MarkerManager.startFaces.get(seed);
            if (recorded != null) face = recorded;
        } else {
            return null;
        }
        List<BlockPos> groupStarts = MarkerManager.connectedPlane(starts, seed, face.getAxis());
        Set<BlockPos> groupEnds = new LinkedHashSet<>();
        int max = MarkerManager.maxEndDistance();
        for (BlockPos s : groupStarts) {
            for (Direction d : new Direction[]{face, face.getOpposite()}) {
                for (int k = 1; k <= max; k++) {
                    BlockPos p = s.relative(d, k);
                    if (ends.contains(p)) groupEnds.add(p);
                }
            }
        }
        BlockPos low = groupStarts.get(0);
        for (BlockPos s : groupStarts) if (s.compareTo(low) < 0) low = s;
        return new Group(new Owner(low, face), face, groupStarts, new ArrayList<>(groupEnds));
    }

    /**
     * Which way a start's group faces: the face it was clicked on, else toward its nearest
     * paired end, else the aimed face (what the old modifier-click clear used), else up.
     */
    private static Direction faceOf(BlockPos start, Direction hint) {
        Direction recorded = MarkerManager.startFaces.get(start);
        if (recorded != null) return recorded;
        Direction toEnd = nearestAlong(start, MarkerManager.endMarkers);
        if (toEnd != null) return toEnd;
        return hint != null ? hint : Direction.UP;
    }

    /** The direction of the nearest marker of {@code set} lined up with {@code p} within reach. */
    private static Direction nearestAlong(BlockPos p, Set<BlockPos> set) {
        int best = Integer.MAX_VALUE;
        Direction out = null;
        for (Direction d : Direction.values()) {
            int k = distanceAlong(p, d, set);
            if (k > 0 && k < best) {
                best = k;
                out = d;
            }
        }
        return out;
    }

    private static int distanceAlong(BlockPos p, Direction d, Set<BlockPos> set) {
        int max = MarkerManager.maxEndDistance();
        for (int k = 1; k <= max; k++) {
            if (set.contains(p.relative(d, k))) return k;
        }
        return -1;
    }

    /** Cog: one block off the marked face, at the group's corner nearest the camera. */
    static Vec3 cogPos(Group g, Vec3 eye) {
        Direction.Axis axis = g.face().getAxis();
        Direction.Axis[] others = new Direction.Axis[2];
        int n = 0;
        for (Direction.Axis a : Direction.Axis.values()) if (a != axis) others[n++] = a;
        double[] pos = new double[3];
        int plane = g.starts().get(0).get(axis);
        pos[axis.ordinal()] = plane + 0.5 + g.face().getAxisDirection().getStep() * 1.0;
        for (Direction.Axis a : others) {
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (BlockPos s : g.starts()) {
                lo = Math.min(lo, s.get(a));
                hi = Math.max(hi, s.get(a));
            }
            double e = component(eye, a);
            boolean low = Math.abs(e - lo) <= Math.abs(e - (hi + 1));
            pos[a.ordinal()] = low ? lo + 0.5 - 0.6 : hi + 0.5 + 0.6;
        }
        return new Vec3(pos[0], pos[1], pos[2]);
    }

    private static double component(Vec3 v, Direction.Axis a) {
        return switch (a) {
            case X -> v.x;
            case Y -> v.y;
            case Z -> v.z;
        };
    }

    // ---- aim --------------------------------------------------------------------------------

    /**
     * The block marker under the crosshair (a walk along the view ray, so end markers floating
     * in the air count too) and any shown group cog the ray hits. Only in the block-marker modes.
     */
    static void updateAim(Minecraft mc, boolean groupMode) {
        aimedMarker = null;
        aimedFace = null;
        aimedCog = null;
        cogDist = Double.POSITIVE_INFINITY;
        if (!groupMode || mc.level == null) return;
        Vec3 eye = Raycast.eye(mc), dir = Raycast.look(mc);
        markerUnderCrosshair(mc);
        for (Object o : ShapeMarkers.cogOwners()) {
            if (!(o instanceof Owner owner)) continue;
            Group g = of(owner);
            if (g == null) continue;
            double t = Gizmo.rayCube(eye, dir, cogPos(g, eye), Gizmo.CTRL_HALF);
            if (t >= 0 && t < cogDist) {
                cogDist = t;
                aimedCog = owner;
            }
        }
    }

    /** Sets {@link #aimedMarker}: the first marker cell on the view ray before solid terrain. */
    private static void markerUnderCrosshair(Minecraft mc) {
        BlockHitResult hit = MarkerManager.targetedHit(mc);
        double reach = Math.max(4.5, MarkerManager.maxEndDistance());
        Vec3 eye = Raycast.eye(mc), dir = Raycast.look(mc);
        double stop = hit != null ? hit.getLocation().distanceTo(eye) + 0.05 : reach;
        BlockPos last = null;
        for (double d = 0; d <= stop; d += 0.1) {
            BlockPos c = BlockPos.containing(eye.add(dir.scale(d)));
            if (c.equals(last)) continue;
            last = c;
            if (MarkerManager.startMarkers.contains(c) || MarkerManager.endMarkers.contains(c)) {
                aimedMarker = c;
                aimedFace = hit != null && hit.getBlockPos().equals(c) ? hit.getDirection() : null;
                return;
            }
        }
    }

    static double cogDist() {
        return cogDist;
    }

    static Owner cogAimed() {
        return aimedCog;
    }

    static void dropAim() {
        aimedCog = null;
    }

    /** The group of the marker under the crosshair (block-marker modes), or null. */
    static Owner lookedOwner() {
        Group g = at(aimedMarker, aimedFace);
        return g == null ? null : g.owner();
    }

    /** Aim hint: the cog, or the marker under the crosshair. */
    static String hint() {
        String mod = MarkerText.mod();
        if (aimedCog != null) return "Cog · LMB options · MMB delete group";
        Group g = at(aimedMarker, aimedFace);
        if (g == null) return null;
        boolean start = MarkerManager.startMarkers.contains(aimedMarker);
        return (start ? "Start group · " + g.starts().size() : "End group · " + g.ends().size())
                + " · LMB cog options · MMB delete one · " + mod + "+MMB delete group";
    }

    // ---- deletes ----------------------------------------------------------------------------

    /**
     * Middle-click on block markers: the one under the crosshair, or (modifier held) its whole
     * group. False when the crosshair isn't on a block marker.
     */
    static boolean deleteAt(Minecraft mc, boolean connected) {
        BlockPos target = aimedMarker;
        Direction face = aimedFace;
        if (target == null) {
            // Outside the group modes there's no walk: the aimed block (any reach) decides.
            markerUnderCrosshair(mc);
            target = aimedMarker;
            face = aimedFace;
            if (target == null) {
                BlockHitResult hit = Raycast.aimedBlock(mc);
                if (hit == null) return false;
                target = hit.getBlockPos();
                face = hit.getDirection();
            }
        }
        if (connected) {
            Group g = at(target, face);
            if (g == null) return false;
            deleteGroup(g);
            return true;
        }
        boolean start = MarkerManager.startMarkers.contains(target);
        if (!MarkerManager.removeMarkerAt(target)) return false;
        MarkerText.say("Marker · " + (start ? "start" : "end") + " removed");
        return true;
    }

    static void deleteGroup(Owner o) {
        Group g = of(o);
        if (g != null) deleteGroup(g);
    }

    private static void deleteGroup(Group g) {
        MarkerManager.startMarkers.removeAll(g.starts());
        MarkerManager.endMarkers.removeAll(g.ends());
        MarkerManager.endMarkers.removeIf(p -> !MarkerManager.isEndAllowed(p)); // orphans
        MarkerManager.changed();
        if (MarkerToolbar.isOpen(g.owner())) MarkerToolbar.close();
        MarkerText.say("Markers · group removed (" + g.starts().size() + " starts, " + g.ends().size() + " ends)");
    }

    // ---- toolbar ----------------------------------------------------------------------------

    static MarkerToolbar.Spec toolbarSpec(Owner o, Minecraft mc) {
        Group g = of(o);
        if (g == null) return null;
        List<MarkerToolbar.Button> row = new ArrayList<>();
        row.add(new MarkerToolbar.Button(MarkerToolbar.REDO, false, false,
                "Re-place ends: run Auto end marker for this group now", () -> replaceEnds(g)));
        row.add(new MarkerToolbar.Button(MarkerToolbar.FLIP, false, false,
                "Flip: swap starts and ends, so the gradient runs the other way", () -> flip(g)));
        row.add(new MarkerToolbar.Button(MarkerToolbar.BIN_ENDS, false, true,
                "Delete this group's " + g.ends().size() + " end markers", () -> {
                    MarkerManager.endMarkers.removeAll(g.ends());
                    MarkerManager.changed();
                    MarkerText.say("Markers · " + g.ends().size() + " ends removed");
                }));
        row.add(new MarkerToolbar.Button(MarkerToolbar.BIN, false, true,
                "Delete the whole group (same as " + MarkerText.mod() + "+MMB)", () -> deleteGroup(g)));
        row.add(new MarkerToolbar.Button(MarkerToolbar.QUESTION, false, false,
                "Help: start and end markers", () -> Minecraft.getInstance()
                        .setScreenAndShow(co.fax.wang.GradientScreen.atHelpTopic("Markers"))));
        double x0 = Double.MAX_VALUE, y0 = x0, z0 = x0, x1 = -x0, y1 = x1, z1 = x1;
        List<BlockPos> all = new ArrayList<>(g.starts());
        all.addAll(g.ends());
        for (BlockPos p : all) {
            x0 = Math.min(x0, p.getX());
            y0 = Math.min(y0, p.getY());
            z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX() + 1);
            y1 = Math.max(y1, p.getY() + 1);
            z1 = Math.max(z1, p.getZ() + 1);
        }
        Vec3 cog = cogPos(g, Raycast.eye(mc));
        return new MarkerToolbar.Spec("Group · " + g.starts().size() + " starts · " + g.ends().size() + " ends",
                List.of(row), cog, new Vec3(x0, y0, z0), new Vec3(x1, y1, z1), List.of(cog));
    }

    /** Auto end marker again for just this group (works with the setting off). */
    private static void replaceEnds(Group g) {
        MarkerManager.endMarkers.removeAll(g.ends());
        for (BlockPos s : g.starts()) MarkerManager.autoPlaceEndFor(s, g.face());
        MarkerManager.changed();
        MarkerText.say("Markers · ends re-placed for " + g.starts().size() + " starts");
    }

    /** Starts become ends and ends become starts; the toolbar follows the flipped group. */
    private static void flip(Group g) {
        if (g.ends().isEmpty()) {
            MarkerText.say("Markers · this group has no ends to flip to — Re-place ends first");
            return;
        }
        Set<BlockPos> starts = MarkerManager.startMarkers, ends = MarkerManager.endMarkers;
        starts.removeAll(g.starts());
        ends.removeAll(g.ends());
        starts.addAll(g.ends());
        for (BlockPos p : g.ends()) MarkerManager.startFaces.put(p, g.face().getOpposite());
        ends.addAll(g.starts());
        MarkerManager.changed();
        Group flipped = at(g.ends().get(0), g.face().getOpposite());
        if (flipped != null) {
            MarkerToolbar.retarget(flipped.owner());
            ShapeMarkers.touch(flipped.owner());
        }
        MarkerText.say("Markers · group flipped — starts and ends swapped");
    }

    // ---- rendering --------------------------------------------------------------------------

    /** The shown groups' cogs. */
    static void render(SubmitNodeCollector col, PoseStack ps, Vec3 cam, Minecraft mc, boolean groupMode) {
        if (!groupMode) return;
        Vec3 eye = Raycast.eye(mc);
        Vec3[] ax = Gizmo.cameraAxes(mc);
        List<Vec3> cogs = new ArrayList<>();
        List<Boolean> aimed = new ArrayList<>();
        for (Object o : ShapeMarkers.cogOwners()) {
            if (!(o instanceof Owner owner)) continue;
            Group g = of(owner);
            if (g == null) continue;
            cogs.add(cogPos(g, eye));
            aimed.add(owner.equals(aimedCog));
        }
        if (cogs.isEmpty()) return;
        Gizmo.twoPass(col, ps, cam, e -> {
            for (int i = 0; i < cogs.size(); i++) Gizmo.cog(e, cogs.get(i), aimed.get(i), ax);
        });
    }
}
