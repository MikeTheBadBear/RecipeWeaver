package dev.recipeweaver.smoke;

import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.GridTier;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.RecipeWeaverApi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Test-only probe for partial metadata matching through the live custom-grid matcher. */
final class GridPartialSmoke {
    static final NamespacedKey KEY = new NamespacedKey("recipeweaver", "smoke_grid_partial");
    private static final NamespacedKey MARKER = new NamespacedKey("recipeweaver", "smoke_partial_marker");
    private static final NamespacedKey LIST_MARKER = new NamespacedKey("recipeweaver", "smoke_partial_list");

    private GridPartialSmoke() { }

    static void verify(RecipeWeaverApi api) {
        api.saveGridRecipe(recipe(IngredientMatchMode.PARTIAL, Material.NETHER_STAR));
        checkRecovered(api);
        List<ItemStack> input = new ArrayList<>(Arrays.asList(new ItemStack[16]));
        ItemStack item = sample();
        item.setAmount(3);
        ItemMeta meta = item.getItemMeta();
        meta.setLore(List.of("Additional lore"));
        meta.getPersistentDataContainer().set(new NamespacedKey("recipeweaver", "smoke_extra"),
                PersistentDataType.INTEGER, 4);
        item.setItemMeta(meta);
        input.set(15, item);
        check(api.gridRecipe(KEY).orElseThrow().matchesItemSample(0, item),
                "Partial sample mismatch: expected=" + sample().getItemMeta().serialize()
                        + " actual=" + item.getItemMeta().serialize());
        check(SmokePlugin.gridResult(api, input) == Material.NETHER_STAR,
                "Partial grid rejected extra metadata or a shifted pattern");
        item.setAmount(1);
        check(SmokePlugin.gridResult(api, input) == null,
                "Partial grid ignored the required ingredient amount");
        item.setAmount(3);
        meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(MARKER, PersistentDataType.STRING, "wrong");
        item.setItemMeta(meta);
        check(SmokePlugin.gridResult(api, input) == null,
                "Partial grid ignored a mismatched sample component");
        meta.getPersistentDataContainer().set(MARKER, PersistentDataType.STRING, "required");
        meta.getPersistentDataContainer().set(LIST_MARKER,
                PersistentDataType.LIST.strings(), List.of("wrong"));
        item.setItemMeta(meta);
        check(SmokePlugin.gridResult(api, input) == null,
                "Partial grid ignored a mismatched persistent-data list");
        meta.getPersistentDataContainer().set(LIST_MARKER,
                PersistentDataType.LIST.strings(), List.of("first", "second"));
        meta.setDisplayName("Wrong sample");
        item.setItemMeta(meta);
        check(SmokePlugin.gridResult(api, input) == null,
                "Partial grid ignored a mismatched sample name");
    }

    static void mutateForRestore(RecipeWeaverApi api) {
        api.saveGridRecipe(recipe(IngredientMatchMode.EXACT, Material.GOLD_INGOT));
        check(api.gridRecipe(KEY).orElseThrow().matchModes().get(0) == IngredientMatchMode.EXACT,
                "Partial grid backup mutation did not save");
    }

    static void checkRecovered(RecipeWeaverApi api) {
        GridRecipeDefinition recipe = api.gridRecipe(KEY).orElseThrow();
        check(saved(recipe), "Partial grid mode, result, or sample was not recovered");
    }

    static boolean saved(GridRecipeDefinition recipe) {
        if (!savedPrior(recipe)) return false;
        ItemMeta meta = recipe.ingredients().get(0).getItemMeta();
        return List.of("first", "second").equals(meta.getPersistentDataContainer().get(
                LIST_MARKER, PersistentDataType.LIST.strings()));
    }

    static boolean savedPrior(GridRecipeDefinition recipe) {
        if (recipe.matchModes().get(0) != IngredientMatchMode.PARTIAL
                || recipe.result().getType() != Material.NETHER_STAR) return false;
        ItemMeta meta = recipe.ingredients().get(0).getItemMeta();
        return "Pattern crystal".equals(meta.getDisplayName())
                && "required".equals(meta.getPersistentDataContainer().get(
                        MARKER, PersistentDataType.STRING));
    }

    private static GridRecipeDefinition recipe(IngredientMatchMode mode, Material result) {
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[16]));
        ingredients.set(0, sample());
        List<IngredientMatchMode> modes = new ArrayList<>(Collections.nCopies(16, IngredientMatchMode.EXACT));
        modes.set(0, mode);
        return new GridRecipeDefinition(KEY, GridTier.ELITE, RecipeKind.SHAPED, 4,
                ingredients, new ItemStack(result), "smoke", true, false, modes, 0, true);
    }

    private static ItemStack sample() {
        ItemStack item = new ItemStack(Material.PRISMARINE_CRYSTALS, 2);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("Pattern crystal");
        meta.getPersistentDataContainer().set(MARKER, PersistentDataType.STRING, "required");
        meta.getPersistentDataContainer().set(LIST_MARKER,
                PersistentDataType.LIST.strings(), List.of("first", "second"));
        item.setItemMeta(meta);
        return item;
    }

    private static void check(boolean passed, String detail) {
        if (!passed) throw new IllegalStateException(detail);
    }
}
