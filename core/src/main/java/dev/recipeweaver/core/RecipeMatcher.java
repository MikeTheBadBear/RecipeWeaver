package dev.recipeweaver.core;

import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.RecipeKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;

/** Non-consuming preview of Bukkit workbench choices and translated shaped patterns. */
final class RecipeMatcher {
    private RecipeMatcher() { }

    static boolean matches(RecipeKind kind, List<ItemStack> recipe, List<ItemStack> grid) {
        return matches(kind, recipe, java.util.Collections.nCopies(9, IngredientMatchMode.EXACT),
                java.util.Collections.nCopies(9, List.of()),
                java.util.Collections.nCopies(9, null), grid);
    }

    static boolean matches(RecipeDefinition recipe, List<ItemStack> grid) {
        return matches(recipe.kind(), recipe.ingredients(), recipe.matchModes(),
                recipe.alternatives(), recipe.itemTags(), grid);
    }

    static boolean matches(RecipeKind kind, List<ItemStack> recipe,
                           List<IngredientMatchMode> modes, List<List<ItemStack>> alternatives,
                           List<NamespacedKey> tags, List<ItemStack> grid) {
        return matches(kind, recipe, modes, alternatives, tags, grid, RecipeMatcher::resolveTag);
    }

    static boolean matches(RecipeKind kind, List<ItemStack> recipe,
                           List<IngredientMatchMode> modes, List<List<ItemStack>> alternatives,
                           List<NamespacedKey> tags, List<ItemStack> grid,
                           Function<NamespacedKey, Set<Material>> tagResolver) {
        if (recipe.size() != 9 || modes.size() != 9 || alternatives.size() != 9
                || tags.size() != 9 || grid.size() != 9) {
            throw new IllegalArgumentException("A workbench grid has nine slots");
        }
        Map<NamespacedKey, Set<Material>> resolvedTags = new HashMap<>();
        return kind == RecipeKind.SHAPELESS
                ? shapeless(recipe, modes, alternatives, tags, grid, tagResolver, resolvedTags)
                : shaped(recipe, modes, alternatives, tags, grid, tagResolver, resolvedTags);
    }

    private static boolean shapeless(List<ItemStack> recipe, List<IngredientMatchMode> modes,
                                     List<List<ItemStack>> alternatives, List<NamespacedKey> tags,
                                     List<ItemStack> grid, Function<NamespacedKey, Set<Material>> tagResolver,
                                     Map<NamespacedKey, Set<Material>> resolvedTags) {
        List<Integer> required = new ArrayList<>();
        int actualCount = 0;
        for (int i = 0; i < 9; i++) if (present(recipe.get(i))) required.add(i);
        for (ItemStack item : grid) if (present(item)) actualCount++;
        if (required.isEmpty() || required.size() != actualCount) return false;
        int[] assigned = new int[9];
        Arrays.fill(assigned, -1);
        for (int expected : required) {
            if (!assign(expected, recipe, modes, alternatives, tags, grid,
                    assigned, new boolean[9], tagResolver, resolvedTags)) return false;
        }
        return true;
    }

    private static boolean assign(int expected, List<ItemStack> recipe,
                                  List<IngredientMatchMode> modes, List<List<ItemStack>> alternatives,
                                  List<NamespacedKey> tags, List<ItemStack> grid, int[] assigned,
                                  boolean[] seen, Function<NamespacedKey, Set<Material>> tagResolver,
                                  Map<NamespacedKey, Set<Material>> resolvedTags) {
        for (int i = 0; i < 9; i++) {
            if (seen[i] || !slotMatches(expected, grid.get(i), recipe, modes, alternatives,
                    tags, tagResolver, resolvedTags)) continue;
            seen[i] = true;
            if (assigned[i] < 0 || assign(assigned[i], recipe, modes, alternatives, tags,
                    grid, assigned, seen, tagResolver, resolvedTags)) {
                assigned[i] = expected;
                return true;
            }
        }
        return false;
    }

