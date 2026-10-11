package dev.recipeweaver.core;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.StonecuttingRecipe;

/** Identifies a completed native station recipe only when its key is unambiguous. */
final class DiscoveryMatches {
    private DiscoveryMatches() { }

    static NamespacedKey stonecutting(ItemStack input, ItemStack result,
                                      Collection<? extends Recipe> candidates) {
        if (!present(input) || !present(result)) return null;
        NamespacedKey found = null;
        for (Recipe candidate : candidates) {
            if (!(candidate instanceof StonecuttingRecipe recipe)
                    || !sameResult(recipe.getResult(), result)
                    || !recipe.getInputChoice().test(input)) continue;
            if (found != null && !found.equals(recipe.getKey())) return null;
            found = recipe.getKey();
        }
        return found;
    }

    /** Modern Bukkit exposes the selected index and its ordered recipe list.
     * Older servers fall back to the unique-match check above. */
    static NamespacedKey stonecutting(Object view, ItemStack input, ItemStack result,
                                      Collection<? extends Recipe> candidates) {
        try {
            Class<?> stonecutterView = Class.forName("org.bukkit.inventory.view.StonecutterView");
            if (!stonecutterView.isInstance(view)) return stonecutting(input, result, candidates);
            Object index = stonecutterView.getMethod("getSelectedRecipeIndex").invoke(view);
            Object recipes = stonecutterView.getMethod("getRecipes").invoke(view);
            if (!(index instanceof Integer selected) || !(recipes instanceof List<?> available)) {
                return null;
            }
            return selectedStonecutting(selected, available, input, result);
        } catch (ClassNotFoundException unsupported) {
            return stonecutting(input, result, candidates);
        } catch (ReflectiveOperationException | LinkageError unsupported) {
            return null;
        }
    }

    static NamespacedKey selectedStonecutting(int index, List<?> available,
                                               ItemStack input, ItemStack result) {
        if (index < 0 || index >= available.size() || !present(input) || !present(result)
                || !(available.get(index) instanceof StonecuttingRecipe recipe)
                || !sameResult(recipe.getResult(), result)
                || !recipe.getInputChoice().test(input)) return null;
        return recipe.getKey();
    }

    /** The selected key remains meaningful when another plugin changes the presented result. */
    static StonecuttingRecipe selectedStonecuttingRecipe(Object view) {
        try {
            Class<?> stonecutterView = Class.forName("org.bukkit.inventory.view.StonecutterView");
            if (!stonecutterView.isInstance(view)) return null;
            Object index = stonecutterView.getMethod("getSelectedRecipeIndex").invoke(view);
            Object recipes = stonecutterView.getMethod("getRecipes").invoke(view);
            if (!(index instanceof Integer selected) || !(recipes instanceof List<?> available)
                    || selected < 0 || selected >= available.size()
                    || !(available.get(selected) instanceof StonecuttingRecipe recipe)) return null;
            return recipe;
        } catch (ReflectiveOperationException | LinkageError unsupported) {
            return null;
        }
    }

    /** FurnaceExtractEvent carries no key while the used-recipe experience batch
     * is drained. Retain every positive-use recipe for its material instead of
     * discarding equal-output keys. */
    static Set<NamespacedKey> furnaceExtractionKeys(
            Map<? extends CookingRecipe<?>, Integer> used, Material extractedType) {
        if (extractedType == null || extractedType.isAir()) return Set.of();
        Set<NamespacedKey> matching = new LinkedHashSet<>();
        for (Map.Entry<? extends CookingRecipe<?>, Integer> entry : used.entrySet()) {
            Integer count = entry.getValue();
            if (count != null && count > 0
                    && entry.getKey().getResult().getType() == extractedType) {
                matching.add(entry.getKey().getKey());
            }
        }
        return Set.copyOf(matching);
    }

    /** A result click exposes item metadata even when FurnaceExtractEvent only exposes its material. */
    static NamespacedKey furnace(Map<? extends CookingRecipe<?>, Integer> used,
                                 ItemStack extracted) {
        if (!present(extracted)) return null;
        return furnace(used, recipe -> recipe.getResult().isSimilar(extracted));
    }

    private static NamespacedKey furnace(Map<? extends CookingRecipe<?>, Integer> used,
                                         Predicate<CookingRecipe<?>> matches) {
        NamespacedKey found = null;
        for (Map.Entry<? extends CookingRecipe<?>, Integer> entry : used.entrySet()) {
            CookingRecipe<?> recipe = entry.getKey();
            Integer count = entry.getValue();
            if (count == null || count < 1 || !matches.test(recipe)) continue;
            if (found != null && !found.equals(recipe.getKey())) return null;
            found = recipe.getKey();
        }
        return found;
    }

    static NamespacedKey keyed(Recipe recipe) {
        return recipe instanceof Keyed keyed ? keyed.getKey() : null;
    }

    private static boolean sameResult(ItemStack left, ItemStack right) {
        return present(left) && present(right) && left.getAmount() == right.getAmount()
                && left.isSimilar(right);
    }

    private static boolean present(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }
}
