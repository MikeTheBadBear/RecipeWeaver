package dev.recipeweaver.core;

import dev.recipeweaver.api.CraftAdvancementAction;
import dev.recipeweaver.api.CraftCommandAction;
import dev.recipeweaver.api.CraftCommandSender;
import dev.recipeweaver.api.CraftSoundAction;
import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.GridTier;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.ResultMetadataMode;
import dev.recipeweaver.api.WeightedResult;
import dev.recipeweaver.api.WeatherCondition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Versioned local storage for advanced and elite crafting recipes. */
final class GridRecipeRepository {
    private static final int SCHEMA = 14;
    private final Plugin plugin;
    private final Path directory;
    private final Map<NamespacedKey, GridRecipeDefinition> recipes = new LinkedHashMap<>();
    private final OrderedRecipeWrites<GridRecipeDefinition> writes;

    GridRecipeRepository(Plugin plugin) { this(plugin, plugin.getDataFolder().toPath()); }
    GridRecipeRepository(Plugin plugin, Path root) {
        this(plugin, root, action -> Bukkit.getScheduler().runTask(plugin, action));
    }
    GridRecipeRepository(Plugin plugin, Path root, Consumer<Runnable> mainCallback) {
        this.plugin = plugin;
        directory = root.resolve("grid-recipes");
        writes = new OrderedRecipeWrites<>(recipes, mainCallback);
    }

    void load() throws IOException { LegacyIoGuard.testOnly(plugin); load(DiskSnapshot.read(directory.getParent())); }

