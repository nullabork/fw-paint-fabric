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
    void retiredCircleAndSquareLoadAsRing() {
        for (String old : new String[]{"MARKER_CIRCLE", "MARKER_SQUARE"}) {
            GradientConfig cfg = GSON.fromJson("{\"placementMode\":\"" + old + "\"}", GradientConfig.class);
            assertEquals(PlacementMode.MARKER_RING, cfg.placementMode, old);
        }
    }

    @Test
    void ringSavesUnderItsOwnName() {
        GradientConfig cfg = new GradientConfig();
        cfg.placementMode = PlacementMode.MARKER_RING;
        String json = GSON.toJson(cfg);
        assertTrue(json.contains("\"placementMode\":\"MARKER_RING\""), json);
        assertEquals(PlacementMode.MARKER_RING, GSON.fromJson(json, GradientConfig.class).placementMode);
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
        assertEquals(java.util.List.of(PlacementMode.MARKER_BOX, PlacementMode.MARKER_RING,
                PlacementMode.MARKER_LINE), PlacementMode.groups().get(2).modes());
    }
}
