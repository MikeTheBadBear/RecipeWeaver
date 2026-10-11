package dev.recipeweaver.core;

import java.util.logging.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class RecipeWeaverCommand implements CommandExecutor, TabCompleter {
    private final RecipeWeaverPlugin plugin;
    private final Map<String, Long> lockdownConfirmations = new ConcurrentHashMap<>();

    RecipeWeaverCommand(RecipeWeaverPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("recipes")) return openRecipes(sender);
        if (args.length == 0) {
            if (sender instanceof Player player) {
                if (!allowed(sender, "recipeweaver.studio")) return true;
                plugin.menus().openHome(player);
            } else {
                sender.sendMessage("RecipeWeaver " + plugin.getDescription().getVersion()
                        + ": /rw info, create, settings, table, reload, backup, backups, restore, lockdown, give");
            }
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "studio" -> {
                if (!allowed(sender, "recipeweaver.studio")) return true;
                if (sender instanceof Player player) plugin.menus().openHome(player);
                else sender.sendMessage("The studio requires an in-game player.");
            }
            case "create" -> {
                if (!allowed(sender, "recipeweaver.studio")) return true;
                if (sender instanceof Player player) {
                    plugin.menus().openHome(player);
                    sender.sendMessage(ChatColor.AQUA + "Choose a recipe type, set its ingredients and result,"
                            + " then click Save custom recipe in the editor.");
                } else sender.sendMessage("Recipe creation requires an in-game player.");
            }
            case "recipes" -> openRecipes(sender);
            case "table" -> giveTable(sender, args);
            case "settings" -> {
                if (!allowed(sender, "recipeweaver.admin")) return true;
                if (sender instanceof Player player) plugin.menus().openSettings(player);
                else sender.sendMessage("The settings menu requires an in-game player.");
            }
            case "info" -> {
                sender.sendMessage(ChatColor.AQUA + "RecipeWeaver "
                        + plugin.getDescription().getVersion() + ChatColor.GRAY + " | "
                        + plugin.recipes().size() + " workbench, " + plugin.stationRecipes().size()
                        + " station, " + plugin.gridRecipes().size() + " grid | " + plugin.platform().platformName() + " "
                        + plugin.platform().minecraftVersion() + " | lockdown=" + plugin.lockedDown());
                if (sender.hasPermission("recipeweaver.admin")) {
                    sender.sendMessage(ChatColor.GRAY + "Startup read: " + plugin.sqlReadPolicy()
                            + " | loaded from " + (plugin.startupSqlSource() == dev.recipeweaver.api.SqlMirrorMode.NONE
                            ? "LOCAL" : plugin.startupSqlSource()));
                    sender.sendMessage(ChatColor.GRAY + "Last reload loaded from "
                            + (plugin.lastReloadSqlSource() == dev.recipeweaver.api.SqlMirrorMode.NONE
                            ? "LOCAL" : plugin.lastReloadSqlSource()));
                }
            }
            case "reload" -> {
                if (!allowed(sender, "recipeweaver.admin")) return true;
                sender.sendMessage(ChatColor.YELLOW + "Reloading RecipeWeaver data...");
                plugin.reloadDataAsync().whenComplete((ignored, failure) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (sender instanceof Player player && !player.isOnline()) return;
                        if (failure == null) sender.sendMessage(ChatColor.GREEN + "RecipeWeaver data reloaded.");
                        else sender.sendMessage(ChatColor.RED + "Reload failed: " + failure.getMessage());
                    });
                });
            }
            case "lockdown" -> {
                if (!allowed(sender, "recipeweaver.admin")) return true;
                long now = System.currentTimeMillis();
                Long previous = lockdownConfirmations.get(sender.getName());
                if (previous == null || now - previous > 30_000L) {
                    lockdownConfirmations.put(sender.getName(), now);
                    sender.sendMessage(ChatColor.YELLOW + "Run /rw lockdown again within 30 seconds to confirm.");
                    return true;
                }
                lockdownConfirmations.remove(sender.getName());
                boolean enabled = !plugin.lockedDown();
                plugin.setLockdownAsync(enabled).whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        plugin.logAsync(Level.WARNING, sender.getName() + " set lockdown=" + enabled);
                        if (!(sender instanceof Player player) || player.isOnline()) {
                            sender.sendMessage(ChatColor.GREEN + "Recipe lockdown is now " + enabled);
                        }
                    } else {
                        plugin.logAsync(Level.WARNING, "Lockdown change failed: " + failure);
                        if (!(sender instanceof Player player) || player.isOnline()) {
                            sender.sendMessage(ChatColor.RED + "Lockdown change failed: " + failure.getMessage());
                        }
                    }
                });
            }
            case "backup" -> backup(sender);
            case "backups" -> listBackups(sender);
            case "restore" -> restore(sender, args);
            case "give" -> give(sender, args);
            default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand. Use /rw info for available commands.");
        }
        return true;
    }

    private boolean openRecipes(CommandSender sender) {
        if (!allowed(sender, "recipeweaver.recipes")) return true;
        if (sender instanceof Player player) plugin.menus().openRecipeBook(player, 0);
        else sender.sendMessage("The recipe book requires an in-game player.");
        return true;
    }

    private void backup(CommandSender sender) {
        if (!allowed(sender, "recipeweaver.admin")) return;
        sender.sendMessage(ChatColor.YELLOW + "Creating RecipeWeaver backup...");
        plugin.createBackupAsync().whenComplete((name, failure) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (sender instanceof Player player && !player.isOnline()) return;
                    if (failure == null) sender.sendMessage(ChatColor.GREEN + "Backup created: " + name);
                    else sender.sendMessage(ChatColor.RED + "Backup failed: " + failure.getMessage());
                }));
    }

    private void listBackups(CommandSender sender) {
        if (!allowed(sender, "recipeweaver.admin")) return;
        sender.sendMessage(ChatColor.YELLOW + "Listing RecipeWeaver backups...");
        plugin.listBackupsAsync().whenComplete((names, failure) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (sender instanceof Player player && !player.isOnline()) return;
                if (failure != null) {
                    sender.sendMessage(ChatColor.RED + "Could not list backups: " + failure.getMessage());
                    return;
                }
                sender.sendMessage(ChatColor.AQUA + "RecipeWeaver backups: " + names.size());
                names.stream().limit(10).forEach(name -> sender.sendMessage(ChatColor.GRAY + name));
                if (names.size() > 10) sender.sendMessage(ChatColor.GRAY + "Showing the latest 10.");
                sender.sendMessage(ChatColor.YELLOW + "Restore: /rw restore <backup-name> confirm");
            });
        });
    }

    private void restore(CommandSender sender, String[] args) {
        if (!allowed(sender, "recipeweaver.admin")) return;
        if (args.length != 3 || !args[2].equalsIgnoreCase("confirm")) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /rw restore <backup-name> confirm");
            sender.sendMessage(ChatColor.YELLOW + "Current RecipeWeaver data is backed up automatically first.");
            return;
        }
        sender.sendMessage(ChatColor.YELLOW + "Restoring RecipeWeaver backup...");
        plugin.restoreDataAsync(args[1]).whenComplete((previous, failure) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (failure == null) {
                    plugin.logAsync(Level.WARNING, sender.getName() + " restored " + args[1]
                            + "; previous data saved as " + previous);
                    if (!(sender instanceof Player player) || player.isOnline()) {
                        sender.sendMessage(ChatColor.GREEN + "Restored " + args[1]
                                + ". Previous data: " + previous);
                    }
                } else {
                    plugin.logAsync(Level.WARNING, "Restore failed: " + failure);
                    if (!(sender instanceof Player player) || player.isOnline()) {
                        sender.sendMessage(ChatColor.RED + "Restore failed: " + failure.getMessage());
                    }
                }
            });
        });
    }

    private void giveTable(CommandSender sender, String[] args) {
        if (!allowed(sender, "recipeweaver.admin")) return;
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage("Usage: /rw table <advanced|elite> [player]");
            return;
        }
        dev.recipeweaver.api.GridTier tier;
        try { tier = dev.recipeweaver.api.GridTier.valueOf(args[1].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) {
            sender.sendMessage(ChatColor.RED + "Use advanced or elite.");
            return;
        }
        Player target = args.length == 3 ? Bukkit.getPlayerExact(args[2])
                : (sender instanceof Player player ? player : null);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Specify an online player.");
            return;
        }
        target.getInventory().addItem(plugin.tableListener().item(tier)).values().forEach(leftover ->
                target.getWorld().dropItemNaturally(target.getLocation(), leftover));
        sender.sendMessage(ChatColor.GREEN + "Gave a " + tier + " crafting table to " + target.getName());
    }

    private void give(CommandSender sender, String[] args) {
        if (!allowed(sender, "recipeweaver.give")) return;
        if (args.length < 3) {
            sender.sendMessage("Usage: /rw give <player> <item-id>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player is not online.");
            return;
        }
        NamespacedKey key = NamespacedKey.fromString(args[2], plugin);
        if (key == null) {
            sender.sendMessage(ChatColor.RED + "Invalid item ID.");
            return;
        }
        ItemStack item = plugin.resolveItem(key).orElse(null);
        if (item == null) {
            sender.sendMessage(ChatColor.RED + "Unknown item: " + key);
            return;
        }
        target.getInventory().addItem(item).values().forEach(leftover ->
                target.getWorld().dropItemNaturally(target.getLocation(), leftover));
        sender.sendMessage(ChatColor.GREEN + "Gave " + key + " to " + target.getName());
    }

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        sender.sendMessage(ChatColor.RED + "Missing permission: " + permission);
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("recipes", "info"));
            if (sender.hasPermission("recipeweaver.studio")) options.addAll(List.of("studio", "create"));
            if (sender.hasPermission("recipeweaver.admin")) options.addAll(List.of("settings", "table", "reload", "lockdown", "backup", "backups", "restore"));
            if (sender.hasPermission("recipeweaver.give")) options.add("give");
            return options.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("table")
                && sender.hasPermission("recipeweaver.admin")) {
            return List.of("advanced", "elite").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("table")
                && sender.hasPermission("recipeweaver.admin")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("restore")
                && sender.hasPermission("recipeweaver.admin")) {
            return plugin.backups().cachedBackups().stream()
                    .filter(name -> name.startsWith(args[1])).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("restore")) {
            return "confirm".startsWith(args[2].toLowerCase(Locale.ROOT)) ? List.of("confirm") : List.of();
        }
        return List.of();
    }
}
