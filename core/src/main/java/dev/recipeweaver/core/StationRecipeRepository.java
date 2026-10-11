package dev.recipeweaver.core;

import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.WeatherCondition;
import dev.recipeweaver.api.CraftSoundAction;
import dev.recipeweaver.api.CraftAdvancementAction;
import dev.recipeweaver.api.CraftCommandAction;
import dev.recipeweaver.api.CraftCommandSender;
import dev.recipeweaver.api.ResultMetadataMode;
import dev.recipeweaver.api.WeightedResult;
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
import org.bukkit.inventory.recipe.CookingBookCategory;
import org.bukkit.plugin.Plugin;

final class StationRecipeRepository {
    private final Plugin plugin;
    private final Path directory;
    private final Map<NamespacedKey, StationRecipeDefinition> recipes = new LinkedHashMap<>();
    private final OrderedRecipeWrites<StationRecipeDefinition> writes;

    StationRecipeRepository(Plugin plugin) { this(plugin, plugin.getDataFolder().toPath()); }

    StationRecipeRepository(Plugin plugin, Path dataRoot) {
        this(plugin, dataRoot, action -> Bukkit.getScheduler().runTask(plugin, action));
    }

    StationRecipeRepository(Plugin plugin, Path dataRoot, Consumer<Runnable> mainCallback) {
        this.plugin = plugin;
        directory = dataRoot.resolve("stations");
        writes = new OrderedRecipeWrites<>(recipes, mainCallback);
    }

    void load() throws IOException { LegacyIoGuard.testOnly(plugin); load(DiskSnapshot.read(directory.getParent())); }

