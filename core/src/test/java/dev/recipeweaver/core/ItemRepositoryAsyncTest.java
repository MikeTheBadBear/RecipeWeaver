package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ItemRepositoryAsyncTest {
    @TempDir Path directory;

    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test
    void rapidWritesPublishOnMainCallbackAndPersistInOrder() throws Exception {
        Plugin plugin = plugin();
        Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        ItemRepository items = new ItemRepository(plugin, directory, callbacks::add);
        NamespacedKey first = new NamespacedKey(plugin, "first");
        NamespacedKey second = new NamespacedKey(plugin, "second");
        ItemStack stack = new ItemStack(Material.DIAMOND, 2);
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("RecipeWeaverItemAsyncTest"))) {
            CompletableFuture<Void> firstWrite = items.saveAsync(first, stack, io);
            stack.setAmount(3);
            CompletableFuture<Void> secondWrite = items.saveAsync(second, stack, io);
            assertThrows(IllegalStateException.class, () -> items.save(first, stack));
            io.flush().join();
            assertFalse(firstWrite.isDone());
            assertFalse(secondWrite.isDone());
            assertTrue(items.get(first).isEmpty());
            assertTrue(Files.readString(directory.resolve("items.yml")).contains("schema: 2"));
            drain(callbacks);
            assertTrue(firstWrite.isDone());
            assertTrue(secondWrite.isDone());
            assertEquals(2, items.get(first).orElseThrow().getAmount());
            assertEquals(3, items.get(second).orElseThrow().getAmount());

            CompletableFuture<Boolean> removal = items.removeAsync(first, io);
            io.flush().join();
            drain(callbacks);
            assertTrue(removal.join());
            assertTrue(items.get(first).isEmpty());
            assertTrue(items.get(second).isPresent());
        }
    }

    @Test
    void compassTrackingIsSerializedExplicitlyAndSchemaOneStillLoads() throws Exception {
        Plugin plugin = plugin();
        NamespacedKey id = new NamespacedKey(plugin, "tracked_compass");
        ItemStack compass = new ItemStack(Material.COMPASS);
        CompassMeta meta = (CompassMeta) compass.getItemMeta();
        meta.setLodestoneTracked(true);
        compass.setItemMeta(meta);
        ItemRepository items = new ItemRepository(plugin, directory);
        items.save(id, compass);
        String saved = Files.readString(directory.resolve("items.yml"));
        assertTrue(saved.contains("schema: 2"));
        assertTrue(saved.contains("compass-tracking:"));
        assertTrue(saved.contains("true"));
        // MockBukkit cannot deserialize CompassMetaMock from its own YAML;
        // the six-server probe checks the saved item round trip.
        Files.writeString(directory.resolve("items.yml"), "schema: 1\nitems: {}\n");
        ItemRepository reloaded = new ItemRepository(plugin, directory);
        reloaded.load();
        assertEquals(0, reloaded.size());
    }

    @Test
    void mapScalingIsSerializedExplicitly() throws Exception {
        Plugin plugin = plugin();
        NamespacedKey id = new NamespacedKey(plugin, "scaling_map");
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) map.getItemMeta();
        meta.setScaling(true);
        map.setItemMeta(meta);
        new ItemRepository(plugin, directory).save(id, map);
        String saved = Files.readString(directory.resolve("items.yml"));
        assertTrue(saved.contains("schema: 2"));
        assertTrue(saved.contains("map-scaling:"));
        assertTrue(saved.contains("true"));
    }

    private static void drain(Queue<Runnable> callbacks) {
        Runnable callback;
        while ((callback = callbacks.poll()) != null) callback.run();
    }

    private Plugin plugin() {
        File root = directory.toFile();
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getDataFolder" -> root;
                    case "getName" -> "RecipeWeaver";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
