package dev.recipeweaver.core;

import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;

/** Ingredient choices used by RecipeWeaver-controlled station transactions. */
final class StationIngredientMatcher {
    private StationIngredientMatcher() { }

    static boolean matches(StationRecipeDefinition recipe, int slot, ItemStack actual) {
        if (!recipe.hasIngredient(slot)) return !present(actual);
        if (!present(actual)) return recipe.optional(slot);
        if (actual.getAmount() < recipe.ingredients().get(slot).getAmount()) return false;
        if (recipe.matchesItemSample(slot, actual)) return true;
        NamespacedKey tagKey = recipe.itemTags().get(slot);
        if (tagKey == null) return false;
        Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, tagKey, Material.class);
        return tag != null && tag.isTagged(actual.getType());
    }

    static void validateTags(StationRecipeDefinition recipe) {
        for (int slot = 0; slot < recipe.kind().slots(); slot++) {
            NamespacedKey key = recipe.itemTags().get(slot);
            if (key == null) continue;
            Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
            if (tag == null || tag.getValues().isEmpty()) {
                throw new IllegalArgumentException("Unknown or empty item tag: " + key);
            }
            if (recipe.kind() == StationKind.BREWING && slot == 1
                    && tag.getValues().stream().anyMatch(value -> !StationRecipeDefinition.bottleMaterial(value))) {
                throw new IllegalArgumentException("Brewing bottle tag contains items outside the bottle slot: " + key);
            }
        }
    }

    private static boolean present(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }
}
