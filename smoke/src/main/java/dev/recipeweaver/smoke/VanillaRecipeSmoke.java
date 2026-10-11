package dev.recipeweaver.smoke;

import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.RecipeWeaverApi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemCraftResult;
import org.bukkit.inventory.ItemStack;

/** Test-only vanilla suppression and replacement probe. */
final class VanillaRecipeSmoke {
    static final NamespacedKey VANILLA_KEY = NamespacedKey.minecraft("oak_planks");
    private static final NamespacedKey REPLACEMENT_KEY =
            new NamespacedKey("recipeweaver", "smoke_oak_log_override");

    private VanillaRecipeSmoke() { }

    static void verify(RecipeWeaverApi api) {
        if (api.disabledVanillaRecipes().contains(VANILLA_KEY)) {
            check(Bukkit.getRecipe(VANILLA_KEY) == null,
                    "Saved vanilla recipe exclusion was not applied at startup");
            api.setVanillaRecipeDisabledAsync(VANILLA_KEY, false);
        }
        check(Bukkit.getRecipe(VANILLA_KEY) != null,
                "Vanilla oak planks recipe did not restore");
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        ingredients.set(0, new ItemStack(Material.OAK_LOG));
        api.saveRecipe(new RecipeDefinition(REPLACEMENT_KEY, RecipeKind.SHAPELESS,
                ingredients, new ItemStack(Material.AMETHYST_SHARD), "smoke", true, false,
                java.util.Collections.nCopies(9, IngredientMatchMode.MATERIAL),
                java.util.Collections.nCopies(9, List.of()),
                java.util.Collections.nCopies(9, null)));
        check(Bukkit.getRecipe(REPLACEMENT_KEY) != null,
                "Replacement workbench recipe was not registered");
        api.setVanillaRecipeDisabledAsync(VANILLA_KEY, true);
        check(api.disabledVanillaRecipes().contains(VANILLA_KEY)
                        && Bukkit.getRecipe(VANILLA_KEY) == null,
                "Vanilla oak planks recipe was not removed");
        check(craftType() == Material.AMETHYST_SHARD,
                "Replacement recipe did not craft after vanilla suppression");
    }

    static void mutateForRestore(RecipeWeaverApi api) {
        api.setVanillaRecipeDisabledAsync(VANILLA_KEY, false);
        check(!api.disabledVanillaRecipes().contains(VANILLA_KEY)
                        && Bukkit.getRecipe(VANILLA_KEY) != null,
                "Vanilla recipe did not return after exclusion was removed");
    }

    static void checkRecovered(RecipeWeaverApi api) {
        check(api.disabledVanillaRecipes().contains(VANILLA_KEY)
                        && Bukkit.getRecipe(VANILLA_KEY) == null,
                "Backup did not recover vanilla recipe exclusion");
        check(craftType() == Material.AMETHYST_SHARD,
                "Restored replacement recipe did not craft");
    }

    private static Material craftType() {
        ItemStack[] matrix = new ItemStack[9];
        matrix[0] = new ItemStack(Material.OAK_LOG);
        ItemCraftResult result = Bukkit.craftItemResult(matrix, Bukkit.getWorlds().get(0));
        return result == null || result.getResult() == null ? null : result.getResult().getType();
    }

    private static void check(boolean passed, String detail) {
        if (!passed) throw new IllegalStateException(detail);
    }
}
