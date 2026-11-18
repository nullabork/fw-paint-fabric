package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import co.fax.wang.shape.ShapeMarkers;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dev-only scripted play, off unless {@code FWPAINT_DEBUG_SCRIPT=<file>}: once in a world, runs the
 * file's commands one per tick, driving the mod exactly as a player does — the camera's aim and the
 * real key bindings (LMB / RMB / MMB, the modifier, axis lock) and scroll — so markers can be
 * placed, cogs clicked, toolbars opened and controls dragged, rotated and scaled with nobody at the
 * keyboard. {@code snap <name>} logs {@code TOUR <name>} for the screenshot watcher (the same
 * protocol as {@link DebugTour}) and holds still while it captures.
 *
 * <p>While a script runs: anything held counts as the paint tool, losing window focus doesn't pause
 * the game, and markers and shapes save under a separate {@code debug|} world key, so a script's
 * {@code clear} never touches the markers you play with. Commands (one per line, {@code #} comments):
 *
 * <pre>
 * wait N                    do nothing for N ticks
 * cmd &lt;command&gt;             run a chat command (no slash): tp, fill, setblock, time, gamemode…
 * origin X Y Z              later "$x+3" / "$y" / "$z-1" are measured from here
 * look X Y Z                aim the camera at a point (eyes on it)
 * aim cog | control K | button K | ring K DEG | grip T|B [DEG]
 *                           aim at the open (else last touched) marker's cog, its control K,
 *                           or button K of its open toolbar; or the point DEG degrees round
 *                           gimbal ring K (0 green, 1 red) of the line in rotate mode
 * drop                      turn gravity on for the line made last
 * turn DYAW DPITCH          turn the camera by degrees
 * mode NAME | paint NAME    placement mode / paint type (enum names)
 * set FIELD VALUE           a config field (boolean, int, double, enum, string), for this run
 * down KEY | up KEY         hold / release attack, use, pick, mod or axis
 * click KEY [TICKS]         down, wait TICKS (default 2), up
 * scroll DELTA              a mouse-wheel step as the world sees it
 * style FIELD VALUE         a MarkerStyle field (faceColor, edgeOpacity, block…) of the open
 *                           (else last touched) box, ring or line; colours as hex RRGGBB
 * clear                     remove every (debug) marker and shape
 * screen NAME | close       open a DebugTour screen stop by name / close any screen
 * clearchat                 empty the chat (command feedback clutters screenshots)
 * snap NAME                 log "TOUR NAME" and hold still ~2 s for the capture
 * </pre>
 *
 * The script ends by releasing every key, clearing its markers, putting the config file back as
 * it was before the run and logging {@code TOUR done}.
 */
public final class DebugScript {

    private static final String FILE = System.getenv("FWPAINT_DEBUG_SCRIPT");
    private static final Logger LOG = LoggerFactory.getLogger("fwpaint/tour");
    private static final int SNAP_TICKS = 40;

    private static List<String> lines;
    private static int pc = -1;
    private static int waitTicks;
    private static final List<Runnable> afterWait = new ArrayList<>();
    private static boolean done;
    private static boolean pauseOnLostFocus;
    /** The config file as it was before the run; written back when it ends. */
    private static String savedConfig;

    private DebugScript() {}

    /** True while a script is running (paint tool forced, debug world key). */
    public static boolean active() {
        return FILE != null && !done && pc >= 0;
    }

    /** The prefix that keeps a script's markers apart from the real ones. */
    public static String worldKeyPrefix() {
        return active() ? "debug|" : "";
    }

    static void tick(Minecraft mc) {
        if (FILE == null || done || mc.player == null || mc.level == null) return;
        if (pc < 0) {
            if (mc.player.tickCount < 100) return; // let the world settle first
            try {
                lines = Files.readAllLines(Path.of(FILE));
            } catch (IOException e) {
                LOG.error("TOUR script {} unreadable: {}", FILE, e.toString());
                done = true;
                return;
            }
            pauseOnLostFocus = mc.options.pauseOnLostFocus;
            mc.options.pauseOnLostFocus = false;
            try {
                Path cfg = ConfigManager.configDir().resolve("gradient.json");
                savedConfig = Files.exists(cfg) ? Files.readString(cfg) : null;
            } catch (IOException e) {
                savedConfig = null;
            }
            pc = 0;
            // Give the marker managers a few ticks to switch to the debug world key before the
            // clear, so it empties the debug set, never the real one.
            waitTicks = 5;
            afterWait.add(() -> {
                ShapeMarkers.clearAll();
                MarkerManager.clearAll();
            });
            return;
        }
        if (waitTicks > 0) {
            if (--waitTicks == 0) {
                for (Runnable r : afterWait) r.run();
                afterWait.clear();
            }
            return;
        }
        while (pc < lines.size()) {
            String line = lines.get(pc++).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            try {
                run(mc, line);
            } catch (RuntimeException e) {
                LOG.error("TOUR script line {} '{}': {}", pc, line, e.toString());
            }
            return; // one command per tick, so each sees the last one's effect
        }
        finish(mc);
    }

    private static void finish(Minecraft mc) {
        for (KeyMapping k : keys(mc)) k.setDown(false);
        mc.setScreenAndShow(null);
        ShapeMarkers.clearAll();
        MarkerManager.clearAll();
        mc.options.pauseOnLostFocus = pauseOnLostFocus;
        // Put the config back as it was (mode / paint / set changes were only for the run).
        if (savedConfig != null) {
            try {
                Files.writeString(ConfigManager.configDir().resolve("gradient.json"), savedConfig);
                ConfigManager.load();
            } catch (IOException e) {
                LOG.error("TOUR could not restore config: {}", e.toString());
            }
        }
        done = true; // the marker managers switch back to the real world key next tick
        LOG.info("TOUR done");
    }

    /** Scene origin for {@code $x}, {@code $y}, {@code $z} (see {@link #expand}). */
    private static int ox, oy, oz;
    private static final Pattern VAR = Pattern.compile("\\$([xyz])([+-]\\d+(?:\\.\\d+)?)?");

    /** Replace {@code $x+3} etc. with the origin coordinate plus the offset. */
    private static String expand(String line) {
        Matcher m = VAR.matcher(line);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int base = switch (m.group(1)) {
                case "x" -> ox;
                case "y" -> oy;
                default -> oz;
            };
            double v = base + (m.group(2) == null ? 0 : Double.parseDouble(m.group(2)));
            m.appendReplacement(out, v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static void run(Minecraft mc, String raw) {
        String line = expand(raw);
        String[] a = line.split("\\s+");
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "wait" -> waitTicks = Integer.parseInt(a[1]);
            case "origin" -> {
                ox = Integer.parseInt(a[1]);
                oy = Integer.parseInt(a[2]);
                oz = Integer.parseInt(a[3]);
            }
            case "aim" -> {
                Vec3 p = "grip".equals(a[1])
                        ? co.fax.wang.shape.LineMarkers.debugGripPoint("T".equalsIgnoreCase(a[2]),
                                a.length > 3 ? d(a[3]) : Double.NaN)
                        : "ring".equals(a[1])
                        ? co.fax.wang.shape.LineMarkers.debugRingPoint(Integer.parseInt(a[2]), d(a[3]))
                        : ShapeMarkers.debugAimPoint(mc, a[1], a.length > 2 ? Integer.parseInt(a[2]) : 0);
                if (p == null) LOG.warn("TOUR script: nothing to aim at for '{}'", line);
                else mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, p);
            }
            case "drop" -> {
                if (!co.fax.wang.shape.LineMarkers.debugDrop()) LOG.warn("TOUR script: no line to drop");
            }
            case "cmd" -> mc.player.connection.sendCommand(line.substring(4).strip());
            case "look" -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES,
                    new Vec3(d(a[1]), d(a[2]), d(a[3])));
            case "turn" -> {
                mc.player.setYRot(mc.player.getYRot() + (float) d(a[1]));
                mc.player.setXRot(Math.max(-90, Math.min(90, mc.player.getXRot() + (float) d(a[2]))));
                mc.player.yRotO = mc.player.getYRot();
                mc.player.xRotO = mc.player.getXRot();
            }
            case "mode" -> Gradient.setPlacementMode(PlacementMode.valueOf(a[1].toUpperCase(Locale.ROOT)));
            case "paint" -> Gradient.setPaintType(PaintType.valueOf(a[1].toUpperCase(Locale.ROOT)));
            case "set" -> setField(a[1], line.substring(line.indexOf(a[1]) + a[1].length()).strip());
            case "down" -> key(mc, a[1]).setDown(true);
            case "up" -> key(mc, a[1]).setDown(false);
            case "click" -> {
                KeyMapping k = key(mc, a[1]);
                k.setDown(true);
                waitTicks = a.length > 2 ? Integer.parseInt(a[2]) : 2;
                afterWait.add(() -> k.setDown(false));
            }
            case "scroll" -> ShapeMarkers.onScroll(d(a[1]));
            case "style" -> {
                if (!ShapeMarkers.debugStyle(a[1], a[2])) LOG.warn("TOUR script: no marker style for '{}'", line);
            }
            case "clear" -> {
                ShapeMarkers.clearAll();
                MarkerManager.clearAll();
            }
            case "screen" -> DebugTour.openStop(mc, a[1]);
            case "close" -> mc.setScreenAndShow(null);
            case "clearchat" -> mc.gui.hud.getChat().clearMessages(false);
            case "snap" -> {
                LOG.info("TOUR {}", a[1]);
                waitTicks = SNAP_TICKS;
            }
            default -> LOG.warn("TOUR script: unknown command '{}'", line);
        }
    }

    private static double d(String s) {
        return Double.parseDouble(s);
    }

    private static KeyMapping key(Minecraft mc, String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "attack", "lmb" -> mc.options.keyAttack;
            case "use", "rmb" -> mc.options.keyUse;
            case "pick", "mmb" -> mc.options.keyPickItem;
            case "mod", "modifier", "ctrl" -> Gradient.modifierKey;
            case "axis", "alt" -> Gradient.axisLockKey;
            default -> throw new IllegalArgumentException("unknown key " + name);
        };
    }

    private static List<KeyMapping> keys(Minecraft mc) {
        return List.of(mc.options.keyAttack, mc.options.keyUse, mc.options.keyPickItem,
                Gradient.modifierKey, Gradient.axisLockKey);
    }

    /** Set a config field (the whole config file is put back when the script ends). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setField(String name, String value) {
        try {
            GradientConfig cfg = ConfigManager.get();
            Field f = GradientConfig.class.getField(name);
            Class<?> t = f.getType();
            Object v;
            if (t == boolean.class) v = Boolean.parseBoolean(value);
            else if (t == int.class) v = Integer.parseInt(value);
            else if (t == double.class) v = Double.parseDouble(value);
            else if (t.isEnum()) v = Enum.valueOf((Class<Enum>) t, value.toUpperCase(Locale.ROOT));
            else v = value;
            f.set(cfg, v);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("config field " + name + ": " + e);
        }
    }
}
