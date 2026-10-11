package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class MenuSessionTest {
    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test
    void delayedResultLeavesAnotherInventoryOpen() {
        PlayerMock player = MockBukkit.getMock().addPlayer("Administrator");
        Inventory settings = Bukkit.createInventory(null, 27, "RecipeWeaver Settings");
        Inventory other = Bukkit.createInventory(null, 27, "Another menu");
        Inventory refreshed = Bukkit.createInventory(null, 27, "Refreshed settings");
        player.openInventory(settings);
        MenuSession session = MenuSession.capture(player);
        assertTrue(session.stillViewing(player));
        CompletableFuture<Void> save = new CompletableFuture<>();
        save.whenComplete((ignored, failure) -> {
            if (session.stillViewing(player)) player.openInventory(refreshed);
        });

        player.openInventory(other);
        save.complete(null);
        assertSame(other, player.getOpenInventory().getTopInventory());
    }

    @Test
    void delayedResultRefreshesStillOpenInventory() {
        PlayerMock player = MockBukkit.getMock().addPlayer("Administrator");
        Inventory settings = Bukkit.createInventory(null, 27, "RecipeWeaver Settings");
        Inventory refreshed = Bukkit.createInventory(null, 27, "Refreshed settings");
        player.openInventory(settings);
        MenuSession session = MenuSession.capture(player);
        CompletableFuture<Void> save = new CompletableFuture<>();
        save.whenComplete((ignored, failure) -> {
            if (session.stillViewing(player)) player.openInventory(refreshed);
        });

        save.complete(null);
        assertSame(refreshed, player.getOpenInventory().getTopInventory());
    }
}