    void load(DiskSnapshot snapshot) throws IOException {
        if (writes.hasPending()) throw new IOException("Station recipe writes are still pending");
        Map<NamespacedKey, StationRecipeDefinition> loaded = new LinkedHashMap<>();
        for (String name : snapshot.directory("stations").keySet()) {
            if (!name.endsWith(".yml")) continue;
            try {
                YamlConfiguration yaml = snapshot.yaml("stations/" + name);
                int schema = yaml.getInt("schema");
                if (schema < 1 || schema > 10) throw new IllegalArgumentException("Unsupported schema");
                if (schema >= 4 && (!yaml.isInt("max-crafts-per-player")
                        || !yaml.isInt("cooldown-seconds"))) {
                    throw new IllegalArgumentException("Invalid station player limits or delay");
                }
                NamespacedKey key = NamespacedKey.fromString(yaml.getString("id", ""), plugin);
                if (key == null) throw new IllegalArgumentException("Invalid ID");
                StationKind kind = StationKind.valueOf(yaml.getString("kind", ""));
                List<ItemStack> ingredients = new ArrayList<>(kind.slots());
                List<IngredientMatchMode> modes = new ArrayList<>(kind.slots());
                List<List<ItemStack>> alternatives = new ArrayList<>(kind.slots());
                List<NamespacedKey> tags = new ArrayList<>(kind.slots());
                List<Boolean> consumeSlots = new ArrayList<>(kind.slots());
                List<ItemStack> remainders = new ArrayList<>(kind.slots());
                List<Boolean> optionalSlots = new ArrayList<>(kind.slots());
                for (int i = 0; i < kind.slots(); i++) {
                    ingredients.add(yaml.getItemStack("ingredients." + i));
                    modes.add(schema == 1 ? IngredientMatchMode.EXACT : IngredientMatchMode.valueOf(
                            yaml.getString("match-modes." + i, IngredientMatchMode.EXACT.name())));
                    String path = "alternatives." + i;
                    if (schema >= 2 && yaml.contains(path) && !yaml.isList(path)) {
                        throw new IllegalArgumentException("Invalid alternatives for slot " + i);
                    }
                    List<ItemStack> choices = new ArrayList<>();
                    if (schema >= 2) {
                        for (Object value : yaml.getList(path, List.of())) {
                            if (!(value instanceof ItemStack item)) {
                                throw new IllegalArgumentException("Invalid alternative for slot " + i);
                            }
                            choices.add(item);
                        }
                    }
                    alternatives.add(choices);
                    String rawTag = schema >= 2 ? yaml.getString("item-tags." + i) : null;
                    if (rawTag != null && !rawTag.contains(":")) {
                        throw new IllegalArgumentException("Item tag needs a namespace for slot " + i);
                    }
                    NamespacedKey tag = rawTag == null ? null : NamespacedKey.fromString(rawTag);
                    if (rawTag != null && tag == null) {
                        throw new IllegalArgumentException("Invalid item tag for slot " + i);
                    }
                    tags.add(tag);
                    String consumePath = "consume-slots." + i;
                    if (schema >= 8 && !yaml.isBoolean(consumePath)) {
                        throw new IllegalArgumentException("Invalid consumption setting for slot " + i);
                    }
                    consumeSlots.add(schema < 8 || yaml.getBoolean(consumePath));
                    String remainderPath = "remainders." + i;
                    if (schema >= 8 && yaml.contains(remainderPath)
                            && !yaml.isItemStack(remainderPath)) {
                        throw new IllegalArgumentException("Invalid returned item for slot " + i);
                    }
                    remainders.add(schema < 8 ? null : yaml.getItemStack(remainderPath));
                    String optionalPath = "optional-slots." + i;
                    if (schema >= 9 && !yaml.isBoolean(optionalPath)) {
                        throw new IllegalArgumentException("Invalid optional setting for slot " + i);
                    }
                    optionalSlots.add(schema >= 9 && yaml.getBoolean(optionalPath));
                }
                CraftSoundAction craftSound = null;
                if (schema >= 6 && yaml.contains("actions.sound")) {
                    NamespacedKey sound = readKey(yaml, "actions.sound.key");
                    if (sound == null || !yaml.isBoolean("actions.sound.per-batch")) {
                        throw new IllegalArgumentException("Invalid craft sound action");
                    }
                    craftSound = new CraftSoundAction(sound,
                            readNumber(yaml, "actions.sound.volume").floatValue(),
                            readNumber(yaml, "actions.sound.pitch").floatValue(),
                            yaml.getBoolean("actions.sound.per-batch"));
                }
                CraftAdvancementAction craftAdvancement = null;
                if (schema >= 6 && yaml.contains("actions.advancement")) {
                    craftAdvancement = new CraftAdvancementAction(
                            readKey(yaml, "actions.advancement"));
                }
                CraftCommandAction craftCommand = null;
                if (schema >= 6 && yaml.contains("actions.command")) {
                    if (!yaml.isBoolean("actions.command.per-batch")) {
                        throw new IllegalArgumentException("Invalid craft command cadence");
                    }
                    craftCommand = new CraftCommandAction(
                            readString(yaml, "actions.command.text"),
                            CraftCommandSender.valueOf(readString(yaml, "actions.command.sender")),
                            yaml.getBoolean("actions.command.per-batch"));
                }
                String copyMode = schema < 7 ? null : readString(yaml, "result-metadata-mode");
                ResultMetadataMode metadataMode = copyMode == null
                        ? ResultMetadataMode.NONE : ResultMetadataMode.valueOf(copyMode);
                List<WeightedResult> results = new ArrayList<>();
                if (schema >= 10) {
                    if (!yaml.isInt("result-count")) {
                        throw new IllegalArgumentException("Invalid station result count");
                    }
                    int count = yaml.getInt("result-count");
                    if (count < 0 || count > StationRecipeDefinition.MAX_RESULTS) {
                        throw new IllegalArgumentException("Invalid station result count");
                    }
                    for (int i = 0; i < count; i++) {
                        if (!yaml.isItemStack("results." + i + ".item")
                                || !yaml.isInt("results." + i + ".weight")) {
                            throw new IllegalArgumentException("Invalid weighted result " + i);
                        }
                        results.add(new WeightedResult(yaml.getItemStack("results." + i + ".item"),
                                yaml.getInt("results." + i + ".weight")));
                    }
                } else if (kind != StationKind.SMITHING_TRIM) {
                    results.add(new WeightedResult(yaml.getItemStack("result"), 1));
                }
                StationRecipeDefinition recipe = new StationRecipeDefinition(key, kind, ingredients,
                        yaml.getItemStack("result"), yaml.getString("group", ""),
                        yaml.getInt("cookingTicks", 200), (float) yaml.getDouble("experience", 0),
                        yaml.getInt("anvilLevels", 1), yaml.getBoolean("enabled", true),
                        yaml.getBoolean("hidden", false), modes, alternatives, tags,
                        schema < 3 ? CookingBookCategory.MISC : CookingBookCategory.valueOf(
                                yaml.getString("book-category", CookingBookCategory.MISC.name())),
                        schema < 4 ? 0 : yaml.getInt("max-crafts-per-player"),
                        schema < 4 ? 0 : yaml.getInt("cooldown-seconds"),
                        schema < 5 ? RecipeConditions.NONE : new RecipeConditions(
                                readString(yaml, "conditions.permission"),
                                readString(yaml, "conditions.world"),
                                readKey(yaml, "conditions.biome"),
                                readInt(yaml, "conditions.time-start", -1),
                                readInt(yaml, "conditions.time-end", -1),
                                WeatherCondition.valueOf(yaml.contains("conditions.weather")
                                        ? readString(yaml, "conditions.weather") : "ANY"),
                                readInt(yaml, "conditions.minimum-level", 0),
                                readString(yaml, "conditions.scoreboard-objective"),
                                readInt(yaml, "conditions.minimum-score", 0),
                                readKey(yaml, "conditions.advancement")),
                        craftSound, craftAdvancement, craftCommand,
                        schema < 7 ? -1 : readInt(yaml, "result-copy-source-slot", -1),
                        metadataMode, consumeSlots, remainders, optionalSlots, results);
                if (loaded.putIfAbsent(key, recipe) != null) throw new IllegalArgumentException("Duplicate ID");
            } catch (Exception ex) {
                throw new IOException("Invalid station recipe " + name + ": " + ex.getMessage(), ex);
            }
        }
        recipes.clear();
        recipes.putAll(loaded);
        writes.clear();
    }

