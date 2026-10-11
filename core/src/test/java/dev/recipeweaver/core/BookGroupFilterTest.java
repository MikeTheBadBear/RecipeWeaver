package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class BookGroupFilterTest {
    @Test
    void cyclesAllUngroupedAndNamedGroupsWithoutDuplicatingCaseVariants() {
        List<String> available = BookGroupFilter.available(
                List.of("Tools", "", "food", "FOOD", "tools"));
        assertEquals(List.of("", "food", "Tools"), available);
        String group = null;
        group = BookGroupFilter.next(group, available);
        assertEquals("", group);
        group = BookGroupFilter.next(group, available);
        assertEquals("food", group);
        group = BookGroupFilter.next(group, available);
        assertEquals("Tools", group);
        assertNull(BookGroupFilter.next(group, available));
        assertTrue(BookGroupFilter.accepts("FOOD", "food"));
        assertFalse(BookGroupFilter.accepts("FOOD", "Tools"));
        assertTrue(BookGroupFilter.accepts(null, "Tools"));
    }

    @Test
    void removedGroupResetsToAllBeforeCyclingAvailableGroups() {
        List<String> available = BookGroupFilter.available(List.of("building", "magic"));
        assertNull(BookGroupFilter.selected("removed", available));
        assertEquals("building", BookGroupFilter.next("removed", available));
        assertNull(BookGroupFilter.next("anything", List.of()));
    }

    @Test
    void surroundingWhitespaceDoesNotCreatePhantomGroups() {
        List<String> available = BookGroupFilter.available(
                java.util.Arrays.asList(" Tools ", "tools", " ", null));
        assertEquals(List.of("", "Tools"), available);
        assertEquals("Tools", BookGroupFilter.selected(" tools ", available));
        assertTrue(BookGroupFilter.accepts("Tools", " tools "));
        assertTrue(BookGroupFilter.accepts("", " "));
        assertFalse(BookGroupFilter.accepts("Tools", " "));
    }

    @Test
    void internalWhitespaceVariantsShareOneBookGroup() {
        List<String> available = BookGroupFilter.available(
                List.of("Magic  Tools", "Magic\tTools", "Magic\nTools"));
        assertEquals(List.of("Magic Tools"), available);
        assertEquals("Magic Tools", BookGroupFilter.selected("magic   tools", available));
        assertTrue(BookGroupFilter.accepts("Magic Tools", "Magic\tTools"));
        assertFalse(BookGroupFilter.accepts("Magic Tools", "Magic Potions"));
    }

    @Test
    void groupSortIgnoresLegacyWhitespaceAroundSavedNames() {
        List<String> groups = new java.util.ArrayList<>(List.of(
                "  Tools", "Food", "Magic  Tools", "Magic Tools"));
        groups.sort(java.util.Comparator.comparing(BookGroupFilter::sortKey,
                String.CASE_INSENSITIVE_ORDER));
        assertEquals(List.of("Food", "Magic  Tools", "Magic Tools", "  Tools"), groups);
    }
}
