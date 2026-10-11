package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlDocumentStoreTest {
    @TempDir Path directory;

    @Test
    void readingAnUninitializedSourceDoesNotCreateDocumentTable() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("uninitialized.sqlite");
        SqlDocumentStore store = new SqlDocumentStore(SqlDocumentStore.Backend.SQLITE,
                url, null, null);
        assertTrue(store.readAll().isEmpty());
        try (var connection = DriverManager.getConnection(url);
             var tables = connection.getMetaData().getTables(null, null, null,
                     new String[]{"TABLE"})) {
            boolean found = false;
            while (tables.next()) {
                if ("rw_documents".equalsIgnoreCase(tables.getString("TABLE_NAME"))) found = true;
            }
            assertFalse(found);
        }
    }

    @Test
    void sqliteSurvivesReopenWithUpdatesTombstonesAndAtomicReplacement() throws Exception {
        SqlDocumentStore first = new SqlDocumentStore(SqlDocumentStore.Backend.SQLITE,
                "jdbc:sqlite:" + directory.resolve("recipes.sqlite"), null, null);
        first.put("recipes/one.yml", "schema: 1\n");
        first.put("recipes/one.yml", "schema: 2\n");
        first.put("items.yml", "schema: 1\n");
        first.delete("recipes/one.yml");

        SqlDocumentStore reopened = new SqlDocumentStore(SqlDocumentStore.Backend.SQLITE,
                "jdbc:sqlite:" + directory.resolve("recipes.sqlite"), null, null);
        assertEquals(SqlDocumentStore.Document.tombstone(),
                reopened.readAll().get("recipes/one.yml"));
        assertEquals(SqlDocumentStore.Document.value("schema: 1\n"),
                reopened.readAll().get("items.yml"));

        Map<String, SqlDocumentStore.Document> replacement = Map.of(
                "stations/two.yml", SqlDocumentStore.Document.value("schema: 3\n"),
                "recipes/one.yml", SqlDocumentStore.Document.tombstone());
        reopened.replaceAll(replacement);
        assertEquals(replacement, first.readAll());
        assertThrows(IOException.class, () -> reopened.replaceAll(Map.of(
                "../outside.yml", SqlDocumentStore.Document.value("bad"))));
        assertEquals(replacement, first.readAll());
    }

    @Test
    void h2RoundTripUsesSamePortableDocumentSchema() throws Exception {
        String file = directory.resolve("recipes-h2").toString().replace('\\', '/');
        SqlDocumentStore store = new SqlDocumentStore(SqlDocumentStore.Backend.H2,
                "jdbc:h2:file:" + file, null, null);
        store.put("grid-recipes/example.yml", "schema: 9\nid: recipeweaver:example\n");
        assertEquals("schema: 9\nid: recipeweaver:example\n",
                store.readAll().get("grid-recipes/example.yml").content());
        store.delete("grid-recipes/example.yml");
        assertTrue(store.readAll().get("grid-recipes/example.yml").deleted());
    }

    @Test
    void connectionProbeSavesReadsAndRemovesItsDocumentOnBothLocalDrivers() throws Exception {
        for (SqlDocumentStore.Backend backend : new SqlDocumentStore.Backend[] {
                SqlDocumentStore.Backend.SQLITE, SqlDocumentStore.Backend.H2}) {
            SqlDocumentStore store = localStore(backend, "probe-" + backend);
            store.put("settings.yml", "schema: 9\nlockdown: false\n");
            Map<String, SqlDocumentStore.Document> before = store.readAll();
            assertTrue(store.verifyRoundTrip());
            assertEquals(before, store.readAll());
        }
    }

    @Test
    void connectionProbeWorksDespiteAnUnrelatedInvalidSourceRow() throws Exception {
        for (SqlDocumentStore.Backend backend : new SqlDocumentStore.Backend[] {
                SqlDocumentStore.Backend.SQLITE, SqlDocumentStore.Backend.H2}) {
            String path = directory.resolve("probe-invalid-" + backend)
                    .toString().replace('\\', '/');
            String url = backend == SqlDocumentStore.Backend.SQLITE
                    ? "jdbc:sqlite:" + path + ".sqlite" : "jdbc:h2:file:" + path;
            SqlDocumentStore store = new SqlDocumentStore(backend, url, null, null);
            store.put("settings.yml", "schema: 9\n");
            try (var connection = DriverManager.getConnection(url);
                 var insert = connection.prepareStatement("INSERT INTO rw_documents "
                         + "(path, content, deleted) VALUES (?, ?, 0)")) {
                insert.setString(1, "not-a-recipe-path.yml");
                insert.setString(2, "unrelated: invalid\n");
                assertEquals(1, insert.executeUpdate());
            }
            assertThrows(IOException.class, store::readAll);
            assertTrue(store.verifyRoundTrip(), backend.name());
            try (var connection = DriverManager.getConnection(url);
                 var query = connection.prepareStatement("SELECT COUNT(*) FROM rw_documents");
                 var count = query.executeQuery()) {
                assertTrue(count.next());
                assertEquals(2, count.getInt(1), backend.name());
            }
            assertThrows(IOException.class, store::readAll);
        }
    }

    @Test
    void invalidDeletionMarkerCannotBeReadOrUsedAsAMirrorBaseline() throws Exception {
        for (SqlDocumentStore.Backend backend : new SqlDocumentStore.Backend[] {
                SqlDocumentStore.Backend.SQLITE, SqlDocumentStore.Backend.H2}) {
            String path = directory.resolve("bad-deletion-marker-" + backend)
                    .toString().replace('\\', '/');
            String url = backend == SqlDocumentStore.Backend.SQLITE
                    ? "jdbc:sqlite:" + path + ".sqlite" : "jdbc:h2:file:" + path;
            SqlDocumentStore store = new SqlDocumentStore(backend, url, null, null);
            store.put("settings.yml", "schema: 9\n");
            try (var connection = DriverManager.getConnection(url);
                 var update = connection.prepareStatement(
                         "UPDATE rw_documents SET content = NULL, deleted = 2 WHERE path = ?")) {
                update.setString(1, "settings.yml");
                assertEquals(1, update.executeUpdate());
            }
            assertThrows(IOException.class, store::readAll);
            assertThrows(IOException.class, () -> store.writeIfUnchanged("settings.yml",
                    "schema: 9\n", SqlDocumentStore.Document.value("schema: 10\n")));
            assertThrows(IOException.class, () -> store.writeIfSnapshotUnchanged("settings.yml",
                    Map.of("settings.yml", "schema: 9\n"),
                    SqlDocumentStore.Document.value("schema: 10\n")));
        }
    }

    @Test
    void applicationSettingsRoundTripThroughSqliteSnapshot() throws Exception {
        SettingsRepository saved = new SettingsRepository(directory.resolve("live"));
        saved.setLockdown(true);
        saved.setBackupRetention(7);
        SqlDocumentStore store = new SqlDocumentStore(SqlDocumentStore.Backend.SQLITE,
                "jdbc:sqlite:" + directory.resolve("application.sqlite"), null, null);
        store.replaceSnapshot(DiskSnapshot.read(directory.resolve("live")));

        SettingsRepository loaded = new SettingsRepository(directory.resolve("restored"));
        loaded.load(store.readSnapshot());
        assertTrue(loaded.lockdown());
        assertEquals(7, loaded.backupRetention());
    }

    @Test
    void sqliteWritesCanRunInOrderOffTheServerThread() throws Exception {
        SqlDocumentStore store = new SqlDocumentStore(SqlDocumentStore.Backend.SQLITE,
                "jdbc:sqlite:" + directory.resolve("ordered.sqlite"), null, null);
        AtomicReference<String> workerName = new AtomicReference<>();
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("sql-test"))) {
            var first = io.submit(() -> {
                workerName.set(Thread.currentThread().getName());
                store.put("settings.yml", "version: 1\n");
                return null;
            });
            var second = io.submit(() -> {
                store.put("settings.yml", "version: 2\n");
                return null;
            });
            var snapshot = io.submit(store::readAll);
            first.join();
            second.join();
            assertEquals("version: 2\n", snapshot.join().get("settings.yml").content());
        }
        assertEquals("RecipeWeaver-IO", workerName.get());
    }

    @Test
    void rejectsWrongDriverSchemeAndInvalidDocumentPaths() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new SqlDocumentStore(
                SqlDocumentStore.Backend.SQLITE, "jdbc:mysql://localhost/db", null, null));
        SqlDocumentStore store = new SqlDocumentStore(SqlDocumentStore.Backend.SQLITE,
                "jdbc:sqlite:" + directory.resolve("invalid.sqlite"), null, null);
        assertThrows(IOException.class, () -> store.put("backups/secret.yml", "bad"));
        assertThrows(IOException.class, () -> store.put("recipes/../settings.yml", "bad"));
        assertThrows(IOException.class, () -> store.put("recipes\\bad.yml", "bad"));
        assertThrows(IOException.class, () -> store.put("recipes/a.b.yml", "bad"));
        assertThrows(IOException.class, () -> store.put("discoveries/not-a-uuid.yml", "bad"));
        assertTrue(store.readAll().isEmpty());
    }

    @Test
    void guardedFullReplacementRejectsStaleSourceAndStaleAudit() throws Exception {
        for (SqlDocumentStore.Backend backend : new SqlDocumentStore.Backend[] {
                SqlDocumentStore.Backend.SQLITE, SqlDocumentStore.Backend.H2}) {
            SqlDocumentStore store = localStore(backend, "full-guard-" + backend);
            DiskSnapshot first = DiskSnapshot.fromDocuments(Map.of("settings.yml", "version: 1\n"));
            DiskSnapshot second = DiskSnapshot.fromDocuments(Map.of("settings.yml", "version: 2\n"));
            store.replaceSnapshot(first);
            assertFalse(store.replaceSnapshotIfUnchanged(second,
                    Map.of("settings.yml", "version: stale\n")));
            assertEquals(first.documents(), store.readSnapshot().documents());
            assertTrue(store.replaceSnapshotIfUnchanged(second, first.documents()));
            assertEquals(second.documents(), store.readSnapshot().documents());
            assertFalse(store.replaceSnapshotIfCurrentHash(first,
                    SqlSnapshotService.sha256(first.documents())));
            assertEquals(second.documents(), store.readSnapshot().documents());
            assertTrue(store.replaceSnapshotIfCurrentHash(first,
                    SqlSnapshotService.sha256(second.documents())));
            assertEquals(first.documents(), store.readSnapshot().documents());
        }
    }

    @Test
    void guardedReplacementRejectsUnexpectedLocalPathsBeforeSqlOverwrite() throws Exception {
        Path local = directory.resolve("local-with-notes");
        Files.createDirectories(local.resolve("recipes"));
        Files.writeString(local.resolve("settings.yml"), "source: local\n");
        Files.writeString(local.resolve("recipes/notes.txt"), "operator note\n");
        DiskSnapshot unfiltered = DiskSnapshot.read(local);
        Map<String, String> previous = Map.of("settings.yml", "source: sql\n");
        for (SqlDocumentStore.Backend backend : new SqlDocumentStore.Backend[] {
                SqlDocumentStore.Backend.SQLITE, SqlDocumentStore.Backend.H2}) {
            SqlDocumentStore store = localStore(backend, "invalid-guard-" + backend);
            store.replaceSnapshot(DiskSnapshot.fromDocuments(previous));
            assertThrows(IOException.class, () -> store.replaceSnapshotIfUnchanged(unfiltered, previous));
            assertEquals(previous, store.readSnapshot().documents());
            assertThrows(IOException.class, () -> store.replaceSnapshotIfCurrentHash(unfiltered,
                    SqlSnapshotService.sha256(previous)));
            assertEquals(previous, store.readSnapshot().documents());
        }
    }

    @Test
    void auditedLocalWinsRemovesTombstonesWhenLiveDocumentsAlreadyMatch() throws Exception {
        Map<String, String> documents = Map.of("settings.yml", "source: same\n");
        DiskSnapshot local = DiskSnapshot.fromDocuments(documents);
        for (SqlDocumentStore.Backend backend : new SqlDocumentStore.Backend[] {
                SqlDocumentStore.Backend.SQLITE, SqlDocumentStore.Backend.H2}) {
            SqlDocumentStore store = localStore(backend, "local-wins-deletions-" + backend);
            store.replaceSnapshot(local);
            store.delete("recipes/old.yml");
            assertEquals(documents, store.readSnapshot().documents());
            String inspected = SqlSnapshotService.sha256Stored(store.readAll());
            assertFalse(inspected.equals(SqlSnapshotService.sha256(documents)));

            assertTrue(store.replaceSnapshotIfCurrentHash(local, inspected));
            assertEquals(Map.of("settings.yml", SqlDocumentStore.Document.value("source: same\n")),
                    store.readAll());
            assertEquals(SqlSnapshotService.sha256(documents),
                    SqlSnapshotService.sha256Stored(store.readAll()));
        }
    }

    @Test
    void simultaneousGuardedWritesCannotBothReplaceTheSameObservedValue() throws Exception {
        for (SqlDocumentStore.Backend backend : new SqlDocumentStore.Backend[] {
                SqlDocumentStore.Backend.SQLITE, SqlDocumentStore.Backend.H2}) {
            SqlDocumentStore store = localStore(backend, "path-guard-" + backend);
            store.put("settings.yml", "version: first\n");
            CountDownLatch start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var one = workers.submit(() -> {
                    start.await();
                    return store.writeIfUnchanged("settings.yml", "version: first\n",
                            SqlDocumentStore.Document.value("version: one\n"));
                });
                var two = workers.submit(() -> {
                    start.await();
                    return store.writeIfUnchanged("settings.yml", "version: first\n",
                            SqlDocumentStore.Document.value("version: two\n"));
                });
                start.countDown();
                int accepted = (committed(one) ? 1 : 0) + (committed(two) ? 1 : 0);
                assertEquals(1, accepted);
                assertTrue(store.readSnapshot().file("settings.yml").matches("version: (one|two)\\n"));
            }
        }
    }

    @Test
    void incrementalSnapshotGuardRejectsUnrelatedSqlChangesAndAllowsIdempotentRetry() throws Exception {
        for (SqlDocumentStore.Backend backend : new SqlDocumentStore.Backend[] {
                SqlDocumentStore.Backend.SQLITE, SqlDocumentStore.Backend.H2}) {
            SqlDocumentStore store = localStore(backend, "global-guard-" + backend);
            Map<String, String> baseline = Map.of("settings.yml", "version: first\n",
                    "recipes/one.yml", "recipe: first\n");
            store.replaceSnapshot(DiskSnapshot.fromDocuments(baseline));
            store.put("items.yml", "external: true\n");
            assertFalse(store.writeIfSnapshotUnchanged("recipes/one.yml", baseline,
                    SqlDocumentStore.Document.value("recipe: second\n")));
            assertEquals("recipe: first\n", store.readSnapshot().file("recipes/one.yml"));
            assertEquals("external: true\n", store.readSnapshot().file("items.yml"));
            store.delete("items.yml");
            assertTrue(store.writeIfSnapshotUnchanged("recipes/one.yml", baseline,
                    SqlDocumentStore.Document.value("recipe: second\n")));
            assertTrue(store.writeIfSnapshotUnchanged("recipes/one.yml", baseline,
                    SqlDocumentStore.Document.value("recipe: second\n")));
            assertEquals("recipe: second\n", store.readSnapshot().file("recipes/one.yml"));
        }
    }

    @Test
    void incrementalWriteCannotMakeAnAuditableSourceExceedTheFileLimit() throws Exception {
        SqlDocumentStore store = localStore(SqlDocumentStore.Backend.SQLITE,
                "incremental-file-limit");
        Map<String, String> full = new LinkedHashMap<>();
        full.put("settings.yml", "schema: 4\n");
        for (int index = 0; index < 9_999; index++) {
            full.put("recipes/limit_" + index + ".yml", "schema: 1\n");
        }
        store.replaceSnapshot(DiskSnapshot.fromDocuments(full));

        IOException denied = assertThrows(IOException.class,
                () -> store.writeIfSnapshotUnchanged("recipes/overflow.yml", full,
                        SqlDocumentStore.Document.value("schema: 1\n")));
        assertTrue(denied.getMessage().contains("snapshot limits"));
        assertEquals(full, store.readSnapshot().documents());
    }

    @Test
    void incrementalMirrorCompactsOldTombstonesBeforeTheyCrowdOutLiveData() throws Exception {
        SqlDocumentStore store = localStore(SqlDocumentStore.Backend.SQLITE,
                "incremental-tombstone-compaction");
        Map<String, SqlDocumentStore.Document> rows = new LinkedHashMap<>();
        rows.put("settings.yml", SqlDocumentStore.Document.value("schema: 4\n"));
        for (int index = 0; index < 9_999; index++) {
            rows.put("recipes/deleted_" + index + ".yml", SqlDocumentStore.Document.tombstone());
        }
        store.replaceAll(rows);
        Map<String, String> baseline = Map.of("settings.yml", "schema: 4\n");
        assertEquals(baseline, store.readSnapshot().documents());

        assertTrue(store.writeIfSnapshotUnchanged("items.yml", baseline,
                SqlDocumentStore.Document.value("items: live\n")));
        assertEquals(Map.of("settings.yml", "schema: 4\n", "items.yml", "items: live\n"),
                store.readSnapshot().documents());
        assertEquals(2, store.readAll().size());
    }

    @Test
    void concurrentIncrementalWritesToDifferentPathsCannotBothAcceptOneBaseline() throws Exception {
        for (SqlDocumentStore.Backend backend : new SqlDocumentStore.Backend[] {
                SqlDocumentStore.Backend.SQLITE, SqlDocumentStore.Backend.H2}) {
            SqlDocumentStore store = localStore(backend, "concurrent-global-" + backend);
            Map<String, String> baseline = Map.of("settings.yml", "version: first\n");
            store.replaceSnapshot(DiskSnapshot.fromDocuments(baseline));
            CountDownLatch start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var first = workers.submit(() -> {
                    start.await();
                    return store.writeIfSnapshotUnchanged("recipes/one.yml", baseline,
                            SqlDocumentStore.Document.value("recipe: one\n"));
                });
                var second = workers.submit(() -> {
                    start.await();
                    return store.writeIfSnapshotUnchanged("items.yml", baseline,
                            SqlDocumentStore.Document.value("item: one\n"));
                });
                start.countDown();
                int accepted = (committed(first) ? 1 : 0) + (committed(second) ? 1 : 0);
                assertEquals(1, accepted, backend.name());
                Map<String, String> current = store.readSnapshot().documents();
                assertEquals(2, current.size());
                assertEquals("version: first\n", current.get("settings.yml"));
                assertTrue(current.containsKey("recipes/one.yml") ^ current.containsKey("items.yml"));
            }
        }
    }

    @Test
    void h2GuardInitializesAlongsideAnExistingDocumentTable() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("old-h2-source").toString().replace('\\', '/');
        try (var connection = DriverManager.getConnection(url);
             var create = connection.createStatement()) {
            create.executeUpdate("CREATE TABLE rw_documents (path VARCHAR(512) PRIMARY KEY, "
                    + "content CLOB, deleted SMALLINT NOT NULL)");
            create.executeUpdate("INSERT INTO rw_documents (path, content, deleted) "
                    + "VALUES ('settings.yml', 'source: old', 0)");
        }
        SqlDocumentStore store = new SqlDocumentStore(SqlDocumentStore.Backend.H2,
                url, null, null);
        Map<String, String> baseline = Map.of("settings.yml", "source: old");
        assertTrue(store.writeIfSnapshotUnchanged("settings.yml", baseline,
                SqlDocumentStore.Document.value("source: new")));
        assertEquals("source: new", store.readSnapshot().file("settings.yml"));
        try (var connection = DriverManager.getConnection(url);
             var query = connection.prepareStatement("SELECT revision FROM rw_write_guard WHERE id = 1");
             var result = query.executeQuery()) {
            assertTrue(result.next());
            assertEquals(1L, result.getLong(1));
        }
    }

    private SqlDocumentStore localStore(SqlDocumentStore.Backend backend, String name) {
        String path = directory.resolve(name).toString().replace('\\', '/');
        return backend == SqlDocumentStore.Backend.SQLITE
                ? new SqlDocumentStore(backend, "jdbc:sqlite:" + path + ".sqlite", null, null)
                : new SqlDocumentStore(backend, "jdbc:h2:file:" + path, null, null);
    }

    private static boolean committed(Future<Boolean> result) throws Exception {
        try { return result.get(20, TimeUnit.SECONDS); }
        catch (ExecutionException ex) {
            assertTrue(ex.getCause() instanceof IOException);
            return false;
        }
    }
}
