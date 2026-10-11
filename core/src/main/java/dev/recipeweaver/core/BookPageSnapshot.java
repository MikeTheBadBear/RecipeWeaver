package dev.recipeweaver.core;

import java.util.List;
import org.bukkit.NamespacedKey;

/** Recipe IDs corresponding to the icons actually shown on one book page. */
record BookPageSnapshot(List<NamespacedKey> keys) {
    BookPageSnapshot {
        keys = List.copyOf(keys);
    }

    NamespacedKey keyAt(int slot) {
        return slot >= 0 && slot < keys.size() ? keys.get(slot) : null;
    }
}
