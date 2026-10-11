package dev.recipeweaver.core;

import dev.recipeweaver.adapters.BukkitRecipeAdapter;
import dev.recipeweaver.adapters.NativeStationAdapter;
import dev.recipeweaver.adapters.PlatformAdapter;
import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.GridCraftAction;
import dev.recipeweaver.api.GridCraftCondition;
import dev.recipeweaver.api.ItemProvider;
import dev.recipeweaver.api.BackupFormat;
import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.SqlMirrorMode;
import dev.recipeweaver.api.SqlReadPolicy;
import dev.recipeweaver.api.SqlPreparedSource;
import dev.recipeweaver.api.SqlSourceStatus;
import dev.recipeweaver.api.StationRecipeDefinition;
import dev.recipeweaver.api.StationCraftAction;
import dev.recipeweaver.api.StationCraftCondition;
import dev.recipeweaver.api.StationKind;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

public final class RecipeWeaverPlugin extends JavaPlugin implements RecipeWeaverApi {
    private RecipeRepository recipeRepository;
    private StationRecipeRepository stationRepository;
    private GridRecipeRepository gridRepository;
    private TableRepository tableRepository;
    private CauldronRepository cauldronRepository;
    private ItemRepository itemRepository;
    private ItemProviders itemProviders;
    private GridExtensions gridExtensions;
    private StationExtensions stationExtensions;
    private SettingsRepository settingsRepository;
    private DiscoveryRepository discoveryRepository;
    private VanillaRecipeController vanillaRecipes;
    private BackupService backupService;
    private SqlSnapshotService sqlSnapshots;
    private PlatformAdapter platform;
    private boolean guardedCrafterCrafts;
    private CrafterRecipeCache crafterRecipeCache;
    private NativeStationAdapter stations;
    private StudioMenus menus;
    private BookPrompt bookPrompt;
    private StationMenus stationMenus;
    private GridMenus gridMenus;
    private TableListener tableListener;
    private CauldronListener cauldronListener;
    private BrewingListener brewingListener;
    private BookItemListener bookItemListener;
    private AsyncIoService io;
    private final Queue<Runnable> mainCompletions = new ConcurrentLinkedQueue<>();
    private volatile boolean shuttingDown;
    private volatile boolean reloadBusy;
    private volatile boolean restoreFinalizing;
    private volatile boolean sourceSwitchBusy;
    private SqlMirrorMode startupSqlSource = SqlMirrorMode.NONE;
    private SqlMirrorMode lastReloadSqlSource = SqlMirrorMode.NONE;
    private CompletableFuture<Void> activeReload;
    private CompletableFuture<String> activeRestore;
    private final List<PendingDiscovery> deferredDiscoveries = new ArrayList<>();

    private record PendingDiscovery(UUID player, NamespacedKey key, CompletableFuture<Boolean> result) { }

    @Override
    public void onEnable() {
        io = new AsyncIoService(getLogger(), getDataFolder().toPath());
        recipeRepository = new RecipeRepository(this, getDataFolder().toPath(), this::dispatchMain);
        stationRepository = new StationRecipeRepository(this, getDataFolder().toPath(), this::dispatchMain);
        gridRepository = new GridRecipeRepository(this, getDataFolder().toPath(), this::dispatchMain);
        tableRepository = new TableRepository(getDataFolder().toPath(), this::storageSnapshotBusy,
                this::dispatchMain);
        cauldronRepository = new CauldronRepository(getDataFolder().toPath(), this::storageSnapshotBusy,
                this::dispatchMain);
        itemRepository = new ItemRepository(this, getDataFolder().toPath(), this::dispatchMain);
        itemProviders = new ItemProviders(message -> logAsync(Level.WARNING, message));
        gridExtensions = new GridExtensions(message -> logAsync(Level.WARNING, message));
        stationExtensions = new StationExtensions(message -> logAsync(Level.WARNING, message));
        settingsRepository = new SettingsRepository(this, this::dispatchMain);
        discoveryRepository = new DiscoveryRepository(this);
        vanillaRecipes = new VanillaRecipeController(this);
        backupService = new BackupService(this, null, () -> settingsRepository.backupRetention(),
                () -> settingsRepository.backupFormat(), this::dispatchMain);
        sqlSnapshots = new SqlSnapshotService(getDataFolder().toPath(), backupService);
        backupService.listBackupsAsync(io).whenComplete((names, failure) -> {
            if (failure != null) logAsync(Level.WARNING,
                    "Could not cache backup names: " + unwrapCompletion(failure).getMessage());
        });
        platform = new BukkitRecipeAdapter();
        stations = new NativeStationAdapter();
        guardedCrafterCrafts = new NativeCrafterGuard(this).register();
        if (!guardedCrafterCrafts) {
            try {
                crafterRecipeCache = CrafterRecipeCache.open(platform);
                platform = crafterRecipeCache;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
                logAsync(Level.WARNING, "No cancellable crafter event or safe cache reset: native RecipeWeaver workbench recipes are withheld: " + ex);
            }
            logAsync(Level.WARNING,
                    "No cancellable crafter craft event: exact or condition-dependent workbench recipes are unavailable natively on this server");
        }
        try {
            // Startup is a lifecycle boundary: read every file on the I/O worker before
            // decoding Bukkit item data and registering recipes on the server thread.
            DiskSnapshot snapshot;
            try { snapshot = io.submit(() -> DiskSnapshot.read(getDataFolder().toPath())).join(); }
            catch (CompletionException ex) { throw new IOException("Could not read saved data", ex.getCause()); }
            settingsRepository.load(snapshot);
            if (!canSafelySuppressNativeRecipes()
                    && !settingsRepository.disabledVanillaRecipes().isEmpty()) {
                throw new IOException("Saved vanilla recipe exclusions cannot be enforced without a crafter event or cache reset");
            }
            IOException localFailure = null;
            try {
                discoveryRepository.load(snapshot);
                itemRepository.load(snapshot);
                recipeRepository.load(snapshot);
                stationRepository.load(snapshot);
                gridRepository.load(snapshot);
                tableRepository.load(snapshot);
                cauldronRepository.load(snapshot);
                validateUniqueIds(recipeRepository, stationRepository, gridRepository);
            } catch (IOException failure) {
                localFailure = failure;
            }
            SqlReadPolicy readPolicy = settingsRepository.sqlReadPolicy();
            boolean loadedSql = false;
            if (readPolicy == SqlReadPolicy.PREFER_SQL_AT_STARTUP
                    || readPolicy == SqlReadPolicy.PREFER_SQL_AT_STARTUP_AND_RELOAD) {
                loadedSql = loadFromSqlAtStartup(snapshot, localFailure);
            } else if (localFailure != null) {
                if (readPolicy != SqlReadPolicy.RECOVER_ON_STARTUP) throw localFailure;
                loadedSql = loadFromSqlAtStartup(snapshot, localFailure);
            }
            if (!loadedSql) {
                if (localFailure != null) throw localFailure;
                io.submit(() -> {
                    io.configureMirrors(settingsRepository.sqlMirrors(),
                            settingsRepository.remoteSqlProfile());
                    return null;
                }).join();
                registerLoadedRecipes();
                vanillaRecipes.reconcile(settingsRepository.disabledVanillaRecipes());
                logAsync(Level.INFO, "Startup loaded local data; read policy " + readPolicy);
            }
        } catch (IOException | RuntimeException ex) {
            logAsync(Level.SEVERE, "Could not load saved data: " + ex.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        bookPrompt = new BookPrompt(this);
        Bukkit.getPluginManager().registerEvents(bookPrompt, this);
        menus = new StudioMenus(this);
        Bukkit.getPluginManager().registerEvents(menus, this);
        bookItemListener = new BookItemListener(this);
        Bukkit.getPluginManager().registerEvents(bookItemListener, this);
        Bukkit.getPluginManager().registerEvents(new DiscoveryListener(this), this);
        Bukkit.getPluginManager().registerEvents(new NativeCraftGuard(this), this);
        Bukkit.getPluginManager().registerEvents(new NativeSmithingGuard(this), this);
        Bukkit.getPluginManager().registerEvents(new NativeStonecutterGuard(this), this);
        Bukkit.getPluginManager().registerEvents(new NativeCookingGuard(this), this);
        stationMenus = new StationMenus(this);
        Bukkit.getPluginManager().registerEvents(stationMenus, this);
        gridMenus = new GridMenus(this);
        Bukkit.getPluginManager().registerEvents(gridMenus, this);
        tableListener = new TableListener(this);
        Bukkit.getPluginManager().registerEvents(tableListener, this);
        cauldronListener = new CauldronListener(this);
        Bukkit.getPluginManager().registerEvents(cauldronListener, this);
        brewingListener = new BrewingListener(this);
        Bukkit.getPluginManager().registerEvents(brewingListener, this);
        Bukkit.getPluginManager().registerEvents(new StationInventoryListener(this), this);
        Bukkit.getPluginManager().registerEvents(gridExtensions, this);
        Bukkit.getPluginManager().registerEvents(stationExtensions, this);
        RecipeWeaverCommand command = new RecipeWeaverCommand(this);
        PluginCommand root = getCommand("rw");
        PluginCommand recipes = getCommand("recipes");
        if (root == null || recipes == null) throw new IllegalStateException("plugin.yml commands are missing");
        root.setExecutor(command);
        root.setTabCompleter(command);
        recipes.setExecutor(command);
        Bukkit.getServicesManager().register(RecipeWeaverApi.class, this, this, ServicePriority.Normal);
        ExternalItemHooks itemHooks = new ExternalItemHooks(itemProviders,
                message -> logAsync(Level.WARNING, message));
        Bukkit.getPluginManager().registerEvents(itemHooks, this);
        itemHooks.installPresent();
        logAsync(Level.INFO, "Loaded " + recipeRepository.all().size() + " workbench and "
                + stationRepository.all().size() + " station and "
                + gridRepository.all().size() + " grid recipes on "
                + platform.platformName() + " " + platform.minecraftVersion());
    }

    private void dispatchMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        AtomicBoolean once = new AtomicBoolean();
        Runnable queued = new Runnable() {
            @Override public void run() {
                if (!once.compareAndSet(false, true)) return;
                mainCompletions.remove(this);
                action.run();
            }
        };
        mainCompletions.add(queued);
        try { Bukkit.getScheduler().runTask(this, queued); }
        catch (RuntimeException ex) {
            if (!shuttingDown) {
                mainCompletions.remove(queued);
                throw ex;
            }
        }
    }

    private void drainMainCompletions() {
        Runnable completion;
        while ((completion = mainCompletions.poll()) != null) {
            try { completion.run(); }
            catch (RuntimeException ex) {
                logAsync(Level.SEVERE, "Could not finish an I/O callback during shutdown: " + ex);
            }
        }
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        TextPrompt.closeAll();
        if (bookPrompt != null) bookPrompt.close();
        if (io != null) {
            int quietPasses = 0;
            for (int pass = 0; pass < 64; pass++) {
                try { io.flush().orTimeout(30, java.util.concurrent.TimeUnit.SECONDS).join(); }
                catch (CompletionException ex) {
                    logAsync(Level.SEVERE, "RecipeWeaver I/O failed during shutdown: "
                            + unwrapCompletion(ex));
                }
                drainMainCompletions();
                if (tableRepository != null) tableRepository.completePendingOnShutdown();
                if (cauldronRepository != null) cauldronRepository.completePendingOnShutdown();
                boolean busy = !mainCompletions.isEmpty() || reloadBusy || restoreFinalizing
                        || sourceSwitchBusy || (sqlSnapshots != null && sqlSnapshots.busy())
                        || (backupService != null && backupService.busy())
                        || (tableRepository != null && tableRepository.pendingWrite())
                        || (cauldronRepository != null && cauldronRepository.pendingWrite());
                if (busy) quietPasses = 0;
                else if (++quietPasses == 2) break;
            }
        }
        if (activeReload != null) {
            activeReload.completeExceptionally(new IllegalStateException("Plugin disabled during reload"));
            activeReload = null;
        }
        if (activeRestore != null) {
            activeRestore.completeExceptionally(new IllegalStateException("Plugin disabled during restore"));
            activeRestore = null;
        }
        for (PendingDiscovery pending : deferredDiscoveries) {
            pending.result().completeExceptionally(new IllegalStateException("Plugin disabled during reload"));
        }
        deferredDiscoveries.clear();
        reloadBusy = false;
        restoreFinalizing = false;
        if (recipeRepository != null && platform != null) {
            for (RecipeDefinition recipe : recipeRepository.all()) platform.unregister(recipe.key());
        }
        if (stationRepository != null && stations != null) {
            for (StationRecipeDefinition recipe : stationRepository.all()) stations.unregister(recipe.key());
        }
        if (vanillaRecipes != null) {
            try { vanillaRecipes.reconcile(Set.of()); }
            catch (RuntimeException ex) {
                logAsync(Level.SEVERE, "Could not restore vanilla recipes during shutdown: " + ex);
            }
        }
        if (itemProviders != null) itemProviders.clear();
        if (gridExtensions != null) gridExtensions.clear();
        if (stationExtensions != null) stationExtensions.clear();
        Bukkit.getServicesManager().unregisterAll(this);
        if (io != null) io.close();
    }

    @Override
    public CompletionStage<Void> reloadDataAsync() {
        requireServerThread();
        if (reloadBusy || restoreFinalizing || sourceSwitchBusy || sqlSnapshots.busy())
            return CompletableFuture.failedFuture(
                new IllegalStateException("Reload or restore already in progress"));
        if (backupService.busy()) return CompletableFuture.failedFuture(
                new IllegalStateException("Backup in progress; retry reload when it finishes"));
        if ((tableListener != null && tableListener.pendingBreakCleanup())
                || (tableRepository != null && tableRepository.pendingWrite())
                || (cauldronListener != null && cauldronListener.pendingBreakCleanup())
                || (cauldronRepository != null && cauldronRepository.pendingWrite())) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("A table or cauldron break is finishing; retry reload next tick"));
        }

