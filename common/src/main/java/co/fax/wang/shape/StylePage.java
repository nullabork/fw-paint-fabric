package co.fax.wang.shape;

import co.fax.wang.BlockPickScreen;
import co.fax.wang.Gradient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The Rendering page of a region marker's toolbar (box, ring, line): the eye button on the
 * marker's normal toolbar swaps its buttons for these, and Back swaps them back.
 *
 * <pre>
 * Back · Show as block · (lines) Show as gradient · Gradient angle · Reset · [faces − +] · [edges − +] (opacity, each pair framed) ·
 *        faces when obscured · edges when obscured
 * face colours   (filled squares)
 * edge colours   (hollow squares)
 * </pre>
 *
 * Everything edits the marker's own {@link MarkerStyle} and shows on the marker at once. A colour
 * pressed again goes back to the marker's default. "Show as block" opens the block list
 * ({@link BlockPickScreen}); the toolbar comes back on this page when that closes.
 */
final class StylePage {

    private StylePage() {}

    private record Swatch(String name, int rgb) {}

    private static final Swatch[] COLORS = {
            new Swatch("amber", 0xFFA000), new Swatch("white", 0xFFFFFF), new Swatch("red", 0xFF5555),
            new Swatch("yellow", 0xFFE14D), new Swatch("green", 0x55DD55), new Swatch("cyan", 0x4DD9E6),
            new Swatch("blue", 0x60A0FF), new Swatch("purple", 0xB57BFF)};

    /** The eye: opens this page. */
    private static final MarkerToolbar.Icon EYE = MarkerToolbar.pixels(new String[]{
            ".......", "..XXX..", ".X...X.", "X..X..X", ".X...X.", "..XXX..", "......."}, 0.085);
    private static final MarkerToolbar.Icon BACK = MarkerToolbar.pixels(new String[]{
            ".......", "..X....", ".X.....", "XXXXXXX", ".X.....", "..X....", "......."}, 0.085);
    /** A block: a framed square with a brick course across it. */
    private static final MarkerToolbar.Icon BLOCK = MarkerToolbar.pixels(new String[]{
            "XXXXXXX", "X..X..X", "XXXXXXX", "X.X..XX", "XXXXXXX", "X..X..X", "XXXXXXX"}, 0.085);
    /** The plus / minus of the opacity buttons: yellow, so it stands apart from the white emblem. */
    private static final int SIGN = co.fax.wang.ui.Ui.ATTENTION;

    /**
     * An opacity button: what it changes on the left (a filled square for faces, a square outline
     * for edges, matching the colour rows) and a yellow minus or plus on the right.
     */
    private static MarkerToolbar.Icon opacityIcon(boolean face, int dir) {
        return (e, c, ax, size, fg) -> {
            Vec3 left = c.add(ax[0].scale(-size * 0.19)), right = c.add(ax[0].scale(size * 0.2));
            double r = size * 0.15;
            if (face) Gizmo.billboardQuad(e, left, ax, r, r, fg);
            else outline(e, left, ax, r, size * 0.04, fg);
            Gizmo.billboardQuad(e, right, ax, size * 0.14, size * 0.04, SIGN);
            if (dir > 0) Gizmo.billboardQuad(e, right, ax, size * 0.04, size * 0.14, SIGN);
        };
    }

    private static void outline(Gizmo.Em e, Vec3 c, Vec3[] ax, double r, double t, int argb) {
        Gizmo.billboardQuad(e, c.add(ax[1].scale(r - t)), ax, r, t, argb);
        Gizmo.billboardQuad(e, c.add(ax[1].scale(-(r - t))), ax, r, t, argb);
        Gizmo.billboardQuad(e, c.add(ax[0].scale(r - t)), ax, t, r, argb);
        Gizmo.billboardQuad(e, c.add(ax[0].scale(-(r - t))), ax, t, r, argb);
    }

    /** Show as gradient: bands fading from full to faint, top to bottom. */
    private static final MarkerToolbar.Icon GRADIENT = MarkerToolbar.pixels(new String[]{
            "4444444", "4444444", "3333333", "2222222", "2222222", "1111111", "1111111"}, 0.085);

