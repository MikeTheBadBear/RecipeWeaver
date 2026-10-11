package dev.recipeweaver.api;

import java.util.Objects;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

/** One committed custom-grid batch and its selected result. */
public record GridCraftBatch(NamespacedKey recipe, ItemStack result) {
    public GridCraftBatch {
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(result, "result");
        result = result.clone();
    }

    @Override public ItemStack result() { return result.clone(); }
}
