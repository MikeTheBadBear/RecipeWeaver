package dev.recipeweaver.core;

import dev.recipeweaver.api.StationCraftBatch;
import dev.recipeweaver.api.StationKind;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

/** Proves a campfire's claimed drop survived spawn and its selected input slot cleared. */
final class CampfireCommitTracker {
    static final class Pending {
        private final UUID entity;
        private final NamespacedKey recipe;
        private final CampfireDiscoveryTracker.CookOrigin origin;
        private final ItemStack result;
        private boolean invalidated;
        private boolean consumed;

        private Pending(UUID entity, NamespacedKey recipe,
                CampfireDiscoveryTracker.CookOrigin origin, ItemStack result) {
            this.entity = entity;
            this.recipe = recipe;
            this.origin = origin;
            this.result = result.clone();
        }

        CampfireDiscoveryTracker.CookOrigin origin() { return origin; }
    }

    private final Map<UUID, Pending> active = new HashMap<>();

    Pending begin(UUID entity, CampfireDiscoveryTracker.SpawnClaim claim) {
        Objects.requireNonNull(entity, "entity");
        Pending earlier = active.get(entity);
        if (earlier != null) {
            earlier.invalidated = true;
            return null;
        }
        if (claim == null || claim.recipe() == null || claim.origin() == null
                || claim.origin().slot() < 0 || claim.origin().slot() >= 4
                || !present(claim.item())) return null;
        Pending pending = new Pending(entity, claim.recipe(), claim.origin(), claim.item());
        active.put(entity, pending);
        return pending;
    }

    StationCraftBatch complete(Pending pending, boolean spawnAccepted, boolean entityValid,
            ItemStack liveItem, String liveRecipeMarker, boolean slotCleared) {
        if (pending == null || pending.consumed) return null;
        pending.consumed = true;
        if (!active.remove(pending.entity, pending) || pending.invalidated
                || !spawnAccepted || !entityValid || !slotCleared
                || !pending.recipe.toString().equals(liveRecipeMarker)
                || !present(liveItem) || !pending.result.isSimilar(liveItem)
                || pending.result.getAmount() != liveItem.getAmount()) return null;
        return new StationCraftBatch(pending.recipe, StationKind.CAMPFIRE, pending.result);
    }

    void discard(Pending pending) { complete(pending, false, false, null, null, false); }

    void invalidateOrigin(UUID world, int x, int y, int z) {
        active.entrySet().removeIf(entry -> {
            CampfireDiscoveryTracker.CookOrigin origin = entry.getValue().origin;
            if (!origin.world().equals(world) || origin.x() != x
                    || origin.y() != y || origin.z() != z) return false;
            entry.getValue().invalidated = true;
            return true;
        });
    }

    private static boolean present(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }
}
