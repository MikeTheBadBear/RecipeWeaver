package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class BookItemListenerTest {
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
        player = MockBukkit.getMock().addPlayer("Reader");
    }

    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test
    void cancelledAirStillOpensTaggedBookButDeniedBlockAndOrdinaryBooksDoNot() throws Exception {
        EventHandler handler = BookItemListener.class
                .getMethod("onInteract", PlayerInteractEvent.class).getAnnotation(EventHandler.class);
        assertFalse(handler.ignoreCancelled());
        List<PlayerMock> opened = new ArrayList<>();
        BookItemListener listener = new BookItemListener(plugin, () -> true,
                reader -> true, reader -> opened.add((PlayerMock) reader));
        ItemStack tagged = BookItemListener.create(plugin);
        player.getInventory().setItemInMainHand(tagged);

        PlayerInteractEvent air = event(Action.RIGHT_CLICK_AIR, tagged, EquipmentSlot.HAND);
        air.setCancelled(true);
        listener.onInteract(air);
        assertEquals(List.of(player), opened);
        assertTrue(air.isCancelled());

        PlayerInteractEvent deniedBlock = event(Action.RIGHT_CLICK_BLOCK, tagged, EquipmentSlot.HAND);
        deniedBlock.setUseItemInHand(Event.Result.DENY);
        listener.onInteract(deniedBlock);
        assertEquals(1, opened.size());

        ItemStack ordinary = new ItemStack(Material.BOOK);
        player.getInventory().setItemInMainHand(ordinary);
        PlayerInteractEvent ordinaryAir = event(Action.RIGHT_CLICK_AIR, ordinary, EquipmentSlot.HAND);
        ordinaryAir.setCancelled(true);
        listener.onInteract(ordinaryAir);
        assertEquals(1, opened.size());
    }

    @Test
    void offhandEventDoesNotOpenBookTwiceWhenMainHandAlreadyHasIt() {
        List<PlayerMock> opened = new ArrayList<>();
        BookItemListener listener = new BookItemListener(plugin, () -> true,
                reader -> true, reader -> opened.add((PlayerMock) reader));
        ItemStack tagged = BookItemListener.create(plugin);
        player.getInventory().setItemInMainHand(tagged);
        player.getInventory().setItemInOffHand(tagged.clone());
        listener.onInteract(event(Action.RIGHT_CLICK_AIR, tagged, EquipmentSlot.HAND));
        listener.onInteract(event(Action.RIGHT_CLICK_AIR, tagged, EquipmentSlot.OFF_HAND));
        assertEquals(List.of(player), opened);
    }

    private PlayerInteractEvent event(Action action, ItemStack item, EquipmentSlot hand) {
        return new PlayerInteractEvent(player, action, item, null, BlockFace.SELF, hand);
    }
}
