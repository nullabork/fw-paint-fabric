package co.fax.wang.palette;

import co.fax.wang.CurveFunction;
import co.fax.wang.NoiseType;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaletteTest {

    private static Palette sample() {
        Palette p = new Palette();
        p.id = "sunset-fade";
        p.name = "Sunset fade";
        p.segments.add(PaletteSegment.ofBlock("minecraft:red_sandstone"));
        p.segments.add(PaletteSegment.ofAuto(AutoMode.COLOR));
        p.segments.add(PaletteSegment.ofBlock("minecraft:sandstone"));
        p.stops = List.of(0.25, 0.6);
        p.curve = CurveFunction.EASE_IN;
        p.autoExclude.add("minecraft:granite");
        p.noiseType = NoiseType.PERLIN;
        return p;
    }

    @Test
    void copyIsDeepAndEqualInContent() {
        Palette p = sample();
        Palette c = p.copy();
        assertEquals(p.contentKey(), c.contentKey());
        c.segments.get(0).block = "minecraft:stone";
        c.autoExclude.add("minecraft:diorite");
        assertNotEquals(p.contentKey(), c.contentKey());
        assertEquals("minecraft:red_sandstone", p.segments.get(0).block); // original untouched
        assertEquals(1, p.autoExclude.size());
    }

    @Test
    void contentKeyChangesWithAnyPlacementAffectingField() {
        Palette a = sample();
        Palette b = sample();
        assertEquals(a.contentKey(), b.contentKey());
        b.variationWindow = 2;
        b.variationChance = 50;
        assertNotEquals(a.contentKey(), b.contentKey());
        // ...but the CACHE key ignores variation, so tweaks don't restart in-progress paint.
        assertEquals(a.cacheKey(), b.cacheKey());
        b = sample();
        b.sizing = SizingMode.SET_STEPS;
        assertNotEquals(a.contentKey(), b.contentKey());
        b = sample();
        b.segments.add(PaletteSegment.ofAuto(AutoMode.BRIGHTNESS));
        assertNotEquals(a.contentKey(), b.contentKey());
    }

    @Test
    void missingBlocksIgnoresAutomaticAndDeduplicates() {
        Palette p = sample();
        p.segments.add(PaletteSegment.ofBlock("minecraft:red_sandstone")); // duplicate
        List<String> missing = p.missingBlocks(Set.of("minecraft:sandstone"));
        assertEquals(List.of("minecraft:red_sandstone"), missing);
        assertTrue(p.missingBlocks(
                Set.of("minecraft:sandstone", "minecraft:red_sandstone")).isEmpty());
    }

    @Test
    void automaticFlags() {
        Palette p = sample();
        assertTrue(p.hasAutomatic());
        assertFalse(p.allAutomatic());
        Palette allAuto = new Palette();
        allAuto.segments.add(PaletteSegment.ofAuto(AutoMode.BRIGHTNESS));
        assertTrue(allAuto.allAutomatic());
        assertTrue(new Palette().allAutomatic()); // empty strip counts as all-automatic
    }

    @Test
    void gsonRoundTripPreservesEverything() {
        Gson gson = new Gson();
        Palette p = sample();
        Palette back = gson.fromJson(gson.toJson(p), Palette.class);
        assertEquals(p.contentKey(), back.contentKey());
        assertEquals("Sunset fade", back.name);
        assertTrue(back.segments.get(1).isAutomatic());
        assertEquals(AutoMode.COLOR, back.segments.get(1).auto);
        assertNull(back.segments.get(0).auto);
    }
}
