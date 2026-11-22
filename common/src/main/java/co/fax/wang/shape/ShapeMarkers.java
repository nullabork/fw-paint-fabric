package co.fax.wang.shape;

import co.fax.wang.Gradient;
import co.fax.wang.PlacementMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * The markers' front door: what painting asks of them, the per-tick input hand-off, the cogs
 * and the one floating toolbar ({@link MarkerToolbar}), and persistence. The markers themselves
 * (lines and shapes, both {@link LineShape}s) live in {@link LineMarkers}.
 *
 * <p><b>Grammar</b> (the same on every marker): LMB puts each point and grabs / drags controls.
 * RMB is "the other one": aimed at a control, its one quick action; while creating, cancel. MMB
 * deletes the smallest piece under the crosshair (a line's middle node, else the marker). Scroll
 * pushes / pulls the aimed thing; modifier+scroll grows / shrinks it. Double-click changes a
 * point. The modifier means "the other way / all of it", never an action of its own; Alt locks a
 * drag to an axis. Aiming a control names its keys on the action bar.
 *
 * <p><b>Aim</b>: each tick every control (cogs included) is ray-tested and the nearest wins; a
 * live drag keeps the aim. Only when no control is aimed does the open toolbar get the crosshair.
 * Every marker has a grey cog; cogs show only for the marker under the crosshair (it lingers a
 * moment after you look away, so you can reach the cog) and the last-touched one.
 *
 * <p>Markers render whatever the mode; they are edited in the marker modes (Shape, Line /
 * Spline) and offer their gradient options while painting with Gradient. Persisted per
 * world+dimension via {@link ShapeStore}.
 */
public final class ShapeMarkers {

    /** True while a line / shape control holds the aim. */
    private static boolean lineAim;
    private static boolean barHover;
    /**
     * A press the markers took (a control, cog or toolbar button) is still held: painting must
     * not pick it up when the aim slides off, or the toolbar closes under it.
     */
    private static boolean attackHeld, useHeld;
    private static boolean lastAttack, lastUse, lastPick;
    private static String currentKey; // world+dimension the loaded markers belong to

    /** The marker last created / edited / clicked (keeps its cog out). */
    private static Object lastTouched;
    /** A marker whose toolbar reopens on its Rendering page once no screen is up (see {@link #reopenStyle}). */
    private static Object reopenOwner;
    /** The marker last under the crosshair; lingers {@link #HOVER_LINGER} ticks after. */
    private static Object hoverOwner;
    private static int hoverTicks;
    private static final int HOVER_LINGER = 60;

    // The step-1 creation message, shown a moment after a marker mode is picked.
    private static PlacementMode lastMode;
    private static int stepOneDelay;

    private ShapeMarkers() {}

    // ---- region queries (the contract PaintPlacer consumes) --------------------------------

    /** True when any marker exists in this world. */
    public static boolean any() {
        return !LineMarkers.lines.isEmpty();
    }

    /** True when the cell is inside a marker: a shape, or a line's tube. */
    public static boolean contains(BlockPos cell) {
        for (LineShape l : LineMarkers.lines) {
            if (l.contains(cell)) return true;
        }
        return false;
    }

    /**
     * The verdict on breaking {@code pos} by hand, from the markers' dig modes:
     * null to let it break, else why not. A Dig outside marker protects every block in it. Dig
     * inside markers only count while some part of one is within {@code reach} of {@code eye};
     * then only blocks inside one of those break. Out of reach of them all, digging is normal.
     */
    static String digVerdict(java.util.List<LineShape> lines, BlockPos pos, Vec3 eye, double reach) {
        LineShape fence = null;
        boolean inOne = false;
        for (LineShape l : lines) {
            if (l.dig == LineShape.Dig.OFF) continue;
            boolean in = l.contains(pos);
            if (l.dig == LineShape.Dig.OUTSIDE) {
                if (in) return LineMarkers.nm(l) + " is on Dig outside — blocks inside it are kept";
            } else if (in) {
                inOne = true;
            } else if (fence == null && l.cellInReach(eye, reach)) {
                fence = l;
            }
        }
        return fence != null && !inOne ? LineMarkers.nm(fence) + " is on Dig inside — only blocks inside it break" : null;
    }

    private static long digSaidMs;

    /** True when the markers' dig modes forbid the player breaking {@code pos}; says why (now and then). */
    public static boolean digBlocked(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || LineMarkers.lines.isEmpty()) return false;
        String why = digVerdict(LineMarkers.lines, pos, mc.player.getEyePosition(), mc.player.blockInteractionRange());
        if (why == null) return false;
        long now = System.currentTimeMillis();
        if (now - digSaidMs > 1500) {
            digSaidMs = now;
            MarkerText.say(why);
        }
        return true;
    }

    /**
     * The cell's gradient segment through the marker containing it (its start → its end), or
     * null when no marker holds it.
     */
    public static LineShape.Segment segmentFor(BlockPos cell) {
        for (LineShape l : LineMarkers.lines) {
            LineShape.Segment seg = l.segmentFor(cell);
            if (seg != null) return seg;
        }
        return null;
    }

    /** Marker count for the Settings clear-button label. */
    public static int count() {
        return LineMarkers.lines.size();
    }

    /**
     * True while the crosshair is on a marker control, a cog or the open toolbar (tool in hand):
     * clicks then belong to the markers and PaintPlacer stands down.
     */
    public static boolean controlAimed() {
        return lineAim || barHover || attackHeld || useHeld || LineMarkers.controlAimed();
    }

    /** The line or shape whose cells hold {@code cell}, or null. */
    public static LineShape lineContaining(BlockPos cell) {
        for (LineShape l : LineMarkers.lines) {
            if (l.contains(cell)) return l;
        }
        return null;
    }

    /** Drops every marker and any in-progress creation, and persists. */
    public static void clearAll() {
        LineMarkers.clear();
        resetTransient();
        saveCurrent();
    }

    private static void resetTransient() {
        lineAim = false;
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

    // ---- world sync / persistence ----------------------------------------------------------

    private static void saveCurrent() {
        ShapeStore.store(currentKey, LineMarkers.lines);
    }

    /** Persist after an edit. */
    static void saveLines() {
        saveCurrent();
    }

    private static void syncWorld(Minecraft mc) {
        String key = worldKey(mc);
        if (!java.util.Objects.equals(key, currentKey)) {
            currentKey = key;
            ShapeStore.loadInto(key, LineMarkers.lines); // null key (main menu) clears
            LineMarkers.resetTransient();
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

    /**
     * Mouse-wheel hook from the loader shells: a dragged point, an aimed node or a shape's face
     * or side. Returns true when consumed (the shells then cancel the vanilla hotbar scroll).
     */
    public static boolean onScroll(double deltaY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || deltaY == 0) return false;
        if (!Gradient.holdingPaintTool(mc) || mc.gui.screen() != null) return false;
        return LineMarkers.onScroll(deltaY);
    }

    // ---- client tick ------------------------------------------------------------------------

    /** Every client tick: world sync, then (tool in hand, no screen up) aim and input. */
    public static void tick(Minecraft mc) {
        syncWorld(mc);
        co.fax.wang.DebugRender.tick(mc);
        if (mc.level == null || mc.player == null) return;
        PlacementMode pm = mc.gui.screen() != null ? PlacementMode.DISABLED : Gradient.currentPlacement(mc);

        LineMarkers.tickPhysics(mc); // a dropped marker keeps falling whatever is in hand

        boolean screenOpen = mc.gui.screen() != null;
        boolean handles = Gradient.holdingPaintTool(mc) && !screenOpen;
        stepOne(pm, screenOpen);

        boolean attack = mc.options.keyAttack.isDown();
        boolean use = mc.options.keyUse.isDown();
        boolean pick = mc.options.keyPickItem.isDown();
        boolean attackEdge = attack && !lastAttack, useEdge = use && !lastUse, pickEdge = pick && !lastPick;
        lastAttack = attack;
        lastUse = use;
        lastPick = pick;

        if (!handles) {
            lineAim = false;
            barHover = false;
            attackHeld = false;
            useHeld = false;
            LineMarkers.updateAim(mc, pm, false);
            MarkerToolbar.close();
            MarkerText.hint(null);
            return;
        }

        // ---- aim: every control is ray-tested, the nearest wins; a live drag keeps it ----------
        LineMarkers.updateAim(mc, pm, true);
        lineAim = LineMarkers.controlDist() != Double.POSITIVE_INFINITY;
        if (reopenOwner != null) {
            Object o = reopenOwner;
            reopenOwner = null;
            MarkerToolbar.Spec s = specFor(o, pm);
            if (s != null) {
                if (!MarkerToolbar.isOpen(o)) MarkerToolbar.toggle(o, s);
                MarkerToolbar.showStyle(true);
                touch(o);
            }
        }
        MarkerToolbar.refresh(specFor(MarkerToolbar.owner(), pm));
        boolean dragging = LineMarkers.dragging();
        if (dragging) MarkerToolbar.close(); // the toolbar gets out of the way of a drag
        // The rotate rings are big enough to pass behind an open toolbar: its buttons win.
        if (lineAim && LineMarkers.ringAimed() && MarkerToolbar.updateHover(mc)) {
            LineMarkers.dropAim();
            lineAim = false;
        }
        barHover = !lineAim && !dragging && MarkerToolbar.updateHover(mc);
        if (!barHover) MarkerToolbar.dropHover();
        updateHoverOwner(mc);
        MarkerText.hint(hint(pm, attack || use));
        if (attackEdge) attackHeld = lineAim || barHover;
        if (!attack) attackHeld = false;
        if (useEdge) useHeld = lineAim || barHover;
        if (!use) useHeld = false;

        // ---- LMB on the toolbar or a cog belongs to the toolbar ---------------------------------
        boolean clickTaken = false;
        if (attackEdge) {
            Object cog = lineAim ? LineMarkers.cogAimed() : null;
            if (barHover) {
                MarkerToolbar.press();
                clickTaken = true;
            } else if (cog != null) {
                MarkerToolbar.toggle(cog, specFor(cog, pm));
                touch(cog);
                clickTaken = true;
            } else {
                MarkerToolbar.close(); // LMB anywhere else closes it (and does its own thing)
            }
        }

        // ---- the markers own the marker modes' clicks ------------------------------------------
        boolean owns = !clickTaken && !barHover;
        LineMarkers.tick(mc, pm, owns, attack, attackEdge && !clickTaken, use, useEdge, pickEdge && !barHover);
    }

    /** What's under the crosshair now (control or body) keeps its cog out for a moment. */
    private static void updateHoverOwner(Minecraft mc) {
        Object looked = barHover ? null : LineMarkers.hudLine(mc);
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
        if (lineAim) return LineMarkers.hint();
        if (barHover) return MarkerToolbar.hoverLabel();
        if (LineMarkers.sketchHint() != null) return LineMarkers.sketchHint();
        // Bodies only speak in the marker modes (not while painting past them), and not mid-press.
        if (!pm.isMarker() || held) return null;
        return LineMarkers.lookedLine() != null ? LineMarkers.hint() : null;
    }

    /**
     * Dev-only ({@link co.fax.wang.DebugScript}'s {@code aim}): where something of the marker whose
     * toolbar is open (else the last touched one) is — {@code what} is {@code cog}, {@code control}
     * (index {@code k} of its controls) or {@code button} (index {@code k} on its open toolbar).
     * Null when there's no such thing.
     */
    public static Vec3 debugAimPoint(Minecraft mc, String what, int k) {
        if ("button".equals(what)) return MarkerToolbar.debugButton(mc, k);
        Object owner = MarkerToolbar.owner() != null ? MarkerToolbar.owner() : lastTouched;
        MarkerToolbar.Spec spec = specFor(owner, Gradient.currentPlacement(mc));
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
        MarkerStyle st = owner instanceof LineShape l ? l.style : null;
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

    /**
     * The HUD's marker readout while you look at a line or shape (any part of it) or its toolbar
     * is open: "Line · 23.5 blocks long · 3 nodes", or "Shape · 5 × 3 × 8". Null otherwise.
     */
    public static String hudLineInfo() {
        LineShape l = LineMarkers.hudLine(Minecraft.getInstance());
        if (l == null && MarkerToolbar.owner() instanceof LineShape open) l = open;
        if (l == null) return null;
        String dig = l.dig == LineShape.Dig.INSIDE ? " · dig inside" : l.dig == LineShape.Dig.OUTSIDE ? " · dig outside" : "";
        if (l.shape) return "Shape · " + LineMarkers.sizeOf(l) + dig;
        int nodes = l.curve.nodes.size();
        return "Line · " + LineMarkers.fmt(l.length()) + " blocks long · " + nodes + (nodes == 1 ? " node" : " nodes") + dig;
    }

    /** The open marker's toolbar for this tick, or null when it's gone / has nothing to offer now. */
    private static MarkerToolbar.Spec specFor(Object owner, PlacementMode pm) {
        if (!(owner instanceof LineShape l)) return null;
        return pm.isMarker() ? LineMarkers.toolbarSpec(l) : LineMarkers.paintSpec(l, pm);
    }

    /** A moment after Shape or Line is picked, say what the first click does. */
    private static void stepOne(PlacementMode pm, boolean screenOpen) {
        // Off (tool put away, a screen open) isn't a pick: swapping back mustn't repeat it.
        if (screenOpen || pm == PlacementMode.DISABLED) return;
        if (pm != lastMode) {
            lastMode = pm;
            stepOneDelay = 30;
            return;
        }
        if (stepOneDelay <= 0 || --stepOneDelay > 0) return;
        if (LineMarkers.creating()) return;
        if (pm == PlacementMode.MARKER_SHAPE) MarkerText.say("Shape · step 1 of 2 — LMB the first corner");
        else if (pm == PlacementMode.MARKER_LINE) MarkerText.say("Line · step 1 of 2 — LMB the start");
    }

    // ---- rendering (each loader's level-render submit hook) -------------------------------------

    public static void render(SubmitNodeCollector col, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        PlacementMode pm = Gradient.currentPlacement(mc);
        boolean handles = Gradient.holdingPaintTool(mc) && mc.gui.screen() == null;
        LineMarkers.render(col, cam, handles, pm);
        if (handles) MarkerToolbar.render(col, cam);
    }
}
