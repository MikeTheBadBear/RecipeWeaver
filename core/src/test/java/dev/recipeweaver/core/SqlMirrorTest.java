package dev.recipeweaver.core;

import dev.recipeweaver.api.SqlMirrorMode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlMirrorTest {
    @TempDir Path root;

    @Test
    void localPrimaryWritesPropagateInOrderAndSwitchResynchronizesH2() throws Exception {
        Path settings = root.resolve("settings.yml");
        Path recipe = root.resolve("recipes/example.yml");
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("mirror-test"), root)) {
            io.submit(() -> { io.configureMirror(SqlMirrorMode.SQLITE); return null; }).join();
            io.submit(() -> { io.writeDocument(settings, "schema: 4\n"); return null; }).join();
            io.submit(() -> { io.writeDocument(recipe, "first: true\n"); return null; }).join();
            io.submit(() -> { io.writeDocument(recipe, "second: true\n"); return null; }).join();
            SqlDocumentStore sqlite = SqlSnapshotService.localStore(root,
                    SqlDocumentStore.Backend.SQLITE);
            assertEquals("second: true\n", sqlite.readSnapshot().file("recipes/example.yml"));
            assertEquals(Files.readString(recipe), sqlite.readSnapshot().file("recipes/example.yml"));

            io.submit(() -> { io.deleteDocument(recipe); return null; }).join();
            assertFalse(Files.exists(recipe));
            assertTrue(sqlite.readAll().get("recipes/example.yml").deleted());

            io.submit(() -> { io.configureMirror(SqlMirrorMode.H2); return null; }).join();
            SqlDocumentStore h2 = SqlSnapshotService.localStore(root, SqlDocumentStore.Backend.H2);
            assertEquals("schema: 4\n", h2.readSnapshot().file("settings.yml"));
            assertNull(h2.readSnapshot().file("recipes/example.yml"));
            assertNull(io.mirrorFailure());
        }
    }

    @Test
    void mirrorFailureKeepsLocalCommitAndFullResyncRecovers() throws Exception {
        Path directory = root.resolve("sql-snapshots");
        Files.writeString(directory, "blocked");
        Path settings = root.resolve("settings.yml");
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("mirror-failure-test"), root)) {
            io.submit(() -> { io.configureMirror(SqlMirrorMode.SQLITE); return null; }).join();
            assertNotNull(io.mirrorFailure());
            io.submit(() -> { io.writeDocument(settings, "local: current\n"); return null; }).join();
            assertEquals("local: current\n", Files.readString(settings));
            assertNotNull(io.mirrorFailure());
            Files.delete(directory);
            assertTrue(io.submit(io::synchronizeMirror).join());
            assertNull(io.mirrorFailure());
            SqlDocumentStore sqlite = SqlSnapshotService.localStore(root,
                    SqlDocumentStore.Backend.SQLITE);
            assertEquals("local: current\n", sqlite.readSnapshot().file("settings.yml"));
        }
    }

    @Test
    void transientMirrorFailureRetriesWithoutAnotherLocalWrite() throws Exception {
        Path directory = root.resolve("sql-snapshots");
        Files.writeString(directory, "blocked");
        Path settings = root.resolve("settings.yml");
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("mirror-background-retry-test"), root)) {
            io.submit(() -> { io.configureMirror(SqlMirrorMode.SQLITE); return null; }).join();
            io.submit(() -> { io.writeDocument(settings, "local: committed\n"); return null; }).join();
            assertNotNull(io.mirrorFailure());
            Files.delete(directory);

            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (io.mirrorFailure() != null && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertNull(io.mirrorFailure(), "Transient SQL outage should recover without another local write");
            SqlDocumentStore sqlite = SqlSnapshotService.localStore(root,
                    SqlDocumentStore.Backend.SQLITE);
            assertEquals("local: committed\n", sqlite.readSnapshot().file("settings.yml"));
        }
    }

    @Test
    void backgroundRetryStopsWhenSqlHasDiverged() throws Exception {
        Path directory = root.resolve("sql-snapshots");
        Files.writeString(directory, "blocked");
        Path settings = root.resolve("settings.yml");
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("mirror-retry-conflict-test"), root)) {
            io.submit(() -> { io.configureMirror(SqlMirrorMode.SQLITE); return null; }).join();
            io.submit(() -> { io.writeDocument(settings, "local: true\n"); return null; }).join();
            Files.delete(directory);
            SqlDocumentStore sqlite = SqlSnapshotService.localStore(root,
                    SqlDocumentStore.Backend.SQLITE);
            sqlite.put("settings.yml", "external: true\n");

            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while ((io.mirrorFailure() == null || !io.mirrorFailure().contains("conflict"))
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(io.mirrorFailure() != null && io.mirrorFailure().contains("conflict"));
            Thread.sleep(1200);
            assertEquals("external: true\n", sqlite.readSnapshot().file("settings.yml"));
            assertEquals("local: true\n", Files.readString(settings));
        }
    }

    @Test
    void unexpectedLocalFileCannotPoisonSqlMirrorAndRecoveryPreservesLocalDocuments() throws Exception {
        Path settings = root.resolve("settings.yml");
        Path notes = root.resolve("recipes/notes.txt");
        Files.createDirectories(notes.getParent());
        Files.writeString(settings, "source: local\n");
        Files.writeString(notes, "operator note\n");
        for (SqlMirrorMode mode : List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2)) {
            try (AsyncIoService io = new AsyncIoService(Logger.getLogger("unexpected-file-" + mode), root)) {
                io.submit(() -> { io.configureMirror(mode); return null; }).join();
                assertNotNull(io.mirrorFailure());
                SqlDocumentStore store = SqlSnapshotService.localStore(root,
                        SqlDocumentStore.Backend.valueOf(mode.name()));
                assertTrue(store.readSnapshot().documents().isEmpty());
                assertEquals("source: local\n", Files.readString(settings));
                assertEquals("operator note\n", Files.readString(notes));
                Files.delete(notes);
                assertTrue(io.submit(io::synchronizeMirror).join());
                assertNull(io.mirrorFailure());
                assertEquals("source: local\n", store.readSnapshot().file("settings.yml"));
            }
            Files.writeString(notes, "operator note\n");
        }
    }

    @Test
    void twoSelectedMirrorsReceiveOrderedWritesAndDeletes() throws Exception {
        Path recipe = root.resolve("recipes/two.yml");
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("two-mirror-test"), root)) {
            io.submit(() -> {
                io.configureMirrors(List.of(SqlMirrorMode.H2, SqlMirrorMode.SQLITE),
                        RemoteSqlProfile.defaults());
                return null;
            }).join();
            assertEquals(List.of(SqlMirrorMode.H2, SqlMirrorMode.SQLITE), io.mirrorModes());
            io.submit(() -> { io.writeDocument(recipe, "value: first\n"); return null; }).join();
            io.submit(() -> { io.writeDocument(recipe, "value: second\n"); return null; }).join();
            for (SqlDocumentStore.Backend backend : List.of(
                    SqlDocumentStore.Backend.H2, SqlDocumentStore.Backend.SQLITE)) {
                SqlDocumentStore store = SqlSnapshotService.localStore(root, backend);
                assertEquals("value: second\n", store.readSnapshot().file("recipes/two.yml"));
            }
            io.submit(() -> { io.deleteDocument(recipe); return null; }).join();
            for (SqlDocumentStore.Backend backend : List.of(
                    SqlDocumentStore.Backend.H2, SqlDocumentStore.Backend.SQLITE)) {
                assertTrue(SqlSnapshotService.localStore(root, backend).readAll()
                        .get("recipes/two.yml").deleted());
            }
            assertTrue(io.mirrorFailures().isEmpty());
        }
    }

    @Test
    void failedSqliteDoesNotBlockH2AndTargetedRetryRecoversIt() throws Exception {
        Path sqlRoot = root.resolve("sql-snapshots");
        Files.createDirectories(sqlRoot.resolve("recipeweaver.sqlite"));
        Path settings = root.resolve("settings.yml");
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("isolation-test"), root)) {
            io.submit(() -> {
                io.configureMirrors(List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2),
                        RemoteSqlProfile.defaults());
                return null;
            }).join();
            assertTrue(io.mirrorFailures().containsKey(SqlMirrorMode.SQLITE));
            assertFalse(io.mirrorFailures().containsKey(SqlMirrorMode.H2));
            io.submit(() -> { io.writeDocument(settings, "local: saved\n"); return null; }).join();
            assertEquals("local: saved\n", Files.readString(settings));
            assertEquals("local: saved\n", SqlSnapshotService.localStore(root,
                    SqlDocumentStore.Backend.H2).readSnapshot().file("settings.yml"));
            Files.delete(sqlRoot.resolve("recipeweaver.sqlite"));
            assertTrue(io.submit(() -> io.synchronizeMirror(SqlMirrorMode.SQLITE)).join());
            assertTrue(io.mirrorFailures().isEmpty());
            assertEquals("local: saved\n", SqlSnapshotService.localStore(root,
                    SqlDocumentStore.Backend.SQLITE).readSnapshot().file("settings.yml"));
        }
    }

    @Test
    void startupDivergencePausesMirrorUntilAuditedLocalChoice() throws Exception {
        Path settings = root.resolve("settings.yml");
        Files.writeString(settings, "source: local\n");
        SqlDocumentStore sqlite = SqlSnapshotService.localStore(root,
                SqlDocumentStore.Backend.SQLITE);
        sqlite.put("settings.yml", "source: sql\n");
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("startup-conflict-test"), root)) {
            io.submit(() -> { io.configureMirror(SqlMirrorMode.SQLITE); return null; }).join();
            assertTrue(io.mirrorFailure().contains("conflict"));
            assertFalse(io.submit(io::synchronizeMirror).join());
            io.submit(() -> { io.writeDocument(root.resolve("items.yml"), "item: local\n");
                return null; }).join();
            assertEquals("source: sql\n", sqlite.readSnapshot().file("settings.yml"));
            assertNull(sqlite.readSnapshot().file("items.yml"));

            String inspected = SqlSnapshotService.sha256(sqlite.readSnapshot().documents());
            assertTrue(io.submitObserved(() -> io.replaceMirrorFromLocal(
                    SqlMirrorMode.SQLITE, inspected)).join());
            assertNull(io.mirrorFailure());
            assertEquals("source: local\n", sqlite.readSnapshot().file("settings.yml"));
            assertEquals("item: local\n", sqlite.readSnapshot().file("items.yml"));
        }
    }

    @Test
    void tombstoneOnlySourceRequiresExplicitLocalChoiceBeforeFirstMirrorSync() throws Exception {
        Files.writeString(root.resolve("settings.yml"), "source: local\n");
        for (SqlMirrorMode mode : List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2)) {
            SqlDocumentStore store = SqlSnapshotService.localStore(root,
                    SqlDocumentStore.Backend.valueOf(mode.name()));
            store.delete("recipes/previous.yml");
            try (AsyncIoService io = new AsyncIoService(Logger.getLogger("tombstone-conflict-" + mode), root)) {
                io.submit(() -> { io.configureMirror(mode); return null; }).join();
                assertTrue(io.mirrorFailure().contains("conflict"));
                assertTrue(store.readAll().get("recipes/previous.yml").deleted());
                assertNull(store.readSnapshot().file("settings.yml"));
                assertEquals("source: local\n", Files.readString(root.resolve("settings.yml")));
                String inspected = SqlSnapshotService.sha256Stored(store.readAll());
                assertTrue(io.submitObserved(() -> io.replaceMirrorFromLocal(mode, inspected)).join());
                assertNull(io.mirrorFailure());
                assertEquals("source: local\n", store.readSnapshot().file("settings.yml"));
                assertFalse(store.readAll().containsKey("recipes/previous.yml"));
            }
        }
    }

    @Test
    void matchingLiveSqlWithExtraTombstoneStillConflictsOnFirstMirrorSync() throws Exception {
        Path settings = root.resolve("settings.yml");
        for (SqlMirrorMode mode : List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2)) {
            Files.writeString(settings, "source: local\n");
            SqlDocumentStore store = SqlSnapshotService.localStore(root,
                    SqlDocumentStore.Backend.valueOf(mode.name()));
            store.put("settings.yml", "source: local\n");
            store.delete("recipes/previous.yml");
            try (AsyncIoService io = new AsyncIoService(Logger.getLogger("matching-tombstone-" + mode), root)) {
                io.submit(() -> { io.configureMirror(mode); return null; }).join();
                assertTrue(io.mirrorFailure().contains("conflict"), mode.name());
                io.submit(() -> { io.writeDocument(settings, "source: later\n"); return null; }).join();
                assertEquals("source: later\n", Files.readString(settings));
                assertEquals("source: local\n", store.readSnapshot().file("settings.yml"));
                assertTrue(store.readAll().get("recipes/previous.yml").deleted());
            }
        }
    }

    @Test
    void explicitLocalChoiceWaitsForPriorFailedSaveBeforeOverwritingSql() throws Exception {
        Files.writeString(root.resolve("settings.yml"), "source: local\n");
        for (SqlMirrorMode mode : List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2)) {
            SqlDocumentStore store = SqlSnapshotService.localStore(root,
                    SqlDocumentStore.Backend.valueOf(mode.name()));
            store.put("settings.yml", "source: sql\n");
            try (AsyncIoService io = new AsyncIoService(Logger.getLogger("local-choice-order-" + mode), root)) {
                io.submit(() -> { io.configureMirror(mode); return null; }).join();
                assertTrue(io.mirrorFailure().contains("conflict"));
                String inspected = SqlSnapshotService.sha256Stored(store.readAll());
                assertThrows(CompletionException.class, () -> io.submit(() -> {
                    throw new IOException("prior local save failed");
                }).join());

                CompletionException blocked = assertThrows(CompletionException.class, () ->
                        RecipeWeaverPlugin.replaceSqlMirrorFromLocalOnWorkerAsync(io, mode, inspected).join());
                assertEquals("prior local save failed", blocked.getCause().getMessage());
                assertEquals("source: sql\n", store.readSnapshot().file("settings.yml"));
                assertTrue(io.mirrorFailure().contains("conflict"));

                assertTrue(RecipeWeaverPlugin.replaceSqlMirrorFromLocalOnWorkerAsync(io, mode,
                        inspected).join());
                assertEquals("source: local\n", store.readSnapshot().file("settings.yml"));
                assertNull(io.mirrorFailure());
            }
        }
    }

    @Test
    void manualResyncWaitsForEarlierFailedSaveBeforePropagatingLocalFiles() throws Exception {
        Path settings = root.resolve("settings.yml");
        Files.writeString(settings, "source: first\n");
        for (SqlMirrorMode mode : List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2)) {
            try (AsyncIoService io = new AsyncIoService(Logger.getLogger("resync-order-" + mode), root)) {
                io.submit(() -> { io.configureMirror(mode); return null; }).join();
                SqlDocumentStore store = SqlSnapshotService.localStore(root,
                        SqlDocumentStore.Backend.valueOf(mode.name()));
                assertEquals("source: first\n", store.readSnapshot().file("settings.yml"));
                io.submit(() -> {
                    Files.writeString(settings, "source: second\n");
                    return null;
                }).join();
                assertThrows(CompletionException.class, () -> io.submit(() -> {
                    throw new IOException("pending local save failed");
                }).join());

                CompletionException blocked = assertThrows(CompletionException.class, () ->
                        RecipeWeaverPlugin.resyncSqlMirrorOnWorkerAsync(io, mode).join());
                assertEquals("pending local save failed", blocked.getCause().getMessage());
                assertEquals("source: first\n", store.readSnapshot().file("settings.yml"));

                assertTrue(RecipeWeaverPlugin.resyncSqlMirrorOnWorkerAsync(io, mode).join());
                assertEquals("source: second\n", store.readSnapshot().file("settings.yml"));
            }
            Files.writeString(settings, "source: first\n");
        }
    }

    @Test
    void staleLocalChoicePreservesSqlAndDoesNotPoisonWorker() throws Exception {
        Files.writeString(root.resolve("settings.yml"), "source: local\n");
        SqlDocumentStore sqlite = SqlSnapshotService.localStore(root,
                SqlDocumentStore.Backend.SQLITE);
        sqlite.put("settings.yml", "source: first-sql\n");
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("stale-conflict-test"), root)) {
            io.submit(() -> { io.configureMirror(SqlMirrorMode.SQLITE); return null; }).join();
            String inspected = SqlSnapshotService.sha256(sqlite.readSnapshot().documents());
            sqlite.put("settings.yml", "source: newer-sql\n");
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> RecipeWeaverPlugin.replaceSqlMirrorFromLocalOnWorkerAsync(
                            io, SqlMirrorMode.SQLITE, inspected).join());
            assertEquals("SQL source changed since inspection; inspect it again",
                    failure.getCause().getMessage());
            assertEquals("source: newer-sql\n", sqlite.readSnapshot().file("settings.yml"));
            io.flush().join();
            String current = SqlSnapshotService.sha256(sqlite.readSnapshot().documents());
            assertTrue(RecipeWeaverPlugin.replaceSqlMirrorFromLocalOnWorkerAsync(
                    io, SqlMirrorMode.SQLITE, current).join());
            assertEquals("source: local\n", sqlite.readSnapshot().file("settings.yml"));
        }
    }

    @Test
    void externalEditOfWrittenPathPausesOnlyThatMirror() throws Exception {
        Path recipe = root.resolve("recipes/changed.yml");
        Files.createDirectories(recipe.getParent());
        Files.writeString(recipe, "version: first\n");
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("path-conflict-test"), root)) {
            io.submit(() -> { io.configureMirrors(List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2),
                    RemoteSqlProfile.defaults()); return null; }).join();
            SqlDocumentStore sqlite = SqlSnapshotService.localStore(root,
                    SqlDocumentStore.Backend.SQLITE);
            SqlDocumentStore h2 = SqlSnapshotService.localStore(root, SqlDocumentStore.Backend.H2);
            sqlite.put("recipes/changed.yml", "version: external\n");
            io.submit(() -> { io.writeDocument(recipe, "version: second\n"); return null; }).join();
            assertEquals("version: second\n", Files.readString(recipe));
            assertEquals("version: external\n", sqlite.readSnapshot().file("recipes/changed.yml"));
            assertEquals("version: second\n", h2.readSnapshot().file("recipes/changed.yml"));
            assertTrue(io.mirrorFailures().get(SqlMirrorMode.SQLITE).contains("conflict"));
            assertFalse(io.mirrorFailures().containsKey(SqlMirrorMode.H2));
            assertFalse(io.submit(() -> io.synchronizeMirror(SqlMirrorMode.SQLITE)).join());
        }
    }

    @Test
    void unrelatedExternalDocumentPausesIncrementalPropagationWithoutLosingLocalCommit() throws Exception {
        Path recipe = root.resolve("recipes/changed.yml");
        Files.createDirectories(recipe.getParent());
        Files.writeString(recipe, "version: first\n");
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("global-conflict-test"), root)) {
            io.submit(() -> { io.configureMirrors(List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2),
                    RemoteSqlProfile.defaults()); return null; }).join();
            SqlDocumentStore sqlite = SqlSnapshotService.localStore(root,
                    SqlDocumentStore.Backend.SQLITE);
            SqlDocumentStore h2 = SqlSnapshotService.localStore(root, SqlDocumentStore.Backend.H2);
            sqlite.put("items.yml", "external: true\n");
            io.submit(() -> { io.writeDocument(recipe, "version: second\n"); return null; }).join();
            assertEquals("version: second\n", Files.readString(recipe));
            assertEquals("version: first\n", sqlite.readSnapshot().file("recipes/changed.yml"));
            assertEquals("external: true\n", sqlite.readSnapshot().file("items.yml"));
            assertEquals("version: second\n", h2.readSnapshot().file("recipes/changed.yml"));
            assertTrue(io.mirrorFailures().get(SqlMirrorMode.SQLITE).contains("conflict"));
            assertFalse(io.mirrorFailures().containsKey(SqlMirrorMode.H2));
            assertFalse(io.submit(() -> io.synchronizeMirror(SqlMirrorMode.SQLITE)).join());
            String inspected = SqlSnapshotService.sha256(sqlite.readSnapshot().documents());
            assertTrue(io.submitObserved(() -> io.replaceMirrorFromLocal(
                    SqlMirrorMode.SQLITE, inspected)).join());
            assertTrue(io.mirrorFailures().isEmpty());
            assertEquals("version: second\n", sqlite.readSnapshot().file("recipes/changed.yml"));
            assertNull(sqlite.readSnapshot().file("items.yml"));
        }
    }

    @Test
    void fullResyncDetectsUnrelatedExternalDocument() throws Exception {
        Files.writeString(root.resolve("settings.yml"), "source: local\n");
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("full-conflict-test"), root)) {
            io.submit(() -> { io.configureMirror(SqlMirrorMode.SQLITE); return null; }).join();
            SqlDocumentStore sqlite = SqlSnapshotService.localStore(root,
                    SqlDocumentStore.Backend.SQLITE);
            sqlite.put("items.yml", "source: external\n");
            assertFalse(io.submit(io::synchronizeMirror).join());
            assertTrue(io.mirrorFailure().contains("conflict"));
            assertEquals("source: external\n", sqlite.readSnapshot().file("items.yml"));
        }
    }

    @Test
    void reconfigurationKeepsKnownBaselineAcrossLocalRestore() throws Exception {
        Path settings = root.resolve("settings.yml");
        Files.writeString(settings, "source: first\n");
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("restore-baseline-test"), root)) {
            io.submit(() -> { io.configureMirror(SqlMirrorMode.SQLITE); return null; }).join();
            SqlDocumentStore sqlite = SqlSnapshotService.localStore(root,
                    SqlDocumentStore.Backend.SQLITE);
            Files.writeString(settings, "source: restored\n");
            io.submit(() -> { io.configureMirror(SqlMirrorMode.SQLITE); return null; }).join();
            assertEquals("source: restored\n", sqlite.readSnapshot().file("settings.yml"));
            assertNull(io.mirrorFailure());

            sqlite.put("settings.yml", "source: external\n");
            Files.writeString(settings, "source: later-restore\n");
            io.submit(() -> { io.configureMirror(SqlMirrorMode.SQLITE); return null; }).join();
            assertEquals("source: external\n", sqlite.readSnapshot().file("settings.yml"));
            assertTrue(io.mirrorFailure().contains("conflict"));
        }
    }
}