    Collection<StationRecipeDefinition> all() { return List.copyOf(recipes.values()); }
    Optional<StationRecipeDefinition> get(NamespacedKey key) { return Optional.ofNullable(recipes.get(key)); }

    void save(StationRecipeDefinition recipe) throws IOException {
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

    CompletableFuture<Void> saveAsync(StationRecipeDefinition recipe, AsyncIoService io, Runnable onRollback) {
        String yaml = serialize(recipe);
        return writes.saveAsync(recipe.key(), recipe, io,
                () -> io.writeDocument(pathFor(recipe.key()), yaml), onRollback);
    }

    CompletableFuture<Boolean> removeAsync(NamespacedKey key, AsyncIoService io, Runnable onRollback) {
        return writes.removeAsync(key, io, () -> io.deleteDocument(pathFor(key)), onRollback);
    }

    private static String serialize(StationRecipeDefinition recipe) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema", 10);
        yaml.set("id", recipe.key().toString());
        yaml.set("kind", recipe.kind().name());
        yaml.set("group", recipe.group());
        yaml.set("book-category", recipe.bookCategory().name());
        yaml.set("cookingTicks", recipe.cookingTicks());
        yaml.set("experience", recipe.experience());
        yaml.set("anvilLevels", recipe.anvilLevels());
        yaml.set("max-crafts-per-player", recipe.maxCraftsPerPlayer());
        yaml.set("cooldown-seconds", recipe.cooldownSeconds());
        yaml.set("enabled", recipe.enabled());
        yaml.set("hidden", recipe.hidden());
        yaml.set("result-metadata-mode", recipe.resultMetadataMode().name());
        yaml.set("result-count", recipe.resultChoices().size());
        for (int i = 0; i < recipe.resultChoices().size(); i++) {
            WeightedResult choice = recipe.resultChoices().get(i);
            yaml.set("results." + i + ".item", choice.item());
            yaml.set("results." + i + ".weight", choice.weight());
        }
        if (recipe.resultMetadataMode() != ResultMetadataMode.NONE) {
            yaml.set("result-copy-source-slot", recipe.resultCopySourceSlot());
        }
        RecipeConditions conditions = recipe.conditions();
        if (conditions.permission() != null) yaml.set("conditions.permission", conditions.permission());
        if (conditions.world() != null) yaml.set("conditions.world", conditions.world());
        if (conditions.biome() != null) yaml.set("conditions.biome", conditions.biome().toString());
        if (conditions.timeStart() >= 0) {
            yaml.set("conditions.time-start", conditions.timeStart());
            yaml.set("conditions.time-end", conditions.timeEnd());
        }
        if (conditions.weather() != WeatherCondition.ANY) {
            yaml.set("conditions.weather", conditions.weather().name());
        }
        if (conditions.minimumLevel() > 0) yaml.set("conditions.minimum-level", conditions.minimumLevel());
        if (conditions.scoreboardObjective() != null) {
            yaml.set("conditions.scoreboard-objective", conditions.scoreboardObjective());
            yaml.set("conditions.minimum-score", conditions.minimumScore());
        }
        if (conditions.advancement() != null) {
            yaml.set("conditions.advancement", conditions.advancement().toString());
        }
        if (recipe.craftSound() != null) {
            yaml.set("actions.sound.key", recipe.craftSound().sound().toString());
            yaml.set("actions.sound.volume", (double) recipe.craftSound().volume());
            yaml.set("actions.sound.pitch", (double) recipe.craftSound().pitch());
            yaml.set("actions.sound.per-batch", recipe.craftSound().perBatch());
        }
        if (recipe.craftAdvancement() != null) {
            yaml.set("actions.advancement", recipe.craftAdvancement().advancement().toString());
        }
        if (recipe.craftCommand() != null) {
            yaml.set("actions.command.text", recipe.craftCommand().command());
            yaml.set("actions.command.sender", recipe.craftCommand().sender().name());
            yaml.set("actions.command.per-batch", recipe.craftCommand().perBatch());
        }
        List<ItemStack> ingredients = recipe.ingredients();
        List<List<ItemStack>> alternatives = recipe.alternatives();
        for (int i = 0; i < recipe.kind().slots(); i++) {
            yaml.set("ingredients." + i, ingredients.get(i));
            yaml.set("match-modes." + i, recipe.matchModes().get(i).name());
            yaml.set("alternatives." + i, alternatives.get(i));
            yaml.set("consume-slots." + i, recipe.consumes(i));
            yaml.set("remainders." + i, recipe.remainder(i));
            yaml.set("optional-slots." + i, recipe.optional(i));
            NamespacedKey tag = recipe.itemTags().get(i);
            if (tag != null) yaml.set("item-tags." + i, tag.toString());
        }
        yaml.set("result", recipe.result());
        return yaml.saveToString();
    }

    private static String readString(YamlConfiguration yaml, String path) {
        if (!yaml.contains(path)) return null;
        if (!yaml.isString(path)) throw new IllegalArgumentException("Invalid " + path);
        return yaml.getString(path);
    }

    private static int readInt(YamlConfiguration yaml, String path, int fallback) {
        if (!yaml.contains(path)) return fallback;
        if (!yaml.isInt(path)) throw new IllegalArgumentException("Invalid " + path);
        return yaml.getInt(path);
    }

    private static Number readNumber(YamlConfiguration yaml, String path) {
        Object value = yaml.get(path);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Invalid " + path);
        return number;
    }

    private static NamespacedKey readKey(YamlConfiguration yaml, String path) {
        String value = readString(yaml, path);
        if (value == null) return null;
        NamespacedKey key = value.contains(":") ? NamespacedKey.fromString(value) : null;
        if (key == null || !value.equals(key.toString())) {
            throw new IllegalArgumentException("Invalid " + path);
        }
        return key;
    }

    private Path pathFor(NamespacedKey key) {
        return directory.resolve(Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.toString().getBytes(StandardCharsets.UTF_8)) + ".yml");
    }
}
