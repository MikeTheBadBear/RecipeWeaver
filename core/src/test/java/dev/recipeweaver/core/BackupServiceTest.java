package dev.recipeweaver.core;

import dev.recipeweaver.api.BackupFormat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipFile;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import dev.recipeweaver.api.GridTier;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

final class BackupServiceTest {
    @TempDir Path directory;

    @Test
    void preparedBackupMoveRetriesTransientAccessDenial() throws Exception {
        Path source = directory.resolve("prepared.dir");
        Path destination = directory.resolve("saved.dir");
        Files.createDirectory(source);
        Files.writeString(source.resolve("items.yml"), "schema: 2\n");
        AtomicInteger attempts = new AtomicInteger();

        BackupService.moveWithRetry(source, destination, (from, to) -> {
            if (attempts.incrementAndGet() <= 2) throw new AccessDeniedException(from.toString());
            Files.move(from, to);
        });

        assertEquals(3, attempts.get());
        assertFalse(Files.exists(source));
        assertEquals("schema: 2\n", Files.readString(destination.resolve("items.yml")));
    }

    @Test
    void backupRestoreRoundTripsSettingsAndRetainsPreviousSnapshot() throws Exception {
        Plugin plugin = plugin();
        SettingsRepository settings = new SettingsRepository(plugin);
        settings.setLockdown(true);
        BackupService service = new BackupService(plugin, () -> { });
        String first = service.createBackup();
        settings.setLockdown(false);

        String beforeRestore = service.restore(first);
        assertTrue(service.listBackups().contains(first));
        assertTrue(service.listBackups().contains(beforeRestore));
        SettingsRepository restored = new SettingsRepository(plugin);
        restored.load();
        assertTrue(restored.lockdown());
    }

