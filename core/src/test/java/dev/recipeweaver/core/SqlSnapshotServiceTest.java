package dev.recipeweaver.core;

import dev.recipeweaver.api.SqlMirrorMode;
import dev.recipeweaver.api.SqlPreparedSource;
import dev.recipeweaver.api.SqlSourceStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlSnapshotServiceTest {
    @TempDir Path directory;

    @Test
    void localSqlExportsRejectJunctionSnapshotDirectory() throws Exception {
        Path root = Files.createDirectory(directory.resolve("plugin"));
        Files.writeString(root.resolve("settings.yml"), "source: local\n");
        Path outside = Files.createDirectory(directory.resolve("outside"));
        createDirectoryRedirect(root.resolve("sql-snapshots"), outside);
        SqlSnapshotService snapshots = new SqlSnapshotService(root,
                new BackupService(plugin(root), () -> { }));

        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("SQL-directory-junction-test"))) {
            for (SqlDocumentStore.Backend backend : new SqlDocumentStore.Backend[] {
                    SqlDocumentStore.Backend.SQLITE, SqlDocumentStore.Backend.H2}) {
                ExecutionException rejected = assertThrows(ExecutionException.class,
                        () -> snapshots.exportAsync(backend, io).get(15, TimeUnit.SECONDS));
                assertTrue(rejected.getCause().getMessage().contains("junction"));
            }
            io.flush().get(15, TimeUnit.SECONDS);
        }
        try (var files = Files.list(outside)) { assertTrue(files.findAny().isEmpty()); }
    }

    @Test
    void preparedSqlRestoreRejectsJunctionBackupDirectory() throws Exception {
        Path root = Files.createDirectory(directory.resolve("plugin"));
        SqlSnapshotService.localStore(root, SqlDocumentStore.Backend.SQLITE)
                .put("settings.yml", "source: sql\n");
        Path outside = Files.createDirectory(directory.resolve("outside"));
        createDirectoryRedirect(root.resolve("backups"), outside);
        SqlSnapshotService snapshots = new SqlSnapshotService(root,
                new BackupService(plugin(root), () -> { }));

        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("SQL-backup-junction-test"))) {
            ExecutionException rejected = assertThrows(ExecutionException.class,
                    () -> snapshots.prepareRestoreAsync(SqlDocumentStore.Backend.SQLITE, io)
                            .get(15, TimeUnit.SECONDS));
            assertTrue(rejected.getCause().getMessage().contains("junction"));
            io.flush().get(15, TimeUnit.SECONDS);
        }
        try (var files = Files.list(outside)) { assertTrue(files.findAny().isEmpty()); }
    }

    @Test
    void sqliteAndH2ExportThenRestoreThroughNormalBackupValidation() throws Exception {
        for (SqlDocumentStore.Backend backend : new SqlDocumentStore.Backend[] {
                SqlDocumentStore.Backend.SQLITE, SqlDocumentStore.Backend.H2}) {
            Path root = directory.resolve(backend.name());
            SettingsRepository settings = new SettingsRepository(root);
            settings.setLockdown(true);
            settings.setBackupRetention(7);
            BackupService backups = new BackupService(plugin(root), settings::load);
            SqlSnapshotService snapshots = new SqlSnapshotService(root, backups);
            try (AsyncIoService io = new AsyncIoService(Logger.getLogger("SQL-snapshot-test"))) {
                assertEquals(1, snapshots.exportAsync(backend, io).get(15, TimeUnit.SECONDS));
                settings.setLockdown(false);
                String prepared = snapshots.prepareRestoreAsync(backend, io).get(15, TimeUnit.SECONDS);
                assertTrue(prepared.endsWith(".dir"));
                assertTrue(Files.isRegularFile(root.resolve("backups").resolve(prepared)
                        .resolve("settings.yml")));
                assertTrue(backups.listBackups().contains(prepared));
                assertFalse(snapshots.busy());

                String previous = backups.restore(prepared);
                assertTrue(previous.endsWith(".zip"));
                assertTrue(settings.lockdown());
                assertEquals(7, settings.backupRetention());
            }
        }
    }

    @Test
    void importRejectsSqlDocumentThatCannotBecomeAValidBackupEntry() throws Exception {
        Path root = directory.resolve("invalid");
        Files.createDirectories(root.resolve("sql-snapshots"));
        String url = "jdbc:sqlite:" + root.resolve("sql-snapshots/recipeweaver.sqlite");
        SqlDocumentStore store = new SqlDocumentStore(SqlDocumentStore.Backend.SQLITE,
                url, null, null);
        store.put("settings.yml", "schema: 9\nlockdown: false\n");
        assertThrows(IOException.class,
                () -> store.put("recipes/bad.name.yml", "schema: 1\n"));
        injectInvalidSqlitePath(url, "recipes/bad.name.yml", "schema: 1\n");
        BackupService backups = new BackupService(plugin(root), () -> { });
        SqlSnapshotService snapshots = new SqlSnapshotService(root, backups);
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("SQL-snapshot-test"))) {
            SqlSourceStatus status = snapshots.auditAsync(List.of(SqlMirrorMode.SQLITE),
                    RemoteSqlProfile.defaults(), io).get(15, TimeUnit.SECONDS).get(0);
            assertTrue(status.failure().contains("read failed"));
            assertEquals(null, status.sha256());
            ExecutionException failure = assertThrows(ExecutionException.class, () ->
                    snapshots.prepareRestoreAsync(SqlDocumentStore.Backend.SQLITE, io)
                            .get(15, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IOException);
            assertTrue(failure.getCause().getMessage().contains("Invalid SQL document path"));
            assertFalse(snapshots.busy());
            io.flush().get(15, TimeUnit.SECONDS);
            store.replaceSnapshot(DiskSnapshot.fromDocuments(Map.of(
                    "settings.yml", "schema: 9\nlockdown: false\n")));
            String recovered = snapshots.prepareRestoreAsync(SqlDocumentStore.Backend.SQLITE, io)
                    .get(15, TimeUnit.SECONDS);
            assertTrue(backups.listBackups().contains(recovered));
        }
    }

    @Test
    void auditSelectedSourcesReportsCurrentChangedMissingAndExtraDocuments() throws Exception {
        Path root = directory.resolve("audit");
        Files.createDirectories(root);
        Files.writeString(root.resolve("settings.yml"), "schema: 6\nlockdown: false\n");
        Files.writeString(root.resolve("items.yml"), "items: first\n");
        SqlDocumentStore sqlite = SqlSnapshotService.localStore(root,
                SqlDocumentStore.Backend.SQLITE);
        SqlDocumentStore h2 = SqlSnapshotService.localStore(root, SqlDocumentStore.Backend.H2);
        sqlite.replaceSnapshot(DiskSnapshot.read(root));
        h2.replaceSnapshot(DiskSnapshot.read(root));
        SqlSnapshotService service = new SqlSnapshotService(root,
                new BackupService(plugin(root), () -> { }));
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("SQL-audit-test"), root)) {
            List<SqlMirrorMode> modes = List.of(SqlMirrorMode.H2, SqlMirrorMode.SQLITE);
            List<SqlSourceStatus> current = service.auditAsync(modes,
                    RemoteSqlProfile.defaults(), io).get(15, TimeUnit.SECONDS);
            assertEquals(modes, current.stream().map(SqlSourceStatus::backend).toList());
            assertTrue(current.stream().allMatch(SqlSourceStatus::current));
            assertEquals(64, current.get(0).sha256().length());
            assertEquals(current.get(0).sha256(), current.get(1).sha256());

            Files.writeString(root.resolve("items.yml"), "items: second\n");
            Files.createDirectories(root.resolve("recipes"));
            Files.writeString(root.resolve("recipes/new.yml"), "schema: 1\n");
            h2.put("recipes/extra.yml", "schema: 1\n");
            List<SqlSourceStatus> drift = service.auditAsync(modes,
                    RemoteSqlProfile.defaults(), io).get(15, TimeUnit.SECONDS);
            assertEquals(1, drift.get(0).missing());
            assertEquals(1, drift.get(0).changed());
            assertEquals(1, drift.get(0).extra());
            assertEquals(1, drift.get(1).missing());
            assertEquals(1, drift.get(1).changed());
            assertEquals(0, drift.get(1).extra());
            assertFalse(drift.get(0).current());
        }
    }

    @Test
    void auditKeepsItsQueuePositionAheadOfLaterWritesAndCompletesOnIoWorker() throws Exception {
        Path root = directory.resolve("ordered-audit");
        Files.createDirectories(root);
        Path settings = root.resolve("settings.yml");
        Files.writeString(settings, "source: first\n");
        SqlSnapshotService.localStore(root, SqlDocumentStore.Backend.SQLITE)
                .put("settings.yml", "source: first\n");
        SqlSnapshotService service = new SqlSnapshotService(root,
                new BackupService(plugin(root), () -> { }));
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("SQL-ordered-audit"), root)) {
            io.submit(() -> {
                workerEntered.countDown();
                if (!releaseWorker.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting for ordered audit");
                }
                return null;
            });
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
            AtomicReference<String> completedOn = new AtomicReference<>();
            CompletableFuture<List<SqlSourceStatus>> audit = service.auditAsync(
                    List.of(SqlMirrorMode.SQLITE), RemoteSqlProfile.defaults(), io);
            CompletableFuture<List<SqlSourceStatus>> observed = audit.whenComplete((value, failure) ->
                    completedOn.set(Thread.currentThread().getName()));
            CompletableFuture<Void> laterWrite = io.submit(() -> {
                Files.writeString(settings, "source: later\n");
                return null;
            });
            assertFalse(audit.isDone());
            assertEquals("source: first\n", Files.readString(settings));
            releaseWorker.countDown();
            assertTrue(observed.get(15, TimeUnit.SECONDS).get(0).current());
            laterWrite.get(15, TimeUnit.SECONDS);
            assertEquals("RecipeWeaver-IO", completedOn.get());
            assertEquals("source: later\n", Files.readString(settings));
        } finally {
            releaseWorker.countDown();
        }
    }

    @Test
    void failedEarlierWriteStopsSqlAuditWithoutPoisoningRetry() throws Exception {
        Path root = directory.resolve("failed-write-audit");
        Files.createDirectories(root);
        Files.writeString(root.resolve("settings.yml"), "source: first\n");
        SqlSnapshotService.localStore(root, SqlDocumentStore.Backend.H2)
                .put("settings.yml", "source: first\n");
        SqlSnapshotService service = new SqlSnapshotService(root,
                new BackupService(plugin(root), () -> { }));
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("SQL-failed-write-audit"), root)) {
            ExecutionException failedWrite = assertThrows(ExecutionException.class, () ->
                    io.submit(() -> { throw new IOException("prior SQL save failed"); })
                            .get(15, TimeUnit.SECONDS));
            assertEquals("prior SQL save failed", failedWrite.getCause().getMessage());
            ExecutionException blocked = assertThrows(ExecutionException.class, () ->
                    service.auditAsync(List.of(SqlMirrorMode.H2), RemoteSqlProfile.defaults(), io)
                            .get(15, TimeUnit.SECONDS));
            assertEquals("prior SQL save failed", blocked.getCause().getMessage());
            assertFalse(service.busy());
            SqlSourceStatus retried = service.auditAsync(List.of(SqlMirrorMode.H2),
                    RemoteSqlProfile.defaults(), io).get(15, TimeUnit.SECONDS).get(0);
            assertTrue(retried.current());
        }
    }

    @Test
    void orderedRestoreSkipsInvalidFirstSourceAndPreparesNextReadableSource() throws Exception {
        Path root = directory.resolve("ordered-restore");
        Files.createDirectories(root);
        Files.writeString(root.resolve("settings.yml"), "local: true\n");
        SqlDocumentStore sqlite = SqlSnapshotService.localStore(root,
                SqlDocumentStore.Backend.SQLITE);
        sqlite.put("settings.yml", "old: true\n");
        injectInvalidSqlitePath("jdbc:sqlite:" + root.resolve("sql-snapshots/recipeweaver.sqlite"),
                "recipes/bad.name.yml", "schema: 1\n");
        SqlDocumentStore h2 = SqlSnapshotService.localStore(root, SqlDocumentStore.Backend.H2);
        h2.put("settings.yml", "from-h2: true\n");
        SqlSnapshotService service = new SqlSnapshotService(root,
                new BackupService(plugin(root), () -> { }));
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("SQL-order-test"), root)) {
            SqlPreparedSource selected = service.prepareOrderedRestoreAsync(
                    List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2),
                    RemoteSqlProfile.defaults(), io).get(15, TimeUnit.SECONDS);
            assertEquals(SqlMirrorMode.H2, selected.backend());
            assertTrue(selected.backupName().endsWith(".dir"));
            assertEquals("from-h2: true\n", Files.readString(root.resolve("backups")
                    .resolve(selected.backupName()).resolve("settings.yml")));
            assertEquals("local: true\n", Files.readString(root.resolve("settings.yml")));
        }
    }

    @Test
    void failedFirstReadDoesNotHideHealthySecondSource() throws Exception {
        Path root = directory.resolve("read-failure");
        Path sqlRoot = root.resolve("sql-snapshots");
        Files.createDirectories(sqlRoot.resolve("recipeweaver.sqlite"));
        Files.writeString(root.resolve("settings.yml"), "local: true\n");
        SqlSnapshotService.localStore(root, SqlDocumentStore.Backend.H2)
                .put("settings.yml", "source: h2\n");
        SqlSnapshotService service = new SqlSnapshotService(root,
                new BackupService(plugin(root), () -> { }));
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("SQL-failure-audit"), root)) {
            List<SqlSourceStatus> statuses = service.auditAsync(
                    List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2),
                    RemoteSqlProfile.defaults(), io).get(15, TimeUnit.SECONDS);
            assertTrue(statuses.get(0).failure().startsWith("SQLITE read failed"));
            assertEquals(null, statuses.get(0).sha256());
            assertEquals(null, statuses.get(1).failure());
            assertEquals(1, statuses.get(1).changed());
            SqlPreparedSource selected = service.prepareOrderedRestoreAsync(
                    List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2),
                    RemoteSqlProfile.defaults(), io).get(15, TimeUnit.SECONDS);
            assertEquals(SqlMirrorMode.H2, selected.backend());
        }
    }

    @Test
    void unreadableLocalReadDoesNotBlockPreparingHealthySqlRecovery() throws Exception {
        Path root = directory.resolve("unreadable-local");
        Files.createDirectories(root);
        Files.write(root.resolve("settings.yml"), new byte[] {(byte) 0xc3, (byte) 0x28});
        SqlSnapshotService.localStore(root, SqlDocumentStore.Backend.SQLITE)
                .put("settings.yml", "schema: 9\nlockdown: false\n");
        SqlSnapshotService service = new SqlSnapshotService(root,
                new BackupService(plugin(root), () -> { }));
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("SQL-recovery-test"), root)) {
            ExecutionException localFailure = assertThrows(ExecutionException.class,
                    () -> io.submitObserved(() -> DiskSnapshot.read(root)).get(15, TimeUnit.SECONDS));
            assertTrue(localFailure.getCause() instanceof IOException);
            io.flush().get(15, TimeUnit.SECONDS);
            SqlPreparedSource prepared = service.prepareOrderedRestoreAsync(
                    List.of(SqlMirrorMode.SQLITE), RemoteSqlProfile.defaults(), io)
                    .get(15, TimeUnit.SECONDS);
            assertEquals(SqlMirrorMode.SQLITE, prepared.backend());
            assertEquals("schema: 9\nlockdown: false\n", Files.readString(root.resolve("backups")
                    .resolve(prepared.backupName()).resolve("settings.yml")));
            assertEquals(2, Files.readAllBytes(root.resolve("settings.yml")).length);
        }
    }

    @Test
    void auditStillOffersInspectedSqlSourceWhenLocalFilesAreUnreadable() throws Exception {
        Path root = directory.resolve("audit-unreadable-local");
        Files.createDirectories(root);
        Files.write(root.resolve("settings.yml"), new byte[] {(byte) 0xc3, (byte) 0x28});
        SqlSnapshotService.localStore(root, SqlDocumentStore.Backend.SQLITE)
                .put("settings.yml", "schema: 9\nlockdown: false\n");
        SqlSnapshotService service = new SqlSnapshotService(root,
                new BackupService(plugin(root), () -> { }));
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("SQL-unreadable-audit"), root)) {
            List<SqlSourceStatus> statuses = service.auditAsync(List.of(SqlMirrorMode.SQLITE),
                    RemoteSqlProfile.defaults(), io).get(15, TimeUnit.SECONDS);
            SqlSourceStatus status = statuses.get(0);
            assertEquals(null, status.failure());
            assertFalse(status.comparisonAvailable());
            assertFalse(status.current());
            assertEquals(64, status.sha256().length());

            SqlPreparedSource prepared = service.prepareSelectedRestoreAsync(
                    List.of(SqlMirrorMode.SQLITE), RemoteSqlProfile.defaults(),
                    SqlMirrorMode.SQLITE, status.sha256(), io).get(15, TimeUnit.SECONDS);
            assertEquals("schema: 9\nlockdown: false\n", Files.readString(root.resolve("backups")
                    .resolve(prepared.backupName()).resolve("settings.yml")));
            assertEquals(2, Files.readAllBytes(root.resolve("settings.yml")).length);
        }
    }

    @Test
    void auditAndManualPrepareRejectSqlCopiesWithoutSettings() throws Exception {
        Path root = directory.resolve("missing-sql-settings");
        Files.createDirectories(root);
        Files.writeString(root.resolve("settings.yml"), "schema: 9\nlockdown: false\n");
        SqlSnapshotService.localStore(root, SqlDocumentStore.Backend.SQLITE)
                .put("items.yml", "items: {}\n");
        SqlSnapshotService service = new SqlSnapshotService(root,
                new BackupService(plugin(root), () -> { }));
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("SQL-missing-settings"), root)) {
            SqlSourceStatus status = service.auditAsync(List.of(SqlMirrorMode.SQLITE),
                    RemoteSqlProfile.defaults(), io).get(15, TimeUnit.SECONDS).get(0);
            assertTrue(status.failure().contains("read failed"));
            assertEquals(null, status.sha256());
            assertThrows(ExecutionException.class, () ->
                    service.prepareRestoreAsync(SqlDocumentStore.Backend.SQLITE, io)
                            .get(15, TimeUnit.SECONDS));
            io.flush().get(15, TimeUnit.SECONDS);
            assertTrue(Files.notExists(root.resolve("backups")));
        }
    }

    @Test
    void decodedAuditRejectsStructurallyReadableButInvalidSource() throws Exception {
        Path root = directory.resolve("decoded-audit");
        SettingsRepository settings = new SettingsRepository(root);
        settings.setLockdown(false);
        SqlSnapshotService.localStore(root, SqlDocumentStore.Backend.SQLITE)
                .put("settings.yml", "schema: 9\nlockdown: false\n");
        SqlSnapshotService.localStore(root, SqlDocumentStore.Backend.H2)
                .replaceSnapshot(DiskSnapshot.read(root));
        BackupService backups = new BackupService(plugin(root), () -> { });
        SqlSnapshotService service = new SqlSnapshotService(root, backups);
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("SQL-decoded-audit"), root)) {
            List<SqlSnapshotService.AuditCandidate> candidates =
                    service.auditCandidatesAsync(List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2),
                            RemoteSqlProfile.defaults(), io).get(15, TimeUnit.SECONDS);
            List<SqlSourceStatus> validated =
                    SqlSnapshotService.validateAuditedSources(candidates, backups);
            assertEquals("SQLITE invalid snapshot", validated.get(0).failure());
            assertEquals(64, validated.get(0).sha256().length());
            assertEquals(null, validated.get(1).failure());
            assertTrue(validated.get(1).current());
            assertTrue(Files.notExists(root.resolve("backups")));
            assertTrue(io.submit(() -> {
                io.configureMirrors(List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2),
                        RemoteSqlProfile.defaults());
                return io.replaceMirrorFromLocal(SqlMirrorMode.SQLITE,
                        validated.get(0).sha256());
            }).get(15, TimeUnit.SECONDS));
            List<SqlSourceStatus> repaired = SqlSnapshotService.validateAuditedSources(
                    service.auditCandidatesAsync(List.of(SqlMirrorMode.SQLITE),
                            RemoteSqlProfile.defaults(), io).get(15, TimeUnit.SECONDS), backups);
            assertTrue(repaired.get(0).current());
        }
    }

    @Test
    void selectedRestoreUsesAuditedDigestAndRejectsChangedSource() throws Exception {
        Path root = directory.resolve("selected-restore");
        Files.createDirectories(root);
        Files.writeString(root.resolve("settings.yml"), "local: true\n");
        SqlSnapshotService.localStore(root, SqlDocumentStore.Backend.SQLITE)
                .put("settings.yml", "source: sqlite\n");
        SqlDocumentStore h2 = SqlSnapshotService.localStore(root, SqlDocumentStore.Backend.H2);
        h2.put("settings.yml", "source: h2\n");
        SqlSnapshotService service = new SqlSnapshotService(root,
                new BackupService(plugin(root), () -> { }));
        List<SqlMirrorMode> selected = List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2);
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("SQL-selected-test"), root)) {
            List<SqlSourceStatus> audit = service.auditAsync(selected,
                    RemoteSqlProfile.defaults(), io).get(15, TimeUnit.SECONDS);
            SqlPreparedSource prepared = service.prepareSelectedRestoreAsync(selected,
                    RemoteSqlProfile.defaults(), SqlMirrorMode.H2,
                    audit.get(1).sha256(), io).get(15, TimeUnit.SECONDS);
            assertEquals(SqlMirrorMode.H2, prepared.backend());
            assertEquals("source: h2\n", Files.readString(root.resolve("backups")
                    .resolve(prepared.backupName()).resolve("settings.yml")));
            assertEquals("local: true\n", Files.readString(root.resolve("settings.yml")));

            h2.put("settings.yml", "source: changed\n");
            var stale = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> service.prepareSelectedRestoreAsync(selected,
                            RemoteSqlProfile.defaults(), SqlMirrorMode.H2,
                            audit.get(1).sha256(), io).get(15, TimeUnit.SECONDS));
            assertTrue(stale.getCause().getMessage().contains("changed since inspection"));
            io.flush().get(15, TimeUnit.SECONDS);
            List<SqlSourceStatus> refreshed = service.auditAsync(selected,
                    RemoteSqlProfile.defaults(), io).get(15, TimeUnit.SECONDS);
            SqlPreparedSource updated = service.prepareSelectedRestoreAsync(selected,
                    RemoteSqlProfile.defaults(), SqlMirrorMode.H2,
                    refreshed.get(1).sha256(), io).get(15, TimeUnit.SECONDS);
            assertEquals("source: changed\n", Files.readString(root.resolve("backups")
                    .resolve(updated.backupName()).resolve("settings.yml")));
            assertThrows(IllegalArgumentException.class,
                    () -> service.prepareSelectedRestoreAsync(selected,
                            RemoteSqlProfile.defaults(), SqlMirrorMode.MYSQL,
                            audit.get(1).sha256(), io));
        }
    }

    @Test
    void deletionOnlySqlChangesInvalidateInspectedRestoreAndLocalOverwrite() throws Exception {
        for (SqlDocumentStore.Backend backend : new SqlDocumentStore.Backend[] {
                SqlDocumentStore.Backend.SQLITE, SqlDocumentStore.Backend.H2}) {
            Path root = directory.resolve("tombstone-audit-" + backend);
            Files.createDirectories(root);
            Files.writeString(root.resolve("settings.yml"), "source: local\n");
            SqlDocumentStore store = SqlSnapshotService.localStore(root, backend);
            store.put("settings.yml", "source: sql\n");
            SqlMirrorMode mode = SqlMirrorMode.valueOf(backend.name());
            SqlSnapshotService service = new SqlSnapshotService(root,
                    new BackupService(plugin(root), () -> { }));
            try (AsyncIoService io = new AsyncIoService(Logger.getLogger("SQL-tombstone-audit-" + mode), root)) {
                String liveHash = SqlSnapshotService.sha256(store.readSnapshot().documents());
                SqlSourceStatus withoutDeletion = service.auditAsync(List.of(mode),
                        RemoteSqlProfile.defaults(), io).get(15, TimeUnit.SECONDS).get(0);
                assertEquals(liveHash, withoutDeletion.sha256());

                store.delete("recipes/first.yml");
                SqlSourceStatus inspected = service.auditAsync(List.of(mode),
                        RemoteSqlProfile.defaults(), io).get(15, TimeUnit.SECONDS).get(0);
                assertFalse(liveHash.equals(inspected.sha256()));
                store.delete("recipes/second.yml");
                SqlSourceStatus changed = service.auditAsync(List.of(mode),
                        RemoteSqlProfile.defaults(), io).get(15, TimeUnit.SECONDS).get(0);
                assertFalse(inspected.sha256().equals(changed.sha256()));
                assertEquals(liveHash, SqlSnapshotService.sha256(store.readSnapshot().documents()));

                ExecutionException stale = assertThrows(ExecutionException.class,
                        () -> service.prepareSelectedRestoreAsync(List.of(mode),
                                RemoteSqlProfile.defaults(), mode, inspected.sha256(), io)
                                .get(15, TimeUnit.SECONDS));
                assertTrue(stale.getCause().getMessage().contains("changed since inspection"));
                assertFalse(store.replaceSnapshotIfCurrentHash(DiskSnapshot.read(root), inspected.sha256()));
                assertEquals("source: sql\n", store.readSnapshot().file("settings.yml"));
                assertTrue(store.replaceSnapshotIfCurrentHash(DiskSnapshot.read(root), changed.sha256()));
                assertEquals("source: local\n", store.readSnapshot().file("settings.yml"));
            }
        }
    }

    private static void injectInvalidSqlitePath(String url, String path, String content)
            throws Exception {
        // Model an existing or externally edited SQL copy without using the guarded writer.
        try (var connection = java.sql.DriverManager.getConnection(url);
             var insert = connection.prepareStatement(
                     "INSERT INTO rw_documents (path, content, deleted) VALUES (?, ?, 0)")) {
            insert.setString(1, path);
            insert.setString(2, content);
            insert.executeUpdate();
        }
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

    private static Plugin plugin(Path root) {
        File dataFolder = root.toFile();
        Logger logger = Logger.getLogger("SQL-snapshot-test");
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getDataFolder" -> dataFolder;
                    case "getName" -> "RecipeWeaver";
                    case "getLogger" -> logger;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
