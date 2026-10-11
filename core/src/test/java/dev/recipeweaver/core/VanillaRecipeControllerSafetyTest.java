package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class VanillaRecipeControllerSafetyTest {
    private final NamespacedKey key = NamespacedKey.minecraft("sticks");
    private final AtomicInteger clears = new AtomicInteger();
    private FakeRegistry registry;
    private VanillaRecipeController controller;

    @BeforeEach void setUp() {
        MockBukkit.mock();
        registry = new FakeRegistry();
        registry.recipes.put(key, recipe(key));
        controller = new VanillaRecipeController(registry, clears::incrementAndGet);
    }

    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void failedNativeRemovalReturnRejectsExclusion() {
        registry.removeReturnsFalse = true;
        assertThrows(IllegalStateException.class, () -> controller.reconcile(Set.of(key)));
        assertNotNull(registry.get(key));
        assertEquals(1, clears.get());
    }

    @Test void throwingNativeRemovalRejectsExclusion() {
        registry.removeThrows = true;
        assertThrows(IllegalStateException.class, () -> controller.reconcile(Set.of(key)));
        assertNotNull(registry.get(key));
        assertEquals(1, clears.get());
    }

    @Test void reportedRemovalWithoutRegistryChangeRejectsExclusion() {
        registry.removeLeavesRecipe = true;
        assertThrows(IllegalStateException.class, () -> controller.reconcile(Set.of(key)));
        assertNotNull(registry.get(key));
    }

    @Test void failedNativeRestoreReturnRejectsEnable() {
        controller.reconcile(Set.of(key));
        registry.addReturnsFalse = true;
        assertThrows(IllegalStateException.class, () -> controller.reconcile(Set.of()));
        assertFalse(registry.recipes.containsKey(key));
        assertEquals(1, controller.removedRecipes().size());
    }

    @Test void throwingNativeRestoreRejectsEnable() {
        controller.reconcile(Set.of(key));
        registry.addThrows = true;
        assertThrows(IllegalStateException.class, () -> controller.reconcile(Set.of()));
        assertFalse(registry.recipes.containsKey(key));
        assertEquals(1, controller.removedRecipes().size());
    }

    @Test void reportedRestoreWithoutRecipeRejectsEnable() {
        controller.reconcile(Set.of(key));
        registry.addLeavesRecipeAbsent = true;
        assertThrows(IllegalStateException.class, () -> controller.reconcile(Set.of()));
        assertFalse(registry.recipes.containsKey(key));
    }

    @Test void foreignActiveRecipeCannotSilentlyReplaceRetainedVanillaRecipe() {
        controller.reconcile(Set.of(key));
        registry.recipes.put(key, recipeWithResult(key, Material.EMERALD));
        AtomicInteger writes = new AtomicInteger();
        var outcome = controller.applyAndPersist(Set.of(key), Set.of(), () -> {
            writes.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }, () -> Set.of(key));
        assertThrows(CompletionException.class, () -> outcome.toCompletableFuture().join());
        assertEquals(0, writes.get());
        assertFalse(registry.recipes.containsKey(key));
        assertEquals(1, controller.removedRecipes().size());
        controller.reconcile(Set.of());
        assertEquals(Material.STICK, registry.get(key).getResult().getType());
    }

    @Test void falsePositiveRestoreWithWrongOutputRejectsEnable() {
        controller.reconcile(Set.of(key));
        registry.addsWrongOutput = 1;
        AtomicInteger writes = new AtomicInteger();
        var outcome = controller.applyAndPersist(Set.of(key), Set.of(), () -> {
            writes.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }, () -> Set.of(key));
        assertThrows(CompletionException.class, () -> outcome.toCompletableFuture().join());
        assertEquals(0, writes.get());
        assertFalse(registry.recipes.containsKey(key));
        assertEquals(1, controller.removedRecipes().size());
        controller.reconcile(Set.of());
        assertEquals(Material.STICK, registry.get(key).getResult().getType());
    }

    @Test void laterRemovalFailureStillClearsCacheAfterEarlierMutation() {
        NamespacedKey second = NamespacedKey.minecraft("planks");
        registry.recipes.put(second, recipe(second));
        registry.failRemoveKey = second;
        Set<NamespacedKey> requested = new LinkedHashSet<>();
        requested.add(key);
        requested.add(second);
        assertThrows(IllegalStateException.class, () -> controller.reconcile(requested));
        assertFalse(registry.recipes.containsKey(key));
        assertNotNull(registry.get(second));
        assertEquals(1, clears.get());
    }

    @Test void failedNativeRemovalNeverCallsPersistence() {
        registry.removeReturnsFalse = true;
        AtomicInteger writes = new AtomicInteger();
        var outcome = controller.applyAndPersist(Set.of(), Set.of(key), () -> {
            writes.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }, Set::of);
        assertThrows(CompletionException.class, () -> outcome.toCompletableFuture().join());
        assertEquals(0, writes.get());
        assertNotNull(registry.get(key));
    }

    @Test void failedNativeRestoreNeverPersistsEnabledSetting() {
        controller.reconcile(Set.of(key));
        registry.addReturnsFalse = true;
        AtomicInteger writes = new AtomicInteger();
        var outcome = controller.applyAndPersist(Set.of(key), Set.of(), () -> {
            writes.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }, () -> Set.of(key));
        assertThrows(CompletionException.class, () -> outcome.toCompletableFuture().join());
        assertEquals(0, writes.get());
        assertFalse(registry.recipes.containsKey(key));
    }

    @Test void failedDisableWriteRestoresNativeRecipe() {
        var outcome = controller.applyAndPersist(Set.of(), Set.of(key),
                () -> CompletableFuture.failedFuture(new IllegalStateException("disk")), Set::of);
        assertThrows(CompletionException.class, () -> outcome.toCompletableFuture().join());
        assertNotNull(registry.get(key));
        assertTrue(controller.removedRecipes().isEmpty());
    }

    @Test void failedEnableWriteReappliesNativeExclusion() {
        controller.reconcile(Set.of(key));
        var outcome = controller.applyAndPersist(Set.of(key), Set.of(),
                () -> CompletableFuture.failedFuture(new IllegalStateException("disk")),
                () -> Set.of(key));
        assertThrows(CompletionException.class, () -> outcome.toCompletableFuture().join());
        assertFalse(registry.recipes.containsKey(key));
        assertEquals(1, controller.removedRecipes().size());
    }

    @Test void synchronousPersistenceFailureRollsBackNativeExclusion() {
        var outcome = controller.applyAndPersist(Set.of(), Set.of(key), () -> {
            throw new IllegalStateException("enqueue");
        }, Set::of);
        assertThrows(CompletionException.class, () -> outcome.toCompletableFuture().join());
        assertNotNull(registry.get(key));
    }

    @Test void visibleSettingsCallbackFailureRollsBackOptimisticNativeExclusion() {
        CompletableFuture<Void> persisted = new CompletableFuture<>();
        var outcome = controller.applyAndPersist(Set.of(), Set.of(key), () -> persisted,
                () -> { throw new IllegalStateException("visible settings unavailable"); });
        assertFalse(registry.recipes.containsKey(key));
        persisted.complete(null);
        assertThrows(CompletionException.class, () -> outcome.toCompletableFuture().join());
        assertNotNull(registry.get(key));
        assertTrue(controller.removedRecipes().isEmpty());
    }

    private static Recipe recipe(NamespacedKey key) {
        return new ShapelessRecipe(key, new ItemStack(Material.STICK));
    }

    private static Recipe recipeWithResult(NamespacedKey key, Material result) {
        return new ShapelessRecipe(key, new ItemStack(result));
    }

    private static final class FakeRegistry implements VanillaRecipeController.RecipeRegistry {
        private final Map<NamespacedKey, Recipe> recipes = new HashMap<>();
        private boolean removeReturnsFalse;
        private boolean removeThrows;
        private boolean removeLeavesRecipe;
        private boolean addReturnsFalse;
        private boolean addThrows;
        private boolean addLeavesRecipeAbsent;
        private int addsWrongOutput;
        private NamespacedKey failRemoveKey;

        @Override public Recipe get(NamespacedKey key) { return recipes.get(key); }

        @Override public boolean add(Recipe recipe) {
            if (addThrows) throw new IllegalStateException("add");
            if (addReturnsFalse) return false;
            NamespacedKey key = ((Keyed) recipe).getKey();
            if (addsWrongOutput > 0) {
                addsWrongOutput--;
                recipes.put(key, recipeWithResult(key, Material.EMERALD));
            } else if (!addLeavesRecipeAbsent) recipes.put(key, recipe);
            return true;
        }

        @Override public boolean remove(NamespacedKey key) {
            if (removeThrows) throw new IllegalStateException("remove");
            if (removeReturnsFalse || key.equals(failRemoveKey)) return false;
            if (!removeLeavesRecipe) recipes.remove(key);
            return true;
        }
    }
}
