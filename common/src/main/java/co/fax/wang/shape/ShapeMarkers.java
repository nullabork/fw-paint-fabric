package co.fax.wang.shape;

import co.fax.wang.Gradient;
import co.fax.wang.PlacementMode;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime state for the region markers and the one input grammar they all share. Owns the
 * {@link RingShape} circle/square donuts (Ring mode) directly; the box lives in
 * {@link BoxMarkers}, lines in {@link LineMarkers}, block-marker groups in {@link GroupMarkers},
 * and the single floating toolbar in {@link MarkerToolbar}. Ported from the fw-rule shape tools
 * and adapted to FW Paint's placement-mode model.
 *
 * <p><b>Grammar</b> (the same on every marker): LMB puts each point and grabs / drags controls.
 * RMB is "the other one": aimed at a control, its one quick action; while creating, cancel. MMB
 * deletes the smallest piece under the crosshair (a line's middle node, else the marker);
 * modifier+MMB everything connected (a whole line, a whole block-marker group). Scroll pushes /
 * pulls the aimed thing along its axis; modifier+scroll grows / shrinks it. Double-click changes a
 * point. The modifier means "the other way / both ends", never an action of its own; Alt locks
 * any drag to an axis. Aiming a control names its keys on the action bar.
 *
 * <p><b>Aim</b>: each tick every marker's controls (cogs included) are ray-tested and the nearest
 * wins; a live drag keeps the aim. Only when no control is aimed does the open toolbar get the
 * crosshair. Every marker has a grey cog; cogs show only for the marker under the crosshair (it
 * lingers a moment after you look away, so you can reach the cog) and the last-touched one.
 *
 * <p><b>Rings</b> (Ring mode): three clicks create one — centre (the clicked face's axis is the
 * plane normal; aimed at nothing: a horizontal ring at Marker reach), radius A, radius B (equal =
 * one-wide outline, different = a band). Rings start round; their toolbar switches Circle |
 * Square. Drag the white centre to move, the green radius columns to resize, a square's blue
 * corners to turn it; RMB a control extrudes a layer (modifier: the other way). Scrolling while
 * the crosshair is on a ring slides it along its normal. Multiple rings allowed.
 *
 * <p>Regions render whatever the mode; their controls respond whenever the paint tool is in hand
 * (lines only in Line mode). Persisted per world+dimension via {@link ShapeStore}.
 */
public final class ShapeMarkers {

    // Ring visuals (fw-rule palette).
    private static final int RING_FILL = 0x33FFA000;        // committed ring cells (kept faint)
    private static final int RING_EDGE = 0xB0FFB830;        // bright silhouette edges on the band
    private static final int RING_EDGE_LOOKED = 0xC0FFFFFF; // edges go white while looked at
    // The on-top ghost pass draws through EVERYTHING (26.2 has no occluded-only pipeline), so
    // it stays at modest alpha: a faint cage through terrain, a slight glow where visible.
    private static final int RING_GHOST = 0x5CFFB830;       // edge cage through terrain
    private static final int RING_GHOST_LOOKED = 0xA8FFFFFF;
    private static final int RING_PREVIEW = 0x22FFA000;     // creation preview ring
    private static final int CONTROL_OUTLINE = 0xC0FFFFFF;  // white 1px edges when aimed
    private static final int HOVER_CENTER = Gizmo.alpha(co.fax.wang.ui.Ui.MOVE, 0x55);   // creation: pick centre
    private static final int HOVER_RADIUS = Gizmo.alpha(co.fax.wang.ui.Ui.SIZE, 0x66);   // creation: pick radii

    private enum ControlKind { CENTER, RADIUS_A, RADIUS_B, CORNER, COG }

    /** Who owns this tick's aim. */
    private enum Who { NONE, LINE, RING, BOX, GROUP }

    private static final List<RingShape> shapes = new ArrayList<>();

    /** The shape the crosshair is on (silhouette ray-hit — works through terrain), or null. */
    private static RingShape lookedShape;

    // Ring creation state: center+normal set after click 1, ctrlA after click 2.
    private static BlockPos pendingCenter;
    private static Direction.Axis pendingNormal;
    private static BlockPos pendingCtrlA;

    // Targeting/drag state.
    private static RingShape aimedShape;
    private static ControlKind aimedKind;
    private static int aimedCorner;
    private static double aimedDist = Double.POSITIVE_INFINITY;
    private static RingShape dragShape;
    private static ControlKind dragKind;
    // Rotation drag capture: the drag re-derives everything from these, so no rounding drift.
    private static double dragStartTheta;
    private static double dragCornerBaseAngle;
    private static double[] dragStartOffA, dragStartOffB;
    // Move / resize drag capture for the axis lock.
    private static BlockPos dragStartCell;
    private static Direction.Axis ringLockAxis;

    private static Who who = Who.NONE;
    private static boolean barHover;
    /**
     * A press the markers took (a control, cog or toolbar button) is still held: block markers
     * and painting must not pick it up when the aim slides off, or the toolbar closes under it.
     */
    private static boolean attackHeld, useHeld;
    private static boolean lastAttack, lastUse, lastPick;
    private static String currentKey; // world+dimension the loaded regions belong to

    /** The marker last created / edited / clicked (keeps its cog out). */
    private static Object lastTouched;
    /** A marker whose toolbar reopens on its Rendering page once no screen is up (see {@link #reopenStyle}). */
    private static Object reopenOwner;
    /** The marker last under the crosshair; lingers {@link #HOVER_LINGER} ticks after. */
    private static Object hoverOwner;
    private static int hoverTicks;
    private static final int HOVER_LINGER = 60;

    /** Ticks a scroll-grab survives without further scrolling before you must re-target. */
    private static final int SCROLL_GRACE_TICKS = 15;
    private static RingShape scrollShape;
    private static int scrollGrace;
    private static int saveDelay; // a scroll edit saves once the wheel settles
    private static long frame; // render-frame counter (silhouette rebuild throttling)

    // The step-1 creation message, shown a moment after a shape mode is picked.
    private static PlacementMode lastMode;
    private static int stepOneDelay;

    private ShapeMarkers() {}

    // ---- region queries (the contract PaintPlacer consumes) --------------------------------

    /** True when any region (box, ring or line) exists in this world. */
    public static boolean any() {
        return BoxMarkers.box != null || !shapes.isEmpty() || !LineMarkers.lines.isEmpty();
    }

    /** True when the cell is inside a region: the box volume, or a ring band × extrusion. */
    public static boolean contains(BlockPos cell) {
        if (BoxMarkers.box != null && BoxMarkers.box.contains(cell)) return true;
        for (RingShape s : shapes) {
            if (s.contains(cell)) return true;
        }
        for (LineShape l : LineMarkers.lines) {
            if (l.contains(cell)) return true;
        }
        return false;
    }

    /**
     * The cell's gradient segment through the region containing it (start plane → end plane),
     * or null when no region holds it or the region has no depth.
     */
    public static RingShape.Segment segmentFor(BlockPos cell) {
        if (BoxMarkers.box != null) {
            RingShape.Segment seg = BoxMarkers.box.segmentFor(cell);
            if (seg != null) return seg;
        }
        for (RingShape s : shapes) {
            RingShape.Segment seg = s.segmentFor(cell);
            if (seg != null) return seg;
        }
        for (LineShape l : LineMarkers.lines) {
            RingShape.Segment seg = l.segmentFor(cell);
            if (seg != null) return seg;
        }
        return null;
    }

    /** Region count for the Settings clear-button label. */
    public static int count() {
        return shapes.size() + (BoxMarkers.box == null ? 0 : 1) + LineMarkers.lines.size();
    }

    /**
     * True while the crosshair is on a marker control, a cog or the open toolbar (tool in hand):
     * clicks then belong to the markers — MarkerManager and PaintPlacer stand down.
     */
    public static boolean controlAimed() {
        return who != Who.NONE || barHover || attackHeld || useHeld
                || LineMarkers.controlAimed() || BoxMarkers.controlAimed() || dragShape != null;
    }

    /** The line whose tube holds {@code cell}, or null. */
    public static LineShape lineContaining(BlockPos cell) {
        for (LineShape l : LineMarkers.lines) {
            if (l.contains(cell)) return l;
        }
        return null;
    }

    /** Drops every region and any in-progress creation, and persists. */
    public static void clearAll() {
        shapes.clear();
        BoxMarkers.box = null;
        BoxMarkers.style = new MarkerStyle();
        BoxMarkers.reset();
        LineMarkers.clear();
        clearPending();
        resetTransient();
        saveCurrent();
    }

    private static void resetTransient() {
        aimedShape = null;
        dragShape = null;
        lookedShape = null;
        scrollShape = null;
        scrollGrace = 0;
        who = Who.NONE;
        barHover = false;
        lastTouched = null;
        hoverOwner = null;
        reopenOwner = null;
        MarkerToolbar.close();
    }

    // ---- shared marker state (cogs, last touched) ----------------------------------------------

    /** The marker was just created / edited / clicked: its cog stays out. */
    static void touch(Object owner) {
        lastTouched = owner;
    }

    /** Block markers were just placed at {@code pos}: their group is the last touched. */
    public static void touchGroup(BlockPos pos) {
        GroupMarkers.Group g = GroupMarkers.at(pos, null);
        if (g != null) lastTouched = g.owner();
    }

    /**
     * The block list opened from {@code owner}'s Rendering page has closed: put its toolbar back
     * up on that page (a screen closes the toolbar), so the look can be tuned further.
     */
    static void reopenStyle(Object owner) {
        reopenOwner = owner;
    }

    /** Whether {@code owner}'s cog shows: under the crosshair (lingering), last touched, or open. */
    static boolean cogShown(Object owner) {
        return owner != null && (owner.equals(hoverOwner) || owner.equals(lastTouched) || MarkerToolbar.isOpen(owner));
    }

    /** The (at most three) markers whose cogs show. */
    static List<Object> cogOwners() {
        List<Object> out = new ArrayList<>(3);
        for (Object o : new Object[]{hoverOwner, lastTouched, MarkerToolbar.owner()}) {
            if (o != null && !out.contains(o)) out.add(o);
        }
        return out;
    }

    // ---- world sync / persistence ----------------------------------------------------------

    private static void saveCurrent() {
        ShapeStore.store(currentKey, shapes, BoxMarkers.box, LineMarkers.lines);
    }

    /** Persist after an edit (lines, the box and rings share one store file). */
    static void saveLines() {
        saveCurrent();
    }

    /** A scroll edit: save once the wheel has been still for a moment. */
    static void scrolled() {
        saveDelay = SCROLL_GRACE_TICKS;
    }

    private static void syncWorld(Minecraft mc) {
        String key = worldKey(mc);
        if (!java.util.Objects.equals(key, currentKey)) {
            // Flush a pending scroll / drag edit into the world being left.
            if (dragShape != null || scrollGrace > 0 || saveDelay > 0) saveCurrent();
            saveDelay = 0;
            currentKey = key;
            BoxMarkers.box = ShapeStore.loadInto(key, shapes, LineMarkers.lines); // null key (main menu) clears
            BoxMarkers.reset();
            LineMarkers.resetTransient();
            clearPending();
            resetTransient();
        }
    }

    /** Stable per-world, per-dimension key, or null when not in a world. */
    private static String worldKey(Minecraft mc) {
        if (mc.level == null) return null;
        String dim = mc.level.dimension().identifier().toString();
        var singleplayer = mc.getSingleplayerServer();
        if (singleplayer != null) {
            return co.fax.wang.DebugScript.worldKeyPrefix() + "sp:" + singleplayer.getWorldData().getLevelName() + "|" + dim;
        }
        var server = mc.getCurrentServer();
        if (server != null) return "mp:" + server.ip + "|" + dim;
        return "local|" + dim;
    }

    private static void clearPending() {
        pendingCenter = null;
        pendingNormal = null;
        pendingCtrlA = null;
    }

    /**
     * Mouse-wheel hook from the loader shells. Lines first (a dragged point, or an aimed node),
     * then the box (an aimed handle, or its body), then rings: while the crosshair is on a ring —
     * any part of its band, not just a control — scrolling slides it along its extrusion axis
     * (scroll up = positive normal), modifier+scroll extrudes it. Once a ring scroll-grab starts
     * it survives aim slips for a short grace window. Returns true when consumed (the shells then
     * cancel the vanilla hotbar scroll).
     */
    public static boolean onScroll(double deltaY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || deltaY == 0) return false;
        if (!Gradient.holdingPaintTool(mc) || mc.gui.screen() != null) return false;
        if (LineMarkers.onScroll(deltaY)) return true;
        if (who == Who.BOX || (who == Who.NONE && aimedShape == null && lookedShape == null)) {
            if (BoxMarkers.onScroll(mc, deltaY)) return true;
        }
        // Sticky grab first: mid-scroll aim slips don't switch shapes.
        RingShape target = scrollGrace > 0 && scrollShape != null ? scrollShape
                : aimedShape != null ? aimedShape : lookedShape;
        if (target == null) return false;
        if (Gradient.modifierDown()) {
            // Modifier + scroll: extrude — up grows a layer along +normal, down shrinks it
            // (past one layer it flips out the other side, like right-click extrude).
            target.extrude(deltaY > 0 ? 1 : -1);
        } else {
            target.moveCenterTo(RingShape.offset(target.center, target.normal, deltaY > 0 ? 1 : -1));
        }
        scrollShape = target;
        scrollGrace = SCROLL_GRACE_TICKS;
        touch(target);
        return true;
    }

    // ---- client tick ------------------------------------------------------------------------

    /** Called from MarkerManager.tick with the resolved placement mode. */
    public static void tick(Minecraft mc, PlacementMode pm) {
        syncWorld(mc);
        if (mc.level == null || mc.player == null) return;

        if (scrollGrace > 0 && --scrollGrace == 0) { // scroll-grab decay: shape has settled
            scrollShape = null;
            saveCurrent();
        }
        if (saveDelay > 0 && --saveDelay == 0) saveCurrent();
        LineMarkers.tickPhysics(mc); // a dropped line keeps falling whatever is in hand

        boolean screenOpen = mc.gui.screen() != null;
        // Controls respond whenever the paint tool is in hand — the marker modes are only
        // needed to CREATE regions (box corners, ring clicks, line points).
        boolean handles = Gradient.holdingPaintTool(mc) && !screenOpen;
        boolean boxCreate = pm == PlacementMode.MARKER_BOX && !screenOpen;
        boolean ringCreate = pm == PlacementMode.MARKER_RING && !screenOpen;
        boolean groupMode = (pm == PlacementMode.MARKER || pm == PlacementMode.MARKER_DRAW) && !screenOpen;

        if (!boxCreate) BoxMarkers.cancelCreation(); // leaving the mode drops a half-made box
        if (!ringCreate) clearPending();             // ... or ring
        stepOne(pm, screenOpen);

        boolean attack = mc.options.keyAttack.isDown();
        boolean use = mc.options.keyUse.isDown();
        boolean pick = mc.options.keyPickItem.isDown();
        boolean attackEdge = attack && !lastAttack, useEdge = use && !lastUse, pickEdge = pick && !lastPick;
        lastAttack = attack;
        lastUse = use;
        lastPick = pick;

        if (!handles) {
            // Tool put away mid-edit: keep what the drag / scroll did.
            if (dragShape != null || scrollGrace > 0 || saveDelay > 0) saveCurrent();
            saveDelay = 0;
            dragShape = null;
            aimedShape = null;
            lookedShape = null;
            scrollShape = null;
            scrollGrace = 0;
            who = Who.NONE;
            barHover = false;
            attackHeld = false;
            useHeld = false;
            LineMarkers.updateAim(mc, pm, false);
            BoxMarkers.tickDrag(mc, false);
            BoxMarkers.updateAim(mc, false);
            GroupMarkers.updateAim(mc, false);
            MarkerToolbar.close();
            MarkerText.hint(null);
            return;
        }

        // ---- aim: every control is ray-tested, the nearest wins; a live drag keeps it ----------
        LineMarkers.updateAim(mc, pm, true);
        updateAimedControl(mc);
        BoxMarkers.updateAim(mc, true);
        GroupMarkers.updateAim(mc, groupMode);
        resolveAim();
        updateLookedShape(mc);
        if (reopenOwner != null) {
            Object o = reopenOwner;
            reopenOwner = null;
            MarkerToolbar.Spec s = specFor(o, mc, pm, groupMode);
            if (s != null) {
                if (!MarkerToolbar.isOpen(o)) MarkerToolbar.toggle(o, s);
                MarkerToolbar.showStyle(true);
                touch(o);
            }
        }
        MarkerToolbar.refresh(specFor(MarkerToolbar.owner(), mc, pm, groupMode));
        boolean dragging = LineMarkers.dragging() || dragShape != null || BoxMarkers.dragging();
        if (dragging) MarkerToolbar.close(); // the toolbar gets out of the way of a drag
        // A line's rotate rings are big enough to pass behind an open toolbar: its buttons win.
        if (who == Who.LINE && LineMarkers.ringAimed() && MarkerToolbar.updateHover(mc)) {
            LineMarkers.dropAim();
            who = Who.NONE;
        }
        barHover = who == Who.NONE && !dragging && MarkerToolbar.updateHover(mc);
        if (!barHover) MarkerToolbar.dropHover();
        updateHoverOwner(mc, groupMode && !attack && !use); // not mid-stroke over block markers
        MarkerText.hint(hint(pm, attack || use));
        if (attackEdge) attackHeld = who != Who.NONE || barHover;
        if (!attack) attackHeld = false;
        if (useEdge) useHeld = who != Who.NONE || barHover;
        if (!use) useHeld = false;

        // ---- LMB on the toolbar or a cog belongs to the toolbar ---------------------------------
        boolean clickTaken = false;
        if (attackEdge) {
            Object cog = cogAimed();
            if (barHover) {
                MarkerToolbar.press();
                clickTaken = true;
            } else if (cog != null) {
                MarkerToolbar.toggle(cog, specFor(cog, mc, pm, groupMode));
                touch(cog);
                clickTaken = true;
            } else {
                MarkerToolbar.close(); // LMB anywhere else closes it (and does its own thing)
            }
        }
        boolean free = who == Who.NONE && !barHover; // nothing aimed: creation / bodies

        // ---- lines (they own Line mode's clicks) ----------------------------------------------
        boolean lineOwns = !clickTaken && (who == Who.LINE || free);
        if (LineMarkers.tick(mc, pm, lineOwns, attack, attackEdge && !clickTaken, use, useEdge, pickEdge)) {
            if (!attack) dragShape = null;
            return;
        }

        // ---- LMB: grab a control, or create ----------------------------------------------------
        if (attackEdge && !clickTaken) {
            if (who == Who.RING && aimedKind != ControlKind.COG) {
                grabRing();
            } else if (who == Who.BOX) {
                BoxMarkers.grab(mc);
            } else if (free && boxCreate) {
                BoxMarkers.creationClick(mc);
            } else if (free && ringCreate) {
                creationClick(mc);
            }
        }
        if (dragShape != null) {
            if (attack) {
                applyDrag(mc);
            } else {
                dragShape = null;              // release keeps whatever the drag set last
                saveCurrent();
            }
        }
        BoxMarkers.tickDrag(mc, attack);

        // ---- RMB: a control's quick action, or cancel a creation ---------------------------------
        if (useEdge) {
            if (who == Who.RING && aimedKind != ControlKind.COG) {
                aimedShape.extrude(Gradient.modifierDown() ? -1 : 1); // +1 layer (modifier: the other way)
                touch(aimedShape);
                saveCurrent();
            } else if (who == Who.BOX) {
                BoxMarkers.quickAction();
            } else if (free && ringCreate && pendingCenter != null) {
                clearPending();
                MarkerText.say("Ring cancelled");
            } else if (free && boxCreate) {
                BoxMarkers.cancel();
            }
        }

        // ---- MMB: delete the smallest piece under the crosshair --------------------------------
        if (pickEdge && !barHover) {
            boolean connected = Gradient.modifierDown();
            RingShape ring = who == Who.RING ? aimedShape : who == Who.NONE ? lookedShape : null;
            if (who == Who.GROUP) {
                GroupMarkers.deleteGroup(GroupMarkers.cogAimed());
            } else if (who == Who.NONE && GroupMarkers.deleteAt(mc, connected)) {
                // a block marker (or its group) under the crosshair — smaller than a region around it
            } else if (ring != null) {
                removeRing(ring);
            } else if (who == Who.BOX || (who == Who.NONE && BoxMarkers.looked(mc))) {
                BoxMarkers.remove();
            }
        }
    }

    /** Pick the nearest aimed control across every marker kind; the others forget theirs. */
    private static void resolveAim() {
        double dl = LineMarkers.controlDist();
        double dr = dragShape != null ? 0 : aimedShape != null ? aimedDist : Double.POSITIVE_INFINITY;
        double db = BoxMarkers.controlDist(), dg = GroupMarkers.cogDist();
        double best = Math.min(Math.min(dl, dr), Math.min(db, dg));
        if (best == Double.POSITIVE_INFINITY) who = Who.NONE;
        else if (best == dl) who = Who.LINE;
        else if (best == dr) who = Who.RING;
        else if (best == db) who = Who.BOX;
        else who = Who.GROUP;
        if (who != Who.LINE && who != Who.NONE) LineMarkers.dropAim();
        if (who != Who.RING && dragShape == null) aimedShape = null;
        if (who != Who.BOX) BoxMarkers.dropAim();
        if (who != Who.GROUP) GroupMarkers.dropAim();
    }

    /** The marker whose cog is under the crosshair (it won the aim), or null. */
    private static Object cogAimed() {
        return switch (who) {
            case LINE -> LineMarkers.cogAimed();
            case RING -> aimedKind == ControlKind.COG ? aimedShape : null;
            case BOX -> BoxMarkers.cogAimed() ? BoxMarkers.OWNER : null;
            case GROUP -> GroupMarkers.cogAimed();
            case NONE -> null;
        };
    }

    /** What's under the crosshair now (control or body) keeps its cog out for a moment. */
    private static void updateHoverOwner(Minecraft mc, boolean groupMode) {
        Object looked = switch (who) {
            case LINE -> LineMarkers.lookedLine();
            case RING -> aimedShape;
            case BOX -> BoxMarkers.OWNER;
            case GROUP -> GroupMarkers.cogAimed();
            case NONE -> null;
        };
        if (looked == null && !barHover) {
            if (LineMarkers.lookedLine() != null) looked = LineMarkers.lookedLine();
            else if (lookedShape != null) looked = lookedShape;
            else if (BoxMarkers.looked(mc)) looked = BoxMarkers.OWNER;
            else if (groupMode) looked = GroupMarkers.lookedOwner();
        }
        if (looked != null) {
            hoverOwner = looked;
            hoverTicks = HOVER_LINGER;
        } else if (barHover) {
            hoverTicks = HOVER_LINGER;
        } else if (hoverTicks > 0 && --hoverTicks == 0) {
            hoverOwner = null;
        }
    }

    /** The aim hint for the action bar, or null ({@code held}: a mouse button is down). */
    private static String hint(PlacementMode pm, boolean held) {
        return switch (who) {
            case LINE -> LineMarkers.hint();
            case RING -> ringHint();
            case BOX -> BoxMarkers.hint();
            case GROUP -> GroupMarkers.hint();
            case NONE -> {
                if (barHover) yield MarkerToolbar.hoverLabel();
                // Bodies only speak in the marker modes (not while painting past them), and
                // not mid-stroke (sweeping over block markers would flicker through them).
                if (!pm.isMarker() || held) yield null;
                if (LineMarkers.lookedLine() != null) yield LineMarkers.hint();
                if (lookedShape != null) {
                    yield "Ring · scroll push/pull · " + MarkerText.mod() + "+scroll extrude · MMB delete";
                }
                if (pm == PlacementMode.MARKER || pm == PlacementMode.MARKER_DRAW) yield GroupMarkers.hint();
                yield null;
            }
        };
    }

    private static String ringHint() {
        if (aimedShape == null || aimedKind == null) return null;
        String mod = MarkerText.mod(), alt = MarkerText.axis();
        return switch (aimedKind) {
            case CENTER -> "Centre · drag move · " + alt + " axis lock · scroll push/pull · RMB +1 layer · MMB delete";
            case RADIUS_A, RADIUS_B -> "Radius · drag resize · RMB +1 layer · " + mod + "+RMB other way · MMB delete";
            case CORNER -> "Corner · drag turn · RMB +1 layer · MMB delete";
            case COG -> "Cog · LMB options · MMB delete ring";
        };
    }

    /**
     * Dev-only ({@link co.fax.wang.DebugScript}'s {@code aim}): where something of the marker whose
     * toolbar is open (else the last touched one) is — {@code what} is {@code cog}, {@code control}
     * (index {@code k} of its controls) or {@code button} (index {@code k} on its open toolbar).
     * Null when there's no such thing.
     */
    public static Vec3 debugAimPoint(Minecraft mc, String what, int k) {
        if ("button".equals(what)) return MarkerToolbar.debugButton(mc, k);
        MarkerToolbar.Spec spec = debugSpec(mc);
        if (spec == null) return null;
        if ("cog".equals(what)) return spec.cog();
        return k >= 0 && k < spec.controls().size() ? spec.controls().get(k) : null;
    }

    /**
     * Dev-only ({@link co.fax.wang.DebugScript}'s {@code style}): set a {@link MarkerStyle} field
     * of the marker whose toolbar is open (else the last touched one). False when there is none.
     */
    public static boolean debugStyle(String field, String value) {
        Object owner = MarkerToolbar.owner() != null ? MarkerToolbar.owner() : lastTouched;
        MarkerStyle st = owner instanceof RingShape r ? r.style : owner instanceof LineShape l ? l.style
                : owner == BoxMarkers.OWNER ? BoxMarkers.style : null;
        if (st == null) return false;
        switch (field) {
            case "block" -> st.block = "none".equals(value) ? "" : value;
            case "faceColor" -> st.faceColor = Integer.parseInt(value, 16);
            case "edgeColor" -> st.edgeColor = Integer.parseInt(value, 16);
            case "faceOpacity" -> st.faceOpacity = Integer.parseInt(value);
            case "edgeOpacity" -> st.edgeOpacity = Integer.parseInt(value);
            case "edgesThrough" -> st.edgesThrough = Boolean.parseBoolean(value);
            case "facesThrough" -> st.facesThrough = Boolean.parseBoolean(value);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static MarkerToolbar.Spec debugSpec(Minecraft mc) {
        PlacementMode pm = co.fax.wang.Gradient.currentPlacement(mc);
        boolean groupMode = pm == PlacementMode.MARKER || pm == PlacementMode.MARKER_DRAW;
        Object owner = MarkerToolbar.owner() != null ? MarkerToolbar.owner() : lastTouched;
        return specFor(owner, mc, pm, groupMode);
    }

    /**
     * The HUD's line readout while you look at a line — its curve, a control, or any block of its
     * tube — or its toolbar is open: its length along the curve and its node count, e.g.
     * "Line · 23.5 blocks long · 3 nodes". Null otherwise.
     */
    public static String hudLineInfo() {
        LineShape l = LineMarkers.hudLine(Minecraft.getInstance());
        if (l == null && MarkerToolbar.owner() instanceof LineShape open) l = open;
        if (l == null) return null;
        int nodes = l.curve.nodes.size();
        return "Line · " + LineMarkers.fmt(l.length()) + " blocks long · " + nodes + (nodes == 1 ? " node" : " nodes");
    }

    /** The open marker's toolbar for this tick, or null when it's gone / not editable now. */
    private static MarkerToolbar.Spec specFor(Object owner, Minecraft mc, PlacementMode pm, boolean groupMode) {
        if (owner == null) return null;
        if (owner instanceof LineShape l) {
            return pm == PlacementMode.MARKER_LINE ? LineMarkers.toolbarSpec(l) : LineMarkers.paintSpec(l, pm);
        }
        if (owner instanceof RingShape s) return shapes.contains(s) ? ringSpec(s) : null;
        if (owner == BoxMarkers.OWNER) return BoxMarkers.toolbarSpec(mc);
        if (owner instanceof GroupMarkers.Owner g) return groupMode ? GroupMarkers.toolbarSpec(g, mc) : null;
        return null;
    }

    /** A moment after Box / Ring / Line is picked, say what the first click does. */
    private static void stepOne(PlacementMode pm, boolean screenOpen) {
        // Off (tool put away, a screen open) isn't a pick: swapping back mustn't repeat it.
        if (screenOpen || pm == PlacementMode.DISABLED) return;
        if (pm != lastMode) {
            lastMode = pm;
            stepOneDelay = 30;
            return;
        }
        if (stepOneDelay <= 0 || --stepOneDelay > 0) return;
        switch (pm) {
            case MARKER_BOX -> {
                if (!BoxMarkers.creating()) MarkerText.say("Box · step 1 of 2 — LMB the first corner");
            }
            case MARKER_RING -> {
                if (pendingCenter == null) MarkerText.say("Ring · step 1 of 3 — LMB the centre");
            }
            case MARKER_LINE -> {
                if (!LineMarkers.creating()) MarkerText.say("Line · step 1 of 2 — LMB the start");
            }
            default -> { }
        }
    }

    private static void removeRing(RingShape target) {
        shapes.remove(target);
        if (scrollShape == target) scrollShape = null;
        if (lookedShape == target) lookedShape = null;
        aimedShape = null;
        dragShape = null;
        saveCurrent();
        MarkerText.say("Ring removed");
    }

    private static void grabRing() {
        dragShape = aimedShape;
        dragKind = aimedKind;
        dragStartCell = switch (dragKind) {
            case RADIUS_A -> dragShape.ctrlA;
            case RADIUS_B -> dragShape.ctrlB;
            default -> dragShape.center;
        };
        ringLockAxis = null;
        if (dragKind == ControlKind.CORNER) { // rotation drag: capture the start pose
            dragStartTheta = dragShape.theta;
            dragCornerBaseAngle = dragShape.cornerBaseAngle(aimedCorner);
            dragStartOffA = dragShape.offsetOf(dragShape.ctrlA);
            dragStartOffB = dragShape.offsetOf(dragShape.ctrlB);
        }
        touch(dragShape);
    }

    // ---- ring toolbar ------------------------------------------------------------------------

    /** Ring toolbar: Circle | Square · Flip (once it has depth) · Rendering · Delete · ?. */
    private static MarkerToolbar.Spec ringSpec(RingShape s) {
        List<MarkerToolbar.Button> row = new ArrayList<>();
        List<List<MarkerToolbar.Button>> rows = List.of(row);
        if (MarkerToolbar.stylePage()) {
            rows = StylePage.rows(s, s.style, RING_FILL, RING_EDGE, () -> {
                touch(s);
                saveCurrent();
            });
        }
        for (RingShape.Kind k : RingShape.Kind.values()) {
            String name = k == RingShape.Kind.CIRCLE ? "Circle" : "Square";
            LineProfile outline = k == RingShape.Kind.CIRCLE ? LineProfile.CIRCLE : LineProfile.SQUARE;
            row.add(new MarkerToolbar.Button(MarkerToolbar.outline(outline.outline(1)), s.kind == k, false,
                    name + " ring" + (s.kind == k ? " (on)" : ""), () -> {
                        s.setKind(k);
                        touch(s);
                        saveCurrent();
                        MarkerText.say("Ring: " + name.toLowerCase(java.util.Locale.ROOT));
                    }));
        }
        if (s.layerHi() - s.layerLo() > 0) { // flipping a single layer changes nothing
            row.add(new MarkerToolbar.Button(MarkerToolbar.FLIP, s.flip, false,
                    "Flip: gradient from the " + (s.flip ? "far end back to the base (on)" : "base out to the far end"),
                    () -> {
                        s.flip = !s.flip;
                        touch(s);
                        saveCurrent();
                        MarkerText.say(s.flip ? "Ring flipped — the gradient starts at the far end"
                                : "Ring: the gradient starts at the base again");
                    }));
        }
        row.add(StylePage.openButton(s.style));
        row.add(new MarkerToolbar.Button(MarkerToolbar.BIN, false, true,
                "Delete this ring (same as MMB)", () -> removeRing(s)));
        row.add(new MarkerToolbar.Button(MarkerToolbar.QUESTION, false, false,
                "Help: how rings work", () -> Minecraft.getInstance()
                        .setScreenAndShow(co.fax.wang.GradientScreen.atHelpTopic("Shape markers"))));
        int b = s.scanBound();
        BlockPos lo = RingShape.offset(RingShape.offset(RingShape.offset(s.center, s.uAxis(), -b), s.vAxis(), -b),
                s.normal, s.layerLo());
        BlockPos hi = RingShape.offset(RingShape.offset(RingShape.offset(s.center, s.uAxis(), b), s.vAxis(), b),
                s.normal, s.layerHi());
        List<Vec3> controls = new ArrayList<>();
        controls.add(Vec3.atCenterOf(s.center));
        controls.add(Vec3.atCenterOf(s.ctrlA));
        controls.add(Vec3.atCenterOf(s.ctrlB));
        if (s.kind == RingShape.Kind.SQUARE) {
            for (int i = 0; i < 4; i++) controls.add(Vec3.atCenterOf(s.cornerCell(i)));
        }
        return new MarkerToolbar.Spec(null, rows, ringCog(s),
                new Vec3(Math.min(lo.getX(), hi.getX()), Math.min(lo.getY(), hi.getY()), Math.min(lo.getZ(), hi.getZ())),
                new Vec3(Math.max(lo.getX(), hi.getX()) + 1, Math.max(lo.getY(), hi.getY()) + 1,
                        Math.max(lo.getZ(), hi.getZ()) + 1),
                controls);
    }

    /** A ring's cog: just past the far end of its centre column. */
    private static Vec3 ringCog(RingShape s) {
        int layer = s.height > 0 ? s.layerHi() + 1 : s.layerLo() - 1;
        return Vec3.atCenterOf(RingShape.offset(s.center, s.normal, layer));
    }

    // ---- rings: aim, creation, drag ----------------------------------------------------------

    /**
     * The shape whose band the view ray crosses (nearest first) — a precise silhouette ray
     * test, not a world raycast, so it finds shapes buried under terrain too. While dragging,
     * the dragged shape owns the highlight.
     */
    private static void updateLookedShape(Minecraft mc) {
        if (dragShape != null) {
            lookedShape = dragShape;
            return;
        }
        lookedShape = null;
        Vec3 eye = Raycast.eye(mc);
        Vec3 end = eye.add(Raycast.look(mc).scale(Raycast.REACH));
        double best = Double.MAX_VALUE;
        for (RingShape s : shapes) {
            var hit = s.silhouette(false).clip(eye, end, s.silhouetteOrigin());
            if (hit != null && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                double d = hit.getLocation().distanceToSqr(eye);
                if (d < best) {
                    best = d;
                    lookedShape = s;
                }
            }
        }
    }

    /**
     * The three creation clicks: center (plane from the clicked face; aimed at nothing, a level
     * ring at Marker reach), radius A, radius B. New rings start round.
     */
    private static void creationClick(Minecraft mc) {
        if (pendingCenter == null) {
            BlockHitResult hit = Raycast.aimedBlock(mc);
            if (hit != null) {
                pendingCenter = hit.getBlockPos().immutable();
                pendingNormal = hit.getDirection().getAxis();
            } else {
                pendingCenter = BoxMarkers.airCell(mc);
                pendingNormal = Direction.Axis.Y;
            }
            MarkerText.step("Ring", 2, 3, "LMB the outer radius (works over air)");
            return;
        }
        BlockPos onPlane = planeCell(mc, pendingCenter, pendingNormal);
        if (onPlane == null) return;
        if (pendingCtrlA == null) {
            pendingCtrlA = onPlane;
            MarkerText.step("Ring", 3, 3, "LMB the inner radius (same spot = one wide)");
            return;
        }
        RingShape s = new RingShape(RingShape.Kind.CIRCLE, pendingCenter, pendingNormal, pendingCtrlA, onPlane);
        shapes.add(s);
        clearPending();
        touch(s);
        saveCurrent();
        MarkerText.say("Ring set — RMB a control adds a layer · LMB its cog for circle / square");
    }

    /** While holding a control: it follows the view ray's intersection with the base plane. */
    private static void applyDrag(Minecraft mc) {
        if (dragKind == ControlKind.CORNER) {
            // Rotation: the grabbed corner chases the exact aim point's in-plane angle.
            double[] uv = planePointUV(mc, dragShape);
            if (uv == null || (uv[0] == 0 && uv[1] == 0)) return;
            double newTheta = Math.atan2(uv[1], uv[0]) - dragCornerBaseAngle;
            dragShape.applyRotation(newTheta, dragStartTheta, dragStartOffA, dragStartOffB);
        } else {
            BlockPos onPlane = planeCell(mc, dragShape.center, dragShape.normal);
            if (onPlane == null) return;
            onPlane = axisLocked(dragShape, onPlane);
            if (onPlane == null) return;
            switch (dragKind) {
                case CENTER -> dragShape.moveCenterTo(onPlane);
                // Radius nodes may not land on the corner cells.
                case RADIUS_A -> { if (!isCornerCell(dragShape, onPlane)) dragShape.setRadiusControl(true, onPlane); }
                case RADIUS_B -> { if (!isCornerCell(dragShape, onPlane)) dragShape.setRadiusControl(false, onPlane); }
                default -> { }
            }
        }
        aimedShape = dragShape; // keep the highlight on what's being dragged
        aimedKind = dragKind;
    }

    /**
     * Alt axis lock for ring moves / resizes: once the drag has left its start cell, it keeps
     * only the in-plane axis it moved along most. Returns the cell to use (null = wait).
     */
    private static BlockPos axisLocked(RingShape s, BlockPos cell) {
        if (!Gradient.axisLockDown()) {
            ringLockAxis = null;
            return cell;
        }
        int du = cell.get(s.uAxis()) - dragStartCell.get(s.uAxis());
        int dv = cell.get(s.vAxis()) - dragStartCell.get(s.vAxis());
        if (ringLockAxis == null) {
            if (du == 0 && dv == 0) return null;
            ringLockAxis = Math.abs(du) >= Math.abs(dv) ? s.uAxis() : s.vAxis();
        }
        BlockPos out = RingShape.offset(dragStartCell, ringLockAxis, ringLockAxis == s.uAxis() ? du : dv);
        // Keep the drag on the base plane the cell was snapped to.
        return RingShape.offset(out, s.normal, cell.get(s.normal) - out.get(s.normal));
    }

    private static boolean isCornerCell(RingShape shape, BlockPos cell) {
        if (shape.kind != RingShape.Kind.SQUARE) return false;
        for (int i = 0; i < 4; i++) {
            if (shape.cornerCell(i).equals(cell)) return true;
        }
        return false;
    }

    /** Exact in-plane (u, v) of the aim point on {@code shape}'s base plane, or null. */
    private static double[] planePointUV(Minecraft mc, RingShape shape) {
        Vec3 eye = Raycast.eye(mc);
        Vec3 dir = Raycast.look(mc);
        double o = component(eye, shape.normal), d = component(dir, shape.normal);
        if (Math.abs(d) < 1e-6) return null;
        double t = (shape.center.get(shape.normal) + 0.5 - o) / d;
        if (t < 0 || t > Raycast.REACH) return null;
        Vec3 p = eye.add(dir.scale(t));
        return new double[]{
                component(p, shape.uAxis()) - (shape.center.get(shape.uAxis()) + 0.5),
                component(p, shape.vAxis()) - (shape.center.get(shape.vAxis()) + 0.5)};
    }

    /**
     * The cell where the view ray crosses the plane through {@code center}'s middle — this is
     * how radius clicks and drags work over open air at any distance.
     */
    private static BlockPos planeCell(Minecraft mc, BlockPos center, Direction.Axis axis) {
        Vec3 eye = Raycast.eye(mc);
        Vec3 dir = Raycast.look(mc);
        double o = component(eye, axis), d = component(dir, axis);
        if (Math.abs(d) < 1e-6) return null;
        double t = (center.get(axis) + 0.5 - o) / d;
        if (t < 0 || t > Raycast.REACH) return null;
        Vec3 p = eye.add(dir.scale(t));
        BlockPos cell = BlockPos.containing(p.x, p.y, p.z);
        // Snap exactly onto the base plane.
        return switch (axis) {
            case X -> new BlockPos(center.getX(), cell.getY(), cell.getZ());
            case Y -> new BlockPos(cell.getX(), center.getY(), cell.getZ());
            case Z -> new BlockPos(cell.getX(), cell.getY(), center.getZ());
        };
    }

    private static double component(Vec3 v, Direction.Axis axis) {
        return switch (axis) {
            case X -> v.x;
            case Y -> v.y;
            case Z -> v.z;
        };
    }

    /**
     * Nearest ring control the view ray passes through. Controls are full columns spanning the
     * shape's extrusion, and can float in open air (ray/AABB test, not a world raycast); the cog
     * is a small cube past the centre column.
     */
    private static void updateAimedControl(Minecraft mc) {
        if (dragShape != null) return; // the drag owns the highlight until release
        Vec3 eye = Raycast.eye(mc);
        Vec3 dir = Raycast.look(mc);
        aimedShape = null;
        aimedKind = null;
        aimedDist = Double.POSITIVE_INFINITY;
        double best = Raycast.REACH;
        for (RingShape shape : shapes) {
            // CENTER first so it wins ties when controls share a block; radii beat corners.
            ControlKind[] kinds = {ControlKind.CENTER, ControlKind.RADIUS_B, ControlKind.RADIUS_A};
            BlockPos[] cells = {shape.center, shape.ctrlB, shape.ctrlA};
            for (int i = 0; i < kinds.length; i++) {
                double t = columnIntersect(eye, dir, shape, cells[i]);
                if (t >= 0 && t < best) {
                    best = t;
                    aimedShape = shape;
                    aimedKind = kinds[i];
                }
            }
            if (shape.kind == RingShape.Kind.SQUARE) {
                for (int i = 0; i < 4; i++) {
                    double t = columnIntersect(eye, dir, shape, shape.cornerCell(i));
                    if (t >= 0 && t < best) {
                        best = t;
                        aimedShape = shape;
                        aimedKind = ControlKind.CORNER;
                        aimedCorner = i;
                    }
                }
            }
            if (cogShown(shape)) {
                double t = Gizmo.rayCube(eye, dir, ringCog(shape), Gizmo.CTRL_HALF);
                if (t >= 0 && t < best) {
                    best = t;
                    aimedShape = shape;
                    aimedKind = ControlKind.COG;
                }
            }
        }
        if (aimedShape != null) aimedDist = best;
    }

    /** Ray distance to a control's full extrusion column, or -1. */
    private static double columnIntersect(Vec3 eye, Vec3 dir, RingShape shape, BlockPos cell) {
        BlockPos lo = RingShape.offset(cell, shape.normal, shape.layerLo());
        int span = shape.layerHi() - shape.layerLo() + 1;
        double sx = shape.normal == Direction.Axis.X ? span : 1;
        double sy = shape.normal == Direction.Axis.Y ? span : 1;
        double sz = shape.normal == Direction.Axis.Z ? span : 1;
        return ShapeMath.rayBoxIntersect(eye.x, eye.y, eye.z, dir.x, dir.y, dir.z,
                lo.getX(), lo.getY(), lo.getZ(),
                lo.getX() + sx, lo.getY() + sy, lo.getZ() + sz);
    }

    // ---- rendering (called from MarkerManager.render) --------------------------------------

    public static void render(SubmitNodeCollector col, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        PlacementMode pm = Gradient.currentPlacement(mc);
        boolean screenOpen = mc.gui.screen() != null;
        boolean handles = Gradient.holdingPaintTool(mc) && !screenOpen;
        boolean boxCreate = pm == PlacementMode.MARKER_BOX && !screenOpen;
        boolean ringCreate = pm == PlacementMode.MARKER_RING && !screenOpen;
        boolean groupMode = (pm == PlacementMode.MARKER || pm == PlacementMode.MARKER_DRAW) && !screenOpen;
        PoseStack ps = new PoseStack();
        Vec3[] ax = Gizmo.cameraAxes(mc);

        frame++;
        for (RingShape shape : shapes) {
            // Looking at any part of the shape turns its edges white (the "selected" read) —
            // the whole shape is then scrollable, draggable via controls, and middle-click
            // deletable. Rebuilding the cached geometry is throttled while dragging.
            boolean looked = handles && (shape == lookedShape || shape == aimedShape);
            boolean allowRebuild = shape != dragShape || frame % 4 == 0;
            // The ring's own look (its toolbar's Rendering page): looked-at edges stay white
            // but keep its edge opacity, so edges turned off stay off.
            MarkerStyle st = shape.style;
            BlockSkin.Skin skin = BlockSkin.of(st.block);
            if (skin != null && (long) shape.rasterSize() * (shape.layerHi() - shape.layerLo() + 1) * 4 > BlockSkin.MAX_FACES) {
                skin = null; // too big to show as blocks: the flat tint instead
            }
            int edge = st.edge(looked ? RING_EDGE_LOOKED : RING_EDGE, !looked);
            int fill = skin != null ? 0 : st.face(RING_FILL);
            drawRing(col, ps, cam, shape, shape.layerLo(), shape.layerHi(), st.facesThrough ? 0 : fill,
                    edge, allowRebuild);
            if (skin != null) drawRingBlocks(col, ps, cam, shape, skin, st.blockAlpha(), st.facesThrough);
            else if (st.facesThrough && fill >>> 24 != 0) drawRingFacesThrough(col, ps, cam, shape, fill);
            if (looked && edge >>> 24 != 0) {
                WorldDraw.compositeOutline(col, ps, cam, shape.silhouetteOrigin(),
                        shape.silhouette(false), edge);
            }
            // Faint on-top edge cage, always visible, so a buried shape never gets lost.
            int ghost = st.ghost(looked ? RING_GHOST_LOOKED : RING_GHOST, !looked);
            if (ghost >>> 24 != 0) drawRingGhost(col, ps, cam, shape, ghost);
            drawControl(col, ps, cam, shape, ControlKind.RADIUS_A, -1, shape.ctrlA, handles);
            drawControl(col, ps, cam, shape, ControlKind.RADIUS_B, -1, shape.ctrlB, handles);
            drawControl(col, ps, cam, shape, ControlKind.CENTER, -1, shape.center, handles);
            if (shape.kind == RingShape.Kind.SQUARE) {
                for (int i = 0; i < 4; i++) { // derived rotation nodes on the corners
                    drawControl(col, ps, cam, shape, ControlKind.CORNER, i, shape.cornerCell(i), handles);
                }
            }
            if (handles && cogShown(shape)) {
                boolean aimed = shape == aimedShape && aimedKind == ControlKind.COG;
                Vec3 c = ringCog(shape);
                Gizmo.twoPass(col, ps, cam, e -> Gizmo.cog(e, c, aimed, ax));
            }
        }

        BoxMarkers.render(col, ps, cam, mc, handles, boxCreate, (who != Who.NONE && who != Who.BOX) || barHover);
        if (ringCreate) renderRingCreation(col, ps, cam, mc);
        LineMarkers.render(col, cam, handles, pm);
        if (handles) GroupMarkers.render(col, ps, cam, mc, groupMode);
        if (handles) MarkerToolbar.render(col, cam);
    }

    /** A control's colour by what dragging it does: centre moves, radii size, corners turn. */
    private static int controlColor(ControlKind kind) {
        return switch (kind) {
            case CENTER -> co.fax.wang.ui.Ui.MOVE;
            case RADIUS_A, RADIUS_B -> co.fax.wang.ui.Ui.SIZE;
            default -> co.fax.wang.ui.Ui.TURN;
        };
    }

    /**
     * A control renders as a column (white centre, green radii, blue square corners) spanning the
     * shape's extrusion — with the faces between stacked cells culled, so only the column's
     * outer surface is drawn.
     */
    private static void drawControl(SubmitNodeCollector col, PoseStack ps, Vec3 cam, RingShape shape,
                                    ControlKind kind, int cornerIndex, BlockPos cell, boolean canInteract) {
        boolean aimed = canInteract && shape == aimedShape && kind == aimedKind
                && (kind != ControlKind.CORNER || cornerIndex == aimedCorner);
        int base = controlColor(kind);
        int fill = aimed ? Gizmo.alpha(Gizmo.lighten(base), 0x99) : Gizmo.alpha(base, kind == ControlKind.CENTER ? 0x66 : 0x77);
        int lo = shape.layerLo(), hi = shape.layerHi();
        for (int k = lo; k <= hi; k++) {
            int mask = 0;
            for (Direction d : Direction.values()) {
                if (d.getAxis() == shape.normal) {
                    int kk = k + d.getAxisDirection().getStep();
                    if (kk >= lo && kk <= hi) continue; // internal face between stacked cells
                }
                mask |= 1 << d.ordinal();
            }
            WorldDraw.filledBlockFaces(col, ps, cam, RingShape.offset(cell, shape.normal, k), fill, mask);
        }
        int span = hi - lo + 1;
        BlockPos colLo = RingShape.offset(cell, shape.normal, lo);
        int sx = shape.normal == Direction.Axis.X ? span : 1;
        int sy = shape.normal == Direction.Axis.Y ? span : 1;
        int sz = shape.normal == Direction.Axis.Z ? span : 1;
        if (aimed) {
            WorldDraw.regionOutline(col, ps, cam, colLo, sx, sy, sz, CONTROL_OUTLINE);
        }
        // Faint on-top cage, so buried controls stay findable and targetable — unless the ring is
        // set not to show its edge lines when obscured.
        if (!shape.style.edgesThrough) return;
        WorldDraw.boxEdgesSeeThrough(col, ps, cam, colLo, sx, sy, sz,
                aimed ? Gizmo.alpha(Gizmo.lighten(base), 0x99) : Gizmo.alpha(base, 0x66));
    }

    /** Creation hover + live ring preview between clicks. */
    private static void renderRingCreation(SubmitNodeCollector col, PoseStack ps, Vec3 cam, Minecraft mc) {
        if (pendingCenter == null) {
            if (who != Who.NONE || barHover) return; // aiming a control: manipulation, not creation
            BlockHitResult hit = Raycast.aimedBlock(mc);
            BlockPos c = hit != null ? hit.getBlockPos() : BoxMarkers.airCell(mc);
            WorldDraw.filledBlock(col, ps, cam, c, HOVER_CENTER);
            WorldDraw.boxEdgesSeeThrough(col, ps, cam, c, 1, 1, 1, HOVER_CENTER);
            return;
        }
        WorldDraw.filledBlock(col, ps, cam, pendingCenter, Gizmo.alpha(co.fax.wang.ui.Ui.MOVE, 0x77));
        BlockPos onPlane = planeCell(mc, pendingCenter, pendingNormal);
        if (onPlane != null) WorldDraw.filledBlock(col, ps, cam, onPlane, HOVER_RADIUS);
        if (pendingCtrlA != null) WorldDraw.filledBlock(col, ps, cam, pendingCtrlA, Gizmo.alpha(co.fax.wang.ui.Ui.SIZE, 0x77));

        // Live preview: one-wide ring at the hovered radius, or the band vs radius A.
        if (onPlane == null) return;
        RingShape preview = new RingShape(RingShape.Kind.CIRCLE, pendingCenter, pendingNormal,
                pendingCtrlA != null ? pendingCtrlA : onPlane, onPlane);
        drawRing(col, ps, cam, preview, 0, 0, RING_PREVIEW, 0, true);
    }

    /**
     * The shape's ring band on every layer [hLo..hHi] along its normal, batched into ONE
     * geometry submission from the shape's cached raster (no per-frame membership trig, no
     * per-cell submits). Faces shared with a neighboring ring cell — sideways within the band,
     * or up/down between layers — are culled, so only the outer surface of the donut/tube is
     * drawn; {@code edgeArgb} lines every convex edge where two visible faces meet.
     */
    private static void drawRing(SubmitNodeCollector col, PoseStack ps, Vec3 cam, RingShape shape,
                                 int hLo, int hHi, int fillArgb, int edgeArgb, boolean allowRebuild) {
        shape.ensureGeometry(allowRebuild);
        int n = shape.rasterSize();
        if (n == 0) return;
        Direction.Axis normal = shape.normal;
        Direction.Axis uAxis = shape.uAxis();
        BlockPos c = shape.center;
        ps.pushPose();
        ps.translate(c.getX() - cam.x, c.getY() - cam.y, c.getZ() - cam.z);
        col.submitCustomGeometry(ps, net.minecraft.client.renderer.rendertype.RenderTypes.debugFilledBox(),
                (pose, vc) -> {
                    for (int i = 0; i < n; i++) {
                        int du = shape.rasterDu(i), dv = shape.rasterDv(i), m4 = shape.rasterMask(i);
                        for (int k = hLo; k <= hHi; k++) {
                            int mask = faceMask(normal, uAxis, k, hLo, hHi, m4);
                            if (mask == 0) continue;
                            float ox = cellOffset(Direction.Axis.X, normal, uAxis, k, du, dv);
                            float oy = cellOffset(Direction.Axis.Y, normal, uAxis, k, du, dv);
                            float oz = cellOffset(Direction.Axis.Z, normal, uAxis, k, du, dv);
                            if (fillArgb >>> 24 != 0) WorldDraw.emitCellFaces(pose, vc, ox, oy, oz, fillArgb, mask);
                            if (edgeArgb >>> 24 != 0) {
                                WorldDraw.emitCellEdges(pose, vc, ox, oy, oz, edgeArgb, mask, false);
                            }
                        }
                    }
                });
        ps.popPose();
    }

    /**
     * The band's outer surface as block faces (see {@link BlockSkin}): the ring shown as a block,
     * one textured square per visible cell face. Call after {@link #drawRing} (it builds the raster).
     */
    private static void drawRingBlocks(SubmitNodeCollector col, PoseStack ps, Vec3 cam, RingShape shape,
                                       BlockSkin.Skin skin, int alpha, boolean through) {
        int n = shape.rasterSize();
        if (n == 0 || alpha == 0) return;
        int hLo = shape.layerLo(), hHi = shape.layerHi();
        Direction.Axis normal = shape.normal;
        Direction.Axis uAxis = shape.uAxis();
        BlockPos c = shape.center;
        ps.pushPose();
        ps.translate(c.getX() - cam.x, c.getY() - cam.y, c.getZ() - cam.z);
        Vec3 camLocal = new Vec3(cam.x - c.getX(), cam.y - c.getY(), cam.z - c.getZ());
        BlockSkin.submit(col, ps, skin, alpha, through, camLocal, sink -> {
            for (int i = 0; i < n; i++) {
                int du = shape.rasterDu(i), dv = shape.rasterDv(i), m4 = shape.rasterMask(i);
                for (int k = hLo; k <= hHi; k++) {
                    int mask = faceMask(normal, uAxis, k, hLo, hHi, m4);
                    if (mask == 0) continue;
                    BlockSkin.cell(sink,
                            (int) cellOffset(Direction.Axis.X, normal, uAxis, k, du, dv),
                            (int) cellOffset(Direction.Axis.Y, normal, uAxis, k, du, dv),
                            (int) cellOffset(Direction.Axis.Z, normal, uAxis, k, du, dv), mask);
                }
            }
        });
        ps.popPose();
    }

    /** The band's tinted faces drawn over everything (a ring set to show its faces when obscured). */
    private static void drawRingFacesThrough(SubmitNodeCollector col, PoseStack ps, Vec3 cam,
                                             RingShape shape, int argb) {
        int n = shape.rasterSize();
        if (n == 0) return;
        int hLo = shape.layerLo(), hHi = shape.layerHi();
        Direction.Axis normal = shape.normal;
        Direction.Axis uAxis = shape.uAxis();
        BlockPos c = shape.center;
        ps.pushPose();
        ps.translate(c.getX() - cam.x, c.getY() - cam.y, c.getZ() - cam.z);
        col.submitCustomGeometry(ps, WorldDraw.seeThrough(), (pose, vc) -> {
            for (int i = 0; i < n; i++) {
                int du = shape.rasterDu(i), dv = shape.rasterDv(i), m4 = shape.rasterMask(i);
                for (int k = hLo; k <= hHi; k++) {
                    int mask = faceMask(normal, uAxis, k, hLo, hHi, m4);
                    if (mask == 0) continue;
                    WorldDraw.emitCellFacesThrough(pose, vc,
                            cellOffset(Direction.Axis.X, normal, uAxis, k, du, dv),
                            cellOffset(Direction.Axis.Y, normal, uAxis, k, du, dv),
                            cellOffset(Direction.Axis.Z, normal, uAxis, k, du, dv), argb, mask);
                }
            }
        });
        ps.popPose();
    }

    /** Visible faces of a band cell on layer k: those without a band neighbour that way. */
    private static int faceMask(Direction.Axis normal, Direction.Axis uAxis, int k, int hLo, int hHi, int m4) {
        int mask = 0;
        for (Direction d : Direction.values()) {
            int step = d.getAxisDirection().getStep();
            boolean neighbor;
            if (d.getAxis() == normal) {
                int kk = k + step;
                neighbor = kk >= hLo && kk <= hHi;
            } else if (d.getAxis() == uAxis) {
                neighbor = (m4 & (step > 0 ? 1 : 2)) != 0;
            } else {
                neighbor = (m4 & (step > 0 ? 4 : 8)) != 0;
            }
            if (!neighbor) mask |= 1 << d.ordinal();
        }
        return mask;
    }

    /** The world-axis offset of a raster cell: k along the normal, (du, dv) in-plane. */
    private static float cellOffset(Direction.Axis axis, Direction.Axis normal,
                                    Direction.Axis uAxis, int k, int du, int dv) {
        if (axis == normal) return k;
        return axis == uAxis ? du : dv;
    }

    /**
     * The band's convex-edge lines re-emitted through the depth-ignoring see-through pipeline:
     * one batched submission tracing the shape's cage over terrain, so it never gets lost
     * underground. Uses the cached raster; visible-face masks mirror {@link #drawRing}.
     */
    private static void drawRingGhost(SubmitNodeCollector col, PoseStack ps, Vec3 cam,
                                      RingShape shape, int argb) {
        shape.ensureGeometry(false);
        int n = shape.rasterSize();
        if (n == 0) return;
        int hLo = shape.layerLo(), hHi = shape.layerHi();
        Direction.Axis normal = shape.normal;
        Direction.Axis uAxis = shape.uAxis();
        BlockPos c = shape.center;
        ps.pushPose();
        ps.translate(c.getX() - cam.x, c.getY() - cam.y, c.getZ() - cam.z);
        col.submitCustomGeometry(ps, WorldDraw.seeThrough(), (pose, vc) -> {
            for (int i = 0; i < n; i++) {
                int du = shape.rasterDu(i), dv = shape.rasterDv(i), m4 = shape.rasterMask(i);
                for (int k = hLo; k <= hHi; k++) {
                    int mask = faceMask(normal, uAxis, k, hLo, hHi, m4);
                    if (mask == 0) continue;
                    float ox = cellOffset(Direction.Axis.X, normal, uAxis, k, du, dv);
                    float oy = cellOffset(Direction.Axis.Y, normal, uAxis, k, du, dv);
                    float oz = cellOffset(Direction.Axis.Z, normal, uAxis, k, du, dv);
                    WorldDraw.emitCellEdges(pose, vc, ox, oy, oz, argb, mask, true);
                }
            }
        });
        ps.popPose();
    }
}
