package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.RecipeKind;
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

final class RecipeRepositoryAsyncTest {
    @TempDir Path directory;

    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test
    void rapidWritesAndDeleteKeepTheNewestRecipeEvenIfCallbacksArriveOutOfOrder() throws Exception {
        Plugin plugin = plugin();
        Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        RecipeRepository recipes = new RecipeRepository(plugin, directory, callbacks::add);
        NamespacedKey key = new NamespacedKey(plugin, "ordered");
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("RecipeWeaverRecipeAsyncTest"))) {
            CompletableFuture<Void> first = recipes.saveAsync(recipe(key, Material.DIAMOND), io, () -> { });
            CompletableFuture<Void> second = recipes.saveAsync(recipe(key, Material.EMERALD), io, () -> { });
            assertThrows(IllegalStateException.class, () -> recipes.save(recipe(key, Material.GOLD_INGOT)));
            assertEquals(Material.EMERALD, recipes.get(key).orElseThrow().result().getType());
            io.flush().join();
            assertFalse(first.isDone());
            assertFalse(second.isDone());
            List<Runnable> reversed = new ArrayList<>(callbacks);
            callbacks.clear();
            java.util.Collections.reverse(reversed);
            reversed.forEach(Runnable::run);
            first.join();
            second.join();
            assertEquals(Material.EMERALD, recipes.get(key).orElseThrow().result().getType());

            CompletableFuture<Boolean> removed = recipes.removeAsync(key, io, () -> { });
            io.flush().join();
            drain(callbacks);
            assertTrue(removed.join());
            assertTrue(recipes.get(key).isEmpty());
            assertFalse(Files.exists(pathFor(key)));
        }
    }

    @Test
    void failedLatestWriteRestoresLastDurableRecipeAndCallsRollback() throws Exception {
        Plugin plugin = plugin();
        Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        RecipeRepository recipes = new RecipeRepository(plugin, directory, callbacks::add);
        NamespacedKey key = new NamespacedKey(plugin, "rollback");
        AtomicInteger rollbacks = new AtomicInteger();
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("RecipeWeaverRecipeFailureTest"))) {
            CompletableFuture<Void> first = recipes.saveAsync(recipe(key, Material.DIAMOND), io, rollbacks::incrementAndGet);
            io.flush().join();
            Files.delete(pathFor(key));
            Files.createDirectory(pathFor(key));
            CompletableFuture<Void> failed = recipes.saveAsync(
                    recipe(key, Material.EMERALD), io, rollbacks::incrementAndGet);
            assertThrows(CompletionException.class, () -> io.flush().join());
            List<Runnable> reversed = new ArrayList<>(callbacks);
            callbacks.clear();
            java.util.Collections.reverse(reversed);
            reversed.get(0).run();
            assertFalse(failed.isDone());
            reversed.get(1).run();
            first.join();
            assertThrows(CompletionException.class, failed::join);
            assertEquals(Material.DIAMOND, recipes.get(key).orElseThrow().result().getType());
            assertEquals(1, rollbacks.get());
        }
    }

    private static RecipeDefinition recipe(NamespacedKey key, Material result) {
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        ingredients.set(0, new ItemStack(Material.STONE));
        return new RecipeDefinition(key, RecipeKind.SHAPELESS, ingredients,
                new ItemStack(result), "", true, false);
    }

    private Path pathFor(NamespacedKey key) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.toString().getBytes(StandardCharsets.UTF_8));
        return directory.resolve("recipes").resolve(encoded + ".yml");
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
