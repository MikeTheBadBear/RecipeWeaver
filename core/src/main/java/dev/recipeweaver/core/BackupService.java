package dev.recipeweaver.core;

import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.BackupFormat;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.bukkit.plugin.Plugin;

/** Snapshots only RecipeWeaver-owned data and validates a restore before replacing live files. */
final class BackupService {
    private static final List<String> TARGETS = List.of(
            "recipes", "stations", "grid-recipes", "items.yml", "settings.yml", "tables.yml",
            "cauldrons.yml", "discoveries");
    private static final long MAX_ARCHIVE_BYTES = 64L * 1024 * 1024;
    private static final int MAX_ENTRIES = 10000;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private final Plugin plugin;
    private final Path dataRoot;
    private final Path backupDirectory;
    private final ReloadAction reload;
    private final IntSupplier retention;
    private final Supplier<BackupFormat> format;
    private final Consumer<Runnable> mainCallback;
    private final AtomicBoolean backupBusy = new AtomicBoolean();
    private final AtomicBoolean restoreBusy = new AtomicBoolean();
    private volatile List<String> cachedBackups = List.of();

    @FunctionalInterface
    interface ReloadAction { void reload() throws IOException; }

    @FunctionalInterface
    interface SnapshotAction { void apply(DiskSnapshot snapshot) throws IOException; }

    static final class InvalidSourceException extends IOException {
        InvalidSourceException(Throwable cause) {
            super("Selected source failed RecipeWeaver data validation", cause);
        }
    }

    BackupService(Plugin plugin, ReloadAction reload) { this(plugin, reload, () -> 0); }

    BackupService(Plugin plugin, ReloadAction reload, IntSupplier retention) {
        this(plugin, reload, retention, action -> Bukkit.getScheduler().runTask(plugin, action));
    }

    BackupService(Plugin plugin, ReloadAction reload, IntSupplier retention,
                  Consumer<Runnable> mainCallback) {
        this(plugin, reload, retention, () -> BackupFormat.ZIP, mainCallback);
    }

    BackupService(Plugin plugin, ReloadAction reload, IntSupplier retention,
                  Supplier<BackupFormat> format, Consumer<Runnable> mainCallback) {
        this.plugin = plugin;
        this.dataRoot = plugin.getDataFolder().toPath();
        this.backupDirectory = dataRoot.resolve("backups");
        this.reload = reload;
        this.retention = retention;
        this.format = format;
        this.mainCallback = mainCallback;
    }

    String createBackup() throws IOException {
        LegacyIoGuard.testOnly(plugin);
        return createBackup(true, retention.getAsInt(), format.get());
    }

    CompletableFuture<String> createBackupAsync(AsyncIoService io) {
        synchronized (this) {
            if (restoreBusy.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Restore already in progress"));
            }
            if (!backupBusy.compareAndSet(false, true)) {
                return CompletableFuture.failedFuture(new IllegalStateException("Backup already in progress"));
            }
        }
        try {
            int retentionLimit = retention.getAsInt();
            BackupFormat selectedFormat = format.get();
            // An archive failure is reported by this future and leaves local data unchanged;
            // it must not become a stale write failure for the next flush or backup attempt.
            return io.submitObservedAfterSuccessfulWrites(() -> createBackup(true, retentionLimit, selectedFormat))
                    .whenComplete((name, failure) -> backupBusy.set(false));
        } catch (RuntimeException ex) {
            backupBusy.set(false);
            return CompletableFuture.failedFuture(ex);
        }
    }

    boolean busy() { return backupBusy.get() || restoreBusy.get(); }
    boolean restoring() { return restoreBusy.get(); }

    /** ZIP and file replacement run on the ordered worker; Bukkit parsing and publication run on the server thread. */
    CompletableFuture<String> restoreAsync(String name, AsyncIoService io, SnapshotAction publish) {
        return restoreAsync(name, io, publish, false);
    }

