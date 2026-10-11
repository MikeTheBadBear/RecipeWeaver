package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.GridTier;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StationGridAsyncRepositoryTest {
    @TempDir Path directory;

    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test
    void rapidStationUpdatesAndDeletePersistInOrder() throws Exception {
        Plugin plugin = plugin();
        Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        StationRecipeRepository stations = new StationRecipeRepository(plugin, directory, callbacks::add);
        NamespacedKey key = new NamespacedKey(plugin, "station_ordered");
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("RecipeWeaverStationAsyncTest"))) {
            CompletableFuture<Void> first = stations.saveAsync(station(key, Material.DIAMOND), io, () -> { });
            CompletableFuture<Void> second = stations.saveAsync(station(key, Material.EMERALD), io, () -> { });
            assertThrows(IllegalStateException.class, () -> stations.save(station(key, Material.GOLD_INGOT)));
            io.flush().join();
            assertFalse(first.isDone());
            drainReversed(callbacks);
            first.join();
            second.join();
            assertEquals(Material.EMERALD, stations.get(key).orElseThrow().result().getType());
            assertTrue(Files.isRegularFile(pathFor("stations", key)));

            CompletableFuture<Boolean> removed = stations.removeAsync(key, io, () -> { });
            io.flush().join();
            drain(callbacks);
            assertTrue(removed.join());
            assertTrue(stations.get(key).isEmpty());
            assertFalse(Files.exists(pathFor("stations", key)));
        }
    }

    @Test
    void failedGridUpdateRestoresTheLastDurableGrid() throws Exception {
        Plugin plugin = plugin();
        Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        GridRecipeRepository grids = new GridRecipeRepository(plugin, directory, callbacks::add);
        NamespacedKey key = new NamespacedKey(plugin, "grid_rollback");
        AtomicInteger rollbacks = new AtomicInteger();
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("RecipeWeaverGridAsyncTest"))) {
            CompletableFuture<Void> first = grids.saveAsync(grid(key, Material.DIAMOND), io, rollbacks::incrementAndGet);
            io.flush().join();
            Path path = pathFor("grid-recipes", key);
            Files.delete(path);
            Files.createDirectory(path);
            CompletableFuture<Void> failed = grids.saveAsync(grid(key, Material.EMERALD), io, rollbacks::incrementAndGet);
            assertThrows(CompletionException.class, () -> io.flush().join());
            drainReversed(callbacks);
            first.join();
            assertThrows(CompletionException.class, failed::join);
            assertEquals(Material.DIAMOND, grids.get(key).orElseThrow().result().getType());
            assertEquals(1, rollbacks.get());
        }
    }

    private static StationRecipeDefinition station(NamespacedKey key, Material result) {
        return new StationRecipeDefinition(key, StationKind.FURNACE, List.of(new ItemStack(Material.STONE)),
                new ItemStack(result), "", 200, 0, 1, true, false);
    }

    private static GridRecipeDefinition grid(NamespacedKey key, Material result) {
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[4]));
        ingredients.set(0, new ItemStack(Material.STONE, 2));
        return new GridRecipeDefinition(key, GridTier.ELITE, RecipeKind.SHAPED, 2, ingredients,
                new ItemStack(result), "", true, false);
    }

    private Path pathFor(String source, NamespacedKey key) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.toString().getBytes(StandardCharsets.UTF_8));
        return directory.resolve(source).resolve(encoded + ".yml");
    }

    private static void drain(Queue<Runnable> callbacks) {
        Runnable callback;
        while ((callback = callbacks.poll()) != null) callback.run();
    }

    private static void drainReversed(Queue<Runnable> callbacks) {
        List<Runnable> reversed = new ArrayList<>(callbacks);
        callbacks.clear();
        java.util.Collections.reverse(reversed);
        reversed.forEach(Runnable::run);
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
