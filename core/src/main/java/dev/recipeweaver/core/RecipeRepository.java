package dev.recipeweaver.core;

import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.WeatherCondition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import org.bukkit.plugin.Plugin;
import org.bukkit.configuration.file.YamlConfiguration;

final class RecipeRepository {
    private static final int SCHEMA = 4;
    private final Plugin plugin;
    private final Path directory;
    private final Map<NamespacedKey, RecipeDefinition> recipes = new LinkedHashMap<>();
    private final OrderedRecipeWrites<RecipeDefinition> writes;

    RecipeRepository(Plugin plugin) { this(plugin, plugin.getDataFolder().toPath()); }

    RecipeRepository(Plugin plugin, Path dataRoot) {
        this(plugin, dataRoot, action -> Bukkit.getScheduler().runTask(plugin, action));
    }

    RecipeRepository(Plugin plugin, Path dataRoot, Consumer<Runnable> mainCallback) {
        this.plugin = plugin;
        this.directory = dataRoot.resolve("recipes");
        this.writes = new OrderedRecipeWrites<>(recipes, mainCallback);
    }

    void load() throws IOException { LegacyIoGuard.testOnly(plugin); load(DiskSnapshot.read(directory.getParent())); }

    void load(DiskSnapshot snapshot) throws IOException {
        if (writes.hasPending()) {
            throw new IOException("Recipe writes are still pending");
        }
        Map<NamespacedKey, RecipeDefinition> loaded = new LinkedHashMap<>();
        for (String name : snapshot.directory("recipes").keySet()) {
            if (!name.endsWith(".yml")) continue;
            try {
                YamlConfiguration config = snapshot.yaml("recipes/" + name);
                int schema = config.getInt("schema");
                if (schema < 1 || schema > SCHEMA) throw new IllegalArgumentException("Unsupported schema");
                NamespacedKey key = NamespacedKey.fromString(config.getString("id", ""), plugin);
                if (key == null) throw new IllegalArgumentException("Invalid id");
                RecipeKind kind = RecipeKind.valueOf(config.getString("kind", "SHAPED"));
                List<ItemStack> ingredients = new ArrayList<>(9);
                List<IngredientMatchMode> modes = new ArrayList<>(9);
                List<List<ItemStack>> alternatives = new ArrayList<>(9);
                List<NamespacedKey> tags = new ArrayList<>(9);
                for (int slot = 0; slot < 9; slot++) {
                    ingredients.add(config.getItemStack("ingredients." + slot));
                    modes.add(schema == 1 ? IngredientMatchMode.EXACT : IngredientMatchMode.valueOf(
                            config.getString("match-modes." + slot, IngredientMatchMode.EXACT.name())));
                    String choicesPath = "alternatives." + slot;
                    if (schema >= 2 && config.contains(choicesPath) && !config.isList(choicesPath)) {
                        throw new IllegalArgumentException("Invalid alternatives for slot " + slot);
                    }
                    List<ItemStack> choices = new ArrayList<>();
                    if (schema >= 2) {
                        for (Object value : config.getList(choicesPath, List.of())) {
                            if (!(value instanceof ItemStack item)) {
                                throw new IllegalArgumentException("Invalid alternative for slot " + slot);
                            }
                            choices.add(item);
                        }
                    }
                    alternatives.add(choices);
                    String rawTag = schema >= 2 ? config.getString("item-tags." + slot) : null;
                    if (rawTag != null && !rawTag.contains(":")) {
                        throw new IllegalArgumentException("Item tag needs a namespace for slot " + slot);
                    }
                    NamespacedKey tag = rawTag == null ? null : NamespacedKey.fromString(rawTag);
                    if (rawTag != null && tag == null) {
                        throw new IllegalArgumentException("Invalid item tag for slot " + slot);
                    }
                    tags.add(tag);
                }
                ItemStack result = config.getItemStack("result");
                RecipeDefinition recipe = new RecipeDefinition(key, kind, ingredients, result,
                        config.getString("group", ""), config.getBoolean("enabled", true),
                        config.getBoolean("hidden", false), modes, alternatives, tags,
                        schema < 3 ? CraftingBookCategory.MISC : CraftingBookCategory.valueOf(
                                config.getString("book-category", CraftingBookCategory.MISC.name())),
                        schema < 4 ? RecipeConditions.NONE : new RecipeConditions(
                                readString(config, "conditions.permission"),
                                readString(config, "conditions.world"),
                                readKey(config, "conditions.biome"),
                                readInt(config, "conditions.time-start", -1),
                                readInt(config, "conditions.time-end", -1),
                                WeatherCondition.valueOf(config.contains("conditions.weather")
                                        ? readString(config, "conditions.weather") : "ANY"),
                                readInt(config, "conditions.minimum-level", 0),
                                readString(config, "conditions.scoreboard-objective"),
                                readInt(config, "conditions.minimum-score", 0),
                                readKey(config, "conditions.advancement")));
                if (loaded.putIfAbsent(key, recipe) != null) throw new IllegalArgumentException("Duplicate id");
            } catch (Exception ex) {
                throw new IOException("Invalid recipe file " + name + ": " + ex.getMessage(), ex);
            }
        }
        recipes.clear();
        recipes.putAll(loaded);
        writes.clear();
    }

