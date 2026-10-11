package dev.recipeweaver.core;

import java.util.Map;
import org.bukkit.NamespacedKey;

/** Saved item IDs bound to the actual icons shown on one studio page. */
record ItemPageSnapshot(Map<Integer, NamespacedKey> keys) {
    ItemPageSnapshot {
        keys = Map.copyOf(keys);
    }

    NamespacedKey keyAt(int slot) {
        return slot >= 0 && slot < 45 ? keys.get(slot) : null;
    }
}
