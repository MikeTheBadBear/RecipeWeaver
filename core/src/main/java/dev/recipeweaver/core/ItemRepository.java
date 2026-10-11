package dev.recipeweaver.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.plugin.Plugin;

final class ItemRepository {
    private final Plugin plugin;
    private final Path path;
    private final Consumer<Runnable> mainCallback;
    private final Map<NamespacedKey, ItemStack> items = new LinkedHashMap<>();
    private Map<NamespacedKey, ItemStack> pendingItems = new LinkedHashMap<>();
    private long revision;
    private long committedRevision;
    private int pendingWrites;

    ItemRepository(Plugin plugin) { this(plugin, plugin.getDataFolder().toPath()); }

    ItemRepository(Plugin plugin, Path dataRoot) {
        this(plugin, dataRoot, action -> Bukkit.getScheduler().runTask(plugin, action));
    }

    ItemRepository(Plugin plugin, Path dataRoot, Consumer<Runnable> mainCallback) {
        this.plugin = plugin;
        this.path = dataRoot.resolve("items.yml");
        this.mainCallback = mainCallback;
    }

    void load() throws IOException { LegacyIoGuard.testOnly(plugin); load(DiskSnapshot.read(path.getParent())); }

    void load(DiskSnapshot snapshot) throws IOException {
        Map<NamespacedKey, ItemStack> loaded = new LinkedHashMap<>();
        YamlConfiguration config = snapshot.yaml("items.yml");
        if (config != null) {
            int schema = config.getInt("schema");
            if (schema != 1 && schema != 2) throw new IOException("Unsupported item schema");
            if (config.getConfigurationSection("items") != null) {
                for (String encoded : config.getConfigurationSection("items").getKeys(false)) {
                    String id;
                    try { id = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8); }
                    catch (IllegalArgumentException ex) { throw new IOException("Malformed item ID: " + encoded, ex); }
                    NamespacedKey key = NamespacedKey.fromString(id, plugin);
                    ItemStack item = config.getItemStack("items." + encoded);
                    if (key == null || !key.getNamespace().equals("recipeweaver")
                            || item == null || item.getType().isAir()) {
                        throw new IOException("Invalid custom item: " + id);
                    }
                    String trackingPath = "compass-tracking." + encoded;
                    if (schema == 2 && config.contains(trackingPath)) {
                        if (!config.isBoolean(trackingPath)
                                || !(item.getItemMeta() instanceof CompassMeta compass)) {
                            throw new IOException("Invalid compass tracking for custom item: " + id);
                        }
                        compass.setLodestoneTracked(config.getBoolean(trackingPath));
                        item.setItemMeta(compass);
                    }
                    String scalingPath = "map-scaling." + encoded;
                    if (schema == 2 && config.contains(scalingPath)) {
                        if (!config.isBoolean(scalingPath)
                                || !(item.getItemMeta() instanceof MapMeta map)) {
                            throw new IOException("Invalid map scaling for custom item: " + id);
                        }
                        map.setScaling(config.getBoolean(scalingPath));
                        item.setItemMeta(map);
                    }
                    String generationPath = "book-generation." + encoded;
                    if (schema == 2 && config.contains(generationPath)) {
                        if (!config.isString(generationPath)
                                || !(item.getItemMeta() instanceof BookMeta book)) {
                            throw new IOException("Invalid book generation for custom item: " + id);
                        }
                        BookMeta.Generation expected;
                        try {
                            expected = BookMeta.Generation.valueOf(config.getString(generationPath));
                            book.setGeneration(expected);
                        } catch (IllegalArgumentException ex) {
                            throw new IOException("Invalid book generation for custom item: " + id, ex);
                        }
                        item.setItemMeta(book);
                    }
                    var savedEffects = config.getConfigurationSection("food-consume-effects." + encoded);
                    if (schema == 2 && savedEffects != null) {
                        if (!item.getItemMeta().hasFood() || savedEffects.getKeys(false).size() > 64) {
                            throw new IOException("Invalid food effects for custom item: " + id);
                        }
                        for (String index : savedEffects.getKeys(false)) {
                            String effectPath = "food-consume-effects." + encoded + "." + index;
                            Object effect = config.get(effectPath + ".effect");
                            Object probability = config.get(effectPath + ".probability");
                            if (!(effect instanceof PotionEffect potion)
                                    || !(probability instanceof Number number)
                                    || !Float.isFinite(number.floatValue())
                                    || number.floatValue() < 0 || number.floatValue() > 1) {
                                throw new IOException("Invalid food effect for custom item: " + id);
                            }
                            FoodComponentBridge.restoreEffect(item, potion, number.floatValue());
                        }
                    }
                    if (loaded.putIfAbsent(key, item.clone()) != null) throw new IOException("Duplicate item ID: " + id);
                }
            }
        }
        items.clear();
        items.putAll(loaded);
        pendingItems = new LinkedHashMap<>(loaded);
        revision = 0;
        committedRevision = 0;
        pendingWrites = 0;
    }

    void save(NamespacedKey key, ItemStack item) throws IOException {
        LegacyIoGuard.testOnly(plugin);
        validate(key, item);
        requireNoPendingWrite();
        Map<NamespacedKey, ItemStack> next = new LinkedHashMap<>(items);
        next.put(key, item.clone());
        RecipeRepository.writeAtomically(path, serialize(next));
        commitSync(next);
    }

    CompletableFuture<Void> saveAsync(NamespacedKey key, ItemStack item, AsyncIoService io) {
        validate(key, item);
        Map<NamespacedKey, ItemStack> next = new LinkedHashMap<>(pendingItems);
        next.put(key, item.clone());
        return enqueue(next, io);
    }

    List<NamespacedKey> keys() { return List.copyOf(items.keySet()); }

    boolean containsPending(NamespacedKey key) { return pendingItems.containsKey(key); }

    boolean remove(NamespacedKey key) throws IOException {
        LegacyIoGuard.testOnly(plugin);
        requireNoPendingWrite();
        if (!items.containsKey(key)) return false;
        Map<NamespacedKey, ItemStack> next = new LinkedHashMap<>(items);
        next.remove(key);
        RecipeRepository.writeAtomically(path, serialize(next));
        commitSync(next);
        return true;
    }

    CompletableFuture<Boolean> removeAsync(NamespacedKey key, AsyncIoService io) {
        if (!pendingItems.containsKey(key)) return CompletableFuture.completedFuture(false);
        Map<NamespacedKey, ItemStack> next = new LinkedHashMap<>(pendingItems);
        next.remove(key);
        return enqueue(next, io).thenApply(ignored -> true);
    }

    Optional<ItemStack> get(NamespacedKey key) {
        ItemStack item = items.get(key);
        return item == null ? Optional.empty() : Optional.of(item.clone());
    }

    int size() { return items.size(); }

    private CompletableFuture<Void> enqueue(Map<NamespacedKey, ItemStack> next, AsyncIoService io) {
        // Bukkit ItemStack/YAML serialization stays on the caller's server thread.
        String yaml = serialize(next);
        long submitted = ++revision;
        pendingItems = next;
        pendingWrites++;
        CompletableFuture<Void> result = new CompletableFuture<>();
        io.submit(() -> {
            io.writeDocument(path, yaml);
            return null;
        }).whenComplete((ignored, failure) -> {
            try {
                mainCallback.accept(() -> {
                    pendingWrites--;
                    if (failure == null && submitted > committedRevision) {
                        items.clear();
                        items.putAll(next);
                        committedRevision = submitted;
                    }
                    if (pendingWrites == 0) pendingItems = new LinkedHashMap<>(items);
                    if (failure == null) result.complete(null);
                    else result.completeExceptionally(failure);
                });
            } catch (RuntimeException callbackFailure) {
                result.completeExceptionally(callbackFailure);
            }
        });
        return result;
    }

    private void commitSync(Map<NamespacedKey, ItemStack> next) {
        items.clear();
        items.putAll(next);
        pendingItems = new LinkedHashMap<>(next);
    }

    private void requireNoPendingWrite() {
        if (pendingWrites != 0) throw new IllegalStateException("Custom item write is still pending");
    }

    private static void validate(NamespacedKey key, ItemStack item) {
        if (key == null || !key.getNamespace().equals("recipeweaver")) {
            throw new IllegalArgumentException("Custom item IDs must use recipeweaver namespace");
        }
        if (item == null || item.getType().isAir()) throw new IllegalArgumentException("Item is required");
    }

    private static String serialize(Map<NamespacedKey, ItemStack> values) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("schema", 2);
        values.forEach((id, stack) -> {
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(id.toString().getBytes(StandardCharsets.UTF_8));
            ItemStack stored = stack;
            if (FoodComponentBridge.modern() && stack.getItemMeta().hasFood()) {
                List<FoodComponentBridge.StoredEffect> effects =
                        FoodComponentBridge.storedEffects(stack);
                if (!effects.isEmpty()) {
                    stored = stack.clone();
                    FoodComponentBridge.clearStatusEffects(stored);
                    for (int index = 0; index < effects.size(); index++) {
                        String effectPath = "food-consume-effects." + encoded + "." + index;
                        config.set(effectPath + ".effect", effects.get(index).effect());
                        config.set(effectPath + ".probability", effects.get(index).probability());
                    }
                }
            }
            config.set("items." + encoded, stored);
            if (stack.getItemMeta() instanceof CompassMeta compass) {
                // Spigot 1.20.6 omits true from CompassMeta YAML and reloads it as false.
                config.set("compass-tracking." + encoded, compass.isLodestoneTracked());
            }
            if (stack.getItemMeta() instanceof MapMeta map) {
                // Paper 26.2 omits scaling from its item YAML.
                config.set("map-scaling." + encoded, map.isScaling());
            }
            if (stack.getItemMeta() instanceof BookMeta book && book.getGeneration() != null) {
                // Older Bukkit item YAML drops the written-book generation.
                config.set("book-generation." + encoded, book.getGeneration().name());
            }
        });
        return config.saveToString();
    }
}
