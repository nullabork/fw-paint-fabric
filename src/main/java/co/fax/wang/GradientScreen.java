package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings screen (opened by the K keybind). Lets you:
 * <ul>
 *   <li>see and pick the <b>tool</b> item — type in the filter box, click a result row;</li>
 *   <li>cycle the <b>activation mode</b> via a persistent button (mirrors the cycle keybind).</li>
 * </ul>
 *
 * <p>The result list is drawn as plain clickable text rows (not widgets) so it stays compact and
 * fits more entries — the visible count scales to the window height, so a smaller GUI scale shows
 * more rows. Selection is handled in {@link #mouseClicked}.
 *
 * <p>26.2 GUI notes: override {@code extractRenderState(GuiGraphicsExtractor, ...)} (NOT
 * {@code render}); text colours are ARGB and must include the alpha byte ({@code 0xFF......}).
 */
public class GradientScreen extends Screen {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFA0A0A0;
    private static final int ROW_DIM = 0xFFC0C0C0;
    private static final int ROW_SELECTED = 0xFF55FF55;
    private static final int HOVER_BG = 0x33FFFFFF;
    private static final int SELECTED_BG = 0x3355FF55;

    private static final int PANEL_W = 150;  // width of each side panel (list + buttons)
    private static final int GAP = 8;        // gap between the two panels
    private static final int LIST_TOP = 66;  // y where the item list starts (below the filter box)
    private static final int BOTTOM_MARGIN = 40; // space reserved below the list (Done button)

    /** One filtered match: registry id + display name. */
    private record Row(String id, String name) {}

    private EditBox filterBox;
    private Button modeButton;
    private final List<Row> matches = new ArrayList<>();
    private String filter = "";
    private int rowHeight = 12;
    private int listX = 0;   // left edge of the left-hand list panel (set in init)
    private boolean truncated = false;

    public GradientScreen() {
        super(Component.literal("Gradient"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        rowHeight = this.font.lineHeight + 3;

        // Two side-by-side panels: tool filter + list on the LEFT, setting buttons on the RIGHT.
        listX = cx - PANEL_W - GAP / 2; // left panel x
        int rx = cx + GAP / 2;          // right panel (buttons) x

        // Filter box (left panel, above the list).
        filterBox = new EditBox(this.font, listX, 40, PANEL_W, 20, Component.literal("Filter"));
        filterBox.setHint(Component.literal("Filter items…"));
        filterBox.setMaxLength(64);
        filterBox.setValue(filter);
        filterBox.setResponder(s -> {
            filter = s;
            rebuildMatches();
        });
        addRenderableWidget(filterBox);
        setInitialFocus(filterBox);
        rebuildMatches();

        // Setting buttons (right panel, stacked). Mode keeps a field so the keybind can sync it.
        modeButton = addRenderableWidget(Button.builder(modeLabel(), b -> {
            GradientConfig cfg = ConfigManager.get();
            cfg.mode = cfg.mode.next();
            ConfigManager.save();
            b.setMessage(modeLabel());
        }).bounds(rx, 40, PANEL_W, 20).build());

        cycleButton(rx, 64, PANEL_W, this::gradientLabel,
                () -> { GradientConfig c = ConfigManager.get(); c.gradientMode = c.gradientMode.next(); });
        cycleButton(rx, 88, PANEL_W, this::curveLabel,
                () -> { GradientConfig c = ConfigManager.get(); c.curve = c.curve.next(); });
        cycleButton(rx, 112, PANEL_W, this::sourceLabel,
                () -> { GradientConfig c = ConfigManager.get(); c.source = c.source.next(); });
        cycleButton(rx, 136, PANEL_W, this::cleanLabel,
                () -> { GradientConfig c = ConfigManager.get(); c.clean = !c.clean; });

        // Clear all markers (persisted), separated below the settings.
        addRenderableWidget(Button.builder(clearMarkersLabel(), b -> {
            MarkerManager.clearAll();
            b.setMessage(clearMarkersLabel());
        }).bounds(rx, 164, PANEL_W, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(cx - 100, this.height - 28, 200, 20).build());
    }

    private Component clearMarkersLabel() {
        int n = MarkerManager.startMarkers.size() + MarkerManager.endMarkers.size();
        return Component.literal("Clear Markers (" + n + ")");
    }

    /** Build a button at (x,y,w) that runs {@code onCycle}, saves, and refreshes its label. */
    private void cycleButton(int x, int y, int w, java.util.function.Supplier<Component> label, Runnable onCycle) {
        addRenderableWidget(Button.builder(label.get(), b -> {
            onCycle.run();
            ConfigManager.save();
            b.setMessage(label.get());
        }).bounds(x, y, w, 20).build());
    }

    private Component gradientLabel() {
        return Component.literal("Gradient: " + ConfigManager.get().gradientMode.displayName());
    }

    private Component curveLabel() {
        return Component.literal("Curve: " + ConfigManager.get().curve.displayName());
    }

    private Component sourceLabel() {
        return Component.literal("Source: " + ConfigManager.get().source.displayName());
    }

    private Component cleanLabel() {
        return Component.literal("Cleanliness: " + (ConfigManager.get().clean ? "Clean" : "Unclean"));
    }

    /** Recompute the filtered match list, capped to however many rows fit in the window. */
    private void rebuildMatches() {
        matches.clear();
        truncated = false;

        String q = filter.toLowerCase(Locale.ROOT);
        int available = (this.height - BOTTOM_MARGIN) - LIST_TOP;
        int maxRows = Math.max(1, available / rowHeight);

        for (Item item : BuiltInRegistries.ITEM.stream().toList()) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) continue;
            String idStr = id.toString();
            if (idStr.equals("minecraft:air")) continue;

            String name = new ItemStack(item).getHoverName().getString();
            if (!q.isEmpty()
                    && !name.toLowerCase(Locale.ROOT).contains(q)
                    && !idStr.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }

            if (matches.size() >= maxRows) {
                truncated = true; // more matches than fit on screen
                break;
            }
            matches.add(new Row(idStr, name));
        }
    }

    /** Index of the result row at the given screen coords, or -1 if none. */
    private int rowIndexAt(double mouseX, double mouseY) {
        if (mouseX < listX || mouseX > listX + PANEL_W || mouseY < LIST_TOP) return -1;
        int idx = (int) ((mouseY - LIST_TOP) / rowHeight);
        return (idx >= 0 && idx < matches.size()) ? idx : -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) return true; // widgets (filter box, buttons) first
        if (event.button() == 0) {
            int idx = rowIndexAt(event.x(), event.y());
            if (idx >= 0) {
                GradientConfig cfg = ConfigManager.get();
                cfg.selectedTool = matches.get(idx).id();
                ConfigManager.save();
                return true;
            }
        }
        return false;
    }

    private Component modeLabel() {
        return Component.literal("Mode: " + ConfigManager.get().mode.displayName());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick); // background + widgets
        int cx = this.width / 2;
        int x0 = listX;

        g.centeredText(this.font, this.title, cx, 12, WHITE);

        // Selected tool — read live so it updates the instant a row is clicked (left panel header).
        String sel = ConfigManager.get().selectedTool;
        String selName = sel.isEmpty() ? "(none — pick below)" : Gradient.toolDisplayName(sel);
        g.text(this.font, "Tool: " + selName, x0, 30, WHITE);

        // Keep the mode button label in sync if the keybind changed the mode while open.
        modeButton.setMessage(modeLabel());

        // Result rows.
        for (int i = 0; i < matches.size(); i++) {
            Row row = matches.get(i);
            int y = LIST_TOP + i * rowHeight;
            boolean hover = mouseX >= x0 && mouseX <= x0 + PANEL_W && mouseY >= y && mouseY < y + rowHeight;
            boolean selected = row.id().equals(sel);

            if (selected) {
                g.fill(x0, y, x0 + PANEL_W, y + rowHeight, SELECTED_BG);
            } else if (hover) {
                g.fill(x0, y, x0 + PANEL_W, y + rowHeight, HOVER_BG);
            }
            int color = selected ? ROW_SELECTED : (hover ? WHITE : ROW_DIM);
            String label = this.font.plainSubstrByWidth(row.name(), PANEL_W - 8);
            g.text(this.font, label, x0 + 4, y + 2, color);
        }

        if (truncated) {
            int hintY = LIST_TOP + matches.size() * rowHeight + 1;
            g.text(this.font, "More matches… refine the filter", x0, hintY, GREY);
        }
    }

    @Override
    public void onClose() {
        ConfigManager.save();
        super.onClose();
    }
}
