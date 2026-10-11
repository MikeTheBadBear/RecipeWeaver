package dev.recipeweaver.core;

import dev.recipeweaver.api.GridRecipeDefinition;
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

/** Amount-aware ingredient matching for square grids, including translated and optional mirrored shapes. */
final class GridMatcher {
    private GridMatcher() { }

    static boolean matches(GridRecipeDefinition recipe, List<ItemStack> actual) {
        return consumption(recipe, actual) != null;
    }

    /** Returns required consumption per actual slot, or null when the grid does not match. */
    static int[] consumption(GridRecipeDefinition recipe, List<ItemStack> actual) {
        MatchPlan plan = plan(recipe, actual);
        return plan == null ? null : plan.amounts();
    }

    static int[] consumption(GridRecipeDefinition recipe, List<ItemStack> actual,
                             Function<NamespacedKey, Set<Material>> tagResolver) {
        MatchPlan plan = plan(recipe, actual, tagResolver);
        return plan == null ? null : plan.amounts();
    }

    static MatchPlan plan(GridRecipeDefinition recipe, List<ItemStack> actual) {
        return plan(recipe, actual, GridMatcher::resolveTag);
    }

    private static MatchPlan plan(GridRecipeDefinition recipe, List<ItemStack> actual,
                                  Function<NamespacedKey, Set<Material>> tagResolver) {
        int size = recipe.size();
        if (actual.size() != size * size) throw new IllegalArgumentException("Wrong grid size");
        List<NamespacedKey> tags = recipe.itemTags();
        Map<NamespacedKey, Set<Material>> resolvedTags = new HashMap<>();
        return recipe.kind() == RecipeKind.SHAPELESS
                ? shapeless(recipe, tags, actual, tagResolver, resolvedTags)
                : shaped(recipe, tags, actual, size,
                        recipe.allowMirror(), tagResolver, resolvedTags);
    }

    private static MatchPlan shapeless(GridRecipeDefinition recipe, List<NamespacedKey> tags,
                                   List<ItemStack> actual,
                                   Function<NamespacedKey, Set<Material>> tagResolver,
                                   Map<NamespacedKey, Set<Material>> resolvedTags) {
        List<Integer> required = new ArrayList<>();
        List<Integer> optional = new ArrayList<>();
        int actualCount = 0;
        for (int i = 0; i < recipe.size() * recipe.size(); i++) {
            if (!recipe.hasIngredient(i)) continue;
            if (recipe.optional(i)) optional.add(i);
            else required.add(i);
        }
        for (ItemStack item : actual) if (present(item)) actualCount++;
        if (required.isEmpty() || actualCount < required.size()
                || actualCount > required.size() + optional.size()) return null;

        // A one-to-one assignment is needed when two similar ingredients require different amounts.
        int[] assigned = new int[actual.size()];
        Arrays.fill(assigned, -1);
        for (int expectedIndex : required) {
            if (!assign(expectedIndex, recipe, tags, actual, assigned,
                    new boolean[actual.size()], tagResolver, resolvedTags)) return null;
        }
        for (int expectedIndex : optional) {
            assign(expectedIndex, recipe, tags, actual, assigned,
                    new boolean[actual.size()], tagResolver, resolvedTags);
        }
        int[] counts = new int[actual.size()];
        for (int i = 0; i < actual.size(); i++) {
            if (present(actual.get(i)) && assigned[i] < 0) return null;
            if (assigned[i] >= 0) counts[i] = recipe.ingredientAmount(assigned[i]);
        }
        return new MatchPlan(counts, assigned);
    }

    private static boolean assign(int expectedIndex, GridRecipeDefinition recipe,
                                  List<NamespacedKey> tags,
                                  List<ItemStack> actual, int[] assigned, boolean[] seen,
                                  Function<NamespacedKey, Set<Material>> tagResolver,
                                  Map<NamespacedKey, Set<Material>> resolvedTags) {
        for (int i = 0; i < actual.size(); i++) {
            ItemStack found = actual.get(i);
            if (seen[i] || !matches(recipe, expectedIndex, tags.get(expectedIndex), found,
                    tagResolver, resolvedTags)
                    || found.getAmount() < recipe.ingredientAmount(expectedIndex)) continue;
            seen[i] = true;
            if (assigned[i] < 0 || assign(assigned[i], recipe, tags,
                    actual, assigned, seen, tagResolver, resolvedTags)) {
                assigned[i] = expectedIndex;
                return true;
            }
        }
        return false;
    }

