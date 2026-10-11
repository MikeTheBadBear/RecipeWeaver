package dev.recipeweaver.core;

import dev.recipeweaver.api.GridRecipeDefinition;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Per-player lifetime grid craft counts in server-owned player data. Server-thread only. */
final class GridCraftLimits {
    private GridCraftLimits() { }

    static boolean allows(Player player, GridRecipeDefinition recipe,
                          Map<NamespacedKey, Integer> pendingBatches) {
        if (recipe.maxCraftsPerPlayer() == 0) return true;
        if (player == null) return false;
        return allows(player.getPersistentDataContainer(), recipe.key(),
                recipe.maxCraftsPerPlayer(), pendingBatches.getOrDefault(recipe.key(), 0));
    }

    static boolean allows(Player player, NamespacedKey recipe, int limit) {
        return limit == 0 || player != null
                && allows(player.getPersistentDataContainer(), recipe, limit, 0);
    }

    static boolean allows(PersistentDataContainer data, NamespacedKey recipe,
                          int limit, int pendingBatches) {
        if (limit == 0) return true;
        Integer used = data.get(key(recipe), PersistentDataType.INTEGER);
        return pendingBatches >= 0 && pendingBatches < limit
                && (used == null || used >= 0 && used < limit - pendingBatches);
    }

    static Runnable commit(Player player, Map<NamespacedKey, Integer> batchesByRecipe) {
        return commit(player.getPersistentDataContainer(), batchesByRecipe);
    }

    static Runnable commit(PersistentDataContainer data, Map<NamespacedKey, Integer> batchesByRecipe) {
        Map<NamespacedKey, Integer> previous = new LinkedHashMap<>();
        try {
            for (var entry : batchesByRecipe.entrySet()) {
                int batches = entry.getValue();
                if (batches <= 0) throw new IllegalArgumentException("Craft batch count must be positive");
                NamespacedKey storage = key(entry.getKey());
                Integer used = data.get(storage, PersistentDataType.INTEGER);
                if (used != null && used < 0) throw new IllegalStateException("Invalid saved craft count");
                int next = Math.addExact(used == null ? 0 : used, batches);
                previous.put(storage, used);
                data.set(storage, PersistentDataType.INTEGER, next);
            }
        } catch (RuntimeException failure) {
            try { restore(data, previous); }
            catch (RuntimeException undoFailure) { failure.addSuppressed(undoFailure); }
            throw failure;
        }
        return () -> restore(data, previous);
    }

    private static void restore(PersistentDataContainer data, Map<NamespacedKey, Integer> previous) {
        for (var entry : previous.entrySet()) {
            if (entry.getValue() == null) data.remove(entry.getKey());
            else data.set(entry.getKey(), PersistentDataType.INTEGER, entry.getValue());
        }
    }

    private static NamespacedKey key(NamespacedKey recipe) {
        return new NamespacedKey("recipeweaver", "craft-count/"
                + recipe.getNamespace() + "/" + recipe.getKey());
    }
}