    Collection<RecipeDefinition> all() { return List.copyOf(recipes.values()); }
    Optional<RecipeDefinition> get(NamespacedKey key) { return Optional.ofNullable(recipes.get(key)); }

    void save(RecipeDefinition recipe) throws IOException {
        LegacyIoGuard.testOnly(plugin);
        writes.requireNoPendingWrite(recipe.key());
        writeAtomically(pathFor(recipe.key()), serialize(recipe));
        recipes.put(recipe.key(), recipe);
        writes.forget(recipe.key());
    }

    boolean remove(NamespacedKey key) throws IOException {
        LegacyIoGuard.testOnly(plugin);
        writes.requireNoPendingWrite(key);
        if (!recipes.containsKey(key)) return false;
        Files.deleteIfExists(pathFor(key));
        recipes.remove(key);
        writes.forget(key);
        return true;
    }

    CompletableFuture<Void> saveAsync(RecipeDefinition recipe, AsyncIoService io, Runnable onRollback) {
        String yaml = serialize(recipe);
        return writes.saveAsync(recipe.key(), recipe, io,
                () -> io.writeDocument(pathFor(recipe.key()), yaml), onRollback);
    }

    CompletableFuture<Boolean> removeAsync(NamespacedKey key, AsyncIoService io, Runnable onRollback) {
        return writes.removeAsync(key, io, () -> io.deleteDocument(pathFor(key)), onRollback);
    }

    private static String serialize(RecipeDefinition recipe) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("schema", SCHEMA);
        config.set("id", recipe.key().toString());
        config.set("kind", recipe.kind().name());
        config.set("group", recipe.group());
        config.set("book-category", recipe.bookCategory().name());
        config.set("enabled", recipe.enabled());
        config.set("hidden", recipe.hidden());
        RecipeConditions conditions = recipe.conditions();
        if (conditions.permission() != null) config.set("conditions.permission", conditions.permission());
        if (conditions.world() != null) config.set("conditions.world", conditions.world());
        if (conditions.biome() != null) config.set("conditions.biome", conditions.biome().toString());
        if (conditions.timeStart() >= 0) {
            config.set("conditions.time-start", conditions.timeStart());
            config.set("conditions.time-end", conditions.timeEnd());
        }
        if (conditions.weather() != WeatherCondition.ANY) {
            config.set("conditions.weather", conditions.weather().name());
        }
        if (conditions.minimumLevel() > 0) config.set("conditions.minimum-level", conditions.minimumLevel());
        if (conditions.scoreboardObjective() != null) {
            config.set("conditions.scoreboard-objective", conditions.scoreboardObjective());
            config.set("conditions.minimum-score", conditions.minimumScore());
        }
        if (conditions.advancement() != null) {
            config.set("conditions.advancement", conditions.advancement().toString());
        }
        List<ItemStack> ingredients = recipe.ingredients();
        List<List<ItemStack>> alternatives = recipe.alternatives();
        for (int slot = 0; slot < 9; slot++) {
            config.set("ingredients." + slot, ingredients.get(slot));
            config.set("match-modes." + slot, recipe.matchModes().get(slot).name());
            config.set("alternatives." + slot, alternatives.get(slot));
            NamespacedKey tag = recipe.itemTags().get(slot);
            if (tag != null) config.set("item-tags." + slot, tag.toString());
        }
        config.set("result", recipe.result());
        return config.saveToString();
    }

    private static String readString(YamlConfiguration config, String path) {
        if (!config.contains(path)) return null;
        if (!config.isString(path)) throw new IllegalArgumentException("Invalid " + path);
        return config.getString(path);
    }

    private static int readInt(YamlConfiguration config, String path, int fallback) {
        if (!config.contains(path)) return fallback;
        if (!config.isInt(path)) throw new IllegalArgumentException("Invalid " + path);
        return config.getInt(path);
    }

    private static NamespacedKey readKey(YamlConfiguration config, String path) {
        String value = readString(config, path);
        if (value == null) return null;
        NamespacedKey key = value.contains(":") ? NamespacedKey.fromString(value) : null;
        if (key == null || !value.equals(key.toString())) {
            throw new IllegalArgumentException("Invalid " + path);
        }
        return key;
    }

    private Path pathFor(NamespacedKey key) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.toString().getBytes(StandardCharsets.UTF_8));
        return directory.resolve(encoded + ".yml");
    }

    static void writeAtomically(Path destination, String content) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), ".recipeweaver-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            for (int attempt = 0; ; attempt++) {
                try {
                    try {
                        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException ignored) {
                        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                    break;
                } catch (AccessDeniedException ex) {
                    if (attempt == 19) throw ex;
                    try { Thread.sleep(50); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while replacing " + destination, interrupted);
                    }
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
