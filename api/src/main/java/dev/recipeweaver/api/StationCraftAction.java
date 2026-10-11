package dev.recipeweaver.api;

import org.bukkit.entity.Player;

/** Runs on the server thread after an owned station result commits. Player is null for automated
 * brewing and cooking. Cooking action registration is currently gated by the API. */
@FunctionalInterface
public interface StationCraftAction {
    void execute(Player player, StationCraftBatch batch);
}
