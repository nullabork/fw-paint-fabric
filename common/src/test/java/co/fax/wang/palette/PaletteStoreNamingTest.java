package co.fax.wang.palette;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaletteStoreNamingTest {

    @Test
    void emptyNameBecomesUntitledWithIncrements() {
        assertEquals("Untitled", PaletteStore.uniqueName("", List.of()));
        assertEquals("Untitled (2)", PaletteStore.uniqueName("", List.of("Untitled")));
        assertEquals("Untitled (3)",
                PaletteStore.uniqueName(null, List.of("Untitled", "Untitled (2)")));
    }

    @Test
    void uniqueNamesPassThroughAndCollisionsSuffix() {
        assertEquals("Beach", PaletteStore.uniqueName("Beach", List.of("Sunset")));
        assertEquals("Beach (2)", PaletteStore.uniqueName("Beach", List.of("Beach")));
        assertEquals("Beach (2)", PaletteStore.uniqueName("  Beach  ", List.of("beach")));
    }

    @Test
    void nameTakenIsCaseInsensitive() {
        assertTrue(PaletteStore.nameTaken("beach", List.of("Beach")));
        assertTrue(PaletteStore.nameTaken(" Beach ", List.of("beach")));
        assertFalse(PaletteStore.nameTaken("Beach", List.of("Sunset")));
    }

    @Test
    void slugsAreCleanAndCollisionSafe() {
        assertEquals("sunset-fade", PaletteStore.slugFor("Sunset fade", List.of()));
        assertEquals("sunset-fade-2", PaletteStore.slugFor("Sunset  Fade!", List.of("sunset-fade")));
        assertEquals("palette", PaletteStore.slugFor("†††", List.of()));
        assertEquals("palette-2", PaletteStore.slugFor("", List.of("palette")));
    }
}
