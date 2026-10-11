package dev.recipeweaver.smoke;

import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.GridTier;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.WeightedResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

/** Test-only probe for optional custom-grid inputs on real servers. */
final class GridOptionalSmoke {
    static final NamespacedKey KEY = new NamespacedKey("recipeweaver", "smoke_grid_optional");

    private GridOptionalSmoke() { }

    static void verify(RecipeWeaverApi api) {
        api.saveGridRecipe(recipe(true, Material.ECHO_SHARD));
        check(api.gridRecipe(KEY).orElseThrow().optional(1), "Optional grid API lost its flag");
        List<ItemStack> input = emptyInput();
        input.set(0, new ItemStack(Material.PRISMARINE_SHARD));
        check(SmokePlugin.gridResult(api, input) == Material.ECHO_SHARD,
                "Missing optional grid input blocked its recipe");
        input.set(1, new ItemStack(Material.REDSTONE));
        check(SmokePlugin.gridResult(api, input) == Material.ECHO_SHARD,
                "Present optional grid input failed to match");
        input.set(1, new ItemStack(Material.DIRT));
        check(SmokePlugin.gridResult(api, input) == null,
                "Wrong item matched an optional grid input");
        input.set(0, null);
        input.set(1, new ItemStack(Material.REDSTONE));
        check(SmokePlugin.gridResult(api, input) == null,
                "Optional input alone crafted without the required input");
    }

    static void mutateForRestore(RecipeWeaverApi api) {
        api.saveGridRecipe(recipe(false, Material.GOLD_INGOT));
        check(!api.gridRecipe(KEY).orElseThrow().optional(1),
                "Optional grid mutation was not applied");
    }

    static void checkRecovered(RecipeWeaverApi api) {
        GridRecipeDefinition recovered = api.gridRecipe(KEY).orElseThrow();
        check(recovered.optional(1) && recovered.result().getType() == Material.ECHO_SHARD,
                "Backup did not recover optional grid input");
    }

    private static GridRecipeDefinition recipe(boolean optional, Material result) {
        List<ItemStack> samples = emptyInput();
        samples.set(0, new ItemStack(Material.PRISMARINE_SHARD));
        samples.set(1, new ItemStack(Material.REDSTONE));
        List<Boolean> flags = new ArrayList<>(Collections.nCopies(16, false));
        flags.set(1, optional);
        return new GridRecipeDefinition(KEY, GridTier.ELITE, RecipeKind.SHAPED, 4,
                samples, List.of(new WeightedResult(new ItemStack(result), 1)),
                "smoke", true, false,
                Collections.nCopies(16, IngredientMatchMode.EXACT), 0, true,
                Collections.nCopies(16, List.of()), Collections.nCopies(16, null),
                Collections.nCopies(16, true), Collections.nCopies(16, null),
                flags, RecipeConditions.NONE);
    }

    private static List<ItemStack> emptyInput() {
        return new ArrayList<>(Arrays.asList(new ItemStack[16]));
    }

    private static void check(boolean passed, String detail) {
        if (!passed) throw new IllegalStateException(detail);
    }
}
