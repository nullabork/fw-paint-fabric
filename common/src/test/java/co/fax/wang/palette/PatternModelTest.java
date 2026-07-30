package co.fax.wang.palette;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternModelTest {

    private static Palette pattern() {
        Palette p = new Palette();
        p.id = "checker";
        p.name = "Checker";
        p.kind = PaletteKind.PATTERN;
        p.width = 2;
        p.height = 2;
        p.cells = List.of("minecraft:bricks", "", "", "minecraft:stone");
        p.tiling = PatternTiling.START_END_SIDES;
        p.variationWindow = 2;
        return p;
    }

    @Test
    void legacyJsonWithoutKindDefaultsToGradient() {
        Palette back = new Gson().fromJson("{\"id\":\"old\",\"name\":\"Old\"}", Palette.class);
        assertEquals(PaletteKind.GRADIENT, back.kind);
    }

    @Test
    void patternRoundTripsThroughGson() {
        Gson gson = new Gson();
        Palette back = gson.fromJson(gson.toJson(pattern()), Palette.class);
        assertEquals(PaletteKind.PATTERN, back.kind);
        assertEquals(2, back.width);
        assertEquals("minecraft:bricks", back.cellAt(0, 0));
        assertEquals("", back.cellAt(1, 0)); // hole survives
        assertEquals(PatternTiling.START_END_SIDES, back.tiling);
        assertEquals(2, back.variationWindow);
        assertEquals(pattern().contentKey(), back.contentKey());
    }

    @Test
    void contentKeyChangesWithPatternFields() {
        Palette a = pattern();
        Palette b = pattern();
        assertEquals(a.contentKey(), b.contentKey());
        b.variationWindow = 0;
        assertNotEquals(a.contentKey(), b.contentKey());
        b = pattern();
        b.cells = List.of("minecraft:bricks", "", "", "minecraft:bricks");
        assertNotEquals(a.contentKey(), b.contentKey());
    }

    @Test
    void missingBlocksReadsCellsForPatterns() {
        Palette p = pattern();
        assertEquals(List.of("minecraft:stone"), p.missingBlocks(Set.of("minecraft:bricks")));
        assertTrue(p.missingBlocks(Set.of("minecraft:bricks", "minecraft:stone")).isEmpty());
    }

    @Test
    void cellAtClampsOutOfRangeToHole() {
        Palette p = pattern();
        assertEquals("", p.cellAt(-1, 0));
        assertEquals("", p.cellAt(0, 2));
        assertEquals("minecraft:stone", p.cellAt(1, 1));
    }

    @Test
    void tilingWrapFlags() {
        assertTrue(PatternTiling.SIDES.wrapsSides());
        assertTrue(PatternTiling.START_END.wrapsStartEnd());
        assertTrue(PatternTiling.START_END_SIDES.wrapsSides()
                && PatternTiling.START_END_SIDES.wrapsStartEnd());
        assertEquals(false, PatternTiling.NONE.wrapsSides());
    }
}
