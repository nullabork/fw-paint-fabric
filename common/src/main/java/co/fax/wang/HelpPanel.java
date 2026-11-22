package co.fax.wang;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Help tab's scrollable, drill-down manual. Content is a tree of {@link Topic}s: every topic
 * always shows its title ("+"/"−" prefix) and one-line summary; clicking the title expands its
 * detail paragraphs and sub-topics (indented, themselves expandable). Text wraps to the panel
 * width, so the layout is rebuilt on resize and on every expand/collapse. Expansion state lasts
 * for the session.
 */
public final class HelpPanel {

    private record Topic(String title, String summary, List<String> body, List<Topic> children) {}

    private static Topic t(String title, String summary, List<String> body, Topic... children) {
        return new Topic(title, summary, body, List.of(children));
    }

    /** One rendered line; {@code topic} is set on header lines (clicking toggles that topic). */
    private record Line(String text, int indent, int color, Topic topic) {}

    private static final Line BLANK = new Line("", 0, 0, null);

    private static final int LINE_H = 12;
    private static final int INDENT = 10;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFA0A0A0;
    private static final int BODY = 0xFFE0E0C8; // warm off-white for detail text
    private static final int HOVER_BG = 0x33FFFFFF;
    private static final int TRACK = 0x30FFFFFF;
    private static final int THUMB = 0x90FFFFFF;

    private final Font font;
    private final Set<Topic> expanded = new HashSet<>();
    private final List<Line> lines = new ArrayList<>();
    private int scroll = 0;
    private int x, y, w, h;

    public HelpPanel(Font font) {
        this.font = font;
    }

    public void setBounds(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
        rebuild();
    }

