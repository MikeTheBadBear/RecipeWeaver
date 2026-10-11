package dev.recipeweaver.api;

import org.bukkit.entity.Player;

/** Runs on the server thread after a custom grid inventory transaction commits. */
@FunctionalInterface
public interface GridCraftAction {
    void execute(Player player, GridCraftBatch batch);
}