    void load(DiskSnapshot snapshot) throws IOException {
        if (writes.hasPending()) throw new IOException("Grid recipe writes are still pending");
        Map<NamespacedKey, GridRecipeDefinition> loaded = new LinkedHashMap<>();
        for (String name : snapshot.directory("grid-recipes").keySet()) {
            if (!name.endsWith(".yml")) continue;
            try {
                YamlConfiguration config = snapshot.yaml("grid-recipes/" + name);
                int schema = config.getInt("schema");
                if (schema < 1 || schema > SCHEMA) throw new IllegalArgumentException("Unsupported schema");
                NamespacedKey key = NamespacedKey.fromString(config.getString("id", ""), plugin);
                if (key == null) throw new IllegalArgumentException("Invalid ID");
                GridTier tier = GridTier.valueOf(config.getString("tier", ""));
                RecipeKind kind = RecipeKind.valueOf(config.getString("kind", ""));
                int size = config.getInt("size");
                if (size < 2 || size > 6) throw new IllegalArgumentException("Invalid grid size");
                List<ItemStack> ingredients = new ArrayList<>(size * size);
                List<IngredientMatchMode> modes = new ArrayList<>(size * size);
                List<List<ItemStack>> alternatives = new ArrayList<>(size * size);
                List<NamespacedKey> itemTags = new ArrayList<>(size * size);
                List<Boolean> consumeSlots = new ArrayList<>(size * size);
                List<Boolean> optionalSlots = new ArrayList<>(size * size);
                List<ItemStack> remainders = new ArrayList<>(size * size);
                for (int i = 0; i < size * size; i++) {
                    ingredients.add(config.getItemStack("ingredients." + i));
                    modes.add(schema == 1 ? IngredientMatchMode.EXACT : IngredientMatchMode.valueOf(
                            config.getString("match-modes." + i, IngredientMatchMode.EXACT.name())));
                    String choicesPath = "alternatives." + i;
                    if (schema >= 3 && config.contains(choicesPath)
                            && !config.isList(choicesPath)) {
                        throw new IllegalArgumentException("Invalid alternatives for slot " + i);
                    }
                    List<ItemStack> choices = new ArrayList<>();
                    if (schema >= 3) {
                        for (Object value : config.getList(choicesPath, List.of())) {
                            if (!(value instanceof ItemStack item)) {
                                throw new IllegalArgumentException("Invalid alternative for slot " + i);
                            }
                            choices.add(item);
                        }
                    }
                    alternatives.add(choices);
                    String rawTag = schema >= 3 ? config.getString("item-tags." + i) : null;
                    if (rawTag != null && !rawTag.contains(":")) {
                        throw new IllegalArgumentException("Item tag needs a namespace for slot " + i);
                    }
                    NamespacedKey tag = rawTag == null ? null : NamespacedKey.fromString(rawTag);
                    if (rawTag != null && tag == null) {
                        throw new IllegalArgumentException("Invalid item tag for slot " + i);
                    }
                    itemTags.add(tag);
                    String consumePath = "consume." + i;
                    if (schema >= 5 && config.contains(consumePath) && !config.isBoolean(consumePath)) {
                        throw new IllegalArgumentException("Invalid consume flag for slot " + i);
                    }
                    consumeSlots.add(schema < 5 || config.getBoolean(consumePath, true));
                    String optionalPath = "optional." + i;
                    if (schema >= 7 && config.contains(optionalPath) && !config.isBoolean(optionalPath)) {
                        throw new IllegalArgumentException("Invalid optional flag for slot " + i);
                    }
                    optionalSlots.add(schema >= 7 && config.getBoolean(optionalPath, false));
                    String remainderPath = "remainders." + i;
                    ItemStack remainder = schema < 5 ? null : config.getItemStack(remainderPath);
                    if (schema >= 5 && config.contains(remainderPath) && remainder == null) {
                        throw new IllegalArgumentException("Invalid remainder for slot " + i);
                    }
                    remainders.add(remainder);
                }
                List<WeightedResult> results = new ArrayList<>();
                if (schema >= 4) {
                    int count = config.getInt("result-count", 0);
                    if (count < 1 || count > GridRecipeDefinition.MAX_RESULTS) {
                        throw new IllegalArgumentException("Invalid result count");
                    }
                    for (int i = 0; i < count; i++) {
                        results.add(new WeightedResult(config.getItemStack("results." + i + ".item"),
                                config.getInt("results." + i + ".weight", 0)));
                    }
                } else {
                    results.add(new WeightedResult(config.getItemStack("result"), 1));
                }
                RecipeConditions conditions = schema < 6 ? RecipeConditions.NONE : new RecipeConditions(
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
                        readKey(config, "conditions.advancement"));
                CraftSoundAction craftSound = null;
                if (schema >= 8 && config.contains("actions.sound")) {
                    NamespacedKey sound = readKey(config, "actions.sound.key");
                    if (sound == null || !config.isBoolean("actions.sound.per-batch")) {
                        throw new IllegalArgumentException("Invalid craft sound action");
                    }
                    craftSound = new CraftSoundAction(sound,
                            readNumber(config, "actions.sound.volume").floatValue(),
                            readNumber(config, "actions.sound.pitch").floatValue(),
                            config.getBoolean("actions.sound.per-batch"));
                }
                CraftAdvancementAction craftAdvancement = null;
                if (schema >= 9 && config.contains("actions.advancement")) {
                    craftAdvancement = new CraftAdvancementAction(
                            readKey(config, "actions.advancement"));
                }
                CraftCommandAction craftCommand = null;
                if (schema >= 11 && config.contains("actions.command")) {
                    if (!config.isBoolean("actions.command.per-batch")) {
                        throw new IllegalArgumentException("Invalid craft command cadence");
                    }
                    craftCommand = new CraftCommandAction(
                            readString(config, "actions.command.text"),
                            CraftCommandSender.valueOf(readString(config, "actions.command.sender")),
                            config.getBoolean("actions.command.per-batch"));
                }
                String copyMode = schema < 13 ? null : readString(config, "result-metadata-mode");
                ResultMetadataMode metadataMode = copyMode == null
                        ? ResultMetadataMode.NONE : ResultMetadataMode.valueOf(copyMode);
                GridRecipeDefinition recipe = new GridRecipeDefinition(key, tier, kind, size,
                        ingredients, results, config.getString("group", ""),
                        config.getBoolean("enabled", true), config.getBoolean("hidden", false),
                        modes, schema == 1 ? 0 : config.getInt("priority", 0),
                        schema == 1 || config.getBoolean("allow-mirror", true),
                        alternatives, itemTags, consumeSlots, remainders, optionalSlots,
                        conditions, craftSound, craftAdvancement, craftCommand,
                        schema < 12 ? 64 : readInt(config, "max-shift-batches", 64),
                        schema < 13 ? 0 : readInt(config, "cooldown-seconds", 0),
                        schema < 13 ? -1 : readInt(config, "result-copy-source-slot", -1),
                        metadataMode,
                        schema < 14 ? 0 : readInt(config, "max-crafts-per-player", 0));
                if (loaded.putIfAbsent(key, recipe) != null) throw new IllegalArgumentException("Duplicate ID");
            } catch (IOException | RuntimeException ex) {
                throw new IOException("Invalid grid recipe " + name + ": " + ex.getMessage(), ex);
            }
        }
        recipes.clear();
        recipes.putAll(loaded);
        writes.clear();
    }

    Collection<GridRecipeDefinition> all() { return List.copyOf(recipes.values()); }
    Optional<GridRecipeDefinition> get(NamespacedKey key) { return Optional.ofNullable(recipes.get(key)); }

