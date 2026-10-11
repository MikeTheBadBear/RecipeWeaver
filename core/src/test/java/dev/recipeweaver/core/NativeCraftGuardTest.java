package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.WeatherCondition;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class NativeCraftGuardTest {
    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void crafterRemaindersAcceptCompactOrFullLayoutsAndRejectForgedItems() {
        ItemStack[] matrix = new ItemStack[9];
        matrix[0] = new ItemStack(Material.STONE);
        matrix[1] = new ItemStack(Material.DIRT);
        assertTrue(NativeCrafterGuard.safeRemainingItems(
                List.of(new ItemStack(Material.AIR), new ItemStack(Material.AIR)), matrix));
        assertTrue(NativeCrafterGuard.safeRemainingItems(
                Arrays.asList(new ItemStack[9]), matrix));
        assertFalse(NativeCrafterGuard.safeRemainingItems(
                List.of(new ItemStack(Material.DIAMOND, 64), new ItemStack(Material.AIR)),
                matrix));
        assertFalse(NativeCrafterGuard.safeRemainingItems(
                List.of(new ItemStack(Material.AIR)), matrix));
    }

    @Test void checksTheActualMatrixAndPresentedResultInBothWorkbenchSizes() {
        var ingredients = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        ingredients.set(0, new ItemStack(Material.STONE));
        ingredients.set(1, new ItemStack(Material.DIRT));
        var key = new NamespacedKey("recipeweaver", "guard_test");
        var saved = new RecipeDefinition(key, RecipeKind.SHAPED, ingredients,
                new ItemStack(Material.DIAMOND, 2), "", true, false);
        ItemStack[] small = {new ItemStack(Material.STONE), new ItemStack(Material.DIRT), null, null};
        assertTrue(NativeCraftGuard.allowsRecipe(saved, false, small,
                new ItemStack(Material.DIAMOND, 2)));
        assertFalse(NativeCraftGuard.allowsRecipe(saved, false, small,
                new ItemStack(Material.DIAMOND)));
        assertFalse(NativeCraftGuard.allowsRecipe(saved, false, small,
                new ItemStack(Material.EMERALD, 2)));
        small[1] = new ItemStack(Material.GRAVEL);
        assertFalse(NativeCraftGuard.allowsRecipe(saved, false, small,
                new ItemStack(Material.DIAMOND, 2)));
        small[1] = new ItemStack(Material.DIRT);
        assertFalse(NativeCraftGuard.allowsRecipe(saved, true, small,
                new ItemStack(Material.DIAMOND, 2)));
        var disabled = new RecipeDefinition(key, RecipeKind.SHAPED, ingredients,
                new ItemStack(Material.DIAMOND, 2), "", false, false);
        assertFalse(NativeCraftGuard.allowsRecipe(disabled, false, small,
                new ItemStack(Material.DIAMOND, 2)));
        assertFalse(NativeCraftGuard.allowsRecipe(null, false, small,
                new ItemStack(Material.DIAMOND, 2)));
        assertFalse(NativeCraftGuard.allowsRecipe(saved, false, new ItemStack[5],
                new ItemStack(Material.DIAMOND, 2)));
    }

    @Test void workbenchCraftChecksConditionsOnPreviewTakeAndAutomation() {
        var ingredients = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        ingredients.set(0, new ItemStack(Material.STONE));
        ItemStack[] matrix = {new ItemStack(Material.STONE), null, null, null};
        ItemStack result = new ItemStack(Material.DIAMOND);
        RecipeConditions playerRules = new RecipeConditions("recipeweaver.craft.special", "factory",
                null, 23000, 1000, WeatherCondition.RAIN, 7, null, 0, null);
        RecipeDefinition saved = conditionedRecipe(ingredients, result, playerRules);
        World world = world(23500, true);
        assertTrue(NativeCraftGuard.allowsRecipe(saved, false, matrix, result,
                player(true, 8, world), world));
        assertFalse(NativeCraftGuard.allowsRecipe(saved, false, matrix, result,
                player(false, 8, world), world));
        assertFalse(NativeCraftGuard.allowsRecipe(saved, false, matrix, result,
                player(true, 6, world), world));
        World midday = world(12000, true);
        assertFalse(NativeCraftGuard.allowsRecipe(saved, false, matrix, result,
                player(true, 8, midday), midday));
        World dry = world(23500, false);
        assertFalse(NativeCraftGuard.allowsRecipe(saved, false, matrix, result,
                player(true, 8, dry), dry));
        assertFalse(NativeCraftGuard.allowsAutomatedRecipe(saved, false, matrix, result,
                world, new Location(world, 0, 64, 0)));

        RecipeConditions environment = new RecipeConditions(null, "factory", null,
                23000, 1000, WeatherCondition.RAIN, 0, null, 0, null);
        RecipeDefinition automated = conditionedRecipe(ingredients, result, environment);
        assertTrue(NativeCraftGuard.allowsAutomatedRecipe(automated, false, matrix, result,
                world, new Location(world, 0, 64, 0)));
        assertFalse(NativeCraftGuard.allowsAutomatedRecipe(automated, false, matrix, result,
                world(12000, true), new Location(world, 0, 64, 0)));
    }

    private static RecipeDefinition conditionedRecipe(ArrayList<ItemStack> ingredients,
                                                       ItemStack result, RecipeConditions conditions) {
        return new RecipeDefinition(new NamespacedKey("recipeweaver", "conditioned_guard_test"),
                RecipeKind.SHAPED, ingredients, result, "", true, false,
                Collections.nCopies(9, dev.recipeweaver.api.IngredientMatchMode.EXACT),
                Collections.nCopies(9, java.util.List.of()), Collections.nCopies(9, null),
                CraftingBookCategory.MISC, conditions);
    }

    private static World world(long time, boolean storm) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class<?>[] {World.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "factory";
                    case "getTime" -> time;
                    case "hasStorm" -> storm;
                    case "isThundering" -> false;
                    default -> null;
                });
    }

    private static Player player(boolean permission, int level, World world) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> permission;
                    case "getLevel" -> level;
                    case "getWorld" -> world;
                    default -> null;
                });
    }
}
