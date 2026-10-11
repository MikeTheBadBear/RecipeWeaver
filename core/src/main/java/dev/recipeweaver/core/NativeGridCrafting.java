package dev.recipeweaver.core;

import dev.recipeweaver.api.RecipeDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemCraftResult;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

/** Applies native workbench guards and preserves all returns in the advanced table. */
final class NativeGridCrafting {
    private NativeGridCrafting() { }

    static boolean allows(Recipe nativeRecipe, RecipeDefinition saved, boolean lockedDown,
                          Set<NamespacedKey> disabled, ItemStack[] matrix, ItemStack result,
                          Player player, World world) {
        if (nativeRecipe == null) return false;
        if (!(nativeRecipe instanceof Keyed keyed)) return true;
        NamespacedKey key = keyed.getKey();
        if (disabled.contains(key)) return false;
        if (!key.getNamespace().equals("recipeweaver")) return true;
        return saved != null && saved.key().equals(key)
                && NativeCraftGuard.allowsRecipe(saved, lockedDown, matrix, result, player, world);
    }

    static GridMatcher.CraftEffect effect(ItemCraftResult craft) {
        ItemStack[] matrix = craft.getResultingMatrix();
        if (matrix.length != 9) throw new IllegalStateException("Invalid native craft matrix");
        List<ItemStack> inputs = new ArrayList<>(9);
        for (ItemStack item : matrix) inputs.add(item == null ? null : item.clone());
        List<ItemStack> returned = new ArrayList<>();
        for (ItemStack item : craft.getOverflowItems()) {
            if (GridMatcher.present(item)) returned.add(item.clone());
        }
        return new GridMatcher.CraftEffect(inputs, List.copyOf(returned));
    }
}
