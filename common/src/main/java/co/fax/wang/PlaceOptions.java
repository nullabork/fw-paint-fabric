package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The Place options (Don't match palette, Fill voids first, Place in air, Perp snap) and the mode
 * descriptions, shared by the Place tab, the wheel's Place ring and the HUD so all three agree on
 * which option applies to which mode and what each mode is called.
 *
 * <p>Which options apply follows {@link PaintPlacer}: Don't match palette filters the Face modes'
 * selection (Face any, Face target, Face perp); Fill voids first levels in-marker Face any/target
 * fills; Place in air works in every placing mode; Perp snap sets the Face perp run direction and
 * the plane of any Pattern placement.
 */
public final class PlaceOptions {

    private PlaceOptions() {}

    public static boolean dontMatchApplies(PlacementMode m) {
        return m.isFace() || m == PlacementMode.FACE_PERP;
    }

    public static boolean fillVoidsApplies(PlacementMode m) {
        return m.isFace();
    }

    public static boolean placeInAirApplies(PlacementMode m) {
        return m.places();
    }

    public static boolean perpSnapApplies(PlacementMode m, PaintType type) {
        return m == PlacementMode.FACE_PERP || (m.places() && type == PaintType.PATTERN);
    }

    // ---- toggles (wheel + Place tab) ----------------------------------------------------------------

    public static void toggleFillVoids() {
        GradientConfig c = ConfigManager.get();
        c.faceFillVoids = !c.faceFillVoids;
        ConfigManager.save();
        flash("Fill voids first " + (c.faceFillVoids ? "on" : "off"));
    }

    public static void togglePlaceInAir() {
        GradientConfig c = ConfigManager.get();
        c.placeInAir = !c.placeInAir;
        ConfigManager.save();
        flash("Place in air " + (c.placeInAir ? "on" : "off"));
    }

    public static void togglePerpSnap() {
        GradientConfig c = ConfigManager.get();
        c.perpSnapDegrees = c.perpSnapDegrees == 45 ? 90 : 45;
        ConfigManager.save();
        flash("Perp snap " + c.perpSnapDegrees + "°");
    }

    private static void flash(String what) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal(Gradient.message("Place", what, null)));
    }

    /**
     * The HUD's yellow lines: options that apply to {@code mode} and are switched away from their
     * default, or session-only and on. Empty when everything is as it ships.
     */
    public static List<String> attentionLines(GradientConfig cfg, PlacementMode mode) {
        List<String> out = new ArrayList<>();
        if (Gradient.dontMatchPalette && dontMatchApplies(mode)) out.add("● Don't match palette");
        if (!cfg.faceFillVoids && fillVoidsApplies(mode)) out.add("Fill voids first: off");
        if (!cfg.placeInAir && placeInAirApplies(mode)) out.add("Place in air: off");
        if (cfg.perpSnapDegrees != 45 && perpSnapApplies(mode, cfg.activePaintType)) {
            out.add("Perp snap " + cfg.perpSnapDegrees + "°");
        }
        return out;
    }

    // ---- mode descriptions --------------------------------------------------------------------------
    // Keyed by enum name (not constant) so shape modes renamed or merged elsewhere still compile;
    // an unknown name just has no description.

    /** A few words for a Place tab tile. */
    public static String shortDescription(PlacementMode m) {
        return switch (m.name()) {
            case "SINGLE" -> "One column";
            case "FACE" -> "Whole surface";
            case "FACE_TARGET" -> "Same block only";
            case "FACE_PERP" -> "Run across face";
            case "FILL3D" -> "Blob round click";
            case "MARKER" -> "Start/end blocks";
            case "MARKER_DRAW" -> "Drag a run";
            case "DISABLED" -> "Tool does nothing";
            case "MARKER_BOX" -> "Two corners";
            case "MARKER_RING" -> "Round/square band";
            case "MARKER_LINE" -> "Nodes, tubes";
            default -> "";
        };
    }

    /** One sentence: the wheel's hover text and the Place tab's hover hint. */
    public static String description(PlacementMode m) {
        return switch (m.name()) {
            case "SINGLE" -> "One column out of the face you click.";
            case "FACE" -> "The whole connected surface grows at once.";
            case "FACE_TARGET" -> "Like Face any, but only the block type you click.";
            case "FACE_PERP" -> "A one-block-wide run across the face, along your view.";
            case "FILL3D" -> "A blob growing out around the clicked block.";
            case "MARKER" -> "Click blocks to mark starts (turquoise) and ends (amber).";
            case "MARKER_DRAW" -> "A freehand marker pencil: drag a run of start markers.";
            case "DISABLED" -> "The paint tool does nothing; clicks work as normal.";
            case "MARKER_BOX" -> "Two corners mark a whole box region.";
            case "MARKER_RING" -> "A round or square donut region with a wall thickness.";
            case "MARKER_LINE" -> "A curve you bend; give it a shape to make a tube.";
            default -> "";
        };
    }
}
