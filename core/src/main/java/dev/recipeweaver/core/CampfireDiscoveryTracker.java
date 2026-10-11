package dev.recipeweaver.core;

import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Carries a verified campfire cook to its drop without exposing an item marker to players. */
final class CampfireDiscoveryTracker {
    static final NamespacedKey COOK_TOKEN = new NamespacedKey("recipeweaver", "campfire_cook_token");
    static final NamespacedKey DROP_RECIPE = new NamespacedKey("recipeweaver", "campfire_drop_recipe");
    private static final long TOKEN_LIFETIME_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final int MAX_PENDING = 4096;

    record CookOrigin(UUID world, int x, int y, int z, int slot) { }
    record SpawnClaim(ItemStack item, NamespacedKey recipe, CookOrigin origin) { }
    private record Pending(NamespacedKey recipe, ItemStack result, ItemStack spawnComparison,
                           CookOrigin origin, long createdAt) {
        boolean near(Location location) {
            return location != null && location.getWorld() != null
                    && origin.world().equals(location.getWorld().getUID())
                    && Math.abs(location.getX() - (origin.x() + 0.5)) <= 2
                    && Math.abs(location.getY() - (origin.y() + 0.5)) <= 2
                    && Math.abs(location.getZ() - (origin.z() + 0.5)) <= 2;
        }
    }

    private final LongSupplier clock;
    private final Map<UUID, Pending> pending = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Pending> eldest) {
            return size() > MAX_PENDING;
        }
    };

    CampfireDiscoveryTracker() { this(System::nanoTime); }

    CampfireDiscoveryTracker(LongSupplier clock) { this.clock = clock; }

    void invalidateOrigin(UUID world, int x, int y, int z) {
        pending.entrySet().removeIf(entry -> {
            CookOrigin origin = entry.getValue().origin();
            return origin.world().equals(world) && origin.x() == x
                    && origin.y() == y && origin.z() == z;
        });
    }

    ItemStack tagCookResult(ItemStack result, NamespacedKey recipe, Block block) {
        return tagCookResult(result, recipe, block, -1);
    }

    ItemStack tagCookResult(ItemStack result, NamespacedKey recipe, Block block, int slot) {
        if (result == null || result.getType().isAir() || result.getAmount() < 1
                || recipe == null || !"recipeweaver".equals(recipe.getNamespace())) return result;
        long now = clock.getAsLong();
        pending.entrySet().removeIf(entry -> now - entry.getValue().createdAt() > TOKEN_LIFETIME_NANOS);
        ItemStack tagged = result.clone();
        ItemMeta meta = tagged.getItemMeta();
        if (meta == null) return result;
        UUID token = UUID.randomUUID();
        meta.getPersistentDataContainer().set(COOK_TOKEN, PersistentDataType.STRING, token.toString());
        tagged.setItemMeta(meta);
        pending.put(token, new Pending(recipe, result.clone(), stripToken(tagged),
                new CookOrigin(block.getWorld().getUID(),
                        block.getX(), block.getY(), block.getZ(), slot), now));
        return tagged;
    }

    SpawnClaim claimSpawn(ItemStack spawned, Location location) {
        if (spawned == null) return new SpawnClaim(null, null, null);
        ItemMeta meta = spawned.getItemMeta();
        if (meta == null) return new SpawnClaim(spawned, null, null);
        String raw = meta.getPersistentDataContainer().get(COOK_TOKEN, PersistentDataType.STRING);
        if (raw == null) return new SpawnClaim(spawned, null, null);
        ItemStack clean = stripToken(spawned);
        UUID token;
        try { token = UUID.fromString(raw); }
        catch (IllegalArgumentException invalid) { return new SpawnClaim(clean, null, null); }
        Pending selected = pending.remove(token);
        long now = clock.getAsLong();
        if (selected == null || now - selected.createdAt() > TOKEN_LIFETIME_NANOS
                || !selected.near(location) || !sameResult(selected.spawnComparison(), clean)) {
            return new SpawnClaim(clean, null, null);
        }
        // Removing the last PDC field can leave an empty ItemMeta on some
        // implementations. Restore the validated pre-token stack exactly.
        return new SpawnClaim(selected.result().clone(), selected.recipe(), selected.origin());
    }

    NamespacedKey consumePickup(PersistentDataContainer dropData, ItemStack taken,
            Function<NamespacedKey, StationRecipeDefinition> savedRecipes,
            Function<NamespacedKey, Recipe> registeredRecipes) {
        String raw = dropData.get(DROP_RECIPE, PersistentDataType.STRING);
        if (raw == null) return null;
        dropData.remove(DROP_RECIPE);
        if (raw.length() > 256) return null;
        NamespacedKey key;
        try { key = NamespacedKey.fromString(raw); }
        catch (IllegalArgumentException invalid) { return null; }
        if (key == null || !"recipeweaver".equals(key.getNamespace())
                || !raw.equals(key.toString())) return null;
        StationRecipeDefinition saved = savedRecipes.apply(key);
        Recipe registered = registeredRecipes.apply(key);
        return saved != null && saved.enabled() && saved.kind() == StationKind.CAMPFIRE
                && registered instanceof CampfireRecipe nativeRecipe
                && key.equals(nativeRecipe.getKey()) && taken != null
                && taken.getAmount() > 0 && saved.result().isSimilar(taken) ? key : null;
    }

    static boolean protectsMerge(PersistentDataContainer source,
                                 PersistentDataContainer target) {
        return source.has(DROP_RECIPE, PersistentDataType.STRING)
                || target.has(DROP_RECIPE, PersistentDataType.STRING);
    }

    private static ItemStack stripToken(ItemStack item) {
        ItemStack clean = item.clone();
        ItemMeta meta = clean.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().remove(COOK_TOKEN);
            clean.setItemMeta(meta);
        }
        return clean;
    }

    private static boolean sameResult(ItemStack expected, ItemStack actual) {
        return expected != null && actual != null && expected.getAmount() == actual.getAmount()
                && expected.isSimilar(actual);
    }
}