    private static boolean shaped(List<ItemStack> recipe, List<IngredientMatchMode> modes,
                                  List<List<ItemStack>> alternatives, List<NamespacedKey> tags,
                                  List<ItemStack> grid, Function<NamespacedKey, Set<Material>> tagResolver,
                                  Map<NamespacedKey, Set<Material>> resolvedTags) {
        int minRow = 3, minCol = 3, maxRow = -1, maxCol = -1;
        for (int slot = 0; slot < 9; slot++) {
            if (!present(recipe.get(slot))) continue;
            int row = slot / 3, col = slot % 3;
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);
        }
        if (maxRow < 0) return false;
        int height = maxRow - minRow + 1;
        int width = maxCol - minCol + 1;
        for (int rowOffset = 0; rowOffset <= 3 - height; rowOffset++) {
            for (int colOffset = 0; colOffset <= 3 - width; colOffset++) {
                if (shapeAt(recipe, modes, alternatives, tags, grid, minRow, minCol,
                        height, width, rowOffset, colOffset, false, tagResolver, resolvedTags)
                        || shapeAt(recipe, modes, alternatives, tags, grid, minRow, minCol,
                        height, width, rowOffset, colOffset, true, tagResolver, resolvedTags)) return true;
            }
        }
        return false;
    }

    private static boolean shapeAt(List<ItemStack> recipe, List<IngredientMatchMode> modes,
                                   List<List<ItemStack>> alternatives, List<NamespacedKey> tags,
                                   List<ItemStack> grid, int minRow, int minCol, int height, int width,
                                   int rowOffset, int colOffset, boolean mirror,
                                   Function<NamespacedKey, Set<Material>> tagResolver,
                                   Map<NamespacedKey, Set<Material>> resolvedTags) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int patternRow = row - rowOffset;
                int patternCol = col - colOffset;
                int sourceSlot = -1;
                if (patternRow >= 0 && patternRow < height && patternCol >= 0 && patternCol < width) {
                    int sourceCol = mirror ? width - 1 - patternCol : patternCol;
                    sourceSlot = (minRow + patternRow) * 3 + minCol + sourceCol;
                }
                ItemStack actual = grid.get(row * 3 + col);
                boolean required = sourceSlot >= 0 && present(recipe.get(sourceSlot));
                if (required != present(actual)) return false;
                if (required && !slotMatches(sourceSlot, actual, recipe, modes, alternatives,
                        tags, tagResolver, resolvedTags)) return false;
            }
        }
        return true;
    }

    private static boolean slotMatches(int slot, ItemStack actual, List<ItemStack> recipe,
                                       List<IngredientMatchMode> modes, List<List<ItemStack>> alternatives,
                                       List<NamespacedKey> tags,
                                       Function<NamespacedKey, Set<Material>> tagResolver,
                                       Map<NamespacedKey, Set<Material>> resolvedTags) {
        if (!present(actual)) return false;
        IngredientMatchMode mode = modes.get(slot);
        if (sampleMatches(recipe.get(slot), mode, actual)) return true;
        for (ItemStack choice : alternatives.get(slot)) {
            if (sampleMatches(choice, mode, actual)) return true;
        }
        NamespacedKey tag = tags.get(slot);
        if (tag == null) return false;
        Set<Material> materials = resolvedTags.computeIfAbsent(tag, key -> {
            Set<Material> found = tagResolver.apply(key);
            return found == null ? Set.of() : found;
        });
        return materials.contains(actual.getType());
    }

    private static boolean sampleMatches(ItemStack expected, IngredientMatchMode mode, ItemStack actual) {
        return expected != null && (mode == IngredientMatchMode.MATERIAL
                ? expected.getType() == actual.getType() : expected.isSimilar(actual));
    }

    private static Set<Material> resolveTag(NamespacedKey key) {
        Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
        return tag == null ? Set.of() : tag.getValues();
    }

    private static boolean present(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }
}