    @Test
    void invalidArchiveCannotReplaceLiveData() throws Exception {
        Plugin plugin = plugin();
        SettingsRepository settings = new SettingsRepository(plugin);
        settings.setLockdown(true);
        BackupService service = new BackupService(plugin, () -> { });
        Path backups = directory.resolve("backups");
        Files.createDirectories(backups);
        String name = "RecipeWeaver-backup-20260925-120000-abcdef12.zip";
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(backups.resolve(name)))) {
            zip.putNextEntry(new ZipEntry("../settings.yml"));
            zip.write("schema: 1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> service.restore(name));
        SettingsRepository unchanged = new SettingsRepository(plugin);
        unchanged.load();
        assertTrue(unchanged.lockdown());
    }

    @Test
    void failedReloadRollsBackFiles() throws Exception {
        Plugin plugin = plugin();
        SettingsRepository settings = new SettingsRepository(plugin);
        settings.setLockdown(true);
        AtomicInteger reloads = new AtomicInteger();
        BackupService service = new BackupService(plugin, () -> {
            if (reloads.incrementAndGet() == 1) throw new IOException("simulated reload failure");
        });
        String old = service.createBackup();
        settings.setLockdown(false);
        assertThrows(IOException.class, () -> service.restore(old));
        SettingsRepository unchanged = new SettingsRepository(plugin);
        unchanged.load();
        assertFalse(unchanged.lockdown());
        assertEquals(2, reloads.get());
    }

    @Test
    void asyncBackupIsOrderedAndRejectsConcurrentMutations() throws Exception {
        Plugin plugin = plugin();
        Files.writeString(directory.resolve("settings.yml"), "schema: 1\\nmarker: initial\\n");
        BackupService service = new BackupService(plugin, () -> { });
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        try (AsyncIoService io = new AsyncIoService(plugin.getLogger())) {
            io.submit(() -> {
                workerEntered.countDown();
                if (!releaseWorker.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting for backup test");
                }
                return null;
            });
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> earlierWrite = io.submit(() -> {
                Files.writeString(directory.resolve("settings.yml"), "schema: 1\\nmarker: before\\n");
                return null;
            });
            AtomicReference<String> completedOn = new AtomicReference<>();
            CompletableFuture<String> backup = service.createBackupAsync(io);
            CompletableFuture<String> observed = backup.whenComplete((name, failure) ->
                    completedOn.set(Thread.currentThread().getName()));
            CompletableFuture<Void> laterWrite = io.submit(() -> {
                Files.writeString(directory.resolve("settings.yml"), "schema: 1\\nmarker: after\\n");
                return null;
            });

            assertTrue(service.busy());
            assertFalse(backup.isDone());
            assertThrows(CompletionException.class, () -> service.createBackupAsync(io).join());
            World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                    new Class<?>[] {World.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getUID")) return UUID.randomUUID();
                        throw new UnsupportedOperationException(method.getName());
                    });
            Location location = new Location(world, 2, 64, 3);
            TableRepository tables = new TableRepository(directory, service::busy);
            CauldronRepository cauldrons = new CauldronRepository(directory, service::busy);
            assertThrows(IOException.class, () -> tables.put(location, GridTier.ADVANCED));
            assertThrows(IOException.class, () -> cauldrons.put(location, new ItemStack(Material.DIRT)));

            releaseWorker.countDown();
            earlierWrite.get(10, TimeUnit.SECONDS);
            String name = observed.get(10, TimeUnit.SECONDS);
            laterWrite.get(10, TimeUnit.SECONDS);
            assertFalse(service.busy());
            assertEquals("RecipeWeaver-IO", completedOn.get());
            try (ZipFile zip = new ZipFile(directory.resolve("backups").resolve(name).toFile())) {
                String archived = new String(zip.getInputStream(zip.getEntry("settings.yml")).readAllBytes());
                assertTrue(archived.contains("marker: before"));
            }
            assertTrue(Files.readString(directory.resolve("settings.yml")).contains("marker: after"));
        } finally {
            releaseWorker.countDown();
        }
    }

    @Test
    void asyncBackupListingRunsOnWorkerAndRefreshesTabCache() throws Exception {
        Plugin plugin = plugin();
        BackupService service = new BackupService(plugin, () -> { });
        String first = service.createBackup();
        String external = "RecipeWeaver-backup-20260925-120000-abcdef12.zip";
        Files.copy(directory.resolve("backups").resolve(first),
                directory.resolve("backups").resolve(external));
        assertFalse(service.cachedBackups().contains(external));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (AsyncIoService io = new AsyncIoService(plugin.getLogger())) {
            io.submit(() -> {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting for list test");
                }
                return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AtomicReference<String> completedOn = new AtomicReference<>();
            CompletableFuture<List<String>> listed = service.listBackupsAsync(io);
            CompletableFuture<List<String>> observed = listed.whenComplete(
                    (names, failure) -> completedOn.set(Thread.currentThread().getName()));
            assertFalse(listed.isDone());
            release.countDown();
            List<String> names = observed.get(10, TimeUnit.SECONDS);
            assertTrue(names.contains(first) && names.contains(external));
            assertEquals(names, service.cachedBackups());
            assertEquals("RecipeWeaver-IO", completedOn.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void asyncRestoreWaitsForEarlierIoAndPublishesOnMainCallback() throws Exception {
        Plugin plugin = plugin();
        SettingsRepository settings = new SettingsRepository(directory);
        settings.setLockdown(true);
        String source = new BackupService(plugin, () -> { }).createBackup();
        settings.setLockdown(false);
        BackupService service;
        ExecutorService main = Executors.newSingleThreadExecutor(task ->
                new Thread(task, "RecipeWeaver-Test-Main"));
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        try (AsyncIoService io = new AsyncIoService(plugin.getLogger())) {
            service = new BackupService(plugin, () -> { }, () -> 0, main::execute);
            io.submit(() -> {
                workerEntered.countDown();
                if (!releaseWorker.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting for restore test");
                }
                return null;
            });
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
            AtomicReference<String> publishedOn = new AtomicReference<>();
            CompletableFuture<String> restored = service.restoreAsync(source, io, snapshot -> {
                publishedOn.set(Thread.currentThread().getName());
                settings.load(snapshot);
            });
            assertFalse(restored.isDone());
            assertTrue(service.restoring());
            assertThrows(CompletionException.class, () -> service.createBackupAsync(io).join());

            releaseWorker.countDown();
            String recovery = restored.get(15, TimeUnit.SECONDS);
            assertEquals("RecipeWeaver-Test-Main", publishedOn.get());
            assertTrue(settings.lockdown());
            assertFalse(service.busy());
            assertTrue(service.listBackups().contains(source));
            assertTrue(service.listBackups().contains(recovery));
            try (ZipFile zip = new ZipFile(directory.resolve("backups").resolve(recovery).toFile())) {
                String yaml = new String(zip.getInputStream(zip.getEntry("settings.yml")).readAllBytes());
                assertTrue(yaml.contains("lockdown: false"));
            }
        } finally {
            releaseWorker.countDown();
            main.shutdown();
            assertTrue(main.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void asyncRestoreRollsBackDiskAndPublishedStateAfterPublishFailure() throws Exception {
        Plugin plugin = plugin();
        SettingsRepository settings = new SettingsRepository(directory);
        settings.setLockdown(true);
        String source = new BackupService(plugin, () -> { }).createBackup();
        settings.setLockdown(false);
        ExecutorService main = Executors.newSingleThreadExecutor(task ->
                new Thread(task, "RecipeWeaver-Test-Main"));
        try (AsyncIoService io = new AsyncIoService(plugin.getLogger())) {
            BackupService service = new BackupService(plugin, () -> { }, () -> 0, main::execute);
            AtomicInteger publications = new AtomicInteger();
            CompletableFuture<String> restored = service.restoreAsync(source, io, snapshot -> {
                if (publications.getAndIncrement() == 0) {
                    throw new IOException("Simulated publication failure");
                }
                settings.load(snapshot);
            });
            assertThrows(CompletionException.class, restored::join);
            assertEquals(2, publications.get());
            assertFalse(settings.lockdown());
            assertFalse(service.busy());
            SettingsRepository fromDisk = new SettingsRepository(directory);
            fromDisk.load();
            assertFalse(fromDisk.lockdown());
            assertEquals(2, service.listBackups().size());
        } finally {
            main.shutdown();
            assertTrue(main.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void invalidPreparedSourceLeavesLocalDataUntouchedAndCanBeDiscarded() throws Exception {
        Plugin plugin = plugin();
        SettingsRepository settings = new SettingsRepository(directory);
        settings.setLockdown(false);
        Path items = directory.resolve("items.yml");
        Files.writeString(items, "schema: 999\nitems: {}\n");
        ExecutorService main = Executors.newSingleThreadExecutor(task ->
                new Thread(task, "RecipeWeaver-Test-Main"));
        try (AsyncIoService io = new AsyncIoService(plugin.getLogger())) {
            BackupService service = new BackupService(plugin, () -> { }, () -> 0,
                    () -> BackupFormat.DIRECTORY, main::execute);
            String invalid = service.createBackup();
            Files.writeString(items, "schema: 1\nitems: {}\n");
            CompletableFuture<String> attempted = service.restoreAsync(invalid, io,
                    snapshot -> { throw new AssertionError("Invalid source must not publish"); });
            CompletionException failure = assertThrows(CompletionException.class, attempted::join);
            assertTrue(failure.getCause() instanceof BackupService.InvalidSourceException);
            assertEquals("schema: 1\nitems: {}\n", Files.readString(items));
            assertFalse(service.busy());
            service.discardPreparedBackupAsync(invalid, io).join();
            assertFalse(Files.exists(directory.resolve("backups").resolve(invalid)));
        } finally {
            main.shutdown();
            assertTrue(main.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void backupListingObservesPriorWriteFailureWithoutPoisoningNextAttempt() throws Exception {
        Plugin plugin = plugin();
        BackupService service = new BackupService(plugin, () -> { });
        try (AsyncIoService io = new AsyncIoService(plugin.getLogger())) {
            CompletableFuture<Void> failedWrite = io.submit(() -> {
                throw new IOException("prior local save failed");
            });
            CompletableFuture<List<String>> listed = service.listBackupsAsync(io);
            assertThrows(CompletionException.class, failedWrite::join);
            CompletionException failure = assertThrows(CompletionException.class, listed::join);
            assertEquals("prior local save failed", failure.getCause().getMessage());
            assertTrue(service.listBackupsAsync(io).join().isEmpty());
            io.flush().join();
        }
    }

    @Test
    void linkedBackupRootCannotRedirectCreationRestoreOrCleanup() throws Exception {
        Path dataRoot = directory.resolve("plugin");
        Path outside = directory.resolve("outside");
        Files.createDirectories(dataRoot);
        Files.createDirectories(outside);
        Path backupRoot = dataRoot.resolve("backups");
        createDirectoryRedirect(backupRoot, outside);

        BackupService service = new BackupService(plugin(dataRoot), () -> { });
        String name = "RecipeWeaver-backup-20260925-120000-abcdef12.dir";
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("backup-root-link-test"))) {
            CompletionException creation = assertThrows(CompletionException.class,
                    () -> service.createBackupAsync(io).join());
            assertTrue(creation.getCause().getMessage().contains("symbolic link"));
            try (var files = Files.list(outside)) { assertTrue(files.findAny().isEmpty()); }
            assertThrows(IOException.class, service::listBackups);
            assertThrows(CompletionException.class, () -> service.listBackupsAsync(io).join());
            assertThrows(IOException.class, () -> service.restore(name));

            Path externalBackup = Files.createDirectory(outside.resolve(name));
            CompletionException cleanup = assertThrows(CompletionException.class,
                    () -> service.discardPreparedBackupAsync(name, io).join());
            assertTrue(cleanup.getCause().getMessage().contains("symbolic link"));
            assertTrue(Files.isDirectory(externalBackup));
            assertThrows(CompletionException.class, () -> io.flush().join());
        }
    }

    @Test
    void junctionBackupEntryIsNotListedRestoredOrDiscarded() throws Exception {
        Path dataRoot = directory.resolve("plugin");
        Path backups = Files.createDirectories(dataRoot.resolve("backups"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("items.yml"), "external data");
        String name = "RecipeWeaver-backup-20260925-120000-abcdef12.dir";
        createDirectoryRedirect(backups.resolve(name), outside);

        BackupService service = new BackupService(plugin(dataRoot), () -> { });
        assertTrue(service.listBackups().isEmpty());
        IOException rejected = assertThrows(IOException.class, () -> service.restore(name));
        assertTrue(rejected.getMessage().contains("Backup not found"));
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("backup-entry-link-test"))) {
            service.discardPreparedBackupAsync(name, io).join();
        }
        assertEquals("external data", Files.readString(sentinel));
    }

    @Test
    void nestedJunctionCannotBeStagedOrDeletedWithDirectoryBackup() throws Exception {
        Path dataRoot = directory.resolve("plugin");
        Path backups = Files.createDirectories(dataRoot.resolve("backups"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("external.yml"), "external data");
        String name = "RecipeWeaver-backup-20260925-120000-abcdef12.dir";
        Path entry = Files.createDirectory(backups.resolve(name));
        createDirectoryRedirect(entry.resolve("recipes"), outside);

        BackupService service = new BackupService(plugin(dataRoot), () -> { });
        assertTrue(service.listBackups().contains(name));
        IOException rejected = assertThrows(IOException.class, () -> service.restore(name));
        assertTrue(rejected.getMessage().contains("Unsafe directory link"));
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("backup-nested-link-test"))) {
            CompletionException deletion = assertThrows(CompletionException.class,
                    () -> service.discardPreparedBackupAsync(name, io).join());
            assertTrue(deletion.getCause().getMessage().contains("Unsafe directory link"));
            assertThrows(CompletionException.class, () -> io.flush().join());
        }
        assertEquals("external data", Files.readString(sentinel));
        assertTrue(Files.isDirectory(entry));
    }

    @Test
    void junctionDataTargetCannotBeArchived() throws Exception {
        Path dataRoot = Files.createDirectory(directory.resolve("plugin"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("external.yml"), "external data");
        createDirectoryRedirect(dataRoot.resolve("recipes"), outside);

        BackupService service = new BackupService(plugin(dataRoot), () -> { });
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("backup-source-link-test"))) {
            CompletionException rejected = assertThrows(CompletionException.class,
                    () -> service.createBackupAsync(io).join());
            assertTrue(rejected.getCause().getMessage().contains("junction"));
        }
        assertEquals("external data", Files.readString(sentinel));
    }

    @Test
    void restoreStagesItsSelectedBackupBeforeLaterOrderedDeletion() throws Exception {
        Plugin plugin = plugin();
        SettingsRepository settings = new SettingsRepository(directory);
        settings.setLockdown(true);
        String source = new BackupService(plugin, () -> { }).createBackup();
        settings.setLockdown(false);
        ExecutorService main = Executors.newSingleThreadExecutor(task ->
                new Thread(task, "RecipeWeaver-Test-Main"));
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        try (AsyncIoService io = new AsyncIoService(plugin.getLogger())) {
            BackupService service = new BackupService(plugin, () -> { }, () -> 0, main::execute);
            io.submit(() -> {
                workerEntered.countDown();
                if (!releaseWorker.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting for ordered restore");
                }
                return null;
            });
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
            AtomicReference<String> publishedOn = new AtomicReference<>();
            CompletableFuture<String> restored = service.restoreAsync(source, io, snapshot -> {
                publishedOn.set(Thread.currentThread().getName());
                settings.load(snapshot);
            });
            CompletableFuture<Void> laterDelete = io.submit(() -> {
                Files.delete(directory.resolve("backups").resolve(source));
                return null;
            });
            assertFalse(restored.isDone());
            releaseWorker.countDown();
            String previous = restored.get(15, TimeUnit.SECONDS);
            laterDelete.get(15, TimeUnit.SECONDS);
            assertEquals("RecipeWeaver-Test-Main", publishedOn.get());
            assertTrue(settings.lockdown());
            assertFalse(Files.exists(directory.resolve("backups").resolve(source)));
            assertTrue(Files.exists(directory.resolve("backups").resolve(previous)));
            assertFalse(service.busy());
        } finally {
            releaseWorker.countDown();
            main.shutdown();
            assertTrue(main.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedRestorePreparationDoesNotPoisonLaterIoButStillWaitsForPriorWrites() throws Exception {
        Plugin plugin = plugin();
        Files.writeString(directory.resolve("settings.yml"), "schema: 1\nmarker: durable\n");
        ExecutorService main = Executors.newSingleThreadExecutor(task ->
                new Thread(task, "RecipeWeaver-Test-Main"));
        try (AsyncIoService io = new AsyncIoService(plugin.getLogger())) {
            BackupService service = new BackupService(plugin, () -> { }, () -> 0, main::execute);
            String missing = "RecipeWeaver-backup-20260925-120000-abcdef12.zip";
            assertThrows(CompletionException.class, () -> io.submit(() -> {
                throw new IOException("prior save failed");
            }).join());

            CompletionException blocked = assertThrows(CompletionException.class,
                    () -> service.restoreAsync(missing, io, snapshot -> { }).join());
            assertEquals("prior save failed", blocked.getCause().getMessage());
            assertFalse(service.busy());

            CompletionException missingSource = assertThrows(CompletionException.class,
                    () -> service.restoreAsync(missing, io, snapshot -> { }).join());
            assertTrue(missingSource.getCause().getMessage().contains("Backup not found"));
            assertFalse(service.busy());
            io.flush().join();

            String saved = service.createBackupAsync(io).get(10, TimeUnit.SECONDS);
            try (ZipFile zip = new ZipFile(directory.resolve("backups").resolve(saved).toFile())) {
                assertTrue(new String(zip.getInputStream(zip.getEntry("settings.yml")).readAllBytes())
                        .contains("marker: durable"));
            }
        } finally {
            main.shutdown();
            assertTrue(main.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void asyncBackupRejectsEarlierFailedSaveBeforeArchivingAndCanRetry() throws Exception {
        Plugin plugin = plugin();
        Files.writeString(directory.resolve("settings.yml"), "schema: 1\nmarker: durable\n");
        BackupService service = new BackupService(plugin, () -> { });
        try (AsyncIoService io = new AsyncIoService(plugin.getLogger())) {
            CompletableFuture<Void> failedSave = io.submit(() -> {
                throw new IOException("simulated earlier save failure");
            });
            assertThrows(CompletionException.class, failedSave::join);

            CompletableFuture<String> first = service.createBackupAsync(io);
            CompletionException failure = assertThrows(CompletionException.class, first::join);
            assertEquals("simulated earlier save failure", failure.getCause().getMessage());
            assertFalse(service.busy());
            assertFalse(Files.exists(directory.resolve("backups")));

            String name = service.createBackupAsync(io).get(10, TimeUnit.SECONDS);
            assertFalse(service.busy());
            try (ZipFile zip = new ZipFile(directory.resolve("backups").resolve(name).toFile())) {
                String archived = new String(zip.getInputStream(zip.getEntry("settings.yml")).readAllBytes());
                assertTrue(archived.contains("marker: durable"));
            }
        }
    }

    @Test
    void failedArchiveCreationDoesNotPoisonLaterIoOrPreventRetry() throws Exception {
        Plugin plugin = plugin();
        Files.writeString(directory.resolve("settings.yml"), "schema: 1\nmarker: durable\n");
        Path nested = directory.resolve("recipes").resolve("nested");
        Files.createDirectories(nested);
        Path unsupported = nested.resolve("unexpected.yml");
        Files.writeString(unsupported, "schema: 1\n");
        BackupService service = new BackupService(plugin, () -> { });
        try (AsyncIoService io = new AsyncIoService(plugin.getLogger())) {
            CompletionException failed = assertThrows(CompletionException.class,
                    () -> service.createBackupAsync(io).join());
            assertTrue(failed.getCause().getMessage().contains("Unexpected nested data directory"));
            assertFalse(service.busy());
            assertTrue(service.listBackups().isEmpty());
            io.flush().join();

            Files.delete(unsupported);
            Files.delete(nested);
            String saved = service.createBackupAsync(io).get(10, TimeUnit.SECONDS);
            try (ZipFile zip = new ZipFile(directory.resolve("backups").resolve(saved).toFile())) {
                assertTrue(new String(zip.getInputStream(zip.getEntry("settings.yml")).readAllBytes())
                        .contains("marker: durable"));
            }
        }
    }

    @Test
    void sqlRecoveryPreservesUnreadableLocalBytesInRecoveryArchive() throws Exception {
        Plugin plugin = plugin();
        SettingsRepository settings = new SettingsRepository(directory);
        settings.setLockdown(true);
        String source = new BackupService(plugin, () -> { }).createBackup();
        byte[] corrupt = {(byte) 0xc3, (byte) 0x28};
        Files.write(directory.resolve("settings.yml"), corrupt);
        assertThrows(IOException.class, () -> DiskSnapshot.read(directory));
        ExecutorService main = Executors.newSingleThreadExecutor(task ->
                new Thread(task, "RecipeWeaver-Test-Main"));
        try (AsyncIoService io = new AsyncIoService(plugin.getLogger())) {
            BackupService service = new BackupService(plugin, () -> { }, () -> 0, main::execute);
            String recovery = service.restoreUnreadableLocalAsync(source, io, settings::load)
                    .get(15, TimeUnit.SECONDS);
            assertTrue(settings.lockdown());
            assertTrue(Files.readString(directory.resolve("settings.yml")).contains("lockdown: true"));
            assertTrue(service.listBackups().contains(recovery));
            try (ZipFile zip = new ZipFile(directory.resolve("backups").resolve(recovery).toFile())) {
                assertArrayEquals(corrupt, zip.getInputStream(zip.getEntry("settings.yml")).readAllBytes());
            }
        } finally {
            main.shutdown();
            assertTrue(main.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedSqlRecoveryRestoresUnreadableLocalBytes() throws Exception {
        Plugin plugin = plugin();
        SettingsRepository settings = new SettingsRepository(directory);
        settings.setLockdown(true);
        String source = new BackupService(plugin, () -> { }).createBackup();
        byte[] corrupt = {(byte) 0xc3, (byte) 0x28};
        Files.write(directory.resolve("settings.yml"), corrupt);
        ExecutorService main = Executors.newSingleThreadExecutor(task ->
                new Thread(task, "RecipeWeaver-Test-Main"));
        try (AsyncIoService io = new AsyncIoService(plugin.getLogger())) {
            BackupService service = new BackupService(plugin, () -> { }, () -> 0, main::execute);
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> service.restoreUnreadableLocalAsync(source, io,
                            snapshot -> { throw new IOException("Publication failed"); }).join());
            assertTrue(failure.getCause().getMessage().contains("Publication failed"));
            assertArrayEquals(corrupt, Files.readAllBytes(directory.resolve("settings.yml")));
            assertFalse(service.busy());
            assertEquals(2, service.listBackups().size());
        } finally {
            main.shutdown();
            assertTrue(main.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void retentionPrunesOldArchivesButKeepsTheNewBackup() throws Exception {
        Plugin plugin = plugin();
        SettingsRepository settings = new SettingsRepository(plugin);
        settings.setBackupRetention(2);
        BackupService service = new BackupService(plugin, () -> { }, settings::backupRetention);
        String first = service.createBackup();
        String second = service.createBackup();
        String third = service.createBackup();
        Path unrelated = directory.resolve("backups").resolve("admin-notes.txt");
        Files.writeString(unrelated, "keep");
        String fourth = service.createBackup();
        assertEquals(2, service.listBackups().size());
        assertTrue(service.listBackups().contains(fourth));
        assertFalse(service.listBackups().contains(first)
                && service.listBackups().contains(second)
                && service.listBackups().contains(third));
        assertTrue(Files.isRegularFile(unrelated));
        assertTrue(service.listBackups().stream().anyMatch(name ->
                name.equals(first) || name.equals(second) || name.equals(third)));
    }

    @Test
    void retentionKeepsThePreRestoreRecoverySnapshot() throws Exception {
        Plugin plugin = plugin();
        SettingsRepository settings = new SettingsRepository(plugin);
        settings.setBackupRetention(1);
        settings.setLockdown(true);
        BackupService service = new BackupService(plugin, settings::load, settings::backupRetention);
        String source = service.createBackup();
        settings.setLockdown(false);
        String recovery = service.restore(source);
        assertEquals(1, service.listBackups().size());
        assertTrue(service.listBackups().contains(recovery));
        SettingsRepository restored = new SettingsRepository(plugin);
        restored.load();
        assertTrue(restored.lockdown());
        assertEquals(1, restored.backupRetention());
    }

    @Test
    void retentionSettingSurvivesLockdownChanges() throws Exception {
        SettingsRepository settings = new SettingsRepository(plugin());
        settings.setBackupRetention(7);
        settings.setLockdown(true);
        SettingsRepository reloaded = new SettingsRepository(plugin());
        reloaded.load();
        assertEquals(7, reloaded.backupRetention());
        assertTrue(reloaded.lockdown());
        assertThrows(IllegalArgumentException.class, () -> reloaded.setBackupRetention(-1));
    }

    @Test
    void oldSettingsWithoutRetentionRemainUnlimited() throws Exception {
        Files.writeString(directory.resolve("settings.yml"), "schema: 1\nlockdown: true\n");
        SettingsRepository settings = new SettingsRepository(plugin());
        settings.load();
        assertTrue(settings.lockdown());
        assertEquals(0, settings.backupRetention());
        assertEquals(BackupFormat.ZIP, settings.backupFormat());
    }

    private Plugin plugin() {
        return plugin(directory);
    }

    private static void createDirectoryRedirect(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            if (!System.getProperty("os.name", "").startsWith("Windows")) {
                Assumptions.assumeTrue(false, "Directory symlinks are unavailable: " + unavailable);
            }
            String command = "New-Item -ItemType Junction -Path '"
                    + link.toString().replace("'", "''") + "' -Target '"
                    + target.toString().replace("'", "''") + "' | Out-Null";
            Process junction = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-Command", command).redirectErrorStream(true).start();
            String output = new String(junction.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Assumptions.assumeTrue(junction.waitFor() == 0,
                    "Directory symlink and junction creation are unavailable: " + output);
        }
    }

    private Plugin plugin(Path dataRoot) {
        File root = dataRoot.toFile();
        Logger logger = Logger.getLogger("RecipeWeaverBackupTest");
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getDataFolder" -> root;
                    case "getName" -> "RecipeWeaver";
                    case "getLogger" -> logger;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
