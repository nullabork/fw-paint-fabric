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
        for (String s : wrap(topic.summary(), indent + INDENT)) {
            lines.add(new Line(s, indent + INDENT, GREY, null));
        }
        if (!open) return;
        for (String para : topic.body()) {
            lines.add(BLANK);
            for (String s : wrap(para, indent + INDENT)) {
                lines.add(new Line(s, indent + INDENT, BODY, null));
            }
        }
        for (Topic child : topic.children()) {
            lines.add(BLANK);
            addTopic(child, depth + 1);
        }
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
            "2. Hold the tool. Press V to switch what it paints (Solid, Gradient, or Noise) and G "
                + "to cycle the active type's mode. The helper text in the top-left corner always "
                + "shows the current type and mode.",
            "3. In Marker mode, left/right-click blocks to place markers. Then cycle (G) to the "
                + "type's action mode (Place, Extrude, or 3D Fill) and right-click to paint.",
            "K opens and closes this screen. All keys are rebindable under "
                + "Options > Controls > Key Binds > Misc. Placement is done with normal block-place "
                + "actions the server validates, so everything works in multiplayer.")),

        t("Markers", "Turquoise start and amber end blocks that aim and bound every tool.",
            List.of(
                "Hold the tool in Marker mode (cycle with G). Left-click marks start blocks "
                    + "(turquoise), right-click marks end blocks (amber). Click and drag to mark a "
                    + "straight line locked to one axis. Starting a click or drag on an "
                    + "already-marked block removes instead of adds.",
                "Markers are remembered per world and dimension. The Clear Markers button on the "
                    + "Settings tab removes them all."),
            t("Start markers", "Where painting begins.", List.of(
                "Gradient and Noise: a start marker's block is the first block of the gradient - "
                    + "the fill runs from it toward an end marker.",
                "Solid Extrude: clicking a face whose column has a start marker behind it extrudes "
                    + "every connected start marker in that plane together, so one hold builds a "
                    + "whole wall.",
                "Solid 3D Fill: start markers define a space (lines out of all six faces, up to "
                    + "Marker dist). A fill that starts inside that space never leaves it, and one "
                    + "that starts outside never enters it.")),
            t("End markers", "Where painting stops.", List.of(
                "An end marker must line up straight with a start marker, within Marker dist of "
                    + "it - the pair defines a start-to-end line.",
                "Gradient and Noise: the end marker's block is the last block of the gradient; "
                    + "fills only happen strictly between a start and its end.",
                "Solid: an end marker acts like a solid block - an extruding column or a 3D fill "
                    + "stops at it and never passes it, even when the marker floats in air.")),
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
                    + "constraint space in Solid 3D Fill."))),

        t("Solid paint", "One block: extrude walls or fill volumes.",
            List.of("Cycle its modes with G: Marker, Extrude, 3D Fill, Disabled."),
            t("Extrude", "Columns grow straight out of the clicked face.", List.of(
                "Aim at a block face and hold right-click: a column of blocks grows out of the "
                    + "face, one layer at a time, until it hits something (a block, an end marker, "
                    + "or the edge of your reach).",
                "If the clicked column has a start marker behind it, all connected start markers "
                    + "in the same plane extrude together - each column continues from its own "
                    + "first air gap, so re-clicking a half-built wall finishes it. A column that "
                    + "already reached its end marker stays finished - re-clicking never pushes it "
                    + "past the marker.")),
            t("3D Fill", "Flood-fills connected air outward from the clicked face.", List.of(
                "Tap right-click to place the first shell, hold to grow the fill layer by layer. "
                    + "The fill only spreads through connected air, so walls, end markers, and "
                    + "start-marker space (see Markers) contain it.")),
            t("Match modes", "How Solid picks the block it places.", List.of(
                "Selected block: places the block you ticked in the list on the left.",
                "Exact block: copies exactly the block you clicked.",
                "Closest color / Closest brightness: looks at the clicked block and places the "
                    + "closest match from your inventory.",
                "Blocks marked with an X in the list are never placed, even on an exact match."))),

        t("Gradient paint", "Blend from one block to another along a marked line.",
            List.of(
                "Mark a start and an end block (Marker mode), cycle to Place (G), aim between "
                    + "them and hold right-click. Blocks chain from the start toward the end, "
                    + "stepping through the colours in between. The marker blocks themselves count "
                    + "as the gradient's first and last blocks - the fill covers only the cells "
                    + "between them."),
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
                    + "blocks the markers are made of.")),
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
                    + "number share a step.")),
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
                "Pixel %: how much of each texture the Top % modes measure."))),

        t("Noise paint", "Natural, blotchy 3D patterns filling the space between markers.",
            List.of(
                "Mark a region (start markers on one side, end markers opposite, so rows of air "
                    + "sit between pairs), cycle to Place, aim into the air and right-click: the "
                    + "region flood-fills, and each block is chosen by a 3D noise field sampled at "
                    + "its position - so blocks clump into organic patches instead of stripes."),
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
            t("Preview", "A live top-down slice of the field.", List.of(
                "The grid at the bottom right previews the noise with your current blocks and "
                    + "settings. Click and drag it to pan around."))));
}
