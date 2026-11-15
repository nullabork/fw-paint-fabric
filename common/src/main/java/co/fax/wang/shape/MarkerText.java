package co.fax.wang.shape;

import co.fax.wang.Gradient;
import co.fax.wang.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Action-bar text for markers, so every kind speaks the same way:
 * <ul>
 *   <li>creation steps: {@code FW Paint · Box · step 2 of 2 — LMB the opposite corner · RMB cancels},
 *       then {@code FW Paint · Box set} / {@code … cancelled} / {@code … removed};</li>
 *   <li>aim hints: aiming at a control names its keys, e.g.
 *       {@code Radius · drag resize · RMB +1 layer · MMB delete} — sent once per new aim.</li>
 * </ul>
 * Key names are the live bindings (the modifier reads "Ctrl" when it's either Control key).
 */
public final class MarkerText {

    private MarkerText() {}

    /** A message stays up this long before an aim hint may replace it. */
    private static final long MESSAGE_MS = 1500;

    /**
     * A hint still being aimed at is sent again this often. The action bar fades a message out
     * after a couple of seconds whatever it says, so without this the hint would fade while the
     * crosshair is still on the control; it should only go once you look away.
     */
    private static final long REFRESH_MS = 1000;

    private static String lastHint;
    private static long lastSayMs, lastHintMs;

    /** The modifier key's live name, shortened for the common ones ("Ctrl", "Shift", "Alt"). */
    static String mod() {
        return shorten(Gradient.boundKey("modifier"));
    }

    /** The axis-lock key's live name, shortened. */
    static String axis() {
        return shorten(Gradient.boundKey("axis"));
    }

    static String shorten(String key) {
        return switch (key) {
            case "Left Control", "Right Control" -> "Ctrl";
            case "Left Shift", "Right Shift" -> "Shift";
            case "Left Alt", "Right Alt" -> "Alt";
            default -> key;
        };
    }

    /** {@code FW Paint · <msg>} on the action bar (aim hints hold off for a moment after it). */
    public static void say(String msg) {
        lastSayMs = System.currentTimeMillis();
        raw("FW Paint · " + msg);
    }

    /** Creation step: {@code FW Paint · <kind> · step n of N — <what> · RMB cancels}. */
    static void step(String kind, int n, int of, String what) {
        say(kind + " · step " + n + " of " + of + " — " + what + " · RMB cancels");
    }

    /**
     * An aim hint, sent when it differs from the last one and then about once a second for as
     * long as the same thing stays aimed (so it holds steady instead of fading, without flooding
     * the action bar every tick), and not over a message that has only just gone up (it follows
     * once that has been read). {@code null} = nothing aimed: the hint is left to fade, and the
     * next one shows again even if the same.
     */
    static void hint(String text) {
        if (text == null) {
            lastHint = null;
            return;
        }
        long now = System.currentTimeMillis();
        if (text.equals(lastHint) && now - lastHintMs < REFRESH_MS) return;
        if (now - lastSayMs < MESSAGE_MS) return;
        lastHint = text;
        lastHintMs = now;
        raw(text);
    }

    /** Forget the last hint (a message just replaced it, so re-aiming should show it again). */
    static void resetHint() {
        lastHint = null;
    }

    private static void raw(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal(msg));
    }

    /** How far out a point aimed at nothing lands: the Marker reach setting. */
    static double airReach() {
        return Math.max(4, ConfigManager.get().maxMarkerDistance);
    }
}