    /** Faces through blocks: a solid square with a dotted wall across it. */
    private static final MarkerToolbar.Icon FACES_THROUGH = MarkerToolbar.pixels(new String[]{
            "XXXX...", "XXXX.X.", "XXXX...", "XXXX.X.", ".......", ".X.X.X.", "......."}, 0.085);
    /** Edges through blocks: a square outline with a dotted wall across it. */
    private static final MarkerToolbar.Icon EDGES_THROUGH = MarkerToolbar.pixels(new String[]{
            "XXXX...", "X..X.X.", "X..X...", "XXXX.X.", ".......", ".X.X.X.", "......."}, 0.085);

    /** The button that opens the page, for a marker's normal toolbar. */
    static MarkerToolbar.Button openButton(MarkerStyle st) {
        return new MarkerToolbar.Button(EYE, !st.isDefault(), false,
                "Rendering: this marker's colours, opacity, or show it as a block", () -> MarkerToolbar.showStyle(true));
    }

    /**
     * The page's rows for {@code owner}'s style. {@code defFace} / {@code defEdge} are the marker
     * kind's default face and edge colours (ARGB); {@code saved} persists an edit.
     */
    static List<List<MarkerToolbar.Button>> rows(Object owner, MarkerStyle st, int defFace, int defEdge, Runnable saved) {
        List<MarkerToolbar.Button> top = new ArrayList<>();
        top.add(new MarkerToolbar.Button(BACK, false, false, "Back to this marker's options",
                () -> MarkerToolbar.showStyle(false)));
        String blockName = st.hasBlock() ? Gradient.toolDisplayName(st.block) : null;
        top.add(new MarkerToolbar.Button(BLOCK, st.hasBlock(), false,
                blockName != null ? "Shown as " + blockName + " · LMB pick another, or none"
                        : "Show as a block: pick one to see its texture on the marker (places nothing)",
                () -> Minecraft.getInstance().setScreenAndShow(new BlockPickScreen(st.block, id -> {
                    // A newly shown block starts solid, whatever the tint's opacity was.
                    if (!id.isEmpty() && !st.hasBlock()) st.faceOpacity = MarkerStyle.UNSET;
                    st.block = id;
                    saved.run();
                    MarkerText.say(id.isEmpty() ? "Marker: shown as a tint again"
                            : "Marker: shown as " + Gradient.toolDisplayName(id));
                }, () -> ShapeMarkers.reopenStyle(owner)))));
        if (owner instanceof LineShape line) {
            // Lines only: the tube in the colours a Gradient fill would give it (its own direction and angle).
            top.add(new MarkerToolbar.Button(GRADIENT, line.gradPreview, false,
                    line.gradPreview ? "Shown as the gradient (on) · LMB back to the marker's own look"
                            : "Show as gradient: see the active palette's gradient on the line, live (places nothing)",
                    () -> {
                        line.gradPreview = !line.gradPreview;
                        saved.run();
                        MarkerText.say(line.gradPreview ? "Line: shown as the gradient" : "Line: gradient look off");
                    }));
            top.add(LineMarkers.angleButton(line));
        }
        top.add(new MarkerToolbar.Button(MarkerToolbar.REDO, false, false,
                "Reset this marker's look to the default", () -> {
                    if (owner instanceof LineShape line) line.gradPreview = false;
                    st.reset();
                    saved.run();
                    MarkerText.say("Marker: default look");
                }));
        String faces = st.hasBlock() ? "Block" : "Face";
        int facePct = st.facePercent(defFace), edgePct = st.edgePercent(defEdge);
        top.add(opacity(opacityIcon(true, -1), faces, facePct, -1, v -> st.faceOpacity = v, saved).inGroup("face"));
        top.add(opacity(opacityIcon(true, 1), faces, facePct, 1, v -> st.faceOpacity = v, saved).inGroup("face"));
        top.add(opacity(opacityIcon(false, -1), "Edge", edgePct, -1, v -> st.edgeOpacity = v, saved).inGroup("edge"));
        top.add(opacity(opacityIcon(false, 1), "Edge", edgePct, 1, v -> st.edgeOpacity = v, saved).inGroup("edge"));
        top.add(new MarkerToolbar.Button(FACES_THROUGH, st.facesThrough, false,
                "Show faces when obscured: " + (st.facesThrough ? "on · faces show through blocks in front"
                        : "off · blocks in front hide the faces"), () -> {
                    st.facesThrough = !st.facesThrough;
                    saved.run();
                    MarkerText.say("Marker faces when obscured: " + (st.facesThrough ? "shown" : "hidden"));
                }));
        top.add(new MarkerToolbar.Button(EDGES_THROUGH, st.edgesThrough, false,
                "Show edge lines when obscured: " + (st.edgesThrough ? "on · edges show through blocks in front"
                        : "off · blocks in front hide the edges"), () -> {
                    st.edgesThrough = !st.edgesThrough;
                    saved.run();
                    MarkerText.say("Marker edges when obscured: " + (st.edgesThrough ? "shown" : "hidden"));
                }));

        List<MarkerToolbar.Button> faceRow = new ArrayList<>(), edgeRow = new ArrayList<>();
        for (Swatch c : COLORS) {
            boolean faceOn = st.faceColor == c.rgb(), edgeOn = st.edgeColor == c.rgb();
            faceRow.add(new MarkerToolbar.Button(filled(c.rgb()), faceOn, false,
                    "Face colour: " + c.name() + (faceOn ? " (on) · LMB back to default"
                            : st.hasBlock() ? " (hidden while shown as a block)" : ""), () -> {
                        st.faceColor = faceOn ? MarkerStyle.UNSET : c.rgb();
                        saved.run();
                        MarkerText.say("Marker faces: " + (faceOn ? "default colour" : c.name()));
                    }));
            edgeRow.add(new MarkerToolbar.Button(hollow(c.rgb()), edgeOn, false,
                    "Edge colour: " + c.name() + (edgeOn ? " (on) · LMB back to default" : ""), () -> {
                        st.edgeColor = edgeOn ? MarkerStyle.UNSET : c.rgb();
                        saved.run();
                        MarkerText.say("Marker edges: " + (edgeOn ? "default colour" : c.name()));
                    }));
        }
        return List.of(top, faceRow, edgeRow);
    }

