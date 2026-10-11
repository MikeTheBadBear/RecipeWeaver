package dev.recipeweaver.core;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/** Prevents an asynchronous menu result from reopening an inventory the player has left. */
final class MenuSession {
    private final UUID playerId;
    private final Inventory opened;

    private MenuSession(UUID playerId, Inventory opened) {
        this.playerId = playerId;
        this.opened = opened;
    }

    static MenuSession capture(Player player) {
        return new MenuSession(player.getUniqueId(), player.getOpenInventory().getTopInventory());
    }

    boolean stillViewing(Player player) {
        return player.isOnline() && playerId.equals(player.getUniqueId())
                && player.getOpenInventory().getTopInventory() == opened;
    }
}
