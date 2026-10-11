package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.recipeweaver.api.GridTier;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TableAsyncTest {
    @TempDir Path directory;
    // Location retains its World weakly; keep the test proxy alive through each transaction.
    private World world;

    @Test
    void tableWriteWaitsForOrderedWorkerAndPublishesCompletionOnMainCallback() throws Exception {
        ExecutorService main = Executors.newSingleThreadExecutor(task ->
                new Thread(task, "RecipeWeaver-Test-Main"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> publicationThread = new AtomicReference<>();
        Location location = location();
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("RecipeWeaverTableTest"))) {
            TableRepository tables = new TableRepository(directory, () -> false,
                    action -> main.execute(() -> {
                        publicationThread.set(Thread.currentThread().getName());
                        action.run();
                    }));
            io.submit(() -> {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Worker did not resume");
                return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> saved = tables.putAsync(location, GridTier.ELITE, io);
            assertEquals(GridTier.ELITE, tables.get(location));
            assertTrue(tables.pendingWrite());
            assertFalse(saved.isDone());
            assertThrows(IOException.class, () -> tables.removeAsync(location, io));
            release.countDown();
            saved.get(10, TimeUnit.SECONDS);
            assertEquals("RecipeWeaver-Test-Main", publicationThread.get());
            assertFalse(tables.pendingWrite());
            TableRepository fromDisk = new TableRepository(directory);
            fromDisk.load();
            assertEquals(GridTier.ELITE, fromDisk.get(location));
            assertTrue(tables.removeAsync(location, io).get(10, TimeUnit.SECONDS));
            fromDisk.load();
            assertNull(fromDisk.get(location));
        } finally {
            release.countDown();
            main.shutdown();
            assertTrue(main.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shutdownDrainCompletesTableWriteBeforeScheduledCallbackRuns() throws Exception {
        var callbacks = new ConcurrentLinkedQueue<Runnable>();
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("TableShutdownTest"))) {
            TableRepository tables = new TableRepository(directory, () -> false, callbacks::add);
            Location location = location();
            CompletableFuture<Void> saved = tables.putAsync(location, GridTier.ELITE, io);
            io.flush().join();
            assertFalse(saved.isDone());
            assertTrue(tables.pendingWrite());
            tables.completePendingOnShutdown();
            saved.join();
            callbacks.forEach(Runnable::run);
            assertFalse(tables.pendingWrite());
            assertEquals(GridTier.ELITE, tables.get(location));
        }
    }

    @Test
    void failedTableWriteRestoresPreviousInMemoryState() throws Exception {
        Path target = directory.resolve("tables.yml");
        Files.createDirectory(target);
        Files.writeString(target.resolve("occupied"), "prevent replacement");
        ExecutorService main = Executors.newSingleThreadExecutor(task ->
                new Thread(task, "RecipeWeaver-Test-Main"));
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("RecipeWeaverTableFailureTest"))) {
            TableRepository tables = new TableRepository(directory, () -> false, main::execute);
            Location location = location();
            CompletableFuture<Void> saved = tables.putAsync(location, GridTier.ADVANCED, io);
            assertThrows(CompletionException.class, saved::join);
            assertFalse(tables.pendingWrite());
            assertNull(tables.get(location));
            assertTrue(Files.isDirectory(target));
            assertThrows(CompletionException.class, () -> io.flush().join());
        } finally {
            main.shutdown();
            assertTrue(main.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Location location() {
        world = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> method.getName().equals("getUID")
                        ? UUID.fromString("00000000-0000-0000-0000-000000000126") : null);
        return new Location(world, 8, 65, -3);
    }
}
