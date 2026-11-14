package co.fax.wang;

import co.fax.wang.ui.FlatButton;
import co.fax.wang.ui.Ui;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The block list behind a marker's "Show as block" (its toolbar's Rendering page): a filter box
 * over a scrolling grid of every block. LMB a block and the marker is drawn with that block's
 * textures; "No block" goes back to the flat tint. Picking closes the screen, Esc closes it
 * without changing anything. Nothing is placed in the world either way — it is only a preview of
 * how the marked shape would look built from that block.
 */
public class BlockPickScreen extends Screen {

    private static final int CELL = 18;

    private record Row(String id, String name, ItemStack stack) {}

    private final String current;
    private final Consumer<String> onPick;
    private final Runnable onClosed;

    private final List<Row> all = new ArrayList<>();
    private final List<Row> shown = new ArrayList<>();
    private String filter = "";
    private int scroll; // in grid rows
    private EditBox filterBox;
    private FlatButton noneButton;
    private int px, pw, gridY, gridH, cols;

    /**
     * @param current  the block shown now (item id; empty = none), marked in the grid
     * @param onPick   called with the picked block's item id, or "" for none
     * @param onClosed called after the screen closes, picked or not
     */
    public BlockPickScreen(String current, Consumer<String> onPick, Runnable onClosed) {
        super(Component.literal("Show marker as a block"));
        this.current = current == null ? "" : current;
        this.onPick = onPick;
        this.onClosed = onClosed;
    }

    @Override
    protected void init() {
        if (all.isEmpty()) {
            for (var item : BuiltInRegistries.ITEM.stream().toList()) {
                if (!(item instanceof BlockItem)) continue;
                Identifier id = BuiltInRegistries.ITEM.getKey(item);
                if (id == null) continue;
                ItemStack st = new ItemStack(item);
                all.add(new Row(id.toString(), st.getHoverName().getString(), st));
            }
        }
        pw = Math.min(this.width - 24, 16 * CELL);
        cols = Math.max(1, pw / CELL);
        pw = cols * CELL;
        px = (this.width - pw) / 2;
        int top = 12 + Ui.SECTION_H;
        int noneW = this.font.width("No block") + 12;
        filterBox = new EditBox(this.font, px, top, pw - noneW - 4, 18, Component.literal("Filter"));
        filterBox.setHint(Component.literal("Filter blocks…"));
        filterBox.setMaxLength(64);
        filterBox.setValue(filter);
        filterBox.setResponder(s -> {
            filter = s;
            scroll = 0;
            rebuild();
        });
        addRenderableWidget(filterBox);
        setInitialFocus(filterBox);
        noneButton = new FlatButton("No block", FlatButton.Style.PLAIN, () -> pick(""))
                .setBounds(px + pw - noneW, top + 1, noneW);
        gridY = top + 24;
        int avail = this.height - 16 - gridY;
        gridH = Math.max(CELL, avail - avail % CELL);
        rebuild();
        // Open with the current block in view.
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).id().equals(current)) scroll = Math.min(maxScroll(), Math.max(0, i / cols - 1));
        }
    }

    private void rebuild() {
        shown.clear();
        String q = filter.toLowerCase(Locale.ROOT).strip();
        for (Row r : all) {
            if (q.isEmpty() || r.name().toLowerCase(Locale.ROOT).contains(q) || r.id().contains(q)) shown.add(r);
        }
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private int maxScroll() {
        int rows = (shown.size() + cols - 1) / cols;
        return Math.max(0, rows - gridH / CELL);
    }

    private Row rowAt(double mx, double my) {
        if (mx < px || mx >= px + pw || my < gridY || my >= gridY + gridH) return null;
        int idx = (scroll + (int) ((my - gridY) / CELL)) * cols + (int) ((mx - px) / CELL);
        return idx >= 0 && idx < shown.size() ? shown.get(idx) : null;
    }

    private void pick(String id) {
        onPick.accept(id);
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, Ui.BACKDROP);
        Ui.section(g, this.font, px, 12, pw, "Show marker as a block",
                current.isEmpty() ? "none" : Gradient.toolDisplayName(current));
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        noneButton.render(g, this.font, mouseX, mouseY);

        Row hover = rowAt(mouseX, mouseY);
        g.enableScissor(px, gridY, px + pw, gridY + gridH);
        for (int i = scroll * cols; i < shown.size(); i++) {
            int cy = gridY + (i / cols - scroll) * CELL;
            if (cy >= gridY + gridH) break;
            int cx = px + (i % cols) * CELL;
            Row r = shown.get(i);
            if (r.id().equals(current)) {
                g.fill(cx, cy, cx + CELL, cy + CELL, Ui.SELECTED_FILL);
                Ui.outline(g, cx, cy, CELL, CELL, Ui.SELECTED);
            } else if (r == hover) {
                g.fill(cx, cy, cx + CELL, cy + CELL, Ui.HOVER);
            }
            g.item(r.stack(), cx + 1, cy + 1);
        }
        g.disableScissor();
        if (maxScroll() > 0) {
            int trackX = px + pw + 2;
            int visible = gridH / CELL, rows = (shown.size() + cols - 1) / cols;
            int thumbH = Math.max(8, gridH * visible / Math.max(1, rows));
            int thumbY = gridY + (int) ((gridH - thumbH) * (double) scroll / maxScroll());
            g.fill(trackX, gridY, trackX + 2, gridY + gridH, 0x30FFFFFF);
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0x90FFFFFF);
        }
        String foot = hover != null ? hover.name() + " · LMB show the marker as this"
                : shown.isEmpty() ? "No block matches the filter"
                : "A preview only: nothing is placed · Esc cancel";
        g.text(this.font, this.font.plainSubstrByWidth(foot, pw), px, this.height - 12,
                hover != null ? Ui.TEXT : Ui.HINT);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (noneButton.click(event.x(), event.y())) return true;
            Row r = rowAt(event.x(), event.y());
            if (r != null) {
                pick(r.id());
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(scrollY) * 2));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreenAndShow(null);
        onClosed.run();
    }
}
