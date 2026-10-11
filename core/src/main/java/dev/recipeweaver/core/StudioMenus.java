package dev.recipeweaver.core;

import java.util.logging.Level;
import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.MapLocationLabel;
import dev.recipeweaver.api.BackupFormat;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.WeatherCondition;
import dev.recipeweaver.api.StationRecipeDefinition;
import dev.recipeweaver.api.SqlMirrorMode;
import dev.recipeweaver.api.SqlReadPolicy;
import dev.recipeweaver.api.SqlSourceStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.AxolotlBucketMeta;
import org.bukkit.inventory.meta.BlockDataMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.KnowledgeBookMeta;
import org.bukkit.inventory.meta.MusicInstrumentMeta;
import org.bukkit.inventory.meta.OminousBottleMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.SuspiciousStewMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.TropicalFishBucketMeta;
import org.bukkit.inventory.meta.WritableBookMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.inventory.meta.components.ToolComponent;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;

final class StudioMenus implements Listener {
    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private final RecipeWeaverPlugin plugin;
    private final TextPrompt textPrompt;
    private final BookPrompt bookPrompt;
    private final Map<UUID, RecipeDraft> recipeDrafts = new HashMap<>();
    private final Set<UUID> pendingRecipeWrites = new HashSet<>();
    private final Map<UUID, ItemStack> itemDrafts = new HashMap<>();
    private final Map<UUID, NamespacedKey> itemDraftIds = new HashMap<>();
    private final Set<UUID> pendingItemWrites = new HashSet<>();
    private final Map<UUID, ItemStack[]> testMatrices = new HashMap<>();
    private final Map<UUID, String> bookQueries = new HashMap<>();
    private final Map<UUID, String> bookGroups = new HashMap<>();
    private final Map<UUID, BookCategory> bookCategories = new HashMap<>();
    private final Map<UUID, BookSort> bookSorts = new HashMap<>();
    private final Map<UUID, BookDiscovery> bookDiscoveries = new HashMap<>();
    private final Map<UUID, Map<SqlMirrorMode, SqlSourceStatus>> sqlAudits = new HashMap<>();
    private final SqlAuditTickets sqlAuditTickets = new SqlAuditTickets();

    StudioMenus(RecipeWeaverPlugin plugin) {
        this.plugin = plugin;
        textPrompt = new TextPrompt(plugin);
        Bukkit.getPluginManager().registerEvents(textPrompt, plugin);
        bookPrompt = plugin.bookPrompt();
    }

