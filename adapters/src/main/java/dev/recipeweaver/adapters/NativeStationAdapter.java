package dev.recipeweaver.adapters;

import dev.recipeweaver.api.StationRecipeDefinition;
import dev.recipeweaver.api.IngredientMatchMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmithingTrimRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;

/** Registers workstation types supported by the baseline Bukkit API. */
public final class NativeStationAdapter {
    public void register(StationRecipeDefinition recipe) {
        RecipeChoice[] choices = new RecipeChoice[recipe.kind().slots()];
        for (int slot = 0; slot < choices.length; slot++) {
            if (recipe.hasIngredient(slot)) choices[slot] = choice(recipe, slot);
        }
        unregister(recipe.key());
        if (!recipe.enabled()) return;
        RecipeChoice first = choices[0];
        switch (recipe.kind()) {
            case FURNACE -> addCooking(new FurnaceRecipe(recipe.key(), recipe.result(), first,
                    recipe.experience(), recipe.cookingTicks()), recipe);
            case BLAST_FURNACE -> addCooking(new BlastingRecipe(recipe.key(), recipe.result(), first,
                    recipe.experience(), recipe.cookingTicks()), recipe);
            case SMOKER -> addCooking(new SmokingRecipe(recipe.key(), recipe.result(), first,
                    recipe.experience(), recipe.cookingTicks()), recipe);
            case CAMPFIRE -> addCooking(new CampfireRecipe(recipe.key(), recipe.result(), first,
                    recipe.experience(), recipe.cookingTicks()), recipe);
            case STONECUTTER -> {
                StonecuttingRecipe nativeRecipe = new StonecuttingRecipe(recipe.key(), recipe.result(), first);
                nativeRecipe.setGroup(recipe.group());
                add(nativeRecipe);
            }
            case SMITHING_TRANSFORM -> add(new SmithingTransformRecipe(recipe.key(), recipe.result(),
                    first, choices[1], choices[2]));
            case SMITHING_TRIM -> add(new SmithingTrimRecipe(recipe.key(), first,
                    choices[1], choices[2]));
            case ANVIL, GRINDSTONE -> { /* Native result event listener handles these workstations. */ }
            case BREWING, CAULDRON -> { /* Dedicated station listeners handle these workstations. */ }
        }
    }

    private void addCooking(CookingRecipe<?> recipe, StationRecipeDefinition definition) {
        recipe.setGroup(definition.group());
        recipe.setCategory(definition.bookCategory());
        add(recipe);
    }

    private void add(org.bukkit.inventory.Recipe recipe) {
        if (!Bukkit.addRecipe(recipe)) throw new IllegalStateException("Server rejected " + ((org.bukkit.Keyed) recipe).getKey());
    }

    private RecipeChoice choice(StationRecipeDefinition recipe, int index) {
        List<org.bukkit.inventory.ItemStack> samples = new ArrayList<>();
        samples.add(recipe.ingredients().get(index));
        samples.addAll(recipe.alternatives().get(index));
        if (recipe.matchModes().get(index) == IngredientMatchMode.EXACT) {
            return new RecipeChoice.ExactChoice(samples);
        }
        LinkedHashSet<Material> materials = new LinkedHashSet<>();
        for (org.bukkit.inventory.ItemStack sample : samples) materials.add(sample.getType());
        NamespacedKey tagKey = recipe.itemTags().get(index);
        if (tagKey != null) {
            Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, tagKey, Material.class);
            if (tag == null || tag.getValues().isEmpty()) {
                throw new IllegalArgumentException("Unknown or empty item tag: " + tagKey);
            }
            materials.addAll(tag.getValues());
        }
        materials.removeIf(material -> !material.isItem());
        if (materials.isEmpty()) throw new IllegalArgumentException("Station choice has no item materials");
        return new RecipeChoice.MaterialChoice(new ArrayList<>(materials));
    }

    public void unregister(NamespacedKey key) { Bukkit.removeRecipe(key); }
}
