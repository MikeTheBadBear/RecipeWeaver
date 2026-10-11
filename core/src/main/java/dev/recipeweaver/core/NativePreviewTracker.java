package dev.recipeweaver.core;

import java.util.Map;
import java.util.WeakHashMap;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Remembers a native result when its recipe pointer later disappears from an open inventory. */
final class NativePreviewTracker {
    private final Map<Inventory, ItemStack> previews = new WeakHashMap<>();

    void remember(Inventory inventory, ItemStack result) {
        if (result != null && !result.getType().isAir() && result.getAmount() > 0) {
            previews.put(inventory, result.clone());
        }
    }

    boolean matches(Inventory inventory, ItemStack presented) {
        ItemStack previous = previews.get(inventory);
        if (previous == null || presented == null || presented.getType().isAir()
                || previous.getAmount() != presented.getAmount()
                || !previous.isSimilar(presented)) return false;
        return true;
    }

    void forget(Inventory inventory) { previews.remove(inventory); }
}
