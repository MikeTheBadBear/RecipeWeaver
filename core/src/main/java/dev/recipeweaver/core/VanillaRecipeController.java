package dev.recipeweaver.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Recipe;

/** Applies the configured Minecraft recipe exclusions and retains removed recipes for restoration. */
final class VanillaRecipeController {
    interface RecipeRegistry {
        Recipe get(NamespacedKey key);
        boolean add(Recipe recipe);
        boolean remove(NamespacedKey key);
    }

    private final RecipeRegistry registry;
    private final Runnable clearCrafterCache;
    private final BooleanSupplier canSuppress;
    private final Map<NamespacedKey, Recipe> removed = new LinkedHashMap<>();

    VanillaRecipeController(RecipeWeaverPlugin plugin) {
        this(new RecipeRegistry() {
            @Override public Recipe get(NamespacedKey key) { return Bukkit.getRecipe(key); }
            @Override public boolean add(Recipe recipe) { return Bukkit.addRecipe(recipe); }
            @Override public boolean remove(NamespacedKey key) { return Bukkit.removeRecipe(key); }
        }, plugin::clearCrafterRecipeCache, plugin::canSafelySuppressNativeRecipes);
    }

    VanillaRecipeController(RecipeRegistry registry, Runnable clearCrafterCache) {
        this(registry, clearCrafterCache, () -> true);
    }

    VanillaRecipeController(RecipeRegistry registry, Runnable clearCrafterCache,
                            BooleanSupplier canSuppress) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clearCrafterCache = Objects.requireNonNull(clearCrafterCache, "clearCrafterCache");
        this.canSuppress = Objects.requireNonNull(canSuppress, "canSuppress");
    }

    List<Recipe> removedRecipes() { return List.copyOf(removed.values()); }

    CompletionStage<Void> applyAndPersist(Set<NamespacedKey> previous,
            Set<NamespacedKey> requested, Supplier<CompletionStage<Void>> persist,
            Supplier<Set<NamespacedKey>> visible) {
        try { reconcile(requested); }
        catch (RuntimeException failure) {
            restoreAfterFailure(previous, failure);
            return CompletableFuture.failedFuture(failure);
        }
        try {
            return persist.get().whenComplete((ignored, failure) -> {
                try {
                    Set<NamespacedKey> current = visible.get();
                    if (current != null) reconcile(current);
                } catch (RuntimeException completionFailure) {
                    restoreAfterFailure(previous, completionFailure);
                    throw completionFailure;
                }
            });
        } catch (RuntimeException failure) {
            restoreAfterFailure(previous, failure);
            return CompletableFuture.failedFuture(failure);
        }
    }

    private void restoreAfterFailure(Set<NamespacedKey> previous, RuntimeException failure) {
        try { reconcile(previous); }
        catch (RuntimeException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
    }

    void reconcile(Set<NamespacedKey> disabled) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Recipes require the server thread");
        Objects.requireNonNull(disabled, "disabled");
        if (!disabled.isEmpty() && !canSuppress.getAsBoolean()) {
            throw new IllegalStateException(
                    "Vanilla recipe exclusion requires a cancellable crafter event or safe cache reset");
        }
        boolean mutationAttempted = false;
        try {
            for (NamespacedKey key : new ArrayList<>(removed.keySet())) {
                if (disabled.contains(key)) continue;
                Recipe original = removed.get(key);
                Recipe active = registry.get(key);
                if (active != null) {
                    if (!sameRegisteredRecipe(original, active)) {
                        throw new IllegalStateException(
                                "A different recipe occupies the vanilla key " + key);
                    }
                    removed.remove(key);
                    continue;
                }
                boolean added;
                mutationAttempted = true;
                try { added = registry.add(original); }
                catch (RuntimeException failure) {
                    throw new IllegalStateException("Could not restore vanilla recipe " + key, failure);
                }
                if (!added || !sameRegisteredRecipe(original, registry.get(key))) {
                    throw new IllegalStateException("Could not restore vanilla recipe " + key);
                }
                removed.remove(key);
            }
            for (NamespacedKey key : disabled) {
                Recipe active = registry.get(key);
                if (active == null) continue;
                // Retain the original before calling an API that may mutate and then fail.
                removed.putIfAbsent(key, active);
                boolean removedNow;
                mutationAttempted = true;
                try { removedNow = registry.remove(key); }
                catch (RuntimeException failure) {
                    throw new IllegalStateException("Could not disable vanilla recipe " + key, failure);
                }
                Recipe stillActive = registry.get(key);
                if (!removedNow || stillActive != null) {
                    throw new IllegalStateException("Could not disable vanilla recipe " + key);
                }
            }
        } finally {
            // A failed operation may still have mutated the NMS lookup cache.
            if (mutationAttempted) clearCrafterCache.run();
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
}
