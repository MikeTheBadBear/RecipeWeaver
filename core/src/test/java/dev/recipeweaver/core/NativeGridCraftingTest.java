package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.*;

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
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemCraftResult;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class NativeGridCraftingTest {
    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void advancedFallbackEnforcesNativePermissionsAndRejectsStaleOrDisabledRecipes() {
        var player = MockBukkit.getMock().addPlayer();
        var world = player.getWorld();
        var key = new NamespacedKey("recipeweaver", "native_fallback_guard");
        ItemStack result = new ItemStack(Material.DIAMOND);
        var nativeRecipe = new ShapelessRecipe(key, result).addIngredient(Material.STONE);
        var pattern = new ArrayList<ItemStack>(Arrays.asList(new ItemStack[9]));
        pattern.set(0, new ItemStack(Material.STONE));
        var rules = new RecipeConditions("recipeweaver.test.craft", null, null,
                -1, -1, WeatherCondition.ANY, 0, null, 0, null);
        var saved = new RecipeDefinition(key, RecipeKind.SHAPELESS, pattern, result,
                "", true, false, Collections.nCopies(9, IngredientMatchMode.MATERIAL),
                Collections.nCopies(9, List.of()), Collections.nCopies(9, null),
                CraftingBookCategory.MISC, rules);
        ItemStack[] matrix = pattern.toArray(ItemStack[]::new);
        assertFalse(NativeGridCrafting.allows(nativeRecipe, saved, false, Set.of(), matrix,
                result, player, world));
        player.setOp(true);
        assertTrue(NativeGridCrafting.allows(nativeRecipe, saved, false, Set.of(), matrix,
                result, player, world));
        assertFalse(NativeGridCrafting.allows(nativeRecipe, saved, true, Set.of(), matrix,
                result, player, world));
        assertFalse(NativeGridCrafting.allows(nativeRecipe, null, false, Set.of(), matrix,
                result, player, world));
        assertFalse(NativeGridCrafting.allows(nativeRecipe, saved, false, Set.of(key), matrix,
                result, player, world));
        assertFalse(NativeGridCrafting.allows(nativeRecipe, saved, false, Set.of(), matrix,
                new ItemStack(Material.EMERALD), player, world));
        matrix[0] = new ItemStack(Material.DIRT);
        assertFalse(NativeGridCrafting.allows(nativeRecipe, saved, false, Set.of(), matrix,
                result, player, world));
    }

    @Test void ordinaryVanillaFallbackRemainsAvailableUnlessExplicitlyDisabled() {
        var recipe = new ShapelessRecipe(NamespacedKey.minecraft("test_fallback"),
                new ItemStack(Material.STICK)).addIngredient(Material.OAK_PLANKS);
        assertTrue(NativeGridCrafting.allows(recipe, null, true, Set.of(),
                new ItemStack[9], recipe.getResult(), null, null));
        assertFalse(NativeGridCrafting.allows(recipe, null, false, Set.of(recipe.getKey()),
                new ItemStack[9], recipe.getResult(), null, null));
        assertFalse(NativeGridCrafting.allows(null, null, false, Set.of(),
                new ItemStack[9], recipe.getResult(), null, null));
    }

    @Test void nativeOverflowIsPreservedAndCapacityCheckedWithoutChangingSourceSnapshots() {
        ItemStack[] matrix = new ItemStack[9];
        matrix[0] = new ItemStack(Material.HONEY_BOTTLE, 2);
        ItemStack bottle = new ItemStack(Material.GLASS_BOTTLE);
        var craft = craft(matrix, List.of(bottle));
        var effect = NativeGridCrafting.effect(craft);
        assertEquals(1, effect.returned().size());
        assertEquals(Material.GLASS_BOTTLE, effect.returned().getFirst().getType());
        matrix[0].setAmount(9);
        bottle.setAmount(7);
        assertEquals(2, effect.inputs().getFirst().getAmount());
        assertEquals(1, effect.returned().getFirst().getAmount());

        ItemStack[] full = new ItemStack[36];
        Arrays.setAll(full, i -> new ItemStack(Material.COBBLESTONE, 64));
        assertNull(GridMenus.insertOutputAndReturns(full, null, effect.returned()));
        assertNull(GridMenus.insertOutputAndReturns(full, craft.getResult(), effect.returned()));
        full[0] = null;
        assertNotNull(GridMenus.insertOutputAndReturns(full, null, effect.returned()));
        assertNull(GridMenus.insertOutputAndReturns(full, craft.getResult(), effect.returned()));
        full[1] = null;
        var next = GridMenus.insertOutputAndReturns(full, craft.getResult(), effect.returned());
        assertNotNull(next);
        assertEquals(Material.SUGAR, next[0].getType());
        assertEquals(Material.GLASS_BOTTLE, next[1].getType());
        assertNull(full[0]);
        assertNull(full[1]);
    }

    @Test void invalidNativeMatrixIsRejectedBeforeInventoryMutation() {
        assertThrows(IllegalStateException.class, () ->
                NativeGridCrafting.effect(craft(new ItemStack[4], List.of())));
    }

    private static ItemCraftResult craft(ItemStack[] matrix, List<ItemStack> overflow) {
        return new ItemCraftResult() {
            @Override public ItemStack getResult() { return new ItemStack(Material.SUGAR, 3); }
            @Override public ItemStack[] getResultingMatrix() { return matrix; }
            @Override public List<ItemStack> getOverflowItems() { return overflow; }
        };
    }
}
