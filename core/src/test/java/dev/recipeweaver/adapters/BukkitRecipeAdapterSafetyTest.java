package dev.recipeweaver.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.RecipeKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class BukkitRecipeAdapterSafetyTest {
    private final NamespacedKey key = new NamespacedKey("recipeweaver", "native_adapter_safety");
    private FakeRegistry registry;
    private BukkitRecipeAdapter adapter;
    private Recipe previous;

    @BeforeEach void setUp() {
        MockBukkit.mock();
        registry = new FakeRegistry();
        adapter = new BukkitRecipeAdapter(registry);
        previous = new ShapelessRecipe(key, new ItemStack(Material.STICK));
        registry.recipes.put(key, previous);
    }

    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void reportedFailedRemovalAbortsReplacementBeforeAddingNewRecipe() {
        registry.removeReturnsFalse = true;
        assertThrows(IllegalStateException.class, () -> adapter.register(updatedRecipe()));
        assertSame(previous, registry.get(key));
        assertEquals(0, registry.addCalls);
    }

    @Test void reportedSuccessfulRemovalWithOldRecipeStillRegisteredAbortsReplacement() {
        registry.removeLeavesRecipe = true;
        assertThrows(IllegalStateException.class, () -> adapter.register(updatedRecipe()));
        assertSame(previous, registry.get(key));
        assertEquals(0, registry.addCalls);
    }

    @Test void failedRemovalThatActuallyMutatedRegistryRestoresOldRecipe() {
        registry.removeReturnsFalseAfterRemoval = true;
        assertThrows(IllegalStateException.class, () -> adapter.register(updatedRecipe()));
        assertSame(previous, registry.get(key));
        assertEquals(1, registry.addCalls);
    }

    @Test void thrownRemovalAfterMutationRestoresOldRecipe() {
        registry.removeThrowsAfterRemoval = true;
        assertThrows(IllegalStateException.class, () -> adapter.register(updatedRecipe()));
        assertSame(previous, registry.get(key));
        assertEquals(1, registry.addCalls);
    }

    @Test void failedAddAfterSuccessfulRemovalRestoresOldRecipe() {
        registry.addFailures = 1;
        assertThrows(IllegalStateException.class, () -> adapter.register(updatedRecipe()));
        assertSame(previous, registry.get(key));
        assertEquals(2, registry.addCalls);
    }

    @Test void reportedSuccessfulAddWithoutRegistryEntryRestoresOldRecipe() {
        registry.addWithoutRecipe = 1;
        assertThrows(IllegalStateException.class, () -> adapter.register(updatedRecipe()));
        assertSame(previous, registry.get(key));
        assertEquals(2, registry.addCalls);
    }

    @Test void reportedSuccessfulAddWithWrongOutputRestoresOldRecipe() {
        registry.addsWrongOutput = 1;
        assertThrows(IllegalStateException.class, () -> adapter.register(updatedRecipe()));
        assertSame(previous, registry.get(key));
        assertEquals(2, registry.addCalls);
    }

    private RecipeDefinition updatedRecipe() {
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        ingredients.set(0, new ItemStack(Material.STONE));
        return new RecipeDefinition(key, RecipeKind.SHAPELESS, ingredients,
                new ItemStack(Material.DIAMOND), "", true, false);
    }

    private static final class FakeRegistry implements BukkitRecipeAdapter.RecipeRegistry {
        private final Map<NamespacedKey, Recipe> recipes = new HashMap<>();
        private int addCalls;
        private int addFailures;
        private int addWithoutRecipe;
        private int addsWrongOutput;
        private boolean removeReturnsFalse;
        private boolean removeLeavesRecipe;
        private boolean removeReturnsFalseAfterRemoval;
        private boolean removeThrowsAfterRemoval;

        @Override public Recipe get(NamespacedKey key) { return recipes.get(key); }

        @Override public boolean add(Recipe recipe) {
            addCalls++;
            if (addFailures > 0) { addFailures--; return false; }
            if (addWithoutRecipe > 0) { addWithoutRecipe--; return true; }
            NamespacedKey key = ((Keyed) recipe).getKey();
            if (addsWrongOutput > 0) {
                addsWrongOutput--;
                recipes.put(key, new ShapelessRecipe(key, new ItemStack(Material.EMERALD)));
            } else recipes.put(key, recipe);
            return true;
        }

        @Override public boolean remove(NamespacedKey key) {
            if (removeReturnsFalse) return false;
            if (removeLeavesRecipe) return true;
            recipes.remove(key);
            if (removeReturnsFalseAfterRemoval) return false;
            if (removeThrowsAfterRemoval) throw new IllegalStateException("remove failed late");
            return true;
        }
    }
}
