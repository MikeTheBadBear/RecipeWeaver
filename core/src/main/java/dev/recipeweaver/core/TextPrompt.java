package dev.recipeweaver.core;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/** An anvil-based text input that never consumes a player's items or experience. */
final class TextPrompt implements Listener {
    private static final Set<Inventory> OWNED = Collections.newSetFromMap(new WeakHashMap<>());
    private final Plugin plugin;
    private final Map<UUID, PromptHolder> prompts = new HashMap<>();

    TextPrompt(Plugin plugin) { this.plugin = plugin; }

    static boolean owns(Inventory inventory) {
        return inventory.getHolder() instanceof PromptHolder || OWNED.contains(inventory);
    }

    static void closeAll() {
        for (Inventory inventory : List.copyOf(OWNED)) inventory.clear();
        OWNED.clear();
    }

    void open(Player player, String title, String initial, Consumer<String> accepted, Runnable cancelled) {
        String shown = initial == null || initial.isBlank() ? "type_here" : initial;
        PromptHolder holder = new PromptHolder(player.getUniqueId(),
                initial == null ? "" : initial, accepted, cancelled);
        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        meta.setDisplayName(shown);
        paper.setItemMeta(meta);
        PromptHolder previous = prompts.put(player.getUniqueId(), holder);
        if (previous != null) release(previous);
        // InventoryClickEvent handlers use this prompt throughout the editors.
        // Opening another inventory during that event can discard the new view.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (prompts.get(player.getUniqueId()) == holder && player.isOnline()) {
                if (!openNativeAnvil(player, title, paper, holder)) {
                    Inventory inventory = Bukkit.createInventory(holder, InventoryType.ANVIL, title);
                    holder.inventory = inventory;
                    OWNED.add(inventory);
                    inventory.setItem(0, paper);
                    inventory.setItem(2, paper.clone());
                    player.openInventory(inventory);
                }
            }
        });
    }

    /** Paper exposes a real anvil view; the generic Bukkit inventory is not an AnvilInventory. */
    private boolean openNativeAnvil(Player player, String title, ItemStack paper, PromptHolder holder) {
        try {
            Method method = player.getClass().getMethod("openAnvil", Location.class, boolean.class);
            Object opened = method.invoke(player, player.getLocation(), true);
            if (!(opened instanceof InventoryView view) || view.getType() != InventoryType.ANVIL) {
                return false;
            }
            holder.inventory = view.getTopInventory();
            OWNED.add(holder.inventory);
            try { view.setTitle(title); }
            catch (RuntimeException ignored) { }
            holder.inventory.setItem(0, paper);
            holder.inventory.setItem(2, paper.clone());
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            OWNED.remove(holder.inventory);
            holder.inventory = null;
            return false;
        }
    }

    private static boolean matches(Inventory inventory, PromptHolder holder) {
        return holder != null && holder.inventory != null
                && (inventory == holder.inventory || inventory.equals(holder.inventory)
                        || inventory.getHolder() == holder);
    }

    private PromptHolder current(Player player, Inventory inventory) {
        PromptHolder holder = prompts.get(player.getUniqueId());
        return matches(inventory, holder) ? holder : null;
    }

    @EventHandler
    public void onPrepare(PrepareAnvilEvent event) {
        if (!owns(event.getInventory())) return;
        ItemStack paper = event.getInventory().getItem(0);
        if (paper != null) {
            event.setResult(paper.clone());
            event.getInventory().setRepairCost(0);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!owns(top)) return;
        event.setCancelled(true);
        PromptHolder holder = current(player, top);
        if (holder == null || holder.submitted) return;
        if (event.getRawSlot() != 2) return;
        String value = renameText(event);
        if (value == null || value.isBlank()) value = holder.initial;
        if (value.isBlank()) {
            player.sendMessage(ChatColor.RED + "Enter text before accepting this prompt.");
            return;
        }
        String acceptedValue = value.trim();
        holder.submitted = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            boolean stillViewing = matches(player.getOpenInventory().getTopInventory(), holder);
            prompts.remove(player.getUniqueId(), holder);
            release(holder);
            if (!player.isOnline()) return;
            if (stillViewing) player.closeInventory();
            holder.accepted.accept(acceptedValue);
        });
    }

    private static String renameText(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top instanceof AnvilInventory anvil) {
            try {
                String name = anvil.getRenameText();
                if (name != null && !name.isBlank()) return name;
            } catch (RuntimeException ignored) { }
        }
        try {
            Method method = event.getView().getClass().getMethod("getRenameText");
            Object name = method.invoke(event.getView());
            if (name instanceof String text && !text.isBlank()) return text;
        } catch (ReflectiveOperationException | SecurityException ignored) { }
        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType().isAir()) result = top.getItem(2);
        if (result != null && result.hasItemMeta()) {
            ItemMeta meta = result.getItemMeta();
            if (meta.hasDisplayName()) return meta.getDisplayName();
        }
        return null;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || !owns(event.getView().getTopInventory())) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        PromptHolder holder = current(player, event.getInventory());
        if (holder == null) return;
        if (prompts.remove(player.getUniqueId(), holder)) {
            release(holder);
            if (holder.submitted) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) holder.cancelled.run();
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PromptHolder holder = prompts.remove(event.getPlayer().getUniqueId());
        if (holder != null) release(holder);
    }

    private static void release(PromptHolder holder) {
        if (holder.inventory == null) return;
        // Real anvil views return their input items to the player when they close.
        // These papers are UI controls and must be removed before that happens.
        holder.inventory.clear();
        OWNED.remove(holder.inventory);
    }

    private static final class PromptHolder implements InventoryHolder {
        private final UUID owner;
        private final String initial;
        private final Consumer<String> accepted;
        private final Runnable cancelled;
        private Inventory inventory;
        private boolean submitted;

        private PromptHolder(UUID owner, String initial, Consumer<String> accepted, Runnable cancelled) {
            this.owner = owner;
            this.initial = initial;
            this.accepted = accepted;
            this.cancelled = cancelled;
        }

        @Override public Inventory getInventory() { return inventory; }
    }
}
