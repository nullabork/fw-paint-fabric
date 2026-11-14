package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.palette.PaletteKind;
import co.fax.wang.palette.PaletteStore;
import co.fax.wang.wheel.WheelScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * Dev-only screen tour, off unless {@code FWPAINT_DEBUG_TOUR=1}: once in a world, opens every FW
 * Paint screen in turn (a few seconds each) and logs {@code TOUR <name>} as each one opens, so a
 * screenshot script can capture them all without anyone clicking. Closes the last one and logs
 * {@code TOUR done}. A value other than {@code 1} keeps only the stops whose name starts with it
 * ({@code FWPAINT_DEBUG_TOUR=wheel}: just the wheel rings).
 */
public final class DebugTour {

    private static final String TOUR = System.getenv("FWPAINT_DEBUG_TOUR");
    private static final boolean ON = TOUR != null;
    private static final int STEP_TICKS = 60;
    private static final Logger LOG = LoggerFactory.getLogger("fwpaint/tour");

    private record Stop(String name, Runnable before, Supplier<Screen> screen) {}

    private static List<Stop> stops;
    /** What the tour changes, put back when it ends so the dev config is left as it was. */
    private static List<co.fax.wang.config.WheelPress> savedHistory;
    private static PaintType savedPaint;
    private static int index = -1;
    private static int wait;

    private DebugTour() {}

    /** Called each client tick from {@link DebugRender#tick}. */
    static void tick(Minecraft mc) {
        if (!ON || mc.player == null || mc.level == null || index >= size()) return;
        if (index < 0) {
            if (mc.player.tickCount < 100) return; // let the world settle first
            savedHistory = new java.util.ArrayList<>(ConfigManager.get().wheelHistory);
            savedPaint = ConfigManager.get().activePaintType;
            WheelScreen.debugSeedHistory();
            index = 0;
            open(mc);
            return;
        }
        if (++wait < STEP_TICKS) return;
        wait = 0;
        index++;
        if (index >= size()) {
            WheelScreen.debugHoverPath = null;
            mc.setScreenAndShow(null);
            ConfigManager.get().wheelHistory = savedHistory;
            ConfigManager.get().activePaintType = savedPaint;
            ConfigManager.save();
            LOG.info("TOUR done");
            return;
        }
        open(mc);
    }

    private static int size() {
        return stops().size();
    }

    private static void open(Minecraft mc) {
        Stop s = stops().get(index);
        s.before().run();
        mc.setScreenAndShow(s.screen().get());
        LOG.info("TOUR {}", s.name());
    }

    /** Open the tour stop called {@code name} (for {@link DebugScript}'s {@code screen}). */
    static void openStop(Minecraft mc, String name) {
        for (Stop s : allStops()) {
            if (s.name().equals(name)) {
                s.before().run();
                mc.setScreenAndShow(s.screen().get());
                return;
            }
        }
        LOG.warn("TOUR no screen stop '{}'", name);
    }

    private static void paint(PaintType t) {
        ConfigManager.get().activePaintType = t;
    }

    private static List<Stop> all;

    private static List<Stop> stops() {
        if (stops != null) return stops;
        stops = allStops();
        if (!"1".equals(TOUR)) stops = stops.stream().filter(s -> s.name().startsWith(TOUR)).toList();
        return stops;
    }

    private static List<Stop> allStops() {
        if (all != null) return all;
        Runnable none = () -> {};
        all = List.of(
                new Stop("paint-solid", () -> paint(PaintType.SOLID), GradientScreen::atPaintTab),
                new Stop("paint-gradient", () -> paint(PaintType.GRADIENT), GradientScreen::atPaintTab),
                new Stop("paint-pattern", () -> paint(PaintType.PATTERN), GradientScreen::atPaintTab),
                new Stop("place", () -> paint(PaintType.GRADIENT), GradientScreen::atPlaceTab),
                new Stop("finder", none, () -> GradientScreen.atBarTab(2)),
                new Stop("settings", none, () -> GradientScreen.atBarTab(3)),
                new Stop("help", none, () -> GradientScreen.atHelpTopic("Palettes")),
                new Stop("palette-editor", none, () -> new PaletteEditScreen(first(PaletteKind.GRADIENT))),
                new Stop("pattern-editor", none, () -> new PatternEditScreen(first(PaletteKind.PATTERN))),
                new Stop("hud-move", none, () -> new HudPlacementScreen(null)),
                new Stop("block-pick", none, () -> new BlockPickScreen("minecraft:oak_log", id -> { }, () -> { })),
                wheel("wheel", PaintType.GRADIENT),
                wheel("wheel-paint", PaintType.GRADIENT, 0, 1),
                wheel("wheel-palette", PaintType.GRADIENT, 1, 0),
                wheel("wheel-palette-pattern", PaintType.PATTERN, 1, 0),
                wheel("wheel-palette-solid", PaintType.SOLID, 1, 1),
                wheel("wheel-solid-blocks", PaintType.SOLID, 1, 0, 2),
                wheel("wheel-place", PaintType.GRADIENT, 2, 4),
                wheel("wheel-place-blocks", PaintType.GRADIENT, 2, 0, 1),
                wheel("wheel-place-markers", PaintType.GRADIENT, 2, 1, 0),
                wheel("wheel-place-shapes", PaintType.GRADIENT, 2, 2, 1),
                wheel("wheel-place-options", PaintType.GRADIENT, 2, 3, 0));
        return all;
    }

    /** A wheel stop hovering {@code path} (root slot, ring-1 slot, ring-2 slot). */
    private static Stop wheel(String name, PaintType type, int... path) {
        return new Stop(name, () -> {
            paint(type);
            WheelScreen.debugHoverPath = path.length == 0 ? null : path;
        }, WheelScreen::new);
    }

    private static co.fax.wang.palette.Palette first(PaletteKind kind) {
        List<co.fax.wang.palette.Palette> all = PaletteStore.allOf(kind);
        return all.isEmpty() ? null : all.get(0);
    }
}
