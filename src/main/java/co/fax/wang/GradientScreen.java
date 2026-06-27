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

    private static final int LIST_TOP = 116;
    private static final int LIST_W = 200;
    private static final int BOTTOM_MARGIN = 40; // space reserved below the list (Done button + hint)

    /** One filtered match: registry id + display name. */
    private record Row(String id, String name) {}

    private EditBox filterBox;
    private Button modeButton;
    private final List<Row> matches = new ArrayList<>();
    private String filter = "";
    private int rowHeight = 12;
    private boolean truncated = false;

    public GradientScreen() {
        super(Component.literal("Gradient"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        rowHeight = this.font.lineHeight + 3;

        // Mode button — reflects the current mode and cycles it on click (same state the keybind drives).
        modeButton = addRenderableWidget(Button.builder(modeLabel(), b -> {
            GradientConfig cfg = ConfigManager.get();
            cfg.mode = cfg.mode.next();
            ConfigManager.save();
            b.setMessage(modeLabel());
        }).bounds(cx - 100, 50, 200, 20).build());

        // Filter box — type to narrow the item list.
        filterBox = new EditBox(this.font, cx - 100, 90, 200, 20, Component.literal("Filter"));
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

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(cx - 100, this.height - 28, 200, 20).build());
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
        int x0 = this.width / 2 - LIST_W / 2;
        if (mouseX < x0 || mouseX > x0 + LIST_W || mouseY < LIST_TOP) return -1;
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
        int x0 = cx - LIST_W / 2;

        g.centeredText(this.font, this.title, cx, 16, WHITE);

        // Selected tool — read live so it updates the instant a row is clicked.
        String sel = ConfigManager.get().selectedTool;
        String selName = sel.isEmpty() ? "(none — pick one below)" : Gradient.toolDisplayName(sel);
        g.centeredText(this.font, Component.literal("Selected tool: " + selName), cx, 32, WHITE);

        // Keep the mode button label in sync if the keybind changed the mode while open.
        modeButton.setMessage(modeLabel());

        g.centeredText(this.font,
                Component.literal("Type to filter, click an item to set it as your tool"),
                cx, 78, GREY);

        // Result rows.
        for (int i = 0; i < matches.size(); i++) {
            Row row = matches.get(i);
            int y = LIST_TOP + i * rowHeight;
            boolean hover = mouseX >= x0 && mouseX <= x0 + LIST_W && mouseY >= y && mouseY < y + rowHeight;
            boolean selected = row.id().equals(sel);

            if (selected) {
                g.fill(x0, y, x0 + LIST_W, y + rowHeight, SELECTED_BG);
            } else if (hover) {
                g.fill(x0, y, x0 + LIST_W, y + rowHeight, HOVER_BG);
            }
            int color = selected ? ROW_SELECTED : (hover ? WHITE : ROW_DIM);
            String label = this.font.plainSubstrByWidth(row.name(), LIST_W - 8);
            g.text(this.font, label, x0 + 4, y + 2, color);
        }

        if (truncated) {
            int hintY = LIST_TOP + matches.size() * rowHeight + 1;
            g.centeredText(this.font,
                    Component.literal("More matches… refine the filter to narrow it down"),
                    cx, hintY, GREY);
        }
    }

    @Override
    public void onClose() {
        ConfigManager.save();
        super.onClose();
    }
}