    private static MatchPlan shaped(GridRecipeDefinition recipe, List<NamespacedKey> tags,
                                List<ItemStack> actual, int size, boolean allowMirror,
                                Function<NamespacedKey, Set<Material>> tagResolver,
                                Map<NamespacedKey, Set<Material>> resolvedTags) {
        int minRow = size, minCol = size, maxRow = -1, maxCol = -1;
        for (int slot = 0; slot < size * size; slot++) {
            if (!recipe.hasIngredient(slot)) continue;
            int row = slot / size, col = slot % size;
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);
        }
        if (maxRow < 0) return null;
        int height = maxRow - minRow + 1;
        int width = maxCol - minCol + 1;
        for (int rowOffset = 0; rowOffset <= size - height; rowOffset++) {
            for (int colOffset = 0; colOffset <= size - width; colOffset++) {
                MatchPlan match = at(recipe, tags, actual, size,
                        minRow, minCol, height, width, rowOffset, colOffset, false,
                        tagResolver, resolvedTags);
                if (match != null) return match;
                if (allowMirror) {
                    match = at(recipe, tags, actual, size,
                            minRow, minCol, height, width, rowOffset, colOffset, true,
                            tagResolver, resolvedTags);
                    if (match != null) return match;
                }
            }
        }
        return null;
    }

    private static MatchPlan at(GridRecipeDefinition recipe, List<NamespacedKey> tags,
                            List<ItemStack> actual, int size,
                            int minRow, int minCol, int height, int width,
                            int rowOffset, int colOffset, boolean mirror,
                            Function<NamespacedKey, Set<Material>> tagResolver,
                            Map<NamespacedKey, Set<Material>> resolvedTags) {
        int[] counts = new int[size * size];
        int[] sources = new int[size * size];
        Arrays.fill(sources, -1);
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                int patternRow = row - rowOffset;
                int patternCol = col - colOffset;
                int sourceSlot = -1;
                if (patternRow >= 0 && patternRow < height && patternCol >= 0 && patternCol < width) {
                    int sourceCol = mirror ? width - 1 - patternCol : patternCol;
                    sourceSlot = (minRow + patternRow) * size + minCol + sourceCol;
                }
                int slot = row * size + col;
                ItemStack found = actual.get(slot);
                boolean expected = sourceSlot >= 0 && recipe.hasIngredient(sourceSlot);
                if (!expected && present(found)) return null;
                if (expected && !present(found) && !recipe.optional(sourceSlot)) return null;
                if (expected && present(found)) {
                    if (!matches(recipe, sourceSlot, tags.get(sourceSlot),
                            found, tagResolver, resolvedTags)
                            || found.getAmount() < recipe.ingredientAmount(sourceSlot)) return null;
                    counts[slot] = recipe.ingredientAmount(sourceSlot);
                    sources[slot] = sourceSlot;
                }
            }
        }
        return new MatchPlan(counts, sources);
    }

    /** Applies input use while keeping each matched ingredient's original recipe slot. */
    static CraftEffect apply(GridRecipeDefinition recipe, List<ItemStack> actual, MatchPlan plan) {
        if (plan == null || actual.size() != plan.amounts().length
                || actual.size() != plan.sourceSlots().length) {
            throw new IllegalArgumentException("Wrong grid match plan size");
        }
        List<ItemStack> next = new ArrayList<>(actual.size());
        List<ItemStack> returned = new ArrayList<>();
        for (int i = 0; i < actual.size(); i++) {
            ItemStack item = actual.get(i);
            int source = plan.sourceSlots()[i];
            if (source < 0) {
                if (plan.amounts()[i] != 0 || present(item)) {
                    throw new IllegalStateException("A grid ingredient changed before crafting");
                }
                next.add(null);
                continue;
            }
            int amount = plan.amounts()[i];
            if (!present(item) || amount < 1 || item.getAmount() < amount) {
                throw new IllegalStateException("A grid ingredient changed before crafting");
            }
            if (!recipe.consumes(source)) {
                next.add(item.clone());
                continue;
            }
            ItemStack remainder = recipe.remainder(source);
            if (item.getAmount() == amount) {
                next.add(remainder);
            } else {
                ItemStack left = item.clone();
                left.setAmount(item.getAmount() - amount);
                next.add(left);
                if (remainder != null) returned.add(remainder);
            }
        }
        return new CraftEffect(next, returned);
    }

    record MatchPlan(int[] amounts, int[] sourceSlots) { }
    record CraftEffect(List<ItemStack> inputs, List<ItemStack> returned) { }

    private static boolean matches(GridRecipeDefinition recipe, int expectedSlot, NamespacedKey tag,
                                   ItemStack found,
                                   Function<NamespacedKey, Set<Material>> tagResolver,
                                   Map<NamespacedKey, Set<Material>> resolvedTags) {
        if (!present(found)) return false;
        if (recipe.matchesItemSample(expectedSlot, found)) return true;
        if (tag == null) return false;
        Set<Material> materials = resolvedTags.computeIfAbsent(tag, key -> {
            Set<Material> foundMaterials = tagResolver.apply(key);
            return foundMaterials == null ? Set.of() : foundMaterials;
        });
        return materials.contains(found.getType());
    }

    static void validateTags(GridRecipeDefinition recipe) {
        for (NamespacedKey key : recipe.itemTags()) {
            if (key != null && resolveTag(key).isEmpty()) {
                throw new IllegalArgumentException("Unknown or empty item tag: " + key);
            }
        }
    }

    private static Set<Material> resolveTag(NamespacedKey key) {
        Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
        return tag == null ? Set.of() : tag.getValues();
    }

    static List<ItemStack> consume(List<ItemStack> actual, int[] counts) {
        if (counts == null || actual.size() != counts.length) {
            throw new IllegalArgumentException("Wrong consumption plan size");
        }
        List<ItemStack> remainder = new ArrayList<>(actual.size());
        for (int i = 0; i < actual.size(); i++) {
            ItemStack item = actual.get(i);
            int amount = counts[i];
            if (amount < 0 || (amount > 0 && (!present(item) || item.getAmount() < amount))) {
                throw new IllegalStateException("A grid ingredient changed before crafting");
            }
            if (!present(item) || item.getAmount() == amount) remainder.add(null);
            else {
                ItemStack left = item.clone();
                left.setAmount(item.getAmount() - amount);
                remainder.add(left);
            }
        }
        return remainder;
    }

    static boolean present(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }
}
