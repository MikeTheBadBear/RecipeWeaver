package dev.recipeweaver.api;

import java.util.Objects;
import org.bukkit.NamespacedKey;

/** Grants a named advancement after a committed custom-grid craft. */
public record CraftAdvancementAction(NamespacedKey advancement) {
    public CraftAdvancementAction {
        Objects.requireNonNull(advancement, "advancement");
    }
}
