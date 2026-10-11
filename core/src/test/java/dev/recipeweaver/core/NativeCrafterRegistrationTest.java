package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.WeatherCondition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class NativeCrafterRegistrationTest {
    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void unobservableCrafterWithholdsPlainExactShapedRecipes() {
        assertFalse(NativeCrafterGuard.nativeRegistrationAllowed(recipe(RecipeConditions.NONE,
                RecipeKind.SHAPED, IngredientMatchMode.EXACT, true), false));
    }

    @Test void unobservableCrafterWithholdsPlainExactShapelessRecipes() {
        assertFalse(NativeCrafterGuard.nativeRegistrationAllowed(recipe(RecipeConditions.NONE,
                RecipeKind.SHAPELESS, IngredientMatchMode.EXACT, true), false));
    }

    @Test void unobservableCrafterWithholdsNamedExactSample() {
        assertFalse(NativeCrafterGuard.nativeRegistrationAllowed(recipeWithFirstChoice(
                RecipeKind.SHAPED, IngredientMatchMode.EXACT, namedStone(), List.of()), false));
    }

    @Test void unobservableCrafterWithholdsExactAlternatives() {
        assertFalse(NativeCrafterGuard.nativeRegistrationAllowed(recipeWithFirstChoice(
                RecipeKind.SHAPELESS, IngredientMatchMode.EXACT,
                new ItemStack(Material.STONE), List.of(namedStone())), false));
    }

    @Test void unobservableCrafterWithholdsMixedMaterialAndExactSlots() {
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        ingredients.set(0, new ItemStack(Material.STONE));
        ingredients.set(1, namedStone());
        List<IngredientMatchMode> modes = new ArrayList<>(Collections.nCopies(9, IngredientMatchMode.EXACT));
        modes.set(0, IngredientMatchMode.MATERIAL);
        RecipeDefinition mixed = new RecipeDefinition(new NamespacedKey("recipeweaver", "mixed_crafter_policy"),
                RecipeKind.SHAPED, ingredients, new ItemStack(Material.DIAMOND), "", true, false,
                modes, Collections.nCopies(9, List.of()), Collections.nCopies(9, null),
                CraftingBookCategory.MISC, RecipeConditions.NONE);
        assertFalse(NativeCrafterGuard.nativeRegistrationAllowed(mixed, false));
    }

    @Test void unobservableCrafterRetainsStaticMaterialShapedRecipes() {
        assertTrue(NativeCrafterGuard.nativeRegistrationAllowed(recipe(RecipeConditions.NONE,
                RecipeKind.SHAPED, IngredientMatchMode.MATERIAL, true), false));
    }

    @Test void unobservableCrafterRetainsMaterialShapelessRecipesDespiteEmptyExactSlots() {
        assertTrue(NativeCrafterGuard.nativeRegistrationAllowed(recipe(RecipeConditions.NONE,
                RecipeKind.SHAPELESS, IngredientMatchMode.MATERIAL, true), false));
    }

    @Test void unobservableCrafterRetainsMaterialAlternativesAndNamedSamples() {
        assertTrue(NativeCrafterGuard.nativeRegistrationAllowed(recipeWithFirstChoice(
                RecipeKind.SHAPELESS, IngredientMatchMode.MATERIAL,
                namedStone(), List.of(new ItemStack(Material.COBBLESTONE))), false));
    }

    @Test void unobservableCrafterWithholdsPermissionConditions() {
        assertDenied(new RecipeConditions("recipeweaver.craft", null, null, -1, -1,
                WeatherCondition.ANY, 0, null, 0, null));
    }

    @Test void unobservableCrafterWithholdsWorldConditions() {
        assertDenied(new RecipeConditions(null, "world_nether", null, -1, -1,
                WeatherCondition.ANY, 0, null, 0, null));
    }

    @Test void unobservableCrafterWithholdsBiomeConditions() {
        assertDenied(new RecipeConditions(null, null, NamespacedKey.minecraft("desert"),
                -1, -1, WeatherCondition.ANY, 0, null, 0, null));
    }

    @Test void unobservableCrafterWithholdsTimeConditions() {
        assertDenied(new RecipeConditions(null, null, null, 12000, 18000,
                WeatherCondition.ANY, 0, null, 0, null));
    }

    @Test void unobservableCrafterWithholdsWeatherConditions() {
        assertDenied(new RecipeConditions(null, null, null, -1, -1,
                WeatherCondition.RAIN, 0, null, 0, null));
    }

    @Test void unobservableCrafterWithholdsExperienceConditions() {
        assertDenied(new RecipeConditions(null, null, null, -1, -1,
                WeatherCondition.ANY, 10, null, 0, null));
    }

    @Test void unobservableCrafterWithholdsScoreboardConditions() {
        assertDenied(new RecipeConditions(null, null, null, -1, -1,
                WeatherCondition.ANY, 0, "crafts", 3, null));
    }

    @Test void unobservableCrafterWithholdsAdvancementConditions() {
        assertDenied(new RecipeConditions(null, null, null, -1, -1,
                WeatherCondition.ANY, 0, null, 0, NamespacedKey.minecraft("story/mine_stone")));
    }

    @Test void cancellableCrafterEventAllowsRuntimeConditionValidation() {
        RecipeDefinition restricted = recipe(new RecipeConditions("recipeweaver.craft",
                null, null, -1, -1, WeatherCondition.ANY, 0, null, 0, null),
                RecipeKind.SHAPED, IngredientMatchMode.EXACT, true);
        assertTrue(NativeCrafterGuard.nativeRegistrationAllowed(restricted, true));
    }

    @Test void disabledRecipeIsNotNativeEvenWithGuard() {
        RecipeDefinition disabled = recipe(RecipeConditions.NONE, RecipeKind.SHAPED,
                IngredientMatchMode.EXACT, false);
        assertFalse(NativeCrafterGuard.nativeRegistrationAllowed(disabled, false));
        assertFalse(NativeCrafterGuard.nativeRegistrationAllowed(disabled, true));
    }

    private static void assertDenied(RecipeConditions conditions) {
        assertFalse(NativeCrafterGuard.nativeRegistrationAllowed(recipe(conditions,
                RecipeKind.SHAPED, IngredientMatchMode.MATERIAL, true), false));
    }

    private static RecipeDefinition recipeWithFirstChoice(RecipeKind kind, IngredientMatchMode mode,
                                                          ItemStack first, List<ItemStack> alternatives) {
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        ingredients.set(0, first);
        List<List<ItemStack>> choices = new ArrayList<>(Collections.nCopies(9, List.of()));
        choices.set(0, alternatives);
        return new RecipeDefinition(new NamespacedKey("recipeweaver", "crafter_policy_choice"),
                kind, ingredients, new ItemStack(Material.DIAMOND), "", true, false,
                Collections.nCopies(9, mode), choices, Collections.nCopies(9, null),
                CraftingBookCategory.MISC, RecipeConditions.NONE);
    }

    private static ItemStack namedStone() {
        ItemStack item = new ItemStack(Material.STONE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("Expected sample");
        item.setItemMeta(meta);
        return item;
    }

    private static RecipeDefinition recipe(RecipeConditions conditions, RecipeKind kind,
                                           IngredientMatchMode mode, boolean enabled) {
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        ingredients.set(0, new ItemStack(Material.STONE));
        return new RecipeDefinition(new NamespacedKey("recipeweaver", "crafter_policy"),
                kind, ingredients, new ItemStack(Material.DIAMOND), "", enabled, false,
                Collections.nCopies(9, mode), Collections.nCopies(9, List.of()),
                Collections.nCopies(9, null), CraftingBookCategory.MISC, conditions);
    }
}
