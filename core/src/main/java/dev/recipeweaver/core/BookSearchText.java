package dev.recipeweaver.core;

import java.util.Collection;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Searchable player-book text, including every possible result and its custom name. */
final class BookSearchText {
    private BookSearchText() { }

    static String of(NamespacedKey key, String kind, String group,
                     Collection<ItemStack> results) {
        StringBuilder text = new StringBuilder(key.toString())
                .append(' ').append(kind).append(' ').append(group);
        for (ItemStack result : results) {
            if (result == null || result.getType().isAir()) continue;
            text.append(' ').append(result.getType().name());
            ItemMeta meta = result.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                text.append(' ').append(ChatColor.stripColor(meta.getDisplayName()));
            }
            if (meta != null && meta.hasItemName()) {
                text.append(' ').append(meta.getItemName());
            }
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }
}
