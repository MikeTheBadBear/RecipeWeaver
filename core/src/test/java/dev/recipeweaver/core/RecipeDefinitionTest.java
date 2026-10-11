package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import dev.recipeweaver.api.WeatherCondition;
import dev.recipeweaver.api.CraftSoundAction;
import dev.recipeweaver.api.CraftCommandAction;
import dev.recipeweaver.api.CraftCommandSender;
import dev.recipeweaver.api.CraftAdvancementAction;
import dev.recipeweaver.api.ResultMetadataMode;
import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.GridTier;
import dev.recipeweaver.api.WeightedResult;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.recipe.CookingBookCategory;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RecipeDefinitionTest {
    @TempDir Path directory;

    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test
    void workbenchDefinitionIsAnImmutableSnapshot() {
        List<ItemStack> inputs = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        ItemStack stone = new ItemStack(Material.STONE);
        ItemStack diamond = new ItemStack(Material.DIAMOND, 2);
        inputs.set(0, stone);
        RecipeDefinition recipe = new RecipeDefinition(key("workbench/one"), RecipeKind.SHAPED,
                inputs, diamond, "mining", true, false);
        stone.setType(Material.DIRT);
        diamond.setAmount(1);
        recipe.ingredients().get(0).setType(Material.DIRT);
        recipe.result().setAmount(1);
        assertEquals(Material.STONE, recipe.ingredients().get(0).getType());
        assertEquals(2, recipe.result().getAmount());
        assertThrows(IllegalArgumentException.class, () -> new RecipeDefinition(key("bad"),
                RecipeKind.SHAPELESS, Arrays.asList(new ItemStack[9]), new ItemStack(Material.DIAMOND), "", true, false));
    }

    @Test
    void workbenchChoicesAreImmutableAndRequireMaterialModeForTags() {
        List<ItemStack> inputs = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        inputs.set(0, new ItemStack(Material.OAK_PLANKS));
        List<List<ItemStack>> alternatives = new ArrayList<>();
        for (int i = 0; i < 9; i++) alternatives.add(new ArrayList<>());
        ItemStack dirt = new ItemStack(Material.DIRT, 20);
        alternatives.get(0).add(dirt);
        List<NamespacedKey> tags = new ArrayList<>(Collections.nCopies(9, null));
        tags.set(0, NamespacedKey.fromString("minecraft:planks"));
        List<IngredientMatchMode> modes = new ArrayList<>(Collections.nCopies(9, IngredientMatchMode.EXACT));
        assertThrows(IllegalArgumentException.class, () -> new RecipeDefinition(key("bad_tag_mode"),
                RecipeKind.SHAPED, inputs, new ItemStack(Material.DIAMOND), "", true, false,
                modes, alternatives, tags));
        modes.set(0, IngredientMatchMode.MATERIAL);
        RecipeDefinition recipe = new RecipeDefinition(key("choice_snapshot"), RecipeKind.SHAPED,
                inputs, new ItemStack(Material.DIAMOND), "", true, false, modes, alternatives, tags);
        dirt.setType(Material.STONE);
        recipe.alternatives().get(0).get(0).setType(Material.STONE);
        assertEquals(Material.DIRT, recipe.alternatives().get(0).get(0).getType());
        assertEquals(1, recipe.alternatives().get(0).get(0).getAmount());
        assertTrue(recipe.matchesItemSample(0, new ItemStack(Material.DIRT)));
        assertFalse(recipe.matchesItemSample(0, new ItemStack(Material.STONE)));
    }

    @Test
    void shapelessWorkbenchPreviewReassignsOverlappingAlternatives() {
        List<ItemStack> pattern = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        pattern.set(0, new ItemStack(Material.STONE));
        pattern.set(1, new ItemStack(Material.DIRT));
        List<List<ItemStack>> alternatives = new ArrayList<>();
        for (int i = 0; i < 9; i++) alternatives.add(new ArrayList<>());
        alternatives.get(0).add(new ItemStack(Material.DIRT));
        List<ItemStack> actual = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        actual.set(2, new ItemStack(Material.DIRT));
        actual.set(8, new ItemStack(Material.STONE));
        assertTrue(RecipeMatcher.matches(RecipeKind.SHAPELESS, pattern,
                Collections.nCopies(9, IngredientMatchMode.EXACT), alternatives,
                Collections.nCopies(9, null), actual));
        actual.set(8, new ItemStack(Material.REDSTONE));
        assertFalse(RecipeMatcher.matches(RecipeKind.SHAPELESS, pattern,
                Collections.nCopies(9, IngredientMatchMode.EXACT), alternatives,
                Collections.nCopies(9, null), actual));
    }

    @Test
    void workbenchPreviewIncludesResolvedItemTagMaterials() {
        List<ItemStack> pattern = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        pattern.set(0, new ItemStack(Material.OAK_PLANKS));
        List<NamespacedKey> tags = new ArrayList<>(Collections.nCopies(9, null));
        tags.set(0, NamespacedKey.fromString("minecraft:planks"));
        List<ItemStack> actual = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        actual.set(4, new ItemStack(Material.BIRCH_PLANKS));
        assertTrue(RecipeMatcher.matches(RecipeKind.SHAPED, pattern,
                Collections.nCopies(9, IngredientMatchMode.MATERIAL),
                Collections.nCopies(9, List.of()), tags, actual,
                ignored -> java.util.Set.of(Material.BIRCH_PLANKS)));
    }

    @Test
    void currentSchemaStoresWorkbenchChoicesAndBookCategoryAndLoadsSchemaTwo() throws Exception {
        List<ItemStack> inputs = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        inputs.set(0, new ItemStack(Material.OAK_PLANKS));
        List<List<ItemStack>> alternatives = new ArrayList<>();
        for (int i = 0; i < 9; i++) alternatives.add(new ArrayList<>());
        alternatives.get(0).add(new ItemStack(Material.DIRT));
        List<NamespacedKey> tags = new ArrayList<>(Collections.nCopies(9, null));
        tags.set(0, NamespacedKey.fromString("minecraft:planks"));
        List<IngredientMatchMode> modes = new ArrayList<>(Collections.nCopies(9, IngredientMatchMode.EXACT));
        modes.set(0, IngredientMatchMode.MATERIAL);
        RecipeDefinition recipe = new RecipeDefinition(key("stored_choices"), RecipeKind.SHAPED,
                inputs, new ItemStack(Material.EMERALD), "", true, false,
                modes, alternatives, tags, CraftingBookCategory.EQUIPMENT);
        new RecipeRepository(plugin()).save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("recipes"))) { file = files.findFirst().orElseThrow(); }
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("schema: 4"));
        assertTrue(yaml.contains("book-category: EQUIPMENT"));
        assertTrue(yaml.contains("MATERIAL") && yaml.contains("DIRT"));
        assertTrue(yaml.contains("minecraft:planks"));
        // MockBukkit cannot deserialize its own ItemMetaMock; use server-style item YAML.
        String fixture = """
                schema: 3
                id: recipeweaver:stored_choices
                kind: SHAPED
                book-category: EQUIPMENT
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: OAK_PLANKS
                match-modes:
                  '0': MATERIAL
                alternatives:
                  '0':
                  - ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: DIRT
                item-tags:
                  '0': minecraft:planks
                result:
                  ==: org.bukkit.inventory.ItemStack
                  v: 1
                  type: EMERALD
                """;
        Files.writeString(file, fixture);
        RecipeRepository reloaded = new RecipeRepository(plugin());
        reloaded.load();
        assertEquals(CraftingBookCategory.EQUIPMENT,
                reloaded.get(recipe.key()).orElseThrow().bookCategory());
        Files.writeString(file, fixture.replace("schema: 3", "schema: 2")
                .replace("book-category: EQUIPMENT", ""));
        reloaded.load();
        assertEquals(CraftingBookCategory.MISC,
                reloaded.get(recipe.key()).orElseThrow().bookCategory());
        assertEquals(Material.DIRT,
                reloaded.get(recipe.key()).orElseThrow().alternatives().get(0).get(0).getType());
    }

    @Test
    void workbenchConditionsRoundTripAndOlderSchemaDefaultsToUnrestricted() throws Exception {
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        ingredients.set(0, new ItemStack(Material.STONE));
        RecipeConditions conditions = new RecipeConditions("recipeweaver.craft.special", "world",
                NamespacedKey.minecraft("plains"), 23000, 1000, WeatherCondition.RAIN,
                7, "crafts", 3, NamespacedKey.minecraft("story/root"));
        RecipeDefinition recipe = new RecipeDefinition(key("conditional_workbench"),
                RecipeKind.SHAPED, ingredients, new ItemStack(Material.DIAMOND), "", true, false,
                Collections.nCopies(9, IngredientMatchMode.EXACT),
                Collections.nCopies(9, List.of()), Collections.nCopies(9, null),
                CraftingBookCategory.MISC, conditions);
        RecipeRepository repository = new RecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("recipes"))) { file = files.findFirst().orElseThrow(); }
        String saved = Files.readString(file);
        assertTrue(saved.contains("schema: 4") && saved.contains("conditions:"));
        assertTrue(saved.contains("permission: recipeweaver.craft.special"));

        String fixture = """
                schema: 4
                id: recipeweaver:conditional_workbench
                kind: SHAPED
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: STONE
                result:
                  ==: org.bukkit.inventory.ItemStack
                  v: 1
                  type: DIAMOND
                conditions:
                  permission: recipeweaver.craft.special
                  world: world
                  biome: minecraft:plains
                  time-start: 23000
                  time-end: 1000
                  weather: RAIN
                  minimum-level: 7
                  scoreboard-objective: crafts
                  minimum-score: 3
                  advancement: minecraft:story/root
                """;
        Files.writeString(file, fixture);
        repository.load();
        assertEquals(conditions, repository.get(recipe.key()).orElseThrow().conditions());
        Files.writeString(file, fixture.replace("schema: 4", "schema: 3")
                .substring(0, fixture.indexOf("conditions:")));
        repository.load();
        assertEquals(RecipeConditions.NONE, repository.get(recipe.key()).orElseThrow().conditions());
        Files.writeString(file, fixture.replace("permission: recipeweaver.craft.special",
                "permission: 42"));
        assertThrows(java.io.IOException.class, repository::load);
        assertEquals(RecipeConditions.NONE, repository.get(recipe.key()).orElseThrow().conditions());
    }

    @Test
    void trimIsDynamicAndSmithingRequiresAllInputs() {
        StationRecipeDefinition trim = new StationRecipeDefinition(key("trim"), StationKind.SMITHING_TRIM,
                List.of(new ItemStack(Material.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE),
                        new ItemStack(Material.DIAMOND_CHESTPLATE), new ItemStack(Material.REDSTONE)),
                null, "", 200, 0, 1, true, false);
        assertNull(trim.result());
        assertThrows(IllegalArgumentException.class, () -> new StationRecipeDefinition(key("bad"),
                StationKind.SMITHING_TRANSFORM, Arrays.asList(new ItemStack(Material.STONE), null, null),
                new ItemStack(Material.DIAMOND), "", 200, 0, 1, true, false));
    }

    @Test
    void stationChoicesAreImmutableAndMatchCustomTransactions() {
        List<ItemStack> inputs = Arrays.asList(new ItemStack(Material.STONE), null);
        List<List<ItemStack>> alternatives = new ArrayList<>();
        alternatives.add(new ArrayList<>(List.of(new ItemStack(Material.DIRT, 5))));
        alternatives.add(new ArrayList<>());
        List<IngredientMatchMode> modes = List.of(IngredientMatchMode.MATERIAL,
                IngredientMatchMode.EXACT);
        StationRecipeDefinition recipe = new StationRecipeDefinition(key("anvil_choices"),
                StationKind.ANVIL, inputs, new ItemStack(Material.EMERALD), "", 200, 0, 1,
                true, false, modes, alternatives, Arrays.asList(null, null));
        alternatives.get(0).get(0).setType(Material.REDSTONE);
        recipe.alternatives().get(0).get(0).setType(Material.REDSTONE);
        assertEquals(Material.DIRT, recipe.alternatives().get(0).get(0).getType());
        assertEquals(1, recipe.alternatives().get(0).get(0).getAmount());
        assertTrue(StationInventoryListener.matches(recipe, new ItemStack(Material.DIRT, 8), null));
        assertFalse(StationInventoryListener.matches(recipe, new ItemStack(Material.REDSTONE), null));
        assertFalse(StationInventoryListener.matches(recipe, new ItemStack(Material.DIRT),
                new ItemStack(Material.FLINT)));
        List<List<ItemStack>> invalid = new ArrayList<>(alternatives);
        invalid.set(1, List.of(new ItemStack(Material.FLINT)));
        assertThrows(IllegalArgumentException.class, () -> new StationRecipeDefinition(key("bad_empty_slot"),
                StationKind.ANVIL, inputs, new ItemStack(Material.EMERALD), "", 200, 0, 1,
                true, false, modes, invalid, Arrays.asList(null, null)));
    }

    @Test
    void nativeRecipesRejectUnsupportedComponentMatching() {
        List<ItemStack> workbench = new ArrayList<>(Collections.nCopies(9, null));
        workbench.set(0, new ItemStack(Material.STONE));
        List<IngredientMatchMode> modes = new ArrayList<>(Collections.nCopies(9, IngredientMatchMode.EXACT));
        modes.set(0, IngredientMatchMode.PARTIAL);
        assertThrows(IllegalArgumentException.class, () -> new RecipeDefinition(key("partial_native"),
                RecipeKind.SHAPED, workbench, new ItemStack(Material.EMERALD), "", true, false,
                modes, Collections.nCopies(9, List.of()), Collections.nCopies(9, null)));
        assertThrows(IllegalArgumentException.class, () -> new StationRecipeDefinition(
                key("partial_station"), StationKind.FURNACE, List.of(new ItemStack(Material.STONE)),
                new ItemStack(Material.EMERALD), "", 200, 0, 0, true, false,
                List.of(IngredientMatchMode.PARTIAL), List.of(List.of()),
                Collections.nCopies(1, null)));
        modes.set(0, IngredientMatchMode.CUSTOM_DATA_ONLY);
        assertThrows(IllegalArgumentException.class, () -> new RecipeDefinition(key("pdc_native"),
                RecipeKind.SHAPED, workbench, new ItemStack(Material.EMERALD), "", true, false,
                modes, Collections.nCopies(9, List.of()), Collections.nCopies(9, null)));
        assertThrows(IllegalArgumentException.class, () -> new StationRecipeDefinition(
                key("pdc_furnace"), StationKind.FURNACE, List.of(new ItemStack(Material.STONE)),
                new ItemStack(Material.EMERALD), "", 200, 0, 0, true, false,
                List.of(IngredientMatchMode.CUSTOM_DATA_ONLY), List.of(List.of()),
                Collections.nCopies(1, null)));
    }

    @Test
    void fieldSelectedModesPersistForAnvilsAndRemainUnavailableOnNativeStations() throws Exception {
        List<IngredientMatchMode> selectors = List.of(IngredientMatchMode.NAME_ONLY,
                IngredientMatchMode.LORE_ONLY, IngredientMatchMode.MODEL_ONLY,
                IngredientMatchMode.ENCHANTMENTS_ONLY, IngredientMatchMode.DAMAGE_ONLY,
                IngredientMatchMode.ITEM_FLAGS_ONLY,
                IngredientMatchMode.ATTRIBUTE_MODIFIERS_ONLY,
                IngredientMatchMode.UNBREAKABLE_ONLY,
                IngredientMatchMode.ARMOR_TRIM_ONLY,
                IngredientMatchMode.POTION_ONLY,
                IngredientMatchMode.STORED_ENCHANTMENTS_ONLY,
                IngredientMatchMode.BANNER_PATTERNS_ONLY,
                IngredientMatchMode.CROSSBOW_PROJECTILES_ONLY,
                IngredientMatchMode.FIREWORK_ONLY,
                IngredientMatchMode.BUNDLE_CONTENTS_ONLY);
        StationRecipeRepository repository = new StationRecipeRepository(plugin());
        for (IngredientMatchMode mode : selectors) {
            String id = "selected_" + mode.name().toLowerCase();
            StationRecipeDefinition anvil = new StationRecipeDefinition(key(id),
                    StationKind.ANVIL, List.of(new ItemStack(Material.PAPER),
                            new ItemStack(Material.DIRT)),
                    new ItemStack(Material.EMERALD), "", 200, 0, 0, true, false,
                    List.of(mode, IngredientMatchMode.MATERIAL),
                    List.of(List.of(), List.of()), Arrays.asList(null, null));
            repository.save(anvil);
            Path file = encodedRecipePath(directory.resolve("stations"), anvil.key());
            assertTrue(Files.readString(file).contains(mode.name()));
            // MockBukkit cannot deserialize its own weighted ItemStack output.
            Files.writeString(file, """
                    schema: 9
                    id: %s
                    kind: ANVIL
                    ingredients:
                      '0':
                        ==: org.bukkit.inventory.ItemStack
                        v: 1
                        type: PAPER
                      '1':
                        ==: org.bukkit.inventory.ItemStack
                        v: 1
                        type: DIRT
                    result:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: EMERALD
                    match-modes:
                      '0': %s
                      '1': MATERIAL
                    max-crafts-per-player: 0
                    cooldown-seconds: 0
                    consume-slots:
                      '0': true
                      '1': true
                    optional-slots:
                      '0': false
                      '1': false
                    """.formatted(anvil.key(), mode));
            assertThrows(IllegalArgumentException.class, () -> new StationRecipeDefinition(
                    key("furnace_" + id), StationKind.FURNACE,
                    List.of(new ItemStack(Material.PAPER)), new ItemStack(Material.EMERALD),
                    "", 200, 0, 0, true, false, List.of(mode), List.of(List.of()),
                    Collections.nCopies(1, null)), mode.name());
            List<ItemStack> nativeInputs = new ArrayList<>(Collections.nCopies(9, null));
            nativeInputs.set(0, new ItemStack(Material.PAPER));
            List<IngredientMatchMode> nativeModes = new ArrayList<>(
                    Collections.nCopies(9, IngredientMatchMode.MATERIAL));
            nativeModes.set(0, mode);
            assertThrows(IllegalArgumentException.class, () -> new RecipeDefinition(
                    key("native_" + id), RecipeKind.SHAPED, nativeInputs,
                    new ItemStack(Material.EMERALD), "", true, false, nativeModes,
                    Collections.nCopies(9, List.of()), Collections.nCopies(9, null)), mode.name());
        }
        List<ResultMetadataMode> copyModes = List.of(ResultMetadataMode.FOOD_ONLY,
                ResultMetadataMode.TOOL_ONLY, ResultMetadataMode.ATTRIBUTE_MODIFIERS_ONLY,
                ResultMetadataMode.DAMAGE_ONLY, ResultMetadataMode.ITEM_FLAGS_ONLY,
                ResultMetadataMode.UNBREAKABLE_ONLY, ResultMetadataMode.ARMOR_TRIM_ONLY,
                ResultMetadataMode.POTION_ONLY,
                ResultMetadataMode.STORED_ENCHANTMENTS_ONLY,
                ResultMetadataMode.BANNER_PATTERNS_ONLY,
                ResultMetadataMode.CROSSBOW_PROJECTILES_ONLY,
                ResultMetadataMode.FIREWORK_ONLY,
                ResultMetadataMode.BUNDLE_CONTENTS_ONLY);
        for (ResultMetadataMode mode : copyModes) {
            NamespacedKey copyKey = key("copy_" + mode.name().toLowerCase());
            repository.save(new StationRecipeDefinition(
                    copyKey, StationKind.ANVIL,
                    List.of(new ItemStack(Material.PAPER), new ItemStack(Material.DIRT)),
                    new ItemStack(Material.EMERALD), "", 200, 0, 0, true, false,
                    List.of(IngredientMatchMode.MATERIAL, IngredientMatchMode.MATERIAL),
                    List.of(List.of(), List.of()), Arrays.asList(null, null),
                    CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                    null, null, null, 0, mode));
            Path file = encodedRecipePath(directory.resolve("stations"), copyKey);
            assertTrue(Files.readString(file).contains("result-metadata-mode: " + mode));
            // MockBukkit cannot deserialize its own weighted ItemStack output.
            Files.writeString(file, """
                    schema: 9
                    id: %s
                    kind: ANVIL
                    ingredients:
                      '0':
                        ==: org.bukkit.inventory.ItemStack
                        v: 1
                        type: PAPER
                      '1':
                        ==: org.bukkit.inventory.ItemStack
                        v: 1
                        type: DIRT
                    result:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: EMERALD
                    result-metadata-mode: %s
                    result-copy-source-slot: 0
                    max-crafts-per-player: 0
                    cooldown-seconds: 0
                    consume-slots:
                      '0': true
                      '1': true
                    optional-slots:
                      '0': false
                      '1': false
                    """.formatted(copyKey, mode));
        }
        repository.load();
        for (IngredientMatchMode mode : selectors) {
            StationRecipeDefinition loaded = repository.get(key(
                    "selected_" + mode.name().toLowerCase())).orElseThrow();
            assertEquals(mode, loaded.matchModes().get(0));
            if (mode == IngredientMatchMode.ARMOR_TRIM_ONLY
                    || mode == IngredientMatchMode.POTION_ONLY
                    || mode == IngredientMatchMode.STORED_ENCHANTMENTS_ONLY
                    || mode == IngredientMatchMode.BANNER_PATTERNS_ONLY
                    || mode == IngredientMatchMode.CROSSBOW_PROJECTILES_ONLY
                    || mode == IngredientMatchMode.FIREWORK_ONLY
                    || mode == IngredientMatchMode.BUNDLE_CONTENTS_ONLY) {
                assertFalse(loaded.matchesItemSample(0, new ItemStack(Material.PAPER)),
                        "Incompatible items cannot satisfy specialized component matching");
            } else {
                assertTrue(loaded.matchesItemSample(0, new ItemStack(Material.PAPER)), mode.name());
            }
        }
        for (ResultMetadataMode mode : copyModes) {
            StationRecipeDefinition loaded = repository.get(key(
                    "copy_" + mode.name().toLowerCase())).orElseThrow();
            assertEquals(mode, loaded.resultMetadataMode());
            assertEquals(0, loaded.resultCopySourceSlot());
        }
    }

    private static Path encodedRecipePath(Path directory, NamespacedKey key) {
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                key.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return directory.resolve(encoded + ".yml");
    }

    @Test
    void anvilCustomDataOnlyIngredientModePersists() throws Exception {
        // MockBukkit cannot deserialize meta-bearing ItemStacks. Typed PDC
        // matching is asserted separately in ItemDataRuleTest.
        ItemStack sample = new ItemStack(Material.PAPER);
        StationRecipeDefinition recipe = new StationRecipeDefinition(key("anvil_pdc_mode"),
                StationKind.ANVIL, List.of(sample, new ItemStack(Material.DIRT)),
                new ItemStack(Material.EMERALD), "", 200, 0, 0, true, false,
                List.of(IngredientMatchMode.CUSTOM_DATA_ONLY, IngredientMatchMode.MATERIAL),
                List.of(List.of(), List.of()), Arrays.asList(null, null));
        StationRecipeRepository repository = new StationRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("stations"))) {
            file = files.findFirst().orElseThrow();
        }
        assertTrue(Files.readString(file).contains("CUSTOM_DATA_ONLY"));
        Files.writeString(file, """
                schema: 9
                id: recipeweaver:anvil_pdc_mode
                kind: ANVIL
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: PAPER
                  '1':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: DIRT
                result:
                  ==: org.bukkit.inventory.ItemStack
                  v: 1
                  type: EMERALD
                match-modes:
                  '0': CUSTOM_DATA_ONLY
                  '1': MATERIAL
                max-crafts-per-player: 0
                cooldown-seconds: 0
                result-metadata-mode: NONE
                consume-slots:
                  '0': true
                  '1': true
                optional-slots:
                  '0': false
                  '1': false
                """);
        repository.load();
        StationRecipeDefinition loaded = repository.get(recipe.key()).orElseThrow();
        assertEquals(IngredientMatchMode.CUSTOM_DATA_ONLY, loaded.matchModes().get(0));
        assertTrue(loaded.matchesItemSample(0, new ItemStack(Material.PAPER)));
        assertFalse(loaded.matchesItemSample(0, new ItemStack(Material.BOOK)));
    }

    @Test
    void brewingBottleAlternativesAndTagModesAreValidated() {
        List<ItemStack> inputs = List.of(new ItemStack(Material.DIRT), new ItemStack(Material.POTION));
        List<List<ItemStack>> alternatives = new ArrayList<>();
        alternatives.add(new ArrayList<>());
        alternatives.add(new ArrayList<>(List.of(new ItemStack(Material.SPLASH_POTION))));
        List<NamespacedKey> tags = Arrays.asList(NamespacedKey.fromString("minecraft:planks"), null);
        assertThrows(IllegalArgumentException.class, () -> new StationRecipeDefinition(key("exact_tag"),
                StationKind.BREWING, inputs, new ItemStack(Material.POTION), "", 20, 0, 0,
                true, false, List.of(IngredientMatchMode.EXACT, IngredientMatchMode.EXACT),
                alternatives, tags));
        alternatives.set(1, List.of(new ItemStack(Material.STONE)));
        assertThrows(IllegalArgumentException.class, () -> new StationRecipeDefinition(key("bad_bottle"),
                StationKind.BREWING, inputs, new ItemStack(Material.POTION), "", 20, 0, 0,
                true, false, List.of(IngredientMatchMode.MATERIAL, IngredientMatchMode.EXACT),
                alternatives, tags));
    }

    @Test
    void stationSchemaSevenStoresChoicesAndBookCategoryAndLoadsOlderSchemas() throws Exception {
        List<ItemStack> inputs = List.of(new ItemStack(Material.OAK_PLANKS));
        List<List<ItemStack>> alternatives = List.of(List.of(new ItemStack(Material.DIRT)));
        StationRecipeDefinition recipe = new StationRecipeDefinition(key("station_choices"),
                StationKind.FURNACE, inputs, new ItemStack(Material.EMERALD), "", 200, 0, 1,
                true, false, List.of(IngredientMatchMode.MATERIAL), alternatives,
                List.of(NamespacedKey.fromString("minecraft:planks")), CookingBookCategory.BLOCKS);
        new StationRecipeRepository(plugin()).save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("stations"))) { file = files.findFirst().orElseThrow(); }
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("schema: 10"));
        assertTrue(yaml.contains("book-category: BLOCKS"));
        assertTrue(yaml.contains("MATERIAL") && yaml.contains("DIRT"));
        assertTrue(yaml.contains("minecraft:planks"));
        String fixture = """
                schema: 3
                id: recipeweaver:station_choices
                kind: FURNACE
                book-category: BLOCKS
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: OAK_PLANKS
                match-modes:
                  '0': MATERIAL
                alternatives:
                  '0':
                  - ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: DIRT
                item-tags:
                  '0': minecraft:planks
                result:
                  ==: org.bukkit.inventory.ItemStack
                  v: 1
                  type: EMERALD
                """;
        Files.writeString(file, fixture);
        StationRecipeRepository reloaded = new StationRecipeRepository(plugin());
        reloaded.load();
        assertEquals(CookingBookCategory.BLOCKS,
                reloaded.get(recipe.key()).orElseThrow().bookCategory());
        Files.writeString(file, fixture.replace("schema: 3", "schema: 2")
                .replace("book-category: BLOCKS", ""));
        reloaded.load();
        assertEquals(CookingBookCategory.MISC,
                reloaded.get(recipe.key()).orElseThrow().bookCategory());
        assertEquals(Material.DIRT,
                reloaded.get(recipe.key()).orElseThrow().alternatives().get(0).get(0).getType());
    }

    @Test
    void stationMatchingChecksAllSlotsAndIgnoresStackAmount() {
        List<ItemStack> expected = Arrays.asList(new ItemStack(Material.STONE), null);
        assertTrue(StationInventoryListener.matches(expected, new ItemStack(Material.STONE, 64), null));
        assertFalse(StationInventoryListener.matches(expected, new ItemStack(Material.DIRT), null));
        assertFalse(StationInventoryListener.matches(expected, new ItemStack(Material.STONE),
                new ItemStack(Material.REDSTONE)));
    }

    @Test
    void settingsAndAtomicWriteRoundTrip() throws Exception {
        SettingsRepository settings = new SettingsRepository(plugin());
        settings.setLockdown(true);
        SettingsRepository reloaded = new SettingsRepository(plugin());
        reloaded.load();
        assertTrue(reloaded.lockdown());
        Path destination = directory.resolve("nested/data.txt");
        RecipeRepository.writeAtomically(destination, "first");
        RecipeRepository.writeAtomically(destination, "second");
        assertEquals("second", Files.readString(destination));
    }

    @Test
    void shapedPreviewAcceptsTranslationAndMirrorButRejectsExtraInputs() {
        List<ItemStack> pattern = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        pattern.set(0, new ItemStack(Material.STONE));
        pattern.set(1, new ItemStack(Material.DIRT));
        List<ItemStack> grid = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        grid.set(4, new ItemStack(Material.DIRT, 64));
        grid.set(5, new ItemStack(Material.STONE));
        assertTrue(RecipeMatcher.matches(RecipeKind.SHAPED, pattern, grid));
        grid.set(8, new ItemStack(Material.REDSTONE));
        assertFalse(RecipeMatcher.matches(RecipeKind.SHAPED, pattern, grid));
    }

    @Test
    void shapelessPreviewCountsDuplicateIngredients() {
        List<ItemStack> pattern = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        pattern.set(0, new ItemStack(Material.STONE));
        pattern.set(4, new ItemStack(Material.STONE));
        List<ItemStack> grid = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        grid.set(2, new ItemStack(Material.STONE, 3));
        assertFalse(RecipeMatcher.matches(RecipeKind.SHAPELESS, pattern, grid));
        grid.set(8, new ItemStack(Material.STONE));
        assertTrue(RecipeMatcher.matches(RecipeKind.SHAPELESS, pattern, grid));
        grid.set(2, new ItemStack(Material.DIRT));
        assertFalse(RecipeMatcher.matches(RecipeKind.SHAPELESS, pattern, grid));
    }

    @Test
    void failedRecipeLoadKeepsThePreviouslyLoadedRecipe() throws Exception {
        RecipeRepository repository = new RecipeRepository(plugin());
        RecipeDefinition recipe = new RecipeDefinition(key("reload_safe"), RecipeKind.SHAPELESS,
                Arrays.asList(new ItemStack(Material.STONE), null, null, null, null, null, null, null, null),
                new ItemStack(Material.DIAMOND), "", true, false);
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("recipes"))) { file = files.findFirst().orElseThrow(); }
        Files.writeString(file, "schema: broken");
        assertThrows(java.io.IOException.class, repository::load);
        assertTrue(repository.get(recipe.key()).isPresent());
    }
    @Test
    void stationShiftTransferIsAllOrNothingAndDoesNotMutateTheOriginalInventory() {
        ItemStack[] storage = new ItemStack[36];
        Arrays.fill(storage, new ItemStack(Material.DIRT, 64));
        assertNull(StationInventoryListener.insert(storage, new ItemStack(Material.DIAMOND)));
        storage[0] = new ItemStack(Material.DIAMOND, 63);
        storage[1] = null;
        ItemStack[] planned = StationInventoryListener.insert(storage, new ItemStack(Material.DIAMOND, 2));
        assertEquals(64, planned[0].getAmount());
        assertEquals(1, planned[1].getAmount());
        assertEquals(63, storage[0].getAmount());
        assertNull(storage[1]);
    }

    @Test
    void stationResultMustHavePositiveAmount() {
        assertThrows(IllegalArgumentException.class, () -> new StationRecipeDefinition(key("zero"),
                StationKind.ANVIL, Arrays.asList(new ItemStack(Material.STONE), null),
                new ItemStack(Material.DIAMOND, 0), "", 200, 0, 1, true, false));
    }

    @Test
    void anvilRulesUseSchemaSevenAndOlderStationRecipesRemainUnlimited() throws Exception {
        List<ItemStack> ingredients = Arrays.asList(new ItemStack(Material.STONE), null);
        StationRecipeDefinition limited = new StationRecipeDefinition(key("limited_anvil"),
                StationKind.ANVIL, ingredients, new ItemStack(Material.DIAMOND), "", 200, 0,
                0, true, false, List.of(IngredientMatchMode.EXACT, IngredientMatchMode.EXACT),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                CookingBookCategory.MISC, 2, 15);
        StationRecipeRepository repository = new StationRecipeRepository(plugin());
        repository.save(limited);
        Path file;
        try (var files = Files.list(directory.resolve("stations"))) { file = files.findFirst().orElseThrow(); }
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("schema: 10"));
        assertTrue(yaml.contains("max-crafts-per-player: 2"));
        assertTrue(yaml.contains("cooldown-seconds: 15"));
        String fixture = """
                schema: 4
                id: recipeweaver:limited_anvil
                kind: ANVIL
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: STONE
                result:
                  ==: org.bukkit.inventory.ItemStack
                  v: 1
                  type: DIAMOND
                max-crafts-per-player: 2
                cooldown-seconds: 15
                """;
        Files.writeString(file, fixture);
        repository.load();
        assertEquals(2, repository.get(limited.key()).orElseThrow().maxCraftsPerPlayer());
        assertEquals(15, repository.get(limited.key()).orElseThrow().cooldownSeconds());
        Files.writeString(file, fixture.replace("schema: 4", "schema: 3")
                .replace("max-crafts-per-player: 2", "")
                .replace("cooldown-seconds: 15", ""));
        repository.load();
        assertEquals(0, repository.get(limited.key()).orElseThrow().maxCraftsPerPlayer());
        assertEquals(0, repository.get(limited.key()).orElseThrow().cooldownSeconds());
        Files.writeString(file, fixture.replace("cooldown-seconds: 15", "cooldown-seconds: invalid"));
        assertThrows(java.io.IOException.class, repository::load);
        assertEquals(0, repository.get(limited.key()).orElseThrow().maxCraftsPerPlayer());
        assertThrows(IllegalArgumentException.class, () -> new StationRecipeDefinition(key("automated"),
                StationKind.FURNACE, List.of(new ItemStack(Material.STONE)), new ItemStack(Material.DIAMOND),
                "", 200, 0, 0, true, false, List.of(IngredientMatchMode.EXACT),
                List.of(List.of()), Arrays.asList((NamespacedKey) null), CookingBookCategory.MISC, 1, 0));
    }

    @Test
    void stationConditionsRoundTripAndOlderSchemaDefaultsToUnrestricted() throws Exception {
        RecipeConditions conditions = new RecipeConditions("recipeweaver.example", "world",
                NamespacedKey.minecraft("plains"), 23000, 1000, WeatherCondition.RAIN,
                7, "crafts", 3, NamespacedKey.minecraft("story/mine_stone"));
        StationRecipeDefinition recipe = new StationRecipeDefinition(key("conditional_anvil"),
                StationKind.ANVIL, Arrays.asList(new ItemStack(Material.STONE), null),
                new ItemStack(Material.DIAMOND), "", 200, 0, 0, true, false,
                List.of(IngredientMatchMode.EXACT, IngredientMatchMode.EXACT),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                CookingBookCategory.MISC, 2, 15, conditions);
        StationRecipeRepository repository = new StationRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("stations"))) { file = files.findFirst().orElseThrow(); }
        String saved = Files.readString(file);
        assertTrue(saved.contains("schema: 10"));
        assertTrue(saved.contains("conditions:"));
        assertTrue(saved.contains("permission: recipeweaver.example"));
        String fixture = """
                schema: 5
                id: recipeweaver:conditional_anvil
                kind: ANVIL
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: STONE
                result:
                  ==: org.bukkit.inventory.ItemStack
                  v: 1
                  type: DIAMOND
                max-crafts-per-player: 2
                cooldown-seconds: 15
                conditions:
                  permission: recipeweaver.example
                  world: world
                  biome: minecraft:plains
                  time-start: 23000
                  time-end: 1000
                  weather: RAIN
                  minimum-level: 7
                  scoreboard-objective: crafts
                  minimum-score: 3
                  advancement: minecraft:story/mine_stone
                """;
        Files.writeString(file, fixture);
        repository.load();
        assertEquals(conditions, repository.get(recipe.key()).orElseThrow().conditions());
        String old = fixture.replace("schema: 5", "schema: 4");
        Files.writeString(file, old.substring(0, old.indexOf("conditions:")));
        repository.load();
        assertEquals(RecipeConditions.NONE, repository.get(recipe.key()).orElseThrow().conditions());
        Files.writeString(file, fixture.replace("permission: recipeweaver.example", "permission: 42"));
        assertThrows(java.io.IOException.class, repository::load);
        assertEquals(RecipeConditions.NONE, repository.get(recipe.key()).orElseThrow().conditions());
        Files.writeString(file, fixture);
        StationRecipeDefinition cooking = new StationRecipeDefinition(key("conditioned_furnace"),
                StationKind.FURNACE, List.of(new ItemStack(Material.STONE)),
                new ItemStack(Material.DIAMOND), "", 200, 0, 0, true, false,
                List.of(IngredientMatchMode.EXACT), List.of(List.of()), Arrays.asList((NamespacedKey) null),
                CookingBookCategory.MISC, 0, 0, conditions);
        repository.save(cooking);
        Path cookingFile;
        try (var files = Files.list(directory.resolve("stations"))) {
            cookingFile = files.filter(candidate -> !candidate.equals(file)).findFirst().orElseThrow();
        }
        assertTrue(Files.readString(cookingFile).contains("schema: 10"));
        Files.writeString(cookingFile, """
                schema: 10
                id: recipeweaver:conditioned_furnace
                kind: FURNACE
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: STONE
                result:
                  ==: org.bukkit.inventory.ItemStack
                  v: 1
                  type: DIAMOND
                result-count: 1
                results:
                  '0':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: DIAMOND
                    weight: 1
                max-crafts-per-player: 0
                cooldown-seconds: 0
                result-metadata-mode: NONE
                consume-slots:
                  '0': true
                optional-slots:
                  '0': false
                conditions:
                  permission: recipeweaver.example
                  world: world
                  biome: minecraft:plains
                  time-start: 23000
                  time-end: 1000
                  weather: RAIN
                  minimum-level: 7
                  scoreboard-objective: crafts
                  minimum-score: 3
                  advancement: minecraft:story/mine_stone
                """);
        repository.load();
        assertEquals(conditions, repository.get(cooking.key()).orElseThrow().conditions());
        StationRecipeDefinition stonecutter = new StationRecipeDefinition(key("conditioned_stonecutter"),
                StationKind.STONECUTTER, List.of(new ItemStack(Material.STONE)),
                new ItemStack(Material.DIAMOND), "", 200, 0, 0, true, false,
                List.of(IngredientMatchMode.EXACT), List.of(List.of()), Arrays.asList((NamespacedKey) null),
                CookingBookCategory.MISC, 0, 0, conditions);
        assertEquals(conditions, stonecutter.conditions());
        StationRecipeDefinition cauldron = new StationRecipeDefinition(key("conditioned_cauldron"),
                StationKind.CAULDRON, List.of(new ItemStack(Material.STONE),
                        new ItemStack(Material.DIRT)),
                new ItemStack(Material.DIAMOND), "", 200, 0, 0, true, false,
                List.of(IngredientMatchMode.EXACT, IngredientMatchMode.EXACT),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                CookingBookCategory.MISC, 0, 0, conditions);
        assertEquals(conditions, cauldron.conditions());
        StationRecipeDefinition brewing = new StationRecipeDefinition(key("conditioned_brewing"),
                StationKind.BREWING, List.of(new ItemStack(Material.BLAZE_POWDER),
                        new ItemStack(Material.POTION)),
                new ItemStack(Material.POTION), "", 200, 0, 0, true, false,
                List.of(IngredientMatchMode.EXACT, IngredientMatchMode.EXACT),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                CookingBookCategory.MISC, 0, 0, conditions);
        assertEquals(conditions, brewing.conditions());
    }

    @Test
    void stationActionsRoundTripAndOlderSchemaDefaultsToNoActions() throws Exception {
        CraftSoundAction sound = new CraftSoundAction(
                NamespacedKey.minecraft("block.note_block.bell"), 1.25F, 0.75F, true);
        CraftAdvancementAction advancement = new CraftAdvancementAction(
                NamespacedKey.minecraft("story/root"));
        CraftCommandAction command = new CraftCommandAction(
                "rw info {player} {uuid}", CraftCommandSender.CONSOLE, false);
        StationRecipeDefinition recipe = new StationRecipeDefinition(key("action_anvil"),
                StationKind.ANVIL, Arrays.asList(new ItemStack(Material.STONE), null),
                new ItemStack(Material.DIAMOND), "", 200, 0, 0, true, false,
                List.of(IngredientMatchMode.EXACT, IngredientMatchMode.EXACT),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                sound, advancement, command);
        StationRecipeRepository repository = new StationRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("stations"))) { file = files.findFirst().orElseThrow(); }
        String saved = Files.readString(file);
        assertTrue(saved.contains("schema: 10"));
        assertTrue(saved.contains("actions:"));
        String fixture = """
                schema: 6
                id: recipeweaver:action_anvil
                kind: ANVIL
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: STONE
                result:
                  ==: org.bukkit.inventory.ItemStack
                  v: 1
                  type: DIAMOND
                max-crafts-per-player: 0
                cooldown-seconds: 0
                actions:
                  sound:
                    key: minecraft:block.note_block.bell
                    volume: 1.25
                    pitch: 0.75
                    per-batch: true
                  advancement: minecraft:story/root
                  command:
                    text: rw info {player} {uuid}
                    sender: CONSOLE
                    per-batch: false
                """;
        Files.writeString(file, fixture);
        repository.load();
        StationRecipeDefinition loaded = repository.get(recipe.key()).orElseThrow();
        assertEquals(sound, loaded.craftSound());
        assertEquals(advancement, loaded.craftAdvancement());
        assertEquals(command, loaded.craftCommand());
        Files.writeString(file, fixture.replace("schema: 6", "schema: 5"));
        repository.load();
        StationRecipeDefinition legacy = repository.get(recipe.key()).orElseThrow();
        assertNull(legacy.craftSound());
        assertNull(legacy.craftAdvancement());
        assertNull(legacy.craftCommand());
        Files.writeString(file, fixture.replace("sender: CONSOLE", "sender: UNKNOWN"));
        assertThrows(java.io.IOException.class, repository::load);
        assertNull(repository.get(recipe.key()).orElseThrow().craftCommand());
        assertThrows(IllegalArgumentException.class, () -> new StationRecipeDefinition(
                key("automated_action"), StationKind.FURNACE,
                List.of(new ItemStack(Material.STONE)), new ItemStack(Material.DIAMOND),
                "", 200, 0, 0, true, false, List.of(IngredientMatchMode.EXACT),
                List.of(List.of()), Arrays.asList((NamespacedKey) null),
                CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                sound, advancement, null));
    }

    @Test
    void stationResultMetadataCopiesActualInputAndSchemaSevenValidatesItsSource() throws Exception {
        ItemStack source = new ItemStack(Material.DIRT);
        ItemMeta sourceMeta = source.getItemMeta();
        sourceMeta.setDisplayName("Copied source");
        sourceMeta.setLore(List.of("Copied lore"));
        NamespacedKey dataKey = key("copy_marker");
        sourceMeta.getPersistentDataContainer().set(dataKey, PersistentDataType.STRING, "copied");
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.DIAMOND);
        ItemStack result = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.NAME_AND_LORE);
        assertEquals("Copied source", result.getItemMeta().getDisplayName());
        assertEquals(List.of("Copied lore"), result.getItemMeta().getLore());
        assertNull(result.getItemMeta().getPersistentDataContainer()
                .get(dataKey, PersistentDataType.STRING));
        assertFalse(template.getItemMeta().hasDisplayName());
        assertEquals("copied", source.getItemMeta().getPersistentDataContainer()
                .get(dataKey, PersistentDataType.STRING));

        StationRecipeDefinition recipe = new StationRecipeDefinition(key("copy_grindstone"),
                StationKind.GRINDSTONE, List.of(new ItemStack(Material.STONE), new ItemStack(Material.DIRT)),
                template, "", 200, 0, 0, true, false,
                List.of(IngredientMatchMode.EXACT, IngredientMatchMode.MATERIAL),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                null, null, null, 1, ResultMetadataMode.NAME_LORE_CUSTOM_DATA);
        StationRecipeRepository repository = new StationRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("stations"))) { file = files.findFirst().orElseThrow(); }
        String saved = Files.readString(file);
        assertTrue(saved.contains("schema: 10")
                && saved.contains("result-metadata-mode: NAME_LORE_CUSTOM_DATA")
                && saved.contains("result-copy-source-slot: 1"));
        String fixture = """
                schema: 7
                id: recipeweaver:copy_grindstone
                kind: GRINDSTONE
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: STONE
                  '1':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: DIRT
                result:
                  ==: org.bukkit.inventory.ItemStack
                  v: 1
                  type: DIAMOND
                max-crafts-per-player: 0
                cooldown-seconds: 0
                result-metadata-mode: NAME_LORE_CUSTOM_DATA
                result-copy-source-slot: 1
                """;
        Files.writeString(file, fixture);
        repository.load();
        StationRecipeDefinition loaded = repository.get(recipe.key()).orElseThrow();
        assertEquals(ResultMetadataMode.NAME_LORE_CUSTOM_DATA, loaded.resultMetadataMode());
        assertEquals(1, loaded.resultCopySourceSlot());
        Files.writeString(file, fixture.replace("schema: 7", "schema: 6")
                .replace("result-metadata-mode: NAME_LORE_CUSTOM_DATA", "")
                .replace("result-copy-source-slot: 1", ""));
        repository.load();
        StationRecipeDefinition legacy = repository.get(recipe.key()).orElseThrow();
        assertEquals(ResultMetadataMode.NONE, legacy.resultMetadataMode());
        assertEquals(-1, legacy.resultCopySourceSlot());
        Files.writeString(file, fixture.replace("result-copy-source-slot: 1",
                "result-copy-source-slot: 2"));
        assertThrows(java.io.IOException.class, repository::load);
        assertEquals(ResultMetadataMode.NONE,
                repository.get(recipe.key()).orElseThrow().resultMetadataMode());
        assertThrows(IllegalArgumentException.class, () -> new StationRecipeDefinition(
                key("copy_furnace"), StationKind.FURNACE, List.of(new ItemStack(Material.STONE)),
                template, "", 200, 0, 0, true, false,
                List.of(IngredientMatchMode.EXACT), List.of(List.of()),
                Arrays.asList((NamespacedKey) null), CookingBookCategory.MISC,
                0, 0, RecipeConditions.NONE, null, null, null,
                0, ResultMetadataMode.NAME));
    }

    @Test
    void stationIngredientAmountsCatalystsAndReturnsArePlannedAndPersisted() throws Exception {
        StationRecipeDefinition recipe = new StationRecipeDefinition(key("ingredient_anvil"),
                StationKind.ANVIL,
                List.of(new ItemStack(Material.STONE, 3), new ItemStack(Material.GLASS_BOTTLE)),
                new ItemStack(Material.DIAMOND), "", 200, 0, 0, true, false,
                List.of(IngredientMatchMode.MATERIAL, IngredientMatchMode.EXACT),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                null, null, null, -1, ResultMetadataMode.NONE,
                List.of(true, false), Arrays.asList(new ItemStack(Material.BUCKET), null));
        assertFalse(StationInventoryListener.matches(recipe, new ItemStack(Material.STONE, 2),
                new ItemStack(Material.GLASS_BOTTLE)));
        assertTrue(StationInventoryListener.matches(recipe, new ItemStack(Material.STONE, 5),
                new ItemStack(Material.GLASS_BOTTLE)));
        ItemStack[] storage = new ItemStack[36];
        StationInventoryListener.InputPlan plan = StationInventoryListener.planInputs(recipe,
                new ItemStack(Material.STONE, 5), new ItemStack(Material.GLASS_BOTTLE), storage);
        assertEquals(2, plan.first().getAmount());
        assertEquals(Material.GLASS_BOTTLE, plan.second().getType());
        assertEquals(Material.BUCKET, plan.storage()[0].getType());
        assertNull(storage[0]);
        StationInventoryListener.InputPlan replaced = StationInventoryListener.planInputs(recipe,
                new ItemStack(Material.STONE, 3), new ItemStack(Material.GLASS_BOTTLE), storage);
        assertEquals(Material.BUCKET, replaced.first().getType());
        assertNull(replaced.storage()[0]);
        Arrays.fill(storage, new ItemStack(Material.COBBLESTONE, 64));
        assertNull(StationInventoryListener.planInputs(recipe, new ItemStack(Material.STONE, 5),
                new ItemStack(Material.GLASS_BOTTLE), storage));

        StationRecipeRepository repository = new StationRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("stations"))) { file = files.findFirst().orElseThrow(); }
        String saved = Files.readString(file);
        assertTrue(saved.contains("schema: 10") && saved.contains("consume-slots:")
                && saved.contains("optional-slots:"));
        Files.writeString(file, """
                schema: 8
                id: recipeweaver:ingredient_anvil
                kind: ANVIL
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: STONE
                    amount: 3
                  '1':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: GLASS_BOTTLE
                result:
                  ==: org.bukkit.inventory.ItemStack
                  v: 1
                  type: DIAMOND
                max-crafts-per-player: 0
                cooldown-seconds: 0
                result-metadata-mode: NONE
                consume-slots:
                  '0': true
                  '1': false
                remainders:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: BUCKET
                """);
        repository.load();
        StationRecipeDefinition loaded = repository.get(recipe.key()).orElseThrow();
        assertEquals(3, loaded.ingredients().get(0).getAmount());
        assertEquals(List.of(true, false), loaded.consumeSlots());
        assertEquals(Material.BUCKET, loaded.remainder(0).getType());
        assertThrows(IllegalArgumentException.class, () -> new StationRecipeDefinition(
                key("free_anvil"), StationKind.ANVIL, recipe.ingredients(), recipe.result(),
                "", 200, 0, 0, true, false, recipe.matchModes(), recipe.alternatives(),
                recipe.itemTags(), CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                null, null, null, -1, ResultMetadataMode.NONE,
                List.of(false, false), Arrays.asList(null, null)));
    }

    @Test
    void stationOptionalInputAndPartialMetadataAreEnforcedAndSchemaNineLoads() throws Exception {
        ItemStack sample = new ItemStack(Material.CLAY_BALL);
        ItemMeta sampleMeta = sample.getItemMeta();
        sampleMeta.setDisplayName("Marked clay");
        sample.setItemMeta(sampleMeta);
        ItemStack actual = sample.clone();
        ItemMeta actualMeta = actual.getItemMeta();
        actualMeta.setLore(List.of("Extra detail"));
        actual.setItemMeta(actualMeta);
        StationRecipeDefinition recipe = new StationRecipeDefinition(key("optional_partial_anvil"),
                StationKind.ANVIL, List.of(sample, new ItemStack(Material.REDSTONE, 2)),
                new ItemStack(Material.GOLD_INGOT), "", 200, 0, 0, true, false,
                List.of(IngredientMatchMode.PARTIAL, IngredientMatchMode.MATERIAL),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                null, null, null, -1, ResultMetadataMode.NONE,
                List.of(true, true), Arrays.asList(null, null), List.of(false, true));
        assertTrue(StationInventoryListener.matches(recipe, actual, null));
        assertTrue(StationInventoryListener.matches(recipe, actual, new ItemStack(Material.REDSTONE, 2)));
        assertFalse(StationInventoryListener.matches(recipe, actual, new ItemStack(Material.REDSTONE)));
        assertFalse(StationInventoryListener.matches(recipe, actual, new ItemStack(Material.DIRT, 2)));
        assertFalse(StationInventoryListener.matches(recipe, new ItemStack(Material.CLAY_BALL), null));
        StationInventoryListener.InputPlan absent = StationInventoryListener.planInputs(recipe,
                actual, null, new ItemStack[36]);
        assertNull(absent.first());
        assertNull(absent.second());
        StationInventoryListener.InputPlan present = StationInventoryListener.planInputs(recipe,
                actual, new ItemStack(Material.REDSTONE, 3), new ItemStack[36]);
        assertEquals(1, present.second().getAmount());
        assertThrows(IllegalArgumentException.class, () -> new StationRecipeDefinition(
                key("optional_only_consume"), StationKind.ANVIL, recipe.ingredients(), recipe.result(),
                "", 200, 0, 0, true, false, recipe.matchModes(), recipe.alternatives(),
                recipe.itemTags(), CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                null, null, null, -1, ResultMetadataMode.NONE,
                List.of(false, true), Arrays.asList(null, null), List.of(false, true)));
        assertThrows(IllegalArgumentException.class, () -> new StationRecipeDefinition(
                key("optional_copy_source"), StationKind.ANVIL, recipe.ingredients(), recipe.result(),
                "", 200, 0, 0, true, false, recipe.matchModes(), recipe.alternatives(),
                recipe.itemTags(), CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                null, null, null, 1, ResultMetadataMode.NAME,
                List.of(true, true), Arrays.asList(null, null), List.of(false, true)));

        StationRecipeRepository repository = new StationRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("stations"))) { file = files.findFirst().orElseThrow(); }
        assertTrue(Files.readString(file).contains("schema: 10"));
        String fixture = """
                schema: 9
                id: recipeweaver:optional_partial_anvil
                kind: ANVIL
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: CLAY_BALL
                  '1':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: REDSTONE
                    amount: 2
                result:
                  ==: org.bukkit.inventory.ItemStack
                  v: 1
                  type: GOLD_INGOT
                match-modes:
                  '0': PARTIAL
                  '1': MATERIAL
                max-crafts-per-player: 0
                cooldown-seconds: 0
                result-metadata-mode: NONE
                consume-slots:
                  '0': true
                  '1': true
                optional-slots:
                  '0': false
                  '1': true
                """;
        Files.writeString(file, fixture);
        repository.load();
        StationRecipeDefinition loaded = repository.get(recipe.key()).orElseThrow();
        assertEquals(IngredientMatchMode.PARTIAL, loaded.matchModes().get(0));
        assertEquals(List.of(false, true), loaded.optionalSlots());
        Files.writeString(file, fixture.replace("optional-slots:\n  '0': false\n  '1': true\n",
                "optional-slots:\n  '0': false\n"));
        assertThrows(java.io.IOException.class, repository::load);
        assertEquals(List.of(false, true), repository.get(recipe.key()).orElseThrow().optionalSlots());
    }

    @Test
    void weightedStationResultsPersistWithDeterministicTicketsAndRejectInvalidShapes() throws Exception {
        ItemStack gold = new ItemStack(Material.GOLD_INGOT, 2);
        List<WeightedResult> choices = new ArrayList<>(List.of(
                new WeightedResult(gold, 1),
                new WeightedResult(new ItemStack(Material.EMERALD), 3)));
        StationRecipeDefinition recipe = StationRecipeDefinition.withResults(key("weighted_anvil"),
                StationKind.ANVIL, Arrays.asList(new ItemStack(Material.FLINT), null),
                choices, "smoke", 200, 0, 0, true, false);
        gold.setType(Material.DIRT);
        choices.clear();
        recipe.resultChoices().get(0).item().setType(Material.DIRT);
        assertEquals(Material.GOLD_INGOT, recipe.result().getType());
        assertEquals(4, recipe.totalResultWeight());
        assertEquals(Material.GOLD_INGOT, recipe.resultForTicket(0).getType());
        for (int ticket = 1; ticket < 4; ticket++) {
            assertEquals(Material.EMERALD, recipe.resultForTicket(ticket).getType());
        }
        assertThrows(IllegalArgumentException.class, () -> recipe.resultForTicket(4));
        assertThrows(IllegalArgumentException.class, () -> StationRecipeDefinition.withResults(
                key("weighted_furnace"), StationKind.FURNACE,
                List.of(new ItemStack(Material.RAW_IRON)),
                List.of(new WeightedResult(new ItemStack(Material.IRON_INGOT), 1),
                        new WeightedResult(new ItemStack(Material.GOLD_INGOT), 1)),
                "", 200, 0, 0, true, false));

        StationRecipeRepository repository = new StationRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("stations"))) { file = files.findFirst().orElseThrow(); }
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("schema: 10") && yaml.contains("result-count: 2"));
        String fixture = """
                schema: 10
                id: recipeweaver:weighted_anvil
                kind: ANVIL
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: FLINT
                result:
                  ==: org.bukkit.inventory.ItemStack
                  v: 1
                  type: GOLD_INGOT
                  amount: 2
                result-count: 2
                results:
                  '0':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: GOLD_INGOT
                      amount: 2
                    weight: 1
                  '1':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: EMERALD
                    weight: 3
                max-crafts-per-player: 0
                cooldown-seconds: 0
                result-metadata-mode: NONE
                consume-slots:
                  '0': true
                  '1': true
                optional-slots:
                  '0': false
                  '1': false
                """;
        Files.writeString(file, fixture);
        repository.load();
        StationRecipeDefinition loaded = repository.get(recipe.key()).orElseThrow();
        assertEquals(2, loaded.resultChoices().size());
        assertEquals(3, loaded.resultChoices().get(1).weight());
        assertEquals(Material.EMERALD, loaded.resultForTicket(3).getType());
        Files.writeString(file, fixture.replace("result-count: 2", "result-count: 17"));
        assertThrows(java.io.IOException.class, repository::load);
        assertEquals(2, repository.get(recipe.key()).orElseThrow().resultChoices().size());
    }

    @Test
    void weightedCauldronOutcomesPersistAndSelectOneTicketRange() throws Exception {
        StationRecipeDefinition recipe = StationRecipeDefinition.withResults(key("weighted_cauldron"),
                StationKind.CAULDRON, List.of(new ItemStack(Material.FLINT),
                        new ItemStack(Material.BONE_MEAL)),
                List.of(new WeightedResult(new ItemStack(Material.GOLD_NUGGET), 2),
                        new WeightedResult(new ItemStack(Material.DIAMOND), 1)),
                "", 200, 0, 0, true, false);
        assertEquals(3, recipe.totalResultWeight());
        assertEquals(Material.GOLD_NUGGET, recipe.resultForTicket(0).getType());
        assertEquals(Material.GOLD_NUGGET, recipe.resultForTicket(1).getType());
        assertEquals(Material.DIAMOND, recipe.resultForTicket(2).getType());
        recipe.resultForTicket(2).setType(Material.DIRT);
        assertEquals(Material.DIAMOND, recipe.resultForTicket(2).getType());
        StationRecipeRepository repository = new StationRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("stations"))) { file = files.findFirst().orElseThrow(); }
        String saved = Files.readString(file);
        assertTrue(saved.contains("schema: 10") && saved.contains("result-count: 2")
                && saved.contains("weight: 2") && saved.contains("weight: 1"));
        Files.writeString(file, """
                schema: 10
                id: recipeweaver:weighted_cauldron
                kind: CAULDRON
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: FLINT
                  '1':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: BONE_MEAL
                result:
                  ==: org.bukkit.inventory.ItemStack
                  v: 1
                  type: GOLD_NUGGET
                result-count: 2
                results:
                  '0':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: GOLD_NUGGET
                    weight: 2
                  '1':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: DIAMOND
                    weight: 1
                max-crafts-per-player: 0
                cooldown-seconds: 0
                result-metadata-mode: NONE
                consume-slots:
                  '0': true
                  '1': true
                optional-slots:
                  '0': false
                  '1': false
                """);
        repository.load();
        StationRecipeDefinition loaded = repository.get(recipe.key()).orElseThrow();
        assertEquals(2, loaded.resultChoices().size());
        assertEquals(3, loaded.totalResultWeight());
        assertEquals(Material.DIAMOND, loaded.resultForTicket(2).getType());
    }

    @Test
    void weightedBrewingOutcomesPersistAndEveryBottleResultHasOneItem() throws Exception {
        NamespacedKey key = key("weighted_brewing");
        List<ItemStack> ingredients = List.of(new ItemStack(Material.DIRT),
                new ItemStack(Material.POTION));
        StationRecipeDefinition recipe = StationRecipeDefinition.withResults(key,
                StationKind.BREWING, ingredients,
                List.of(new WeightedResult(new ItemStack(Material.POTION), 1),
                        new WeightedResult(new ItemStack(Material.SPLASH_POTION), 2)),
                "smoke", 20, 0, 0, true, false);
        assertEquals(3, recipe.totalResultWeight());
        assertEquals(Material.POTION, recipe.resultForTicket(0).getType());
        assertEquals(Material.SPLASH_POTION, recipe.resultForTicket(1).getType());
        assertEquals(Material.SPLASH_POTION, recipe.resultForTicket(2).getType());
        assertThrows(IllegalArgumentException.class, () -> StationRecipeDefinition.withResults(
                key, StationKind.BREWING, ingredients,
                List.of(new WeightedResult(new ItemStack(Material.POTION), 1),
                        new WeightedResult(new ItemStack(Material.GLASS_BOTTLE, 2), 1)),
                "smoke", 20, 0, 0, true, false));

        StationRecipeRepository repository = new StationRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("stations"))) {
            file = files.findFirst().orElseThrow();
        }
        String saved = Files.readString(file);
        assertTrue(saved.contains("schema: 10") && saved.contains("result-count: 2")
                && saved.contains("weight: 2"));
        Files.writeString(file, """
                schema: 10
                id: recipeweaver:weighted_brewing
                kind: BREWING
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: DIRT
                  '1':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: POTION
                result:
                  ==: org.bukkit.inventory.ItemStack
                  v: 1
                  type: POTION
                result-count: 2
                results:
                  '0':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: POTION
                    weight: 1
                  '1':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: SPLASH_POTION
                    weight: 2
                max-crafts-per-player: 0
                cooldown-seconds: 0
                result-metadata-mode: NONE
                consume-slots:
                  '0': true
                  '1': true
                optional-slots:
                  '0': false
                  '1': false
                """);
        repository.load();
        StationRecipeDefinition loaded = repository.get(key).orElseThrow();
        assertEquals(2, loaded.resultChoices().size());
        assertEquals(3, loaded.totalResultWeight());
        assertEquals(Material.SPLASH_POTION, loaded.resultForTicket(2).getType());
    }

    @Test
    void customItemCatalogCanRemoveAStoredItem() throws Exception {
        ItemRepository items = new ItemRepository(plugin());
        NamespacedKey id = key("catalog_item");
        items.save(id, new ItemStack(Material.DIAMOND));
        assertTrue(items.keys().contains(id));
        assertTrue(items.get(id).isPresent());
        assertTrue(items.remove(id));
        assertFalse(items.get(id).isPresent());
        assertFalse(items.remove(id));
    }

    @Test
    void fullItemDataCopiesPotionComponentsAndKeepsConfiguredResultAmount() {
        ItemStack source = new ItemStack(Material.POTION);
        PotionMeta metadata = (PotionMeta) source.getItemMeta();
        metadata.setColor(Color.fromRGB(40, 80, 160));
        metadata.addCustomEffect(new PotionEffect(PotionEffectType.SPEED, 200, 1), true);
        metadata.setDisplayName("Source potion");
        metadata.getPersistentDataContainer().set(key("full_item_marker"),
                PersistentDataType.STRING, "from source");
        source.setItemMeta(metadata);
        ItemStack template = new ItemStack(Material.POTION);
        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.ALL_ITEM_DATA);
        assertTrue(source.isSimilar(copied));
        assertEquals(1, copied.getAmount());
        assertEquals(1, source.getAmount());
        assertFalse(template.getItemMeta().hasDisplayName());
        assertEquals(Color.fromRGB(40, 80, 160), ((PotionMeta) copied.getItemMeta()).getColor());
        assertEquals("from source", copied.getItemMeta().getPersistentDataContainer()
                .get(key("full_item_marker"), PersistentDataType.STRING));
        ItemStack paper = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER, 3),
                new ItemStack(Material.PAPER, 2), ResultMetadataMode.ALL_ITEM_DATA);
        assertEquals(2, paper.getAmount());
        assertThrows(IllegalArgumentException.class, () -> GridResultMetadata.copyFrom(
                source, new ItemStack(Material.DIAMOND), ResultMetadataMode.ALL_ITEM_DATA));
    }

    @Test
    void fullItemDataModeValidatesAndPersistsForGridAndOwnedStation() throws Exception {
        GridRecipeDefinition grid = fullItemGrid(Material.PAPER, List.of());
        GridRecipeRepository grids = new GridRecipeRepository(plugin());
        grids.save(grid);
        Path gridFile;
        try (var files = Files.list(directory.resolve("grid-recipes"))) {
            gridFile = files.findFirst().orElseThrow();
        }
        assertTrue(Files.readString(gridFile).contains("result-metadata-mode: ALL_ITEM_DATA"));
        Files.writeString(gridFile, """
                schema: 14
                id: recipeweaver:full_item_grid
                tier: ELITE
                kind: SHAPED
                size: 2
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: PAPER
                result-count: 1
                results:
                  '0':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: PAPER
                      amount: 2
                    weight: 1
                result-metadata-mode: ALL_ITEM_DATA
                result-copy-source-slot: 0
                """);
        grids.load();
        assertEquals(ResultMetadataMode.ALL_ITEM_DATA,
                grids.get(grid.key()).orElseThrow().resultMetadataMode());
        assertThrows(IllegalArgumentException.class,
                () -> fullItemGrid(Material.DIAMOND, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> fullItemGrid(Material.PAPER, List.of(new ItemStack(Material.DIAMOND))));
        assertThrows(IllegalArgumentException.class,
                () -> fullItemGrid(Material.PAPER, List.of(), true));

        StationRecipeDefinition station = new StationRecipeDefinition(key("full_item_anvil"),
                StationKind.ANVIL, Arrays.asList(new ItemStack(Material.PAPER), null),
                new ItemStack(Material.PAPER, 2), "", 200, 0, 0, true, false,
                List.of(IngredientMatchMode.MATERIAL, IngredientMatchMode.EXACT),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                null, null, null, 0, ResultMetadataMode.ALL_ITEM_DATA);
        StationRecipeRepository stations = new StationRecipeRepository(plugin());
        stations.save(station);
        Path stationFile;
        try (var files = Files.list(directory.resolve("stations"))) {
            stationFile = files.findFirst().orElseThrow();
        }
        assertTrue(Files.readString(stationFile).contains("result-metadata-mode: ALL_ITEM_DATA"));
        Files.writeString(stationFile, """
                schema: 9
                id: recipeweaver:full_item_anvil
                kind: ANVIL
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: PAPER
                result:
                  ==: org.bukkit.inventory.ItemStack
                  v: 1
                  type: PAPER
                  amount: 2
                max-crafts-per-player: 0
                cooldown-seconds: 0
                consume-slots:
                  '0': true
                  '1': true
                optional-slots:
                  '0': false
                  '1': false
                result-metadata-mode: ALL_ITEM_DATA
                result-copy-source-slot: 0
                """);
        stations.load();
        assertEquals(ResultMetadataMode.ALL_ITEM_DATA,
                stations.get(station.key()).orElseThrow().resultMetadataMode());
        assertThrows(IllegalArgumentException.class, () -> new StationRecipeDefinition(
                key("full_item_wrong_material"), StationKind.GRINDSTONE,
                Arrays.asList(new ItemStack(Material.PAPER), null),
                new ItemStack(Material.DIAMOND), "", 200, 0, 0, true, false,
                List.of(IngredientMatchMode.MATERIAL, IngredientMatchMode.EXACT),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                null, null, null, 0, ResultMetadataMode.ALL_ITEM_DATA));
    }

    private GridRecipeDefinition fullItemGrid(Material output, List<ItemStack> sourceAlternatives) {
        return fullItemGrid(output, sourceAlternatives, false);
    }

    private GridRecipeDefinition fullItemGrid(Material output, List<ItemStack> sourceAlternatives,
                                              boolean optionalSource) {
        List<List<ItemStack>> alternatives = new ArrayList<>(Collections.nCopies(4, List.of()));
        alternatives.set(0, sourceAlternatives);
        List<Boolean> optional = new ArrayList<>(Collections.nCopies(4, false));
        optional.set(0, optionalSource);
        return new GridRecipeDefinition(key("full_item_grid"), GridTier.ELITE,
                RecipeKind.SHAPED, 2,
                Arrays.asList(new ItemStack(Material.PAPER), null, null, null),
                List.of(new WeightedResult(new ItemStack(output, 2), 1)),
                "", true, false, Collections.nCopies(4, IngredientMatchMode.MATERIAL),
                0, true, alternatives, Collections.nCopies(4, null),
                Collections.nCopies(4, true), Collections.nCopies(4, null),
                optional, RecipeConditions.NONE,
                null, null, null, 64, 0, 0, ResultMetadataMode.ALL_ITEM_DATA);
    }
    private NamespacedKey key(String value) { return new NamespacedKey("recipeweaver", value); }

    private Plugin plugin() {
        File root = directory.toFile();
        Logger logger = Logger.getLogger("RecipeWeaverTest");
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getDataFolder" -> root;
                    case "getName" -> "RecipeWeaver";
                    case "getLogger" -> logger;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