    void openHome(Player player) {
        bookPrompt.cancel(player);
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.HOME, 0, null);
        Inventory inventory = create(holder, 27, "RecipeWeaver Studio");
        inventory.setItem(10, icon(Material.CRAFTING_TABLE, "Create shaped recipe",
                "Use a 3x3 pattern", "Set ingredients and result, then click Save recipe"));
        inventory.setItem(12, icon(Material.PAPER, "Create shapeless recipe",
                "Any arrangement", "Set ingredients and result, then click Save recipe"));
        inventory.setItem(14, icon(Material.DIAMOND, "Custom item editor", "Hold the item to edit"));
        inventory.setItem(16, icon(Material.BOOK, "Recipe book", "Browse saved recipes"));
        if (player.hasPermission("recipeweaver.admin")) {
            inventory.setItem(18, icon(Material.COMPARATOR, "Global settings", "Edit RecipeWeaver settings"));
        }
        inventory.setItem(20, icon(Material.NETHER_STAR, "Create table recipe",
                "Advanced and elite crafting grids"));
        inventory.setItem(22, icon(Material.FURNACE, "Create station recipe",
                "Furnace, smithing, anvil, and more"));
        inventory.setItem(24, icon(Material.CHEST, "Saved custom items", "Browse, edit, or delete saved items"));
        player.openInventory(inventory);
    }

    void openSettings(Player player) {
        if (!player.hasPermission("recipeweaver.admin")) return;
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.SETTINGS, 0, null);
        holder.settingsPage = new SettingsPageSnapshot(plugin.bookItemEnabled(), plugin.lockedDown(),
                plugin.backupFormat());
        Inventory inventory = create(holder, 27, "RecipeWeaver Settings");
        inventory.setItem(9, icon(plugin.bookItemEnabled() ? Material.ENCHANTED_BOOK : Material.BOOK,
                "Right-click book item: " + plugin.bookItemEnabled(), "Click to toggle the book item"));
        inventory.setItem(11, icon(Material.CHEST, "Create backup", "Snapshot recipes, items, and settings"));
        inventory.setItem(13, icon(plugin.lockedDown() ? Material.REDSTONE_BLOCK : Material.EMERALD_BLOCK,
                "Recipe lockdown: " + plugin.lockedDown(), "Click to toggle registered recipes"));
        inventory.setItem(15, icon(Material.BOOK, "Browse backups", "Select a backup to restore"));
        inventory.setItem(17, icon(Material.HOPPER, "Backups to keep: "
                + (plugin.backupRetention() == 0 ? "Unlimited" : plugin.backupRetention()),
                "Click to enter a number; 0 keeps all backups"));
        inventory.setItem(19, icon(Material.BARREL, "Backup format: " + plugin.backupFormat(),
                "Click to switch ZIP / DIRECTORY"));
        inventory.setItem(21, icon(Material.CRAFTING_TABLE,
                "Disabled vanilla recipes: " + plugin.disabledVanillaRecipes().size(),
                "Click to manage Minecraft recipe IDs",
                "Removing one may affect player recipe discoveries"));
        inventory.setItem(22, icon(Material.ARROW, "Back to studio"));
        inventory.setItem(23, icon(Material.BOOKSHELF, "Local SQL snapshots",
                "Export to SQLite or H2", "Prepare a saved snapshot for restore"));
        inventory.setItem(24, icon(Material.ENDER_CHEST, "Remote SQL connection",
                "Configure MySQL, MariaDB, PostgreSQL, SQL Server, or Oracle"));
        inventory.setItem(25, icon(Material.HOPPER, "SQL mirror order",
                "Local files remain the write source",
                "SQL reads: " + plugin.sqlReadPolicy(),
                "Click to reorder SQL targets and choose startup priority"));
        player.openInventory(inventory);
    }

    private void clickSettings(Player player, MenuHolder holder, int slot) {
        if (!player.hasPermission("recipeweaver.admin")) return;
        if (slot == 22) { openHome(player); return; }
        if (holder.settingsPage == null || !holder.settingsPage.stillCurrentFor(slot,
                plugin.bookItemEnabled(), plugin.lockedDown(), plugin.backupFormat())) {
            player.sendMessage(ChatColor.YELLOW + "Settings changed since this page opened. Review the current values.");
            openSettings(player);
            return;
        }
        if (slot == 9) {
            boolean enabled = !plugin.bookItemEnabled();
            finishSettingsWrite(player, plugin.setBookItemEnabledAsync(enabled),
                    "Right-click recipe book item is now " + (enabled ? "enabled." : "disabled."));
            return;
        } else if (slot == 11) {
            MenuSession session = MenuSession.capture(player);
            player.sendMessage(ChatColor.YELLOW + "Creating RecipeWeaver backup...");
            plugin.createBackupAsync().whenComplete((name, failure) -> {
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (failure == null) player.sendMessage(ChatColor.GREEN + "Backup created: " + name);
                    else player.sendMessage(ChatColor.RED + "Backup failed: " + failure.getMessage());
                    if (session.stillViewing(player)) openSettings(player);
                });
            });
            return;
        } else if (slot == 13) {
            boolean enabled = !plugin.lockedDown();
            finishSettingsWrite(player, plugin.setLockdownAsync(enabled),
                    "Recipe lockdown is now " + enabled);
            return;
        } else if (slot == 15) {
            openBackups(player, 0);
            return;
        } else if (slot == 17) {
            textPrompt.open(player, "Backups to keep", Integer.toString(plugin.backupRetention()),
                    value -> {
                try {
                    int count = Integer.parseInt(value);
                    finishSettingsWrite(player, plugin.setBackupRetentionAsync(count),
                            "Backup retention saved. It applies after the next backup.");
                } catch (IllegalArgumentException ex) {
                    player.sendMessage(ChatColor.RED + "Backup retention must be 0 through 10000.");
                    openSettings(player);
                }
            }, () -> openSettings(player));
            return;
        } else if (slot == 19) {
            BackupFormat next = plugin.backupFormat() == BackupFormat.ZIP
                    ? BackupFormat.DIRECTORY : BackupFormat.ZIP;
            finishSettingsWrite(player, plugin.setBackupFormatAsync(next),
                    "Future backups will use " + next + " format.");
            return;
        } else if (slot == 21) {
            openVanillaRecipes(player, 0);
            return;
        } else if (slot == 23) {
            openLocalSql(player);
            return;
        } else if (slot == 24) {
            openRemoteSql(player);
            return;
        } else if (slot == 25) {
            openSqlOrder(player);
            return;
        } else {
            return;
        }
    }

    private void openLocalSql(Player player) {
        if (!player.hasPermission("recipeweaver.admin")) return;
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.LOCAL_SQL, 0, null);
        holder.sqlOrderPage = new SqlOrderPageSnapshot(plugin.sqlMirrorModes(),
                plugin.remoteSqlProfile());
        Inventory inventory = create(holder, 27, "RecipeWeaver SQL Snapshots");
        Map<SqlMirrorMode, String> failures = plugin.sqlMirrorFailures();
        inventory.setItem(4, icon(failures.isEmpty() ? Material.EMERALD : Material.REDSTONE,
                "SQL mirrors: " + plugin.sqlMirrorModes(),
                "Local files are authoritative", "Behind: " + failures.keySet()));
        inventory.setItem(10, icon(Material.CHEST, "Export to SQLite",
                "Save current RecipeWeaver data in SQLite"));
        inventory.setItem(11, icon(Material.WRITABLE_BOOK, "Restore from SQLite",
                "Prepare a regular backup for review"));
        inventory.setItem(15, icon(Material.CHEST, "Export to H2",
                "Save current RecipeWeaver data in H2"));
        inventory.setItem(16, icon(Material.WRITABLE_BOOK, "Restore from H2",
                "Prepare a regular backup for review"));
        inventory.setItem(18, icon(Material.BARRIER, "Disable local SQL mirrors",
                "Remove SQLite and H2; preserve any remote mirror"));
        inventory.setItem(19, icon(Material.COMPARATOR, "Toggle SQLite: "
                        + plugin.sqlMirrorModes().contains(SqlMirrorMode.SQLITE),
                "Copy live writes after the local commit"));
        inventory.setItem(20, icon(Material.COMPARATOR, "Toggle H2: "
                        + plugin.sqlMirrorModes().contains(SqlMirrorMode.H2),
                "Copy live writes after the local commit"));
        inventory.setItem(21, icon(Material.CLOCK, "Retry all selected mirrors",
                "Copies local data only if SQL has not changed",
                "Inspect sources to resolve a conflict"));
        inventory.setItem(22, icon(Material.ARROW, "Back to settings"));
        inventory.setItem(26, icon(Material.PAPER, "Local files remain authoritative",
                "Manual snapshots and live mirroring are separate"));
        player.openInventory(inventory);
    }

    private void openRemoteSql(Player player) {
        if (!player.hasPermission("recipeweaver.admin")) return;
        RemoteSqlProfile profile = plugin.remoteSqlProfile();
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.REMOTE_SQL, 0, null);
        holder.remoteSqlPage = new RemoteSqlPageSnapshot(profile, plugin.sqlMirrorModes());
        Inventory inventory = create(holder, 27, "RecipeWeaver Remote SQL");
        String failure = plugin.sqlMirrorFailures().get(profile.backend());
        boolean active = plugin.sqlMirrorModes().contains(profile.backend());
        inventory.setItem(4, icon(active && failure != null ? Material.REDSTONE_BLOCK
                        : active ? Material.EMERALD_BLOCK : Material.GRAY_DYE,
                "Live remote mirror: " + (active ? profile.backend() : "OFF"),
                active && failure != null ? failure : "Local files remain authoritative"));
        inventory.setItem(9, icon(Material.COMPARATOR, "Backend: " + profile.backend(),
                "Click to cycle five remote database types",
                "Changing backend clears username and password",
                "Disable the remote mirror before changing backend"));
        inventory.setItem(10, icon(Material.WRITABLE_BOOK, "JDBC URL",
                profile.jdbcUrl(), "Click to edit with a book"));
        inventory.setItem(11, icon(Material.NAME_TAG, "Username: "
                + (profile.username().isBlank() ? "<empty>" : profile.username()),
                "Click to edit with an anvil"));
        inventory.setItem(12, icon(Material.TRIPWIRE_HOOK, "Password: "
                + (profile.password().isEmpty() ? "<empty>" : "configured"),
                "Click to replace; existing value is never shown"));
        inventory.setItem(13, icon(Material.BARRIER, "Clear password",
                "Remove the saved SQL password"));
        inventory.setItem(14, icon(Material.BARRIER, "Clear username",
                "Use an empty SQL username"));
        inventory.setItem(15, icon(Material.ENDER_EYE, "Test save/read/delete",
                "Write, read, then remove a temporary RecipeWeaver document"));
        inventory.setItem(16, icon(Material.CHEST, "Export current data",
                "Replace remote RecipeWeaver documents, then read them back"));
        inventory.setItem(17, icon(Material.WRITABLE_BOOK, "Prepare remote restore",
                "Read remote documents into a reviewable backup"));
        inventory.setItem(19, icon(Material.COMPARATOR, "Mirror live writes to " + profile.backend(),
                "Synchronize local data to this remote database"));
        inventory.setItem(20, icon(Material.REDSTONE_BLOCK, "Disable remote mirror",
                "Keep the selected local SQL mirrors"));
        inventory.setItem(21, icon(Material.CLOCK, "Retry remote mirror only",
                "Copies local data only if SQL has not changed"));
        inventory.setItem(22, icon(Material.ARROW, "Back to settings"));
        inventory.setItem(26, icon(Material.PAPER, "Connection settings are saved locally",
                "Passwords are not displayed in this menu"));
        player.openInventory(inventory);
    }

    private void openSqlOrder(Player player) {
        if (!player.hasPermission("recipeweaver.admin")) return;
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.SQL_ORDER, 0, null);
        Inventory inventory = create(holder, 45, "RecipeWeaver SQL Mirror Order");
        boolean preferSql = plugin.sqlReadPolicy() == SqlReadPolicy.PREFER_SQL_AT_STARTUP
                || plugin.sqlReadPolicy() == SqlReadPolicy.PREFER_SQL_AT_STARTUP_AND_RELOAD;
        inventory.setItem(4, icon(Material.CHEST, preferSql
                        ? "Local files: startup fallback" : "Local files: source 1",
                preferSql ? "First valid selected SQL source takes priority at startup"
                        : "Authoritative; SQL targets follow in this order"));
        List<SqlMirrorMode> modes = plugin.sqlMirrorModes();
        holder.sqlOrderPage = new SqlOrderPageSnapshot(modes, plugin.remoteSqlProfile());
        Map<SqlMirrorMode, SqlSourceStatus> audited = sqlAudits.getOrDefault(
                player.getUniqueId(), Map.of());
        int[] positions = {10, 13, 16};
        for (int index = 0; index < modes.size(); index++) {
            SqlMirrorMode mode = modes.get(index);
            String failure = plugin.sqlMirrorFailures().get(mode);
            SqlSourceStatus status = audited.get(mode);
            inventory.setItem(positions[index], icon(failure == null ? Material.EMERALD : Material.REDSTONE,
                    (preferSql ? "Selected source " + (index + 1) : "Target " + (index + 2))
                            + ": " + mode,
                    failure == null ? "No reported propagation failure" : failure,
                    status == null ? "Inspect sources before choosing this copy"
                            : status.failure() != null ? status.failure()
                            : !status.comparisonAvailable()
                            ? "Audited: local files unreadable; comparison unavailable"
                            : status.current() ? "Audited: matches local files"
                            : "Audited: differs from local files",
                    "Left click: prepare this inspected SQL copy",
                    "Right click: keep local files after confirmation"));
            inventory.setItem(positions[index] + 9, icon(Material.ARROW, "Move " + mode + " earlier"));
            inventory.setItem(positions[index] + 18, icon(Material.ARROW, "Move " + mode + " later"));
        }
        inventory.setItem(37, icon(Material.SPYGLASS, "Inspect selected SQL sources",
                "Read each source and compare it with local files",
                "Report missing, changed, and extra documents"));
        inventory.setItem(38, icon(Material.WRITABLE_BOOK, "Prepare first readable SQL source",
                "Try selected sources in their displayed order",
                "Create a reviewable backup; restore still needs confirmation"));
        inventory.setItem(36, icon(Material.COMPASS,
                "This startup loaded: " + (plugin.startupSqlSource() == SqlMirrorMode.NONE
                        ? "LOCAL" : plugin.startupSqlSource()),
                "Last reload loaded: " + (plugin.lastReloadSqlSource() == SqlMirrorMode.NONE
                        ? "LOCAL" : plugin.lastReloadSqlSource())));
        inventory.setItem(39, icon(modes.isEmpty() ? Material.BARRIER
                        : plugin.sqlReadPolicy() == SqlReadPolicy.LOCAL_ONLY
                        ? Material.GRAY_DYE : Material.EMERALD_BLOCK,
                "SQL read policy: " + plugin.sqlReadPolicy(),
                modes.isEmpty() ? "Select an SQL source to enable reads" : "Click to cycle",
                "Recovery: SQL only if local data is invalid",
                "Prefer SQL: first valid selected source replaces local files",
                "Startup + reload: selected SQL also takes priority on /rw reload",
                "A backup preserves the previous local data",
                "Click to cycle; local-only is the default"));
        inventory.setItem(40, icon(Material.ARROW, "Back to settings"));
        player.openInventory(inventory);
    }

    private void clickSqlOrder(Player player, MenuHolder holder, int slot, ClickType click) {
        if (!player.hasPermission("recipeweaver.admin")) return;
        if (slot == 40) { openSettings(player); return; }
        if (holder.sqlOrderPage == null
                || !holder.sqlOrderPage.stillCurrent(plugin.sqlMirrorModes(),
                        plugin.remoteSqlProfile())) {
            clearSqlAudit(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + "SQL sources or connection changed; review the refreshed order.");
            openSqlOrder(player);
            return;
        }
        MenuSession session = MenuSession.capture(player);
        if (slot == 37) {
            UUID playerId = player.getUniqueId();
            SqlAuditTickets.Request request = sqlAuditTickets.begin(playerId,
                    plugin.sqlMirrorModes(), plugin.remoteSqlProfile());
            player.sendMessage(ChatColor.YELLOW + "Inspecting selected SQL sources...");
            plugin.auditSqlSourcesAsync().whenComplete((statuses, failure) -> {
                if (!plugin.isEnabled() || !player.isOnline()) {
                    clearSqlAudit(playerId);
                    return;
                }
                if (!sqlAuditTickets.current(playerId, request, plugin.sqlMirrorModes(),
                        plugin.remoteSqlProfile())) {
                    if (session.stillViewing(player)) openSqlOrder(player);
                    return;
                }
                if (failure != null) {
                    clearSqlAudit(playerId);
                    player.sendMessage(ChatColor.RED + "SQL inspection failed: " + sqlError(failure));
                } else {
                    Map<SqlMirrorMode, SqlSourceStatus> inspected = new HashMap<>();
                    for (SqlSourceStatus status : statuses) inspected.put(status.backend(), status);
                    sqlAudits.put(player.getUniqueId(), Map.copyOf(inspected));
                    if (statuses.isEmpty()) player.sendMessage(ChatColor.YELLOW
                            + "No SQL source is selected.");
                    for (SqlSourceStatus status : statuses) {
                        if (status.failure() != null) player.sendMessage(ChatColor.RED
                                + status.failure());
                        else if (!status.comparisonAvailable()) player.sendMessage(ChatColor.YELLOW
                                + status.backend().name() + ": " + status.documents()
                                + " documents; local files unreadable, comparison unavailable; SHA-256 "
                                + status.sha256().substring(0, 12));
                        else player.sendMessage((status.current() ? ChatColor.GREEN : ChatColor.YELLOW)
                                + status.backend().name() + ": " + status.documents() + " documents, "
                                + status.missing() + " missing, " + status.changed() + " changed, "
                                + status.extra() + " extra; SHA-256 " + status.sha256().substring(0, 12));
                    }
                }
                if (session.stillViewing(player)) openSqlOrder(player);
            });
            return;
        }
        int[] sourceSlots = {10, 13, 16};
        for (int index = 0; index < sourceSlots.length; index++) {
            if (slot != sourceSlots[index]) continue;
            SqlMirrorMode mode = holder.sqlOrderPage.sourceAt(index);
            if (mode == null) return;
            SqlSourceStatus status = sqlAudits.getOrDefault(player.getUniqueId(), Map.of()).get(mode);
            if (status == null || status.sha256() == null) {
                player.sendMessage(ChatColor.YELLOW + "Inspect this SQL source first.");
                return;
            }
            if (click.isRightClick()) {
                openSqlLocalConfirm(player, mode, status);
                return;
            }
            if (status.failure() != null) {
                player.sendMessage(ChatColor.RED + status.failure()
                        + "; choose local files or repair the SQL copy before restoring it.");
                return;
            }
            player.sendMessage(ChatColor.YELLOW + "Preparing inspected " + mode + " source...");
            plugin.prepareSqlSourceRestoreAsync(mode, status.sha256()).whenComplete((prepared, failure) -> {
                if (!player.isOnline()) return;
                if (failure != null) {
                    clearSqlAudit(player.getUniqueId());
                    player.sendMessage(ChatColor.RED + "SQL source was not prepared: "
                            + sqlError(failure));
                    if (session.stillViewing(player)) openSqlOrder(player);
                } else {
                    player.sendMessage(ChatColor.GREEN + prepared.backend().name()
                            + " was prepared as " + prepared.backupName());
                    if (session.stillViewing(player))
                        openRestoreConfirm(player, prepared.backupName());
                }
            });
            return;
        }
        if (slot == 38) {
            player.sendMessage(ChatColor.YELLOW + "Preparing the first readable SQL source...");
            plugin.prepareOrderedSqlRestoreAsync().whenComplete((prepared, failure) -> {
                if (!player.isOnline()) return;
                if (failure != null) {
                    player.sendMessage(ChatColor.RED + "No SQL source was prepared: "
                            + sqlError(failure));
                    if (session.stillViewing(player)) openSqlOrder(player);
                } else {
                    player.sendMessage(ChatColor.GREEN + prepared.backend().name()
                            + " was prepared as " + prepared.backupName());
                    if (session.stillViewing(player))
                        openRestoreConfirm(player, prepared.backupName());
                }
            });
            return;
        }
        if (slot == 39) {
            SqlReadPolicy next = nextSqlReadPolicy(plugin.sqlReadPolicy(),
                    !plugin.sqlMirrorModes().isEmpty());
            if (next == null) {
                player.sendMessage(ChatColor.RED + "Select an SQL source before enabling SQL reads.");
                return;
            }
            plugin.setSqlReadPolicyAsync(next).whenComplete((ignored, failure) -> {
                if (failure == null) clearSqlAudits();
                if (!player.isOnline()) return;
                if (failure != null) player.sendMessage(ChatColor.RED
                        + "SQL read setting failed: " + sqlError(failure));
                else {
                    player.sendMessage(ChatColor.GREEN + "SQL read policy: " + next);
                }
                if (session.stillViewing(player)) openSqlOrder(player);
            });
            return;
        }
        int[] up = {19, 22, 25};
        int[] down = {28, 31, 34};
        int index = -1;
        int direction = 0;
        for (int i = 0; i < up.length; i++) {
            if (slot == up[i]) { index = i; direction = -1; break; }
            if (slot == down[i]) { index = i; direction = 1; break; }
        }
        if (index < 0) return;
        List<SqlMirrorMode> current = holder.sqlOrderPage.sources();
        List<SqlMirrorMode> moved = SqlMirrorSelection.move(current, index, direction,
                plugin.remoteSqlProfile());
        if (moved.equals(current)) return;
        plugin.setSqlMirrorModesAsync(moved).whenComplete((ignored, failure) -> {
            if (failure == null) clearSqlAudits();
            if (!player.isOnline()) return;
            if (failure != null) player.sendMessage(ChatColor.RED
                    + "SQL order change failed: " + sqlError(failure));
            else {
                player.sendMessage(ChatColor.GREEN + "SQL mirrors: " + plugin.sqlMirrorModes());
            }
            if (session.stillViewing(player)) openSqlOrder(player);
        });
    }

    static SqlReadPolicy nextSqlReadPolicy(SqlReadPolicy current, boolean hasSqlSource) {
        if (!hasSqlSource) return null;
        return switch (current) {
            case LOCAL_ONLY -> SqlReadPolicy.RECOVER_ON_STARTUP;
            case RECOVER_ON_STARTUP -> SqlReadPolicy.PREFER_SQL_AT_STARTUP;
            case PREFER_SQL_AT_STARTUP -> SqlReadPolicy.PREFER_SQL_AT_STARTUP_AND_RELOAD;
            case PREFER_SQL_AT_STARTUP_AND_RELOAD -> SqlReadPolicy.LOCAL_ONLY;
        };
    }

    static String groupInput(String value) {
        String normalized = value.strip().replaceAll("\\s+", " ");
        return normalized.equalsIgnoreCase("none") ? "" : normalized;
    }

    private void clickRemoteSql(Player player, MenuHolder holder, int slot) {
        if (!player.hasPermission("recipeweaver.admin")) return;
        if (slot == 22) { openSettings(player); return; }
        if (holder.remoteSqlPage == null
                || !holder.remoteSqlPage.stillCurrent(plugin.remoteSqlProfile(),
                        plugin.sqlMirrorModes())) {
            clearSqlAudit(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + "SQL connection or sources changed; review the refreshed controls.");
            openRemoteSql(player);
            return;
        }
        MenuSession session = MenuSession.capture(player);
        RemoteSqlProfile profile = plugin.remoteSqlProfile();
        try {
            switch (slot) {
                case 9 -> {
                    SqlMirrorMode[] backends = {SqlMirrorMode.MYSQL, SqlMirrorMode.MARIADB,
                            SqlMirrorMode.POSTGRESQL, SqlMirrorMode.SQL_SERVER, SqlMirrorMode.ORACLE};
                    int next = (java.util.Arrays.asList(backends).indexOf(profile.backend()) + 1)
                            % backends.length;
                    finishRemoteSqlWrite(player, profile.withBackend(backends[next]));
                }
                case 10 -> bookPrompt.open(player, profile.jdbcUrl(), value -> {
                    try { finishRemoteSqlWrite(player, plugin.remoteSqlProfile().withUrl(value)); }
                    catch (IllegalArgumentException ex) { remoteInputError(player, ex); }
                }, () -> openRemoteSql(player));
                case 11 -> textPrompt.open(player, "SQL username", profile.username(), value -> {
                    try { finishRemoteSqlWrite(player, plugin.remoteSqlProfile().withUsername(value)); }
                    catch (IllegalArgumentException ex) { remoteInputError(player, ex); }
                }, () -> openRemoteSql(player));
                case 12 -> bookPrompt.open(player, "", value -> {
                    try { finishRemoteSqlWrite(player, plugin.remoteSqlProfile().withPassword(value)); }
                    catch (IllegalArgumentException ex) { remoteInputError(player, ex); }
                }, () -> openRemoteSql(player));
                case 13 -> finishRemoteSqlWrite(player, profile.withPassword(""));
                case 14 -> finishRemoteSqlWrite(player, profile.withUsername(""));
                case 15 -> {
                    player.sendMessage(ChatColor.YELLOW + "Testing remote SQL save, read, and cleanup...");
                    plugin.testRemoteSqlAsync().whenComplete((connected, failure) -> {
                        if (!player.isOnline()) return;
                        if (failure != null || !connected) player.sendMessage(ChatColor.RED
                                + "Remote SQL round trip failed: " + sqlError(failure));
                        else player.sendMessage(ChatColor.GREEN + "Remote SQL save/read/delete succeeded.");
                        if (session.stillViewing(player)) openRemoteSql(player);
                    });
                }
                case 16 -> {
                    player.sendMessage(ChatColor.YELLOW + "Exporting to remote SQL...");
                    plugin.exportRemoteSqlAsync().whenComplete((count, failure) -> {
                        if (failure == null) clearSqlAudits();
                        if (!player.isOnline()) return;
                        if (failure != null) player.sendMessage(ChatColor.RED
                                + "Remote SQL export failed: " + sqlError(failure));
                        else player.sendMessage(ChatColor.GREEN + "Saved and read back " + count
                                + " remote documents.");
                        if (session.stillViewing(player)) openRemoteSql(player);
                    });
                }
                case 17 -> {
                    player.sendMessage(ChatColor.YELLOW + "Reading remote SQL snapshot...");
                    plugin.prepareRemoteSqlRestoreAsync().whenComplete((name, failure) -> {
                        if (!player.isOnline()) return;
                        if (failure != null) {
                            player.sendMessage(ChatColor.RED + "Remote SQL import failed: "
                                    + sqlError(failure));
                            if (session.stillViewing(player)) openRemoteSql(player);
                        } else {
                            player.sendMessage(ChatColor.GREEN + "Remote snapshot prepared as " + name);
                            if (session.stillViewing(player)) openRestoreConfirm(player, name);
                        }
                    });
                }
                case 19 -> {
                    List<SqlMirrorMode> current = plugin.sqlMirrorModes();
                    if (!current.contains(profile.backend())) finishRemoteMirrorWrite(player,
                            plugin.setSqlMirrorModesAsync(SqlMirrorSelection.toggle(current,
                                    profile.backend(), profile)));
                }
                case 20 -> {
                    List<SqlMirrorMode> current = plugin.sqlMirrorModes();
                    if (current.contains(profile.backend())) finishRemoteMirrorWrite(player,
                            plugin.setSqlMirrorModesAsync(SqlMirrorSelection.toggle(current,
                                    profile.backend(), profile)));
                }
                case 21 -> {
                    if (!plugin.sqlMirrorModes().contains(profile.backend())) {
                        player.sendMessage(ChatColor.RED + "Enable a remote mirror first.");
                        return;
                    }
                    player.sendMessage(ChatColor.YELLOW + "Synchronizing remote SQL mirror...");
                    plugin.resyncSqlMirrorAsync(profile.backend()).whenComplete((synced, failure) -> {
                        if (failure == null && synced) clearSqlAudits();
                        if (!player.isOnline()) return;
                        if (failure != null || !synced) player.sendMessage(ChatColor.RED
                                + "Remote SQL mirror is still behind: " + sqlError(failure));
                        else player.sendMessage(ChatColor.GREEN + "Remote SQL mirror is current.");
                        if (session.stillViewing(player)) openRemoteSql(player);
                    });
                }
                default -> { }
            }
        } catch (IllegalArgumentException ex) {
            remoteInputError(player, ex);
        }
    }

    private void openSqlLocalConfirm(Player player, SqlMirrorMode mode, SqlSourceStatus status) {
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.SQL_LOCAL_CONFIRM,
                mode, status.sha256());
        Inventory inventory = create(holder, 27, "Confirm SQL Mirror Replacement");
        inventory.setItem(11, icon(Material.ARROW, "Cancel", "Return to SQL sources"));
        inventory.setItem(13, icon(Material.SPYGLASS, "Audited " + mode,
                status.documents() + " documents; SHA-256 " + status.sha256().substring(0, 12),
                "Missing: " + status.missing() + ", changed: " + status.changed()
                        + ", extra: " + status.extra(),
                status.failure() == null ? "Snapshot is valid" : status.failure()));
        inventory.setItem(15, icon(Material.REDSTONE_BLOCK, "Keep local files",
                "Replace this SQL copy with current local files",
                "Fails if the SQL copy changed since inspection"));
        player.openInventory(inventory);
    }

    private void clickSqlLocalConfirm(Player player, MenuHolder holder, int slot) {
        if (!player.hasPermission("recipeweaver.admin")) return;
        if (slot == 11) { openSqlOrder(player); return; }
        if (slot != 15 || holder.sqlMode == null || holder.sqlSha256 == null) return;
        MenuSession session = MenuSession.capture(player);
        SqlSourceStatus inspected = sqlAudits.getOrDefault(player.getUniqueId(), Map.of())
                .get(holder.sqlMode);
        if (inspected == null || !holder.sqlSha256.equals(inspected.sha256())) {
            player.sendMessage(ChatColor.YELLOW + "SQL source settings changed; inspect again.");
            openSqlOrder(player);
            return;
        }
        player.sendMessage(ChatColor.YELLOW + "Replacing inspected " + holder.sqlMode
                + " mirror from local files...");
        plugin.replaceSqlMirrorFromLocalAsync(holder.sqlMode, holder.sqlSha256)
                .whenComplete((synced, failure) -> {
                    if (failure == null && synced) clearSqlAudits();
                    else clearSqlAudit(player.getUniqueId());
                    if (!player.isOnline()) return;
                    if (failure != null || !synced) player.sendMessage(ChatColor.RED
                            + "SQL mirror was not replaced: " + sqlError(failure));
                    else player.sendMessage(ChatColor.GREEN + holder.sqlMode.name()
                            + " now matches local files.");
                    if (session.stillViewing(player)) openSqlOrder(player);
                });
    }

    private void finishRemoteSqlWrite(Player player, RemoteSqlProfile profile) {
        MenuSession session = MenuSession.capture(player);
        player.sendMessage(ChatColor.YELLOW + "Saving remote SQL settings...");
        plugin.setRemoteSqlProfileAsync(profile).whenComplete((ignored, failure) -> {
            if (failure == null) clearSqlAudits();
            if (!player.isOnline()) return;
            if (failure != null) player.sendMessage(ChatColor.RED
                    + "Remote SQL setting failed: " + sqlError(failure));
            else {
                player.sendMessage(ChatColor.GREEN + "Remote SQL settings saved.");
            }
            if (session.stillViewing(player)) openRemoteSql(player);
        });
    }

    private void finishRemoteMirrorWrite(Player player, CompletionStage<Void> saved) {
        MenuSession session = MenuSession.capture(player);
        player.sendMessage(ChatColor.YELLOW + "Saving remote SQL mirror mode...");
        saved.whenComplete((ignored, failure) -> {
            if (failure == null) clearSqlAudits();
            if (!player.isOnline()) return;
            if (failure != null) player.sendMessage(ChatColor.RED
                    + "Remote SQL mirror change failed: " + sqlError(failure));
            else {
                if (plugin.sqlMirrorFailures().containsKey(plugin.remoteSqlBackend()))
                    player.sendMessage(ChatColor.RED + "Remote mirror is behind: "
                            + plugin.sqlMirrorFailures().get(plugin.remoteSqlBackend()));
                else player.sendMessage(ChatColor.GREEN + "SQL mirrors: " + plugin.sqlMirrorModes());
            }
            if (session.stillViewing(player)) openRemoteSql(player);
        });
    }

    private void remoteInputError(Player player, IllegalArgumentException failure) {
        player.sendMessage(ChatColor.RED + failure.getMessage());
        openRemoteSql(player);
    }

    private static String sqlError(Throwable failure) {
        if (failure == null) return "Connection or synchronization failed";
        while (failure.getCause() != null) failure = failure.getCause();
        if (failure instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
            return "SQLState " + sql.getSQLState();
        }
        if (failure instanceof java.io.IOException
                && ("No selected SQL source has a readable, valid snapshot".equals(failure.getMessage())
                    || "SQL source is not a valid RecipeWeaver snapshot".equals(failure.getMessage())
                    || "SQL source changed since inspection; inspect it again".equals(failure.getMessage()))) {
            return failure.getMessage();
        }
        return failure instanceof IllegalArgumentException ? failure.getMessage()
                : "database operation failed";
    }

    private void clearSqlAudits() {
        sqlAudits.clear();
        sqlAuditTickets.invalidateAll();
    }

    private void clearSqlAudit(UUID playerId) {
        sqlAudits.remove(playerId);
        sqlAuditTickets.remove(playerId);
    }

    private void clickLocalSql(Player player, MenuHolder holder, int slot) {
        if (!player.hasPermission("recipeweaver.admin")) return;
        if (slot == 22) { openSettings(player); return; }
        if (holder.sqlOrderPage == null
                || !holder.sqlOrderPage.stillCurrent(plugin.sqlMirrorModes(),
                        plugin.remoteSqlProfile())) {
            clearSqlAudit(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + "SQL sources changed; review the refreshed controls.");
            openLocalSql(player);
            return;
        }
        MenuSession session = MenuSession.capture(player);
        if (slot >= 18 && slot <= 20) {
            List<SqlMirrorMode> next = new ArrayList<>(plugin.sqlMirrorModes());
            if (slot == 18) next.removeIf(mode -> !mode.remote());
            else next = SqlMirrorSelection.toggle(next,
                    slot == 19 ? SqlMirrorMode.SQLITE : SqlMirrorMode.H2,
                    plugin.remoteSqlProfile());
            player.sendMessage(ChatColor.YELLOW + "Saving SQL mirror setting...");
            plugin.setSqlMirrorModesAsync(next).whenComplete((ignored, failure) -> {
                if (failure == null) clearSqlAudits();
                if (!player.isOnline()) return;
                if (failure != null) player.sendMessage(ChatColor.RED
                        + "SQL mirror setting failed: " + failure.getMessage());
                else {
                    if (plugin.sqlMirrorFailure() != null) player.sendMessage(ChatColor.RED
                            + "Mirror selected, but sync is behind local data: " + plugin.sqlMirrorFailure());
                    else player.sendMessage(ChatColor.GREEN + "SQL mirrors: " + plugin.sqlMirrorModes());
                }
                if (session.stillViewing(player)) openLocalSql(player);
            });
            return;
        }
        if (slot == 21) {
            player.sendMessage(ChatColor.YELLOW + "Synchronizing SQL mirror...");
            plugin.resyncSqlMirrorAsync().whenComplete((synced, failure) -> {
                if (failure == null && synced) clearSqlAudits();
                if (!player.isOnline()) return;
                if (failure != null) player.sendMessage(ChatColor.RED
                        + "SQL sync failed: " + failure.getMessage());
                else if (synced) player.sendMessage(ChatColor.GREEN + "SQL mirror is current.");
                else player.sendMessage(ChatColor.RED + "SQL mirror is still behind local data.");
                if (session.stillViewing(player)) openLocalSql(player);
            });
            return;
        }
        if (slot != 10 && slot != 11 && slot != 15 && slot != 16) return;
        SqlDocumentStore.Backend backend = slot == 10 || slot == 11
                ? SqlDocumentStore.Backend.SQLITE : SqlDocumentStore.Backend.H2;
        boolean export = slot == 10 || slot == 15;
        player.sendMessage(ChatColor.YELLOW + (export
                ? "Exporting RecipeWeaver data to " + backend + "..."
                : "Reading " + backend + " snapshot..."));
        if (export) {
            plugin.exportLocalSqlAsync(backend).whenComplete((count, failure) -> {
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (failure == null) clearSqlAudits();
                    if (!player.isOnline()) return;
                    if (failure == null) player.sendMessage(ChatColor.GREEN + "Saved and verified "
                            + count + " documents in " + backend + ".");
                    else player.sendMessage(ChatColor.RED + "SQL export failed: " + failure.getMessage());
                    if (session.stillViewing(player)) openLocalSql(player);
                });
            });
        } else {
            plugin.prepareLocalSqlRestoreAsync(backend).whenComplete((name, failure) -> {
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (failure == null) {
                        player.sendMessage(ChatColor.GREEN + "SQL snapshot prepared as " + name);
                        if (session.stillViewing(player)) openRestoreConfirm(player, name);
                    } else {
                        player.sendMessage(ChatColor.RED + "SQL import failed: " + failure.getMessage());
                        if (session.stillViewing(player)) openLocalSql(player);
                    }
                });
            });
        }
    }

    private void finishSettingsWrite(Player player, CompletionStage<Void> saved, String success) {
        MenuSession session = MenuSession.capture(player);
        player.sendMessage(ChatColor.YELLOW + "Saving RecipeWeaver settings...");
        saved.whenComplete((ignored, failure) -> {
            if (!player.isOnline()) return;
            if (failure == null) player.sendMessage(ChatColor.GREEN + success);
            else player.sendMessage(ChatColor.RED + "Settings save failed: " + failure.getMessage());
            if (session.stillViewing(player)) openSettings(player);
        });
    }

    private void openVanillaRecipes(Player player, int requestedPage) {
        if (!player.hasPermission("recipeweaver.admin")) return;
        List<NamespacedKey> keys = plugin.disabledVanillaRecipes().stream()
                .sorted(Comparator.comparing(NamespacedKey::toString)).toList();
        int maxPage = Math.max(0, (keys.size() - 1) / 45);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.VANILLA, page, null);
        holder.vanillaKeys = keys;
        Inventory inventory = create(holder, 54, "Disabled Vanilla Recipes " + (page + 1));
        for (int slot = 0; slot < 45 && page * 45 + slot < keys.size(); slot++) {
            NamespacedKey key = keys.get(page * 45 + slot);
            inventory.setItem(slot, icon(Material.BARRIER, key.toString(),
                    "Click to restore this vanilla recipe"));
        }
        if (page > 0) inventory.setItem(45, icon(Material.ARROW, "Previous page"));
        inventory.setItem(49, icon(Material.ARROW, "Back to settings"));
        inventory.setItem(52, icon(Material.WRITABLE_BOOK, "Disable a vanilla recipe",
                "Enter an existing minecraft: recipe ID",
                "Bukkit warns removal may erase player discoveries"));
        if (page < maxPage) inventory.setItem(53, icon(Material.ARROW, "Next page"));
        player.openInventory(inventory);
    }

    private void clickVanillaRecipes(Player player, MenuHolder holder, int slot) {
        if (!player.hasPermission("recipeweaver.admin")) return;
        if (slot == 49) { openSettings(player); return; }
        if (slot == 45) { openVanillaRecipes(player, holder.page - 1); return; }
        if (slot == 53) { openVanillaRecipes(player, holder.page + 1); return; }
        if (slot == 52) {
            player.sendMessage(ChatColor.YELLOW
                    + "Bukkit warns that removing a recipe may erase its player discoveries.");
            textPrompt.open(player, "Vanilla recipe ID", "minecraft:oak_planks", value -> {
                NamespacedKey key = NamespacedKey.fromString(value.toLowerCase(Locale.ROOT));
                if (key == null || !key.getNamespace().equals(NamespacedKey.MINECRAFT)
                        || !value.equalsIgnoreCase(key.toString()) || Bukkit.getRecipe(key) == null) {
                    player.sendMessage(ChatColor.RED + "Enter an existing minecraft: recipe ID.");
                    openVanillaRecipes(player, holder.page);
                    return;
                }
                finishVanillaWrite(player, key, true);
            }, () -> openVanillaRecipes(player, holder.page));
            return;
        }
        if (slot < 0 || slot >= 45) return;
        int index = holder.page * 45 + slot;
        if (holder.vanillaKeys == null || index >= holder.vanillaKeys.size()) return;
        finishVanillaWrite(player, holder.vanillaKeys.get(index), false);
    }

    private void finishVanillaWrite(Player player, NamespacedKey key, boolean disabled) {
        MenuSession session = MenuSession.capture(player);
        try {
            CompletionStage<Void> saved = plugin.setVanillaRecipeDisabledAsync(key, disabled);
            player.sendMessage(ChatColor.YELLOW + "Saving vanilla recipe setting...");
            saved.whenComplete((ignored, failure) -> {
                if (!player.isOnline()) return;
                if (failure != null) {
                    player.sendMessage(ChatColor.RED + "Settings save failed: " + failure.getMessage());
                } else {
                    player.sendMessage(ChatColor.GREEN + (disabled ? "Disabled " : "Restored ") + key);
                    if (!disabled && Bukkit.getRecipe(key) == null) {
                        player.sendMessage(ChatColor.YELLOW
                                + "This server could not re-register the recipe; restart to recover it.");
                    }
                }
                if (session.stillViewing(player)) openVanillaRecipes(player, 0);
            });
        } catch (IllegalArgumentException ex) {
            player.sendMessage(ChatColor.RED + ex.getMessage());
            openVanillaRecipes(player, 0);
        }
    }

    private void openBackups(Player player, int requestedPage) {
        if (!player.hasPermission("recipeweaver.admin")) return;
        MenuHolder loadingHolder = new MenuHolder(player.getUniqueId(), Menu.BACKUPS, requestedPage, null);
        Inventory loading = create(loadingHolder, 54, "RecipeWeaver Backups");
        loading.setItem(22, icon(Material.CLOCK, "Loading backups..."));
        loading.setItem(49, icon(Material.ARROW, "Back to settings"));
        player.openInventory(loading);
        plugin.listBackupsAsync().whenComplete((names, failure) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || player.getOpenInventory().getTopInventory() != loading) return;
                if (failure != null) {
                    player.sendMessage(ChatColor.RED + "Could not list backups: " + failure.getMessage());
                    openSettings(player);
                    return;
                }
                int maxPage = Math.max(0, (names.size() - 1) / 45);
                int page = Math.max(0, Math.min(requestedPage, maxPage));
                MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.BACKUPS, page, null);
                holder.backupNames = names;
                Inventory inventory = create(holder, 54, "RecipeWeaver Backups " + (page + 1));
                for (int slot = 0; slot < 45 && page * 45 + slot < names.size(); slot++) {
                    String name = names.get(page * 45 + slot);
                    inventory.setItem(slot, icon(Material.CHEST, name, "Click to review restore"));
                }
                if (page > 0) inventory.setItem(45, icon(Material.ARROW, "Previous page"));
                inventory.setItem(49, icon(Material.ARROW, "Back to settings"));
                if (page < maxPage) inventory.setItem(53, icon(Material.ARROW, "Next page"));
                player.openInventory(inventory);
            });
        });
    }

    private void clickBackups(Player player, MenuHolder holder, int slot) {
        if (!player.hasPermission("recipeweaver.admin")) return;
        if (slot == 49) { openSettings(player); return; }
        if (holder.backupNames == null) return;
        if (slot == 45) { openBackups(player, holder.page - 1); return; }
        if (slot == 53) { openBackups(player, holder.page + 1); return; }
        if (slot < 0 || slot >= 45) return;
        int index = holder.page * 45 + slot;
        if (holder.backupNames == null || index >= holder.backupNames.size()) return;
        String selected = holder.backupNames.get(index);
        openRestoreConfirm(player, selected);
    }

    private void openRestoreConfirm(Player player, String backup) {
        if (!player.hasPermission("recipeweaver.admin")) return;
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.RESTORE_CONFIRM, 0, null, backup);
        Inventory inventory = create(holder, 27, "Confirm RecipeWeaver Restore");
        inventory.setItem(11, icon(Material.ARROW, "Cancel", "Return to backups"));
        inventory.setItem(13, icon(Material.CHEST, backup, "Current data will be backed up first"));
        inventory.setItem(15, icon(Material.REDSTONE_BLOCK, "Restore this backup", "Replaces recipes, items, and settings"));
        player.openInventory(inventory);
    }

    private void clickRestoreConfirm(Player player, String backup, int slot) {
        if (!player.hasPermission("recipeweaver.admin")) return;
        if (slot == 11) { openBackups(player, 0); return; }
        if (slot != 15 || backup == null) return;
        MenuSession session = MenuSession.capture(player);
        player.sendMessage(ChatColor.YELLOW + "Restoring RecipeWeaver backup...");
        plugin.restoreDataAsync(backup).whenComplete((previous, failure) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (failure == null) {
                    plugin.logAsync(Level.WARNING, player.getName() + " restored " + backup
                            + "; previous data saved as " + previous);
                    if (!player.isOnline()) return;
                    player.sendMessage(ChatColor.GREEN + "Restored " + backup
                            + ". Previous data: " + previous);
                    if (session.stillViewing(player)) openSettings(player);
                } else {
                    plugin.logAsync(Level.WARNING, "Restore failed: " + failure);
                    if (!player.isOnline()) return;
                    player.sendMessage(ChatColor.RED + "Restore failed: " + failure.getMessage());
                    if (session.stillViewing(player)) openBackups(player, 0);
                }
            });
        });
    }

    private void startRecipe(Player player, RecipeKind kind) {
        RecipeDraft draft = new RecipeDraft(kind);
        recipeDrafts.put(player.getUniqueId(), draft);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.hasPermission("recipeweaver.studio")
                    && recipeDrafts.get(player.getUniqueId()) == draft) {
                openRecipeEditor(player);
                player.sendMessage(ChatColor.AQUA + "Recipe editor: click the blue grid with an ingredient, "
                        + "click the green result slot with the output, then click Save.");
            }
        });
    }

    private void openRecipeEditor(Player player) {
        RecipeDraft draft = recipeDrafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.RECIPE, 0, null);
        Inventory inventory = create(holder, 54, "RecipeWeaver Crafting Editor");
        renderRecipeEditor(inventory, draft);
        player.openInventory(inventory);
    }

    private void renderRecipeEditor(Inventory inventory, RecipeDraft draft) {
        ItemStack background = icon(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, background);
        inventory.setItem(1, icon(Material.CRAFTING_TABLE, "1. Add ingredients",
                "Pick up an item from your inventory below",
                "Click any blue square in the 3x3 grid",
                draft.kind == RecipeKind.SHAPED ? "Shaped: the positions matter"
                        : "Shapeless: any arrangement works"));
        inventory.setItem(4, icon(Material.NAME_TAG, "Recipe ID",
                draft.existingKey == null ? "Choose an ID when you save" : draft.existingKey.toString()));
        inventory.setItem(7, icon(Material.CHEST, "2. Set the result",
                "Pick up the output item from your inventory",
                "Click the green square on the right"));
        for (int slot : new int[] {9, 13, 18, 22, 27, 31}) {
            inventory.setItem(slot, icon(Material.BLUE_STAINED_GLASS_PANE, " "));
        }
        for (int slot : new int[] {14, 15, 16, 25, 32, 33}) {
            inventory.setItem(slot, icon(Material.GREEN_STAINED_GLASS_PANE, " "));
        }
        for (int i = 0; i < 9; i++) {
            inventory.setItem(GRID[i], draft.ingredients[i] == null
                    ? icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "Ingredient slot " + (i + 1),
                            "Hold an item on your cursor, then click here",
                            "The editor copies one item as a sample")
                    : editorIngredient(draft, i));
        }
        inventory.setItem(23, icon(Material.ARROW, "Ingredients become result",
                "Blue 3x3 grid  ->  green result slot"));
        inventory.setItem(24, draft.result == null
                ? icon(Material.LIME_STAINED_GLASS_PANE, "Result item goes here",
                        "Hold the output item on your cursor",
                        "Click this green square to set the result")
                : editorResult(draft.result));
        inventory.setItem(34, icon(Material.WRITABLE_BOOK, "How to edit ingredients",
                "Left click with an item: set or replace",
                "Left click with an empty cursor: clear",
                "Right click empty: exact / material match",
                "Shift-left a sample: alternatives and item tag"));
        inventory.setItem(36, icon(Material.OAK_SIGN, "Optional settings",
                "The controls to the right are optional",
                "You can save with their default values"));
        inventory.setItem(38, icon(draft.kind == RecipeKind.SHAPED ? Material.CRAFTING_TABLE : Material.PAPER,
                "Crafting style: " + draft.kind,
                draft.kind == RecipeKind.SHAPED ? "Item positions matter" : "Item positions do not matter",
                "Click to switch"));
        inventory.setItem(39, icon(Material.BOOKSHELF, "Recipe book tab: " + draft.bookCategory,
                "Click to change the crafting category"));
        inventory.setItem(40, icon(Material.ITEM_FRAME, "Group: " + (draft.group.isBlank() ? "none" : draft.group),
                "Optional label for organizing recipes",
                "Click to edit; enter none to clear"));
        inventory.setItem(41, icon(draft.hidden ? Material.REDSTONE_TORCH : Material.TORCH,
                "Show in recipe book: " + (draft.hidden ? "No" : "Yes"), "Click to switch"));
        inventory.setItem(42, icon(draft.enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                "Recipe active: " + (draft.enabled ? "Yes" : "No"), "Click to switch"));
        inventory.setItem(43, icon(draft.conditions().unrestricted() ? Material.GRAY_DYE : Material.COMPARATOR,
                "Extra crafting requirements", draft.conditions().unrestricted()
                        ? "None; anyone can craft" : "Permission, location, time, weather, and player rules",
                "Click to edit"));
        boolean hasIngredient = hasRecipeIngredient(draft);
        boolean ready = hasIngredient && draft.result != null;
        inventory.setItem(45, icon(Material.ARROW, "Back to studio", "Leave this editor"));
        inventory.setItem(47, icon(ready ? Material.LIME_DYE
                        : hasIngredient ? Material.GREEN_DYE : Material.LIGHT_BLUE_DYE,
                ready ? "Ready to save" : hasIngredient ? "Next: set the green result"
                        : "Next: fill a blue ingredient slot",
                "Your inventory items are copied as recipe samples"));
        inventory.setItem(49, icon(ready ? Material.EMERALD_BLOCK : Material.REDSTONE_BLOCK,
                ready ? "3. SAVE RECIPE" : "3. SAVE RECIPE (needs items)",
                ready ? "Click to choose a recipe ID and save"
                        : "Add an ingredient and result to enable Save"));
        inventory.setItem(51, icon(Material.TARGET, "Preview recipe match",
                "Try a 3x3 input without consuming items"));
    }

    private void refreshRecipeEditor(Player player) {
        RecipeDraft draft = recipeDrafts.get(player.getUniqueId());
        Inventory inventory = player.getOpenInventory().getTopInventory();
        if (draft != null && inventory.getHolder() instanceof MenuHolder holder
                && holder.menu == Menu.RECIPE && holder.owner.equals(player.getUniqueId())) {
            renderRecipeEditor(inventory, draft);
        }
    }

    private static boolean hasRecipeIngredient(RecipeDraft draft) {
        for (ItemStack ingredient : draft.ingredients) if (ingredient != null) return true;
        return false;
    }

    private static ItemStack editorIngredient(RecipeDraft draft, int index) {
        ItemStack shown = showWorkbenchIngredient(draft.ingredients[index], draft.matchModes[index],
                draft.alternatives.get(index).size(), draft.itemTags[index]);
        ItemMeta meta = shown.getItemMeta();
        List<String> lore = new ArrayList<>(meta.getLore());
        lore.add(ChatColor.AQUA + "Ingredient slot " + (index + 1));
        lore.add(ChatColor.GRAY + "Hold another item + left click: replace");
        lore.add(ChatColor.GRAY + "Empty cursor + left click: clear");
        lore.add(ChatColor.GRAY + "Empty cursor + right click: exact / material");
        lore.add(ChatColor.GRAY + "Shift-left: alternatives and item tag");
        meta.setLore(lore);
        shown.setItemMeta(meta);
        return shown;
    }

    private static ItemStack editorResult(ItemStack result) {
        ItemStack shown = result.clone();
        ItemMeta meta = shown.getItemMeta();
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(ChatColor.GREEN + "Result item");
        lore.add(ChatColor.GRAY + "Hold another item + click: replace");
        lore.add(ChatColor.GRAY + "Empty cursor + click: clear");
        meta.setLore(lore);
        shown.setItemMeta(meta);
        return shown;
    }

    void openRecipeBook(Player player, int page) {
        List<BookEntry> list = visibleRecipes(player);
        int maxPage = Math.max(0, (list.size() - 1) / 45);
        page = Math.max(0, Math.min(page, maxPage));
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.BOOK, page, null);
        Inventory inventory = create(holder, 54, "RecipeWeaver Recipes " + (page + 1));
        int start = page * 45;
        holder.bookPage = new BookPageSnapshot(list.subList(start,
                Math.min(start + 45, list.size())).stream().map(BookEntry::key).toList());
        for (int slot = 0; slot < 45 && start + slot < list.size(); slot++) {
            BookEntry entry = list.get(start + slot);
            ItemStack icon = entry.icon();
            ItemMeta meta = icon.getItemMeta();
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(ChatColor.GRAY + entry.key().toString());
            lore.add(ChatColor.GRAY + entry.kind());
            if (!entry.enabled()) lore.add(ChatColor.RED + "Disabled");
            if (entry.hidden()) lore.add(ChatColor.DARK_GRAY + "Hidden");
            lore.add(plugin.isDiscovered(player, entry.key())
                    ? ChatColor.GREEN + "Discovered" : ChatColor.GRAY + "Undiscovered");
            lore.add(ChatColor.YELLOW + "Click to view recipe");
            meta.setLore(lore);
            icon.setItemMeta(meta);
            inventory.setItem(slot, icon);
        }
        if (page > 0) inventory.setItem(45, icon(Material.ARROW, "Previous page"));
        String query = bookQueries.getOrDefault(player.getUniqueId(), "");
        BookCategory category = bookCategories.getOrDefault(player.getUniqueId(), BookCategory.ALL);
        BookSort sort = bookSorts.getOrDefault(player.getUniqueId(), BookSort.KEY);
        inventory.setItem(46, icon(Material.BOOKSHELF, "Category: " + category, "Click to cycle"));
        inventory.setItem(47, icon(Material.OAK_SIGN, "Search recipes",
                query.isBlank() ? "ID, group, kind, or result name" : "Current: " + query,
                "Left click to edit; right click to clear"));
        inventory.setItem(48, icon(Material.COMPARATOR, "Sort: " + sort, "Click to cycle"));
        inventory.setItem(49, icon(Material.CRAFTING_TABLE, "Create custom recipe",
                player.hasPermission("recipeweaver.studio")
                        ? "Click to open the recipe studio; right-click to close"
                        : "Requires recipeweaver.studio; right-click to close"));
        if (plugin.bookItemEnabled()) inventory.setItem(50, icon(Material.BOOK,
                "Get recipe book item", "Right-click it to browse recipes"));
        String group = bookGroups.get(player.getUniqueId());
        inventory.setItem(51, icon(Material.ITEM_FRAME,
                "Group: " + (group == null ? "All" : group.isBlank() ? "Ungrouped" : group),
                "Click to cycle available recipe groups"));
        BookDiscovery discovery = bookDiscoveries.getOrDefault(player.getUniqueId(), BookDiscovery.ALL);
        inventory.setItem(52, icon(Material.KNOWLEDGE_BOOK, "Discovery: " + discovery,
                "Click to show all, discovered, or undiscovered recipes"));
        if (page < maxPage) inventory.setItem(53, icon(Material.ARROW, "Next page"));
        player.openInventory(inventory);
    }

    private void openRecipeDetail(Player player, BookEntry entry) {
        if (entry.grid() != null) {
            plugin.gridMenus().openGridDetail(player, entry.grid());
            return;
        }
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.DETAIL, 0, entry.key());
        Inventory inventory = create(holder, 54, "Recipe: " + entry.key().getKey());
        if (entry.workbench() != null) {
            RecipeDefinition recipe = entry.workbench();
            List<ItemStack> ingredients = recipe.ingredients();
            for (int i = 0; i < 9; i++) if (ingredients.get(i) != null) inventory.setItem(GRID[i],
                    showWorkbenchIngredient(ingredients.get(i), recipe.matchModes().get(i),
                            recipe.alternatives().get(i).size(), recipe.itemTags().get(i)));
            inventory.setItem(24, recipe.result());
            inventory.setItem(40, icon(Material.PAPER, recipe.kind().name(),
                    "Group: " + recipe.group(), "Vanilla book category: " + recipe.bookCategory()));
            if (!recipe.conditions().unrestricted()) {
                inventory.setItem(26, icon(Material.COMPARATOR, "Craft conditions",
                        StationMenus.conditionLines(recipe.conditions())));
            }
        } else {
            StationRecipeDefinition recipe = entry.station();
            List<ItemStack> ingredients = recipe.ingredients();
            for (int i = 0; i < ingredients.size(); i++) {
                if (ingredients.get(i) != null) inventory.setItem(10 + i,
                        showWorkbenchIngredient(ingredients.get(i), recipe.matchModes().get(i),
                                recipe.alternatives().get(i).size(), recipe.itemTags().get(i)));
            }
            if (recipe.result() != null) inventory.setItem(24, recipe.result());
            else inventory.setItem(24, icon(Material.SMITHING_TABLE, "Dynamic trim result"));
            inventory.setItem(40, icon(Material.FURNACE, recipe.kind().name(),
                    "Group: " + recipe.group(), recipe.kind().cooking()
                            ? "Vanilla book category: " + recipe.bookCategory()
                            : "No vanilla book category",
                    "Player craft limit: " + recipe.maxCraftsPerPlayer(),
                    "Player craft delay: " + recipe.cooldownSeconds() + " seconds",
                    "Result metadata: " + recipe.resultMetadataMode()
                            + (recipe.resultCopySourceSlot() < 0 ? ""
                                    : " from ingredient " + (recipe.resultCopySourceSlot() + 1))));
            if (recipe.resultChoices().size() > 1) {
                List<String> outcomes = new ArrayList<>();
                for (var choice : recipe.resultChoices()) {
                    outcomes.add(choice.item().getType() + " x" + choice.item().getAmount()
                            + " | weight " + choice.weight() + "/"
                            + recipe.totalResultWeight());
                }
                inventory.setItem(42, icon(Material.CHEST,
                        "Possible results: " + recipe.resultChoices().size(),
                        outcomes.toArray(String[]::new)));
            }
            if (recipe.kind() == dev.recipeweaver.api.StationKind.ANVIL
                    || recipe.kind() == dev.recipeweaver.api.StationKind.GRINDSTONE) {
                List<String> rules = new ArrayList<>();
                for (int i = 0; i < ingredients.size(); i++) {
                    ItemStack input = ingredients.get(i);
                    if (input == null) continue;
                    rules.add("Input " + (i + 1) + ": " + input.getType() + " x" + input.getAmount()
                            + (recipe.optional(i) ? " (optional)" : "")
                            + ", " + recipe.matchModes().get(i));
                    rules.add(recipe.consumes(i) ? "Consumed" : "Kept as catalyst");
                    ItemStack returned = recipe.remainder(i);
                    if (returned != null) rules.add("Returns " + returned.getType()
                            + " x" + returned.getAmount());
                }
                inventory.setItem(41, icon(Material.HOPPER, "Ingredient rules",
                        rules.toArray(String[]::new)));
            }
            if (!recipe.conditions().unrestricted()) {
                inventory.setItem(26, icon(Material.COMPARATOR, "Craft conditions",
                        StationMenus.conditionLines(recipe.conditions())));
            }
            if (recipe.craftSound() != null || recipe.craftAdvancement() != null
                    || recipe.craftCommand() != null) {
                List<String> actions = new ArrayList<>();
                if (recipe.craftSound() != null) {
                    actions.add("Sound: " + recipe.craftSound().sound());
                }
                if (recipe.craftAdvancement() != null) {
                    actions.add("Advancement: " + recipe.craftAdvancement().advancement());
                }
                if (recipe.craftCommand() != null) {
                    actions.add("Command as " + recipe.craftCommand().sender());
                }
                inventory.setItem(35, icon(Material.NOTE_BLOCK, "Post-craft actions",
                        actions.toArray(String[]::new)));
            }
        }
        if (player.hasPermission("recipeweaver.studio")) {
            inventory.setItem(45, icon(Material.WRITABLE_BOOK, "Edit recipe"));
            inventory.setItem(53, icon(Material.BARRIER, "Delete recipe", "Requires typing DELETE"));
        }
        inventory.setItem(49, icon(Material.ARROW, "Back to book"));
        player.openInventory(inventory);
    }
    private void openItems(Player player, int page) {
        List<NamespacedKey> keys = new ArrayList<>(plugin.items().keys());
        keys.sort(Comparator.comparing(NamespacedKey::toString));
        int maxPage = Math.max(0, (keys.size() - 1) / 45);
        page = Math.max(0, Math.min(page, maxPage));
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.ITEM_LIST, page, null);
        Inventory inventory = create(holder, 54, "RecipeWeaver Items " + (page + 1));
        Map<Integer, NamespacedKey> shownKeys = new HashMap<>();
        for (int slot = 0; slot < 45 && page * 45 + slot < keys.size(); slot++) {
            NamespacedKey key = keys.get(page * 45 + slot);
            ItemStack item = plugin.items().get(key).orElse(null);
            if (item == null) continue;
            ItemMeta meta = item.getItemMeta();
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(ChatColor.GRAY + key.toString());
            lore.add(ChatColor.YELLOW + "Click to edit");
            meta.setLore(lore);
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
            shownKeys.put(slot, key);
        }
        holder.itemPage = new ItemPageSnapshot(shownKeys);
        if (page > 0) inventory.setItem(45, icon(Material.ARROW, "Previous page"));
        inventory.setItem(49, icon(Material.ARROW, "Back to studio"));
        if (page < maxPage) inventory.setItem(53, icon(Material.ARROW, "Next page"));
        player.openInventory(inventory);
    }
    private void openItemEditor(Player player) {
        ItemStack draft = itemDrafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.ITEM, 0, null);
        Inventory inventory = create(holder, 54, "RecipeWeaver Item Editor");
        NamespacedKey existing = itemDraftIds.get(player.getUniqueId());
        inventory.setItem(4, icon(Material.NAME_TAG, "Item ID", existing == null ? "Assigned when saved" : existing.toString()));
        inventory.setItem(8, icon(Material.WRITABLE_BOOK, "Import Minecraft item text",
                "Enter one complete /give item argument in a book",
                "Supports server item components and custom data",
                "Replaces this draft and keeps its saved item ID"));
        inventory.setItem(10, icon(Material.NAME_TAG, "Edit name", "Use anvil text input"));
        inventory.setItem(11, icon(Material.WRITABLE_BOOK, "Edit lore",
                "Use up to eight book pages; each line becomes one lore line",
                "Type none to clear lore"));
        ItemMeta draftMeta = draft.getItemMeta();
        inventory.setItem(12, draftMeta instanceof Damageable damageable
                && draft.getType().getMaxDurability() > 0
                ? icon(Material.IRON_PICKAXE, "Damage: " + damageable.getDamage(),
                        "Enter 0 through " + (draft.getType().getMaxDurability() - 1))
                : icon(Material.BARRIER, "No durability on this item"));
        inventory.setItem(13, draft.clone());
        inventory.setItem(14, icon(Material.ENCHANTED_BOOK,
                "Enchantments: " + draftMeta.getEnchants().size(), "Click to add, change, or remove"));
        inventory.setItem(15, icon(Material.REDSTONE, "Amount -1", "Current: " + draft.getAmount()));
        inventory.setItem(16, icon(Material.EMERALD, "Amount +1", "Current: " + draft.getAmount()));
        inventory.setItem(17, icon(draftMeta.isUnbreakable() ? Material.LIME_DYE : Material.GRAY_DYE,
                "Unbreakable: " + draftMeta.isUnbreakable(), "Click to switch"));
        inventory.setItem(18, icon(Material.ARROW, "Back"));
        inventory.setItem(19, draftMeta instanceof ArmorMeta armor
                ? icon(Material.SMITHING_TABLE, "Armor trim: "
                        + (armor.hasTrim() ? armor.getTrim().getMaterial().getKey() + " "
                        + armor.getTrim().getPattern().getKey() : "none"),
                        "Click to enter material and pattern keys")
                : icon(Material.BARRIER, "This item cannot have an armor trim"));
        inventory.setItem(20, icon(Material.ITEM_FRAME, "Custom model data: "
                + (draftMeta.hasCustomModelData() ? draftMeta.getCustomModelData() : "none"),
                "Click to enter an integer; type none to clear"));
        inventory.setItem(21, icon(Material.PAPER, "Item flags: " + draftMeta.getItemFlags().size(),
                "Click to show or hide tooltip sections"));
        inventory.setItem(22, icon(Material.EMERALD_BLOCK, "Save custom item", "Choose an ID"));
        inventory.setItem(23, icon(Material.ENDER_CHEST, "Import saved or provider item",
                "Enter namespace:item in anvil text input",
                "For ItemsAdder use itemsadder:namespace/item",
                "Replaces this draft; keeps an existing RecipeWeaver item ID"));
        inventory.setItem(24, icon(Material.WRITABLE_BOOK, "Persistent item data",
                "Click to browse, add, or edit values",
                "Current keys: " + draftMeta.getPersistentDataContainer().getKeys().size()));
        inventory.setItem(25, visualComponentControl(draftMeta));
        if (existing != null) inventory.setItem(26, icon(Material.BARRIER, "Delete custom item", "Requires typing DELETE"));
        inventory.setItem(27, icon(Material.BOOKSHELF, "More item components",
                "Axolotl, knowledge book, horn, bottle, bundle, repair, book, block, and item name"));
        inventory.setItem(28, icon(Material.ENCHANTED_BOOK,
                "Enchantment glint: " + (draftMeta.hasEnchantmentGlintOverride()
                        ? draftMeta.getEnchantmentGlintOverride() : "automatic"),
                "Click to cycle automatic, on, and off"));
        inventory.setItem(29, icon(Material.FIRE_CHARGE,
                "Fire resistant: " + draftMeta.isFireResistant(), "Click to switch"));
        inventory.setItem(30, icon(Material.PAPER,
                "Hide entire tooltip: " + draftMeta.isHideTooltip(), "Click to switch"));
        inventory.setItem(31, icon(Material.CHEST,
                "Max stack size: " + (draftMeta.hasMaxStackSize()
                        ? draftMeta.getMaxStackSize() : "default"),
                "Enter 1 through 99; type none for default"));
        inventory.setItem(32, icon(Material.NETHER_STAR,
                "Rarity: " + (draftMeta.hasRarity() ? draftMeta.getRarity() : "default"),
                "Enter default, common, uncommon, rare, or epic"));
        if (draftMeta instanceof PotionMeta potion) {
            inventory.setItem(33, icon(Material.POTION,
                    "Base potion: " + (potion.hasBasePotionType()
                            ? potion.getBasePotionType() : "none"),
                    "Enter a type such as HEALING or LONG_SWIFTNESS"));
            inventory.setItem(34, icon(Material.BREWING_STAND,
                    "Custom potion effects: " + potion.getCustomEffects().size(),
                    "Enter minecraft:speed 200 1 to add or replace",
                    "Enter minecraft:speed 0 to remove"));
        }
        if (draftMeta instanceof WritableBookMeta book) {
            if (draft.getType() == Material.WRITTEN_BOOK && book instanceof BookMeta written) {
                inventory.setItem(35, icon(Material.NAME_TAG,
                        "Book title: " + (written.hasTitle() ? written.getTitle() : "none"),
                        "Enter 1 through 32 characters"));
                inventory.setItem(36, icon(Material.FEATHER,
                        "Book author: " + (written.hasAuthor() ? written.getAuthor() : "none"),
                        "Enter 1 through 64 characters"));
            }
            inventory.setItem(37, icon(Material.WRITABLE_BOOK,
                    "Book pages: " + book.getPageCount(),
                    "Edit up to eight pages in the protected book",
                    "Separate pages with ---PAGE---; none clears"));
        }
        inventory.setItem(38, icon(Material.APPLE,
                "Food properties: " + (draftMeta.hasFood() ? "custom" : "none"),
                "Enter nutrition saturation always-eat eat-seconds",
                "Example: 4 0.6 false 1.6; type none to remove"));
        inventory.setItem(39, icon(Material.GOLDEN_APPLE,
                "Food effects: " + ItemDraftEditor.foodEffectCount(draft),
                "Enter minecraft:speed 200 1 0.5",
                "Use minecraft:speed 0 to remove"));
        inventory.setItem(40, icon(Material.IRON_PICKAXE,
                "Tool properties: " + (draftMeta.hasTool() ? "custom" : "none"),
                "Enter default mining speed and damage per block",
                "Example: 4.0 1; type none to remove"));
        inventory.setItem(41, icon(Material.STONE,
                "Tool block rules: " + (draftMeta.hasTool()
                        ? draftMeta.getTool().getRules().size() : 0),
                "Enter STONE 8.0 true",
                "Use STONE none to remove a single-block rule"));
        if (draftMeta instanceof MapMeta map) {
            inventory.setItem(42, icon(Material.FILLED_MAP,
                    "Map color: " + (map.hasColor() ? colorText(map.getColor()) : "none"),
                    "Enter #RRGGBB or none"));
            inventory.setItem(43, icon(Material.NAME_TAG,
                    "Map ID: " + (map.hasMapId() ? map.getMapId() : "none"),
                    "Enter an existing map ID"));
            inventory.setItem(44, icon(Material.PAPER,
                    "Map scaling: " + map.isScaling(), "Click to switch"));
            String label = MapLocationLabel.read(map);
            inventory.setItem(45, icon(Material.OAK_SIGN,
                    "Map location label: " + (label == null ? "none" : label),
                    "Enter a label of at most 128 characters, or none"));
        }
        if (draftMeta instanceof CompassMeta compass) {
            org.bukkit.Location target = compass.hasLodestone() ? compass.getLodestone() : null;
            String targetText = target == null ? "none"
                    : target.getWorld() == null ? "unavailable world"
                    : target.getWorld().getName() + " " + target.getBlockX() + " "
                            + target.getBlockY() + " " + target.getBlockZ();
            inventory.setItem(45, icon(Material.COMPASS,
                    "Lodestone: " + targetText,
                    "Enter world x y z, or none"));
            inventory.setItem(46, icon(Material.LODESTONE,
                    "Lodestone tracking: " + compass.isLodestoneTracked(),
                    "Click to switch after setting a target"));
        }
        if (draftMeta instanceof CrossbowMeta crossbow) {
            inventory.setItem(47, icon(Material.CROSSBOW,
                    "Charged projectile: " + (crossbow.hasChargedProjectiles()
                            ? crossbow.getChargedProjectiles().get(0).getType() : "none"),
                    "Enter ARROW or FIREWORK_ROCKET; type none to clear"));
        }
        if (draftMeta instanceof BannerMeta banner) {
            inventory.setItem(48, icon(Material.WHITE_BANNER,
                    "Banner patterns: " + banner.numberOfPatterns(),
                    "Enter dye color and pattern type, such as RED CIRCLE",
                    "Type remove-last to remove the newest pattern"));
        }
        if (draftMeta instanceof SuspiciousStewMeta stew) {
            inventory.setItem(49, icon(Material.SUSPICIOUS_STEW,
                    "Stew effects: " + stew.getCustomEffects().size(),
                    "Enter minecraft:speed 200",
                    "Use minecraft:speed 0 to remove"));
        }
        if (draftMeta instanceof TropicalFishBucketMeta fish) {
            inventory.setItem(50, icon(Material.TROPICAL_FISH_BUCKET,
                    "Fish variant: " + (fish.hasVariant() ? fish.getPattern() : "none"),
                    "Enter body color, pattern color, and pattern",
                    "Example: RED BLUE KOB"));
        }
        if (draftMeta instanceof FireworkMeta firework) {
            inventory.setItem(51, icon(Material.FIREWORK_ROCKET,
                    "Firework effects: " + firework.getEffectsSize(),
                    "Enter effect type and color, such as BURST #AA44FF",
                    "Type remove-last to remove the newest effect"));
        }
        if (draftMeta instanceof EnchantmentStorageMeta book) {
            inventory.setItem(52, icon(Material.ENCHANTED_BOOK,
                    "Stored enchantments: " + book.getStoredEnchants().size(),
                    "Enter minecraft:unbreaking 3 to add or replace",
                    "Enter minecraft:unbreaking 0 to remove"));
        }
        if (draftMeta instanceof Damageable damageable && draft.getType().getMaxDurability() > 0) {
            inventory.setItem(53, icon(Material.IRON_PICKAXE,
                    "Max durability: " + (damageable.hasMaxDamage()
                            ? damageable.getMaxDamage() : "default"),
                    "Enter 1 through 1000000; type none for default"));
        }
        player.openInventory(inventory);
    }

    private void openWorkbenchConditionsEditor(Player player) {
        RecipeDraft draft = recipeDrafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.WORKBENCH_CONDITIONS, 0, null);
        Inventory inventory = create(holder, 27, "Workbench Craft Conditions");
        inventory.setItem(10, icon(Material.NAME_TAG, "Permission: " + shownCondition(draft.permission),
                "Enter a permission node; type none to clear"));
        inventory.setItem(11, icon(Material.GRASS_BLOCK, "World: " + shownCondition(draft.world),
                "Enter a world name; type none to clear"));
        inventory.setItem(12, icon(Material.OAK_SAPLING, "Biome: " + shownCondition(draft.biome),
                "Enter a namespaced biome; type none to clear"));
        inventory.setItem(13, icon(Material.CLOCK, "Time: " + (draft.timeStart < 0
                ? "any" : draft.timeStart + "-" + draft.timeEnd),
                "Enter start-end from 0 through 23999; type none to clear"));
        inventory.setItem(14, icon(Material.WATER_BUCKET, "Weather: " + draft.weather,
                "Click to cycle any, clear, rain, thunder"));
        inventory.setItem(15, icon(Material.EXPERIENCE_BOTTLE,
                "Minimum level: " + draft.minimumLevel, "Enter 0 through 100000"));
        inventory.setItem(16, icon(Material.PAPER,
                "Score objective: " + shownCondition(draft.scoreboardObjective),
                "Enter objective name; type none to clear"));
        inventory.setItem(19, icon(Material.COMPARATOR, "Minimum score: " + draft.minimumScore,
                "Set an objective first"));
        inventory.setItem(20, icon(Material.BOOK,
                "Advancement: " + shownCondition(draft.advancement),
                "Enter a namespaced advancement; type none to clear"));
        inventory.setItem(22, icon(Material.BARRIER, "Clear all conditions"));
        inventory.setItem(26, icon(Material.ARROW, "Back to recipe editor"));
        player.openInventory(inventory);
    }

    private void clickWorkbenchConditions(Player player, int slot) {
        RecipeDraft draft = recipeDrafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        switch (slot) {
            case 10 -> editWorkbenchCondition(player, "Permission", shownCondition(draft.permission),
                    value -> draft.permission = optionalCondition(value));
            case 11 -> editWorkbenchCondition(player, "World", shownCondition(draft.world),
                    value -> draft.world = optionalCondition(value));
            case 12 -> editWorkbenchCondition(player, "Biome key", shownCondition(draft.biome),
                    value -> draft.biome = conditionKey(value));
            case 13 -> editWorkbenchCondition(player, "Time range", draft.timeStart < 0
                    ? "none" : draft.timeStart + "-" + draft.timeEnd, value -> {
                if (optionalCondition(value) == null) {
                    draft.timeStart = -1;
                    draft.timeEnd = -1;
                    return;
                }
                String[] parts = value.split("-", -1);
                if (parts.length != 2) throw new IllegalArgumentException("Use start-end or none");
                int start = Integer.parseInt(parts[0]);
                int end = Integer.parseInt(parts[1]);
                if (start < 0 || start > 23999 || end < 0 || end > 23999) {
                    throw new IllegalArgumentException("Time bounds must be 0 through 23999");
                }
                draft.timeStart = start;
                draft.timeEnd = end;
            });
            case 14 -> {
                draft.weather = WeatherCondition.values()[
                        (draft.weather.ordinal() + 1) % WeatherCondition.values().length];
                openWorkbenchConditionsEditor(player);
            }
            case 15 -> editWorkbenchCondition(player, "Minimum level",
                    Integer.toString(draft.minimumLevel), value -> {
                int level = Integer.parseInt(value);
                if (level < 0 || level > 100000) throw new IllegalArgumentException("Use 0 through 100000");
                draft.minimumLevel = level;
            });
            case 16 -> editWorkbenchCondition(player, "Score objective",
                    shownCondition(draft.scoreboardObjective), value -> {
                draft.scoreboardObjective = optionalCondition(value);
                if (draft.scoreboardObjective == null) draft.minimumScore = 0;
            });
            case 19 -> editWorkbenchCondition(player, "Minimum score",
                    Integer.toString(draft.minimumScore), value -> {
                if (draft.scoreboardObjective == null) {
                    throw new IllegalArgumentException("Set a score objective first");
                }
                draft.minimumScore = Integer.parseInt(value);
            });
            case 20 -> editWorkbenchCondition(player, "Advancement key",
                    shownCondition(draft.advancement), value -> {
                NamespacedKey key = conditionKey(value);
                if (key != null && Bukkit.getAdvancement(key) == null) {
                    throw new IllegalArgumentException("Advancement does not exist on this server");
                }
                draft.advancement = key;
            });
            case 22 -> { draft.clearConditions(); openWorkbenchConditionsEditor(player); }
            case 26 -> openRecipeEditor(player);
            default -> { }
        }
    }

    private void editWorkbenchCondition(Player player, String title, String initial,
                                        java.util.function.Consumer<String> accepted) {
        textPrompt.open(player, title, initial, value -> {
            if (!player.hasPermission("recipeweaver.studio")) return;
            RecipeDraft draft = recipeDrafts.get(player.getUniqueId());
            if (draft == null) return;
            RecipeConditions before = draft.conditions();
            try {
                accepted.accept(value);
                draft.conditions();
            } catch (IllegalArgumentException ex) {
                draft.applyConditions(before);
                player.sendMessage(ChatColor.RED + "Invalid condition: " + ex.getMessage());
            }
            openWorkbenchConditionsEditor(player);
        }, () -> openWorkbenchConditionsEditor(player));
    }

    private static String optionalCondition(String value) {
        return value.equalsIgnoreCase("none") ? null : value;
    }

    private static NamespacedKey conditionKey(String value) {
        String raw = optionalCondition(value);
        if (raw == null) return null;
        NamespacedKey key = raw.contains(":") ? NamespacedKey.fromString(raw.toLowerCase(Locale.ROOT)) : null;
        if (key == null || !raw.equalsIgnoreCase(key.toString())) {
            throw new IllegalArgumentException("Use a namespaced key such as minecraft:plains");
        }
        return key;
    }

    private static String shownCondition(Object value) {
        return value == null ? "none" : value.toString();
    }

    private void openAdvancedItemEditor(Player player) {
        ItemStack draft = itemDrafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        ItemMeta meta = draft.getItemMeta();
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.ITEM_ADVANCED, 0, null);
        Inventory inventory = create(holder, 36, "RecipeWeaver More Item Components");
        inventory.setItem(4, draft.clone());
        inventory.setItem(10, meta instanceof AxolotlBucketMeta bucket
                ? icon(Material.AXOLOTL_BUCKET, "Axolotl variant: "
                        + (bucket.hasVariant() ? bucket.getVariant() : "none"),
                        "Enter LUCY, WILD, GOLD, CYAN, BLUE, or none")
                : icon(Material.BARRIER, "Requires an axolotl bucket"));
        inventory.setItem(11, meta instanceof KnowledgeBookMeta book
                ? icon(Material.KNOWLEDGE_BOOK, "Knowledge recipes: " + book.getRecipes().size(),
                        "Enter an existing recipe key; remove key; or clear")
                : icon(Material.BARRIER, "Requires a knowledge book"));
        inventory.setItem(12, meta instanceof MusicInstrumentMeta horn
                ? icon(Material.GOAT_HORN, "Horn instrument: "
                        + (horn.getInstrument() == null ? "default" : horn.getInstrument().getKey()),
                        "Enter PONDER, SING, SEEK, FEEL, ADMIRE, CALL, YEARN, or DREAM")
                : icon(Material.BARRIER, "Requires a goat horn"));
        inventory.setItem(13, meta instanceof OminousBottleMeta bottle
                ? icon(Material.OMINOUS_BOTTLE, "Ominous level: " + bottle.getAmplifier(),
                        "Enter 0 through 4")
                : icon(Material.BARRIER, "Requires an ominous bottle"));
        int shulkerSlots = ItemDraftEditor.shulkerFilledSlots(draft);
        inventory.setItem(14, meta instanceof BundleMeta bundle
                ? icon(Material.BUNDLE, "Bundle contents: " + bundle.getItems().size() + " stacks",
                        "MATERIAL amount replaces all; none clears",
                        "+ MATERIAL amount appends; slot MATERIAL amount replaces",
                        "- slot removes one stack; slots start at 1")
                : shulkerSlots >= 0
                        ? icon(Material.SHULKER_BOX, "Shulker contents: " + shulkerSlots + " filled slots",
                                "Enter slot MATERIAL amount, such as 1 DIAMOND 2",
                                "Enter slot none to clear one slot; none to empty the box")
                        : icon(Material.BARRIER, "Requires a bundle or shulker box"));
        inventory.setItem(15, meta instanceof Repairable repair
                ? icon(Material.ANVIL, "Repair cost: " + repair.getRepairCost(),
                        "Enter 0 through 1000000, or none")
                : icon(Material.BARRIER, "Item has no repair cost"));
        inventory.setItem(16, draft.getType() == Material.WRITTEN_BOOK && meta instanceof BookMeta book
                ? icon(Material.WRITTEN_BOOK, "Book generation: " + book.getGeneration(),
                        "Enter ORIGINAL, COPY_OF_ORIGINAL, COPY_OF_COPY, or TATTERED")
                : icon(Material.BARRIER, "Requires a written book"));
        inventory.setItem(17, meta instanceof BlockDataMeta block
                ? icon(Material.OAK_STAIRS, "Block-item state: "
                        + (block.hasBlockData() ? block.getBlockData(draft.getType()).getAsString() : "none"),
                        "Enter properties such as facing=east,half=top; none to clear")
                : icon(Material.BARRIER, "Requires a block item with states"));
        inventory.setItem(18, icon(Material.NAME_TAG, "Non-italic item name: "
                        + (meta.hasItemName() ? meta.getItemName() : "none"),
                "Enter a name; none to clear"));
        inventory.setItem(19, icon(Material.ITEM_FRAME,
                "Item model: " + modernComponent(meta, "ItemModel"),
                "Enter a namespaced model key; none to clear",
                "Requires a server with this item component"));
        inventory.setItem(20, icon(Material.PAPER,
                "Tooltip style: " + modernComponent(meta, "TooltipStyle"),
                "Enter a namespaced tooltip key; none to clear",
                "Requires a server with this item component"));
        inventory.setItem(21, icon(Material.ENCHANTING_TABLE,
                "Enchantability: " + modernComponent(meta, "Enchantable"),
                "Enter 0 through 1024; none to clear",
                "Requires a server with this item component"));
        inventory.setItem(22, icon(Material.ARROW, "Back to item editor"));
        inventory.setItem(23, icon(Material.IRON_SWORD,
                "Attribute modifiers: " + (meta.getAttributeModifiers() == null
                        ? 0 : meta.getAttributeModifiers().size()),
                "Enter attribute slot amount operation",
                "Example: minecraft:attack_damage mainhand 5 ADD_NUMBER",
                "Enter attribute slot none to remove"));
        inventory.setItem(24, icon(Material.ITEM_FRAME, "Rich custom model data",
                "Book input for floats, flags, strings, and colors",
                "Examples: @floats 1.5, 2.5; @colors #24A0ED",
                "Use @strings with one value per line; none clears all",
                "Requires a server with rich model data components"));
        inventory.setItem(25, icon(Material.BUCKET,
                "Use remainder: " + modernComponentStatus(meta, "UseRemainder"),
                "Enter one Minecraft item text argument in a book",
                "Example: minecraft:bucket; none clears",
                "Requires a server with use remainder components"));
        inventory.setItem(26, icon(Material.CLOCK,
                "Use cooldown: " + modernComponentStatus(meta, "UseCooldown"),
                "Enter seconds and optional namespace:group",
                "Example: 3.5 recipeweaver:shared; none clears"));
        inventory.setItem(27, icon(Material.IRON_CHESTPLATE,
                "Equippable: " + modernComponentStatus(meta, "Equippable"),
                "Enter SLOT and optional namespace:model",
                "Example: HEAD recipeweaver:hat; none clears"));
        inventory.setItem(28, modernSetterSupported("Glider", boolean.class)
                ? icon(Material.ELYTRA, "Glider: " + modernBoolean(meta, "Glider"),
                        "Enter true or false")
                : icon(Material.BARRIER, "Glider unsupported on this server"));
        inventory.setItem(29, modernSetterSupported("AdditionalTradeCost", Integer.class)
                ? icon(Material.EMERALD,
                        "Additional trade cost: " + modernTradeCost(meta),
                        "Enter 0 through 1000000; none clears")
                : icon(Material.BARRIER, "Additional trade cost unsupported on this server"));
        inventory.setItem(30, modernSetterSupported("MinimumAttackCharge", Float.class)
                ? icon(Material.IRON_SWORD,
                        "Minimum attack charge: " + modernOptionalComponent(meta, "MinimumAttackCharge"),
                        "Enter 0 through 1; none clears")
                : icon(Material.BARRIER, "Minimum attack charge unsupported on this server"));
        inventory.setItem(31, modernSetterSupported("BreakSound", org.bukkit.Sound.class)
                ? icon(Material.GLASS,
                        "Break sound: " + modernOptionalComponent(meta, "BreakSound"),
                        "Enter a sound key; none clears",
                        "Example: minecraft:block.glass.break")
                : icon(Material.BARRIER, "Break sound unsupported on this server"));
        inventory.setItem(32, modernSetterSupported("JukeboxPlayable",
                componentType("JukeboxPlayable"))
                ? icon(Material.MUSIC_DISC_CAT,
                        "Jukebox song: " + modernJukeboxSong(meta),
                        "Enter a registered song key; none clears",
                        "Example: minecraft:cat")
                : icon(Material.BARRIER, "Jukebox playable unsupported on this server"));
        inventory.setItem(33, modernSetterSupported("DamageResistant", org.bukkit.Tag.class)
                ? icon(Material.NETHERITE_CHESTPLATE,
                        "Damage resistance: " + modernDamageResistance(meta),
                        "Enter a registered damage type tag; none clears",
                        "Example: minecraft:is_fire")
                : icon(Material.BARRIER, "Damage resistance unsupported on this server"));
        if (draft.getType() == Material.PLAYER_HEAD && meta instanceof SkullMeta skull) {
            var profile = skull.getOwnerProfile();
            inventory.setItem(34, icon(Material.PLAYER_HEAD,
                    "Head profile: " + (profile == null ? "none" : profile.getName()),
                    "Enter player name and optional 64-character skin hash",
                    "Use the protected book; none clears"));
            inventory.setItem(35, icon(Material.NOTE_BLOCK,
                    "Head note sound: " + (skull.getNoteBlockSound() == null
                            ? "none" : skull.getNoteBlockSound()),
                    "Enter a namespaced sound key; none clears"));
        } else {
            inventory.setItem(34, icon(Material.BARRIER, "Head profile requires a player head"));
            inventory.setItem(35, icon(Material.BARRIER, "Head note sound requires a player head"));
        }
        player.openInventory(inventory);
    }

    private static Class<?> componentType(String suffix) {
        try { return ItemMeta.class.getMethod("get" + suffix).getReturnType(); }
        catch (NoSuchMethodException ex) { return null; }
    }

    private static boolean modernSetterSupported(String suffix, Class<?> argument) {
        if (argument == null) return false;
        try { ItemMeta.class.getMethod("set" + suffix, argument); return true; }
        catch (NoSuchMethodException ex) { return false; }
    }

    private static String modernJukeboxSong(ItemMeta meta) {
        try {
            if (!Boolean.TRUE.equals(ItemMeta.class.getMethod("hasJukeboxPlayable").invoke(meta))) {
                return "none";
            }
            Object component = ItemMeta.class.getMethod("getJukeboxPlayable").invoke(meta);
            return String.valueOf(componentType("JukeboxPlayable")
                    .getMethod("getSongKey").invoke(component));
        } catch (ReflectiveOperationException ex) {
            return "unavailable";
        }
    }

    private static String modernDamageResistance(ItemMeta meta) {
        try {
            if (!Boolean.TRUE.equals(ItemMeta.class.getMethod("hasDamageResistant").invoke(meta))) {
                return "none";
            }
            Object tag = ItemMeta.class.getMethod("getDamageResistant").invoke(meta);
            return tag instanceof org.bukkit.Tag<?> keyed ? keyed.getKey().toString() : "custom set";
        } catch (ReflectiveOperationException ex) {
            return "unavailable";
        }
    }

    private static String modernBoolean(ItemMeta meta, String suffix) {
        try {
            return String.valueOf(ItemMeta.class.getMethod("is" + suffix).invoke(meta));
        } catch (ReflectiveOperationException ex) {
            return "unsupported on this server";
        }
    }

    private static String modernOptionalComponent(ItemMeta meta, String suffix) {
        try {
            if (!Boolean.TRUE.equals(ItemMeta.class.getMethod("has" + suffix).invoke(meta))) {
                return "none";
            }
            return String.valueOf(ItemMeta.class.getMethod("get" + suffix).invoke(meta));
        } catch (ReflectiveOperationException ex) {
            return "unsupported on this server";
        }
    }

    private static String modernTradeCost(ItemMeta meta) {
        try { ItemMeta.class.getMethod("hasAdditionalTradeCost"); }
        catch (NoSuchMethodException ex) { return "unsupported on this server"; }
        Object value = meta.serialize().get("additional-trade-cost");
        return value == null ? "none" : value.toString();
    }

    private static String modernComponent(ItemMeta meta, String suffix) {
        try {
            Object value = ItemMeta.class.getMethod("get" + suffix).invoke(meta);
            return value == null ? "none" : value.toString();
        } catch (ReflectiveOperationException ex) {
            return "unsupported on this server";
        }
    }

    private static String modernComponentStatus(ItemMeta meta, String suffix) {
        try {
            return Boolean.TRUE.equals(ItemMeta.class.getMethod("has" + suffix).invoke(meta))
                    ? "set" : "none";
        } catch (NoSuchMethodException ex) {
            return "unsupported on this server";
        } catch (ReflectiveOperationException ex) {
            return "unavailable";
        }
    }

    private static ItemStack visualComponentControl(ItemMeta meta) {
        if (meta instanceof FireworkMeta firework) {
            return icon(Material.FIREWORK_ROCKET,
                    "Firework power " + firework.getPower() + ", effects " + firework.getEffectsSize(),
                    "Enter power #RRGGBB effect-type", "Example: 2 #AA44FF STAR",
                    "Type none to clear effects and power");
        }
        if (meta instanceof FireworkEffectMeta star) {
            return icon(Material.FIREWORK_STAR,
                    "Firework star effect: " + (star.hasEffect() ? star.getEffect().getType() : "none"),
                    "Enter effect-type #RRGGBB, such as BURST #AA44FF",
                    "Type none to clear the effect");
        }
        if (meta instanceof LeatherArmorMeta leather) {
            return icon(Material.LEATHER_CHESTPLATE,
                    "Leather color " + colorText(leather.getColor()),
                    "Enter a six-digit color such as #AA44FF");
        }
        if (meta instanceof PotionMeta potion) {
            return icon(Material.POTION,
                    "Potion color " + (potion.getColor() == null ? "none" : colorText(potion.getColor())),
                    "Enter #RRGGBB or none to clear custom color");
        }
        return icon(Material.BARRIER, "No supported color or firework effect on this item");
    }

    private static String colorText(org.bukkit.Color color) {
        return String.format(Locale.ROOT, "#%06X", color.asRGB());
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        if (!holder.owner.equals(player.getUniqueId())) { event.setCancelled(true); return; }
        int slot = event.getRawSlot();
        if (slot >= event.getView().getTopInventory().getSize()) {
            if (event.isShiftClick()) event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        if (slot < 0) return;
        String permission = switch (holder.menu) {
            case BOOK, DETAIL -> "recipeweaver.recipes";
            case SETTINGS, LOCAL_SQL, REMOTE_SQL, SQL_ORDER, SQL_LOCAL_CONFIRM,
                    VANILLA, BACKUPS, RESTORE_CONFIRM -> "recipeweaver.admin";
            default -> "recipeweaver.studio";
        };
        if (!player.hasPermission(permission)) return;
        switch (holder.menu) {
            case HOME -> clickHome(player, slot);
            case SETTINGS -> clickSettings(player, holder, slot);
            case WORKBENCH_CONDITIONS -> clickWorkbenchConditions(player, slot);
            case LOCAL_SQL -> clickLocalSql(player, holder, slot);
            case REMOTE_SQL -> clickRemoteSql(player, holder, slot);
            case SQL_ORDER -> clickSqlOrder(player, holder, slot, event.getClick());
            case SQL_LOCAL_CONFIRM -> clickSqlLocalConfirm(player, holder, slot);
            case VANILLA -> clickVanillaRecipes(player, holder, slot);
            case BACKUPS -> clickBackups(player, holder, slot);
            case RESTORE_CONFIRM -> clickRestoreConfirm(player, holder.backup, slot);
            case RECIPE -> clickRecipe(player, slot, event.getClick(), event.getCursor());
            case INGREDIENT -> clickWorkbenchIngredient(player, holder.page, slot,
                    event.getClick(), event.getCursor());
            case TEST -> clickTest(player, slot, event.getCursor());
            case BOOK -> clickBook(player, holder, slot, event.getClick());
            case DETAIL -> clickDetail(player, holder.recipe, slot);
            case ITEM -> clickItem(player, slot, event.getCursor());
            case ITEM_ADVANCED -> clickAdvancedItem(player, slot);
            case ITEM_ENCHANTS -> clickItemEnchants(player, slot);
            case ITEM_FLAGS -> clickItemFlags(player, slot);
            case ITEM_DATA -> clickItemData(player, holder.page, slot, event.getClick());
            case ITEM_DATA_COMPOUND -> clickItemCompoundData(player, holder.recipe,
                    holder.page, slot);
            case ITEM_LIST -> clickItemList(player, holder, slot);
        }
    }

    private void clickHome(Player player, int slot) {
        switch (slot) {
            case 10 -> startRecipe(player, RecipeKind.SHAPED);
            case 12 -> startRecipe(player, RecipeKind.SHAPELESS);
            case 14 -> {
                ItemStack held = player.getInventory().getItemInMainHand();
                if (held.getType().isAir()) {
                    player.sendMessage(ChatColor.RED + "Hold an item before opening the item editor.");
                    return;
                }
                itemDrafts.put(player.getUniqueId(), held.clone());
                itemDraftIds.remove(player.getUniqueId());
                openItemEditor(player);
            }
            case 16 -> openRecipeBook(player, 0);
            case 18 -> {
                if (player.hasPermission("recipeweaver.admin")) openSettings(player);
            }
            case 20 -> plugin.gridMenus().openHome(player);
            case 22 -> plugin.stationMenus().openTypes(player);
            case 24 -> openItems(player, 0);
            default -> { }
        }
    }

    private void clickRecipe(Player player, int slot, ClickType click, ItemStack cursor) {
        RecipeDraft draft = recipeDrafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        for (int i = 0; i < GRID.length; i++) {
            if (slot == GRID[i]) {
                if (click == ClickType.SHIFT_LEFT && draft.ingredients[i] != null) {
                    openWorkbenchIngredientEditor(player, i);
                    return;
                }
                if (click == ClickType.RIGHT && copyOne(cursor) == null && draft.ingredients[i] != null) {
                    if (draft.itemTags[i] != null && draft.matchModes[i] == IngredientMatchMode.MATERIAL) {
                        player.sendMessage(ChatColor.YELLOW + "Clear the item tag before using exact matching.");
                    } else {
                        draft.matchModes[i] = draft.matchModes[i] == IngredientMatchMode.EXACT
                                ? IngredientMatchMode.MATERIAL : IngredientMatchMode.EXACT;
                    }
                } else if (click == ClickType.LEFT || click == ClickType.RIGHT) {
                    ItemStack sample = copyOne(cursor);
                    if (sample == null || draft.ingredients[i] == null
                            || !draft.ingredients[i].isSimilar(sample)) {
                        draft.matchModes[i] = IngredientMatchMode.EXACT;
                        draft.alternatives.get(i).clear();
                        draft.itemTags[i] = null;
                    }
                    draft.ingredients[i] = sample;
                }
                refreshRecipeEditor(player);
                return;
            }
        }
        switch (slot) {
            case 24 -> { draft.result = copy(cursor); refreshRecipeEditor(player); }
            case 38 -> {
                draft.kind = draft.kind == RecipeKind.SHAPED ? RecipeKind.SHAPELESS : RecipeKind.SHAPED;
                refreshRecipeEditor(player);
            }
            case 39 -> {
                CraftingBookCategory[] categories = CraftingBookCategory.values();
                draft.bookCategory = categories[(draft.bookCategory.ordinal() + 1) % categories.length];
                refreshRecipeEditor(player);
            }
            case 40 -> textPrompt.open(player, "Recipe group", draft.group, value -> {
                draft.group = groupInput(value);
                openRecipeEditor(player);
            }, () -> openRecipeEditor(player));
            case 41 -> { draft.hidden = !draft.hidden; refreshRecipeEditor(player); }
            case 42 -> { draft.enabled = !draft.enabled; refreshRecipeEditor(player); }
            case 43 -> openWorkbenchConditionsEditor(player);
            case 45 -> { recipeDrafts.remove(player.getUniqueId()); testMatrices.remove(player.getUniqueId()); openHome(player); }
            case 49 -> {
                if (!hasRecipeIngredient(draft) || draft.result == null) {
                    player.sendMessage(ChatColor.YELLOW + "Add an ingredient in the blue 3x3 grid "
                            + "and a result in the green slot before saving.");
                    return;
                }
                textPrompt.open(player, "Recipe ID", draft.existingKey == null ? "new_recipe" : draft.existingKey.getKey(), value -> saveRecipe(player, draft, value),
                        () -> openRecipeEditor(player));
            }
            case 51 -> {
                ItemStack[] matrix = new ItemStack[9];
                for (int i = 0; i < 9; i++) matrix[i] = copyOne(draft.ingredients[i]);
                testMatrices.put(player.getUniqueId(), matrix);
                openTest(player);
            }
            default -> { }
        }
    }

    private void openWorkbenchIngredientEditor(Player player, int index) {
        RecipeDraft draft = recipeDrafts.get(player.getUniqueId());
        if (draft == null || draft.ingredients[index] == null) { openRecipeEditor(player); return; }
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.INGREDIENT, index, null);
        Inventory inventory = create(holder, 54, "Workbench Ingredient Choices");
        List<ItemStack> choices = draft.alternatives.get(index);
        for (int i = 0; i < GridRecipeDefinition.MAX_ALTERNATIVES_PER_SLOT; i++) {
            inventory.setItem(i, i < choices.size() ? showWorkbenchAlternative(choices.get(i))
                    : icon(Material.GRAY_STAINED_GLASS_PANE, "Alternative " + (i + 1),
                            "Click with an item on your cursor to add"));
        }
        inventory.setItem(16, showWorkbenchIngredient(draft.ingredients[index], draft.matchModes[index],
                choices.size(), draft.itemTags[index]));
        inventory.setItem(17, icon(Material.PAPER,
                "Alternatives: " + choices.size() + "/" + GridRecipeDefinition.MAX_ALTERNATIVES_PER_SLOT,
                "Click with a cursor item to add or replace",
                "Click with an empty cursor to remove",
                "Native workbench choices consume one item"));
        inventory.setItem(26, icon(Material.NAME_TAG,
                "Item tag: " + (draft.itemTags[index] == null ? "none" : draft.itemTags[index]),
                "Enter a namespaced Bukkit item tag",
                "A tag uses material matching"));
        inventory.setItem(35, icon(Material.BARRIER, "Clear item tag"));
        inventory.setItem(44, icon(Material.COMPARATOR,
                "Match mode: " + draft.matchModes[index], "Click to switch exact / material"));
        inventory.setItem(53, icon(Material.ARROW, "Back to recipe editor"));
        player.openInventory(inventory);
    }

    private void openItemEnchants(Player player) {
        ItemStack draft = itemDrafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.ITEM_ENCHANTS, 0, null);
        Inventory inventory = create(holder, 54, "Item Enchantments");
        List<org.bukkit.enchantments.Enchantment> enchants = itemEnchantments(draft);
        for (int i = 0; i < enchants.size() && i < 45; i++) {
            var enchant = enchants.get(i);
            inventory.setItem(i, icon(Material.ENCHANTED_BOOK, enchant.getKey().toString()
                    + " " + draft.getItemMeta().getEnchantLevel(enchant), "Click to remove"));
        }
        inventory.setItem(49, icon(Material.ANVIL, "Add or change enchantment",
                "Enter namespace:key level, 1 through 255", "Level 0 removes that enchantment"));
        inventory.setItem(53, icon(Material.ARROW, "Back to item editor"));
        player.openInventory(inventory);
    }

    private void openItemFlags(Player player) {
        ItemStack draft = itemDrafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.ITEM_FLAGS, 0, null);
        Inventory inventory = create(holder, 54, "Item Tooltip Flags");
        ItemFlag[] flags = ItemFlag.values();
        for (int i = 0; i < flags.length && i < 45; i++) {
            boolean enabled = draft.getItemMeta().hasItemFlag(flags[i]);
            inventory.setItem(i, icon(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                    flags[i].name() + ": " + enabled, "Click to switch"));
        }
        inventory.setItem(53, icon(Material.ARROW, "Back to item editor"));
        player.openInventory(inventory);
    }

    private void openItemData(Player player, int page) {
        ItemStack draft = itemDrafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        List<ItemDraftEditor.PersistentEntry> entries = ItemDraftEditor.persistentEntries(draft);
        int maxPage = Math.max(0, (entries.size() - 1) / 45);
        page = Math.max(0, Math.min(page, maxPage));
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.ITEM_DATA, page, null);
        Inventory inventory = create(holder, 54, "Item Persistent Data " + (page + 1));
        for (int slot = 0; slot < 45 && page * 45 + slot < entries.size(); slot++) {
            ItemDraftEditor.PersistentEntry entry = entries.get(page * 45 + slot);
            inventory.setItem(slot, icon(Material.PAPER, entry.key().toString(),
                    "Type: " + entry.type(), "Value: " + entry.preview(),
                    entry.type().equals("compound") || entry.type().equals("compoundarray")
                            ? "Right click to browse children; left click to edit"
                            : "Click to change or remove"));
        }
        if (page > 0) inventory.setItem(45, icon(Material.ARROW, "Previous page"));
        inventory.setItem(49, icon(Material.WRITABLE_BOOK, "Add or change data",
                "Line 1: namespace:key", "Line 2: value, @type value, or none",
                "Types: byte, short, int, long, float, double",
                "Arrays: bytes (hex), ints, longs (comma-separated)",
                "Lists: @bytelist, @shortlist, @floatlist, @doublelist",
                "Array lists: @bytearraylist, @intarraylist, @longarraylist",
                "Nested: @compound child>grandchild @type value",
                "Arrays: @compoundarray append child @type value"));
        inventory.setItem(52, icon(Material.ARROW, "Back to item editor"));
        if (page < maxPage) inventory.setItem(53, icon(Material.ARROW, "Next page"));
        player.openInventory(inventory);
    }

    private void openItemCompoundData(Player player, NamespacedKey outerKey, int requestedPage) {
        ItemStack draft = itemDrafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        boolean array = draft.getItemMeta().getPersistentDataContainer().has(outerKey,
                org.bukkit.persistence.PersistentDataType.TAG_CONTAINER_ARRAY);
        List<ItemDraftEditor.PersistentEntry> entries = array
                ? ItemDraftEditor.persistentCompoundArrayEntries(draft, outerKey)
                : ItemDraftEditor.persistentCompoundEntries(draft, outerKey);
        int maxPage = Math.max(0, (entries.size() - 1) / 45);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.ITEM_DATA_COMPOUND,
                page, outerKey);
        Inventory inventory = create(holder, 54,
                (array ? "Item Compound Array " : "Item Compound Data ") + (page + 1));
        inventory.setItem(50, icon(Material.BOOKSHELF,
                (array ? "Compound array: " : "Compound: ") + outerKey,
                array ? "Elements and typed leaves show their indexes"
                        : "Nested leaves are shown with their full key path"));
        for (int slot = 0; slot < 45 && page * 45 + slot < entries.size(); slot++) {
            ItemDraftEditor.PersistentEntry entry = entries.get(page * 45 + slot);
            inventory.setItem(slot, icon(Material.PAPER, entry.key().toString(),
                    "Type: " + entry.type(), "Value: " + entry.preview(),
                    "Click to change or remove this child"));
        }
        if (page > 0) inventory.setItem(45, icon(Material.ARROW, "Previous page"));
        inventory.setItem(49, icon(Material.WRITABLE_BOOK, "Add or change child",
                "Line 1: " + outerKey,
                array ? "Line 2: @compoundarray append child @type value"
                        : "Line 2: @compound child[>grandchild] @type value",
                array ? "Use @compoundarray index child none or remove index"
                        : "Use @compound child[>grandchild] none to remove"));
        inventory.setItem(52, icon(Material.ARROW, "Back to persistent data"));
        if (page < maxPage) inventory.setItem(53, icon(Material.ARROW, "Next page"));
        player.openInventory(inventory);
    }

    private static List<org.bukkit.enchantments.Enchantment> itemEnchantments(ItemStack item) {
        List<org.bukkit.enchantments.Enchantment> result = new ArrayList<>(
                item.getItemMeta().getEnchants().keySet());
        result.sort(Comparator.comparing(enchant -> enchant.getKey().toString()));
        return result;
    }

    private void clickWorkbenchIngredient(Player player, int index, int slot,
                                           ClickType click, ItemStack cursor) {
        RecipeDraft draft = recipeDrafts.get(player.getUniqueId());
        if (draft == null || draft.ingredients[index] == null) { openRecipeEditor(player); return; }
        if (slot == 53) { openRecipeEditor(player); return; }
        if (slot >= 0 && slot < GridRecipeDefinition.MAX_ALTERNATIVES_PER_SLOT) {
            if (click != ClickType.LEFT && click != ClickType.RIGHT) return;
            List<ItemStack> choices = draft.alternatives.get(index);
            ItemStack alternative = copyOne(cursor);
            if (alternative != null) {
                if (slot < choices.size()) choices.set(slot, alternative);
                else if (choices.size() < GridRecipeDefinition.MAX_ALTERNATIVES_PER_SLOT) {
                    choices.add(alternative);
                }
            } else if (slot < choices.size()) choices.remove(slot);
            openWorkbenchIngredientEditor(player, index);
            return;
        }
        if (slot == 26) {
            textPrompt.open(player, "Item tag", draft.itemTags[index] == null
                    ? "minecraft:planks" : draft.itemTags[index].toString(), value -> {
                if (!player.hasPermission("recipeweaver.studio")) return;
                String raw = value.toLowerCase(Locale.ROOT);
                NamespacedKey key = raw.contains(":") ? NamespacedKey.fromString(raw) : null;
                Tag<Material> tag = key == null ? null
                        : Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
                if (tag == null || tag.getValues().isEmpty()) {
                    player.sendMessage(ChatColor.RED + "Enter an existing namespaced item tag.");
                } else {
                    draft.itemTags[index] = key;
                    draft.matchModes[index] = IngredientMatchMode.MATERIAL;
                }
                openWorkbenchIngredientEditor(player, index);
            }, () -> openWorkbenchIngredientEditor(player, index));
        } else if (slot == 35) {
            draft.itemTags[index] = null;
            openWorkbenchIngredientEditor(player, index);
        } else if (slot == 44) {
            if (draft.itemTags[index] != null && draft.matchModes[index] == IngredientMatchMode.MATERIAL) {
                player.sendMessage(ChatColor.YELLOW + "Clear the item tag before using exact matching.");
            } else {
                draft.matchModes[index] = draft.matchModes[index] == IngredientMatchMode.EXACT
                        ? IngredientMatchMode.MATERIAL : IngredientMatchMode.EXACT;
            }
            openWorkbenchIngredientEditor(player, index);
        }
    }

    private static ItemStack showWorkbenchIngredient(ItemStack item, IngredientMatchMode mode,
                                                      int alternatives, NamespacedKey tag) {
        ItemStack shown = item.clone();
        ItemMeta meta = shown.getItemMeta();
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(ChatColor.AQUA + "Match: " + mode.name().toLowerCase(Locale.ROOT));
        if (alternatives > 0) lore.add(ChatColor.AQUA + "Alternatives: " + alternatives);
        if (tag != null) lore.add(ChatColor.AQUA + "Item tag: " + tag);
        meta.setLore(lore);
        shown.setItemMeta(meta);
        return shown;
    }

    private static ItemStack showWorkbenchAlternative(ItemStack item) {
        ItemStack shown = item.clone();
        ItemMeta meta = shown.getItemMeta();
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(ChatColor.GRAY + "Click with a cursor item to replace");
        lore.add(ChatColor.GRAY + "Click with an empty cursor to remove");
        meta.setLore(lore);
        shown.setItemMeta(meta);
        return shown;
    }

    private void openTest(Player player) {
        RecipeDraft draft = recipeDrafts.get(player.getUniqueId());
        ItemStack[] matrix = testMatrices.get(player.getUniqueId());
        if (draft == null || matrix == null) { openRecipeEditor(player); return; }
        MenuHolder holder = new MenuHolder(player.getUniqueId(), Menu.TEST, 0, null);
        Inventory inventory = create(holder, 54, "RecipeWeaver Match Preview");
        for (int i = 0; i < 9; i++) {
            if (matrix[i] != null) inventory.setItem(GRID[i], matrix[i].clone());
        }
        boolean matches = RecipeMatcher.matches(draft.kind, Arrays.asList(draft.ingredients),
                Arrays.asList(draft.matchModes), draft.alternatives,
                Arrays.asList(draft.itemTags), Arrays.asList(matrix));
        inventory.setItem(24, matches && draft.result != null ? draft.result.clone()
                : icon(Material.BARRIER, "No result", draft.result == null ? "Set a result in the editor" : "Inputs do not match"));
        inventory.setItem(40, icon(matches ? Material.LIME_DYE : Material.RED_DYE,
                matches ? "Ingredients match" : "Ingredients do not match",
                "Preview only; no items are consumed"));
        inventory.setItem(45, icon(Material.ARROW, "Back to editor"));
        inventory.setItem(51, icon(Material.CLOCK, "Reset test inputs", "Copy ingredients from the draft"));
        player.openInventory(inventory);
    }

    private void clickTest(Player player, int slot, ItemStack cursor) {
        RecipeDraft draft = recipeDrafts.get(player.getUniqueId());
        ItemStack[] matrix = testMatrices.get(player.getUniqueId());
        if (draft == null || matrix == null) { openRecipeEditor(player); return; }
        for (int i = 0; i < GRID.length; i++) {
            if (slot == GRID[i]) {
                matrix[i] = copyOne(cursor);
                openTest(player);
                return;
            }
        }
        if (slot == 45) {
            testMatrices.remove(player.getUniqueId());
            openRecipeEditor(player);
        } else if (slot == 51) {
            for (int i = 0; i < 9; i++) matrix[i] = copyOne(draft.ingredients[i]);
            openTest(player);
        }
    }

    private void saveRecipe(Player player, RecipeDraft draft, String rawId) {
        if (!player.hasPermission("recipeweaver.studio")) return;
        NamespacedKey key = NamespacedKey.fromString(rawId.toLowerCase(java.util.Locale.ROOT), plugin);
        if (key == null || !key.getNamespace().equals("recipeweaver")) {
            player.sendMessage(ChatColor.RED + "Use a RecipeWeaver ID with lowercase letters, digits, _, -, or /. ");
            openRecipeEditor(player);
            return;
        }
        if (draft.existingKey != null && !draft.existingKey.equals(key)) {
            player.sendMessage(ChatColor.RED + "Rename is not supported; keep the original recipe ID.");
            openRecipeEditor(player);
            return;
        }
        if (plugin.recipe(key).isPresent() && !key.equals(draft.existingKey)) {
            player.sendMessage(ChatColor.RED + "That recipe ID already exists.");
            openRecipeEditor(player);
            return;
        }
        try {
            RecipeDefinition recipe = new RecipeDefinition(key, draft.kind, Arrays.asList(draft.ingredients),
                    draft.result, draft.group, draft.enabled, draft.hidden,
                    Arrays.asList(draft.matchModes), draft.alternatives,
                    Arrays.asList(draft.itemTags), draft.bookCategory, draft.conditions());
            UUID playerId = player.getUniqueId();
            if (!pendingRecipeWrites.add(playerId)) {
                player.sendMessage(ChatColor.YELLOW + "Recipe save is still in progress.");
                return;
            }
            try {
                plugin.saveRecipeAsync(recipe).whenComplete((ignored, failure) -> {
                    pendingRecipeWrites.remove(playerId);
                    if (!player.isOnline()) return;
                    if (failure == null) {
                        recipeDrafts.remove(playerId);
                        testMatrices.remove(playerId);
                        player.sendMessage(ChatColor.GREEN + "Saved recipe " + key);
                        openHome(player);
                    } else {
                        player.sendMessage(ChatColor.RED + "Recipe save failed: " + failure.getMessage());
                        openRecipeEditor(player);
                    }
                });
            } catch (RuntimeException ex) {
                pendingRecipeWrites.remove(playerId);
                throw ex;
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            player.sendMessage(ChatColor.RED + "Cannot save recipe: " + ex.getMessage());
            openRecipeEditor(player);
        }
    }

    private void clickBook(Player player, MenuHolder holder, int slot, ClickType click) {
        int page = holder.page;
        if (slot < 45) {
            NamespacedKey shown = holder.bookPage == null ? null : holder.bookPage.keyAt(slot);
            if (shown == null) return;
            BookEntry current = bookEntries(player).stream()
                    .filter(entry -> entry.key().equals(shown)).findFirst().orElse(null);
            if (current == null) openRecipeBook(player, page);
            else openRecipeDetail(player, current);
        } else if (slot == 45) openRecipeBook(player, page - 1);
        else if (slot == 46) {
            BookCategory current = bookCategories.getOrDefault(player.getUniqueId(), BookCategory.ALL);
            bookCategories.put(player.getUniqueId(), current.next());
            openRecipeBook(player, 0);
        } else if (slot == 48) {
            BookSort current = bookSorts.getOrDefault(player.getUniqueId(), BookSort.KEY);
            bookSorts.put(player.getUniqueId(), current.next());
            openRecipeBook(player, 0);
        } else if (slot == 50 && plugin.bookItemEnabled()) plugin.bookItems().give(player);
        else if (slot == 52) {
            BookDiscovery current = bookDiscoveries.getOrDefault(player.getUniqueId(), BookDiscovery.ALL);
            bookDiscoveries.put(player.getUniqueId(), current.next());
            openRecipeBook(player, 0);
        } else if (slot == 53) openRecipeBook(player, page + 1);
        else if (slot == 47) {
            if (click.isRightClick()) {
                bookQueries.remove(player.getUniqueId());
                openRecipeBook(player, 0);
            } else textPrompt.open(player, "Search recipes",
                    bookQueries.getOrDefault(player.getUniqueId(), "search"), value -> {
                        bookQueries.put(player.getUniqueId(), value.toLowerCase(java.util.Locale.ROOT));
                        openRecipeBook(player, 0);
                    }, () -> openRecipeBook(player, page));
        }
        else if (slot == 51) {
            List<String> available = BookGroupFilter.available(
                    bookEntriesWithoutGroup(player).stream().map(BookEntry::group).toList());
            String next = BookGroupFilter.next(bookGroups.get(player.getUniqueId()), available);
            if (next == null) bookGroups.remove(player.getUniqueId());
            else bookGroups.put(player.getUniqueId(), next);
            openRecipeBook(player, 0);
        } else if (slot == 49) {
            if (click.isRightClick()) player.closeInventory();
            else if (player.hasPermission("recipeweaver.studio")) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && player.hasPermission("recipeweaver.studio")) openHome(player);
                });
            }
            else player.sendMessage(ChatColor.RED + "Missing permission: recipeweaver.studio");
        }
    }
    private void clickDetail(Player player, NamespacedKey key, int slot) {
        if (slot == 49) { openRecipeBook(player, 0); return; }
        if (!player.hasPermission("recipeweaver.studio")) return;
        if (slot == 45) {
            RecipeDefinition workbench = plugin.recipe(key).orElse(null);
            if (workbench != null) {
                recipeDrafts.put(player.getUniqueId(), new RecipeDraft(workbench));
                openRecipeEditor(player);
                return;
            }
            StationRecipeDefinition station = plugin.stationRecipe(key).orElse(null);
            if (station != null) plugin.stationMenus().openExisting(player, station);
        } else if (slot == 53) {
            textPrompt.open(player, "Type DELETE to confirm", "type_here", value -> {
                if (!player.hasPermission("recipeweaver.studio")) return;
                if (!value.equals("DELETE")) {
                    player.sendMessage(ChatColor.RED + "Deletion cancelled.");
                    openRecipeBook(player, 0);
                    return;
                }
                if (plugin.recipe(key).isPresent() || plugin.stationRecipe(key).isPresent()) {
                    UUID playerId = player.getUniqueId();
                    if (!pendingRecipeWrites.add(playerId)) return;
                    try {
                        (plugin.recipe(key).isPresent() ? plugin.removeRecipeAsync(key)
                                : plugin.removeStationRecipeAsync(key)).whenComplete((removed, failure) -> {
                            pendingRecipeWrites.remove(playerId);
                            if (!player.isOnline()) return;
                            if (failure != null) {
                                player.sendMessage(ChatColor.RED + "Recipe delete failed: " + failure.getMessage());
                            } else if (removed) {
                                player.sendMessage(ChatColor.GREEN + "Deleted recipe " + key);
                            } else player.sendMessage(ChatColor.RED + "Recipe no longer exists.");
                            openRecipeBook(player, 0);
                        });
                    } catch (RuntimeException ex) {
                        pendingRecipeWrites.remove(playerId);
                        player.sendMessage(ChatColor.RED + "Recipe delete failed: " + ex.getMessage());
                        openRecipeBook(player, 0);
                    }
                    return;
                }
                player.sendMessage(ChatColor.RED + "Recipe no longer exists.");
                openRecipeBook(player, 0);
            }, () -> openRecipeBook(player, 0));
        }
    }
    private void clickItemList(Player player, MenuHolder holder, int slot) {
        int page = holder.page;
        if (slot < 45) {
            NamespacedKey key = holder.itemPage == null ? null : holder.itemPage.keyAt(slot);
            if (key == null) return;
            ItemStack item = plugin.items().get(key).orElse(null);
            if (item == null) { openItems(player, page); return; }
            itemDrafts.put(player.getUniqueId(), item);
            itemDraftIds.put(player.getUniqueId(), key);
            openItemEditor(player);
        } else if (slot == 45) openItems(player, page - 1);
        else if (slot == 53) openItems(player, page + 1);
        else if (slot == 49) openHome(player);
    }
    private void clickItem(Player player, int slot, ItemStack cursor) {
        if (pendingItemWrites.contains(player.getUniqueId())) {
            player.sendMessage(ChatColor.YELLOW + "Custom item save is still in progress.");
            return;
        }
        ItemStack draft = itemDrafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        switch (slot) {
            case 8 -> bookPrompt.open(player,
                    "minecraft:paper[minecraft:custom_data={recipeweaver:{level:7}}]", value -> {
                if (!player.hasPermission("recipeweaver.studio")
                        || pendingItemWrites.contains(player.getUniqueId())) return;
                try {
                    itemDrafts.put(player.getUniqueId(), ItemDraftEditor.parseItemText(value));
                    player.sendMessage(ChatColor.GREEN + "Minecraft item text imported into draft.");
                } catch (IllegalArgumentException | IllegalStateException ex) {
                    player.sendMessage(ChatColor.RED + "Item text was not imported: " + ex.getMessage());
                }
                if (player.isOnline()) openItemEditor(player);
            }, () -> openItemEditor(player));
            case 10 -> textPrompt.open(player, "Item name", draft.getItemMeta().hasDisplayName()
                    ? draft.getItemMeta().getDisplayName() : draft.getType().name(), value -> {
                ItemMeta meta = draft.getItemMeta();
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', value));
                draft.setItemMeta(meta);
                openItemEditor(player);
            }, () -> openItemEditor(player));
            case 11 -> {
                ItemMeta meta = draft.getItemMeta();
                String initial = meta.hasLore() ? String.join("\n", meta.getLore()).replace('§', '&')
                        : "New lore line";
                bookPrompt.open(player, initial, value ->
                        applyItemEdit(player, draft, item -> ItemDraftEditor.replaceLore(item, value)),
                        () -> openItemEditor(player));
            }
            case 12 -> {
                if (!(draft.getItemMeta() instanceof Damageable damageable)
                        || draft.getType().getMaxDurability() <= 0) return;
                textPrompt.open(player, "Item damage", Integer.toString(damageable.getDamage()),
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setDamage(item, value)),
                        () -> openItemEditor(player));
            }
            case 13 -> {
                ItemStack replacement = copy(cursor);
                if (replacement != null) itemDrafts.put(player.getUniqueId(), replacement);
                openItemEditor(player);
            }
            case 14 -> openItemEnchants(player);
            case 15 -> { draft.setAmount(Math.max(1, draft.getAmount() - 1)); openItemEditor(player); }
            case 16 -> { draft.setAmount(Math.min(draft.getMaxStackSize(), draft.getAmount() + 1)); openItemEditor(player); }
            case 17 -> applyItemEdit(player, draft, ItemDraftEditor::toggleUnbreakable);
            case 18 -> {
                boolean existing = itemDraftIds.remove(player.getUniqueId()) != null;
                itemDrafts.remove(player.getUniqueId());
                if (existing) openItems(player, 0);
                else openHome(player);
            }
            case 19 -> {
                if (!(draft.getItemMeta() instanceof ArmorMeta armor)) return;
                String initial = armor.hasTrim() ? armor.getTrim().getMaterial().getKey()
                        + " " + armor.getTrim().getPattern().getKey() : "none";
                textPrompt.open(player, "Armor trim material pattern", initial,
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setTrim(item, value)),
                        () -> openItemEditor(player));
            }
            case 20 -> {
                ItemMeta meta = draft.getItemMeta();
                textPrompt.open(player, "Custom model data", meta.hasCustomModelData()
                                ? Integer.toString(meta.getCustomModelData()) : "none",
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setCustomModelData(item, value)),
                        () -> openItemEditor(player));
            }
            case 21 -> openItemFlags(player);
            case 22 -> {
                NamespacedKey existing = itemDraftIds.get(player.getUniqueId());
                textPrompt.open(player, "Custom item ID", existing == null ? "new_item" : existing.getKey(),
                        value -> saveItem(player, draft, value), () -> openItemEditor(player));
            }
            case 23 -> textPrompt.open(player, "Import item ID", "itemsadder:namespace/item", value -> {
                try {
                    itemDrafts.put(player.getUniqueId(),
                            ItemDraftEditor.resolveImport(value, plugin::resolveItem));
                    player.sendMessage(ChatColor.GREEN + "Imported item " + value + " into this draft.");
                } catch (IllegalArgumentException ex) {
                    player.sendMessage(ChatColor.RED + ex.getMessage());
                }
                openItemEditor(player);
            }, () -> openItemEditor(player));
            case 24 -> openItemData(player, 0);
            case 27 -> openAdvancedItemEditor(player);
            case 25 -> {
                ItemMeta meta = draft.getItemMeta();
                String initial;
                if (meta instanceof FireworkMeta firework) {
                    initial = firework.getPower() + " #AA44FF STAR";
                } else if (meta instanceof FireworkEffectMeta star) {
                    initial = star.hasEffect() && !star.getEffect().getColors().isEmpty()
                            ? star.getEffect().getType().name() + " "
                                    + colorText(star.getEffect().getColors().get(0))
                            : "BURST #AA44FF";
                } else if (meta instanceof LeatherArmorMeta leather) {
                    initial = colorText(leather.getColor());
                } else if (meta instanceof PotionMeta potion) {
                    initial = potion.getColor() == null ? "#AA44FF" : colorText(potion.getColor());
                } else return;
                textPrompt.open(player, "Item color or effect", initial, value ->
                        applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setVisualComponent(item, value)),
                        () -> openItemEditor(player));
            }
            case 26 -> {
                NamespacedKey existing = itemDraftIds.get(player.getUniqueId());
                if (existing == null) return;
                textPrompt.open(player, "Type DELETE to confirm", "type_here", value -> {
                    if (!player.hasPermission("recipeweaver.studio")) return;
                    if (!value.equals("DELETE")) {
                        player.sendMessage(ChatColor.RED + "Deletion cancelled.");
                        openItemEditor(player);
                        return;
                    }
                    UUID playerId = player.getUniqueId();
                    if (!pendingItemWrites.add(playerId)) return;
                    try {
                        plugin.removeCustomItem(existing).whenComplete((removed, failure) -> {
                            pendingItemWrites.remove(playerId);
                            if (!player.isOnline()) return;
                            if (failure != null) {
                                player.sendMessage(ChatColor.RED + "Item delete failed: " + failure.getMessage());
                                openItemEditor(player);
                            } else if (!removed) {
                                player.sendMessage(ChatColor.RED + "Custom item no longer exists.");
                                openItems(player, 0);
                            } else {
                                itemDrafts.remove(playerId);
                                itemDraftIds.remove(playerId);
                                player.sendMessage(ChatColor.GREEN + "Deleted custom item " + existing);
                                openItems(player, 0);
                            }
                        });
                    } catch (RuntimeException ex) {
                        pendingItemWrites.remove(playerId);
                        player.sendMessage(ChatColor.RED + "Item delete failed: " + ex.getMessage());
                        openItemEditor(player);
                    }
                }, () -> openItemEditor(player));
            }
            case 28 -> applyItemEdit(player, draft, ItemDraftEditor::cycleEnchantmentGlint);
            case 29 -> applyItemEdit(player, draft, ItemDraftEditor::toggleFireResistance);
            case 30 -> applyItemEdit(player, draft, ItemDraftEditor::toggleHideTooltip);
            case 31 -> {
                ItemMeta meta = draft.getItemMeta();
                textPrompt.open(player, "Max stack size", meta.hasMaxStackSize()
                                ? Integer.toString(meta.getMaxStackSize()) : "none",
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setMaxStackSize(item, value)),
                        () -> openItemEditor(player));
            }
            case 32 -> textPrompt.open(player, "Item rarity", "rare",
                    value -> applyItemEdit(player, draft,
                            item -> ItemDraftEditor.setRarity(item, value)),
                    () -> openItemEditor(player));
            case 33 -> {
                if (!(draft.getItemMeta() instanceof PotionMeta potion)) return;
                textPrompt.open(player, "Base potion type", potion.hasBasePotionType()
                                ? potion.getBasePotionType().name() : "HEALING",
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setPotionBase(item, value)),
                        () -> openItemEditor(player));
            }
            case 34 -> {
                if (!(draft.getItemMeta() instanceof PotionMeta potion)) return;
                String initial = potion.getCustomEffects().isEmpty() ? "minecraft:speed 200 1"
                        : potion.getCustomEffects().get(0).getType().getKey() + " "
                        + potion.getCustomEffects().get(0).getDuration() + " "
                        + potion.getCustomEffects().get(0).getAmplifier();
                textPrompt.open(player, "Potion effect", initial,
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setPotionEffect(item, value)),
                        () -> openItemEditor(player));
            }
            case 35 -> {
                if (draft.getType() != Material.WRITTEN_BOOK
                        || !(draft.getItemMeta() instanceof BookMeta book)) return;
                textPrompt.open(player, "Book title", book.hasTitle() ? book.getTitle() : "New title",
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setBookTitle(item, value)),
                        () -> openItemEditor(player));
            }
            case 36 -> {
                if (draft.getType() != Material.WRITTEN_BOOK
                        || !(draft.getItemMeta() instanceof BookMeta book)) return;
                textPrompt.open(player, "Book author", book.hasAuthor() ? book.getAuthor() : "Author",
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setBookAuthor(item, value)),
                        () -> openItemEditor(player));
            }
            case 37 -> {
                if (!(draft.getItemMeta() instanceof WritableBookMeta book)) return;
                String initial = book.getPageCount() == 0 ? "New page"
                        : String.join("\n---PAGE---\n", book.getPages());
                bookPrompt.open(player, initial,
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setBookPages(item, value)),
                        () -> openItemEditor(player));
            }
            case 38 -> {
                ItemMeta meta = draft.getItemMeta();
                String initial = "4 0.6 false 1.6";
                if (meta.hasFood()) {
                    FoodComponent food = meta.getFood();
                    initial = food.getNutrition() + " " + food.getSaturation() + " "
                            + food.canAlwaysEat() + " " + food.getEatSeconds();
                }
                textPrompt.open(player, "Food properties", initial,
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setFoodProperties(item, value)),
                        () -> openItemEditor(player));
            }
            case 39 -> {
                if (!draft.getItemMeta().hasFood()) return;
                textPrompt.open(player, "Food effect", "minecraft:speed 200 1 0.5",
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setFoodEffect(item, value)),
                        () -> openItemEditor(player));
            }
            case 40 -> {
                ItemMeta meta = draft.getItemMeta();
                String initial = "4.0 1";
                if (meta.hasTool()) {
                    ToolComponent tool = meta.getTool();
                    initial = tool.getDefaultMiningSpeed() + " " + tool.getDamagePerBlock();
                }
                textPrompt.open(player, "Tool properties", initial,
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setToolProperties(item, value)),
                        () -> openItemEditor(player));
            }
            case 41 -> {
                if (!draft.getItemMeta().hasTool()) return;
                textPrompt.open(player, "Tool block rule", "STONE 8.0 true",
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setToolRule(item, value)),
                        () -> openItemEditor(player));
            }
            case 42 -> {
                if (!(draft.getItemMeta() instanceof MapMeta map)) return;
                textPrompt.open(player, "Map color", map.hasColor()
                                ? colorText(map.getColor()) : "#AA44FF",
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setMapColor(item, value)),
                        () -> openItemEditor(player));
            }
            case 43 -> {
                if (!(draft.getItemMeta() instanceof MapMeta map)) return;
                textPrompt.open(player, "Map ID", map.hasMapId()
                                ? Integer.toString(map.getMapId()) : "0",
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setMapId(item, value)),
                        () -> openItemEditor(player));
            }
            case 44 -> applyItemEdit(player, draft, ItemDraftEditor::toggleMapScaling);
            case 45 -> {
                if (draft.getItemMeta() instanceof MapMeta map) {
                    String label = MapLocationLabel.read(map);
                    textPrompt.open(player, "Map location label",
                            label == null ? "none" : label,
                            value -> applyItemEdit(player, draft,
                                    item -> ItemDraftEditor.setMapLocationName(item, value)),
                            () -> openItemEditor(player));
                    return;
                }
                if (!(draft.getItemMeta() instanceof CompassMeta compass)) return;
                String initial = "world 0 64 0";
                if (compass.hasLodestone()) {
                    org.bukkit.Location target = compass.getLodestone();
                    if (target != null && target.getWorld() != null) {
                        initial = target.getWorld().getName() + " " + target.getBlockX()
                                + " " + target.getBlockY() + " " + target.getBlockZ();
                    }
                }
                textPrompt.open(player, "Compass lodestone", initial,
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setCompassLodestone(item, value)),
                        () -> openItemEditor(player));
            }
            case 46 -> applyItemEdit(player, draft, ItemDraftEditor::toggleCompassTracking);
            case 47 -> {
                if (!(draft.getItemMeta() instanceof CrossbowMeta crossbow)) return;
                String initial = crossbow.hasChargedProjectiles()
                        ? crossbow.getChargedProjectiles().get(0).getType().name() : "ARROW";
                textPrompt.open(player, "Crossbow projectile", initial,
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setCrossbowProjectile(item, value)),
                        () -> openItemEditor(player));
            }
            case 48 -> {
                if (!(draft.getItemMeta() instanceof BannerMeta)) return;
                textPrompt.open(player, "Banner pattern", "RED CIRCLE",
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.editBannerPattern(item, value)),
                        () -> openItemEditor(player));
            }
            case 49 -> {
                if (!(draft.getItemMeta() instanceof SuspiciousStewMeta)) return;
                textPrompt.open(player, "Stew effect", "minecraft:speed 200",
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setStewEffect(item, value)),
                        () -> openItemEditor(player));
            }
            case 50 -> {
                if (!(draft.getItemMeta() instanceof TropicalFishBucketMeta fish)) return;
                String initial = fish.hasVariant() ? fish.getBodyColor() + " "
                        + fish.getPatternColor() + " " + fish.getPattern() : "RED BLUE KOB";
                textPrompt.open(player, "Fish variant", initial,
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setTropicalFishVariant(item, value)),
                        () -> openItemEditor(player));
            }
            case 51 -> {
                if (!(draft.getItemMeta() instanceof FireworkMeta)) return;
                textPrompt.open(player, "Firework effect", "BURST #AA44FF",
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.editFireworkEffect(item, value)),
                        () -> openItemEditor(player));
            }
            case 52 -> {
                if (!(draft.getItemMeta() instanceof EnchantmentStorageMeta)) return;
                textPrompt.open(player, "Stored enchantment", "minecraft:unbreaking 3",
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setStoredEnchantment(item, value)),
                        () -> openItemEditor(player));
            }
            case 53 -> {
                if (!(draft.getItemMeta() instanceof Damageable damageable)
                        || draft.getType().getMaxDurability() <= 0) return;
                textPrompt.open(player, "Max durability", damageable.hasMaxDamage()
                                ? Integer.toString(damageable.getMaxDamage()) : "none",
                        value -> applyItemEdit(player, draft,
                                item -> ItemDraftEditor.setMaxDamage(item, value)),
                        () -> openItemEditor(player));
            }
            default -> { }
        }
    }

    private void clickAdvancedItem(Player player, int slot) {
        if (pendingItemWrites.contains(player.getUniqueId())) return;
        ItemStack draft = itemDrafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        ItemMeta meta = draft.getItemMeta();
        if (slot == 22) { openItemEditor(player); return; }
        switch (slot) {
            case 10 -> {
                if (!(meta instanceof AxolotlBucketMeta bucket)) return;
                textPrompt.open(player, "Axolotl variant", bucket.hasVariant()
                                ? bucket.getVariant().name() : "BLUE",
                        value -> applyAdvancedItemEdit(player, draft,
                                item -> ItemDraftEditor.setAxolotlVariant(item, value)),
                        () -> openAdvancedItemEditor(player));
            }
            case 11 -> {
                if (!(meta instanceof KnowledgeBookMeta book)) return;
                textPrompt.open(player, "Knowledge recipe key", book.getRecipes().isEmpty()
                                ? "minecraft:stone_pickaxe" : book.getRecipes().get(0).toString(),
                        value -> applyAdvancedItemEdit(player, draft,
                                item -> ItemDraftEditor.editKnowledgeRecipes(item, value)),
                        () -> openAdvancedItemEditor(player));
            }
            case 12 -> {
                if (!(meta instanceof MusicInstrumentMeta horn)) return;
                String initial = horn.getInstrument() == null ? "DREAM"
                        : horn.getInstrument().getKey().getKey().replace("_goat_horn", "");
                textPrompt.open(player, "Goat horn instrument", initial,
                        value -> applyAdvancedItemEdit(player, draft,
                                item -> ItemDraftEditor.setGoatHornInstrument(item, value)),
                        () -> openAdvancedItemEditor(player));
            }
            case 13 -> {
                if (!(meta instanceof OminousBottleMeta bottle)) return;
                textPrompt.open(player, "Ominous bottle level",
                        Integer.toString(bottle.getAmplifier()),
                        value -> applyAdvancedItemEdit(player, draft,
                                item -> ItemDraftEditor.setOminousBottleLevel(item, value)),
                        () -> openAdvancedItemEditor(player));
            }
            case 14 -> {
                if (meta instanceof BundleMeta bundle) {
                    String initial = bundle.getItems().isEmpty() ? "DIAMOND 2"
                            : bundle.getItems().get(0).getType() + " "
                                    + bundle.getItems().get(0).getAmount();
                    textPrompt.open(player, "Bundle contents", initial,
                            value -> applyAdvancedItemEdit(player, draft,
                                    item -> ItemDraftEditor.setBundleContents(item, value)),
                            () -> openAdvancedItemEditor(player));
                } else if (ItemDraftEditor.shulkerFilledSlots(draft) >= 0) {
                    textPrompt.open(player, "Shulker slot contents", "1 DIAMOND 2",
                            value -> applyAdvancedItemEdit(player, draft,
                                    item -> ItemDraftEditor.setShulkerContents(item, value)),
                            () -> openAdvancedItemEditor(player));
                }
            }
            case 15 -> {
                if (!(meta instanceof Repairable repair)) return;
                textPrompt.open(player, "Anvil repair cost",
                        Integer.toString(repair.getRepairCost()),
                        value -> applyAdvancedItemEdit(player, draft,
                                item -> ItemDraftEditor.setRepairCost(item, value)),
                        () -> openAdvancedItemEditor(player));
            }
            case 16 -> {
                if (draft.getType() != Material.WRITTEN_BOOK || !(meta instanceof BookMeta book)) return;
                textPrompt.open(player, "Written book generation",
                        book.getGeneration() == null ? "ORIGINAL" : book.getGeneration().name(),
                        value -> applyAdvancedItemEdit(player, draft,
                                item -> ItemDraftEditor.setBookGeneration(item, value)),
                        () -> openAdvancedItemEditor(player));
            }
            case 17 -> {
                if (!(meta instanceof BlockDataMeta block)) return;
                String initial = "facing=east,half=top";
                if (block.hasBlockData()) {
                    String encoded = block.getBlockData(draft.getType()).getAsString();
                    int opening = encoded.indexOf('[');
                    if (opening >= 0 && encoded.endsWith("]")) {
                        initial = encoded.substring(opening + 1, encoded.length() - 1);
                    }
                }
                textPrompt.open(player, "Block-item properties", initial,
                        value -> applyAdvancedItemEdit(player, draft,
                                item -> ItemDraftEditor.setBlockItemState(item, value)),
                        () -> openAdvancedItemEditor(player));
            }
            case 18 -> textPrompt.open(player, "Non-italic item name",
                    meta.hasItemName() ? meta.getItemName().replace('§', '&') : "New item name",
                    value -> applyAdvancedItemEdit(player, draft,
                            item -> ItemDraftEditor.setItemName(item, value)),
                    () -> openAdvancedItemEditor(player));
            case 19 -> textPrompt.open(player, "Item model", "minecraft:stone",
                    value -> applyAdvancedItemEdit(player, draft,
                            item -> ItemDraftEditor.setItemModel(item, value)),
                    () -> openAdvancedItemEditor(player));
            case 20 -> textPrompt.open(player, "Tooltip style", "minecraft:default",
                    value -> applyAdvancedItemEdit(player, draft,
                            item -> ItemDraftEditor.setTooltipStyle(item, value)),
                    () -> openAdvancedItemEditor(player));
            case 21 -> textPrompt.open(player, "Enchantability", "10",
                    value -> applyAdvancedItemEdit(player, draft,
                            item -> ItemDraftEditor.setEnchantable(item, value)),
                    () -> openAdvancedItemEditor(player));
            case 23 -> textPrompt.open(player, "Attribute modifier",
                    "minecraft:attack_damage mainhand 5 ADD_NUMBER",
                    value -> applyAdvancedItemEdit(player, draft,
                            item -> ItemDraftEditor.setAttributeModifier(item, value)),
                    () -> openAdvancedItemEditor(player));
            case 24 -> bookPrompt.open(player, "@floats 1.5, 2.5",
                    value -> applyAdvancedItemEdit(player, draft,
                            item -> ItemDraftEditor.setRichCustomModelData(item, value)),
                    () -> openAdvancedItemEditor(player));
            case 25 -> bookPrompt.open(player, "minecraft:bucket",
                    value -> applyAdvancedItemEdit(player, draft,
                            item -> ItemDraftEditor.setUseRemainder(item, value)),
                    () -> openAdvancedItemEditor(player));
            case 26 -> textPrompt.open(player, "Use cooldown", "3.5 recipeweaver:shared",
                    value -> applyAdvancedItemEdit(player, draft,
                            item -> ItemDraftEditor.setUseCooldown(item, value)),
                    () -> openAdvancedItemEditor(player));
            case 27 -> textPrompt.open(player, "Equippable slot and model",
                    "HEAD recipeweaver:hat",
                    value -> applyAdvancedItemEdit(player, draft,
                            item -> ItemDraftEditor.setEquippable(item, value)),
                    () -> openAdvancedItemEditor(player));
            case 28 -> {
                if (!modernSetterSupported("Glider", boolean.class)) return;
                textPrompt.open(player, "Glider", "true",
                    value -> applyAdvancedItemEdit(player, draft,
                            item -> ItemDraftEditor.setGlider(item, value)),
                    () -> openAdvancedItemEditor(player));
            }
            case 29 -> {
                if (!modernSetterSupported("AdditionalTradeCost", Integer.class)) return;
                textPrompt.open(player, "Additional trade cost", "7",
                    value -> applyAdvancedItemEdit(player, draft,
                            item -> ItemDraftEditor.setAdditionalTradeCost(item, value)),
                    () -> openAdvancedItemEditor(player));
            }
            case 30 -> {
                if (!modernSetterSupported("MinimumAttackCharge", Float.class)) return;
                textPrompt.open(player, "Minimum attack charge", "0.75",
                    value -> applyAdvancedItemEdit(player, draft,
                            item -> ItemDraftEditor.setMinimumAttackCharge(item, value)),
                    () -> openAdvancedItemEditor(player));
            }
            case 31 -> {
                if (!modernSetterSupported("BreakSound", org.bukkit.Sound.class)) return;
                textPrompt.open(player, "Break sound", "minecraft:block.glass.break",
                    value -> applyAdvancedItemEdit(player, draft,
                            item -> ItemDraftEditor.setBreakSound(item, value)),
                    () -> openAdvancedItemEditor(player));
            }
            case 32 -> {
                if (!modernSetterSupported("JukeboxPlayable", componentType("JukeboxPlayable"))) return;
                textPrompt.open(player, "Jukebox song", "minecraft:cat",
                        value -> applyAdvancedItemEdit(player, draft,
                                item -> ItemDraftEditor.setJukeboxPlayable(item, value)),
                        () -> openAdvancedItemEditor(player));
            }
            case 33 -> {
                if (!modernSetterSupported("DamageResistant", org.bukkit.Tag.class)) return;
                textPrompt.open(player, "Damage resistance tag", "minecraft:is_fire",
                        value -> applyAdvancedItemEdit(player, draft,
                                item -> ItemDraftEditor.setDamageResistance(item, value)),
                        () -> openAdvancedItemEditor(player));
            }
            case 34 -> {
                if (draft.getType() != Material.PLAYER_HEAD
                        || !(meta instanceof SkullMeta skull)) return;
                var profile = skull.getOwnerProfile();
                String initial = profile == null || profile.getName() == null
                        ? "PlayerName" : profile.getName();
                bookPrompt.open(player, initial,
                        value -> applyAdvancedItemEdit(player, draft,
                                item -> ItemDraftEditor.setHeadProfile(item, value)),
                        () -> openAdvancedItemEditor(player));
            }
            case 35 -> {
                if (draft.getType() != Material.PLAYER_HEAD
                        || !(meta instanceof SkullMeta skull)) return;
                textPrompt.open(player, "Head note-block sound",
                        skull.getNoteBlockSound() == null
                                ? "minecraft:block.note_block.harp"
                                : skull.getNoteBlockSound().toString(),
                        value -> applyAdvancedItemEdit(player, draft,
                                item -> ItemDraftEditor.setHeadNoteBlockSound(item, value)),
                        () -> openAdvancedItemEditor(player));
            }
            default -> { }
        }
    }

    private void applyAdvancedItemEdit(Player player, ItemStack draft,
                                       java.util.function.Consumer<ItemStack> edit) {
        if (!player.hasPermission("recipeweaver.studio")
                || pendingItemWrites.contains(player.getUniqueId())) return;
        try { edit.accept(draft); }
        catch (IllegalArgumentException | IllegalStateException ex) {
            player.sendMessage(ChatColor.RED + ex.getMessage());
        }
        if (player.isOnline()) openAdvancedItemEditor(player);
    }

    private void clickItemEnchants(Player player, int slot) {
        ItemStack draft = itemDrafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        if (slot == 53) { openItemEditor(player); return; }
        if (slot == 49) {
            textPrompt.open(player, "Enchantment key and level", "minecraft:sharpness 1",
                    value -> applyItemEdit(player, draft,
                            item -> ItemDraftEditor.setEnchantment(item, value)),
                    () -> openItemEnchants(player));
            return;
        }
        List<org.bukkit.enchantments.Enchantment> enchants = itemEnchantments(draft);
        if (slot >= 0 && slot < enchants.size() && slot < 45) {
            String key = enchants.get(slot).getKey().toString();
            applyItemEdit(player, draft, item -> ItemDraftEditor.setEnchantment(item, key + " 0"));
        }
    }

    private void clickItemFlags(Player player, int slot) {
        ItemStack draft = itemDrafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        if (slot == 53) { openItemEditor(player); return; }
        ItemFlag[] flags = ItemFlag.values();
        if (slot >= 0 && slot < flags.length && slot < 45) {
            if (pendingItemWrites.contains(player.getUniqueId())) return;
            ItemDraftEditor.toggleFlag(draft, flags[slot]);
            openItemFlags(player);
        }
    }

    private void clickItemData(Player player, int page, int slot, ClickType click) {
        ItemStack draft = itemDrafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        if (slot == 45) { openItemData(player, page - 1); return; }
        if (slot == 52) { openItemEditor(player); return; }
        if (slot == 53) { openItemData(player, page + 1); return; }
        String initial;
        if (slot == 49) initial = "recipeweaver:custom\n@string value";
        else if (slot >= 0 && slot < 45) {
            List<ItemDraftEditor.PersistentEntry> entries = ItemDraftEditor.persistentEntries(draft);
            int index = page * 45 + slot;
            if (index >= entries.size()) return;
            ItemDraftEditor.PersistentEntry entry = entries.get(index);
            if ((entry.type().equals("compound") || entry.type().equals("compoundarray"))
                    && click.isRightClick()) {
                openItemCompoundData(player, entry.key(), 0);
                return;
            }
            initial = entry.key() + "\n" + (entry.type().equals("compound")
                    ? "@compound recipeweaver:child @string value"
                    : entry.editorValue());
        } else return;
        bookPrompt.open(player, initial, value -> {
            if (!player.hasPermission("recipeweaver.studio")
                    || pendingItemWrites.contains(player.getUniqueId())) return;
            try { ItemDraftEditor.setPersistentData(draft, value); }
            catch (IllegalArgumentException | IllegalStateException ex) {
                player.sendMessage(ChatColor.RED + ex.getMessage());
            }
            if (player.isOnline()) openItemData(player, page);
        }, () -> openItemData(player, page));
    }

    private void clickItemCompoundData(Player player, NamespacedKey outerKey, int page,
                                       int slot) {
        ItemStack draft = itemDrafts.get(player.getUniqueId());
        if (draft == null || outerKey == null) { openHome(player); return; }
        boolean array = draft.getItemMeta().getPersistentDataContainer().has(outerKey,
                org.bukkit.persistence.PersistentDataType.TAG_CONTAINER_ARRAY);
        if (slot == 45) { openItemCompoundData(player, outerKey, page - 1); return; }
        if (slot == 52) { openItemData(player, 0); return; }
        if (slot == 53) { openItemCompoundData(player, outerKey, page + 1); return; }
        String initial;
        if (slot == 49) {
            initial = outerKey + (array
                    ? "\n@compoundarray append recipeweaver:child @string value"
                    : "\n@compound recipeweaver:child @string value");
        } else if (slot >= 0 && slot < 45) {
            List<ItemDraftEditor.PersistentEntry> entries = array
                    ? ItemDraftEditor.persistentCompoundArrayEntries(draft, outerKey)
                    : ItemDraftEditor.persistentCompoundEntries(draft, outerKey);
            int index = page * 45 + slot;
            if (index >= entries.size()) return;
            ItemDraftEditor.PersistentEntry entry = entries.get(index);
            String value = entry.editorValue().isBlank()
                    ? (array ? "@compoundarray append recipeweaver:child @string value"
                            : "@compound " + entry.key() + " none")
                    : entry.editorValue();
            initial = outerKey + "\n" + value;
        } else return;
        bookPrompt.open(player, initial, value -> {
            if (!player.hasPermission("recipeweaver.studio")
                    || pendingItemWrites.contains(player.getUniqueId())) return;
            try { ItemDraftEditor.setPersistentData(draft, value); }
            catch (IllegalArgumentException | IllegalStateException ex) {
                player.sendMessage(ChatColor.RED + ex.getMessage());
            }
            if (player.isOnline()) {
                if (draft.getItemMeta().getPersistentDataContainer().has(outerKey)) {
                    openItemCompoundData(player, outerKey, page);
                } else openItemData(player, 0);
            }
        }, () -> openItemCompoundData(player, outerKey, page));
    }

    private void applyItemEdit(Player player, ItemStack draft,
                               java.util.function.Consumer<ItemStack> edit) {
        if (!player.hasPermission("recipeweaver.studio")
                || pendingItemWrites.contains(player.getUniqueId())) return;
        try { edit.accept(draft); }
        catch (IllegalArgumentException | IllegalStateException ex) {
            player.sendMessage(ChatColor.RED + ex.getMessage());
        }
        if (player.isOnline()) openItemEditor(player);
    }

    private void saveItem(Player player, ItemStack draft, String rawId) {
        if (!player.hasPermission("recipeweaver.studio")) return;
        NamespacedKey key = NamespacedKey.fromString(rawId.toLowerCase(java.util.Locale.ROOT), plugin);
        if (key == null || !key.getNamespace().equals("recipeweaver")) {
            player.sendMessage(ChatColor.RED + "Invalid custom item ID.");
            openItemEditor(player);
            return;
        }
        NamespacedKey existing = itemDraftIds.get(player.getUniqueId());
        if (existing != null && !existing.equals(key)) {
            player.sendMessage(ChatColor.RED + "Rename is not supported; keep the original item ID.");
            openItemEditor(player);
            return;
        }
        if (existing == null && plugin.items().containsPending(key)) {
            player.sendMessage(ChatColor.RED + "That item ID already exists. Edit it from Saved custom items.");
            openItemEditor(player);
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!pendingItemWrites.add(playerId)) {
            player.sendMessage(ChatColor.YELLOW + "Custom item save is still in progress.");
            return;
        }
        try {
            plugin.saveCustomItem(key, draft).whenComplete((ignored, failure) -> {
                pendingItemWrites.remove(playerId);
                if (!player.isOnline()) return;
                if (failure != null) {
                    player.sendMessage(ChatColor.RED + "Item save failed: " + failure.getMessage());
                    openItemEditor(player);
                } else {
                    itemDrafts.remove(playerId);
                    itemDraftIds.remove(playerId);
                    player.sendMessage(ChatColor.GREEN + "Saved item " + key + ". Use /rw give <player> " + key);
                    openItems(player, 0);
                }
            });
        } catch (RuntimeException ex) {
            pendingItemWrites.remove(playerId);
            player.sendMessage(ChatColor.RED + "Item save failed: " + ex.getMessage());
            openItemEditor(player);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder)) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        recipeDrafts.remove(id);
        itemDrafts.remove(id);
        itemDraftIds.remove(id);
        testMatrices.remove(id);
        bookQueries.remove(id);
        bookGroups.remove(id);
        bookCategories.remove(id);
        bookSorts.remove(id);
        bookDiscoveries.remove(id);
        clearSqlAudit(id);
    }

    private List<BookEntry> visibleRecipes(Player player) {
        List<BookEntry> entries = bookEntriesWithoutGroup(player);
        UUID id = player.getUniqueId();
        String selected = BookGroupFilter.selected(bookGroups.get(id), BookGroupFilter.available(
                entries.stream().map(BookEntry::group).toList()));
        if (selected == null) bookGroups.remove(id);
        else bookGroups.put(id, selected);
        entries.removeIf(entry -> !BookGroupFilter.accepts(selected, entry.group()));
        BookSort sort = bookSorts.getOrDefault(id, BookSort.KEY);
        entries.sort(sort.comparator());
        return entries;
    }

    private List<BookEntry> bookEntriesWithoutGroup(Player player) {
        List<BookEntry> entries = bookEntries(player);
        UUID id = player.getUniqueId();
        BookCategory category = bookCategories.getOrDefault(id, BookCategory.ALL);
        entries.removeIf(entry -> !category.accepts(entry));
        BookDiscovery discovery = bookDiscoveries.getOrDefault(id, BookDiscovery.ALL);
        entries.removeIf(entry -> !discovery.accepts(plugin.isDiscovered(player, entry.key())));
        String query = bookQueries.getOrDefault(id, "");
        if (!query.isBlank()) entries.removeIf(entry -> !entry.searchText().contains(query));
        return entries;
    }

    private List<BookEntry> bookEntries(Player player) {
        List<BookEntry> entries = new ArrayList<>();
        boolean editor = player.hasPermission("recipeweaver.studio");
        boolean available = !plugin.lockedDown();
        for (RecipeDefinition recipe : plugin.recipes()) {
            if (editor || (available && recipe.enabled() && !recipe.hidden())) {
                entries.add(new BookEntry(recipe.key(), recipe, null, null));
            }
        }
        for (StationRecipeDefinition recipe : plugin.stationRecipes()) {
            if (editor || (available && recipe.enabled() && !recipe.hidden())) {
                entries.add(new BookEntry(recipe.key(), null, recipe, null));
            }
        }
        for (GridRecipeDefinition recipe : plugin.gridRecipes()) {
            if (editor || (available && recipe.enabled() && !recipe.hidden())) {
                entries.add(new BookEntry(recipe.key(), null, null, recipe));
            }
        }
        return entries;
    }

    private record BookEntry(NamespacedKey key, RecipeDefinition workbench,
                             StationRecipeDefinition station, GridRecipeDefinition grid) {
        ItemStack icon() {
            if (workbench != null) return workbench.result();
            if (grid != null) return grid.result();
            ItemStack result = station.result();
            return result != null ? result : station.ingredients().get(1);
        }
        String kind() {
            if (workbench != null) return workbench.kind().name();
            if (grid != null) return grid.tier() + " " + grid.size() + "x" + grid.size()
                    + " " + grid.kind();
            return station.kind().name();
        }
        boolean enabled() {
            return workbench != null ? workbench.enabled()
                    : grid != null ? grid.enabled() : station.enabled();
        }
        boolean hidden() {
            return workbench != null ? workbench.hidden()
                    : grid != null ? grid.hidden() : station.hidden();
        }
        String group() {
            return workbench != null ? workbench.group()
                    : grid != null ? grid.group() : station.group();
        }
        String searchText() {
            List<ItemStack> results;
            if (workbench != null) results = List.of(workbench.result());
            else if (grid != null) results = grid.resultChoices().stream()
                    .map(dev.recipeweaver.api.WeightedResult::item).toList();
            else if (!station.resultChoices().isEmpty()) {
                results = station.resultChoices().stream()
                        .map(dev.recipeweaver.api.WeightedResult::item).toList();
            } else results = List.of(icon());
            return BookSearchText.of(key, kind(), group(), results);
        }

        String resultSortKey() { return BookResultSortKey.of(icon()); }
    }
    private Inventory create(MenuHolder holder, int size, String title) {
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.inventory = inventory;
        return inventory;
    }

    private static ItemStack copy(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }

    private static ItemStack copyOne(ItemStack item) {
        ItemStack result = copy(item);
        if (result != null) result.setAmount(1);
        return result;
    }

    private static ItemStack icon(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + name);
        List<String> lore = Arrays.stream(loreLines).map(line -> ChatColor.GRAY + line).toList();
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private enum BookCategory {
        ALL, WORKBENCH, STATION, ADVANCED, ELITE;

        BookCategory next() { return values()[(ordinal() + 1) % values().length]; }

        boolean accepts(BookEntry entry) {
            return switch (this) {
                case ALL -> true;
                case WORKBENCH -> entry.workbench() != null;
                case STATION -> entry.station() != null;
                case ADVANCED -> entry.grid() != null
                        && entry.grid().tier() == dev.recipeweaver.api.GridTier.ADVANCED;
                case ELITE -> entry.grid() != null
                        && entry.grid().tier() == dev.recipeweaver.api.GridTier.ELITE;
            };
        }
    }

    private enum BookDiscovery {
        ALL, DISCOVERED, UNDISCOVERED;

        BookDiscovery next() { return values()[(ordinal() + 1) % values().length]; }

        boolean accepts(boolean known) {
            return this == ALL || (this == DISCOVERED) == known;
        }
    }

    private enum BookSort {
        KEY, RESULT, GROUP;

        BookSort next() { return values()[(ordinal() + 1) % values().length]; }

        Comparator<BookEntry> comparator() {
            Comparator<BookEntry> byKey = Comparator.comparing(entry -> entry.key().toString());
            return switch (this) {
                case KEY -> byKey;
                case RESULT -> Comparator.comparing(BookEntry::resultSortKey,
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(byKey);
                case GROUP -> Comparator.comparing((BookEntry entry) ->
                                BookGroupFilter.sortKey(entry.group()), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(byKey);
            };
        }
    }

    private enum Menu { HOME, SETTINGS, LOCAL_SQL, REMOTE_SQL, SQL_ORDER, SQL_LOCAL_CONFIRM,
        WORKBENCH_CONDITIONS,
        VANILLA, BACKUPS, RESTORE_CONFIRM, RECIPE, INGREDIENT, TEST, BOOK, DETAIL,
        ITEM, ITEM_ADVANCED, ITEM_ENCHANTS, ITEM_FLAGS, ITEM_DATA,
        ITEM_DATA_COMPOUND, ITEM_LIST }

    private static final class MenuHolder implements InventoryHolder {
        private final UUID owner;
        private final Menu menu;
        private final int page;
        private final NamespacedKey recipe;
        private final String backup;
        private final SqlMirrorMode sqlMode;
        private final String sqlSha256;
        private BookPageSnapshot bookPage;
        private ItemPageSnapshot itemPage;
        private SettingsPageSnapshot settingsPage;
        private List<String> backupNames;
        private List<NamespacedKey> vanillaKeys;
        private SqlOrderPageSnapshot sqlOrderPage;
        private RemoteSqlPageSnapshot remoteSqlPage;
        private Inventory inventory;

        private MenuHolder(UUID owner, Menu menu, int page, NamespacedKey recipe) {
            this(owner, menu, page, recipe, null);
        }

        private MenuHolder(UUID owner, Menu menu, int page, NamespacedKey recipe, String backup) {
            this.owner = owner;
            this.menu = menu;
            this.page = page;
            this.recipe = recipe;
            this.backup = backup;
            this.sqlMode = null;
            this.sqlSha256 = null;
        }

        private MenuHolder(UUID owner, Menu menu, SqlMirrorMode sqlMode, String sqlSha256) {
            this.owner = owner;
            this.menu = menu;
            this.page = 0;
            this.recipe = null;
            this.backup = null;
            this.sqlMode = sqlMode;
            this.sqlSha256 = sqlSha256;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class RecipeDraft {
        private RecipeKind kind;
        private NamespacedKey existingKey;
        private final ItemStack[] ingredients = new ItemStack[9];
        private final IngredientMatchMode[] matchModes = new IngredientMatchMode[9];
        private final List<List<ItemStack>> alternatives = new ArrayList<>(9);
        private final NamespacedKey[] itemTags = new NamespacedKey[9];
        private ItemStack result;
        private String group = "";
        private CraftingBookCategory bookCategory = CraftingBookCategory.MISC;
        private boolean enabled = true;
        private boolean hidden;
        private String permission;
        private String world;
        private NamespacedKey biome;
        private int timeStart = -1;
        private int timeEnd = -1;
        private WeatherCondition weather = WeatherCondition.ANY;
        private int minimumLevel;
        private String scoreboardObjective;
        private int minimumScore;
        private NamespacedKey advancement;

        private RecipeDraft(RecipeKind kind) {
            this.kind = kind;
            Arrays.fill(matchModes, IngredientMatchMode.EXACT);
            for (int i = 0; i < 9; i++) alternatives.add(new ArrayList<>());
        }

        private RecipeDraft(RecipeDefinition recipe) {
            this(recipe.kind());
            existingKey = recipe.key();
            List<ItemStack> saved = recipe.ingredients();
            List<List<ItemStack>> savedAlternatives = recipe.alternatives();
            for (int i = 0; i < 9; i++) {
                ingredients[i] = saved.get(i);
                matchModes[i] = recipe.matchModes().get(i);
                alternatives.get(i).addAll(savedAlternatives.get(i));
                itemTags[i] = recipe.itemTags().get(i);
            }
            result = recipe.result();
            group = recipe.group();
            bookCategory = recipe.bookCategory();
            enabled = recipe.enabled();
            hidden = recipe.hidden();
            applyConditions(recipe.conditions());
        }

        private void applyConditions(RecipeConditions conditions) {
            permission = conditions.permission();
            world = conditions.world();
            biome = conditions.biome();
            timeStart = conditions.timeStart();
            timeEnd = conditions.timeEnd();
            weather = conditions.weather();
            minimumLevel = conditions.minimumLevel();
            scoreboardObjective = conditions.scoreboardObjective();
            minimumScore = conditions.minimumScore();
            advancement = conditions.advancement();
        }

        private RecipeConditions conditions() {
            return new RecipeConditions(permission, world, biome, timeStart, timeEnd, weather,
                    minimumLevel, scoreboardObjective, minimumScore, advancement);
        }

        private void clearConditions() {
            permission = null;
            world = null;
            biome = null;
            timeStart = -1;
            timeEnd = -1;
            weather = WeatherCondition.ANY;
            minimumLevel = 0;
            scoreboardObjective = null;
            minimumScore = 0;
            advancement = null;
        }
    }
}