        reloadBusy = true;
        CompletableFuture<Void> completed = new CompletableFuture<>();
        activeReload = completed;
        boolean preferSqlOnReload = settingsRepository.sqlReadPolicy()
                == SqlReadPolicy.PREFER_SQL_AT_STARTUP_AND_RELOAD;
        List<SqlMirrorMode> selectedSources = preferSqlOnReload
                ? new ArrayList<>(settingsRepository.sqlMirrors()) : List.of();
        RemoteSqlProfile selectedProfile = settingsRepository.remoteSqlProfile();
        io.flush().whenComplete((ignored, barrierFailure) -> {
            if (barrierFailure != null) {
                finishReload(completed, null, unwrapCompletion(barrierFailure), SqlMirrorMode.NONE);
                return;
            }
            io.submitObserved(() -> DiskSnapshot.read(getDataFolder().toPath()))
                    .whenComplete((snapshot, readFailure) -> {
                        if (preferSqlOnReload) {
                            reloadFromSelectedSql(completed, snapshot,
                                    readFailure == null ? null : unwrapCompletion(readFailure),
                                    selectedSources, selectedProfile);
                        } else if (readFailure != null) {
                            finishReload(completed, null, unwrapCompletion(readFailure), SqlMirrorMode.NONE);
                        } else {
                            finishReload(completed, snapshot, null, SqlMirrorMode.NONE);
                        }
                    });
        });
        return completed;
    }

    private void reloadFromSelectedSql(CompletableFuture<Void> completed, DiskSnapshot local,
                                       Throwable localFailure, List<SqlMirrorMode> remaining,
                                       RemoteSqlProfile profile) {
        if (remaining.isEmpty()) {
            finishSqlReloadFallback(completed, local, localFailure, null);
            return;
        }
        sqlSnapshots.prepareOrderedRestoreAsync(remaining, profile, io)
                .whenComplete((selected, prepareFailure) -> {
                    if (prepareFailure != null) {
                        finishSqlReloadFallback(completed, local, localFailure,
                                unwrapCompletion(prepareFailure));
                        return;
                    }
                    if (local == null) {
                        restoreSelectedSqlReload(completed, local, localFailure,
                                remaining, profile, selected);
                        return;
                    }
                    comparePreparedSqlReloadAsync(io, getDataFolder().toPath().resolve("backups")
                                    .resolve(selected.backupName()), local)
                            .whenComplete((same, compareFailure) -> {
                                if (compareFailure != null) {
                                    Throwable cause = unwrapCompletion(compareFailure);
                                    backupService.discardPreparedBackupAsync(selected.backupName(), io)
                                            .whenComplete((ignored, discardFailure) -> {
                                                if (discardFailure != null) {
                                                    cause.addSuppressed(unwrapCompletion(discardFailure));
                                                }
                                                finishReload(completed, null, cause, SqlMirrorMode.NONE);
                                            });
                                } else if (same) {
                                    try {
                                        dispatchMain(() -> {
                                            try {
                                                backupService.validateSnapshot(local);
                                                backupService.discardPreparedBackupAsync(selected.backupName(), io)
                                                        .whenComplete((ignored, discardFailure) -> finishReload(
                                                                completed, discardFailure == null ? local : null,
                                                                discardFailure == null ? null
                                                                        : unwrapCompletion(discardFailure),
                                                                SqlMirrorMode.NONE));
                                            } catch (IOException invalid) {
                                                skipInvalidReloadSource(completed, local, localFailure, remaining,
                                                        profile, selected);
                                            }
                                        });
                                    } catch (RuntimeException ex) {
                                        finishReload(completed, null, ex, SqlMirrorMode.NONE);
                                    }
                                } else {
                                    restoreSelectedSqlReload(completed, local, localFailure,
                                            remaining, profile, selected);
                                }
                            });
                });
    }

    /** Compare in queue order without treating a failed source read as a failed local write. */
    static CompletableFuture<Boolean> comparePreparedSqlReloadAsync(AsyncIoService io,
                                                                     Path preparedRoot,
                                                                     DiskSnapshot local) {
        return io.submitObservedAfterSuccessfulWrites(() ->
                DiskSnapshot.read(preparedRoot).documents().equals(local.documents()));
    }

    private void finishSqlReloadFallback(CompletableFuture<Void> completed, DiskSnapshot local,
                                         Throwable localFailure, Throwable sqlFailure) {
        if (localFailure == null) {
            logAsync(Level.WARNING, "No valid selected SQL reload source; using local files");
            finishReload(completed, local, null, SqlMirrorMode.NONE);
            return;
        }
        IOException failure = new IOException("Local data is unreadable and no selected SQL reload source is valid",
                localFailure);
        if (sqlFailure != null) failure.addSuppressed(sqlFailure);
        finishReload(completed, null, failure, SqlMirrorMode.NONE);
    }

    private void restoreSelectedSqlReload(CompletableFuture<Void> completed, DiskSnapshot local,
                                          Throwable localFailure, List<SqlMirrorMode> remaining,
                                          RemoteSqlProfile profile, SqlPreparedSource selected) {
        CompletableFuture<String> restored = localFailure == null
                ? backupService.restoreAsync(selected.backupName(), io, this::applySnapshot)
                : backupService.restoreUnreadableLocalAsync(selected.backupName(), io, this::applySnapshot);
        restored.whenComplete((previous, restoreFailure) -> {
            Throwable cause = restoreFailure == null ? null : unwrapCompletion(restoreFailure);
            if (cause instanceof BackupService.InvalidSourceException) {
                skipInvalidReloadSource(completed, local, localFailure, remaining, profile, selected);
            } else if (cause != null) {
                finishReload(completed, null, cause, SqlMirrorMode.NONE);
            } else {
                logAsync(Level.WARNING, "Reloaded from " + selected.backend()
                        + "; previous local files saved as " + previous);
                finishReload(completed, null, null, selected.backend());
            }
        });
    }

    private void skipInvalidReloadSource(CompletableFuture<Void> completed, DiskSnapshot local,
                                         Throwable localFailure, List<SqlMirrorMode> remaining,
                                         RemoteSqlProfile profile,
                                         SqlPreparedSource selected) {
        backupService.discardPreparedBackupAsync(selected.backupName(), io)
                .whenComplete((ignored, discardFailure) -> {
                    if (discardFailure != null) {
                        finishReload(completed, null, unwrapCompletion(discardFailure),
                                SqlMirrorMode.NONE);
                        return;
                    }
                    logAsync(Level.WARNING, "Skipped invalid " + selected.backend()
                            + " SQL reload source");
                    remaining.remove(selected.backend());
                    reloadFromSelectedSql(completed, local, localFailure, remaining, profile);
                });
    }

    private void finishReload(CompletableFuture<Void> completed, DiskSnapshot snapshot,
                              Throwable failure, SqlMirrorMode source) {
        Runnable finish = () -> {
            Throwable resultFailure = failure;
            try {
                if (resultFailure == null && snapshot != null) applySnapshot(snapshot);
                if (resultFailure == null) lastReloadSqlSource = source;
            } catch (IOException | RuntimeException ex) {
                resultFailure = ex;
            } finally {
                reloadBusy = false;
                activeReload = null;
                replayDeferredDiscoveries();
            }
            if (resultFailure == null) completed.complete(null);
            else completed.completeExceptionally(resultFailure);
        };
        if (Bukkit.isPrimaryThread()) finish.run();
        else {
            try { dispatchMain(finish); }
            catch (RuntimeException ex) {
                reloadBusy = false;
                activeReload = null;
                completed.completeExceptionally(ex);
            }
        }
    }

    boolean reloadInProgress() { return reloadBusy; }

    boolean storageSnapshotBusy() {
        return reloadBusy || restoreFinalizing || (backupService != null && backupService.busy())
                || (sqlSnapshots != null && sqlSnapshots.busy()) || sourceSwitchBusy;
    }

    private <T> T waitDuringStartup(CompletionStage<T> operation) throws IOException {
        CompletableFuture<T> future = operation.toCompletableFuture();
        while (!future.isDone()) {
            drainMainCompletions();
            if (future.isDone()) break;
            try { Thread.sleep(5L); }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during SQL startup recovery", ex);
            }
        }
        drainMainCompletions();
        try { return future.join(); }
        catch (CompletionException ex) {
            Throwable cause = unwrapCompletion(ex);
            if (cause instanceof IOException ioFailure) throw ioFailure;
            throw new IOException("SQL startup recovery failed", cause);
        }
    }

    private boolean loadFromSqlAtStartup(DiskSnapshot localSnapshot, IOException localFailure)
            throws IOException {
        boolean validLocal = localFailure == null;
        List<SqlMirrorMode> remaining = new ArrayList<>(settingsRepository.sqlMirrors());
        RemoteSqlProfile profile = settingsRepository.remoteSqlProfile();
        while (!remaining.isEmpty()) {
            SqlPreparedSource selected;
            try {
                selected = waitDuringStartup(sqlSnapshots.prepareOrderedRestoreAsync(remaining, profile, io));
            } catch (IOException noSource) {
                if (!validLocal) throw new IOException(
                        "No selected SQL source passed startup validation", localFailure);
                logAsync(Level.WARNING, "No readable selected SQL startup source; using valid local data");
                return false;
            }
            if (validLocal) {
                boolean unchanged = waitDuringStartup(io.submit(() -> DiskSnapshot.read(
                        getDataFolder().toPath().resolve("backups").resolve(selected.backupName()))
                        .documents().equals(localSnapshot.documents())));
                if (unchanged) {
                    waitDuringStartup(backupService.discardPreparedBackupAsync(selected.backupName(), io));
                    logAsync(Level.INFO, "Selected " + selected.backend()
                            + " SQL startup source matches local data");
                    return false;
                }
            }
            try {
                String previous = waitDuringStartup(backupService.restoreAsync(
                        selected.backupName(), io, this::applySnapshot));
                startupSqlSource = selected.backend();
                logAsync(Level.WARNING, (validLocal ? "Loaded preferred SQL startup source "
                        : "Recovered invalid local data from ") + selected.backend()
                        + "; previous local files saved as " + previous);
                return true;
            } catch (BackupService.InvalidSourceException invalid) {
                waitDuringStartup(backupService.discardPreparedBackupAsync(selected.backupName(), io));
                remaining.remove(selected.backend());
                logAsync(Level.WARNING, "Skipped invalid " + selected.backend()
                        + " SQL recovery source");
            }
        }
        if (!validLocal) throw new IOException("No selected SQL source passed startup validation", localFailure);
        logAsync(Level.WARNING, "No valid selected SQL startup source; using valid local data");
        return false;
    }

    private boolean mutationBlocked() {
        return reloadBusy || restoreFinalizing || (sqlSnapshots != null && sqlSnapshots.busy())
                || sourceSwitchBusy;
    }

    private void replayDeferredDiscoveries() {
        List<PendingDiscovery> queued = List.copyOf(deferredDiscoveries);
        deferredDiscoveries.clear();
        for (PendingDiscovery pending : queued) {
            try {
                discoveryRepository.discoverAsync(pending.player(), pending.key(), io)
                        .whenComplete((saved, failure) -> {
                            if (failure == null) pending.result().complete(saved);
                            else pending.result().completeExceptionally(failure);
                        });
            } catch (RuntimeException ex) {
                pending.result().completeExceptionally(ex);
            }
        }
    }

    private static Throwable unwrapCompletion(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    private void applySnapshot(DiskSnapshot snapshot) throws IOException {
        SettingsRepository nextSettings = new SettingsRepository(this, this::dispatchMain);
        DiscoveryRepository nextDiscoveries = new DiscoveryRepository(this);
        ItemRepository nextItems = new ItemRepository(this, getDataFolder().toPath(), this::dispatchMain);
        RecipeRepository nextRecipes = new RecipeRepository(this, getDataFolder().toPath(), this::dispatchMain);
        StationRecipeRepository nextStations = new StationRecipeRepository(
                this, getDataFolder().toPath(), this::dispatchMain);
        GridRecipeRepository nextGrids = new GridRecipeRepository(
                this, getDataFolder().toPath(), this::dispatchMain);
        TableRepository nextTables = new TableRepository(getDataFolder().toPath(), this::storageSnapshotBusy,
                this::dispatchMain);
        CauldronRepository nextCauldrons = new CauldronRepository(getDataFolder().toPath(), this::storageSnapshotBusy,
                this::dispatchMain);
        nextSettings.load(snapshot);
        nextDiscoveries.load(snapshot);
        nextItems.load(snapshot);
        nextRecipes.load(snapshot);
        nextStations.load(snapshot);
        nextGrids.load(snapshot);
        nextTables.load(snapshot);
        nextCauldrons.load(snapshot);
        validateUniqueIds(nextRecipes, nextStations, nextGrids);
        SettingsRepository oldSettings = settingsRepository;
        DiscoveryRepository oldDiscoveries = discoveryRepository;
        ItemRepository oldItems = itemRepository;
        RecipeRepository oldRecipes = recipeRepository;
        StationRecipeRepository oldStations = stationRepository;
        GridRecipeRepository oldGrids = gridRepository;
        TableRepository oldTables = tableRepository;
        CauldronRepository oldCauldrons = cauldronRepository;
        try {
            unregisterLoadedRecipes();
            settingsRepository = nextSettings;
            discoveryRepository = nextDiscoveries;
            itemRepository = nextItems;
            recipeRepository = nextRecipes;
            stationRepository = nextStations;
            gridRepository = nextGrids;
            tableRepository = nextTables;
            cauldronRepository = nextCauldrons;
            registerLoadedRecipes();
            vanillaRecipes.reconcile(settingsRepository.disabledVanillaRecipes());
        } catch (RuntimeException | Error failure) {
            try { unregisterLoadedRecipes(); }
            catch (RuntimeException | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            settingsRepository = oldSettings;
            discoveryRepository = oldDiscoveries;
            itemRepository = oldItems;
            recipeRepository = oldRecipes;
            stationRepository = oldStations;
            gridRepository = oldGrids;
            tableRepository = oldTables;
            cauldronRepository = oldCauldrons;
            try {
                registerLoadedRecipes();
                vanillaRecipes.reconcile(settingsRepository.disabledVanillaRecipes());
            } catch (RuntimeException | Error rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
        io.submit(() -> {
            io.configureMirrors(settingsRepository.sqlMirrors(),
                    settingsRepository.remoteSqlProfile());
            return null;
        });
    }

    private static void validateUniqueIds(RecipeRepository workbench, StationRecipeRepository stations,
                                          GridRecipeRepository grids) throws IOException {
        for (RecipeDefinition recipe : workbench.all()) {
            if (stations.get(recipe.key()).isPresent() || grids.get(recipe.key()).isPresent()) {
                throw new IOException("Recipe ID exists in two sources: " + recipe.key());
            }
        }
        for (StationRecipeDefinition recipe : stations.all()) {
            if (grids.get(recipe.key()).isPresent()) {
                throw new IOException("Recipe ID exists in two sources: " + recipe.key());
            }
        }
    }

    private void unregisterLoadedRecipes() {
        for (RecipeDefinition recipe : recipeRepository.all()) platform.unregister(recipe.key());
        for (StationRecipeDefinition recipe : stationRepository.all()) stations.unregister(recipe.key());
    }

    private void registerLoadedRecipes() {
        if (settingsRepository.lockdown()) {
            reconcileNativeRecipeBooksForOnlinePlayers();
            return;
        }
        for (RecipeDefinition recipe : recipeRepository.all()) {
            try { registerWorkbench(recipe); }
            catch (RuntimeException ex) { logAsync(Level.WARNING, "Could not register " + recipe.key() + ": " + ex.getMessage()); }
        }
        for (StationRecipeDefinition recipe : stationRepository.all()) {
            try { stations.register(recipe); }
            catch (RuntimeException ex) { logAsync(Level.WARNING, "Could not register " + recipe.key() + ": " + ex.getMessage()); }
        }
        reconcileNativeRecipeBooksForOnlinePlayers();
    }

    private void registerWorkbench(RecipeDefinition recipe) {
        if ((guardedCrafterCrafts || crafterRecipeCache != null)
                && NativeCrafterGuard.nativeRegistrationAllowed(recipe, guardedCrafterCrafts)) {
            platform.register(recipe);
        } else {
            // With no cancellable crafter event, no later click guard can deny
            // an automatic craft. Keep guarded recipes in the saved/custom grids.
            platform.unregister(recipe.key());
        }
    }

    void clearCrafterRecipeCache() {
        if (crafterRecipeCache != null) crafterRecipeCache.clear();
    }

    boolean canSafelySuppressNativeRecipes() {
        return guardedCrafterCrafts || crafterRecipeCache != null;
    }

    @Override public int backupRetention() { return settingsRepository.backupRetention(); }
    @Override public BackupFormat backupFormat() { return settingsRepository.backupFormat(); }
    @Override public Set<NamespacedKey> disabledVanillaRecipes() {
        requireServerThread();
        return settingsRepository.disabledVanillaRecipes();
    }
    List<Recipe> removedVanillaRecipes() { return vanillaRecipes.removedRecipes(); }
    @Override public boolean bookItemEnabled() { return settingsRepository.bookItemEnabled(); }
    BookItemListener bookItems() { return bookItemListener; }

    @Override
    public boolean hasDiscoveredRecipe(Player player, NamespacedKey key) {
        requireServerThread();
        return discoveryRepository.knows(player.getUniqueId(), key)
                || player.hasDiscoveredRecipe(key);
    }

    boolean isDiscovered(Player player, NamespacedKey key) {
        requireServerThread();
        return discoveryRepository.knows(player.getUniqueId(), key);
    }

    @Override
    public CompletionStage<Boolean> discoverRecipe(Player player, NamespacedKey key) {
        requireServerThread();
        if (recipe(key).isEmpty() && stationRecipe(key).isEmpty() && gridRecipe(key).isEmpty()) {
            throw new IllegalArgumentException("Unknown RecipeWeaver recipe: " + key);
        }
        java.util.UUID playerId = player.getUniqueId();
        CompletionStage<Boolean> discovery;
        if (mutationBlocked()) {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            deferredDiscoveries.add(new PendingDiscovery(playerId, key, result));
            discovery = result;
        } else {
            discovery = discoveryRepository.discoverAsync(playerId, key, io);
        }
        revealNativeBookAfterDiscovery(playerId, key, discovery);
        return discovery;
    }

    void markDiscovered(Player player, NamespacedKey key) {
        if (recipe(key).isEmpty() && stationRecipe(key).isEmpty() && gridRecipe(key).isEmpty()) return;
        java.util.UUID playerId = player.getUniqueId();
        NativeRecipeBook.afterFailed(discoverRecipe(player, key), failure -> {
            if (shuttingDown) return;
            logAsync(Level.WARNING, "Could not save recipe discovery for " + playerId
                    + " and " + key + ": " + failure.getMessage());
            try {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (shuttingDown) return;
                    Player online = Bukkit.getPlayer(playerId);
                    if (online != null) NativeRecipeBook.concealFailed(online, key,
                            discoveryRepository.knows(playerId, key));
                });
            } catch (RuntimeException ex) {
                if (!shuttingDown) logAsync(Level.WARNING,
                        "Could not conceal failed recipe discovery for " + playerId
                                + " and " + key + ": " + ex.getMessage());
            }
        });
    }

    private void revealNativeBookAfterDiscovery(UUID playerId, NamespacedKey key,
                                                CompletionStage<Boolean> discovery) {
        if (recipeRepository.get(key).isEmpty() && stationRepository.get(key).isEmpty()) return;
        NativeRecipeBook.afterDurable(discovery, () -> {
            if (shuttingDown) return;
            try {
                // A known key can complete inline during PlayerRecipeDiscoverEvent.
                // Wait until that event commits before a reveal can trigger another one.
                Bukkit.getScheduler().runTask(this, () -> {
                    if (shuttingDown) return;
                    Player online = Bukkit.getPlayer(playerId);
                    if (online == null || !discoveryRepository.knows(playerId, key)) return;
                    NativeRecipeBook.revealEarned(online, key,
                            recipeRepository.get(key).orElse(null),
                            stationRepository.get(key).orElse(null), lockedDown(),
                            Bukkit.getRecipe(key) != null);
                });
            } catch (RuntimeException ex) {
                if (!shuttingDown) logAsync(Level.WARNING,
                        "Could not update native recipe book for " + playerId
                                + " and " + key + ": " + ex.getMessage());
            }
        });
    }

    void logAsync(Level level, String message) {
        if (io == null) throw new IllegalStateException("RecipeWeaver I/O worker is unavailable");
        io.log(level, message);
    }

    void flushIo() throws IOException {
        if (io == null) return;
        if (Bukkit.isPrimaryThread()) throw new IOException(
                "Blocking I/O flush is unavailable on the server thread");
        try { io.flush().join(); }
        catch (CompletionException ex) {
            throw new IOException("Pending RecipeWeaver I/O failed", ex.getCause());
        }
    }

    @Override public CompletionStage<Void> setBookItemEnabledAsync(boolean enabled) {
        requireServerThread();
        if (mutationBlocked()) return reloadRejected();
        return settingsRepository.setBookItemEnabledAsync(enabled, io);
    }

    @Override public CompletionStage<Void> setBackupRetentionAsync(int count) {
        requireServerThread();
        if (mutationBlocked()) return reloadRejected();
        return settingsRepository.setBackupRetentionAsync(count, io);
    }

    boolean nativeRecipeBookUnavailable(NamespacedKey key) {
        RecipeDefinition workbench = recipeRepository.get(key).orElse(null);
        StationRecipeDefinition station = stationRepository.get(key).orElse(null);
        return NativeRecipeBook.blocksDiscovery(key, workbench, station, lockedDown(),
                settingsRepository.disabledVanillaRecipes().contains(key));
    }

    boolean nativeRecipeBookNeedsDurableUnlock(Player player, NamespacedKey key) {
        RecipeDefinition workbench = recipeRepository.get(key).orElse(null);
        StationRecipeDefinition station = stationRepository.get(key).orElse(null);
        return NativeRecipeBook.defersDiscovery(key, workbench, station, lockedDown(),
                settingsRepository.disabledVanillaRecipes().contains(key),
                discoveryRepository.knows(player.getUniqueId(), key));
    }

    void adoptNativeRecipeBook(Player player) {
        Collection<RecipeDefinition> workbench = recipeRepository.all();
        Collection<StationRecipeDefinition> stationRecipes = stationRepository.all();
        UUID playerId = player.getUniqueId();
        for (NamespacedKey key : NativeRecipeBook.adoptable(player, workbench, stationRecipes,
                candidate -> Bukkit.getRecipe(candidate) != null,
                candidate -> discoveryRepository.knows(playerId, candidate))) {
            markDiscovered(player, key);
        }
    }

    void reconcileNativeRecipeBook(Player player) {
        Collection<RecipeDefinition> workbench = recipeRepository.all();
        Collection<StationRecipeDefinition> stationRecipes = stationRepository.all();
        NativeRecipeBook.concealOrphans(player, workbench, stationRecipes);
        NativeRecipeBook.concealKnown(player, workbench, stationRecipes, lockedDown(),
                key -> Bukkit.getRecipe(key) != null,
                key -> discoveryRepository.knows(player.getUniqueId(), key));
        if (lockedDown()) return;
        NativeRecipeBook.revealKnown(player, workbench, stationRecipes,
                key -> discoveryRepository.knows(player.getUniqueId(), key),
                key -> Bukkit.getRecipe(key) != null);
    }

    private void reconcileNativeRecipeBooksForOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try { reconcileNativeRecipeBook(player); }
            catch (RuntimeException ex) {
                logAsync(Level.WARNING, "Could not update native recipe book for "
                        + player.getUniqueId() + ": " + ex.getMessage());
            }
        }
    }

    private void concealRemovedNativeRecipeForOnlinePlayers(NamespacedKey key) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try { NativeRecipeBook.concealRemoved(player, key); }
            catch (RuntimeException ex) {
                logAsync(Level.WARNING, "Could not remove deleted native recipe " + key
                        + " from the book for " + player.getUniqueId() + ": " + ex.getMessage());
            }
        }
    }

    @Override
    public CompletionStage<Void> setBackupFormatAsync(BackupFormat format) {
        requireServerThread();
        if (mutationBlocked()) return reloadRejected();
        return settingsRepository.setBackupFormatAsync(format, io);
    }

    @Override
    public CompletionStage<Void> setVanillaRecipeDisabledAsync(NamespacedKey key, boolean disabled) {
        requireServerThread();
        if (mutationBlocked()) return reloadRejected();
        if (key == null || !key.getNamespace().equals(NamespacedKey.MINECRAFT)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Only minecraft recipe keys can be disabled"));
        }
        if (disabled && !canSafelySuppressNativeRecipes()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Vanilla recipe exclusion requires a cancellable crafter event or safe cache reset"));
        }
        SettingsRepository target = settingsRepository;
        Set<NamespacedKey> previous = target.disabledVanillaRecipes();
        Set<NamespacedKey> requested = new HashSet<>(previous);
        if (disabled) requested.add(key);
        else requested.remove(key);
        if (requested.size() > 256) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "At most 256 vanilla recipes may be disabled"));
        }
        // The native registry must accept the change before an optimistic settings write
        // can expose or persist it. This is essential on 1.20.6 without a craft event.
        return vanillaRecipes.applyAndPersist(previous, requested,
                () -> target.setVanillaRecipeDisabledAsync(key, disabled, io),
                () -> settingsRepository == target ? target.disabledVanillaRecipes() : null);
    }

    @Override public CompletionStage<Void> setLockdownAsync(boolean enabled) {
        requireServerThread();
        if (mutationBlocked()) return reloadRejected();
        SettingsRepository target = settingsRepository;
        CompletionStage<Void> saved = target.setLockdownAsync(enabled, io);
        unregisterLoadedRecipes();
        registerLoadedRecipes();
        return saved.whenComplete((ignored, failure) -> {
            if (failure != null && settingsRepository == target) {
                unregisterLoadedRecipes();
                registerLoadedRecipes();
            }
        });
    }

    private static void requireServerThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Recipe changes must run on the server thread");
    }

    private static <T> CompletionStage<T> reloadRejected() {
        return CompletableFuture.failedFuture(
                new IllegalStateException("Reload or restore in progress; retry when it finishes"));
    }

    @Override
    public Optional<ItemStack> customItem(NamespacedKey key) {
        requireServerThread();
        return itemRepository.get(key);
    }

    @Override
    public Optional<ItemStack> resolveItem(NamespacedKey key) {
        requireServerThread();
        return key.getNamespace().equals("recipeweaver")
                ? itemRepository.get(key) : itemProviders.resolve(key);
    }

    @Override
    public boolean registerItemProvider(Plugin owner, String namespace, ItemProvider provider) {
        requireServerThread();
        requireLiveOwner(owner);
        return itemProviders.register(owner, namespace, provider);
    }

    @Override
    public boolean unregisterItemProvider(Plugin owner, String namespace) {
        requireServerThread();
        return itemProviders.unregister(owner, namespace);
    }

    @Override
    public void registerGridCondition(Plugin owner, NamespacedKey recipe, GridCraftCondition condition) {
        requireServerThread();
        requireLiveOwner(owner);
        gridExtensions.registerCondition(owner, recipe, condition);
    }

    @Override
    public boolean unregisterGridCondition(Plugin owner, NamespacedKey recipe) {
        requireServerThread();
        return gridExtensions.unregisterCondition(owner, recipe);
    }

    @Override
    public void registerGridAction(Plugin owner, NamespacedKey recipe, boolean perBatch,
                                   GridCraftAction action) {
        requireServerThread();
        requireLiveOwner(owner);
        gridExtensions.registerAction(owner, recipe, perBatch, action);
    }

    @Override
    public boolean unregisterGridAction(Plugin owner, NamespacedKey recipe) {
        requireServerThread();
        return gridExtensions.unregisterAction(owner, recipe);
    }

    @Override
    public void registerStationCondition(Plugin owner, NamespacedKey recipe, StationCraftCondition condition) {
        requireServerThread();
        requireLiveOwner(owner);
        requireOwnedStationCondition(recipe);
        stationExtensions.registerCondition(owner, recipe, condition);
    }

    @Override
    public boolean unregisterStationCondition(Plugin owner, NamespacedKey recipe) {
        requireServerThread();
        return stationExtensions.unregisterCondition(owner, recipe);
    }

    @Override
    public void registerStationAction(Plugin owner, NamespacedKey recipe, StationCraftAction action) {
        requireServerThread();
        requireLiveOwner(owner);
        requireOwnedStation(recipe);
        stationExtensions.registerAction(owner, recipe, action);
    }

    @Override
    public boolean unregisterStationAction(Plugin owner, NamespacedKey recipe) {
        requireServerThread();
        return stationExtensions.unregisterAction(owner, recipe);
    }

    private void requireOwnedStation(NamespacedKey key) {
        StationRecipeDefinition recipe = stationRepository.get(key).orElseThrow(() ->
                new IllegalArgumentException("Unknown station recipe: " + key));
        if (recipe.kind() != StationKind.ANVIL && recipe.kind() != StationKind.GRINDSTONE
                && recipe.kind() != StationKind.CAULDRON
                && recipe.kind() != StationKind.BREWING) {
            throw new IllegalArgumentException("Station callbacks require an anvil, grindstone, cauldron, or brewing recipe");
        }
    }

    private void requireOwnedStationCondition(NamespacedKey key) {
        StationRecipeDefinition recipe = stationRepository.get(key).orElseThrow(() ->
                new IllegalArgumentException("Unknown station recipe: " + key));
        if (!recipe.kind().cooking()) requireOwnedStation(key);
    }

    GridExtensions gridExtensions() { return gridExtensions; }
    StationExtensions stationExtensions() { return stationExtensions; }

    private static void requireLiveOwner(Plugin owner) {
        if (owner == null || !owner.isEnabled()
                || Bukkit.getPluginManager().getPlugin(owner.getName()) != owner) {
            throw new IllegalArgumentException("Extension owner must be an enabled installed plugin");
        }
    }

    @Override
    public CompletionStage<Void> saveCustomItem(NamespacedKey key, ItemStack item) {
        requireServerThread();
        if (mutationBlocked()) return reloadRejected();
        return itemRepository.saveAsync(key, item, io);
    }

    @Override
    public CompletionStage<Boolean> removeCustomItem(NamespacedKey key) {
        requireServerThread();
        if (mutationBlocked()) return reloadRejected();
        return itemRepository.removeAsync(key, io);
    }

    ItemRepository items() { return itemRepository; }
    @Override
    public CompletionStage<String> createBackupAsync() {
        requireServerThread();
        if (mutationBlocked()) return reloadRejected();
        if ((tableListener != null && tableListener.pendingBreakCleanup())
                || (tableRepository != null && tableRepository.pendingWrite())
                || (cauldronListener != null && cauldronListener.pendingBreakCleanup())
                || (cauldronRepository != null && cauldronRepository.pendingWrite())) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("A table or cauldron break is finishing; retry backup next tick"));
        }
        return backupService.createBackupAsync(io);
    }

    @Override
    public CompletionStage<String> restoreDataAsync(String backupName) {
        requireServerThread();
        if (mutationBlocked() || backupService.busy()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Backup, reload, or restore in progress; retry when it finishes"));
        }
        if ((tableListener != null && tableListener.pendingBreakCleanup())
                || (tableRepository != null && tableRepository.pendingWrite())
                || (cauldronListener != null && cauldronListener.pendingBreakCleanup())
                || (cauldronRepository != null && cauldronRepository.pendingWrite())) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("A table or cauldron break is finishing; retry restore next tick"));
        }
        restoreFinalizing = true;
        CompletableFuture<String> completed = new CompletableFuture<>();
        activeRestore = completed;
        backupService.restoreAsync(backupName, io, this::applySnapshot)
                .whenComplete((previous, failure) -> {
                    Runnable finish = () -> {
                        restoreFinalizing = false;
                        activeRestore = null;
                        replayDeferredDiscoveries();
                        if (failure == null) completed.complete(previous);
                        else completed.completeExceptionally(unwrapCompletion(failure));
                    };
                    if (Bukkit.isPrimaryThread()) finish.run();
                    else {
                        try { dispatchMain(finish); }
                        catch (RuntimeException ex) {
                            restoreFinalizing = false;
                            activeRestore = null;
                            completed.completeExceptionally(ex);
                        }
                    }
                });
        return completed;
    }

    @Override
    public CompletionStage<List<String>> listBackupsAsync() {
        requireServerThread();
        return backupService.listBackupsAsync(io);
    }

    CompletionStage<Integer> exportLocalSqlAsync(SqlDocumentStore.Backend backend) {
        requireServerThread();
        if (storageSnapshotBusy()) return CompletableFuture.failedFuture(
                new IllegalStateException("Backup, reload, restore, or SQL snapshot in progress"));
        if ((tableRepository != null && tableRepository.pendingWrite())
                || (cauldronRepository != null && cauldronRepository.pendingWrite())) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("A table or cauldron write is finishing; retry next tick"));
        }
        return finishSqlSnapshot(sqlSnapshots.exportAsync(backend, io));
    }

    CompletionStage<String> prepareLocalSqlRestoreAsync(SqlDocumentStore.Backend backend) {
        requireServerThread();
        if (storageSnapshotBusy()) return CompletableFuture.failedFuture(
                new IllegalStateException("Backup, reload, restore, or SQL snapshot in progress"));
        return prepareValidatedSqlRestoreAsync(SqlMirrorMode.valueOf(backend.name()),
                RemoteSqlProfile.defaults());
    }

    CompletionStage<Boolean> testRemoteSqlAsync() {
        requireServerThread();
        if (storageSnapshotBusy()) return CompletableFuture.failedFuture(
                new IllegalStateException("Backup, reload, restore, or SQL source change in progress"));
        return finishSqlSnapshot(sqlSnapshots.testRemoteAsync(settingsRepository.remoteSqlProfile(), io));
    }

    CompletionStage<Integer> exportRemoteSqlAsync() {
        requireServerThread();
        if (storageSnapshotBusy()) return CompletableFuture.failedFuture(
                new IllegalStateException("Backup, reload, restore, or SQL source change in progress"));
        if ((tableRepository != null && tableRepository.pendingWrite())
                || (cauldronRepository != null && cauldronRepository.pendingWrite())) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("A table or cauldron write is finishing; retry next tick"));
        }
        return finishSqlSnapshot(sqlSnapshots.exportRemoteAsync(settingsRepository.remoteSqlProfile(), io));
    }

    CompletionStage<String> prepareRemoteSqlRestoreAsync() {
        requireServerThread();
        if (storageSnapshotBusy()) return CompletableFuture.failedFuture(
                new IllegalStateException("Backup, reload, restore, or SQL source change in progress"));
        RemoteSqlProfile profile = settingsRepository.remoteSqlProfile();
        return prepareValidatedSqlRestoreAsync(profile.backend(), profile);
    }

    private CompletionStage<String> prepareValidatedSqlRestoreAsync(SqlMirrorMode mode,
                                                                     RemoteSqlProfile profile) {
        List<SqlMirrorMode> source = List.of(mode);
        return finishSqlSnapshot(sqlSnapshots.auditCandidatesAsync(source, profile, io))
                .thenCompose(candidates -> {
                    SqlSourceStatus status = SqlSnapshotService.validateAuditedSources(
                            candidates, backupService).get(0);
                    if (status.failure() != null) {
                        return CompletableFuture.failedFuture(new IOException(
                                "SQL source is not a valid RecipeWeaver snapshot"));
                    }
                    return finishSqlSnapshot(sqlSnapshots.prepareSelectedRestoreAsync(
                            source, profile, mode, status.sha256(), io))
                            .thenApply(SqlPreparedSource::backupName);
                });
    }

    @Override public CompletionStage<List<SqlSourceStatus>> auditSqlSourcesAsync() {
        requireServerThread();
        if (storageSnapshotBusy()) return CompletableFuture.failedFuture(
                new IllegalStateException("Backup, reload, restore, or SQL source change in progress"));
        return finishSqlSnapshot(sqlSnapshots.auditCandidatesAsync(settingsRepository.sqlMirrors(),
                settingsRepository.remoteSqlProfile(), io)).thenApply(candidates ->
                        SqlSnapshotService.validateAuditedSources(candidates, backupService));
    }

    @Override public CompletionStage<SqlPreparedSource> prepareOrderedSqlRestoreAsync() {
        requireServerThread();
        if (storageSnapshotBusy()) return CompletableFuture.failedFuture(
                new IllegalStateException("Backup, reload, restore, or SQL source change in progress"));
        List<SqlMirrorMode> selected = settingsRepository.sqlMirrors();
        RemoteSqlProfile profile = settingsRepository.remoteSqlProfile();
        return finishSqlSnapshot(sqlSnapshots.auditCandidatesAsync(selected, profile, io))
                .thenCompose(candidates -> {
                    for (SqlSourceStatus status : SqlSnapshotService.validateAuditedSources(
                            candidates, backupService)) {
                        if (status.failure() == null) {
                            return finishSqlSnapshot(sqlSnapshots.prepareSelectedRestoreAsync(
                                    selected, profile, status.backend(), status.sha256(), io));
                        }
                    }
                    return CompletableFuture.failedFuture(new IOException(
                            "No selected SQL source has a readable, valid snapshot"));
                });
    }

    @Override public CompletionStage<SqlPreparedSource> prepareSqlSourceRestoreAsync(
            SqlMirrorMode mode, String expectedSha256) {
        requireServerThread();
        if (storageSnapshotBusy()) return CompletableFuture.failedFuture(
                new IllegalStateException("Backup, reload, restore, or SQL source change in progress"));
        try {
            List<SqlMirrorMode> selected = settingsRepository.sqlMirrors();
            RemoteSqlProfile profile = settingsRepository.remoteSqlProfile();
            if (mode == null || !selected.contains(mode)) throw new IllegalArgumentException(
                    "SQL source is not selected: " + mode);
            if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Expected SQL source SHA-256 is invalid");
            }
            return finishSqlSnapshot(sqlSnapshots.auditCandidatesAsync(List.of(mode), profile, io))
                    .thenCompose(candidates -> {
                        SqlSourceStatus status = SqlSnapshotService.validateAuditedSources(
                                candidates, backupService).get(0);
                        if (status.failure() != null) return CompletableFuture.failedFuture(
                                new IOException("SQL source is not a valid RecipeWeaver snapshot"));
                        if (!expectedSha256.equals(status.sha256())) return CompletableFuture.failedFuture(
                                new IOException("SQL source changed since inspection; inspect it again"));
                        return finishSqlSnapshot(sqlSnapshots.prepareSelectedRestoreAsync(
                                selected, profile, mode, expectedSha256, io));
                    });
        } catch (IllegalArgumentException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    private <T> CompletionStage<T> finishSqlSnapshot(CompletableFuture<T> operation) {
        CompletableFuture<T> completed = new CompletableFuture<>();
        operation.whenComplete((value, failure) -> {
            try {
                dispatchMain(() -> {
                    if (!sqlSnapshots.busy()) replayDeferredDiscoveries();
                    if (failure == null) completed.complete(value);
                    else completed.completeExceptionally(unwrapCompletion(failure));
                });
            } catch (RuntimeException ex) {
                completed.completeExceptionally(ex);
            }
        });
        return completed;
    }

    @Override public SqlMirrorMode sqlMirrorMode() {
        requireServerThread();
        return settingsRepository.sqlMirrorBackend();
    }

    @Override public List<SqlMirrorMode> sqlMirrorModes() {
        requireServerThread();
        return settingsRepository.sqlMirrors();
    }

    @Override public String sqlMirrorFailure() { return io.mirrorFailure(); }

    @Override public Map<SqlMirrorMode, String> sqlMirrorFailures() { return io.mirrorFailures(); }

    @Override public SqlReadPolicy sqlReadPolicy() {
        requireServerThread();
        return settingsRepository.sqlReadPolicy();
    }

    @Override public SqlMirrorMode startupSqlSource() {
        requireServerThread();
        return startupSqlSource;
    }

    @Override public SqlMirrorMode lastReloadSqlSource() {
        requireServerThread();
        return lastReloadSqlSource;
    }

    @Override public CompletionStage<Void> setSqlReadPolicyAsync(SqlReadPolicy policy) {
        requireServerThread();
        java.util.Objects.requireNonNull(policy, "policy");
        if (storageSnapshotBusy()) return CompletableFuture.failedFuture(
                new IllegalStateException("Backup, reload, restore, or SQL source change in progress"));
        sourceSwitchBusy = true;
        CompletableFuture<Void> completed = new CompletableFuture<>();
        try {
            settingsRepository.setSqlReadPolicyAsync(policy, io).whenComplete((saved, failure) -> {
                finishSourceSwitch(completed, failure);
            });
        } catch (RuntimeException ex) {
            sourceSwitchBusy = false;
            return CompletableFuture.failedFuture(ex);
        }
        return completed;
    }

    RemoteSqlProfile remoteSqlProfile() {
        requireServerThread();
        return settingsRepository.remoteSqlProfile();
    }

    @Override public SqlMirrorMode remoteSqlBackend() { return remoteSqlProfile().backend(); }
    @Override public String remoteSqlJdbcUrl() { return remoteSqlProfile().jdbcUrl(); }
    @Override public String remoteSqlUsername() { return remoteSqlProfile().username(); }
    @Override public boolean remoteSqlPasswordConfigured() {
        return !remoteSqlProfile().password().isEmpty();
    }

    @Override public CompletionStage<Void> setRemoteSqlConnectionAsync(SqlMirrorMode backend,
                                                                       String jdbcUrl, String username,
                                                                       String password) {
        return setRemoteSqlProfileAsync(new RemoteSqlProfile(backend, jdbcUrl, username, password));
    }

    CompletionStage<Void> setRemoteSqlProfileAsync(RemoteSqlProfile profile) {
        requireServerThread();
        if (storageSnapshotBusy()) return CompletableFuture.failedFuture(
                new IllegalStateException("Backup, reload, restore, or SQL source change in progress"));
        sourceSwitchBusy = true;
        CompletableFuture<Void> completed = new CompletableFuture<>();
        CompletableFuture<Void> write;
        try { write = settingsRepository.setRemoteSqlProfileAsync(profile, io); }
        catch (RuntimeException ex) {
            sourceSwitchBusy = false;
            return CompletableFuture.failedFuture(ex);
        }
        write.whenComplete((saved, saveFailure) -> {
            if (saveFailure != null) {
                finishSourceSwitch(completed, saveFailure);
                return;
            }
            List<SqlMirrorMode> modes = settingsRepository.sqlMirrors();
            if (modes.stream().noneMatch(SqlMirrorMode::remote)) {
                finishSourceSwitch(completed, null);
                return;
            }
            io.submit(() -> {
                io.configureMirrors(modes, profile);
                return null;
            }).whenComplete((configured, configureFailure) -> {
                finishSourceSwitch(completed, configureFailure);
            });
        });
        return completed;
    }

    @Override public CompletionStage<Void> setSqlMirrorModeAsync(SqlMirrorMode backend) {
        java.util.Objects.requireNonNull(backend, "backend");
        return setSqlMirrorModesAsync(backend == SqlMirrorMode.NONE ? List.of() : List.of(backend));
    }

    @Override public CompletionStage<Void> setSqlMirrorModesAsync(List<SqlMirrorMode> modes) {
        requireServerThread();
        List<SqlMirrorMode> selected = SqlMirrorSelection.validate(modes,
                settingsRepository.remoteSqlProfile());
        if (storageSnapshotBusy()) return CompletableFuture.failedFuture(
                new IllegalStateException("Backup, reload, restore, or SQL source change in progress"));
        sourceSwitchBusy = true;
        CompletableFuture<Void> completed = new CompletableFuture<>();
        CompletableFuture<Void> write;
        try { write = settingsRepository.setSqlMirrorsAsync(selected, io); }
        catch (RuntimeException ex) {
            sourceSwitchBusy = false;
            return CompletableFuture.failedFuture(ex);
        }
        write.whenComplete((saved, saveFailure) -> {
            if (saveFailure != null) {
                finishSourceSwitch(completed, saveFailure);
                return;
            }
            io.submit(() -> {
                io.configureMirrors(selected, settingsRepository.remoteSqlProfile());
                return null;
            }).whenComplete((configured, configureFailure) -> {
                finishSourceSwitch(completed, configureFailure);
            });
        });
        return completed;
    }

    /** Publish source-setting results and replay queued discoveries on the Bukkit thread. */
    private void finishSourceSwitch(CompletableFuture<Void> completed, Throwable failure) {
        try {
            dispatchMain(() -> {
                sourceSwitchBusy = false;
                replayDeferredDiscoveries();
                if (failure == null) completed.complete(null);
                else completed.completeExceptionally(unwrapCompletion(failure));
            });
        } catch (RuntimeException ex) {
            sourceSwitchBusy = false;
            completed.completeExceptionally(ex);
        }
    }

    @Override public CompletionStage<Boolean> resyncSqlMirrorAsync() {
        return resyncSqlMirrorAsync(null);
    }

    @Override public CompletionStage<Boolean> resyncSqlMirrorAsync(SqlMirrorMode mode) {
        requireServerThread();
        if (mode != null && !settingsRepository.sqlMirrors().contains(mode)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "SQL mirror is not selected: " + mode));
        }
        if (storageSnapshotBusy()) return CompletableFuture.failedFuture(
                new IllegalStateException("Backup, reload, restore, or SQL source change in progress"));
        sourceSwitchBusy = true;
        CompletableFuture<Boolean> completed = new CompletableFuture<>();
        resyncSqlMirrorOnWorkerAsync(io, mode)
                .whenComplete((synced, failure) -> {
            try {
                dispatchMain(() -> {
                    sourceSwitchBusy = false;
                    replayDeferredDiscoveries();
                    if (failure == null) completed.complete(synced);
                    else completed.completeExceptionally(unwrapCompletion(failure));
                });
            } catch (RuntimeException ex) {
                sourceSwitchBusy = false;
                completed.completeExceptionally(ex);
            }
        });
        return completed;
    }

    /** Manual propagation must wait for all earlier local saves to succeed. */
    static CompletableFuture<Boolean> resyncSqlMirrorOnWorkerAsync(AsyncIoService io,
                                                                     SqlMirrorMode mode) {
        return io.submitAfterSuccessfulWrites(() ->
                mode == null ? io.synchronizeMirrors() : io.synchronizeMirror(mode));
    }

    @Override public CompletionStage<Boolean> replaceSqlMirrorFromLocalAsync(
            SqlMirrorMode mode, String expectedSha256) {
        requireServerThread();
        if (mode == null || !settingsRepository.sqlMirrors().contains(mode)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "SQL mirror is not selected: " + mode));
        }
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Expected SQL source SHA-256 is invalid"));
        }
        if (storageSnapshotBusy()) return CompletableFuture.failedFuture(
                new IllegalStateException("Backup, reload, restore, or SQL source change in progress"));
        sourceSwitchBusy = true;
        CompletableFuture<Boolean> completed = new CompletableFuture<>();
        replaceSqlMirrorFromLocalOnWorkerAsync(io, mode, expectedSha256)
                .whenComplete((synced, failure) -> {
            try {
                dispatchMain(() -> {
                    sourceSwitchBusy = false;
                    replayDeferredDiscoveries();
                    if (failure == null) completed.complete(synced);
                    else completed.completeExceptionally(unwrapCompletion(failure));
                });
            } catch (RuntimeException ex) {
                sourceSwitchBusy = false;
                completed.completeExceptionally(ex);
            }
        });
        return completed;
    }

    /** A local-wins SQL overwrite must not bypass a queued failed local save. */
    static CompletableFuture<Boolean> replaceSqlMirrorFromLocalOnWorkerAsync(AsyncIoService io,
                                                                              SqlMirrorMode mode,
                                                                              String expectedSha256) {
        return io.submitObservedAfterSuccessfulWrites(() ->
                io.replaceMirrorFromLocal(mode, expectedSha256));
    }

    CompletionStage<Void> putTableAsync(org.bukkit.Location location,
                                        dev.recipeweaver.api.GridTier tier) throws IOException {
        requireServerThread();
        return tableRepository.putAsync(location, tier, io);
    }

    CompletionStage<Boolean> removeTableAsync(org.bukkit.Location location) throws IOException {
        requireServerThread();
        return tableRepository.removeAsync(location, io);
    }

    CompletionStage<Void> putCauldronAsync(org.bukkit.Location location, ItemStack item)
            throws IOException {
        requireServerThread();
        return cauldronRepository.putAsync(location, item, io);
    }

    CompletionStage<ItemStack> removeCauldronAsync(org.bukkit.Location location)
            throws IOException {
        requireServerThread();
        return cauldronRepository.removeAsync(location, io);
    }

    BackupService backups() { return backupService; }
    BookPrompt bookPrompt() { return bookPrompt; }
    StudioMenus menus() { return menus; }
    StationMenus stationMenus() { return stationMenus; }
    GridMenus gridMenus() { return gridMenus; }
    TableListener tableListener() { return tableListener; }
    TableRepository tables() { return tableRepository; }
    CauldronRepository cauldrons() { return cauldronRepository; }
    PlatformAdapter platform() { return platform; }

    @Override public Collection<RecipeDefinition> recipes() { return recipeRepository.all(); }
    @Override public Optional<RecipeDefinition> recipe(NamespacedKey key) { return recipeRepository.get(key); }
    @Override public Collection<StationRecipeDefinition> stationRecipes() { return stationRepository.all(); }
    @Override public Optional<StationRecipeDefinition> stationRecipe(NamespacedKey key) { return stationRepository.get(key); }
    @Override public Collection<GridRecipeDefinition> gridRecipes() { return gridRepository.all(); }
    @Override public Optional<GridRecipeDefinition> gridRecipe(NamespacedKey key) { return gridRepository.get(key); }
    @Override public boolean lockedDown() { return settingsRepository.lockdown(); }

    @Override
    public void saveRecipe(RecipeDefinition recipe) {
        saveRecipeAsync(recipe).whenComplete((ignored, failure) -> {
            if (failure != null) logAsync(Level.WARNING, "Could not save recipe " + recipe.key() + ": " + failure);
        });
    }

    @Override
    public CompletionStage<Void> saveRecipeAsync(RecipeDefinition recipe) {
        requireServerThread();
        if (mutationBlocked()) return reloadRejected();
        if (stationRepository.get(recipe.key()).isPresent() || gridRepository.get(recipe.key()).isPresent()) {
            throw new IllegalArgumentException("ID belongs to another recipe source");
        }
        BukkitRecipeAdapter.validate(recipe);
        RecipeRepository target = recipeRepository;
        RecipeDefinition previous = target.get(recipe.key()).orElse(null);
        if (!lockedDown()) {
            try { registerWorkbench(recipe); }
            catch (RuntimeException ex) { restoreWorkbench(previous, recipe.key()); throw ex; }
        }
        try {
            return target.saveAsync(recipe, io, () -> {
                if (recipeRepository == target) {
                    try { restoreWorkbench(target.get(recipe.key()).orElse(null), recipe.key()); }
                    catch (RuntimeException ex) {
                        logAsync(Level.SEVERE, "Could not restore native recipe " + recipe.key() + ": " + ex);
                    }
                }
            }).thenRun(() -> {
                if (recipeRepository == target && (previous == null
                        || previous.hidden() != recipe.hidden()
                        || previous.enabled() != recipe.enabled())) {
                    reconcileNativeRecipeBooksForOnlinePlayers();
                }
            });
        } catch (RuntimeException ex) {
            restoreWorkbench(previous, recipe.key());
            throw ex;
        }
    }

    private void restoreWorkbench(RecipeDefinition previous, NamespacedKey key) {
        platform.unregister(key);
        if (previous != null && !lockedDown()) registerWorkbench(previous);
    }

    @Override
    public boolean removeRecipe(NamespacedKey key) {
        requireServerThread();
        if (mutationBlocked() || recipeRepository.get(key).isEmpty()) return false;
        removeRecipeAsync(key).whenComplete((ignored, failure) -> {
            if (failure != null) logAsync(Level.WARNING, "Could not remove recipe " + key + ": " + failure);
        });
        return true;
    }

    @Override
    public CompletionStage<Boolean> removeRecipeAsync(NamespacedKey key) {
        requireServerThread();
        if (mutationBlocked()) return reloadRejected();
        RecipeRepository target = recipeRepository;
        RecipeDefinition previous = target.get(key).orElse(null);
        if (previous == null) return java.util.concurrent.CompletableFuture.completedFuture(false);
        platform.unregister(key);
        try {
            return target.removeAsync(key, io, () -> {
                if (recipeRepository == target) {
                    try { restoreWorkbench(target.get(key).orElse(null), key); }
                    catch (RuntimeException ex) {
                        logAsync(Level.SEVERE, "Could not restore native recipe " + key + ": " + ex);
                    }
                }
            }).thenApply(removed -> {
                if (removed && recipeRepository == target) concealRemovedNativeRecipeForOnlinePlayers(key);
                return removed;
            });
        } catch (RuntimeException ex) {
            restoreWorkbench(previous, key);
            throw ex;
        }
    }

    @Override
    public void saveStationRecipe(StationRecipeDefinition recipe) {
        saveStationRecipeAsync(recipe).whenComplete((ignored, failure) -> {
            if (failure != null) logAsync(Level.WARNING, "Could not save station recipe " + recipe.key() + ": " + failure);
        });
    }

    @Override
    public CompletionStage<Void> saveStationRecipeAsync(StationRecipeDefinition recipe) {
        requireServerThread();
        if (mutationBlocked()) return reloadRejected();
        if (recipeRepository.get(recipe.key()).isPresent() || gridRepository.get(recipe.key()).isPresent()) {
            throw new IllegalArgumentException("ID belongs to another recipe source");
        }
        StationIngredientMatcher.validateTags(recipe);
        StationRecipeRepository target = stationRepository;
        StationRecipeDefinition previous = target.get(recipe.key()).orElse(null);
        if (!lockedDown()) {
            try { stations.register(recipe); }
            catch (RuntimeException ex) { restoreStation(previous, recipe.key()); throw ex; }
        }
        try {
            return target.saveAsync(recipe, io, () -> {
                if (stationRepository == target) {
                    try { restoreStation(target.get(recipe.key()).orElse(null), recipe.key()); }
                    catch (RuntimeException ex) {
                        logAsync(Level.SEVERE, "Could not restore native station recipe " + recipe.key() + ": " + ex);
                    }
                }
            }).thenRun(() -> {
                if (stationRepository == target && (previous == null
                        || previous.hidden() != recipe.hidden()
                        || previous.enabled() != recipe.enabled()
                        || previous.kind() != recipe.kind())) {
                    reconcileNativeRecipeBooksForOnlinePlayers();
                }
            });
        } catch (RuntimeException ex) {
            restoreStation(previous, recipe.key());
            throw ex;
        }
    }

    private void restoreStation(StationRecipeDefinition previous, NamespacedKey key) {
        stations.unregister(key);
        if (previous != null && !lockedDown()) stations.register(previous);
    }

    @Override
    public boolean removeStationRecipe(NamespacedKey key) {
        requireServerThread();
        if (mutationBlocked() || stationRepository.get(key).isEmpty()) return false;
        removeStationRecipeAsync(key).whenComplete((ignored, failure) -> {
            if (failure != null) logAsync(Level.WARNING, "Could not remove station recipe " + key + ": " + failure);
        });
        return true;
    }

    @Override
    public CompletionStage<Boolean> removeStationRecipeAsync(NamespacedKey key) {
        requireServerThread();
        if (mutationBlocked()) return reloadRejected();
        StationRecipeRepository target = stationRepository;
        StationRecipeDefinition previous = target.get(key).orElse(null);
        if (previous == null) return java.util.concurrent.CompletableFuture.completedFuture(false);
        stations.unregister(key);
        try {
            return target.removeAsync(key, io, () -> {
                if (stationRepository == target) {
                    try { restoreStation(target.get(key).orElse(null), key); }
                    catch (RuntimeException ex) {
                        logAsync(Level.SEVERE, "Could not restore native station recipe " + key + ": " + ex);
                    }
                }
            }).thenApply(removed -> {
                if (removed && stationRepository == target) concealRemovedNativeRecipeForOnlinePlayers(key);
                return removed;
            });
        } catch (RuntimeException ex) {
            restoreStation(previous, key);
            throw ex;
        }
    }

    @Override
    public void saveGridRecipe(GridRecipeDefinition recipe) {
        saveGridRecipeAsync(recipe).whenComplete((ignored, failure) -> {
            if (failure != null) logAsync(Level.WARNING, "Could not save grid recipe " + recipe.key() + ": " + failure);
        });
    }

    @Override
    public CompletionStage<Void> saveGridRecipeAsync(GridRecipeDefinition recipe) {
        requireServerThread();
        if (mutationBlocked()) return reloadRejected();
        GridMatcher.validateTags(recipe);
        if (recipeRepository.get(recipe.key()).isPresent()
                || stationRepository.get(recipe.key()).isPresent()) {
            throw new IllegalArgumentException("ID belongs to another recipe source");
        }
        GridRecipeRepository target = gridRepository;
        return target.saveAsync(recipe, io, () -> { });
    }

    @Override
    public boolean removeGridRecipe(NamespacedKey key) {
        requireServerThread();
        if (mutationBlocked() || gridRepository.get(key).isEmpty()) return false;
        removeGridRecipeAsync(key).whenComplete((ignored, failure) -> {
            if (failure != null) logAsync(Level.WARNING, "Could not remove grid recipe " + key + ": " + failure);
        });
        return true;
    }

    @Override
    public CompletionStage<Boolean> removeGridRecipeAsync(NamespacedKey key) {
        requireServerThread();
        if (mutationBlocked()) return reloadRejected();
        GridRecipeRepository target = gridRepository;
        return target.removeAsync(key, io, () -> { });
    }

}
