package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.CraftCommandAction;
import dev.recipeweaver.api.CraftCommandSender;
import dev.recipeweaver.api.GridCraftBatch;
import dev.recipeweaver.api.GridCraftCompleteEvent;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class GridCraftCommandsTest {
    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test
    void consoleAndPlayerCommandsUseConfiguredCadenceAndReplaceTokens() {
        UUID id = UUID.randomUUID();
        Player player = player(id);
        CraftCommandAction consoleOnce = new CraftCommandAction(
                "say crafted_by_{player}_{uuid}", CraftCommandSender.CONSOLE, false);
        CraftCommandAction playerEach = new CraftCommandAction(
                "rw info", CraftCommandSender.PLAYER, true);
        List<String> calls = new ArrayList<>();
        assertTrue(GridCraftCommands.execute(player,
                List.of(consoleOnce, consoleOnce, playerEach, playerEach), (sender, command) -> {
                    calls.add((sender == player ? "player:" : "console:") + command);
                    assertTrue(sender == player || sender == Bukkit.getConsoleSender());
                    return true;
                }).isEmpty());
        assertEquals(List.of("console:say crafted_by_TestCrafter_" + id,
                "player:rw info", "player:rw info"), calls);
    }

    @Test
    void rejectedAndFailingCommandsDoNotBlockFollowingBatches() {
        Player player = player(UUID.randomUUID());
        CraftCommandAction action = new CraftCommandAction("say test", CraftCommandSender.CONSOLE, true);
        List<String> failures = GridCraftCommands.execute(player, List.of(action, action, action),
                new java.util.function.BiPredicate<>() {
                    int calls;
                    @Override public boolean test(org.bukkit.command.CommandSender sender, String command) {
                        calls++;
                        if (calls == 2) throw new IllegalStateException("dispatch failed");
                        return calls != 1;
                    }
                });
        assertEquals(2, failures.size());
        assertThrows(IllegalArgumentException.class, () -> new CraftCommandAction(
                "/say test", CraftCommandSender.CONSOLE, false));
        assertThrows(IllegalArgumentException.class, () -> new CraftCommandAction(
                "say first\nsay second", CraftCommandSender.CONSOLE, false));
        assertThrows(IllegalArgumentException.class, () -> new CraftCommandAction(
                "x".repeat(1025), CraftCommandSender.CONSOLE, false));
    }

    @Test
    void optionalPlaceholderExpansionRunsAfterBuiltInTokensAndRejectsUnsafeText() {
        Player player = player(UUID.randomUUID());
        CraftCommandAction good = new CraftCommandAction("say {player} %rank%",
                CraftCommandSender.CONSOLE, true);
        CraftCommandAction bad = new CraftCommandAction("say %bad%",
                CraftCommandSender.CONSOLE, true);
        List<String> dispatched = new ArrayList<>();
        List<String> failures = GridCraftCommands.execute(player,
                List.of(good, bad, good), (sender, command) -> {
                    dispatched.add(command);
                    return true;
                }, (context, command) -> command.replace("%rank%", "Builder")
                        .replace("%bad%", "hidden\nnext"));
        assertEquals(List.of("say TestCrafter Builder", "say TestCrafter Builder"), dispatched);
        assertEquals(1, failures.size());
        assertThrows(IllegalArgumentException.class,
                () -> PlaceholderHook.checkedCommand("/op somebody"));
        assertThrows(IllegalArgumentException.class,
                () -> PlaceholderHook.checkedCommand("say " + "x".repeat(1025)));
        assertEquals("say %rank%", PlaceholderHook.expand(player, "say %rank%"));
    }

    @Test
    void craftEventKeepsCommittedResultsImmutable() {
        ItemStack result = new ItemStack(Material.EMERALD, 2);
        GridCraftBatch batch = new GridCraftBatch(
                new NamespacedKey("recipeweaver", "event_test"), result);
        result.setType(Material.DIRT);
        GridCraftCompleteEvent event = new GridCraftCompleteEvent(player(UUID.randomUUID()), List.of(batch));
        assertEquals(Material.EMERALD, event.batches().get(0).result().getType());
        event.batches().get(0).result().setType(Material.STONE);
        assertEquals(Material.EMERALD, event.batches().get(0).result().getType());
        assertThrows(UnsupportedOperationException.class, () -> event.batches().clear());
        assertThrows(IllegalArgumentException.class, () -> new GridCraftCompleteEvent(
                event.player(), List.of()));
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "TestCrafter";
                    case "getUniqueId" -> id;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