    /** Expand the topic titled {@code title} (and every topic above it) and scroll it to the top. */
    public void focus(String title) {
        List<Topic> path = new ArrayList<>();
        for (Topic t : TOPICS) {
            if (findPath(t, title, path)) break;
        }
        if (path.isEmpty()) return;
        expanded.addAll(path);
        rebuild();
        Topic target = path.get(path.size() - 1);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).topic() == target) {
                scroll = Math.min(i, maxScroll());
                break;
            }
        }
    }

    private static boolean findPath(Topic t, String title, List<Topic> path) {
        path.add(t);
        if (t.title().equals(title)) return true;
        for (Topic c : t.children()) {
            if (findPath(c, title, path)) return true;
        }
        path.remove(path.size() - 1);
        return false;
    }

    // ---- layout -----------------------------------------------------------------------------------

    private void rebuild() {
        lines.clear();
        for (Topic topic : TOPICS) {
            addTopic(topic, 0);
            lines.add(BLANK);
        }
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private void addTopic(Topic topic, int depth) {
        boolean open = expanded.contains(topic);
        int indent = depth * INDENT;
        lines.add(new Line((open ? "− " : "+ ") + topic.title(), indent, WHITE, topic));
        for (String s : wrap(subKeys(topic.summary()), indent + INDENT)) {
            lines.add(new Line(s, indent + INDENT, GREY, null));
        }
        if (!open) return;
        for (String para : topic.body()) {
            lines.add(BLANK);
            for (String s : wrap(subKeys(para), indent + INDENT)) {
                lines.add(new Line(s, indent + INDENT, BODY, null));
            }
        }
        for (Topic child : topic.children()) {
            lines.add(BLANK);
            addTopic(child, depth + 1);
        }
    }

    /**
     * Substitute the {open}/{wheel}/{palette}/{clear}/{link}/{axis} placeholders with the CURRENT
     * key bindings ({clear} and {link} both name the Modifier), so the manual stays correct after
     * a rebind. Runs on every layout rebuild.
     */
    private static String subKeys(String s) {
        if (s.indexOf('{') < 0) return s;
        return s.replace("{open}", Gradient.boundKey("open"))
                .replace("{wheel}", Gradient.boundKey("wheel"))
                .replace("{palette}", Gradient.boundKey("palette"))
                .replace("{clear}", Gradient.boundKey("clear"))
                .replace("{link}", Gradient.boundKey("link"))
                .replace("{axis}", Gradient.boundKey("axis"));
    }

    private List<String> wrap(String text, int indent) {
        int width = Math.max(40, w - indent - 8); // 8 = right padding + scrollbar
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            String cand = cur.isEmpty() ? word : cur + " " + word;
            if (!cur.isEmpty() && font.width(cand) > width) {
                out.add(cur.toString());
                cur = new StringBuilder(word);
            } else {
                cur = new StringBuilder(cand);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    private int visibleLines() { return Math.max(1, h / LINE_H); }
    private int maxScroll() { return Math.max(0, lines.size() - visibleLines()); }

    // ---- input ------------------------------------------------------------------------------------

    public boolean mouseScrolled(double mx, double my, double dir) {
        if (mx < x || mx > x + w || my < y || my > y + h) return false;
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(dir) * 3));
        return true;
    }

    /** Toggle the topic whose header line is under the mouse. */
    public boolean mouseClicked(double mx, double my) {
        int idx = lineAt(mx, my);
        if (idx < 0 || lines.get(idx).topic() == null) return false;
        Topic topic = lines.get(idx).topic();
        if (!expanded.remove(topic)) expanded.add(topic);
        rebuild();
        return true;
    }

    private int lineAt(double mx, double my) {
        if (mx < x || mx > x + w || my < y || my >= y + h) return -1;
        int idx = scroll + (int) ((my - y) / LINE_H);
        return (idx >= 0 && idx < lines.size()) ? idx : -1;
    }

    // ---- rendering --------------------------------------------------------------------------------

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int hoverIdx = lineAt(mouseX, mouseY);
        g.enableScissor(x, y, x + w, y + h);
        for (int i = scroll; i < lines.size(); i++) {
            int ly = y + (i - scroll) * LINE_H;
            if (ly >= y + h) break;
            Line line = lines.get(i);
            if (line.text().isEmpty()) continue;
            if (line.topic() != null && i == hoverIdx) g.fill(x, ly, x + w, ly + LINE_H, HOVER_BG);
            g.text(font, line.text(), x + line.indent(), ly + 2, line.color());
        }
        g.disableScissor();

        if (maxScroll() > 0) {
            int trackX = x + w - 2;
            g.fill(trackX, y, x + w, y + h, TRACK);
            int thumbH = Math.max(8, h * visibleLines() / lines.size());
            int thumbY = y + (int) ((h - thumbH) * (double) scroll / maxScroll());
            g.fill(trackX, thumbY, x + w, thumbY + thumbH, THUMB);
        }
    }

    // ---- content ----------------------------------------------------------------------------------

    private static final List<Topic> TOPICS = List.of(

        t("How do I use this?", "The 60-second version: one tool item, one wheel, RMB.",
            List.of(
                "Two ideas run the whole mod. One: nothing happens unless you hold your chosen "
                    + "paint tool item (a stick is traditional), so your hands stay safe for "
                    + "normal play. Two: everything you'd ever switch lives on one wheel - hold "
                    + "{wheel}, hover a wedge to fan it out, click an option, release. WHAT you "
                    + "paint (Paint: Solid, Gradient, Noise, Pattern) and WHERE blocks go "
                    + "(Place: Blocks, Markers) are two independent choices, and the "
                    + "HUD in the top-left always shows both.",
                "1. Press {open}, go to Settings and click any item in the Paint tool list.  "
                    + "2. Hold that item. Hold {wheel}, hover Paint, click Solid.  "
                    + "3. Still on the wheel: hover Palette, then This block, and click a "
                    + "block from your inventory.  "
                    + "4. Release {wheel}, aim at any block face, RMB. You're painting - hold "
                    + "to keep going.",
                "Every input: {open} opens and closes this screen. {wheel} (hold) is the wheel - "
                    + "everything is on it. {palette} steps to the next palette, pattern or "
                    + "block. RMB paints. LMB puts shape and line points and drags "
                    + "controls. MMB deletes the marker piece you aim at; {clear}+MMB deletes the "
                    + "whole line. {clear} is the Modifier: the other way, "
                    + "all nodes. Hold {axis} to lock a marker drag to one axis. "
                    + "Scroll on a shape pushes or pulls it; {clear}+scroll grows or shrinks it.",
                "Settings › Keys & gestures lists all of these as currently bound; rebind them "
                    + "under Options › Controls › Key Binds › FW Paint (this manual always "
                    + "shows the current keys).",
                "From here it deepens as far as you like - palettes blend gradients and noise, "
                    + "patterns paint drawings, markers and shapes aim and bound everything. "
                    + "The topics below cover each piece.")),

        t("Getting started", "Pick a paint tool, choose what to paint, pick where it goes.", List.of(
            "1. On the Settings tab, click any item in the Paint tool list (a stick works well) - "
                + "the filter box narrows it, and the current tool is marked ● current. FW Paint "
                + "only does anything while you hold that item.",
            "2. On the Paint tab, the Paint with switch picks Solid, Gradient, Noise or Pattern "
                + "(the same as wheel › Paint). For Gradient or Noise, press + New palette, "
                + "double-click blocks (or the Auto rows) into the strip and Save - it's stored, "
                + "put in use and selected. {palette} in-game steps to your next palette.",
            "3. On the Place tab (or the wheel's Place ring), pick a Mode: Blocks (Single, Face "
                + "any, Face target, Face perp, 3D Fill) or Markers (Shape, Line / Spline, Off). "
                + "The Options beside it apply to the selected mode.",
            "4. Aim at a block face and RMB to paint (hold to keep going). Markers are optional - "
                + "they bound what you paint and anchor Auto segments. Any paint works with any "
                + "mode, with or without markers.",
            "{open} opens and closes this screen. Placement is done with normal block-place "
                + "actions the server validates, so everything works in multiplayer.")),

        t("The screen", "Paint · Place · Finder · Settings · Help - and a ? on every section.", List.of(
            "{open} opens the screen and closes it again. It lands on Paint (or Settings while "
                + "you have no paint tool).",
            "Paint: what gets placed - the Paint with switch, your palettes and patterns, and "
                + "Solid's block. Place: the Mode and its Options, plus the marker settings. "
                + "Finder: every block ranked by colour. Settings: the paint tool, general "
                + "settings, the HUD and the keys. Help: this manual.",
            "Every section has one ?: hover it for a summary, click it to open the matching "
                + "topic here.",
            "Messages on the action bar read \"FW Paint · <area>: <what happened> - {open} › "
                + "<tab>\", so they always say where to go to fix things.")),

        t("The wheel", "Hold {wheel}: Paint · Palette · Place - everything you switch.", List.of(
            "Hold {wheel}, hover a wedge to fan out its options (clicks are only for choosing), "
                + "click to choose - set several in one hold - and release. Hovering anything "
                + "shows a one-line description along the bottom of the screen.",
            "Paint: Solid, Gradient, Noise, Pattern. Palette follows the paint type: your "
                + "gradient palettes (Gradient and Noise), your patterns (Pattern), or with "
                + "Solid the match modes - hover This block to fan your inventory's blocks "
                + "(colour-sorted) onto the next ring and click one.",
            "Place: Blocks and Markers fan out the modes. Options holds the toggles - "
                + "Don't match palette, Fill voids first, Place in air, and Perp snap (click "
                + "it to switch 45°/90°). Off turns the tool off. Lit = on; grey = doesn't "
                + "apply to the current mode.",
            "Every ring ends in More… ({open}), which opens the screen on that tab. The wedge "
                + "holding the current selection shows a blue tint; anything selected but no "
                + "longer available shows red. Long lists become a carousel: step with the "
                + "< > arrows or the mouse wheel.",
            "Recent, along the bottom, keeps your last picks (newest first, saved between "
                + "sessions). Click one to set it again - it also switches whatever it needs, "
                + "so a Solid block picked while you paint Gradient goes back to Solid. A pick "
                + "whose block you no longer have shows as missing.")),

        t("HUD", "Top-left: what paints, where, and anything out of the ordinary.", List.of(
            "Line one is \"<Paint> · <Mode>\"; line two shows the palette strip, the pattern, "
                + "or the Solid block.",
            "Yellow lines appear only for options that apply to the mode and aren't at their "
                + "default: ● Don't match palette, Fill voids first: off, Place in air: off, "
                + "Perp snap 90°.",
            "With no paint tool it reads \"FW Paint: pick a paint tool - {open} › Settings\"; "
                + "with nothing picked, \"No palette yet\", \"No pattern yet\" or \"No block "
                + "picked - {open} › Paint\".",
            "Settings › Move HUD… drags it somewhere else, previewing your real HUD.")),

        t("Placement modes", "Single, Face any, Face target, Face perp, 3D Fill - where blocks go (Place tab or the {wheel} wheel).",
            List.of(
                "The Blocks modes paint; the Markers modes make shapes and lines instead, and "
                    + "Off leaves your clicks alone. The green face tint and arrow show exactly "
                    + "which faces the next click will grow from, and in which direction.",
                "Fluids count as empty space: paint under water or lava - columns grow through "
                    + "liquid, fills flood it, scans see past it, and blocks place into it just "
                    + "like placing by hand (anything hand placement replaces, like tall grass "
                    + "or snow layers, gets painted over too).",
                "A tap places one layer; holding pauses for a beat after the first layer, then "
                    + "keeps going at full speed - release during the beat to stop at exactly "
                    + "one.",
                "Plants that vanilla lets you place into - hanging roots, grass, vines, "
                    + "snow layers - count as solid: painting keeps them and works around "
                    + "them. Only air, water and lava count as empty.",
                "Markers never block painting - they only constrain it: a column stops where it "
                    + "would leave the marker it started in, and a 3D fill that starts inside a "
                    + "marker stays inside it. A 3D fill on a block touching an empty marker "
                    + "cell counts as inside - so a one-block gap at a jagged edge fills from any "
                    + "face of its neighbour.",
                "Inside a marker (a shape or a line's tube), its inside wall counts as a block face "
                    + "once it's within reach: aim at it and every mode selects that face and "
                    + "its neighbours just like real blocks, growing back into the shape."),
            t("Single", "One column out of the clicked face.", List.of(
                "Aim at a block face and hold RMB: a single column grows straight out of that "
                    + "face, one block at a time, until it hits something (a block, a "
                    + "marker's skin, or the edge of your reach).",
                "Re-clicking a half-built column continues it from its first air gap.")),
            t("Face any", "The whole connected surface grows at once.", List.of(
                "Click a face and every connected, reachable block on the same plane with an "
                    + "exposed face extrudes together - click the top of a wall to make it taller, "
                    + "its side to make it thicker. Each column shows a green face + arrow first, "
                    + "so you can see the whole selection before you click.")),
            t("Face target", "Face any, limited to the block type you click.", List.of(
                "Works like Face any, but the selection only spreads across blocks of the same "
                    + "type as the one you click - click a stone floor and only the stone gets "
                    + "the green preview and grows; dirt, grass, or planks beside it are left "
                    + "alone.")),
            t("Face perp", "A 1-block-wide run, snapped to 45 or 90 degrees.", List.of(
                "Like Face any, but instead of the whole surface it selects a single-block-wide "
                    + "line through the block you click, crossing your view - perpendicular to "
                    + "where you're looking - snapped to 45 or 90 degrees (the Perp snap option). "
                    + "45 lets you paint diagonal, stair-stepped runs, including up-diagonals "
                    + "across walls.",
                "The run extends both ways from the click while faces stay exposed and in "
                    + "reach.",
                "Works with every paint type - solid lines, gradient runs, noise streaks, or "
                    + "pattern strips.")),
            t("3D Fill", "A blob of blocks growing around the clicked block.", List.of(
                "The block you click is the centre: tap RMB to wrap its open sides in the first "
                    + "shell, hold to grow the fill layer by layer. It only spreads through "
                    + "connected air, so walls and the marker it started in contain it.",
                "Works with all four paint types: Solid fills with one block, Gradient makes a "
                    + "sphere-ish blend from the centre outward, Noise fills with the noise "
                    + "field, Pattern stacks the drawing in planes out of the clicked face. "
                    + "Aimed into a marked region with Noise, it flood-fills the whole region in "
                    + "one click (the classic noise fill).")),
            t("Options", "The toggles beside the modes - greyed when they don't apply.", List.of(
                "The Place tab's OPTIONS (and the wheel's Place › Options) belong to the selected "
                    + "mode: an option that doesn't apply to it is greyed out, and the HUD only "
                    + "mentions options that apply and aren't at their default.",
                "Fill voids first (Face any and Face target, in markers; on by default): an "
                    + "in-marker face fill raises the lowest columns first until the surface is "
                    + "level, then stacks everything together. Only columns within your reach "
                    + "count - a void you can't reach never holds the rest up. Turn it off to "
                    + "advance every column at once.",
                "Place in air (every placing mode; on by default): blocks with nothing next to "
                    + "them go straight into the air, so fills and tubes in open sky don't need "
                    + "scaffolding. Aim at open sky and a green cell shows at the edge of your "
                    + "reach: click to put one block there. Turn it off on servers whose "
                    + "anti-cheat flags that - blocks then wait until a neighbour is placed.",
                "Only place inside markers (every placing mode; off by default): a click that "
                    + "would start outside every marker while right against one - the outer skin "
                    + "of a shape or tube - places "
                    + "nothing, so a slip never spreads a blob over the outside. Clicks away from "
                    + "markers paint as normal. The HUD says Outside the marker: nothing placed.",
                "Perp snap 45° | 90° (Face perp, and the planes patterns paint on): the angle "
                    + "runs and pattern planes snap to."),
                t("Don't match palette", "Stop face selection spreading over what you just painted.", List.of(
                    "On the Place tab, or the wheel's Place › Options. While it's on (lit on the "
                        + "wheel, a yellow line on the HUD), Face any, Face target and Face perp "
                        + "don't spread onto blocks of your current selection - the palette's "
                        + "blocks (Gradient, Noise), the pattern's blocks (Pattern) or the Solid "
                        + "block. Lay one layer, click beside it, and the selection stops at the "
                        + "layer instead of growing over it.",
                    "The block you click still counts. Auto palette segments and the Solid match "
                        + "modes (Exact, Closest) pick blocks at paint time, so they can't be "
                        + "skipped. The toggle isn't saved (the yellow dot): it's off again after "
                        + "a relaunch, so it can't be forgotten on."))),
            t("Off", "The tool does nothing; clicks work as normal.", List.of(
                "Pick Off on the Place tab (Markers group) or straight from the wheel's Place "
                    + "ring. Your paint tool then behaves like a plain item until you pick a "
                    + "mode again."))),

        t("Markers", "Shapes and lines: regions you make, then drag, turn and reshape, that bound and steer painting.",
            List.of(
                "Pick Shape or Line / Spline on the Place tab (Markers) or the wheel (Place › "
                    + "Markers). Markers aren't blocks - they bound painting: painting that "
                    + "begins inside a marker stays inside it, and gradients run through it the "
                    + "way its toolbar says. Both kinds are edited in either marker mode; the "
                    + "mode only decides what a click on nothing makes.",
                "A shape and a line work the same way, so knowing one is knowing the other: the "
                    + "same cog and toolbar, the same move and rotate cubes, the same gradient "
                    + "choices, Rendering page and Physics.",
                "One grammar for every marker: LMB puts points and drags controls. RMB does a "
                    + "control's quick action and cancels something half-made. MMB deletes the "
                    + "smallest piece you aim at (a line's middle node, otherwise the marker). "
                    + "{clear} (the Modifier) means the other way, or all of it. Scroll pushes "
                    + "or pulls; {clear}+scroll grows or shrinks. Hold {axis} to lock a drag to "
                    + "one axis. Aiming at a control shows its keys on the action bar.",
                "Controls are coloured by what dragging them does: Start teal, End orange, Move "
                    + "white, Size green, Turn blue, Bend pink, and the grey cog for Options.",
                "Three cubes ride together just off the marker's surface, on the side you stand: "
                    + "white Move, the grey cog, blue Rotate. They slide along it to stay a few "
                    + "blocks from you and come round it as you walk, so they are never left "
                    + "underneath or under the ground. Standing inside a big shape, they float "
                    + "a few blocks in front of you instead.",
                "LMB the cog to open the toolbar; LMB it again or anywhere else closes it, and it "
                    + "hides while you drag. Hovering a button names it on the action bar; ? "
                    + "opens its Help page.",
                "Rendering (the eye on a toolbar) swaps the buttons for that marker's look; the "
                    + "arrow goes back. Filled squares colour its faces, hollow squares its edge "
                    + "lines (click the lit one again for the default); the lighter / heavier "
                    + "pairs set face and edge opacity in 10% steps. Show as block opens a "
                    + "filterable block list and draws the marker with that block's textures; "
                    + "Show as gradient draws it in your gradient palette's blocks; Gradient "
                    + "angle sets which way is top for the Across gradient. Previews only, "
                    + "nothing is placed. The last two buttons choose whether faces and edge "
                    + "lines still show where blocks are in front of them. The look is saved "
                    + "with the marker.",
                "Look into a marker and its edges turn white and its handles come out. A faint "
                    + "ghost outline always shows through terrain, so a marker moved underground "
                    + "stays findable and grabbable. Several markers can exist at once.",
                "Markers are remembered per world and dimension. Clear all markers (N) on the "
                    + "Place tab removes them all. Boxes and rings made with earlier versions "
                    + "come back as shapes."),
            t("Marker reach", "How far away a point lands when you aim at nothing.", List.of(
                "The Place tab slider. A shape's corner or a line's node aimed at the open sky "
                    + "lands this far from you.")),
            t("Marker dig", "TL;DR: the pick on a marker's cog toolbar limits what you can break to the marker's shape.",
                List.of(
                "LMB the pick button on a shape's or line's toolbar to step it: Off, Dig inside, "
                    + "Dig outside.",
                "Dig inside: while any part of the marker is within your reach, you can only "
                    + "break blocks that are inside it - dig a tunnel, a dome or a pit exactly "
                    + "the marker's shape. Walk out of reach of it and you dig as normal.",
                "Dig outside: blocks inside the marker can't be broken at all - clear away "
                    + "everything round it and what is in the marker is left standing.",
                "Inside means the highlighted blocks: for a hollow shape or a shell tube that is "
                    + "only its walls. It applies to breaking by hand with any tool or item, in "
                    + "survival and creative, and is saved with the marker. The action bar says "
                    + "which marker stopped you.",
                "With several markers: a block in any Dig outside marker is kept; near Dig inside "
                    + "markers, a block inside any one of them breaks.")),
            t("Marker shape", "TL;DR: LMB one corner, LMB the opposite corner, then its cog turns the box into anything.",
                List.of(
                "1. Pick Shape on the wheel (Place › Markers).",
                "2. LMB a block face for one corner, LMB another for the opposite corner (a "
                    + "preview box follows your aim; aimed at the sky a corner lands at Marker "
                    + "reach). RMB cancels. You get a box, standing upright.",
                "3. LMB the grey cog. Top row: Box, Cylinder, Triangle, Star, Sphere - each keeps "
                    + "the size you have. Then Hollow or Filled, and for a hollow one Capped "
                    + "(closed ends) or open like a pipe.",
                "4. Drag to resize: the teal and orange cubes are the bottom and top faces "
                    + "(height); the two green cubes on the side are width and depth. Dragging "
                    + "one side squashes a cylinder into an oval or a box into a slab; hold "
                    + "{link} to resize both together and keep the proportions. A hollow shape "
                    + "has a third, paler green cube on the inside of its wall: drag it to "
                    + "thicken the wall. Sizes land on whole blocks when you let go.",
                "5. Scroll on a face makes it a block taller or shorter; {clear}+scroll on a face "
                    + "or side grows or shrinks the whole profile; scroll on the wall cube "
                    + "thickens it. RMB a side cube makes width and depth equal again.",
                "6. White cube: drag to move (hold {axis}: one axis). Blue cube: the rotate "
                    + "rings, exactly as on a line - grab a ring and pull to turn the shape, "
                    + "{link} for 15-degree steps, {axis}+drag to tilt just that ring.",
                "Gradients: Along (the default for a shape) runs bottom face to top face, so the "
                    + "top of the marker is the top of the gradient; Across runs side to side, "
                    + "with Gradient angle (Rendering page) choosing which side; Inside out and "
                    + "Outside in run between the middle and the skin. Flip swaps bottom and top.",
                "Physics › Gravity drops the shape so it settles on the ground like a real "
                    + "object; Undo drop puts it back.",
                "MMB a control, or anywhere inside the selected shape, deletes it. A sphere "
                    + "resized on one side or made taller becomes an egg.")),
            t("Marker line", "TL;DR: LMB start, LMB end, drag the dots, pick a shape on the cog, 3D Fill inside it.",
                List.of(
                "1. Pick Line / Spline on the wheel (Place › Markers).",
                "2. LMB a block face for the start, another for the end (aimed at the sky, a "
                    + "point lands at Marker reach). RMB cancels.",
                "3. Drag the cubes to move the line. Drag the pink balls to bend it.",
                "4. LMB the grey cog and click a shape on its toolbar. Glowing blocks = what gets "
                    + "painted. Across / Along / Inside out / Outside in picks which way a "
                    + "gradient runs.",
                "5. Switch to 3D Fill, aim at a spot inside the tube (a block, or the green air "
                    + "cell), hold RMB.",
                "Stuck? The ? on the line's toolbar opens this page."),
                t("Controls cheat sheet", "Every click and key, one line each.", List.of(
                    "All of this works in the marker modes (Line / Spline or Shape) - in the "
                        + "painting modes lines just show (and keep painting inside the tube), so "
                        + "you can't nudge them by accident.",
                    "LMB-drag a cube (node): move it. Lands on the block grid when you let go.",
                    "LMB-drag a pink ball (handle): bend the curve that side.",
                    "RMB-drag a pink ball: slide it in or out - same angle, more or less bend.",
                    "Drag a ball back onto its cube: sharp corner there.",
                    "Double-click a cube: switch it between sharp corner and smooth curve.",
                    "Double-click the line: add a cube there (it keeps the size already there).",
                    "Scroll while dragging, or on a node: push the point further away / pull it "
                        + "nearer. {clear}+scroll on a node: resize it.",
                    "Hold {axis} while dragging: lock to one axis (coloured guide line).",
                    "Hold {link} while dragging a ball: the opposite ball mirrors it.",
                    "Drag a green (size) or blue (twist) cube: set that node. Hold {link}: every "
                        + "node. RMB one: reset that node ({link}: all of them).",
                    "MMB a middle cube: remove it. MMB the line: delete it. {clear}+MMB: delete "
                        + "the whole line.",
                    "{axis} and {link} are rebindable in Controls.")),
                t("Shapes and tubes", "The toolbar behind the line's cog.", List.of(
                    "Bare, circle, square, triangle, star: sweeps that shape along the line (bare "
                        + "is just the line).",
                    "Drawn profile (the wobbly outline): sweep a shape you draw yourself. The "
                        + "first time, you go straight into drawing: LMB a block face to start - "
                        + "the picture is flat against that face, one block thick - then LMB-drag "
                        + "to draw more cells and RMB-drag to rub out. The tube follows as you "
                        + "draw, one drawn cell to one block. LMB the pencil on the toolbar when "
                        + "done, and again any time to change the picture. It stays where you "
                        + "drew it: moving or turning the line doesn't move it. Each node's size "
                        + "cube scales the picture there, so it can taper along the line; on tight "
                        + "bends and big size changes it is blended between nodes, and fine detail "
                        + "can smear.",
                    "Shell (just the skin, default) or Solid (filled in). A shell also has "
                        + "Capped: turn it off to leave the two ends open, like a pipe.",
                    "Across / Along / Inside out / Outside in: which way a gradient runs (see "
                        + "Painting it). Flip swaps the start and end. Delete is the same as "
                        + "{clear}+MMB. ? opens this page.",
                    "Every node has its own size and twist, easing smoothly between nodes: the "
                        + "green (diagonal arrow) and blue (curved arrow) cubes show on both ends "
                        + "and on the node you aim at. Different sizes = a taper, like a tentacle.",
                    "White cube (four arrows) beside the line: drag to move the whole thing. It "
                        + "rides with the grey cog and the blue rotate cube, just off the tube on "
                        + "the side you stand: the three slide along the line to stay a few blocks "
                        + "from you and come round it as you walk, so they are never underneath it "
                        + "or under the ground.",
                    "Blue cube (globe): LMB turns rotate mode on or off. Two rings go round the "
                        + "whole line; grab either anywhere and pull to turn the line about its "
                        + "centre. Hold {link} for 15-degree steps. The rings turn with the line "
                        + "and are remembered with it.",
                    "To turn the line about some other direction, re-aim a ring first: hold the "
                        + "axis-lock key (Alt) and drag a ring. Only that ring moves - it leans "
                        + "about the other ring's axis and the line stays where it is.",
                    "Physics (the weight on the toolbar) opens a page with Gravity. Turn it on "
                        + "and the line falls as one solid piece, hits the ground, tumbles and "
                        + "settles where something of that shape would come to rest - a branch "
                        + "dropped on a hill. A line that is underground pops up first. It only "
                        + "moves the marker; nothing is placed until you paint. Press Gravity "
                        + "again to stop it mid-fall; Undo drop puts the line back.",
                    "A bare line (no shape) selects just the blocks it passes through.")),
                t("Painting it", "Grow a fill inside the tube.", List.of(
                    "3D Fill inside the tube grows from where you aim - a block, or the green "
                        + "cell in open air - one block, then a layer around it; keep holding "
                        + "to keep growing. It stays inside the tube.",
                    "Which way a Gradient runs, on the toolbar: Across (default) cuts the tube "
                        + "into slices at right angles to the line and runs from the bottom of "
                        + "each slice to its top, so it follows every bend; Along from start "
                        + "(teal cube) to end (orange); Inside out from line to skin; Outside in "
                        + "from skin to line.",
                    "While painting with Gradient, look at a line and LMB its grey cog for the "
                        + "same choices and Rendering (the eye). There, Gradient angle puts a pink "
                        + "plane across the line with a T (top) and a B "
                        + "(bottom) cube on a ring: drag either round the ring to choose which "
                        + "way the Across gradient runs ({link}: 15-degree steps). The angle "
                        + "tool goes away when you paint or click anything else.",
                    "Also on Rendering, Show as gradient draws the line in the palette's "
                        + "colours, live, until "
                        + "you turn it off or reset its look.",
                    "Place in air (on by default) means a tube floating in the sky needs no "
                        + "scaffolding.",
                    "Face and Single modes respect the tube too, like any shape - and from "
                        + "inside, the tube's wall in reach counts as a face to click.")),
                t("Tips", "Small things that save time.", List.of(
                    "Lines show faintly through terrain, and you can grab their cubes and balls "
                        + "through blocks - handy underground.",
                    "Cubes snap to the block grid, so a two-cube line is perfectly straight.",
                    "Big tubes update a moment after you drag (they rebuild in the background); "
                        + "the line itself always follows instantly.",
                    "Lines are saved per world and dimension, like the other markers.")))),

        t("Solid paint", "One block: walls, columns, and volumes fast.",
            List.of(
                "Pick Solid with the Paint tab's Paint with switch (or wheel › Paint). Solid "
                    + "places one kind of block through any mode - Single columns, whole faces, "
                    + "or 3D blobs.",
                "Blocks from (Hotbar, Inventory, Both) picks which blocks the list shows. LMB a "
                    + "block to paint with it (that switches to This block); RMB excludes it from "
                    + "matching, or includes it again. The big preview shows what paints - or a "
                    + "? when the mode picks the block at click time - with \"Excluded from "
                    + "matching: N\" under it.",
                "{palette} steps to the next block from Blocks from (skipping excluded ones, and "
                    + "switching to This block). On the wheel, Palette › This block fans the "
                    + "blocks out to click."),
            t("Match modes", "Which block paints: how Solid picks the block it places.", List.of(
                "This block: always the block picked on the left.",
                "Exact block: copies exactly the block you click.",
                "Closest colour / Closest brightness: looks at the clicked block and places the "
                    + "closest match from your blocks. Closeness is measured as perceived colour "
                    + "(Oklab) - Settings › Colour match switches to Classic RGB maths.",
                "Excluded blocks are never placed, even on an exact match."))),

        t("Palettes", "Named, saved block ramps - one palette drives gradient AND noise painting.",
            List.of(
                "With Gradient or Noise picked, the Paint tab lists your gradient palettes; "
                    + "+ New palette starts one. Each row shows its segment sprites (a crosshatch "
                    + "tile is an Auto segment). A blue outline is the selected row, a green "
                    + "● in use is what paints now, and red N missing counts blocks that aren't "
                    + "in your inventory.",
                "LMB selects a palette; double-click puts it in use. The right panel has Use, "
                    + "Edit, Duplicate (makes \"<name> copy\") and Delete (asks first), a "
                    + "gradient preview and a noise preview (drag to pan), and a summary of "
                    + "every setting. {palette} steps to the next palette in-game.",
                "By default painting refuses while blocks are missing; Settings › When blocks "
                    + "are missing › Skip them skips the missing segments instead.",
                "Saving a palette also puts it in use. Everything on a palette persists between "
                    + "sessions - only your inventory can differ later."),
            t("The editor", "A top bar, then four columns in working order.", List.of(
                "The top bar reads \"‹ Paint / PALETTE\", then the name box, Cancel and Save. "
                    + "‹ Paint, Cancel and Esc go back to the Paint tab (asking \"Discard unsaved "
                    + "changes?\" if needed); Save stores the palette, puts it in use and returns "
                    + "with it selected. A yellow ● unsaved changes shows while you edit.",
                "The columns run in working order: 1 Pick blocks · 2 Arrange · 3 How it blends · "
                    + "Preview. Each section has one ? that explains it.")),
            t("Pick blocks", "The blocks you can add.", List.of(
                "Blocks from (Hotbar, Inventory, Both) picks the list, sorted by colour, with "
                    + "Auto colour and Auto brightness pinned at the top. Double-click anything to "
                    + "add it to the strip, or drag it onto the strip to put it exactly where you "
                    + "drop it (the same block can appear more than once).",
                "RMB a block to ban it from Auto segments (red, struck through - saved with the "
                    + "palette).")),
            t("The segment strip", "Arrange: the palette itself - order, sizes, and stops.", List.of(
                "The strip runs START (top) to END (bottom). Each segment is drawn with its block "
                    + "(crosshatch for Auto) and its name beside it. A red outline means that "
                    + "block is missing from your inventory.",
                "Drag the handles to resize segments - the Curve becomes Custom and placement "
                    + "uses exactly the shares you drew. Drag a segment to move it; drag it out "
                    + "sideways to remove it. Or click a segment, then ↑ ↓ moves it and Del "
                    + "removes it.")),
            t("How it blends", "Order, Curve, Sizing, Variation, Chaos, Step length.", List.of(
                "Order: Colour ↑ / Colour ↓ / Bright ↑ / Bright ↓ re-sorts the strip (Auto "
                    + "segments sort as a middle grey). Reordering by hand makes it Custom - no "
                    + "choice lit, \"Custom · arranged by hand\".",
                "Curve: Linear / Ease in / Ease out / In/out / Step shapes how the segments share "
                    + "the run. Dragging a stop makes it Custom.",
                "Variation: Off or ±1 to ±3, plus a chance - each placed cell may swap to a block "
                    + "within that many positions of its own in the colour ordering of your "
                    + "inventory. Changing it never restarts an in-progress paint - only new "
                    + "cells roll the new setting.",
                "Chaos: how ragged the bands are - the chance a placement repeats the previous "
                    + "step or skips ahead one.",
                "Step length: the wobble - the chance each step runs randomly longer or shorter "
                    + "(its neighbour compensates, so the gradient still starts and ends on time)."),
                t("Sizing", "How long a placed gradient runs.", List.of(
                    "Min blocks: the shortest run that realises the segment ratios - it never "
                        + "stretches toward an end block.",
                    "Fill space: expands to fill from the placed block to the edge of the marker "
                        + "it is in, or the first solid block when there's no marker.",
                    "Set steps: stretches or shrinks the gradient to a fixed number of blocks "
                        + "(the Steps slider shows only for Set steps)."))),
            t("Noise settings", "Only used by Noise paint.", List.of(
                "The NOISE section (it folds away) holds the palette's noise: Type Smooth, Perlin "
                    + "or Fractal; Lock XYZ (on = one Scale, off = separate X, Y and Z); and the "
                    + "Seed, with a Random button for a new one. See Noise paint.")),
            t("Preview", "One preview: As gradient or As noise.", List.of(
                "Switch As gradient | As noise (it opens on As noise when you paint Noise); ⛶ "
                    + "enlarges it. Drag the noise cube to move through the noise.",
                "Under it: \"N segments · X blocks + Y Auto\", and a yellow warning when Auto "
                    + "segments touch.")),
            t("Automatic segments", "Wildcards resolved when you paint.", List.of(
                "An Auto segment picks ONE real block from your inventory at paint time - "
                    + "Auto colour matches by perceived colour, Auto brightness by lightness. "
                    + "The choice is deterministic (closest match to the segment's spot in the "
                    + "ramp), so a strip of nothing but Auto segments still gives a stable, "
                    + "hand-shaped distribution.",
                "At the start of the strip it resolves against the block you started painting "
                    + "on. At the end it scans ahead for the edge of the marker, or the first solid "
                    + "block - and if there is nothing to find, it picks the OPPOSITE of your "
                    + "start from the inventory (lightest vs darkest for brightness, the "
                    + "furthest colour for colour), so the ramp still spans. In the middle it "
                    + "blends between its neighbouring segments.",
                "Ramps are honest: running out of one of the ramp's blocks mid-paint stops with "
                    + "an 'out of X' message instead of quietly substituting another block.",
                "3D fills must know their whole range up front, so a 3D paint needs the strip "
                    + "to end in a real block (Auto at the start is fine - it reads the clicked "
                    + "block). A palette that's all Auto with nothing to anchor to shows an "
                    + "error instead of placing."))),

        t("Patterns", "Hand-drawn 2D block grids, painted as planes.",
            List.of(
                "A pattern is a grid you draw cell by cell (up to 32x32). With Pattern picked, "
                    + "the Paint tab lists only your patterns - + New pattern starts one - with "
                    + "the same row tags and Use / Edit / Duplicate / Delete buttons as palettes. "
                    + "{palette} then steps through your patterns instead of palettes.",
                "Placement starts at the pattern's START edge and advances out of the face "
                    + "you click; the pattern's width runs across a plane derived from the "
                    + "direction you're facing, snapped to 45 or 90 degrees (Perp snap). "
                    + "Painting a box with the same pattern gives the pattern on the faces "
                    + "parallel to its plane and edge streaks on the perpendicular sides.",
                "Adjacent strokes continue the same pattern instead of restarting - placed "
                    + "cells remember their position in the drawing, so painting column by "
                    + "column lines up. Switching or editing patterns starts fresh.",
                "Empty cells are holes: nothing is placed there."),
            t("The pattern editor", "Ink · Canvas · Preview + How it repeats.", List.of(
                "The same top bar as the palette editor (\"‹ Paint / PATTERN\", name, Cancel, "
                    + "Save), with one ? per section.",
                "Ink: Blocks from picks the list; the Eraser is pinned at the top. LMB a block to "
                    + "make it your ink.",
                "Canvas: the tool buttons choose what plain LMB does. Draw: drag over cells (a "
                    + "drag that starts on your ink erases that ink only). Line: a straight or "
                    + "45-degree line. Fill: flood fills the clicked cell's connected region. "
                    + "Pick: takes a cell's block as ink, then goes back to Draw. Origin: sets "
                    + "where placement starts, so a fresh stroke can start anywhere in the "
                    + "drawing.",
                "The shortcuts work from any tool: Shift-drag for a line, RMB to fill, MMB to "
                    + "pick, Ctrl-click to set the origin.",
                "Size W × H (1-32) resizes the canvas; shrinking keeps the cropped cells until "
                    + "you save, so growing back restores them. Clear (red, asks first) empties "
                    + "the grid.",
                "How it repeats: Tile None / Sides (wraps across the width) / Start/end (rows "
                    + "repeat) / Both. Start from Top / Bottom picks the edge placed first - "
                    + "Bottom keeps a drawing upright when you paint up from the ground. "
                    + "Variation: at ±1 to ±3 a cell may swap to a block within that many "
                    + "positions of it in the colour ordering of your inventory - subtle "
                    + "shimmer. The preview shows the wall with variation applied."))),

        t("Gradient paint", "Blend along the palette in use - between markers or free-hand.",
            List.of(
                "Between markers: mark a start and an end block, then click the face the line "
                    + "runs out of and hold. The palette stretches start-to-end across the line; "
                    + "Auto segments anchor to the real marker blocks.",
                "Outside markers: the gradient grows out of whatever face you click, sized by "
                    + "the palette's Sizing; the block you start on anchors an Auto start."),
            t("Paint memory", "How free-hand paint keeps its place.", List.of(
                "Painting outside markers remembers which step each placed block was, so "
                    + "clicking the face of a half-finished gradient continues it instead of "
                    + "restarting. Pattern placements remember their plane the same way.",
                "The memory clears when you switch or edit the palette or pattern in use, or "
                    + "after the Paint memory idle time (Settings › General; default 10 minutes, "
                    + "up to 30) - stop for longer than that and the next click starts fresh. "
                    + "Clicking a FINISHED gradient starts a new one on top rather than doing "
                    + "nothing. Changing a pattern's variation does NOT reset an in-progress "
                    + "pattern - only new cells roll the new variation.",
                "3D fills remember their centre: click its centre block or a block that belongs "
                    + "to it and it keeps growing the same sphere from the original middle; click "
                    + "elsewhere to start a new one."))),

        t("Noise paint", "Natural, blotchy 3D patterns - regions, surfaces, or free-hand.",
            List.of(
                "Every block is chosen by a 3D noise field sampled at its position, so blocks "
                    + "clump into organic patches instead of stripes. The order is the palette's "
                    + "strip - its START in the valleys, its END on the peaks - and the noise "
                    + "type, scale, and seed come from the palette's NOISE section.",
                "Classic region fill: make a shape or a line tube, switch to 3D Fill, aim into "
                    + "it and RMB - the whole marker fills at once.",
                "Or paint it anywhere: Single columns, Face surfaces, and free 3D blobs all work, "
                    + "bounded by markers like every other paint type."),
            t("Noise fields", "Smooth randomness over space.", List.of(
                "A noise field gives every position in the world a value, and nearby positions "
                    + "get similar values - that's why the pattern forms patches. Your palette is "
                    + "stretched over the field: low values place segments from the start of the "
                    + "strip, high values from the end.",
                "Smooth: soft, rounded blobs. Perlin: natural, ridged shapes. Fractal: several "
                    + "layers of detail on top of each other.")),
            t("Seed and Scale", "Reroll or resize the noise (saved on the palette).", List.of(
                "Seed: any text or number - the same seed always gives the same noise. Change "
                    + "it, or press Random, to reroll.",
                "Scale: the feature size in blocks, per axis. With Lock XYZ on, one Scale drives "
                    + "all three axes; turn it off to stretch the noise (tall streaks, flat "
                    + "layers)."))),

        t("Finder", "Every block in the game, ranked by colour or brightness.",
            List.of(
                "A discovery tool: find the blocks closest to a chosen colour, beyond what's in "
                    + "your inventory. Blocks lists every placeable block in the game, in the "
                    + "order set by the Sort switch - Colour is a rainbow (greys first dark to "
                    + "light, then hue families with brightness running through each band), "
                    + "Brightness runs dark to light.",
                "Click any row to make it the Reference: it highlights and scrolls to the middle "
                    + "of the list, so the nearest colours sit just above and below it. The small "
                    + "target button jumps back to it after you scroll away.",
                "Or use a Pure colour instead: click the hue/saturation field and set the "
                    + "brightness slider. A coloured marker line in the list shows exactly where "
                    + "that colour falls in the ordering.",
                "Colours come from each block's actual texture (resource packs included), "
                    + "measured once per game session.")),

        t("Settings", "Paint tool, general settings, HUD, and the keys.",
            List.of(
                "Paint tool: click a row in the list to make that item the paint tool. The "
                    + "filter box narrows the list; the current tool is marked ● current.",
                "General: Colour match Perceptual (Oklab) | Classic (RGB) decides what \"closest\" "
                    + "means for Solid's matching and Auto segments. When blocks are missing: "
                    + "Don't paint | Skip them. Paint memory: how long paint remembers where it "
                    + "left off between clicks.",
                "HUD: Move HUD… lets you drag the HUD somewhere else, previewing your real HUD.",
                "Keys & gestures: a read-only list of every input as currently bound. Change in "
                    + "Controls… opens Options › Controls, where they're all under Key Binds › "
                    + "FW Paint.")));
}