    /** SQL recovery may replace unreadable local bytes; the recovery archive still preserves them. */
    CompletableFuture<String> restoreUnreadableLocalAsync(String name, AsyncIoService io,
                                                            SnapshotAction publish) {
        return restoreAsync(name, io, publish, true);
    }

    private CompletableFuture<String> restoreAsync(String name, AsyncIoService io,
                                                    SnapshotAction publish, boolean unreadableLocal) {
        if (!validName(name)) return CompletableFuture.failedFuture(new IOException("Invalid backup name"));
        synchronized (this) {
            if (backupBusy.get() || !restoreBusy.compareAndSet(false, true)) {
                return CompletableFuture.failedFuture(new IllegalStateException("Backup or restore already in progress"));
            }
        }
        CompletableFuture<String> result = new CompletableFuture<>();
        final int retentionLimit;
        final BackupFormat selectedFormat;
        try {
            retentionLimit = retention.getAsInt();
            selectedFormat = format.get();
        }
        catch (RuntimeException ex) {
            finishRestore(result, null, ex);
            return result;
        }
        // A missing or unreadable source is this restore's failure, not a failed local
        // write that should poison the next flush or backup. Preserve the prior-write gate.
        io.submitObservedAfterSuccessfulWrites(() -> prepareRestore(name))
                .whenComplete((prepared, prepareFailure) -> {
                    if (prepareFailure != null) {
                        finishRestore(result, null, prepareFailure);
                        return;
                    }
                    onMain(() -> {
                        validate(prepared.stage(), prepared.snapshot());
                        return null;
                    }).whenComplete((ignored, validationFailure) -> {
                        if (validationFailure != null) {
                            Throwable invalid = validationFailure instanceof IOException
                                    ? new InvalidSourceException(validationFailure) : validationFailure;
                            io.submit(() -> { deleteTree(prepared.stage()); return null; })
                                    .whenComplete((cleaned, cleanupFailure) -> {
                                        if (cleanupFailure != null) invalid.addSuppressed(cleanupFailure);
                                        finishRestore(result, null, invalid);
                                    });
                            return;
                        }
                        io.submit(() -> commitRestore(prepared, retentionLimit, selectedFormat,
                                unreadableLocal))
                                .whenComplete((commit, commitFailure) -> {
                                    if (commitFailure != null) {
                                        finishRestore(result, null, commitFailure);
                                        return;
                                    }
                                    onMain(() -> {
                                        publish.apply(prepared.snapshot());
                                        return null;
                                    }).whenComplete((published, publishFailure) -> {
                                        if (publishFailure == null) {
                                            io.submit(() -> {
                                                applyRetention(commit.previous(), retentionLimit);
                                                try { cleanupRestore(commit); }
                                                catch (IOException ex) {
                                                    log(Level.WARNING, "Restore succeeded, but temporary cleanup failed: "
                                                            + ex.getMessage());
                                                }
                                                return commit.previous();
                                            }).whenComplete((previous, cleanupFailure) ->
                                                    finishRestore(result, previous, cleanupFailure));
                                            return;
                                        }
                                        io.submit(() -> {
                                            rollbackRestoreFiles(commit);
                                            try { cleanupRestore(commit); }
                                            catch (IOException cleanupFailure) {
                                                publishFailure.addSuppressed(cleanupFailure);
                                                log(Level.WARNING, "Restore rolled back, but temporary cleanup failed: "
                                                        + cleanupFailure.getMessage());
                                            }
                                            return null;
                                        }).whenComplete((rolledBack, rollbackFailure) -> {
                                            if (rollbackFailure != null) {
                                                publishFailure.addSuppressed(rollbackFailure);
                                                log(Level.SEVERE, "Restore rollback needs manual recovery from "
                                                        + commit.recovery());
                                                finishRestore(result, null, publishFailure);
                                                return;
                                            }
                                            if (commit.oldSnapshot() == null) {
                                                // The caller's publisher must roll back its in-memory state on
                                                // failure. The original unreadable files were restored above.
                                                finishRestore(result, null, publishFailure);
                                            } else {
                                                onMain(() -> {
                                                    publish.apply(commit.oldSnapshot());
                                                    return null;
                                                }).whenComplete((reloadedOld, oldFailure) -> {
                                                    if (oldFailure != null) publishFailure.addSuppressed(oldFailure);
                                                    finishRestore(result, null, publishFailure);
                                                });
                                            }
                                        });
                                    });
                                });
                    });
                });
        return result;
    }

