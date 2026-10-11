package dev.recipeweaver.core;

import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** The result label players see when sorting the custom recipe book. */
final class BookResultSortKey {
    private BookResultSortKey() { }

    static String of(ItemStack result) {
        ItemMeta meta = result.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String name = ChatColor.stripColor(meta.getDisplayName());
            if (name != null && !name.isBlank()) return name.strip();
        }
        if (meta != null && meta.hasItemName() && !meta.getItemName().isBlank()) {
            return meta.getItemName().strip();
        }
        return result.getType().name().replace('_', ' ');
    }
}
