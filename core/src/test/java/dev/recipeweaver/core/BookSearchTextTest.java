package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

final class BookSearchTextTest {
    @Test
    void customResultNameCanBeFoundWithoutChangingTheRecipeIdOrMaterial() {
        ItemStack result = BookItemMetaFixture.item(Material.DIAMOND_SWORD,
                ChatColor.AQUA + "Frost Blade", null);

        String text = BookSearchText.of(new NamespacedKey("recipeweaver", "winter_weapon"),
                "SHAPED", "Weapons", List.of(result));
        assertTrue(text.contains("frost blade"));
        assertTrue(text.contains("diamond_sword"));
        assertFalse(text.contains("§"));
    }

    @Test
    void alternateWeightedResultIsSearchableEvenWhenPrimaryHasAnotherMaterial() {
        String text = BookSearchText.of(new NamespacedKey("recipeweaver", "ore_lottery"),
                "FURNACE", "Ores", List.of(
                        BookItemMetaFixture.item(Material.IRON_INGOT, null, null),
                        BookItemMetaFixture.item(Material.GOLD_INGOT, null, null)));
        assertTrue(text.contains("iron_ingot"));
        assertTrue(text.contains("gold_ingot"));
    }

    @Test
    void itemNameComponentCanBeFoundWithoutAStyledDisplayName() {
        ItemStack result = BookItemMetaFixture.item(Material.PAPER, null, "Aurora Pattern");

        String text = BookSearchText.of(new NamespacedKey("recipeweaver", "pattern"),
                "SHAPELESS", "Designs", List.of(result));
        assertTrue(text.contains("aurora pattern"));
    }
}
