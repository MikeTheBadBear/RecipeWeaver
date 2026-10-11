package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlReloadComparisonTest {
    @TempDir Path directory;

    @Test
    void preparedSourceComparisonKeepsItsQueuePositionOnTheIoWorker() throws Exception {
        Path prepared = directory.resolve("prepared");
        Files.createDirectories(prepared);
        Files.writeString(prepared.resolve("settings.yml"), "source: first\n");
        DiskSnapshot local = DiskSnapshot.fromDocuments(Map.of("settings.yml", "source: first\n"));
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("SQL-reload-compare-order"))) {
            io.submit(() -> {
                workerEntered.countDown();
                if (!releaseWorker.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting for SQL reload comparison");
                }
                return null;
            });
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
            AtomicReference<String> completedOn = new AtomicReference<>();
            CompletableFuture<Boolean> comparison = RecipeWeaverPlugin
                    .comparePreparedSqlReloadAsync(io, prepared, local);
            CompletableFuture<Boolean> observed = comparison.whenComplete((same, failure) ->
                    completedOn.set(Thread.currentThread().getName()));
            CompletableFuture<Void> laterWrite = io.submit(() -> {
                Files.writeString(prepared.resolve("settings.yml"), "source: later\n");
                return null;
            });
            assertFalse(comparison.isDone());
            releaseWorker.countDown();
            assertTrue(observed.get(10, TimeUnit.SECONDS));
            laterWrite.get(10, TimeUnit.SECONDS);
            assertEquals("RecipeWeaver-IO", completedOn.get());
            assertEquals("source: later\n", Files.readString(prepared.resolve("settings.yml")));
        } finally {
            releaseWorker.countDown();
        }
    }

    @Test
    void failedPreparedSourceReadDoesNotPoisonRetryButPriorSaveStillBlocksIt() throws Exception {
        Path prepared = directory.resolve("prepared");
        Files.createDirectories(prepared);
        Files.writeString(prepared.resolve("settings.yml"), "source: first\n");
        DiskSnapshot local = DiskSnapshot.fromDocuments(Map.of("settings.yml", "source: first\n"));
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("SQL-reload-compare-failure"))) {
            ExecutionException prior = assertThrows(ExecutionException.class, () ->
                    io.submit(() -> { throw new IOException("prior save failed"); })
                            .get(10, TimeUnit.SECONDS));
            assertEquals("prior save failed", prior.getCause().getMessage());
            ExecutionException blocked = assertThrows(ExecutionException.class, () ->
                    RecipeWeaverPlugin.comparePreparedSqlReloadAsync(io, prepared, local)
                            .get(10, TimeUnit.SECONDS));
            assertEquals("prior save failed", blocked.getCause().getMessage());

            Path malformed = prepared.resolve("recipes");
            Files.writeString(malformed, "not a directory\n");
            ExecutionException invalid = assertThrows(ExecutionException.class, () ->
                    RecipeWeaverPlugin.comparePreparedSqlReloadAsync(io, prepared, local)
                            .get(10, TimeUnit.SECONDS));
            assertTrue(invalid.getCause().getMessage().contains("recipes must be a directory"));
            io.flush().get(10, TimeUnit.SECONDS);
            Files.delete(malformed);
            assertTrue(RecipeWeaverPlugin.comparePreparedSqlReloadAsync(io, prepared, local)
                    .get(10, TimeUnit.SECONDS));
        }
    }
}
