package dev.recipeweaver.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Captures long text through a temporary, protected book and quill. */
final class BookPrompt implements Listener {
    private static final int MAX_PAGES = 8;
    private static final int MAX_PAGE_LENGTH = 1024;
    private final Plugin plugin;
    private final NamespacedKey marker;
    private final Map<UUID, UUID> pending = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> submitted = new HashSet<>();

    BookPrompt(Plugin plugin) {
        this.plugin = plugin;
        marker = new NamespacedKey(plugin, "temporary_command_book");
    }

    void open(Player player, String initial, Consumer<String> accepted, Runnable cancelled) {
        UUID token = UUID.randomUUID();
        UUID playerId = player.getUniqueId();
        pending.put(playerId, token);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!pending.remove(playerId, token)) return;
            if (!player.isOnline()) return;
            cancel(player);
            PlayerInventory inventory = player.getInventory();
            int slot = -1;
            for (int index = 0; index < 9; index++) {
                ItemStack item = inventory.getItem(index);
                if (item == null || item.getType().isAir()) { slot = index; break; }
            }
            if (slot < 0) {
                player.sendMessage(ChatColor.RED + "Free one hotbar slot to edit long text.");
                cancelled.run();
                return;
            }
            if (initial != null && initial.length() > MAX_PAGES * MAX_PAGE_LENGTH) {
                player.sendMessage(ChatColor.RED + "Text is too long for the in-game book editor.");
                cancelled.run();
                return;
            }
            ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
            BookMeta meta = (BookMeta) book.getItemMeta();
            meta.setDisplayName("RecipeWeaver text editor");
            meta.setPages(splitPages(initial == null ? "" : initial));
            meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, token.toString());
            book.setItemMeta(meta);
            Session session = new Session(token, slot, inventory.getHeldItemSlot(), accepted);
            sessions.put(player.getUniqueId(), session);
            inventory.setItem(slot, book);
            inventory.setHeldItemSlot(slot);
            player.closeInventory();
            player.sendMessage(ChatColor.AQUA + "Right-click the book, edit up to eight pages, and click Done."
                    + " Page boundaries do not add newlines. Use /rw to cancel.");
        });
    }

    void cancel(Player player) {
        pending.remove(player.getUniqueId());
        Session session = sessions.remove(player.getUniqueId());
        if (session != null) {
            submitted.remove(session.token());
            cleanup(player, session);
        }
    }

    void close() {
        pending.clear();
        for (UUID id : List.copyOf(sessions.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) cancel(player);
        }
        sessions.clear();
        submitted.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEdit(PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.get(player.getUniqueId());
        if (session == null || event.getSlot() != session.slot()
                || !tagged(event.getPreviousBookMeta(), session.token())
                || !tagged(player.getInventory().getItem(session.slot()), session.token())) return;
        if (submitted.contains(session.token())) {
            event.setCancelled(true);
            return;
        }
        event.setSigning(false);
        List<String> pages = event.getNewBookMeta().getPages();
        if (pages.isEmpty() || pages.size() > MAX_PAGES
                || pages.stream().anyMatch(page -> page.length() > MAX_PAGE_LENGTH)
                || String.join("", pages).isBlank()) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Use one through eight pages of text, up to 1024 characters each.");
            return;
        }
        event.setCancelled(true);
        submitted.add(session.token());
        String value = String.join("", pages);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!sessions.remove(player.getUniqueId(), session)) return;
            submitted.remove(session.token());
            cleanup(player, session);
            if (player.isOnline()) session.accepted().accept(value);
        });
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;
        if (tagged(event.getCurrentItem(), session.token())
                || tagged(event.getCursor(), session.token())
                || event.getHotbarButton() == session.slot()) event.setCancelled(true);
    }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session != null && tagged(event.getOldCursor(), session.token())) event.setCancelled(true);
    }

    @EventHandler public void onDrop(PlayerDropItemEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && tagged(event.getItemDrop().getItemStack(), session.token())) {
            event.setCancelled(true);
        }
    }

    @EventHandler public void onSwap(PlayerSwapHandItemsEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && (tagged(event.getMainHandItem(), session.token())
                || tagged(event.getOffHandItem(), session.token()))) event.setCancelled(true);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { cancel(event.getPlayer()); }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        // A server crash can interrupt a prompt before its temporary book is removed.
        PlayerInventory inventory = event.getPlayer().getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (tagged(inventory.getItem(slot), null)) inventory.setItem(slot, null);
        }
    }

    private void cleanup(Player player, Session session) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (tagged(inventory.getItem(slot), session.token())) inventory.setItem(slot, null);
        }
        inventory.setHeldItemSlot(session.previousHeld());
    }

    private boolean tagged(ItemStack item, UUID token) {
        if (item == null || item.getType() != Material.WRITABLE_BOOK
                || !(item.getItemMeta() instanceof BookMeta meta)) return false;
        return tagged(meta, token);
    }

    private boolean tagged(BookMeta meta, UUID token) {
        String saved = meta.getPersistentDataContainer().get(marker, PersistentDataType.STRING);
        return saved != null && (token == null || token.toString().equals(saved));
    }

    private static String[] splitPages(String value) {
        if (value.isEmpty()) return new String[] {""};
        List<String> pages = new ArrayList<>();
        for (int start = 0; start < value.length();) {
            int end = Math.min(start + MAX_PAGE_LENGTH, value.length());
            if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
            pages.add(value.substring(start, end));
            start = end;
        }
        return pages.toArray(String[]::new);
    }

    private record Session(UUID token, int slot, int previousHeld, Consumer<String> accepted) { }
}
