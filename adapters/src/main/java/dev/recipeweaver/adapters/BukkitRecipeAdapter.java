package dev.recipeweaver.adapters;

import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.IngredientMatchMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

/** API-only adapter for standard crafting and auto-crafting recipes. */
public final class BukkitRecipeAdapter implements PlatformAdapter {
    interface RecipeRegistry {
        Recipe get(NamespacedKey key);
        boolean add(Recipe recipe);
        boolean remove(NamespacedKey key);
    }

    private final RecipeRegistry registry;

    public BukkitRecipeAdapter() {
        this(new RecipeRegistry() {
            @Override public Recipe get(NamespacedKey key) { return Bukkit.getRecipe(key); }
            @Override public boolean add(Recipe recipe) { return Bukkit.addRecipe(recipe); }
            @Override public boolean remove(NamespacedKey key) { return Bukkit.removeRecipe(key); }
        });
    }

    BukkitRecipeAdapter(RecipeRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void register(RecipeDefinition recipe) {
        RecipeChoice[] choices = choices(recipe);
        Recipe previous = registry.get(recipe.key());
        unregister(recipe.key());
        if (!recipe.enabled()) return;
        List<ItemStack> inputs = recipe.ingredients();
        if (recipe.kind() == RecipeKind.SHAPELESS) {
            ShapelessRecipe nativeRecipe = new ShapelessRecipe(recipe.key(), recipe.result());
            nativeRecipe.setGroup(recipe.group());
            nativeRecipe.setCategory(recipe.bookCategory());
            for (RecipeChoice choice : choices) {
                if (choice != null) nativeRecipe.addIngredient(choice);
            }
            install(recipe.key(), nativeRecipe, previous);
            return;
        }

        int minRow = 3, minCol = 3, maxRow = -1, maxCol = -1;
        for (int slot = 0; slot < 9; slot++) {
            if (inputs.get(slot) == null) continue;
            int row = slot / 3, col = slot % 3;
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);
        }
        ShapedRecipe nativeRecipe = new ShapedRecipe(recipe.key(), recipe.result());
        nativeRecipe.setGroup(recipe.group());
        nativeRecipe.setCategory(recipe.bookCategory());
        String[] rows = new String[maxRow - minRow + 1];
        for (int row = minRow; row <= maxRow; row++) {
            StringBuilder shape = new StringBuilder(maxCol - minCol + 1);
            for (int col = minCol; col <= maxCol; col++) {
                int slot = row * 3 + col;
                shape.append(inputs.get(slot) == null ? ' ' : (char) ('A' + slot));
            }
            rows[row - minRow] = shape.toString();
        }
        nativeRecipe.shape(rows);
        for (int slot = 0; slot < 9; slot++) {
            if (choices[slot] != null) nativeRecipe.setIngredient((char) ('A' + slot), choices[slot]);
        }
        install(recipe.key(), nativeRecipe, previous);
    }

    /** Resolve all choices before a recipe is saved, including while recipe lockdown is active. */
    public static void validate(RecipeDefinition recipe) { choices(recipe); }

    private static RecipeChoice[] choices(RecipeDefinition recipe) {
        RecipeChoice[] choices = new RecipeChoice[9];
        for (int slot = 0; slot < 9; slot++) {
            if (recipe.hasIngredient(slot)) choices[slot] = choice(recipe, slot);
        }
        return choices;
    }

    private static RecipeChoice choice(RecipeDefinition recipe, int slot) {
        ItemStack primary = recipe.ingredients().get(slot);
        List<ItemStack> samples = new ArrayList<>();
        samples.add(primary);
        samples.addAll(recipe.alternatives().get(slot));
        NamespacedKey tagKey = recipe.itemTags().get(slot);
        if (recipe.matchModes().get(slot) == IngredientMatchMode.EXACT) {
            return new RecipeChoice.ExactChoice(samples);
        }
        LinkedHashSet<Material> materials = new LinkedHashSet<>();
        for (ItemStack sample : samples) materials.add(sample.getType());
        if (tagKey != null) {
            Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, tagKey, Material.class);
            if (tag == null || tag.getValues().isEmpty()) {
                throw new IllegalArgumentException("Unknown or empty item tag: " + tagKey);
            }
            materials.addAll(tag.getValues());
        }
        materials.removeIf(material -> !material.isItem());
        if (materials.isEmpty()) throw new IllegalArgumentException("Workbench choice has no item materials");
        return new RecipeChoice.MaterialChoice(new ArrayList<>(materials));
    }

    private void install(NamespacedKey key, Recipe nativeRecipe, Recipe previous) {
        try {
            if (!registry.add(nativeRecipe)
                    || !sameRegisteredRecipe(nativeRecipe, registry.get(key))) {
                throw new IllegalStateException("Server did not register " + key);
            }
        } catch (RuntimeException failure) {
            rollbackRegistration(key, previous, failure);
            throw failure;
        }
    }

    private void rollbackRegistration(NamespacedKey key, Recipe previous,
                                      RuntimeException failure) {
        try {
            if (registry.get(key) != null) {
                registry.remove(key);
                if (registry.get(key) != null) {
                    throw new IllegalStateException("Could not remove failed registration " + key);
                }
            }
            if (previous != null && (!registry.add(previous)
                    || !sameRegisteredRecipe(previous, registry.get(key)))) {
                throw new IllegalStateException("Could not restore previous recipe " + key);
            }
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    @Override public void unregister(NamespacedKey key) {
        Recipe previous = registry.get(key);
        if (previous == null) return;
        boolean removed;
        try { removed = registry.remove(key); }
        catch (RuntimeException failure) {
            restoreAfterPartialRemoval(key, previous, failure);
            throw failure;
        }
        Recipe active = registry.get(key);
        if (!removed || active != null) {
            IllegalStateException failure = new IllegalStateException(
                    "Server did not remove native recipe " + key);
            if (active == null) restoreAfterPartialRemoval(key, previous, failure);
            throw failure;
        }
    }

    private void restoreAfterPartialRemoval(NamespacedKey key, Recipe previous,
                                             RuntimeException failure) {
        try {
            if (registry.get(key) == null && (!registry.add(previous)
                    || !sameRegisteredRecipe(previous, registry.get(key)))) {
                throw new IllegalStateException("Could not restore removed recipe " + key);
            }
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static boolean sameRegisteredRecipe(Recipe expected, Recipe actual) {
        if (expected == actual) return true;
        if (expected == null || actual == null
                || !(expected instanceof Keyed expectedKey)
                || !(actual instanceof Keyed actualKey)
                || !expectedKey.getKey().equals(actualKey.getKey())) return false;
        return expected.getResult().getAmount() == actual.getResult().getAmount()
                && expected.getResult().isSimilar(actual.getResult());
    }

    @Override public String platformName() { return Bukkit.getServer().getName(); }
    @Override public String minecraftVersion() {
        String[] parts = Bukkit.getBukkitVersion().split("[-.]", 4);
        if (parts.length < 2) return Bukkit.getBukkitVersion();
        if (parts[0].equals("1") && parts.length >= 3 && parts[2].matches("[0-9]+")) return parts[0] + "." + parts[1] + "." + parts[2];
        return parts[0] + "." + parts[1];
    }
}
