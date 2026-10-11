package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class TextPromptTest {
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
    void savePromptOpensAfterMenuClickAndAcceptsTheDisplayedDefaultId() {
        Inventory editor = Bukkit.createInventory(null, 54, "Recipe editor");
        player.openInventory(editor);
        AtomicReference<String> submitted = new AtomicReference<>();
        TextPrompt prompt = new TextPrompt(plugin);
        prompt.open(player, "Recipe ID", "new_recipe", submitted::set, () -> { });

        assertSame(editor, player.getOpenInventory().getTopInventory());
        MockBukkit.getMock().getScheduler().performOneTick();
        assertEquals(InventoryType.ANVIL, player.getOpenInventory().getTopInventory().getType());
        assertTrue(TextPrompt.owns(player.getOpenInventory().getTopInventory()));
        assertNotNull(player.getOpenInventory().getTopInventory().getItem(2));

        InventoryClickEvent click = new InventoryClickEvent(player.getOpenInventory(),
                InventoryType.SlotType.RESULT, 2, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        prompt.onClick(click);
        assertNull(submitted.get());
        MockBukkit.getMock().getScheduler().performOneTick();
        assertEquals("new_recipe", submitted.get());
    }

    @Test
    void pluginShutdownRemovesControlsFromEveryOpenTextPrompt() {
        TextPrompt prompt = new TextPrompt(plugin);
        prompt.open(player, "Recipe ID", "new_recipe", value -> { }, () -> { });
        MockBukkit.getMock().getScheduler().performOneTick();
        Inventory inventory = player.getOpenInventory().getTopInventory();
        TextPrompt.closeAll();
        assertTrue(inventory.isEmpty());
    }

    @Test
    void repeatedAcceptClicksStayCancelledAndSubmitOnlyOnce() {
        TextPrompt prompt = new TextPrompt(plugin);
        AtomicInteger accepted = new AtomicInteger();
        prompt.open(player, "Recipe ID", "new_recipe", value -> accepted.incrementAndGet(), () -> { });
        MockBukkit.getMock().getScheduler().performOneTick();
        Inventory inventory = player.getOpenInventory().getTopInventory();
        for (int attempt = 0; attempt < 3; attempt++) {
            InventoryClickEvent click = new InventoryClickEvent(player.getOpenInventory(),
                    InventoryType.SlotType.RESULT, 2, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            prompt.onClick(click);
            assertTrue(click.isCancelled());
        }
        assertEquals(0, accepted.get());
        MockBukkit.getMock().getScheduler().performOneTick();
        assertEquals(1, accepted.get());
        assertTrue(inventory.isEmpty());
    }

    @Test
    void closingOrReplacingPromptClearsTemporaryItemsBeforeAnvilReturnsThem() {
        TextPrompt prompt = new TextPrompt(plugin);
        AtomicInteger cancelled = new AtomicInteger();
        prompt.open(player, "First ID", "first", value -> { }, cancelled::incrementAndGet);
        MockBukkit.getMock().getScheduler().performOneTick();
        Inventory first = player.getOpenInventory().getTopInventory();
        prompt.open(player, "Second ID", "second", value -> { }, cancelled::incrementAndGet);
        assertTrue(first.isEmpty());
        MockBukkit.getMock().getScheduler().performOneTick();
        Inventory second = player.getOpenInventory().getTopInventory();
        prompt.onClose(new InventoryCloseEvent(player.getOpenInventory()));
        assertTrue(second.isEmpty());
        MockBukkit.getMock().getScheduler().performOneTick();
        assertEquals(1, cancelled.get());
    }

    @Test
    void closingAfterAcceptBeforeScheduledCallbackDoesNotCancelOrLeakItems() {
        TextPrompt prompt = new TextPrompt(plugin);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        prompt.open(player, "ID", "recipe", value -> accepted.incrementAndGet(), cancelled::incrementAndGet);
        MockBukkit.getMock().getScheduler().performOneTick();
        Inventory inventory = player.getOpenInventory().getTopInventory();
        prompt.onClick(new InventoryClickEvent(player.getOpenInventory(),
                InventoryType.SlotType.RESULT, 2, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        prompt.onClose(new InventoryCloseEvent(player.getOpenInventory()));
        assertTrue(inventory.isEmpty());
        MockBukkit.getMock().getScheduler().performOneTick();
        assertEquals(1, accepted.get());
        assertEquals(0, cancelled.get());
    }

    @Test
    void customAnvilTopAcceptsRenamedResultWithoutCastingToAnvilInventory() {
        TextPrompt prompt = new TextPrompt(plugin);
        AtomicReference<String> submitted = new AtomicReference<>();
        prompt.open(player, "Recipe ID", "new_recipe", submitted::set, () -> { });
        MockBukkit.getMock().getScheduler().performOneTick();

        Inventory promptInventory = player.getOpenInventory().getTopInventory();
        Inventory customTop = Bukkit.createInventory(promptInventory.getHolder(), 9, "Custom anvil top");
        ItemStack result = new ItemStack(Material.PAPER);
        ItemMeta meta = result.getItemMeta();
        meta.setDisplayName("my_recipe");
        result.setItemMeta(meta);
        customTop.setItem(2, result);
        InventoryView customView = new InventoryView() {
            @Override public Inventory getTopInventory() { return customTop; }
            @Override public Inventory getBottomInventory() { return player.getInventory(); }
            @Override public HumanEntity getPlayer() { return player; }
            @Override public InventoryType getType() { return InventoryType.ANVIL; }
            @Override public String getTitle() { return "Recipe ID"; }
            @Override public String getOriginalTitle() { return "Recipe ID"; }
            @Override public void setTitle(String title) { }
        };
        InventoryClickEvent click = new InventoryClickEvent(customView,
                InventoryType.SlotType.RESULT, 2, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        prompt.onClick(click);
        MockBukkit.getMock().getScheduler().performOneTick();
        assertEquals("my_recipe", submitted.get());
    }
}