    private <T> CompletableFuture<T> onMain(Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            mainCallback.accept(() -> {
                try { result.complete(action.call()); }
                catch (Throwable ex) { result.completeExceptionally(ex); }
            });
        } catch (RuntimeException ex) {
            result.completeExceptionally(ex);
        }
        return result;
    }

    private void finishRestore(CompletableFuture<String> result, String previous, Throwable failure) {
        try {
            mainCallback.accept(() -> {
                restoreBusy.set(false);
                if (failure == null) result.complete(previous);
                else result.completeExceptionally(failure);
            });
        } catch (RuntimeException ex) {
            restoreBusy.set(false);
            if (failure != null) ex.addSuppressed(failure);
            result.completeExceptionally(ex);
        }
    }

    private String createBackup(boolean applyRetention, int retentionLimit,
                                BackupFormat selectedFormat) throws IOException {
        if (plugin instanceof RecipeWeaverPlugin recipeWeaver) recipeWeaver.flushIo();
        requireSafeBackupDirectory();
        Files.createDirectories(backupDirectory);
        requireSafeBackupDirectory();
        String name = "RecipeWeaver-backup-" + STAMP.format(Instant.now()) + "-"
                + UUID.randomUUID().toString().substring(0, 8)
                + (selectedFormat == BackupFormat.ZIP ? ".zip" : ".dir");
        Path destination = backupDirectory.resolve(name);
        Path temporary = selectedFormat == BackupFormat.ZIP
                ? Files.createTempFile(backupDirectory, ".rw-backup-", ".tmp")
                : Files.createTempDirectory(backupDirectory, ".rw-backup-");
        try {
            if (selectedFormat == BackupFormat.ZIP) {
                try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                    backupFiles((source, budget) -> writeEntry(zip, source, budget));
                }
            } else {
                backupFiles((source, budget) -> writeDirectoryEntry(temporary, source, budget));
            }
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, destination); }
            if (applyRetention) applyRetention(name, retentionLimit);
            try { listBackups(); }
            catch (IOException ex) {
                log(Level.WARNING, "Backup was saved, but the backup list could not be refreshed: "
                        + ex.getMessage());
            }
            return name;
        } finally {
            deleteTree(temporary);
        }
    }

    @FunctionalInterface
    private interface BackupWriter { void write(Path source, BackupBudget budget) throws IOException; }

    private void backupFiles(BackupWriter writer) throws IOException {
        BackupBudget budget = new BackupBudget();
        for (String target : TARGETS) {
            Path source = dataRoot.resolve(target);
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) continue;
            if (isUnsafeLink(source)) throw new IOException("Refusing symbolic link or junction: " + target);
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                for (Path path : safeWalk(source)) {
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        if (!path.equals(source)) throw new IOException("Unexpected nested data directory: " + path);
                    } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        writer.write(path, budget);
                    } else throw new IOException("Refusing non-file in " + target);
                }
            } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                writer.write(source, budget);
            } else throw new IOException("Refusing non-file: " + target);
        }
    }

    List<String> listBackups() throws IOException {
        if (plugin instanceof RecipeWeaverPlugin && Bukkit.isPrimaryThread()) {
            throw new IOException("Use asynchronous backup listing on the server thread");
        }
        requireSafeBackupDirectory();
        if (!Files.isDirectory(backupDirectory)) {
            cachedBackups = List.of();
            return cachedBackups;
        }
        try (var files = Files.list(backupDirectory)) {
            List<String> names = files.filter(BackupService::validBackupPath)
                    .map(p -> p.getFileName().toString())
                    .filter(BackupService::validName).sorted(Comparator.reverseOrder()).toList();
            cachedBackups = names;
            return names;
        }
    }

    CompletableFuture<List<String>> listBackupsAsync(AsyncIoService io) {
        return io.submitObservedAfterSuccessfulWrites(this::listBackups);
    }

    List<String> cachedBackups() { return cachedBackups; }

    CompletableFuture<Void> discardPreparedBackupAsync(String name, AsyncIoService io) {
        if (!validName(name) || !name.endsWith(".dir")) {
            return CompletableFuture.failedFuture(new IOException("Invalid prepared backup name"));
        }
        return io.submit(() -> {
            requireSafeBackupDirectory();
            Path candidate = backupDirectory.resolve(name);
            if (validBackupPath(candidate)) deleteTree(candidate);
            listBackups();
            return null;
        });
    }

    /** Returns the name of the automatic snapshot taken immediately before the restore. */
    String restore(String name) throws IOException {
        if (reload == null) throw new IOException(
                "Synchronous restore is unavailable; use restoreAsync");
        if (busy()) throw new IOException("Backup or restore in progress; retry when it finishes");
        if (plugin instanceof RecipeWeaverPlugin recipeWeaver && recipeWeaver.reloadInProgress()) {
            throw new IOException("Reload in progress; retry restore when it finishes");
        }
        if (!validName(name)) throw new IOException("Invalid backup name");
        requireSafeBackupDirectory();
        Path source = backupDirectory.resolve(name);
        if (!validBackupPath(source)) throw new IOException("Backup not found: " + name);
        Files.createDirectories(dataRoot);
        Path stage = Files.createTempDirectory(dataRoot, ".rw-restore-");
        try {
            stageBackup(source, stage);
            validate(stage);
            String previous = createBackup(false, retention.getAsInt(), format.get());
            Path recovery = Files.createTempDirectory(dataRoot, ".rw-recovery-");
            Set<String> movedOld = new HashSet<>();
            Set<String> installed = new HashSet<>();
            boolean keepRecovery = false;
            try {
                for (String target : TARGETS) {
                    Path live = dataRoot.resolve(target);
                    Path old = recovery.resolve(target);
                    Path replacement = stage.resolve(target);
                    if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
                        moveWithRetry(live, old);
                        movedOld.add(target);
                    }
                    if (Files.exists(replacement, LinkOption.NOFOLLOW_LINKS)) {
                        // Track partial copies too, so rollback removes them before restoring the old data.
                        installed.add(target);
                        installReplacement(replacement, live);
                    }
                }
                reload.reload();
                applyRetention(previous, retention.getAsInt());
                return previous;
            } catch (IOException | RuntimeException ex) {
                IOException failure = new IOException("Restore failed; previous files were preserved: " + ex, ex);
                try {
                    for (String target : TARGETS) {
                        if (installed.contains(target)) deleteTree(dataRoot.resolve(target));
                        if (movedOld.contains(target)) moveWithRetry(recovery.resolve(target), dataRoot.resolve(target));
                    }
                    reload.reload();
                } catch (IOException | RuntimeException rollbackFailure) {
                    keepRecovery = true;
                    failure.addSuppressed(rollbackFailure);
                    log(Level.SEVERE, "Restore rollback needs manual recovery from " + recovery);
                }
                throw failure;
            } finally {
                if (!keepRecovery) deleteTree(recovery);
            }
        } finally {
            deleteTree(stage);
        }
    }

    private record PreparedRestore(Path stage, DiskSnapshot snapshot) { }

    private record RestoreCommit(PreparedRestore prepared, Path recovery, String previous,
                                 DiskSnapshot oldSnapshot, Set<String> movedOld, Set<String> installed) { }

    private PreparedRestore prepareRestore(String name) throws IOException {
        requireSafeBackupDirectory();
        Path source = backupDirectory.resolve(name);
        if (!validBackupPath(source)) {
            throw new IOException("Backup not found: " + name);
        }
        Files.createDirectories(dataRoot);
        Path stage = Files.createTempDirectory(dataRoot, ".rw-restore-");
        try {
            stageBackup(source, stage);
            return new PreparedRestore(stage, DiskSnapshot.read(stage));
        } catch (IOException | RuntimeException ex) {
            try { deleteTree(stage); }
            catch (IOException cleanupFailure) { ex.addSuppressed(cleanupFailure); }
            throw ex;
        }
    }

    private RestoreCommit commitRestore(PreparedRestore prepared, int retentionLimit,
                                        BackupFormat selectedFormat, boolean unreadableLocal) throws IOException {
        RestoreCommit commit = null;
        try {
            String previous = createBackup(false, retentionLimit, selectedFormat);
            DiskSnapshot oldSnapshot;
            try { oldSnapshot = DiskSnapshot.read(dataRoot); }
            catch (IOException ex) {
                if (!unreadableLocal) throw ex;
                oldSnapshot = null;
            }
            Path recovery = Files.createTempDirectory(dataRoot, ".rw-recovery-");
            commit = new RestoreCommit(prepared, recovery, previous, oldSnapshot,
                    new HashSet<>(), new HashSet<>());
            for (String target : TARGETS) {
                Path live = dataRoot.resolve(target);
                Path old = recovery.resolve(target);
                Path replacement = prepared.stage().resolve(target);
                if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
                    moveWithRetry(live, old);
                    commit.movedOld().add(target);
                }
                if (Files.exists(replacement, LinkOption.NOFOLLOW_LINKS)) {
                    // Record partial copies so rollback removes them before restoring old data.
                    commit.installed().add(target);
                    installReplacement(replacement, live);
                }
            }
            return commit;
        } catch (IOException | RuntimeException ex) {
            try {
                if (commit == null) deleteTree(prepared.stage());
                else {
                    rollbackRestoreFiles(commit);
                    cleanupRestore(commit);
                }
            } catch (IOException rollbackFailure) {
                ex.addSuppressed(rollbackFailure);
                if (commit != null) log(Level.SEVERE,
                        "Restore rollback needs manual recovery from " + commit.recovery());
            }
            throw ex;
        }
    }

    private void rollbackRestoreFiles(RestoreCommit commit) throws IOException {
        for (String target : TARGETS) {
            if (commit.installed().contains(target)) deleteTree(dataRoot.resolve(target));
            if (commit.movedOld().contains(target)) {
                moveWithRetry(commit.recovery().resolve(target), dataRoot.resolve(target));
            }
        }
    }

    private static void cleanupRestore(RestoreCommit commit) throws IOException {
        deleteTree(commit.recovery());
        deleteTree(commit.prepared().stage());
    }

    static void moveWithRetry(Path source, Path destination) throws IOException {
        moveWithRetry(source, destination, (from, to) -> Files.move(from, to));
    }

    @FunctionalInterface
    interface MoveOperation {
        void move(Path source, Path destination) throws IOException;
    }

    static void moveWithRetry(Path source, Path destination, MoveOperation move) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                move.move(source, destination);
                return;
            } catch (AccessDeniedException ex) {
                if (attempt == 19) throw ex;
                try { Thread.sleep(50); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while moving restore data", interrupted);
                }
            }
        }
    }

    private static void installReplacement(Path replacement, Path live) throws IOException {
        if (!Files.isDirectory(replacement, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(replacement, live);
            return;
        }
        // Copying validated files avoids Windows denying a directory rename while a scanner
        // or another reader briefly has a handle inside the extracted staging directory.
        try (var paths = Files.walk(replacement)) {
            for (Path source : paths.toList()) {
                Path destination = live.resolve(replacement.relativize(source));
                if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(source, destination);
                } else throw new IOException("Unexpected staged restore entry: " + source);
            }
        }
    }

    private void applyRetention(String latest, int limit) {
        if (limit <= 0) return;
        try {
            List<String> names = new ArrayList<>(listBackups());
            Map<String, java.nio.file.attribute.FileTime> modified = new HashMap<>();
            for (String name : names) {
                modified.put(name, Files.getLastModifiedTime(
                        backupDirectory.resolve(name), LinkOption.NOFOLLOW_LINKS));
            }
            names.sort(Comparator.comparing((String name) -> modified.get(name))
                    .reversed().thenComparing(Comparator.reverseOrder()));
            Set<String> keep = new HashSet<>();
            if (names.contains(latest)) keep.add(latest);
            for (String name : names) {
                if (keep.size() >= limit) break;
                keep.add(name);
            }
            for (String name : names) {
                if (keep.contains(name)) continue;
                Path old = backupDirectory.resolve(name);
                if (validBackupPath(old)) deleteTree(old);
            }
            listBackups();
        } catch (IOException ex) {
            log(Level.WARNING, "Backup was saved, but retention cleanup failed: "
                    + ex.getMessage());
        }
    }

    private void validate(Path stage) throws IOException {
        validate(stage, DiskSnapshot.read(stage));
    }

    /** Decode a captured snapshot without replacing or publishing live data. */
    void validateSnapshot(DiskSnapshot snapshot) throws IOException {
        validate(dataRoot, snapshot);
    }

    private void validate(Path stage, DiskSnapshot snapshot) throws IOException {
        SettingsRepository settings = new SettingsRepository(stage);
        ItemRepository items = new ItemRepository(plugin, stage);
        RecipeRepository recipes = new RecipeRepository(plugin, stage);
        StationRecipeRepository stations = new StationRecipeRepository(plugin, stage);
        GridRecipeRepository grids = new GridRecipeRepository(plugin, stage);
        TableRepository tables = new TableRepository(stage);
        CauldronRepository cauldrons = new CauldronRepository(stage);
        DiscoveryRepository discoveries = new DiscoveryRepository(plugin, stage);
        settings.load(snapshot);
        items.load(snapshot);
        recipes.load(snapshot);
        stations.load(snapshot);
        grids.load(snapshot);
        tables.load(snapshot);
        cauldrons.load(snapshot);
        discoveries.load(snapshot);
        for (RecipeDefinition recipe : recipes.all()) {
            if (stations.get(recipe.key()).isPresent() || grids.get(recipe.key()).isPresent()) {
                throw new IOException("ID exists in two recipe sources: " + recipe.key());
            }
        }
        for (var recipe : stations.all()) {
            if (grids.get(recipe.key()).isPresent()) {
                throw new IOException("ID exists in two recipe sources: " + recipe.key());
            }
        }
    }

    private void writeEntry(ZipOutputStream zip, Path source, BackupBudget budget) throws IOException {
        String name = measureEntry(source, budget);
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(source, zip);
        zip.closeEntry();
    }

    private void writeDirectoryEntry(Path root, Path source, BackupBudget budget) throws IOException {
        String name = measureEntry(source, budget);
        Path destination = root.resolve(name).normalize();
        if (!destination.startsWith(root)) throw new IOException("Unsafe backup path: " + name);
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination);
    }

    private String measureEntry(Path source, BackupBudget budget) throws IOException {
        String name = dataRoot.relativize(source).toString().replace('\\', '/');
        if (!allowedEntry(name)) throw new IOException("Unexpected file in RecipeWeaver data: " + name);
        budget.bytes += Files.size(source);
        budget.entries++;
        if (budget.bytes > MAX_ARCHIVE_BYTES || budget.entries > MAX_ENTRIES) {
            throw new IOException("RecipeWeaver data exceeds backup limits");
        }
        return name;
    }

    private static final class BackupBudget {
        private long bytes;
        private int entries;
    }

    private static void stageBackup(Path source, Path stage) throws IOException {
        if (source.getFileName().toString().endsWith(".zip")) extract(source, stage);
        else copyDirectoryBackup(source, stage);
    }

    private static void copyDirectoryBackup(Path source, Path stage) throws IOException {
        long bytes = 0;
        int entries = 0;
        for (Path path : safeWalk(source)) {
            if (path.equals(source)) continue;
            String name = source.relativize(path).toString().replace('\\', '/');
            Path destination = stage.resolve(name).normalize();
            if (!destination.startsWith(stage)) throw new IOException("Unsafe backup path: " + name);
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                if (!List.of("recipes", "stations", "grid-recipes", "discoveries").contains(name)) {
                    throw new IOException("Unexpected backup directory: " + name);
                }
                Files.createDirectories(destination);
            } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                if (!allowedEntry(name) || ++entries > MAX_ENTRIES) {
                    throw new IOException("Unsafe backup entry: " + name);
                }
                bytes += Files.size(path);
                if (bytes > MAX_ARCHIVE_BYTES) throw new IOException("Backup exceeds size limit");
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination);
            } else throw new IOException("Unexpected backup entry: " + name);
        }
    }

    private static void extract(Path source, Path stage) throws IOException {
        Set<String> seen = new HashSet<>();
        long bytes = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(source))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    if (!name.equals("recipes/") && !name.equals("stations/")
                            && !name.equals("grid-recipes/")
                            && !name.equals("discoveries/")) {
                        throw new IOException("Unexpected backup directory: " + name);
                    }
                    zip.closeEntry();
                    continue;
                }
                if (!allowedEntry(name) || !seen.add(name) || seen.size() > MAX_ENTRIES) {
                    throw new IOException("Unsafe or duplicate backup entry: " + name);
                }
                Path destination = stage.resolve(name).normalize();
                if (!destination.startsWith(stage)) throw new IOException("Unsafe backup path: " + name);
                Files.createDirectories(destination.getParent());
                try (OutputStream output = Files.newOutputStream(destination)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        bytes += count;
                        if (bytes > MAX_ARCHIVE_BYTES) throw new IOException("Backup exceeds size limit");
                        output.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static boolean allowedEntry(String name) {
        return name.equals("settings.yml") || name.equals("items.yml")
                || name.equals("tables.yml") || name.equals("cauldrons.yml")
                || name.matches("(recipes|stations|grid-recipes)/[A-Za-z0-9_-]+\\.yml")
                || name.matches("discoveries/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\\.yml");
    }

    private void log(Level level, String message) {
        if (plugin instanceof RecipeWeaverPlugin recipeWeaver) recipeWeaver.logAsync(level, message);
        else plugin.getLogger().log(level, message);
    }

    private static boolean validName(String name) {
        return name != null && name.matches(
                "RecipeWeaver-backup-[0-9]{8}-[0-9]{6}-[0-9a-f]{8}\\.(zip|dir)");
    }

    private void requireSafeBackupDirectory() throws IOException {
        if (isUnsafeLink(backupDirectory)) {
            throw new IOException("Backup directory is a symbolic link or junction");
        }
    }

    private static boolean validBackupPath(Path path) {
        String name = path.getFileName().toString();
        if (!validName(name)) return false;
        try {
            if (isUnsafeLink(path)) return false;
        } catch (IOException ignored) {
            return false;
        }
        return name.endsWith(".zip")
                ? Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                : Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    static boolean isUnsafeLink(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        return attributes.isSymbolicLink() || attributes.isOther();
    }

    private static List<Path> safeWalk(Path root) throws IOException {
        List<Path> paths = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw new IOException("Unsafe directory link: " + directory);
                }
                paths.add(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw new IOException("Unsafe file link: " + file);
                }
                paths.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return paths;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        for (Path path : safeWalk(root).stream()
                .sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList()) {
            Files.deleteIfExists(path);
        }
    }
}
