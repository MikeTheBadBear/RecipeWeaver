package dev.recipeweaver.core;

import java.util.logging.Level;
import dev.recipeweaver.api.GridTier;
import java.io.IOException;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Placeable tables are ordinary crafting-table blocks tracked by world and block position. */
final class TableListener implements Listener {
    private final RecipeWeaverPlugin plugin;
    private final NamespacedKey tierKey;
    private int pendingBreakCleanups;

    boolean pendingBreakCleanup() { return pendingBreakCleanups > 0; }

    TableListener(RecipeWeaverPlugin plugin) {
        this.plugin = plugin;
        tierKey = new NamespacedKey(plugin, "table_tier");
    }

    ItemStack item(GridTier tier) {
        ItemStack item = new ItemStack(Material.CRAFTING_TABLE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + (tier == GridTier.ADVANCED ? "Advanced" : "Elite") + " Crafting Table");
        meta.setLore(List.of(ChatColor.GRAY + "Place and right-click to craft with RecipeWeaver"));
        meta.getPersistentDataContainer().set(tierKey, PersistentDataType.STRING, tier.name());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.CRAFTING_TABLE) return;
        ItemMeta meta = event.getItemInHand().getItemMeta();
        if (meta == null) return;
        String value = meta.getPersistentDataContainer().get(tierKey, PersistentDataType.STRING);
        if (value == null) return;
        if (plugin.storageSnapshotBusy()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.YELLOW + "Wait for the RecipeWeaver backup or reload to finish before placing this table.");
            return;
        }
        try {
            GridTier tier = GridTier.valueOf(value);
            Block block = event.getBlockPlaced();
            Player player = event.getPlayer();
            plugin.putTableAsync(block.getLocation(), tier).whenComplete((ignored, failure) -> {
                if (failure == null) {
                    // Another plugin may have cancelled placement after this HIGHEST-priority listener.
                    if (block.getType() != Material.CRAFTING_TABLE) {
                        try {
                            plugin.removeTableAsync(block.getLocation()).whenComplete((removed, cleanupFailure) -> {
                                if (cleanupFailure != null) plugin.logAsync(Level.SEVERE,
                                        "Could not remove cancelled table placement at "
                                                + block.getLocation() + ": " + cleanupFailure);
                            });
                        } catch (IOException ex) {
                            plugin.logAsync(Level.SEVERE, "Could not remove cancelled table placement at "
                                    + block.getLocation() + ": " + ex);
                        }
                    }
                    return;
                }
                plugin.logAsync(Level.SEVERE, "Could not save placed table at "
                        + block.getLocation() + ": " + failure);
                if (block.getType() == Material.CRAFTING_TABLE) {
                    block.setType(Material.AIR);
                    if (player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
                        ItemStack returned = item(tier);
                        if (player.isOnline()) {
                            player.getInventory().addItem(returned).values().forEach(leftover ->
                                    block.getWorld().dropItemNaturally(block.getLocation(), leftover));
                        } else block.getWorld().dropItemNaturally(block.getLocation(), returned);
                    }
                }
                if (player.isOnline()) player.sendMessage(ChatColor.RED
                        + "RecipeWeaver table placement could not be saved: " + failure.getMessage());
            });
        } catch (IllegalArgumentException | IOException ex) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Could not place RecipeWeaver table: " + ex.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        GridTier tier = plugin.tables().get(block.getLocation());
        if (tier == null) return;
        if (plugin.storageSnapshotBusy() || plugin.tables().pendingWrite()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.YELLOW
                    + "Wait for the RecipeWeaver table save, backup, or reload to finish before breaking this table.");
            return;
        }
        event.setDropItems(false);
        pendingBreakCleanups++;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (block.getType() == Material.CRAFTING_TABLE) {
                pendingBreakCleanups--;
                return;
            }
            try {
                plugin.removeTableAsync(block.getLocation()).whenComplete((removed, failure) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            if (failure != null) {
                                plugin.logAsync(Level.SEVERE, "Could not update broken table at "
                                        + block.getLocation() + ": " + failure);
                                if (block.getType().isAir()) block.setType(Material.CRAFTING_TABLE);
                                return;
                            }
                            if (removed && event.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) {
                                block.getWorld().dropItemNaturally(
                                        block.getLocation().add(0.5, 0.5, 0.5), item(tier));
                            }
                        } finally {
                            pendingBreakCleanups--;
                        }
                    });
                });
            } catch (IOException | RuntimeException ex) {
                plugin.logAsync(Level.SEVERE, "Could not update broken table at "
                        + block.getLocation() + ": " + ex);
                if (block.getType().isAir()) block.setType(Material.CRAFTING_TABLE);
                pendingBreakCleanups--;
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> plugin.tables().get(block.getLocation()) != null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> plugin.tables().get(block.getLocation()) != null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> plugin.tables().get(block.getLocation()) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> plugin.tables().get(block.getLocation()) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        GridTier tier = plugin.tables().get(block.getLocation());
        if (tier == null) return;
        if (block.getType() != Material.CRAFTING_TABLE) {
            if (plugin.storageSnapshotBusy() || plugin.tables().pendingWrite()) return;
            try {
                plugin.removeTableAsync(block.getLocation()).whenComplete((removed, failure) -> {
                    if (failure != null) plugin.logAsync(Level.WARNING,
                            "Could not remove stale table: " + failure);
                });
            } catch (IOException ex) {
                plugin.logAsync(Level.WARNING, "Could not remove stale table: " + ex);
            }
            return;
        }
        if (plugin.tables().pendingWrite()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.YELLOW + "Wait for this table to finish saving.");
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setCancelled(true);
        Player player = event.getPlayer();
        plugin.gridMenus().openCraft(player, tier, 3);
    }
}