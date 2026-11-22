package co.fax.wang;

import co.fax.wang.config.GradientConfig;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlacementModeTest {

    private static final Gson GSON = new Gson();

    @Test
    void retiredMarkerModesLoadAsShape() {
        for (String old : new String[]{"MARKER_CIRCLE", "MARKER_SQUARE", "MARKER_RING", "MARKER_BOX", "MARKER", "MARKER_DRAW"}) {
            GradientConfig cfg = GSON.fromJson("{\"placementMode\":\"" + old + "\"}", GradientConfig.class);
            assertEquals(PlacementMode.MARKER_SHAPE, cfg.placementMode, old);
        }
    }

    @Test
    void shapeSavesUnderItsOwnName() {
        GradientConfig cfg = new GradientConfig();
        cfg.placementMode = PlacementMode.MARKER_SHAPE;
        String json = GSON.toJson(cfg);
        assertTrue(json.contains("\"placementMode\":\"MARKER_SHAPE\""), json);
        assertEquals(PlacementMode.MARKER_SHAPE, GSON.fromJson(json, GradientConfig.class).placementMode);
    }

    @Test
    void everyModeRoundTrips() {
        for (PlacementMode m : PlacementMode.values()) {
            GradientConfig cfg = new GradientConfig();
            cfg.placementMode = m;
            assertEquals(m, GSON.fromJson(GSON.toJson(cfg), GradientConfig.class).placementMode);
        }
    }

    @Test
    void groupsListEveryModeOnce() {
        Set<PlacementMode> seen = new HashSet<>();
        for (PlacementMode.Group g : PlacementMode.groups()) {
            for (PlacementMode m : g.modes()) assertTrue(seen.add(m), "twice: " + m);
        }
        assertEquals(Set.of(PlacementMode.values()), seen);
        assertEquals(java.util.List.of(PlacementMode.MARKER_SHAPE, PlacementMode.MARKER_LINE,
                PlacementMode.DISABLED), PlacementMode.groups().get(1).modes());
    }

    @Test
    void rememberedPicksOfRetiredToolsBecomeShape() {
        for (String old : new String[]{"MARKER_BOX", "MARKER_RING", "MARKER", "MARKER_DRAW"}) {
            assertEquals(PlacementMode.MARKER_SHAPE, PlacementMode.byName(old), old);
        }
        assertEquals(PlacementMode.FILL3D, PlacementMode.byName("FILL3D"));
        assertEquals(PlacementMode.DISABLED, PlacementMode.byName("NOPE"));
        assertEquals(PlacementMode.DISABLED, PlacementMode.byName(null));
    }
}
