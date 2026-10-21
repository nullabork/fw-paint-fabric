package co.fax.wang.shape;

import co.fax.wang.ui.Ui;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The one floating toolbar every marker shares. Each Box, Ring, Line and block-marker group has a
 * grey cog cube; LMB the cog opens that marker's toolbar, LMB it again (or LMB anywhere else)
 * closes it, and it closes while anything is dragged. Only one is open at a time.
 *
 * <p>The toolbar floats clear of its marker — centred over the cog, two blocks above the marker's
 * top, joined to the cog by a dashed leader — and drops below the marker instead when it would
 * sit between the camera and one of the marker's controls. Buttons are camera-facing squares drawn
 * on top of everything; controls win the aim over them (ShapeMarkers only asks for the hover when
 * no control is aimed). Hovering a button names it on the action bar.
 *
 * <p>The open marker re-describes its toolbar every tick ({@link Spec}), so buttons always show the
 * live state and a removed marker closes it.
 */
final class MarkerToolbar {

    private MarkerToolbar() {}

    /** A button glyph, drawn centred at {@code c} within a button {@code size} wide. */
    interface Icon {
        void draw(Gizmo.Em e, Vec3 c, Vec3[] ax, double size, int fg);
    }

    /** One button: glyph, lit state, red (destructive) glyph, hover label, action. */
    record Button(Icon icon, boolean active, boolean danger, String label, Runnable action) {}

    /**
     * What a marker's toolbar holds and where it goes: button rows (top first), the cog the
     * leader runs to, the marker's world bounds, and its control positions (kept unblocked).
     */
    record Spec(String title, List<List<Button>> rows, Vec3 cog, Vec3 min, Vec3 max, List<Vec3> controls) {}

    // ---- glyphs ----------------------------------------------------------------------------

    static Icon pixels(String[] rows, double pxOfSize) {
        return (e, c, ax, size, fg) -> Gizmo.pixelGlyph(e, c, ax, rows, size * pxOfSize, fg);
    }

    static Icon outline(double[][] unit) {
        return (e, c, ax, size, fg) -> {
            double r = size * 0.32;
            double[][] pts = new double[unit.length][];
            for (int i = 0; i < unit.length; i++) pts[i] = new double[]{unit[i][0] * r, unit[i][1] * r};
            Gizmo.fan(e, c, ax, pts, fg);
        };
    }

    static final Icon HOLLOW = (e, c, ax, size, fg) -> Gizmo.ring(e, c, ax, size * 0.32, size * 0.19, fg);
    static final Icon DISC = outline(LineProfile.CIRCLE.outline(1));
    static final Icon QUESTION = pixels(new String[]{"XXX", "..X", ".XX", ".X.", "...", ".X."}, 0.11);
    static final Icon BIN = pixels(new String[]{
            "..XXX..", "XXXXXXX", ".......", ".X.X.X.", ".X.X.X.", ".X.X.X.", ".XXXXX."}, 0.085);
    /** Flip: two arrows, one each way. */
    static final Icon FLIP = pixels(new String[]{
            ".X.....", "XXXXXXX", ".X.....", ".......", ".....X.", "XXXXXXX", ".....X."}, 0.085);
    /** Re-place: a circular arrow. */
    static final Icon REDO = pixels(Gizmo.ROTATE_ICON, 0.085);
    /** Delete ends: a bin over the end colour bar. */
    static final Icon BIN_ENDS = pixels(new String[]{
            "..XXX..", "XXXXXXX", ".X.X.X.", ".X.X.X.", ".XXXXX.", ".......", "XXXXXXX"}, 0.085);
    static final Icon LINE_BARE = pixels(new String[]{
            "......X", ".....X.", "....X..", "...X...", "..X....", ".X.....", "X......"}, 0.085);
    static Icon letter(char ch) {
        String[] rows = switch (ch) {
            case 'X' -> new String[]{"X...X", "X...X", ".X.X.", "..X..", ".X.X.", "X...X", "X...X"};
            case 'Y' -> new String[]{"X...X", "X...X", ".X.X.", "..X..", "..X..", "..X..", "..X.."};
            default -> new String[]{"XXXXX", "....X", "...X.", "..X..", ".X...", "X....", "XXXXX"};
        };
        return pixels(rows, 0.09);
    }

    // ---- state -----------------------------------------------------------------------------

    private static final double BTN = 0.8, GAP = 0.15, CLEAR = 2;
    private static final int BG = 0xC0202020, BG_HOVER = 0xE0505050;
    private static final int BG_ACTIVE = Gizmo.alpha(Ui.SELECTED, 0xE0);
    private static final int FG = 0xFFFFFFFF, FG_ON_ACTIVE = 0xFF101010;
    private static final int LEADER = Gizmo.alpha(Ui.OPTIONS, 0xFF);

    /** The marker whose toolbar is open (a LineShape, RingShape, box or group owner), or null. */
    private static Object owner;
    private static Spec spec;
    private static int hover = -1;
    // Layout of the frame (camera-dependent): button centres, size, leader end.
    private static final List<Vec3> centres = new ArrayList<>();
    private static double size;
    private static Vec3 leaderEnd;

    static boolean isOpen(Object o) {
        return owner != null && owner.equals(o);
    }

    static Object owner() {
        return owner;
    }

    /** Open {@code o}'s toolbar (closing any other); if it is the open one, close it. */
    static void toggle(Object o, Spec s) {
        if (isOpen(o)) {
            close();
            return;
        }
        owner = o;
        spec = s;
        hover = -1;
        if (s != null && s.title() != null) MarkerText.say(s.title());
    }

