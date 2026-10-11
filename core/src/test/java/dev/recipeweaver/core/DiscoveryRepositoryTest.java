package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DiscoveryRepositoryTest {
    @TempDir Path directory;

    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test
    void discoveriesPersistAndDuplicateUnlocksAreIdempotent() throws Exception {
        Plugin plugin = plugin();
        UUID player = UUID.randomUUID();
        NamespacedKey first = new NamespacedKey(plugin, "first");
        NamespacedKey second = new NamespacedKey(plugin, "second");
        DiscoveryRepository discoveries = new DiscoveryRepository(plugin);
        discoveries.load();
        assertFalse(discoveries.knows(player, first));
        assertTrue(discoveries.discover(player, first));
        assertFalse(discoveries.discover(player, first));
        assertFalse(discoveries.knows(player, second));

        DiscoveryRepository reloaded = new DiscoveryRepository(plugin);
        reloaded.load();
        assertTrue(reloaded.knows(player, first));
        assertFalse(reloaded.knows(player, second));
    }

    @Test
    void queuedDiscoveriesPersistInOrderWithoutLosingRapidUnlocks() throws Exception {
        Plugin plugin = plugin();
        UUID player = UUID.randomUUID();
        NamespacedKey first = new NamespacedKey(plugin, "async_first");
        NamespacedKey second = new NamespacedKey(plugin, "async_second");
        DiscoveryRepository discoveries = new DiscoveryRepository(plugin);
        discoveries.load();
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("RecipeWeaverAsyncDiscoveryTest");
        try (AsyncIoService io = new AsyncIoService(logger)) {
            discoveries.discoverAsync(player, first, io);
            discoveries.discoverAsync(player, second, io);
            io.flush().join();
            assertTrue(discoveries.knows(player, first));
            assertTrue(discoveries.knows(player, second));
            assertFalse(discoveries.discoverAsync(player, first, io).join());
        }
        DiscoveryRepository reloaded = new DiscoveryRepository(plugin);
        reloaded.load();
        assertTrue(reloaded.knows(player, first));
        assertTrue(reloaded.knows(player, second));
    }

    @Test
    void duplicateUnlockWaitsForThePendingDurableWrite() throws Exception {
        Plugin plugin = plugin();
        UUID player = UUID.randomUUID();
        NamespacedKey key = new NamespacedKey(plugin, "pending");
        DiscoveryRepository discoveries = new DiscoveryRepository(plugin);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (AsyncIoService io = new AsyncIoService(
                java.util.logging.Logger.getLogger("RecipeWeaverPendingDiscoveryTest"))) {
            io.submit(() -> {
                started.countDown();
                release.await();
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            CompletableFuture<Boolean> first = discoveries.discoverAsync(player, key, io);
            CompletableFuture<Boolean> duplicate = discoveries.discoverAsync(player, key, io);
            assertFalse(first.isDone());
            assertFalse(duplicate.isDone());
            assertFalse(discoveries.knows(player, key));
            release.countDown();
            assertTrue(first.join());
            assertFalse(duplicate.join());
            assertTrue(discoveries.knows(player, key));
        } finally {
            release.countDown();
        }
    }

    @Test
    void failedDiscoveryNeverBecomesDurablyKnown() throws Exception {
        Plugin plugin = plugin();
        UUID player = UUID.randomUUID();
        NamespacedKey key = new NamespacedKey(plugin, "failed_book_unlock");
        Files.writeString(directory.resolve("discoveries"), "not a directory");
        DiscoveryRepository discoveries = new DiscoveryRepository(plugin);
        assertThrows(IllegalStateException.class, () -> {
            try (AsyncIoService io = new AsyncIoService(
                    java.util.logging.Logger.getLogger("RecipeWeaverFailedDiscoveryTest"))) {
                CompletableFuture<Boolean> save = discoveries.discoverAsync(player, key, io);
                assertThrows(CompletionException.class, save::join);
                assertFalse(discoveries.knows(player, key));
            }
        });
    }

    @Test
    void invalidReloadKeepsPreviousDiscoveryState() throws Exception {
        Plugin plugin = plugin();
        UUID player = UUID.randomUUID();
        NamespacedKey key = new NamespacedKey(plugin, "known");
        DiscoveryRepository discoveries = new DiscoveryRepository(plugin);
        discoveries.discover(player, key);
        Files.writeString(directory.resolve("discoveries").resolve(player + ".yml"),
                "schema: 1\nrecipes: [invalid:namespace]\n");
        assertThrows(IOException.class, discoveries::load);
        assertTrue(discoveries.knows(player, key));
    }

    @Test
    void backupRestoreIncludesDiscoveries() throws Exception {
        Plugin plugin = plugin();
        UUID player = UUID.randomUUID();
        NamespacedKey first = new NamespacedKey(plugin, "first");
        NamespacedKey later = new NamespacedKey(plugin, "later");
        DiscoveryRepository discoveries = new DiscoveryRepository(plugin);
        discoveries.discover(player, first);
        BackupService backups = new BackupService(plugin, () -> { });
        String snapshot = backups.createBackup();
        discoveries.discover(player, later);
        backups.restore(snapshot);

        DiscoveryRepository restored = new DiscoveryRepository(plugin);
        restored.load();
        assertTrue(restored.knows(player, first));
        assertFalse(restored.knows(player, later));
    }

    @Test
    void bookItemUsesTagInsteadOfNameAndSettingSurvivesReload() throws Exception {
        Plugin plugin = plugin();
        ItemStack book = BookItemListener.create(plugin);
        assertTrue(BookItemListener.isBook(plugin, book));
        assertFalse(BookItemListener.isBook(plugin, new ItemStack(Material.BOOK)));
        ItemStack wrongType = book.clone();
        wrongType.setType(Material.PAPER);
        assertFalse(BookItemListener.isBook(plugin, wrongType));

        SettingsRepository settings = new SettingsRepository(plugin);
        settings.load();
        assertFalse(settings.bookItemEnabled());
        settings.setBookItemEnabled(true);
        settings.setBackupRetention(3);
        settings.setLockdown(true);
        SettingsRepository reloaded = new SettingsRepository(plugin);
        reloaded.load();
        assertTrue(reloaded.bookItemEnabled());
        assertTrue(reloaded.lockdown());
    }

    private Plugin plugin() {
        File root = directory.toFile();
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getDataFolder" -> root;
                    case "getName" -> "RecipeWeaver";
                    case "getLogger" -> java.util.logging.Logger.getLogger("RecipeWeaverDiscoveryTest");
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
