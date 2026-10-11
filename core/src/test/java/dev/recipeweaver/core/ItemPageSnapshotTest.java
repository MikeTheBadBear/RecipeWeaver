package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

final class ItemPageSnapshotTest {
    @Test
    void savedItemClickKeepsTheDisplayedIdWhenTheRepositoryOrderChanges() {
        NamespacedKey displayed = NamespacedKey.fromString("recipeweaver:blue_gem");
        NamespacedKey newlyInserted = NamespacedKey.fromString("recipeweaver:amber_gem");
        Map<Integer, NamespacedKey> paintedSlots = new HashMap<>();
        paintedSlots.put(0, displayed);
        ItemPageSnapshot page = new ItemPageSnapshot(paintedSlots);

        paintedSlots.put(0, newlyInserted);
        assertEquals(displayed, page.keyAt(0));
        assertNull(page.keyAt(1));
        assertNull(page.keyAt(49));
    }
}
