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
     * Substitute the {open}/{cycle}/{paint}/{clear} placeholders with the CURRENT key bindings, so
     * the manual stays correct after a rebind. Runs on every layout rebuild (each screen open).
     */
    private static String subKeys(String s) {
        if (s.indexOf('{') < 0) return s;
        return s.replace("{open}", Gradient.boundKey("open"))
                .replace("{cycle}", Gradient.boundKey("cycle"))
                .replace("{paint}", Gradient.boundKey("paint"))
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

        t("Getting started", "Assign a tool item, hold it, paint.", List.of(
            "1. On the Settings tab, click \"Paint tool\" and pick any item (a stick works well). "
                + "FW Paint only does anything while you hold that item.",
            "2. Hold the tool. Press {paint} (or a tool tab's Use button) to switch what it "
                + "paints - Solid, Gradient, or Noise. Press {cycle} to cycle where blocks go: "
                + "Marker, Marker corners, Marker draw, Single, Face, 3D Fill, Disabled. Any "
                + "paint type works with any placement mode, with or without markers. The helper "
                + "text in the top-left always shows both.",
            "3. Aim at a block face and right-click to paint (hold to keep going). Markers are "
                + "optional - they bound what you paint and give gradients their endpoints.",
            "{open} opens and closes this screen. All keys are rebindable under "
                + "Options > Controls > Key Binds > Misc (this manual always shows the current "
                + "bindings). Placement is done with normal block-place actions the server "
                + "validates, so everything works in multiplayer.")),

        t("Placement modes", "Single, Face, and 3D Fill - where blocks go (cycle with {cycle}).",
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
                "Hold the tool in Marker mode (cycle with {cycle}). Left-click marks start blocks "
                    + "(turquoise), right-click marks end blocks (amber). Click and drag to mark a "
                    + "straight line locked to one axis. Starting a click or drag on an "
                    + "already-marked block removes instead of adds.",
                "Markers aren't real blocks, so every marker mode reaches out to the Marker dist "
                    + "setting - far beyond block-placing range.",
                "Hold the clear-connected key ({clear}, rebindable) while clicking a marked "
                    + "block to remove it together with every marker connected to it in that "
                    + "plane, instead of one at a time.",
                "Markers are remembered per world and dimension. The Clear Markers button on the "
                    + "Settings tab removes them all."),
            t("Marker corners", "Two clicks mark a whole volume of columns.", List.of(
                "Left-click the first corner - its clicked face sets the column direction, and a "
                    + "thin box follows your aim showing the volume. Right-click the opposite "
                    + "corner to commit: start markers fill the first corner's plane, end markers "
                    + "the opposite plane, one pair per column. Left-click again to move the "
                    + "pending corner; switching modes cancels it.",
                "The box depth is capped at Marker dist. Once placed they're perfectly normal "
                    + "markers - toggle any of them off as usual.",
                "With Auto end marker on, each column hugs the terrain instead: the start marker "
                    + "lands on the last solid block before the column's air gap, and the end on "
                    + "the first non-air block after it (or the far plane if it's all air). "
                    + "Everything stays inside the drawn box - a column with no start block "
                    + "inside it, or whose start and end would land on the same block, is simply "
                    + "skipped.",
                "Clicking an already-marked block toggles it off here too ({clear} for its whole "
                    + "plane) - no need to switch back to plain Marker mode.")),
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
                    + "whole Faces, or 3D blobs. Which block it places comes from the Match "
                    + "button."),
            t("Match modes", "How Solid picks the block it places.", List.of(
                "Selected block: places the block you ticked in the list on the left.",
                "Exact block: copies exactly the block you clicked.",
                "Closest color / Closest brightness: looks at the clicked block and places the "
                    + "closest match from your inventory. Closeness is measured as perceived "
                    + "colour (Oklab) - the Color match button in Settings switches back to the "
                    + "classic RGB maths.",
                "Blocks marked with an X in the list are never placed, even on an exact match."))),

        t("Gradient paint", "Blend from one block to another - along markers or free-hand.",
            List.of(
                "Between markers: mark a start and an end block, then click the face the line "
                    + "runs out of and hold. The gradient stretches start-to-end; the marker "
                    + "blocks themselves count as its first and last blocks.",
                "Outside markers: the gradient comes from the picker list ([S] to [E]), one "
                    + "block per step, growing out of whatever face you click. When the last "
                    + "step is placed the column simply stops.",
                "The helper text always says where the endpoints come from: \"Selected from "
                    + "markers\", \"Selected from picker\", or a split pair when one marker "
                    + "floats in air (its side falls back to the picker block)."),
            t("Gradient memory", "How free-hand gradients keep their place.", List.of(
                "Painting outside markers remembers which step each placed block was, so "
                    + "clicking the face of a half-finished gradient continues it instead of "
                    + "restarting.",
                "The memory clears when you change the gradient in the picker, or after the "
                    + "\"Gradient memory\" idle time in Settings (default 1 minute) - stop for "
                    + "longer than that and the next click starts a fresh gradient.",
                "3D fills remember their centre: click a block that belongs to one and it keeps "
                    + "growing the same sphere from the original middle; click elsewhere to start "
                    + "a new one.")),
            t("The block list", "Reads top to bottom as the gradient you'll get.", List.of(
                "White rows are the gradient's steps in placement order, tagged [S] and [E] at "
                    + "the ends. The small icons on a row are that step's swappable stand-ins "
                    + "(see the Variation slider).",
                "Blue rows are blocks in your inventory that the gradient doesn't currently use. "
                    + "Green rows are must-use (the checkmark button forces them in). Red rows are "
                    + "excluded (the X button - never used).",
                "Shortcuts: double-click a row to toggle must-use, middle-click to exclude.")),
            t("Preview vs. placed gradient", "Why what you place can differ from the list.", List.of(
                "The list previews a gradient between the [S] and [E] blocks chosen on this "
                    + "screen. The actual fill, though, depends on the \"From:\" button:",
                "From: Markers - the endpoints are the real blocks sitting at your start and end "
                    + "markers in the world. If those aren't the same blocks as [S]/[E], the "
                    + "placed gradient is computed between them instead and can differ from the "
                    + "preview.",
                "From: Block list - the fill places exactly the preview's steps, no matter what "
                    + "blocks the markers are made of.",
                "Painting where there is no marker line always uses the picker - the mode adapts "
                    + "by itself, and the helper text tells you which one is in effect.")),
            t("Gradient modes (Order)", "How blocks are measured and sorted into a gradient.", List.of(
                "Color: sorts by average texture colour.",
                "Brightness: sorts by average texture brightness, light to dark.",
                "Top % Dark / Top % Light (and their Color variants): measures only the darkest "
                    + "or lightest slice of each texture's pixels - the Pixel % slider sets the "
                    + "slice size. Useful when a texture's accents matter more than its average.",
                "B&W Diff / Color Diff: sorts by how different each texture is from the start "
                    + "block - a gradient of \"increasingly unlike the start\".",
                "Pick: manual ordering. Click a row to raise its number, right-click to lower it "
                    + "(0 removes it). Lowest number places first, highest last; blocks sharing a "
                    + "number share a step.",
                "Colour and brightness are measured as the eye sees them (Oklab) - the Color "
                    + "match button in Settings switches every tool back to the classic "
                    + "luma/RGB maths.")),
            t("Curve", "Where the gradient changes fastest.", List.of(
                "Linear: even change all the way. Ease In: holds the start colour longer. "
                    + "Ease Out: reaches the end colour sooner. Ease In/Out: lingers at both ends. "
                    + "Step: hard quantised bands instead of a smooth blend.")),
            t("Sliders", "Variation, Chaos, Step length, Max steps, Pixel %.", List.of(
                "Variation: lets blocks similar to a step randomly stand in for it - bands get "
                    + "visual variety without gaining or losing steps. At 0% every step is exactly "
                    + "its own block and the fill is fully even.",
                "Chaos: chance a placement repeats the previous step or skips ahead one - adds "
                    + "dither across band boundaries.",
                "Step length: chance each step runs randomly longer or shorter (its neighbour "
                    + "compensates, so the gradient still starts and ends on time).",
                "Max steps: caps how many distinct blocks the gradient uses.",
                "Pixel %: how much of each texture the Top % modes measure.")),
            t("Preview", "A cylinder placed with the real pipeline.", List.of(
                "The open-topped cylinder under the sliders shows the gradient as it would "
                    + "place: the top rim is the start, the bottom rim the end, and every column "
                    + "rolls its own Chaos, Step length, and Variation - the spread you see "
                    + "between columns is the spread a real build gets.",
                "It re-rolls whenever a setting changes. The expand button opens it full "
                    + "screen; X or Esc closes it."))),

        t("Noise paint", "Natural, blotchy 3D patterns - regions, surfaces, or free-hand.",
            List.of(
                "Every block is chosen by a 3D noise field sampled at its position, so blocks "
                    + "clump into organic patches instead of stripes. The order always comes from "
                    + "the picker list ([S] = valleys, [E] = peaks).",
                "Classic region fill: mark a region (start markers on one side, end markers "
                    + "opposite, rows of air between pairs), switch to 3D Fill, aim into the air "
                    + "and right-click - the whole region fills at once.",
                "Or paint it anywhere: Single columns, Face surfaces, and free 3D blobs all work, "
                    + "bounded by end markers like every other paint type."),
            t("Noise fields", "Smooth randomness over space.", List.of(
                "A noise field gives every position in the world a value, and nearby positions "
                    + "get similar values - that's why the pattern forms patches. Your block list "
                    + "is stretched over the field: low values place blocks from the [S] end, "
                    + "high values from the [E] end.",
                "Smooth: soft, rounded blobs. Perlin: natural, ridged shapes. Fractal: several "
                    + "layers of detail on top of each other.")),
            t("Seed and Scale", "Reroll or resize the pattern.", List.of(
                "Seed: any text or number - the same seed always gives the same pattern. Change "
                    + "it to reroll.",
                "Scale: the feature size in blocks, per axis. With Lock XYZ on, one slider drives "
                    + "all three axes; unlock it to stretch the pattern (tall streaks, flat "
                    + "layers).")),
            t("Ordering and sliders", "Shared ideas with the gradient tool.", List.of(
                "Order, Pixel %, Variation, Chaos, and Max steps work exactly like the gradient "
                    + "tool's (see Gradient paint), but keep their own separate values for the "
                    + "noise tool.")),
            t("Preview", "A live cube of the field, sampled where you stand.", List.of(
                "The cube at the bottom right shows the noise built from your current blocks and "
                    + "settings, sampled at real world coordinates starting at your feet - the "
                    + "right face runs East, the left face South, the top face up. What you see "
                    + "is what painting that region would place.",
                "Drag a face to pan along it - the drag locks to the face you press. The top "
                    + "face pans across the ground, the side faces also pan up and down. The "
                    + "coordinates under the cube follow along.",
                "The expand button opens the preview full screen; X or Esc closes it."))),

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