    private static MarkerToolbar.Button opacity(MarkerToolbar.Icon icon, String what, int percent, int dir,
                                                java.util.function.IntConsumer set, Runnable saved) {
        int next = MarkerStyle.step(percent, dir);
        String verb = dir < 0 ? "less" : "more";
        boolean end = next == percent;
        return new MarkerToolbar.Button(icon, false, false,
                what + " opacity " + percent + "%" + (end ? (dir < 0 ? " (hidden)" : " (solid)") : " · LMB " + verb + " (" + next + "%)"),
                () -> {
                    set.accept(next);
                    saved.run();
                    MarkerText.say("Marker " + what.toLowerCase(java.util.Locale.ROOT) + " opacity: " + next + "%");
                });
    }

    /** A face colour: a filled square of it. */
    private static MarkerToolbar.Icon filled(int rgb) {
        return (e, c, ax, size, fg) -> Gizmo.billboardQuad(e, c, ax, size * 0.3, size * 0.3, 0xFF000000 | rgb);
    }

    /** An edge colour: a square outline of it. */
    private static MarkerToolbar.Icon hollow(int rgb) {
        return (e, c, ax, size, fg) -> {
            int argb = 0xFF000000 | rgb;
            double r = size * 0.3, t = size * 0.05;
            Gizmo.billboardQuad(e, c.add(ax[1].scale(r - t)), ax, r, t, argb);
            Gizmo.billboardQuad(e, c.add(ax[1].scale(-(r - t))), ax, r, t, argb);
            Gizmo.billboardQuad(e, c.add(ax[0].scale(r - t)), ax, t, r, argb);
            Gizmo.billboardQuad(e, c.add(ax[0].scale(-(r - t))), ax, t, r, argb);
        };
    }
}