    void save(GridRecipeDefinition recipe) throws IOException {
        LegacyIoGuard.testOnly(plugin);
        writes.requireNoPendingWrite(recipe.key());
        RecipeRepository.writeAtomically(pathFor(recipe.key()), serialize(recipe));
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

    CompletableFuture<Void> saveAsync(GridRecipeDefinition recipe, AsyncIoService io, Runnable onRollback) {
        String yaml = serialize(recipe);
        return writes.saveAsync(recipe.key(), recipe, io,
                () -> io.writeDocument(pathFor(recipe.key()), yaml), onRollback);
    }

    CompletableFuture<Boolean> removeAsync(NamespacedKey key, AsyncIoService io, Runnable onRollback) {
        return writes.removeAsync(key, io, () -> io.deleteDocument(pathFor(key)), onRollback);
    }

    private static String serialize(GridRecipeDefinition recipe) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("schema", SCHEMA);
        config.set("id", recipe.key().toString());
        config.set("tier", recipe.tier().name());
        config.set("kind", recipe.kind().name());
        config.set("size", recipe.size());
        config.set("group", recipe.group());
        config.set("enabled", recipe.enabled());
        config.set("hidden", recipe.hidden());
        config.set("priority", recipe.priority());
        config.set("allow-mirror", recipe.allowMirror());
        config.set("max-shift-batches", recipe.maxShiftBatches());
        config.set("cooldown-seconds", recipe.cooldownSeconds());
        config.set("max-crafts-per-player", recipe.maxCraftsPerPlayer());
        config.set("result-metadata-mode", recipe.resultMetadataMode().name());
        if (recipe.resultMetadataMode() != ResultMetadataMode.NONE) {
            config.set("result-copy-source-slot", recipe.resultCopySourceSlot());
        }
        List<ItemStack> items = recipe.ingredients();
        List<IngredientMatchMode> modes = recipe.matchModes();
        List<List<ItemStack>> alternatives = recipe.alternatives();
        List<NamespacedKey> itemTags = recipe.itemTags();
        List<Boolean> consumeSlots = recipe.consumeSlots();
        List<Boolean> optionalSlots = recipe.optionalSlots();
        List<ItemStack> remainders = recipe.remainders();
        for (int i = 0; i < items.size(); i++) {
            config.set("ingredients." + i, items.get(i));
            if (modes.get(i) != IngredientMatchMode.EXACT) {
                config.set("match-modes." + i, modes.get(i).name());
            }
            if (!alternatives.get(i).isEmpty()) config.set("alternatives." + i, alternatives.get(i));
            if (itemTags.get(i) != null) config.set("item-tags." + i, itemTags.get(i).toString());
            if (!consumeSlots.get(i)) config.set("consume." + i, false);
            if (optionalSlots.get(i)) config.set("optional." + i, true);
            if (remainders.get(i) != null) config.set("remainders." + i, remainders.get(i));
        }
        config.set("result-count", recipe.resultChoices().size());
        for (int i = 0; i < recipe.resultChoices().size(); i++) {
            WeightedResult choice = recipe.resultChoices().get(i);
            config.set("results." + i + ".item", choice.item());
            config.set("results." + i + ".weight", choice.weight());
        }
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
        if (conditions.minimumLevel() > 0) {
            config.set("conditions.minimum-level", conditions.minimumLevel());
        }
        if (conditions.scoreboardObjective() != null) {
            config.set("conditions.scoreboard-objective", conditions.scoreboardObjective());
            config.set("conditions.minimum-score", conditions.minimumScore());
        }
        if (conditions.advancement() != null) {
            config.set("conditions.advancement", conditions.advancement().toString());
        }
        CraftSoundAction craftSound = recipe.craftSound();
        if (craftSound != null) {
            config.set("actions.sound.key", craftSound.sound().toString());
            config.set("actions.sound.volume", (double) craftSound.volume());
            config.set("actions.sound.pitch", (double) craftSound.pitch());
            config.set("actions.sound.per-batch", craftSound.perBatch());
        }
        if (recipe.craftAdvancement() != null) {
            config.set("actions.advancement", recipe.craftAdvancement().advancement().toString());
        }
        if (recipe.craftCommand() != null) {
            config.set("actions.command.text", recipe.craftCommand().command());
            config.set("actions.command.sender", recipe.craftCommand().sender().name());
            config.set("actions.command.per-batch", recipe.craftCommand().perBatch());
        }
        return config.saveToString();
    }

    private static Number readNumber(YamlConfiguration config, String path) {
        Object value = config.get(path);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Invalid " + path);
        return number;
    }

    private static String readString(YamlConfiguration config, String path) {
        if (!config.contains(path)) return null;
        if (!config.isString(path)) throw new IllegalArgumentException("Invalid " + path);
        return config.getString(path);
    }

    private static int readInt(YamlConfiguration config, String path, int defaultValue) {
        if (!config.contains(path)) return defaultValue;
        if (!config.isInt(path)) throw new IllegalArgumentException("Invalid " + path);
        return config.getInt(path);
    }

    private static NamespacedKey readKey(YamlConfiguration config, String path) {
        String raw = readString(config, path);
        if (raw == null) return null;
        NamespacedKey key = raw.contains(":") ? NamespacedKey.fromString(raw) : null;
        if (key == null || !raw.equals(key.toString())) {
            throw new IllegalArgumentException("Invalid " + path);
        }
        return key;
    }

    private Path pathFor(NamespacedKey key) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.toString().getBytes(StandardCharsets.UTF_8));
        return directory.resolve(encoded + ".yml");
    }
}
