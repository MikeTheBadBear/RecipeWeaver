package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class BookPromptTest {
    private Plugin plugin;
    private PlayerMock player;

    @BeforeEach void setUp() {
        MockBukkit.mock();
        plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "RecipeWeaver";
                    case "isEnabled" -> true;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        player = MockBukkit.getMock().addPlayer("Editor");
    }

    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test
    void writableBookCapturesLongTextThenRestoresTheHotbar() {
        player.getInventory().setItem(0, new ItemStack(Material.STONE));
        player.getInventory().setHeldItemSlot(0);
        AtomicReference<String> saved = new AtomicReference<>();
        BookPrompt prompt = new BookPrompt(plugin);
        prompt.open(player, "say {player}", saved::set, () -> { });
        MockBukkit.getMock().getScheduler().performOneTick();
        ItemStack temporary = player.getInventory().getItem(1);
        assertEquals(Material.WRITABLE_BOOK, temporary.getType());
        assertEquals(1, player.getInventory().getHeldItemSlot());
        BookMeta previous = (BookMeta) temporary.getItemMeta();
        assertEquals("say {player}", previous.getPage(1));
        BookMeta edited = previous.clone();
        String longCommand = "say " + "long command ".repeat(12) + "{player}";
        edited.setPages(longCommand);
        PlayerEditBookEvent event = new PlayerEditBookEvent(player, 1, previous, edited, false);
        prompt.onEdit(event);
        assertTrue(event.isCancelled());
        assertNull(saved.get());
        MockBukkit.getMock().getScheduler().performOneTick();
        assertEquals(longCommand, saved.get());
        assertNull(player.getInventory().getItem(1));
        assertEquals(Material.STONE, player.getInventory().getItem(0).getType());
        assertEquals(0, player.getInventory().getHeldItemSlot());
    }

    @Test
    void invalidPageStaysEditableAndCancellationRemovesTemporaryBook() {
        BookPrompt prompt = new BookPrompt(plugin);
        prompt.open(player, "", value -> { }, () -> { });
        MockBukkit.getMock().getScheduler().performOneTick();
        ItemStack temporary = player.getInventory().getItem(0);
        BookMeta previous = (BookMeta) temporary.getItemMeta();
        BookMeta edited = previous.clone();
        String[] tooMany = new String[9];
        Arrays.fill(tooMany, "text");
        edited.setPages(tooMany);
        PlayerEditBookEvent event = new PlayerEditBookEvent(player, 0, previous, edited, false);
        prompt.onEdit(event);
        assertTrue(event.isCancelled());
        prompt.cancel(player);
        assertNull(player.getInventory().getItem(0));
    }

    @Test
    void multiPageEditorPreservesExactTextAcrossPageBoundaries() {
        String initial = "first line\n" + "x".repeat(1300);
        AtomicReference<String> saved = new AtomicReference<>();
        BookPrompt prompt = new BookPrompt(plugin);
        prompt.open(player, initial, saved::set, () -> { });
        MockBukkit.getMock().getScheduler().performOneTick();
        ItemStack temporary = player.getInventory().getItem(0);
        BookMeta previous = (BookMeta) temporary.getItemMeta();
        assertEquals(2, previous.getPageCount());
        assertEquals(initial, String.join("", previous.getPages()));
        BookMeta edited = previous.clone();
        edited.setPages("first line\n", "second line\n", "last line");
        PlayerEditBookEvent event = new PlayerEditBookEvent(player, 0, previous, edited, false);
        prompt.onEdit(event);
        assertTrue(event.isCancelled());
        MockBukkit.getMock().getScheduler().performOneTick();
        assertEquals("first line\nsecond line\nlast line", saved.get());
        assertNull(player.getInventory().getItem(0));
    }

    @Test
    void submittedBookRemainsProtectedUntilCleanupAndDuplicateEditIsCancelled() {
        AtomicInteger accepted = new AtomicInteger();
        BookPrompt prompt = new BookPrompt(plugin);
        prompt.open(player, "say safe", value -> accepted.incrementAndGet(), () -> { });
        MockBukkit.getMock().getScheduler().performOneTick();
        BookMeta previous = (BookMeta) player.getInventory().getItem(0).getItemMeta();
        PlayerEditBookEvent first = new PlayerEditBookEvent(player, 0, previous, previous.clone(), false);
        prompt.onEdit(first);
        assertTrue(first.isCancelled());
        player.openInventory(Bukkit.createInventory(null, 27));
        InventoryClickEvent move = new InventoryClickEvent(player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, 0, ClickType.NUMBER_KEY,
                InventoryAction.HOTBAR_SWAP, 0);
        prompt.onClick(move);
        assertTrue(move.isCancelled());
        PlayerEditBookEvent duplicate = new PlayerEditBookEvent(player, 0, previous, previous.clone(), false);
        prompt.onEdit(duplicate);
        assertTrue(duplicate.isCancelled());
        MockBukkit.getMock().getScheduler().performOneTick();
        assertEquals(1, accepted.get());
        assertNull(player.getInventory().getItem(0));
    }

    @Test
    void cancellingSubmittedBookPreventsItsDeferredSave() {
        AtomicReference<String> saved = new AtomicReference<>();
        BookPrompt prompt = new BookPrompt(plugin);
        prompt.open(player, "say cancelled", saved::set, () -> { });
        MockBukkit.getMock().getScheduler().performOneTick();
        BookMeta previous = (BookMeta) player.getInventory().getItem(0).getItemMeta();
        prompt.onEdit(new PlayerEditBookEvent(player, 0, previous, previous.clone(), false));
        prompt.cancel(player);
        MockBukkit.getMock().getScheduler().performOneTick();
        assertNull(saved.get());
        assertNull(player.getInventory().getItem(0));
    }

    @Test
    void cancelledPendingEditorDoesNotCreateBookOnNextTick() {
        BookPrompt prompt = new BookPrompt(plugin);
        prompt.open(player, "say pending", value -> { }, () -> { });
        prompt.cancel(player);
        MockBukkit.getMock().getScheduler().performOneTick();
        assertNull(player.getInventory().getItem(0));
    }
}
