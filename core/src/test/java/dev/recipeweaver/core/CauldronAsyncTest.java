package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import be.seeseemelk.mockbukkit.MockBukkit;
import org.junit.jupiter.api.io.TempDir;

final class CauldronAsyncTest {
    @TempDir Path directory;

    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test
    void writeWaitsForWorkerAndPublishesOnlyAfterDurableSaveOnMainCallback() throws Exception {
        ExecutorService main = Executors.newSingleThreadExecutor(task ->
                new Thread(task, "RecipeWeaver-Test-Main"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> publicationThread = new AtomicReference<>();
        Location location = location();
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("CauldronAsyncTest"))) {
            CauldronRepository cauldrons = new CauldronRepository(directory, () -> false,
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
            CompletableFuture<Void> saved = cauldrons.putAsync(
                    location, new ItemStack(Material.STONE), io);
            assertTrue(cauldrons.pendingWrite());
            assertTrue(cauldrons.isProtected(location));
            assertNull(cauldrons.get(location));
            assertFalse(saved.isDone());
            assertThrows(IOException.class, () -> cauldrons.removeAsync(location, io));
            release.countDown();
            saved.get(10, TimeUnit.SECONDS);
            assertEquals("RecipeWeaver-Test-Main", publicationThread.get());
            assertFalse(cauldrons.pendingWrite());
            assertEquals(Material.STONE, cauldrons.get(location).getType());
            assertTrue(Files.readString(directory.resolve("cauldrons.yml")).contains("STONE"));

            CompletableFuture<ItemStack> removed = cauldrons.removeAsync(location, io);
            assertEquals(Material.STONE, removed.get(10, TimeUnit.SECONDS).getType());
            assertNull(cauldrons.get(location));
            assertFalse(Files.readString(directory.resolve("cauldrons.yml")).contains("STONE"));
        } finally {
            release.countDown();
            main.shutdown();
            assertTrue(main.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shutdownDrainCompletesDurableWriteBeforeScheduledCallbackRuns() throws Exception {
        var callbacks = new ConcurrentLinkedQueue<Runnable>();
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("CauldronShutdownTest"))) {
            CauldronRepository cauldrons = new CauldronRepository(
                    directory, () -> false, callbacks::add);
            Location location = location();
            CompletableFuture<Void> saved = cauldrons.putAsync(
                    location, new ItemStack(Material.STONE), io);
            io.flush().join();
            assertFalse(saved.isDone());
            assertTrue(cauldrons.pendingWrite());
            cauldrons.completePendingOnShutdown();
            saved.join();
            assertEquals(Material.STONE, cauldrons.get(location).getType());
            callbacks.forEach(Runnable::run);
            assertFalse(cauldrons.pendingWrite());
            assertEquals(Material.STONE, cauldrons.get(location).getType());
        }
    }

    @Test
    void failedWriteKeepsPriorStateAndClearsPendingFence() throws Exception {
        Path target = directory.resolve("cauldrons.yml");
        Files.createDirectory(target);
        Files.writeString(target.resolve("occupied"), "prevent replacement");
        ExecutorService main = Executors.newSingleThreadExecutor(task ->
                new Thread(task, "RecipeWeaver-Test-Main"));
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("CauldronFailureTest"))) {
            CauldronRepository cauldrons = new CauldronRepository(directory, () -> false, main::execute);
            Location location = location();
            CompletableFuture<Void> saved = cauldrons.putAsync(
                    location, new ItemStack(Material.STONE), io);
            assertThrows(CompletionException.class, saved::join);
            assertFalse(cauldrons.pendingWrite());
            assertFalse(cauldrons.isProtected(location));
            assertNull(cauldrons.get(location));
            assertTrue(Files.isDirectory(target));
            assertThrows(CompletionException.class, () -> io.flush().join());
        } finally {
            main.shutdown();
            assertTrue(main.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Location location() {
        return new Location(MockBukkit.getMock().addSimpleWorld("cauldron-test"), 8, 65, -3);
    }
}
