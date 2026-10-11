package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

final class BookResultSortKeyTest {
    @Test
    void resultNameSortUsesVisibleNamesBeforeMaterialNames() {
        ItemStack laterByName = BookItemMetaFixture.item(Material.DIAMOND,
                ChatColor.GOLD + "Zircon charm", null);
        ItemStack earlierByName = BookItemMetaFixture.item(Material.EMERALD,
                ChatColor.GREEN + "Amber charm", null);
        assertTrue(BookResultSortKey.of(earlierByName).compareToIgnoreCase(
                BookResultSortKey.of(laterByName)) < 0);
        assertEquals("Amber charm", BookResultSortKey.of(earlierByName));
        assertEquals("IRON INGOT", BookResultSortKey.of(BookItemMetaFixture.item(
                Material.IRON_INGOT, null, null)));
    }

    @Test
    void resultSortUsesItemNameWhenDisplayNameIsAbsent() {
        ItemStack item = BookItemMetaFixture.item(Material.PAPER, null, "Aurora Pattern");
        assertEquals("Aurora Pattern", BookResultSortKey.of(item));
    }
}
