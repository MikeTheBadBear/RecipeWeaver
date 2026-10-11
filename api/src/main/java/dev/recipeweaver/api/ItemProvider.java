package dev.recipeweaver.api;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

/** Resolves one external item ID on the server thread. Return null for an unknown item. */
@FunctionalInterface
public interface ItemProvider {
    ItemStack resolve(NamespacedKey key);
}
