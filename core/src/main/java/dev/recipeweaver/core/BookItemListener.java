package dev.recipeweaver.core;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** A tagged, optional shortcut to the player recipe book. */
final class BookItemListener implements Listener {
    private final Plugin plugin;
    private final BooleanSupplier enabled;
    private final Predicate<Player> canBrowse;
    private final Consumer<Player> openBook;

    BookItemListener(RecipeWeaverPlugin plugin) {
        this(plugin, plugin::bookItemEnabled,
                player -> player.hasPermission("recipeweaver.recipes"),
                player -> plugin.menus().openRecipeBook(player, 0));
    }

    BookItemListener(Plugin plugin, BooleanSupplier enabled, Predicate<Player> canBrowse,
                     Consumer<Player> openBook) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.canBrowse = Objects.requireNonNull(canBrowse, "canBrowse");
        this.openBook = Objects.requireNonNull(openBook, "openBook");
    }

    static ItemStack create(Plugin plugin) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "RecipeWeaver Recipe Book");
        meta.setLore(List.of(ChatColor.GRAY + "Right-click to browse recipes"));
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "recipe_book"),
                PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    static boolean isBook(Plugin plugin, ItemStack item) {
        if (item == null || item.getType() != Material.BOOK || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "recipe_book"), PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    void give(Player player) {
        if (!enabled.getAsBoolean()) return;
        if (!player.getInventory().addItem(create(plugin)).isEmpty()) {
            player.sendMessage(ChatColor.RED + "Make room in your inventory for the recipe book.");
        } else {
            player.sendMessage(ChatColor.GREEN + "Received a RecipeWeaver recipe book.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (!enabled.getAsBoolean()) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // Bukkit pre-cancels interactions with air when vanilla would do nothing.
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && event.useItemInHand() == Event.Result.DENY) return;
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;
        Player player = event.getPlayer();
        if (hand == EquipmentSlot.OFF_HAND
                && isBook(plugin, player.getInventory().getItemInMainHand())) return;
        ItemStack held = hand == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (!isBook(plugin, held)) return;
        event.setCancelled(true);
        if (!canBrowse.test(player)) {
            player.sendMessage(ChatColor.RED + "Missing permission: recipeweaver.recipes");
            return;
        }
        openBook.accept(player);
    }
}