    /** Re-point the open toolbar at a new owner (a group flipped: its seed is now an end). */
    static void retarget(Object o) {
        if (owner != null) owner = o;
    }

    static void close() {
        owner = null;
        spec = null;
        hover = -1;
        centres.clear();
    }

    /** This tick's description of the open marker; null (gone / not editable now) closes it. */
    static void refresh(Spec s) {
        if (owner == null) return;
        if (s == null) close();
        else spec = s;
    }

    /** True while the crosshair is on one of the open toolbar's buttons. */
    static boolean hovered() {
        return hover >= 0;
    }

    /**
     * Lay the toolbar out for the current camera and find the button under the crosshair (only
     * called when no control is aimed — controls win). Its label goes up as the aim hint.
     */
    static boolean updateHover(Minecraft mc) {
        hover = -1;
        if (spec == null) return false;
        layout(mc);
        Vec3 eye = Raycast.eye(mc), dir = Raycast.look(mc);
        Vec3[] ax = Gizmo.cameraAxes(mc);
        for (int k = 0; k < centres.size(); k++) {
            if (Gizmo.rayBillboard(eye, dir, centres.get(k), ax, size / 2, size / 2) >= 0) {
                hover = k;
                break;
            }
        }
        return hover >= 0;
    }

    /** What the hovered button does (the action-bar label), or null. */
    static String hoverLabel() {
        return hover >= 0 && spec != null ? button(hover).label() : null;
    }

    /** Forget the hover without closing (a control took the aim this tick). */
    static void dropHover() {
        hover = -1;
    }

    /** Run the hovered button. */
    static void press() {
        if (hover < 0 || spec == null) return;
        Button b = button(hover);
        b.action().run();
        MarkerText.resetHint(); // the action's own message stands; re-hovering names it again
    }

    private static Button button(int k) {
        for (List<Button> row : spec.rows()) {
            if (k < row.size()) return row.get(k);
            k -= row.size();
        }
        throw new IndexOutOfBoundsException();
    }

    /**
     * Button centres for this camera: rows stacked top-down, each centred, the whole bar scaled
     * up with distance so it stays readable; above the marker unless that hides a control.
     */
    private static void layout(Minecraft mc) {
        centres.clear();
        Vec3 eye = Raycast.eye(mc);
        Vec3[] ax = Gizmo.cameraAxes(mc);
        double scale = Math.max(1, Math.min(3, eye.distanceTo(spec.cog()) / 12));
        size = BTN * scale;
        double gap = GAP * scale;
        int rows = spec.rows().size();
        double h = rows * size + (rows - 1) * gap;
        Vec3 above = new Vec3(spec.cog().x, spec.max().y + CLEAR + h / 2, spec.cog().z);
        Vec3 below = new Vec3(spec.cog().x, spec.min().y - CLEAR - h / 2, spec.cog().z);
        double w = 0;
        for (List<Button> row : spec.rows()) w = Math.max(w, row.size() * size + (row.size() - 1) * gap);
        Vec3 c = blocksControl(eye, above, ax, w / 2, h / 2) && !blocksControl(eye, below, ax, w / 2, h / 2)
                ? below : above;
        leaderEnd = c.add(ax[1].scale(c == above ? -h / 2 : h / 2));
        for (int r = 0; r < rows; r++) {
            List<Button> row = spec.rows().get(r);
            double rw = row.size() * size + (row.size() - 1) * gap;
            Vec3 rc = c.add(ax[1].scale(h / 2 - size / 2 - r * (size + gap)));
            for (int i = 0; i < row.size(); i++) {
                centres.add(rc.add(ax[0].scale(-rw / 2 + size / 2 + i * (size + gap))));
            }
        }
    }

    /** True when a bar centred at {@code c} would sit between the eye and a control. */
    private static boolean blocksControl(Vec3 eye, Vec3 c, Vec3[] ax, double hw, double hh) {
        for (Vec3 p : spec.controls()) {
            Vec3 d = p.subtract(eye);
            double len = d.length();
            if (len < 1e-6) continue;
            double t = Gizmo.rayBillboard(eye, d.scale(1 / len), c, ax, hw + 0.2, hh + 0.2);
            if (t >= 0 && t < len) return true;
        }
        return false;
    }

    // ---- rendering ---------------------------------------------------------------------------

    static void render(SubmitNodeCollector col, Vec3 cam) {
        if (spec == null) return;
        Minecraft mc = Minecraft.getInstance();
        layout(mc);
        Vec3[] ax = Gizmo.cameraAxes(mc);
        PoseStack ps = new PoseStack();
        Vec3 cog = spec.cog();
        Vec3 end = leaderEnd;
        List<Button> flat = new ArrayList<>();
        for (List<Button> row : spec.rows()) flat.addAll(row);
        Gizmo.onTop(col, ps, cam, e -> {
            Gizmo.dashed(e, cog, end, 0.02 * size / BTN, 0.3, 0.2, LEADER);
            for (int k = 0; k < flat.size() && k < centres.size(); k++) {
                Button b = flat.get(k);
                Vec3 c = centres.get(k);
                int bg = b.active() ? BG_ACTIVE : k == hover ? BG_HOVER : BG;
                Gizmo.billboardQuad(e, c, ax, size / 2, size / 2, bg);
                // Icon a hair toward the camera so it sorts over its background.
                int fg = b.danger() ? Ui.MISSING : b.active() ? FG_ON_ACTIVE : FG;
                b.icon().draw(e, c.subtract(ax[2].scale(0.01)), ax, size, fg);
            }
        });
    }
}
