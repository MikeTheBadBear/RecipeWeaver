package dev.recipeweaver.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Service published through Bukkit's ServicesManager. Calls that change recipes belong on the server thread. */
public interface RecipeWeaverApi {
    Collection<RecipeDefinition> recipes();
    Optional<RecipeDefinition> recipe(NamespacedKey key);
    /** Queues an ordered disk write; use saveRecipeAsync to observe durable completion. */
    void saveRecipe(RecipeDefinition recipe);
    /** Main-thread call; the returned stage completes after the recipe is durably written. */
    CompletionStage<Void> saveRecipeAsync(RecipeDefinition recipe);
    /** Queues an ordered deletion; use removeRecipeAsync to observe durable completion. */
    boolean removeRecipe(NamespacedKey key);
    /** Main-thread call; returns false when the recipe does not exist. */
    CompletionStage<Boolean> removeRecipeAsync(NamespacedKey key);
    Collection<StationRecipeDefinition> stationRecipes();
    Optional<StationRecipeDefinition> stationRecipe(NamespacedKey key);
    /** Queues an ordered disk write; use the async method to observe durable completion. */
    void saveStationRecipe(StationRecipeDefinition recipe);
    CompletionStage<Void> saveStationRecipeAsync(StationRecipeDefinition recipe);
    /** Queues an ordered deletion; use the async method to observe durable completion. */
    boolean removeStationRecipe(NamespacedKey key);
    CompletionStage<Boolean> removeStationRecipeAsync(NamespacedKey key);
    Collection<GridRecipeDefinition> gridRecipes();
    Optional<GridRecipeDefinition> gridRecipe(NamespacedKey key);
    /** Queues an ordered disk write; use the async method to observe durable completion. */
    void saveGridRecipe(GridRecipeDefinition recipe);
    CompletionStage<Void> saveGridRecipeAsync(GridRecipeDefinition recipe);
    /** Queues an ordered deletion; use the async method to observe durable completion. */
    boolean removeGridRecipe(NamespacedKey key);
    CompletionStage<Boolean> removeGridRecipeAsync(NamespacedKey key);
    /** Main-thread query; includes the server's native recipe knowledge. */
    boolean hasDiscoveredRecipe(Player player, NamespacedKey key);
    /** Main-thread call; completes after the discovery is durably written off-thread. */
    CompletionStage<Boolean> discoverRecipe(Player player, NamespacedKey key);
    /** Main-thread query of the committed custom-item catalog. */
    Optional<ItemStack> customItem(NamespacedKey key);
    /** Main-thread lookup across the saved catalog and enabled external item providers. Returns a clone. */
    Optional<ItemStack> resolveItem(NamespacedKey key);
    /** Main-thread registration for one namespace. Another plugin's registration cannot be replaced. */
    boolean registerItemProvider(Plugin owner, String namespace, ItemProvider provider);
    /** Main-thread removal; only the plugin that registered the namespace may remove it. */
    boolean unregisterItemProvider(Plugin owner, String namespace);
    /** Main-thread rule for one custom grid recipe; false or a callback failure denies that recipe. */
    void registerGridCondition(Plugin owner, NamespacedKey recipe, GridCraftCondition condition);
    /** Main-thread removal of the owner's rule for one custom grid recipe. */
    boolean unregisterGridCondition(Plugin owner, NamespacedKey recipe);
    /** Main-thread post-commit action for one custom grid recipe. */
    void registerGridAction(Plugin owner, NamespacedKey recipe, boolean perBatch, GridCraftAction action);
    /** Main-thread removal of the owner's post-commit action for one custom grid recipe. */
    boolean unregisterGridAction(Plugin owner, NamespacedKey recipe);
    /** Main-thread rule for one owned station recipe; cooking and brewing pass null for player. */
    void registerStationCondition(Plugin owner, NamespacedKey recipe, StationCraftCondition condition);
    /** Main-thread removal of the owner's rule for one owned station recipe. */
    boolean unregisterStationCondition(Plugin owner, NamespacedKey recipe);
    /** Main-thread post-commit action for one owned station result; brewing passes null for player, once per bottle. */
    void registerStationAction(Plugin owner, NamespacedKey recipe, StationCraftAction action);
    /** Main-thread removal of the owner's post-commit action for one owned station recipe. */
    boolean unregisterStationAction(Plugin owner, NamespacedKey recipe);
    /** Main-thread call; completes after the item is durably saved off-thread. */
    CompletionStage<Void> saveCustomItem(NamespacedKey key, ItemStack item);
    /** Main-thread call; completes after the item is durably removed off-thread. */
    CompletionStage<Boolean> removeCustomItem(NamespacedKey key);
    /** Main-thread request; raw file reads run on the ordered I/O worker, then Bukkit data is published on the server thread. */
    CompletionStage<Void> reloadDataAsync();
    /** Main-thread request; ZIP creation and retention run on the ordered I/O worker. Completion may run on that worker. */
    CompletionStage<String> createBackupAsync();
    /** Main-thread request; backup listing runs on the ordered I/O worker. */
    CompletionStage<List<String>> listBackupsAsync();
    /** Main-thread request; ZIP extraction and file replacement run on the I/O worker with Bukkit publication on the server thread. */
    CompletionStage<String> restoreDataAsync(String backupName);
    /** Whether players may right-click the optional RecipeWeaver book item. */
    boolean bookItemEnabled();
    /** Main-thread change; completes after the book-item setting is durable. */
    CompletionStage<Void> setBookItemEnabledAsync(boolean enabled);
    /** Maximum number of retained backups; zero means unlimited. */
    int backupRetention();
    /** Main-thread change; completes after the retention setting is durable. */
    CompletionStage<Void> setBackupRetentionAsync(int count);
    /** Main-thread query of the format selected for future backups. */
    BackupFormat backupFormat();
    /** Main-thread change; completes after settings are durably written. */
    CompletionStage<Void> setBackupFormatAsync(BackupFormat format);
    /** Minecraft recipe IDs currently configured for suppression. */
    Set<NamespacedKey> disabledVanillaRecipes();
    /** Main-thread change; completes after the exclusion list is durably written. */
    CompletionStage<Void> setVanillaRecipeDisabledAsync(NamespacedKey key, boolean disabled);
    /** First selected SQL mirror, or NONE. Use sqlMirrorModes for the complete ordered selection. */
    SqlMirrorMode sqlMirrorMode();
    /** Ordered SQL write targets following the authoritative local files. */
    List<SqlMirrorMode> sqlMirrorModes();
    /** Compatibility shortcut that replaces the complete selection with one mirror or none. */
    CompletionStage<Void> setSqlMirrorModeAsync(SqlMirrorMode mode);
    /** Main-thread change; saves the ordered selection and attempts an initial sync for each target. */
    CompletionStage<Void> setSqlMirrorModesAsync(List<SqlMirrorMode> modes);
    /** Main-thread retry for selected mirrors that have not diverged from their last synchronized contents. */
    CompletionStage<Boolean> resyncSqlMirrorAsync();
    /** Main-thread retry for one mirror that has not diverged from its last synchronized contents. */
    CompletionStage<Boolean> resyncSqlMirrorAsync(SqlMirrorMode mode);
    /** Explicitly replace one selected SQL mirror from local files if its content still matches an audit SHA-256. */
    CompletionStage<Boolean> replaceSqlMirrorFromLocalAsync(SqlMirrorMode mode, String expectedSha256);
    /** First propagation error, if any. Use sqlMirrorFailures for every target. */
    String sqlMirrorFailure();
    /** Sanitized propagation status for each target that is behind local files. */
    Map<SqlMirrorMode, String> sqlMirrorFailures();
    /** Startup source priority; LOCAL_ONLY is the default for older installations. */
    SqlReadPolicy sqlReadPolicy();
    /** Main-thread change; saves whether startup may recover from or prefer ordered SQL sources. */
    CompletionStage<Void> setSqlReadPolicyAsync(SqlReadPolicy policy);
    /** SQL source loaded at this startup, or NONE when local files were loaded. */
    SqlMirrorMode startupSqlSource();

