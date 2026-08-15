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
     * Substitute the {open}/{wheel}/{palette}/{clear} placeholders with the CURRENT key
     * bindings, so the manual stays correct after a rebind. Runs on every layout rebuild.
     */
    private static String subKeys(String s) {
        if (s.indexOf('{') < 0) return s;
        return s.replace("{open}", Gradient.boundKey("open"))
                .replace("{wheel}", Gradient.boundKey("wheel"))
                .replace("{palette}", Gradient.boundKey("palette"))
                .replace("{clear}", Gradient.boundKey("clear"));
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

        t("Getting started", "Assign a tool item, build a palette, paint.", List.of(
            "1. On the Settings tab, click \"Paint tool\" and pick any item (a stick works well). "
                + "FW Paint only does anything while you hold that item.",
            "2. On the Palette tab, press + Gradient and build your first palette: double-click "
                + "blocks (or the Automatic rows) into the strip, then Save. Gradient and noise "
                + "painting both use the active palette; press {palette} in-game to cycle "
                + "through your saved ones.",
            "3. Hold the tool, then hold {wheel} to open the selector wheel. Hover a wedge to "
                + "fan out its options (clicks are only for choosing): Paint picks what the "
                + "tool paints (Solid, Gradient, Pattern, Noise); Place fans out Blocks "
                + "(Single, Face, Face perp, 3D Fill), Markers (Marker, Marker draw), and "
                + "Shape markers (Marker box, circle, square) on a third ring. The Palette "
                + "wedge follows the paint type: your gradient palettes (or patterns under "
                + "Pattern paint), or - with Solid paint - the match modes, where hovering "
                + "Select block fans your inventory's blocks (colour-sorted compact icons) "
                + "onto the next ring; clicking one makes it the Solid block. "
                + "The wedge directly holding the current selection shows a blue tint, so "
                + "where the active choice lives reads at a glance. "
                + "Anything selected but no longer available shows red (a crossed-out block, "
                + "or a palette missing its blocks). Long lists become a "
                + "scrolling carousel: step with the < > arrows or the mouse wheel. Click an "
                + "option to choose (set several in one hold), release {wheel} when done. The Paint "
                + "tab ({open} lands on it) has buttons for all of these too. Any paint type "
                + "works with any placement mode, with or without markers. The helper text in "
                + "the top-left always shows both, plus the active palette or pattern.",
            "4. Aim at a block face and right-click to paint (hold to keep going). Markers are "
                + "optional - they bound what you paint and anchor Automatic segments.",
            "{open} opens and closes this screen. All keys are rebindable under "
                + "Options > Controls > Key Binds > Misc (this manual always shows the current "
                + "bindings). Placement is done with normal block-place actions the server "
                + "validates, so everything works in multiplayer.")),

        t("Placement modes", "Single, Face, and 3D Fill - where blocks go (pick on the {wheel} wheel).",
            List.of(
                "The green face tint and arrow show exactly which faces the next click will grow "
                    + "from, and in which direction.",
                "A tap places one layer; holding pauses for a beat after the first layer, then "
                    + "keeps going at full speed - release during the beat to stop at exactly "
                    + "one.",
                "Markers never block painting - they only constrain it: columns stop at end "
                    + "markers (even ones floating in air), and 3D fills that start inside marker "
                    + "space stay inside it."),
            t("Single", "One column out of the clicked face.", List.of(
                "Aim at a block face and hold right-click: a single column grows straight out of "
                    + "that face, one block at a time, until it hits something (a block, an end "
                    + "marker, or the edge of your reach).",
                "Re-clicking a half-built column continues it from its first air gap. A column "
                    + "that already reached its end marker stays finished.")),
            t("Face", "The whole connected surface grows at once.", List.of(
                "Click a face and every connected, reachable block on the same plane with an "
                    + "exposed face extrudes together - click the top of a wall to make it taller, "
                    + "its side to make it thicker. Each column shows a green face + arrow first, "
                    + "so you can see the whole selection before you click.",
                "With a start marker behind the clicked column, the selection is the connected "
                    + "group of start markers in that plane instead (one hold builds the whole "
                    + "marked wall), and it can't grab faces outside the marker group.",
                "Fill voids first (Settings, on by default): an in-marker face fill raises the "
                    + "lowest columns first until the surface is level, then stacks everything "
                    + "together. It's about keeping things even, so only columns within your "
                    + "reach count - a void you can't reach never holds the rest up. Turn it off "
                    + "to advance every column at once.")),
            t("Face perpendicular", "A 1-block-wide run, snapped to 45 degrees.", List.of(
                "Like Face, but instead of the whole surface it selects a single-block-wide "
                    + "line through the block you click, crossing your view - perpendicular to where you're "
                    + "looking - snapped to 45 or 90 degrees (the Perp snap setting). 45 lets "
                    + "you paint diagonal, stair-stepped runs, including up-diagonals across "
                    + "walls.",
                "The run extends both ways from the click while faces stay exposed and in "
                    + "reach; end markers stop it like everything else. With a start marker "
                    + "behind the clicked block, only the marked blocks along the run are "
                    + "selected - never the whole marker plane.",
                "Works with every paint type - solid lines, gradient runs, noise streaks, or "
                    + "pattern strips.")),
            t("3D Fill", "A blob of blocks growing out of the clicked face.", List.of(
                "Tap right-click to place the first shell, hold to grow the fill layer by layer. "
                    + "It only spreads through connected air, so walls, end markers, and "
                    + "start-marker space contain it.",
                "Works with all three paint types: Solid fills with one block, Gradient makes a "
                    + "sphere-ish blend from the centre outward, Noise fills with the pattern. "
                    + "Aimed into a marked region with Noise, it flood-fills the whole region in "
                    + "one click (the classic noise fill)."))),

        t("Markers", "Turquoise start and amber end blocks that aim and bound every tool.",
            List.of(
                "Hold the tool in Marker mode (pick it on the {wheel} wheel). Left-click marks start blocks "
                    + "(turquoise), right-click marks end blocks (amber). Click and drag to mark a "
                    + "straight line locked to one axis. Starting a click or drag on an "
                    + "already-marked block removes instead of adds.",
                "Markers aren't real blocks: block markers reach out to the Marker dist "
                    + "setting, and the region markers (box, shapes) place and edit at sight "
                    + "range - far beyond block-placing range.",
                "Hold the clear-connected key ({clear}, rebindable) while clicking a marked "
                    + "block to remove it together with every marker connected to it in that "
                    + "plane, instead of one at a time.",
                "Markers are remembered per world and dimension. The Clear Markers button on the "
                    + "Settings tab removes them all."),
            t("Marker box", "Drag once to mark a whole region as one box.", List.of(
                "Press left-click on a block to anchor the first corner - the face you press on "
                    + "sets the gradient direction, and that side of the box is the START. Drag "
                    + "to the opposite corner (the box preview follows your aim) and release to "
                    + "commit. One box at a time; a new drag replaces it.",
                "The box is a single region, not a pile of markers: the start plane tints "
                    + "turquoise and the end plane amber. Gradients inside it run from the start "
                    + "plane to the end plane, and painting that begins inside the box stays "
                    + "inside it.",
                "Middle-click while aiming at the box deletes it. The Clear Markers button "
                    + "removes it too.")),
            t("Shape markers", "Circle and square donut regions - cylinders with drawn wall thickness.", List.of(
                "In Marker circle or Marker square mode, three clicks make a shape: click a "
                    + "block for the CENTER (the clicked face sets the shape's plane - that base "
                    + "plane is the gradient START side), then click two radii on that plane "
                    + "(they work over open air). Equal radii give a one-block-wide outline; "
                    + "different radii give a band - the wall thickness.",
                "Blue control blocks appear on the shape: drag the center to move it, drag a "
                    + "radius control to resize the band (dragging the inner past the outer just "
                    + "swaps them). Squares grow green corner nodes - drag one to rotate the "
                    + "square freely. Controls respond whenever the tool is in hand - just aim "
                    + "at one; no need to switch modes. The bright outline lights up while "
                    + "you're looking at a region with the tool held.",
                "Right-click a control to extrude the shape one layer along its plane's normal "
                    + "(hold {clear} to extrude the other way); scroll while aiming a control to "
                    + "slide the whole shape along that axis. Middle-click removes whatever "
                    + "marker you're aiming at - a shape, the box, or a plain block marker - in "
                    + "any mode. Multiple shapes can exist at once.",
                "Painting into a shape is restricted to its band: Face mode click inside the "
                    + "ring selects exactly the ring's faces, columns stop at the extrusion's "
                    + "far end, and gradients run from the base plane (start) to the far side "
                    + "(end) - so a tall extruded circle paints as a giant gradient cylinder "
                    + "with exactly the wall thickness you drew.")),
            t("Marker draw", "A freehand marker pencil.", List.of(
                "Hold left-click and sweep the crosshair to spatter start markers over whatever "
                    + "it touches; hold right-click for end markers (they only stick where a "
                    + "start marker lines up within Marker dist).",
                "With Auto end marker on, every start in the stroke scans along the face the "
                    + "stroke began on - brushing across a block's side mid-sweep won't fire an "
                    + "end marker off sideways.",
                "A stroke that begins on a marked block erases instead of drawing - and with the "
                    + "clear-connected key held, the whole connected plane goes at once.")),
            t("Start markers", "Where painting begins.", List.of(
                "Gradient and Noise: a start marker's block is the first block of the gradient - "
                    + "the fill runs from it toward an end marker.",
                "Face mode: clicking a face whose column has a start marker behind it extrudes "
                    + "every connected start marker in that plane together, so one hold builds a "
                    + "whole wall.",
                "3D Fill: start markers define a space (lines out of all six faces, up to "
                    + "Marker dist). A fill that starts inside that space never leaves it, and one "
                    + "that starts outside never enters it.")),
            t("End markers", "Where painting stops.", List.of(
                "An end marker must line up straight with a start marker, within Marker dist of "
                    + "it - the pair defines a start-to-end line.",
                "Gradient and Noise: the end marker's block is the last block of the gradient.",
                "In every placement mode an end marker acts like a solid block - a column or a "
                    + "3D fill stops at it and never passes it, even when the marker floats in "
                    + "air.")),
            t("Auto end marker (Settings)", "Start clicks place the matching end for you.", List.of(
                "With \"Auto end marker\" on, placing a start marker scans outward from the face "
                    + "you clicked: click the top face of a floor block and it scans straight up, "
                    + "click the side of a wall block and it scans out sideways.",
                "The first non-air block it finds becomes the end marker. If there is only air "
                    + "within Marker dist, the end marker is dropped at max distance instead.",
                "Dragging a line of start markers works too - every start in the line scans along "
                    + "the face the drag began on, giving you a matching line of ends in one "
                    + "sweep.",
                "While it's on and the tool is in Marker mode, the face you're pointing at is "
                    + "tinted blue with a small arrow showing which way the scan will go. Blocks "
                    + "that are already start markers don't show it (clicking them removes the "
                    + "marker instead), and during a drag the arrows run down the whole line.")),
            t("Marker dist", "The reach of a start-to-end pair.", List.of(
                "The Settings slider sets the max distance between a start and its end marker, "
                    + "the auto end marker's scan range, and the reach of a start marker's "
                    + "constraint space in 3D Fill."))),

        t("Solid paint", "One block: walls, columns, and volumes fast.",
            List.of(
                "Solid places one kind of block through any placement mode - Single columns, "
                    + "whole Faces, or 3D blobs. Left-click a block in the list to select it "
                    + "(click again to clear); right-click excludes it from the closest-match "
                    + "modes. The big preview shows the selected block - or a ? when the mode "
                    + "picks the block at click time."),
            t("Match modes", "How Solid picks the block it places.", List.of(
                "Selected block: places the block you selected in the list.",
                "Exact block: copies exactly the block you clicked.",
                "Closest color / Closest brightness: looks at the clicked block and places the "
                    + "closest match from your inventory. Closeness is measured as perceived "
                    + "colour (Oklab) - the Color match button in Settings switches back to the "
                    + "classic RGB maths.",
                "Excluded (red) blocks are never placed, even on an exact match."))),

        t("Palettes", "Named, saved block ramps - one palette drives gradient AND noise painting.",
            List.of(
                "The Palette tab lists every palette you've saved: its name on the left, its "
                    + "segment sprites on the right (a crosshatch tile is an Automatic segment). "
                    + "Click a row to select and expand it - the summary shows every setting and "
                    + "any missing blocks. Use makes it the active palette (shown on the HUD; "
                    + "{palette} cycles through them in-game); Edit opens the editor; Delete asks "
                    + "first.",
                "A red border means the palette needs blocks that aren't in your "
                    + "hotbar/inventory right now. By default painting refuses while blocks are "
                    + "missing; the \"Missing blocks\" toggle in Settings can skip the missing "
                    + "segments instead.",
                "Saving a palette also makes it active. Everything on a palette persists "
                    + "between sessions - only your inventory can differ later."),
            t("The editor", "Previews on top; settings left, blocks middle, strip right.", List.of(
                "The two previews at the top render the palette as a gradient cylinder and a "
                    + "noise cube side by side - each has its own expand button. The name box "
                    + "and Save/Cancel sit under them; an empty name saves as Untitled, and "
                    + "names must be unique. The whole page scrolls when it doesn't fit.",
                "Middle column: the blocks from the chosen Source, sorted by colour, plus the "
                    + "two pinned Auto rows. Double-click anything to append it to the strip, "
                    + "or drag it onto the strip to insert it exactly where you drop it (the "
                    + "same block can appear more than once). Right-click a block to ban it "
                    + "from Automatic segments (red = banned, saved with the palette).",
                "Left column: Variation, Chaos, Step length, the Sizing mode, and the noise "
                    + "settings (type, scale, seed - used only when noise painting), grouped "
                    + "under small headings. The circled-? icons show each control's help; "
                    + "hovering a control for a second does too.")),
            t("The segment strip", "The palette itself - order, sizes, and stops.", List.of(
                "Each segment is drawn with its block (crosshatch for Automatic), its name in "
                    + "the gutter to its right; names too long to fit scroll, and when segments "
                    + "get too short the labels pack together in order, never leaving the "
                    + "strip. A red outline means that block is missing from your inventory.",
                "Drag the pointed handles left of the strip to resize segments - the Curve "
                    + "flips to Custom and placement uses exactly the shares you drew. Cycling "
                    + "the Curve button (Linear, Ease In/Out, Step...) snaps back to an "
                    + "automatic shape; it needs at least 3 segments.",
                "Reorder by dragging a segment up or down - it swaps with its neighbours as it "
                    + "crosses them, and only the two swapping segments change place. Or click a "
                    + "segment (white outline) and use the arrow keys. Any manual reorder sets "
                    + "the Order toggle to Custom; the Order button re-sorts by colour or "
                    + "brightness, ascending or descending (needs 2 blocks; Automatics sort as "
                    + "a middle grey). Delete/Backspace removes the selected segment - or drag "
                    + "a segment sideways out of the strip and let go.")),
            t("Automatic segments", "Wildcards resolved when you paint.", List.of(
                "An Auto segment picks ONE real block from your inventory at paint time - "
                    + "Auto colour matches by perceived colour, Auto brightness by lightness. "
                    + "The choice is deterministic (closest match to the segment's spot in the "
                    + "ramp), so a strip of nothing but Auto segments still gives a stable, "
                    + "hand-shaped distribution.",
                "At the start of the strip it resolves against the block you started painting "
                    + "on. At the end it scans ahead for an end marker, or the first solid "
                    + "block - and if there is nothing to find, it picks the OPPOSITE of your "
                    + "start from the inventory (lightest vs darkest for brightness, the "
                    + "furthest colour for colour), so the ramp still spans. In the middle it "
                    + "blends between its neighbouring segments.",
                "Ramps are honest: running out of one of the ramp's blocks mid-paint stops with "
                    + "an 'out of X' message instead of quietly substituting another block.",
                "3D fills must know their whole range up front, so a 3D paint needs the strip "
                    + "to end in a real block (Automatic at the start is fine - it reads the "
                    + "clicked block). A palette that's all Automatic with nothing to anchor to "
                    + "shows an error instead of placing.")),
            t("Sizing", "How long a placed gradient runs.", List.of(
                "Min blocks: the shortest run that realises the segment ratios - it never "
                    + "stretches toward an end block.",
                "Fill space: expands to fill from the placed block to the end marker, or the "
                    + "first solid block when there's no marker.",
                "Set steps: stretches or shrinks the gradient to a fixed number of blocks "
                    + "(the Steps slider appears under the toggle).")),
            t("Sliders", "Variation, Chaos, Step length.", List.of(
                "Variation: the Var toggle picks a window (+-1 to +-3) and the slider beside it "
                    + "a chance (0-100%) - each placed cell may swap to a block within that "
                    + "many positions of its own in the colour ordering of your inventory. "
                    + "Changing it never restarts an in-progress paint - only new cells roll "
                    + "the new setting. The same model drives pattern variation.",
                "Chaos: chance a placement repeats the previous step or skips ahead one - adds "
                    + "dither across band boundaries.",
                "Step length: chance each step runs randomly longer or shorter (its neighbour "
                    + "compensates, so the gradient still starts and ends on time)."))),

        t("Patterns", "Hand-drawn 2D block grids, painted as planes.",
            List.of(
                "A pattern is a grid you draw cell by cell (up to 32x32) in the pattern editor "
                    + "- press + Pattern on the Palette tab. Patterns live in the same list as "
                    + "gradient palettes and paint under the PATTERN paint type (pick it on "
                    + "the {wheel} wheel); the cycle keybind ({palette}) then steps through your "
                    + "patterns instead of gradients - each keeps its own selection.",
                "Placement starts at the pattern's START edge and advances out of the face "
                    + "you click; the pattern's width runs across a plane derived from the "
                    + "direction you're facing, snapped to 45 or 90 degrees (Perp snap). "
                    + "Painting a box with the same pattern gives the pattern on the faces "
                    + "parallel to its plane and edge streaks on the perpendicular sides.",
                "Adjacent strokes continue the same pattern instead of restarting - placed "
                    + "cells remember their position in the drawing, so painting column by "
                    + "column lines up. Switching or editing patterns starts fresh.",
                "Empty cells are holes: nothing is placed there. Tiling controls repetition: "
                    + "none, sides (wraps across the width), start/end (columns repeat), or "
                    + "both.",
                "Variation (experimental): at +-1 to +-3, a cell may swap to a block within "
                    + "that many positions of it in the colour ordering of your inventory - "
                    + "subtle texture shimmer with no chaos."),
            t("The pattern editor", "Pick a block, draw on the grid.", List.of(
                "Left-click a block in the list to make it your drawing block - its sprite "
                    + "follows the cursor. Hold left-click and scrub over the canvas to draw "
                    + "it into cells (over empty cells and other blocks alike). The pinned "
                    + "Eraser row clears any cell instead.",
                "Press on a cell that already holds your selected block and the drag becomes "
                    + "an eraser for that block only - other blocks and empty cells are left "
                    + "alone. A drag never toggles cell by cell.",
                "Shift-drag draws a straight or 45-degree line, previewed live and locked in "
                    + "on release. Right-click flood fills the clicked cell's connected "
                    + "same-content region (other blocks bound it). Middle-click picks a "
                    + "cell's block; Ctrl-click sets the placement-origin plus (one per grid) "
                    + "so a fresh stroke can start anywhere in the drawing.",
                "Width/Height (1-32) resize the canvas; shrinking keeps the cropped cells "
                    + "until you save, so growing back restores them; Clear (with a confirm) "
                    + "empties the grid. 'start' is the edge placed first - the Start: "
                    + "Top/Bottom button flips it so painting up from the ground keeps the "
                    + "drawing upright. The iso preview up top shows the wall with variance "
                    + "applied; its expand button opens it full screen."))),

        t("Gradient paint", "Blend along the active palette - between markers or free-hand.",
            List.of(
                "Between markers: mark a start and an end block, then click the face the line "
                    + "runs out of and hold. The palette stretches start-to-end across the line; "
                    + "Automatic segments anchor to the real marker blocks.",
                "Outside markers: the gradient grows out of whatever face you click, sized by "
                    + "the palette's Sizing mode; the block you start on anchors an Automatic "
                    + "start.",
                "The helper text always says what the next click would anchor to - markers, or "
                    + "the palette alone."),
            t("Paint memory", "How free-hand paint keeps its place.", List.of(
                "Painting outside markers remembers which step each placed block was, so "
                    + "clicking the face of a half-finished gradient continues it instead of "
                    + "restarting. Pattern placements remember their plane the same way.",
                "The memory clears when you switch or edit the active palette or pattern, or "
                    + "after the \"Paint memory\" idle time in Settings (default 10 minutes, up "
                    + "to 30) - stop for longer than that and the next click starts fresh. "
                    + "Clicking a FINISHED gradient starts a new one on top rather than doing "
                    + "nothing. Changing a pattern's variance does NOT reset an in-progress "
                    + "pattern - only new cells roll the new variance.",
                "3D fills remember their centre: click a block that belongs to one and it keeps "
                    + "growing the same sphere from the original middle; click elsewhere to start "
                    + "a new one."))),

        t("Noise paint", "Natural, blotchy 3D patterns - regions, surfaces, or free-hand.",
            List.of(
                "Every block is chosen by a 3D noise field sampled at its position, so blocks "
                    + "clump into organic patches instead of stripes. The order is the active "
                    + "palette's strip - top of the strip in the valleys, bottom on the peaks - "
                    + "and the noise type, scale, and seed come from the palette too.",
                "Classic region fill: mark a region (start markers on one side, end markers "
                    + "opposite, rows of air between pairs), switch to 3D Fill, aim into the air "
                    + "and right-click - the whole region fills at once.",
                "Or paint it anywhere: Single columns, Face surfaces, and free 3D blobs all work, "
                    + "bounded by end markers like every other paint type."),
            t("Noise fields", "Smooth randomness over space.", List.of(
                "A noise field gives every position in the world a value, and nearby positions "
                    + "get similar values - that's why the pattern forms patches. Your palette is "
                    + "stretched over the field: low values place segments from the top of the "
                    + "strip, high values from the bottom.",
                "Smooth: soft, rounded blobs. Perlin: natural, ridged shapes. Fractal: several "
                    + "layers of detail on top of each other.")),
            t("Seed and Scale", "Reroll or resize the pattern (saved on the palette).", List.of(
                "Seed: any text or number - the same seed always gives the same pattern. Change "
                    + "it to reroll.",
                "Scale: the feature size in blocks, per axis. With Lock XYZ on, one slider drives "
                    + "all three axes; unlock it to stretch the pattern (tall streaks, flat "
                    + "layers)."))),

        t("Finder", "Every block in the game, ranked by colour or brightness.",
            List.of(
                "A discovery tool: find the blocks closest to a chosen colour, beyond what's in "
                    + "your inventory. The left list is every placeable block in the game, in the "
                    + "order set by the Sort button - Color is a rainbow (grays first dark to "
                    + "light, then hue families with brightness running through each band), "
                    + "Brightness runs dark to light.",
                "Click any row to select that block: it highlights and scrolls to the middle of "
                    + "the list, so the nearest colours sit just above and below it. The small "
                    + "target button jumps back to the selection after you scroll away.",
                "Or pick a pure colour instead: click the hue/saturation field on the right and "
                    + "set the brightness slider. A coloured marker line in the list shows "
                    + "exactly where that colour falls in the ordering.",
                "Colours come from each block's actual texture (resource packs included), "
                    + "measured once per game session.")));
}
