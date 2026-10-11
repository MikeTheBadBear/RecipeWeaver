package dev.recipeweaver.core;

import dev.recipeweaver.api.GridRecipeDefinition;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Per-player grid cooldowns in server-owned player data, accessed only on the server thread. */
final class GridCooldowns {
    private GridCooldowns() { }

    static boolean ready(Player player, GridRecipeDefinition recipe) {
        if (recipe.cooldownSeconds() == 0) return true;
        return ready(player.getPersistentDataContainer(), recipe.key(), System.currentTimeMillis());
    }

    static boolean ready(Player player, NamespacedKey recipe, int cooldownSeconds) {
        return cooldownSeconds == 0 || player != null
                && ready(player.getPersistentDataContainer(), recipe, System.currentTimeMillis());
    }

    static boolean ready(PersistentDataContainer data, NamespacedKey recipe, long nowMillis) {
        Long until = data.get(key(recipe), PersistentDataType.LONG);
        return until == null || until <= nowMillis;
    }

    static Runnable commit(Player player, Map<NamespacedKey, Integer> secondsByRecipe) {
        return commit(player.getPersistentDataContainer(), secondsByRecipe, System.currentTimeMillis());
    }

    static Runnable commit(PersistentDataContainer data, Map<NamespacedKey, Integer> secondsByRecipe,
                           long nowMillis) {
        Map<NamespacedKey, Long> previous = new LinkedHashMap<>();
        try {
            for (var entry : secondsByRecipe.entrySet()) {
                int seconds = entry.getValue();
                if (seconds <= 0) continue;
                NamespacedKey storage = key(entry.getKey());
                previous.put(storage, data.get(storage, PersistentDataType.LONG));
                data.set(storage, PersistentDataType.LONG, nowMillis + seconds * 1000L);
            }
        } catch (RuntimeException failure) {
            try { restore(data, previous); }
            catch (RuntimeException undoFailure) { failure.addSuppressed(undoFailure); }
            throw failure;
        }
        return () -> restore(data, previous);
    }

    private static void restore(PersistentDataContainer data, Map<NamespacedKey, Long> previous) {
        for (var entry : previous.entrySet()) {
            if (entry.getValue() == null) data.remove(entry.getKey());
            else data.set(entry.getKey(), PersistentDataType.LONG, entry.getValue());
        }
    }

    private static NamespacedKey key(NamespacedKey recipe) {
        return new NamespacedKey("recipeweaver", "cooldown/"
                + recipe.getNamespace() + "/" + recipe.getKey());
    }
}