    /** Source used by the last successful runtime reload; NONE means local files. */
    SqlMirrorMode lastReloadSqlSource();
    /** Compare each selected SQL source with local files on the ordered I/O worker. */
    CompletionStage<List<SqlSourceStatus>> auditSqlSourcesAsync();
    /** Prepare the first readable selected SQL source as a backup for normal restore review. */
    CompletionStage<SqlPreparedSource> prepareOrderedSqlRestoreAsync();
    /** Prepare one selected SQL source only if its content still matches a prior audit SHA-256. */
    CompletionStage<SqlPreparedSource> prepareSqlSourceRestoreAsync(SqlMirrorMode mode, String expectedSha256);
    /** Selected remote connection settings. The password is deliberately not readable through this API. */
    SqlMirrorMode remoteSqlBackend();
    String remoteSqlJdbcUrl();
    String remoteSqlUsername();
    boolean remoteSqlPasswordConfigured();
    /** Main-thread change; a backend change needs the live remote mirror disabled first. */
    CompletionStage<Void> setRemoteSqlConnectionAsync(SqlMirrorMode backend, String jdbcUrl,
                                                      String username, String password);
    boolean lockedDown();
    /** Main-thread change; updates registrations now and completes after the setting is durable. */
    CompletionStage<Void> setLockdownAsync(boolean enabled);
}
