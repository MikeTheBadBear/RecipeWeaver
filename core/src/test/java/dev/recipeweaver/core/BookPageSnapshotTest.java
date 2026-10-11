package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

final class BookPageSnapshotTest {
    @Test
    void selectionKeepsTheRenderedRecipeWhenLiveOrderingChanges() {
        NamespacedKey first = key("first");
        NamespacedKey second = key("second");
        NamespacedKey newlyDiscovered = key("newly_discovered");
        List<NamespacedKey> visibleWhenOpened = new ArrayList<>(List.of(first, second));
        BookPageSnapshot page = new BookPageSnapshot(visibleWhenOpened);

        visibleWhenOpened.add(0, newlyDiscovered);
        assertEquals(second, page.keyAt(1));
        assertEquals(newlyDiscovered, visibleWhenOpened.get(0));
        assertNull(page.keyAt(2));
        assertNull(page.keyAt(-1));
    }

    private static NamespacedKey key(String name) {
        return new NamespacedKey("recipeweaver", name);
    }
}
