package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
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
import dev.recipeweaver.api.WeatherCondition;
import dev.recipeweaver.api.WeightedResult;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GridRecipeTest {
    @TempDir Path directory;

    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test
    void eliteSixBySixMatchesShiftedAndMirroredPatternWithoutExtraItems() {
        List<ItemStack> pattern = empty(6);
        pattern.set(0, new ItemStack(Material.STONE));
        pattern.set(1, new ItemStack(Material.DIRT));
        GridRecipeDefinition recipe = grid("six", GridTier.ELITE, RecipeKind.SHAPED, 6, pattern);
        List<ItemStack> actual = empty(6);
        actual.set(4 * 6 + 3, new ItemStack(Material.DIRT, 64));
        actual.set(4 * 6 + 4, new ItemStack(Material.STONE));
        assertTrue(GridMatcher.matches(recipe, actual));
        actual.set(5 * 6 + 5, new ItemStack(Material.REDSTONE));
        assertFalse(GridMatcher.matches(recipe, actual));
    }

    @Test
    void shapelessGridCountsDuplicateIngredients() {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE));
        pattern.set(1, new ItemStack(Material.STONE));
        GridRecipeDefinition recipe = grid("duplicates", GridTier.ELITE, RecipeKind.SHAPELESS, 2, pattern);
        List<ItemStack> actual = empty(2);
        actual.set(2, new ItemStack(Material.STONE, 4));
        assertFalse(GridMatcher.matches(recipe, actual));
        actual.set(3, new ItemStack(Material.STONE));
        assertTrue(GridMatcher.matches(recipe, actual));
    }

    @Test
    void shapelessAmountsFindAOneToOneAssignmentAndConsumeExactly() {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE, 2));
        pattern.set(1, new ItemStack(Material.STONE, 5));
        GridRecipeDefinition recipe = grid("amounts", GridTier.ELITE, RecipeKind.SHAPELESS, 2, pattern);
        List<ItemStack> actual = empty(2);
        actual.set(0, new ItemStack(Material.STONE, 7));
        actual.set(3, new ItemStack(Material.STONE, 4));
        int[] counts = GridMatcher.consumption(recipe, actual);
        assertArrayEquals(new int[] {5, 0, 0, 2}, counts);
        List<ItemStack> remainder = GridMatcher.consume(actual, counts);
        assertEquals(2, remainder.get(0).getAmount());
        assertEquals(2, remainder.get(3).getAmount());
        assertEquals(7, actual.get(0).getAmount());
        assertEquals(4, actual.get(3).getAmount());

        actual.set(0, new ItemStack(Material.STONE, 4));
        assertFalse(GridMatcher.matches(recipe, actual));
    }

    @Test
    void shapedAmountsFollowMirroredTranslatedSlots() {
        List<ItemStack> pattern = empty(3);
        pattern.set(0, new ItemStack(Material.STONE, 3));
        pattern.set(1, new ItemStack(Material.DIRT, 2));
        GridRecipeDefinition recipe = grid("shape_amounts", GridTier.ADVANCED, RecipeKind.SHAPED, 3, pattern);
        List<ItemStack> actual = empty(3);
        actual.set(7, new ItemStack(Material.DIRT, 5));
        actual.set(8, new ItemStack(Material.STONE, 4));
        assertArrayEquals(new int[] {0, 0, 0, 0, 0, 0, 0, 2, 3},
                GridMatcher.consumption(recipe, actual));
        List<ItemStack> remainder = GridMatcher.consume(actual, GridMatcher.consumption(recipe, actual));
        assertEquals(3, remainder.get(7).getAmount());
        assertEquals(1, remainder.get(8).getAmount());
        actual.get(8).setAmount(2);
        assertFalse(GridMatcher.matches(recipe, actual));
    }

    @Test
    void materialModeIgnoresMetadataButStillRequiresTheStackAmount() {
        List<ItemStack> pattern = empty(2);
        ItemStack namedStone = new ItemStack(Material.STONE, 3);
        ItemMeta meta = namedStone.getItemMeta();
        meta.setDisplayName("Named sample");
        namedStone.setItemMeta(meta);
        pattern.set(0, namedStone);
        List<ItemStack> actual = empty(2);
        actual.set(3, new ItemStack(Material.STONE, 4));
        GridRecipeDefinition exact = grid("exact", GridTier.ELITE, RecipeKind.SHAPED, 2, pattern);
        assertFalse(GridMatcher.matches(exact, actual));

        List<IngredientMatchMode> modes = new ArrayList<>(Arrays.asList(
                IngredientMatchMode.MATERIAL, IngredientMatchMode.EXACT,
                IngredientMatchMode.EXACT, IngredientMatchMode.EXACT));
        GridRecipeDefinition material = new GridRecipeDefinition(exact.key(), exact.tier(), exact.kind(),
                exact.size(), pattern, exact.result(), "", true, false, modes, 0, true);
        assertArrayEquals(new int[] {0, 0, 0, 3}, GridMatcher.consumption(material, actual));
        actual.get(3).setAmount(2);
        assertFalse(GridMatcher.matches(material, actual));
        actual.set(3, new ItemStack(Material.DIRT, 4));
        assertFalse(GridMatcher.matches(material, actual));
    }

    @Test
    void partialModeRequiresSampleMetadataButAllowsExtraComponents() {
        List<ItemStack> pattern = empty(2);
        ItemStack sample = new ItemStack(Material.PAPER, 2);
        ItemMeta sampleMeta = sample.getItemMeta();
        sampleMeta.setDisplayName("Pattern blade");
        NamespacedKey marker = new NamespacedKey("recipeweaver", "ingredient_marker");
        sampleMeta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, "required");
        NamespacedKey listMarker = new NamespacedKey("recipeweaver", "ingredient_list");
        sampleMeta.getPersistentDataContainer().set(listMarker,
                PersistentDataType.LIST.strings(), List.of("first", "second"));
        sample.setItemMeta(sampleMeta);
        pattern.set(0, sample);
        GridRecipeDefinition recipe = new GridRecipeDefinition(
                new NamespacedKey("recipeweaver", "partial_components"), GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern, new ItemStack(Material.EMERALD), "", true,
                false, List.of(IngredientMatchMode.PARTIAL, IngredientMatchMode.EXACT,
                        IngredientMatchMode.EXACT, IngredientMatchMode.EXACT), 0, true);

        ItemStack actualItem = sample.clone();
        actualItem.setAmount(3);
        ItemMeta actualMeta = actualItem.getItemMeta();
        actualMeta.setLore(List.of("Additional lore"));
        actualMeta.getPersistentDataContainer().set(
                new NamespacedKey("recipeweaver", "extra_marker"), PersistentDataType.INTEGER, 7);
        actualItem.setItemMeta(actualMeta);
        List<ItemStack> actual = empty(2);
        actual.set(3, actualItem);
        assertArrayEquals(new int[] {0, 0, 0, 2}, GridMatcher.consumption(recipe, actual));

        actualItem.setAmount(1);
        assertFalse(GridMatcher.matches(recipe, actual));
        actualItem.setAmount(3);
        actualMeta = actualItem.getItemMeta();
        actualMeta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, "different");
        actualItem.setItemMeta(actualMeta);
        assertFalse(GridMatcher.matches(recipe, actual));
        actualMeta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, "required");
        actualMeta.getPersistentDataContainer().set(listMarker,
                PersistentDataType.LIST.strings(), List.of("different"));
        actualItem.setItemMeta(actualMeta);
        assertFalse(GridMatcher.matches(recipe, actual));
        actualMeta.getPersistentDataContainer().set(listMarker,
                PersistentDataType.LIST.strings(), List.of("first", "second"));
        actualMeta.setDisplayName("Wrong blade");
        actualItem.setItemMeta(actualMeta);
        assertFalse(GridMatcher.matches(recipe, actual));
        actualItem.setType(Material.MAP);
        assertFalse(GridMatcher.matches(recipe, actual));
    }

    @Test
    void currentSchemaWritesPartialModeAndSchemaNineDefaultsToExact() throws Exception {
        List<ItemStack> pattern = empty(2);
        ItemStack sample = new ItemStack(Material.STONE);
        ItemMeta meta = sample.getItemMeta();
        meta.setDisplayName("Partial stone");
        sample.setItemMeta(meta);
        pattern.set(0, sample);
        GridRecipeDefinition recipe = new GridRecipeDefinition(
                new NamespacedKey("recipeweaver", "partial_round_trip"), GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern, new ItemStack(Material.EMERALD), "", true,
                false, List.of(IngredientMatchMode.PARTIAL, IngredientMatchMode.EXACT,
                        IngredientMatchMode.EXACT, IngredientMatchMode.EXACT), 0, true);
        GridRecipeRepository repository = new GridRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("grid-recipes"))) {
            file = files.findFirst().orElseThrow();
        }
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("schema: 14") && yaml.contains("PARTIAL"));
        String fixture = """
                schema: 10
                id: recipeweaver:partial_round_trip
                tier: ELITE
                kind: SHAPED
                size: 2
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: STONE
                match-modes:
                  '0': PARTIAL
                result-count: 1
                results:
                  '0':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: EMERALD
                    weight: 1
                """;
        Files.writeString(file, fixture);
        repository.load();
        assertEquals(IngredientMatchMode.PARTIAL,
                repository.get(recipe.key()).orElseThrow().matchModes().get(0));

        Files.writeString(file, fixture.replace("schema: 10", "schema: 9")
                .replace("match-modes:\n  '0': PARTIAL\n", ""));
        repository.load();
        assertEquals(IngredientMatchMode.EXACT,
                repository.get(recipe.key()).orElseThrow().matchModes().get(0));
    }

    @Test
    void shapelessMaterialAndExactSlotsFindDistinctStacks() {
        List<ItemStack> pattern = empty(2);
        ItemStack namedStone = new ItemStack(Material.STONE);
        ItemMeta meta = namedStone.getItemMeta();
        meta.setDisplayName("Exact stone");
        namedStone.setItemMeta(meta);
        pattern.set(0, new ItemStack(Material.STONE, 2));
        pattern.set(1, namedStone);
        GridRecipeDefinition recipe = new GridRecipeDefinition(new NamespacedKey("recipeweaver", "mixed_modes"),
                GridTier.ELITE, RecipeKind.SHAPELESS, 2, pattern, new ItemStack(Material.EMERALD),
                "", true, false, List.of(IngredientMatchMode.MATERIAL, IngredientMatchMode.EXACT,
                IngredientMatchMode.EXACT, IngredientMatchMode.EXACT), 0, true);
        List<ItemStack> actual = empty(2);
        actual.set(0, namedStone.clone());
        actual.get(0).setAmount(3);
        actual.set(1, new ItemStack(Material.STONE, 2));
        assertArrayEquals(new int[] {1, 2, 0, 0}, GridMatcher.consumption(recipe, actual));
    }

    @Test
    void mirrorCanBeDisabledAndCarriesEachSlotMatchMode() {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE, 2));
        pattern.set(1, new ItemStack(Material.DIRT));
        List<IngredientMatchMode> modes = List.of(IngredientMatchMode.MATERIAL,
                IngredientMatchMode.EXACT, IngredientMatchMode.EXACT, IngredientMatchMode.EXACT);
        NamespacedKey key = new NamespacedKey("recipeweaver", "mirror_modes");
        GridRecipeDefinition noMirror = new GridRecipeDefinition(key, GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern, new ItemStack(Material.EMERALD), "", true,
                false, modes, 0, false);
        List<ItemStack> actual = empty(2);
        actual.set(2, new ItemStack(Material.DIRT));
        actual.set(3, new ItemStack(Material.STONE, 3));
        assertFalse(GridMatcher.matches(noMirror, actual));
        GridRecipeDefinition mirrored = new GridRecipeDefinition(key, GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern, new ItemStack(Material.EMERALD), "", true,
                false, modes, 0, true);
        assertArrayEquals(new int[] {0, 0, 1, 2}, GridMatcher.consumption(mirrored, actual));
    }

    @Test
    void higherPriorityWinsWithKeyOrderingForTies() {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE));
        GridRecipeDefinition low = grid("a_low", GridTier.ELITE, RecipeKind.SHAPED, 2, pattern);
        GridRecipeDefinition high = new GridRecipeDefinition(new NamespacedKey("recipeweaver", "z_high"),
                GridTier.ELITE, RecipeKind.SHAPED, 2, pattern, new ItemStack(Material.EMERALD),
                "", true, false, Arrays.asList(new IngredientMatchMode[] {
                    IngredientMatchMode.EXACT, IngredientMatchMode.EXACT,
                    IngredientMatchMode.EXACT, IngredientMatchMode.EXACT}), 10, true);
        GridRecipeDefinition tie = new GridRecipeDefinition(new NamespacedKey("recipeweaver", "b_tie"),
                GridTier.ELITE, RecipeKind.SHAPED, 2, pattern, new ItemStack(Material.EMERALD),
                "", true, false, high.matchModes(), 0, true);
        assertEquals(List.of(high, low, tie), GridMenus.orderRecipes(List.of(tie, low, high)));
    }

    @Test
    void savedGridListClickUsesDisplayedKeyAfterInsertionOrDeletion() {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE));
        GridRecipeDefinition first = grid("a_first", GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern);
        GridRecipeDefinition second = grid("b_second", GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern);
        GridRecipeDefinition inserted = grid("0_inserted", GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern);
        List<NamespacedKey> displayedKeys = List.of(first.key(), second.key());
        Map<NamespacedKey, GridRecipeDefinition> current = new HashMap<>();
        current.put(first.key(), first);
        current.put(second.key(), second);
        current.put(inserted.key(), inserted);
        assertEquals(List.of(inserted, first, second), GridMenus.orderRecipes(current.values()));
        assertEquals(first, GridMenus.displayedRecipe(displayedKeys, 0,
                key -> Optional.ofNullable(current.get(key))));

        current.remove(first.key());
        assertNull(GridMenus.displayedRecipe(displayedKeys, 0,
                key -> Optional.ofNullable(current.get(key))));
        assertEquals(second, GridMenus.displayedRecipe(displayedKeys, 1,
                key -> Optional.ofNullable(current.get(key))));
    }

    @Test
    void newGridFieldsSerializeAndLegacyConstructorKeepsDefaults() throws Exception {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE));
        GridRecipeDefinition recipe = new GridRecipeDefinition(new NamespacedKey("recipeweaver", "new_fields"),
                GridTier.ELITE, RecipeKind.SHAPED, 2, pattern, new ItemStack(Material.EMERALD),
                "", true, false, List.of(IngredientMatchMode.MATERIAL, IngredientMatchMode.EXACT,
                IngredientMatchMode.EXACT, IngredientMatchMode.EXACT), 12, false);
        GridRecipeRepository repository = new GridRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("grid-recipes"))) { file = files.findFirst().orElseThrow(); }
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("schema: 14"));
        assertTrue(yaml.contains("priority: 12"));
        assertTrue(yaml.contains("allow-mirror: false"));
        assertTrue(yaml.contains("match-modes:") && yaml.contains("MATERIAL"));
        GridRecipeDefinition old = grid("legacy", GridTier.ELITE, RecipeKind.SHAPED, 2, pattern);
        assertEquals(IngredientMatchMode.EXACT, old.matchModes().get(0));
        assertEquals(0, old.priority());
        assertTrue(old.allowMirror());
    }

    @Test
    void exactAlternativeRespectsMetadataAndPrimaryRequiredAmount() {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE, 3));
        ItemStack namedDirt = new ItemStack(Material.DIRT, 9);
        ItemMeta meta = namedDirt.getItemMeta();
        meta.setDisplayName("Special dirt");
        namedDirt.setItemMeta(meta);
        List<List<ItemStack>> alternatives = emptyAlternatives(2);
        alternatives.get(0).add(namedDirt);
        GridRecipeDefinition recipe = new GridRecipeDefinition(new NamespacedKey("recipeweaver", "exact_alt"),
                GridTier.ELITE, RecipeKind.SHAPED, 2, pattern, new ItemStack(Material.EMERALD),
                "", true, false, Collections.nCopies(4, IngredientMatchMode.EXACT), 0,
                true, alternatives, emptyTags(2));
        assertEquals(1, recipe.alternatives().get(0).get(0).getAmount());
        namedDirt.setType(Material.GOLD_INGOT);
        recipe.alternatives().get(0).get(0).setType(Material.GOLD_INGOT);
        assertEquals(Material.DIRT, recipe.alternatives().get(0).get(0).getType());
        List<ItemStack> actual = empty(2);
        ItemStack found = recipe.alternatives().get(0).get(0);
        found.setAmount(4);
        actual.set(3, found);
        assertArrayEquals(new int[] {0, 0, 0, 3}, GridMatcher.consumption(recipe, actual));
        actual.set(3, new ItemStack(Material.DIRT, 4));
        assertFalse(GridMatcher.matches(recipe, actual));
        actual.set(3, found);
        found.setAmount(2);
        assertFalse(GridMatcher.matches(recipe, actual));
    }

    @Test
    void shapelessAlternativesStillNeedDistinctSlots() {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE, 2));
        pattern.set(1, new ItemStack(Material.DIRT));
        List<List<ItemStack>> alternatives = emptyAlternatives(2);
        alternatives.get(0).add(new ItemStack(Material.DIRT));
        GridRecipeDefinition recipe = new GridRecipeDefinition(new NamespacedKey("recipeweaver", "shapeless_alt"),
                GridTier.ELITE, RecipeKind.SHAPELESS, 2, pattern, new ItemStack(Material.EMERALD),
                "", true, false, Collections.nCopies(4, IngredientMatchMode.MATERIAL), 0,
                true, alternatives, emptyTags(2));
        List<ItemStack> actual = empty(2);
        actual.set(0, new ItemStack(Material.DIRT, 3));
        assertFalse(GridMatcher.matches(recipe, actual));
        actual.set(3, new ItemStack(Material.DIRT));
        int[] counts = GridMatcher.consumption(recipe, actual);
        assertArrayEquals(new int[] {2, 0, 0, 1}, counts);
        assertEquals(1, GridMatcher.consume(actual, counts).get(0).getAmount());
    }

    @Test
    void itemTagMatchesViaResolverAndFollowsMirroredSlot() {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE, 2));
        pattern.set(1, new ItemStack(Material.DIRT));
        List<NamespacedKey> tags = emptyTags(2);
        NamespacedKey planks = NamespacedKey.fromString("minecraft:planks");
        tags.set(0, planks);
        GridRecipeDefinition recipe = new GridRecipeDefinition(new NamespacedKey("recipeweaver", "tag_mirror"),
                GridTier.ELITE, RecipeKind.SHAPED, 2, pattern, new ItemStack(Material.EMERALD),
                "", true, false, Collections.nCopies(4, IngredientMatchMode.EXACT), 0,
                true, emptyAlternatives(2), tags);
        List<ItemStack> actual = empty(2);
        actual.set(2, new ItemStack(Material.DIRT));
        actual.set(3, new ItemStack(Material.OAK_PLANKS, 3));
        assertArrayEquals(new int[] {0, 0, 1, 2},
                GridMatcher.consumption(recipe, actual, key -> key.equals(planks)
                        ? Set.of(Material.OAK_PLANKS) : Set.of()));
        assertEquals(null, GridMatcher.consumption(recipe, actual, key -> Set.of()));
        actual.get(3).setAmount(1);
        assertEquals(null, GridMatcher.consumption(recipe, actual,
                key -> Set.of(Material.OAK_PLANKS)));
    }

    @Test
    void emptySlotCannotCarryChoicesAndChoicesAreBounded() {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE));
        List<List<ItemStack>> alternatives = emptyAlternatives(2);
        alternatives.get(1).add(new ItemStack(Material.DIRT));
        List<NamespacedKey> tags = emptyTags(2);
        assertThrows(IllegalArgumentException.class, () -> new GridRecipeDefinition(
                new NamespacedKey("recipeweaver", "bad_empty_choice"), GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern, new ItemStack(Material.EMERALD), "",
                true, false, Collections.nCopies(4, IngredientMatchMode.EXACT), 0,
                true, alternatives, tags));
        alternatives.get(1).clear();
        tags.set(1, NamespacedKey.fromString("minecraft:planks"));
        assertThrows(IllegalArgumentException.class, () -> new GridRecipeDefinition(
                new NamespacedKey("recipeweaver", "bad_empty_tag"), GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern, new ItemStack(Material.EMERALD), "",
                true, false, Collections.nCopies(4, IngredientMatchMode.EXACT), 0,
                true, alternatives, tags));
        tags.set(1, null);
        for (int i = 0; i <= GridRecipeDefinition.MAX_ALTERNATIVES_PER_SLOT; i++) {
            alternatives.get(0).add(new ItemStack(Material.DIRT));
        }
        assertThrows(IllegalArgumentException.class, () -> new GridRecipeDefinition(
                new NamespacedKey("recipeweaver", "too_many_choices"), GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern, new ItemStack(Material.EMERALD), "",
                true, false, Collections.nCopies(4, IngredientMatchMode.EXACT), 0,
                true, alternatives, tags));
    }

    @Test
    void currentSchemaSerializesAlternativesAndItemTag() throws Exception {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE, 2));
        List<List<ItemStack>> alternatives = emptyAlternatives(2);
        alternatives.get(0).add(new ItemStack(Material.DIRT));
        List<NamespacedKey> tags = emptyTags(2);
        tags.set(0, NamespacedKey.fromString("minecraft:planks"));
        GridRecipeDefinition recipe = new GridRecipeDefinition(new NamespacedKey("recipeweaver", "schema_three"),
                GridTier.ELITE, RecipeKind.SHAPED, 2, pattern, new ItemStack(Material.EMERALD),
                "", true, false, Collections.nCopies(4, IngredientMatchMode.EXACT), 0,
                true, alternatives, tags);
        new GridRecipeRepository(plugin()).save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("grid-recipes"))) { file = files.findFirst().orElseThrow(); }
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("schema: 14"));
        assertTrue(yaml.contains("alternatives:") && yaml.contains("DIRT"));
        assertTrue(yaml.contains("item-tags:") && yaml.contains("minecraft:planks"));
    }

    @Test
    void weightedResultsUseExactTicketRangesAndAreImmutable() throws Exception {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE));
        ItemStack first = new ItemStack(Material.EMERALD, 2);
        List<WeightedResult> outcomes = new ArrayList<>(List.of(
                new WeightedResult(first, 2), new WeightedResult(new ItemStack(Material.DIAMOND), 3)));
        GridRecipeDefinition recipe = new GridRecipeDefinition(
                new NamespacedKey("recipeweaver", "weighted"), GridTier.ELITE, RecipeKind.SHAPED,
                2, pattern, outcomes, "", true, false,
                Collections.nCopies(4, IngredientMatchMode.EXACT), 0, true,
                emptyAlternatives(2), emptyTags(2));
        first.setType(Material.DIRT);
        outcomes.clear();
        recipe.resultChoices().get(0).item().setType(Material.DIRT);
        assertEquals(Material.EMERALD, recipe.result().getType());
        assertEquals(5, recipe.totalResultWeight());
        assertEquals(Material.EMERALD, recipe.resultForTicket(0).getType());
        assertEquals(Material.EMERALD, recipe.resultForTicket(1).getType());
        assertEquals(Material.DIAMOND, recipe.resultForTicket(2).getType());
        assertEquals(Material.DIAMOND, recipe.resultForTicket(4).getType());
        assertThrows(IllegalArgumentException.class, () -> recipe.resultForTicket(5));
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedResult(new ItemStack(Material.STONE), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedResult(new ItemStack(Material.STONE), WeightedResult.MAX_WEIGHT + 1));
        GridRecipeRepository repository = new GridRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("grid-recipes"))) {
            file = files.findFirst().orElseThrow();
        }
        String saved = Files.readString(file);
        assertTrue(saved.contains("schema: 14") && saved.contains("result-count: 2"));
        assertTrue(saved.contains("weight: 2") && saved.contains("weight: 3"));
        // MockBukkit cannot deserialize its own ItemMetaMock; use plain server-style item YAML.
        Files.writeString(file, """
                schema: 4
                id: recipeweaver:weighted
                tier: ELITE
                kind: SHAPED
                size: 2
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: STONE
                result-count: 2
                results:
                  '0':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: EMERALD
                      amount: 2
                    weight: 2
                  '1':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: DIAMOND
                    weight: 3
                """);
        repository.load();
        GridRecipeDefinition loaded = repository.get(recipe.key()).orElseThrow();
        assertEquals(5, loaded.totalResultWeight());
        assertEquals(Material.DIAMOND, loaded.resultForTicket(4).getType());
        assertTrue(loaded.consumes(0));
        assertEquals(null, loaded.remainder(0));
    }

    @Test
    void keptAndReturnedIngredientsFollowShapelessAndMirroredSlotMappings() {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE));
        pattern.set(1, new ItemStack(Material.DIRT));
        List<Boolean> consume = List.of(false, true, true, true);
        List<ItemStack> returns = new ArrayList<>(Collections.nCopies(4, null));
        returns.set(1, new ItemStack(Material.CLAY_BALL, 2));
        GridRecipeDefinition shapeless = new GridRecipeDefinition(
                new NamespacedKey("recipeweaver", "uses_shapeless"), GridTier.ELITE,
                RecipeKind.SHAPELESS, 2, pattern,
                List.of(new WeightedResult(new ItemStack(Material.EMERALD), 1)),
                "", true, false, Collections.nCopies(4, IngredientMatchMode.EXACT),
                0, true, emptyAlternatives(2), emptyTags(2), consume, returns);
        List<ItemStack> actual = empty(2);
        actual.set(0, new ItemStack(Material.DIRT, 2));
        actual.set(3, new ItemStack(Material.STONE));
        GridMatcher.MatchPlan plan = GridMatcher.plan(shapeless, actual);
        assertArrayEquals(new int[] {1, -1, -1, 0}, plan.sourceSlots());
        GridMatcher.CraftEffect effect = GridMatcher.apply(shapeless, actual, plan);
        assertEquals(1, effect.inputs().get(0).getAmount());
        assertEquals(Material.STONE, effect.inputs().get(3).getType());
        assertEquals(Material.CLAY_BALL, effect.returned().get(0).getType());
        assertEquals(2, effect.returned().get(0).getAmount());
        assertEquals(2, actual.get(0).getAmount());

        pattern.set(0, new ItemStack(Material.WATER_BUCKET));
        pattern.set(1, new ItemStack(Material.STONE));
        returns.set(1, null);
        returns.set(0, new ItemStack(Material.BUCKET));
        GridRecipeDefinition mirrored = new GridRecipeDefinition(
                new NamespacedKey("recipeweaver", "uses_mirrored"), GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern,
                List.of(new WeightedResult(new ItemStack(Material.EMERALD), 1)),
                "", true, false, Collections.nCopies(4, IngredientMatchMode.EXACT),
                0, true, emptyAlternatives(2), emptyTags(2),
                List.of(true, false, true, true), returns);
        List<ItemStack> rotated = empty(2);
        rotated.set(0, new ItemStack(Material.STONE));
        rotated.set(1, new ItemStack(Material.WATER_BUCKET));
        GridMatcher.MatchPlan mirroredPlan = GridMatcher.plan(mirrored, rotated);
        assertArrayEquals(new int[] {1, 0, -1, -1}, mirroredPlan.sourceSlots());
        GridMatcher.CraftEffect mirroredEffect = GridMatcher.apply(mirrored, rotated, mirroredPlan);
        assertEquals(Material.STONE, mirroredEffect.inputs().get(0).getType());
        assertEquals(Material.BUCKET, mirroredEffect.inputs().get(1).getType());
        assertTrue(mirroredEffect.returned().isEmpty());
    }

    @Test
    void gridUseFlagsValidateAndLoadSchemaFive() throws Exception {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE));
        pattern.set(1, new ItemStack(Material.DIRT));
        List<ItemStack> returns = new ArrayList<>(Collections.nCopies(4, null));
        returns.set(1, new ItemStack(Material.CLAY_BALL));
        List<WeightedResult> output = List.of(new WeightedResult(new ItemStack(Material.EMERALD), 1));
        GridRecipeDefinition recipe = new GridRecipeDefinition(
                new NamespacedKey("recipeweaver", "uses_persist"), GridTier.ELITE,
                RecipeKind.SHAPELESS, 2, pattern, output, "", true, false,
                Collections.nCopies(4, IngredientMatchMode.EXACT), 0, true,
                emptyAlternatives(2), emptyTags(2), List.of(false, true, true, true), returns);
        assertThrows(IllegalArgumentException.class, () -> new GridRecipeDefinition(
                recipe.key(), GridTier.ELITE, RecipeKind.SHAPELESS, 2, pattern, output,
                "", true, false, recipe.matchModes(), 0, true, emptyAlternatives(2),
                emptyTags(2), List.of(false, false, true, true),
                Collections.nCopies(4, null)));
        returns.set(0, new ItemStack(Material.BUCKET));
        assertThrows(IllegalArgumentException.class, () -> new GridRecipeDefinition(
                recipe.key(), GridTier.ELITE, RecipeKind.SHAPELESS, 2, pattern, output,
                "", true, false, recipe.matchModes(), 0, true, emptyAlternatives(2),
                emptyTags(2), List.of(false, true, true, true), returns));
        GridRecipeRepository repository = new GridRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("grid-recipes"))) { file = files.findFirst().orElseThrow(); }
        String saved = Files.readString(file);
        assertTrue(saved.contains("schema: 14") && saved.contains("consume:")
                && saved.contains("remainders:") && saved.contains("CLAY_BALL"));
        // MockBukkit cannot reload its serialized ItemMetaMock; decode a plain-item fixture.
        Files.writeString(file, """
                schema: 5
                id: recipeweaver:uses_persist
                tier: ELITE
                kind: SHAPELESS
                size: 2
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: STONE
                  '1':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: DIRT
                consume:
                  '0': false
                remainders:
                  '1':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: CLAY_BALL
                result-count: 1
                results:
                  '0':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: EMERALD
                    weight: 1
                """);
        repository.load();
        GridRecipeDefinition loaded = repository.get(recipe.key()).orElseThrow();
        assertFalse(loaded.consumes(0));
        assertTrue(loaded.consumes(1));
        assertEquals(Material.CLAY_BALL, loaded.remainder(1).getType());
        assertEquals(RecipeConditions.NONE, loaded.conditions());
        loaded.remainder(1).setType(Material.GOLD_INGOT);
        assertEquals(Material.CLAY_BALL, loaded.remainder(1).getType());
    }

    @Test
    void gridConditionsSerializeAndLoadOlderSchemaSix() throws Exception {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE));
        RecipeConditions conditions = new RecipeConditions("recipeweaver.craft.special", "world",
                NamespacedKey.fromString("minecraft:plains"), 23000, 1000,
                WeatherCondition.RAIN, 7, "crafts", -2,
                NamespacedKey.fromString("minecraft:story/root"));
        GridRecipeDefinition recipe = new GridRecipeDefinition(
                new NamespacedKey("recipeweaver", "conditioned"), GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern,
                List.of(new WeightedResult(new ItemStack(Material.EMERALD), 1)),
                "", true, false, Collections.nCopies(4, IngredientMatchMode.EXACT),
                0, true, emptyAlternatives(2), emptyTags(2),
                Collections.nCopies(4, true), Collections.nCopies(4, null), conditions);
        GridRecipeRepository repository = new GridRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("grid-recipes"))) { file = files.findFirst().orElseThrow(); }
        String saved = Files.readString(file);
        assertTrue(saved.contains("schema: 14") && saved.contains("conditions:"));
        assertTrue(saved.contains("permission: recipeweaver.craft.special")
                && saved.contains("time-start: 23000") && saved.contains("minimum-score: -2"));
        // MockBukkit cannot reload its own serialized ItemMetaMock; use a plain-item fixture.
        Files.writeString(file, """
                schema: 6
                id: recipeweaver:conditioned
                tier: ELITE
                kind: SHAPED
                size: 2
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: STONE
                result-count: 1
                results:
                  '0':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: EMERALD
                    weight: 1
                conditions:
                  permission: recipeweaver.craft.special
                  world: world
                  biome: minecraft:plains
                  time-start: 23000
                  time-end: 1000
                  weather: RAIN
                  minimum-level: 7
                  scoreboard-objective: crafts
                  minimum-score: -2
                  advancement: minecraft:story/root
                """);
        repository.load();
        assertEquals(conditions, repository.get(recipe.key()).orElseThrow().conditions());
        assertEquals(List.of(false, false, false, false),
                repository.get(recipe.key()).orElseThrow().optionalSlots());
        Files.writeString(file, Files.readString(file).replace("weather: RAIN", "weather: [RAIN]"));
        assertThrows(java.io.IOException.class, repository::load);
    }

    @Test
    void optionalShapedSlotMayBeAbsentOrConsumedWithoutFreeCrafting() {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.WATER_BUCKET));
        pattern.set(1, new ItemStack(Material.STONE));
        List<Boolean> optional = List.of(false, true, false, false);
        List<Boolean> consume = List.of(true, false, true, true);
        List<ItemStack> returns = new ArrayList<>(Collections.nCopies(4, null));
        returns.set(0, new ItemStack(Material.BUCKET));
        GridRecipeDefinition recipe = new GridRecipeDefinition(
                new NamespacedKey("recipeweaver", "optional_shaped"), GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern,
                List.of(new WeightedResult(new ItemStack(Material.EMERALD), 1)),
                "", true, false, Collections.nCopies(4, IngredientMatchMode.EXACT),
                0, true, emptyAlternatives(2), emptyTags(2), consume, returns,
                optional, RecipeConditions.NONE);
        List<ItemStack> input = empty(2);
        input.set(0, new ItemStack(Material.WATER_BUCKET));
        GridMatcher.MatchPlan absent = GridMatcher.plan(recipe, input);
        assertArrayEquals(new int[] {0, -1, -1, -1}, absent.sourceSlots());
        assertEquals(Material.BUCKET, GridMatcher.apply(recipe, input, absent).inputs().get(0).getType());
        input.set(1, new ItemStack(Material.STONE));
        GridMatcher.MatchPlan present = GridMatcher.plan(recipe, input);
        assertArrayEquals(new int[] {0, 1, -1, -1}, present.sourceSlots());
        assertEquals(Material.STONE, GridMatcher.apply(recipe, input, present).inputs().get(1).getType());
        input.set(1, new ItemStack(Material.DIRT));
        assertFalse(GridMatcher.matches(recipe, input));
        assertThrows(IllegalArgumentException.class, () -> new GridRecipeDefinition(
                recipe.key(), recipe.tier(), recipe.kind(), recipe.size(), pattern,
                recipe.resultChoices(), "", true, false, recipe.matchModes(), 0, true,
                emptyAlternatives(2), emptyTags(2), consume, returns,
                List.of(true, true, false, false), RecipeConditions.NONE));
        assertThrows(IllegalArgumentException.class, () -> new GridRecipeDefinition(
                recipe.key(), recipe.tier(), recipe.kind(), recipe.size(), pattern,
                recipe.resultChoices(), "", true, false, recipe.matchModes(), 0, true,
                emptyAlternatives(2), emptyTags(2), List.of(false, true, true, true),
                Collections.nCopies(4, null), optional, RecipeConditions.NONE));
        assertThrows(IllegalArgumentException.class, () -> new GridRecipeDefinition(
                recipe.key(), recipe.tier(), recipe.kind(), recipe.size(), pattern,
                recipe.resultChoices(), "", true, false, recipe.matchModes(), 0, true,
                emptyAlternatives(2), emptyTags(2), consume, returns,
                List.of(false, false, true, false), RecipeConditions.NONE));
    }

    @Test
    void optionalShapelessAssignmentReassignsAnOverlappingRequiredInput() {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE));
        pattern.set(1, new ItemStack(Material.STONE, 2));
        List<List<ItemStack>> alternatives = emptyAlternatives(2);
        alternatives.get(0).add(new ItemStack(Material.DIRT));
        GridRecipeDefinition recipe = new GridRecipeDefinition(
                new NamespacedKey("recipeweaver", "optional_shapeless"), GridTier.ELITE,
                RecipeKind.SHAPELESS, 2, pattern,
                List.of(new WeightedResult(new ItemStack(Material.EMERALD), 1)),
                "", true, false, Collections.nCopies(4, IngredientMatchMode.MATERIAL),
                0, true, alternatives, emptyTags(2), Collections.nCopies(4, true),
                Collections.nCopies(4, null), List.of(false, true, false, false),
                RecipeConditions.NONE);
        List<ItemStack> input = empty(2);
        input.set(0, new ItemStack(Material.STONE, 2));
        input.set(1, new ItemStack(Material.DIRT));
        GridMatcher.MatchPlan plan = GridMatcher.plan(recipe, input);
        assertArrayEquals(new int[] {1, 0, -1, -1}, plan.sourceSlots());
        assertArrayEquals(new int[] {2, 1, 0, 0}, plan.amounts());
        input.set(0, null);
        assertArrayEquals(new int[] {-1, 0, -1, -1},
                GridMatcher.plan(recipe, input).sourceSlots());
        input.set(0, new ItemStack(Material.COBBLESTONE, 2));
        assertFalse(GridMatcher.matches(recipe, input));
    }

    @Test
    void schemaNineWritesOptionalFlagsAndLoadsSeven() throws Exception {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE));
        pattern.set(1, new ItemStack(Material.DIRT));
        GridRecipeDefinition recipe = new GridRecipeDefinition(
                new NamespacedKey("recipeweaver", "optional_stored"), GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern,
                List.of(new WeightedResult(new ItemStack(Material.EMERALD), 1)),
                "", true, false, Collections.nCopies(4, IngredientMatchMode.EXACT),
                0, true, emptyAlternatives(2), emptyTags(2), Collections.nCopies(4, true),
                Collections.nCopies(4, null), List.of(false, true, false, false),
                RecipeConditions.NONE);
        GridRecipeRepository repository = new GridRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("grid-recipes"))) { file = files.findFirst().orElseThrow(); }
        String saved = Files.readString(file);
        assertTrue(saved.contains("schema: 14") && saved.contains("optional:")
                && saved.contains("'1': true"));
        Files.writeString(file, """
                schema: 7
                id: recipeweaver:optional_stored
                tier: ELITE
                kind: SHAPED
                size: 2
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: STONE
                  '1':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: DIRT
                optional:
                  '1': true
                result-count: 1
                results:
                  '0':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: EMERALD
                    weight: 1
                """);
        repository.load();
        assertEquals(List.of(false, true, false, false),
                repository.get(recipe.key()).orElseThrow().optionalSlots());
        Files.writeString(file, Files.readString(file).replace("'1': true", "'1': [true]"));
        assertThrows(java.io.IOException.class, repository::load);
    }

    @Test
    void craftSoundSchemaNineRoundTripsAndOlderSchemaDefaultsToSilent() throws Exception {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE));
        CraftSoundAction action = new CraftSoundAction(
                NamespacedKey.minecraft("block.note_block.bell"), 1.5F, 0.75F, true);
        GridRecipeDefinition recipe = new GridRecipeDefinition(
                new NamespacedKey("recipeweaver", "sound_grid"), GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern,
                List.of(new WeightedResult(new ItemStack(Material.EMERALD), 1)),
                "", true, false, Collections.nCopies(4, IngredientMatchMode.EXACT),
                0, true, emptyAlternatives(2), emptyTags(2),
                Collections.nCopies(4, true), Collections.nCopies(4, null),
                Collections.nCopies(4, false), RecipeConditions.NONE, action);
        GridRecipeRepository repository = new GridRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("grid-recipes"))) {
            file = files.findFirst().orElseThrow();
        }
        String saved = Files.readString(file);
        assertTrue(saved.contains("schema: 14") && saved.contains("block.note_block.bell")
                && saved.contains("per-batch: true"));
        String base = """
                schema: 8
                id: recipeweaver:sound_grid
                tier: ELITE
                kind: SHAPED
                size: 2
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: STONE
                result-count: 1
                results:
                  '0':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: EMERALD
                    weight: 1
                """;
        String withSound = base + """
                actions:
                  sound:
                    key: minecraft:block.note_block.bell
                    volume: 1.5
                    pitch: 0.75
                    per-batch: true
                """;
        Files.writeString(file, withSound);
        repository.load();
        assertEquals(action, repository.get(recipe.key()).orElseThrow().craftSound());
        Files.writeString(file, withSound.replace("volume: 1.5", "volume: [1.5]"));
        assertThrows(java.io.IOException.class, repository::load);
        Files.writeString(file, withSound.replace("per-batch: true", "per-batch: later"));
        assertThrows(java.io.IOException.class, repository::load);
        Files.writeString(file, base.replace("schema: 8", "schema: 7"));
        repository.load();
        assertNull(repository.get(recipe.key()).orElseThrow().craftSound());
    }

    @Test
    void craftAdvancementSchemaNineRoundTripsAndOlderSchemaDefaultsToNone() throws Exception {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE));
        CraftAdvancementAction action = new CraftAdvancementAction(
                NamespacedKey.minecraft("story/root"));
        GridRecipeDefinition recipe = new GridRecipeDefinition(
                new NamespacedKey("recipeweaver", "advancement_grid"), GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern,
                List.of(new WeightedResult(new ItemStack(Material.EMERALD), 1)),
                "", true, false, Collections.nCopies(4, IngredientMatchMode.EXACT),
                0, true, emptyAlternatives(2), emptyTags(2),
                Collections.nCopies(4, true), Collections.nCopies(4, null),
                Collections.nCopies(4, false), RecipeConditions.NONE, null, action);
        GridRecipeRepository repository = new GridRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("grid-recipes"))) {
            file = files.findFirst().orElseThrow();
        }
        String saved = Files.readString(file);
        assertTrue(saved.contains("schema: 14") && saved.contains("story/root"));
        String base = """
                schema: 9
                id: recipeweaver:advancement_grid
                tier: ELITE
                kind: SHAPED
                size: 2
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: STONE
                result-count: 1
                results:
                  '0':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: EMERALD
                    weight: 1
                """;
        Files.writeString(file, base + "actions:\n  advancement: minecraft:story/root\n");
        repository.load();
        assertEquals(action, repository.get(recipe.key()).orElseThrow().craftAdvancement());
        Files.writeString(file, base + "actions:\n  advancement: [invalid]\n");
        assertThrows(java.io.IOException.class, repository::load);
        Files.writeString(file, base.replace("schema: 9", "schema: 8"));
        repository.load();
        assertNull(repository.get(recipe.key()).orElseThrow().craftAdvancement());
    }

    @Test
    void schemaElevenStoresCommandActionAndLoadsTenWithoutOne() throws Exception {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE));
        CraftCommandAction action = new CraftCommandAction(
                "say {player}", CraftCommandSender.CONSOLE, true);
        GridRecipeDefinition recipe = new GridRecipeDefinition(
                new NamespacedKey("recipeweaver", "command_grid"), GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern,
                List.of(new WeightedResult(new ItemStack(Material.EMERALD), 1)),
                "", true, false, Collections.nCopies(4, IngredientMatchMode.EXACT),
                0, true, emptyAlternatives(2), emptyTags(2),
                Collections.nCopies(4, true), Collections.nCopies(4, null),
                Collections.nCopies(4, false), RecipeConditions.NONE, null, null, action);
        GridRecipeRepository repository = new GridRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("grid-recipes"))) {
            file = files.findFirst().orElseThrow();
        }
        String saved = Files.readString(file);
        assertTrue(saved.contains("schema: 14") && saved.contains("per-batch: true")
                && saved.contains("CONSOLE") && saved.contains("say {player}"));

        String fixture = """
                schema: 11
                id: recipeweaver:command_grid
                tier: ELITE
                kind: SHAPED
                size: 2
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: STONE
                result-count: 1
                results:
                  '0':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: EMERALD
                    weight: 1
                actions:
                  command:
                    text: say {player}
                    sender: CONSOLE
                    per-batch: true
                """;
        Files.writeString(file, fixture);
        repository.load();
        assertEquals(action, repository.get(recipe.key()).orElseThrow().craftCommand());
        Files.writeString(file, fixture.replace("per-batch: true", "per-batch: later"));
        assertThrows(java.io.IOException.class, repository::load);
        Files.writeString(file, fixture.substring(0, fixture.indexOf("actions:"))
                .replace("schema: 11", "schema: 10"));
        repository.load();
        assertNull(repository.get(recipe.key()).orElseThrow().craftCommand());
    }

    @Test
    void craftAdvancementAwardsRemainingCriteriaOnceAfterCommittedBatches() {
        CraftAdvancementAction action = new CraftAdvancementAction(
                NamespacedKey.minecraft("story/root"));
        CraftAdvancementAction missing = new CraftAdvancementAction(
                NamespacedKey.minecraft("does_not_exist"));
        Advancement advancement = (Advancement) Proxy.newProxyInstance(
                Advancement.class.getClassLoader(), new Class<?>[] {Advancement.class},
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        Set<String> remaining = new HashSet<>(Set.of("first", "second"));
        AtomicInteger awards = new AtomicInteger();
        AdvancementProgress progress = (AdvancementProgress) Proxy.newProxyInstance(
                AdvancementProgress.class.getClassLoader(), new Class<?>[] {AdvancementProgress.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRemainingCriteria" -> Set.copyOf(remaining);
                    case "awardCriteria" -> {
                        boolean awarded = remaining.remove(args[0]);
                        if (awarded) awards.incrementAndGet();
                        yield awarded;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAdvancementProgress" -> progress;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        GridCraftAdvancements.award(player, List.of(action, action, missing),
                key -> key.equals(action.advancement()) ? advancement : null);
        GridCraftAdvancements.award(player, List.of(action), key -> advancement);
        assertEquals(2, awards.get());
        assertTrue(remaining.isEmpty());
    }

    @Test
    void craftSoundCadencePlaysOnceOrForEachCommittedBatch() {
        CraftSoundAction once = new CraftSoundAction(
                NamespacedKey.minecraft("block.note_block.bell"), 1.0F, 1.0F, false);
        CraftSoundAction each = new CraftSoundAction(
                NamespacedKey.minecraft("block.note_block.bell"), 1.0F, 1.0F, true);
        assertThrows(IllegalArgumentException.class, () -> new CraftSoundAction(
                once.sound(), 0.0F, 1.0F, false));
        assertThrows(IllegalArgumentException.class, () -> new CraftSoundAction(
                once.sound(), 1.0F, Float.NaN, false));
        AtomicInteger plays = new AtomicInteger();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getLocation" -> new Location(null, 0, 0, 0);
                    case "playSound" -> { plays.incrementAndGet(); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        GridCraftSounds.play(player, List.of(once, once, each, each));
        assertEquals(3, plays.get());
    }

    @Test
    void outputAndOverflowRemainderNeedSpaceBeforeStorageChanges() {
        ItemStack[] storage = new ItemStack[36];
        Arrays.fill(storage, new ItemStack(Material.COBBLESTONE, 64));
        storage[0] = new ItemStack(Material.EMERALD, 63);
        ItemStack output = new ItemStack(Material.EMERALD);
        List<ItemStack> returns = List.of(new ItemStack(Material.GOLD_NUGGET));
        assertEquals(null, GridMenus.insertOutputAndReturns(storage, output, returns));
        assertEquals(63, storage[0].getAmount());
        storage[35] = null;
        ItemStack[] next = GridMenus.insertOutputAndReturns(storage, output, returns);
        assertEquals(64, next[0].getAmount());
        assertEquals(Material.GOLD_NUGGET, next[35].getType());
        assertEquals(63, storage[0].getAmount());
        assertEquals(null, storage[35]);
    }

    @Test
    void schemaThreeGridLoadsWithSingleDefaultWeight() throws Exception {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE));
        GridRecipeDefinition recipe = grid("legacy_three_result", GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern);
        GridRecipeRepository repository = new GridRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("grid-recipes"))) { file = files.findFirst().orElseThrow(); }
        Files.writeString(file, """
                schema: 3
                id: recipeweaver:legacy_three_result
                tier: ELITE
                kind: SHAPED
                size: 2
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: STONE
                result:
                  ==: org.bukkit.inventory.ItemStack
                  v: 1
                  type: EMERALD
                """);
        repository.load();
        GridRecipeDefinition loaded = repository.get(recipe.key()).orElseThrow();
        assertEquals(1, loaded.resultChoices().size());
        assertEquals(1, loaded.totalResultWeight());
        assertEquals(Material.EMERALD, loaded.result().getType());
    }

    @Test
    void ingredientAmountsSerializeAndRejectOversizedStacks() throws Exception {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE, 12));
        GridRecipeDefinition recipe = grid("persisted_amount", GridTier.ELITE, RecipeKind.SHAPED, 2, pattern);
        GridRecipeRepository repository = new GridRecipeRepository(plugin());
        repository.save(recipe);
        Path saved;
        try (var files = Files.list(directory.resolve("grid-recipes"))) { saved = files.findFirst().orElseThrow(); }
        assertTrue(Files.readString(saved).contains("amount: 12"));
        pattern.set(0, new ItemStack(Material.STONE, 65));
        assertThrows(IllegalArgumentException.class, () ->
                grid("too_many", GridTier.ELITE, RecipeKind.SHAPED, 2, pattern));
    }

    @Test
    void definitionChecksTierAndIsImmutable() {
        List<ItemStack> pattern = empty(3);
        ItemStack stone = new ItemStack(Material.STONE);
        pattern.set(0, stone);
        GridRecipeDefinition recipe = grid("advanced", GridTier.ADVANCED, RecipeKind.SHAPED, 3, pattern);
        stone.setType(Material.DIRT);
        recipe.ingredients().get(0).setType(Material.DIRT);
        assertEquals(Material.STONE, recipe.ingredients().get(0).getType());
        assertThrows(IllegalArgumentException.class, () ->
                grid("bad", GridTier.ADVANCED, RecipeKind.SHAPED, 4, empty(4)));
    }

    @Test
    void failedGridReloadPreservesTheLoadedRecipe() throws Exception {
        GridRecipeRepository repository = new GridRecipeRepository(plugin());
        List<ItemStack> pattern = empty(4);
        pattern.set(3, new ItemStack(Material.DIAMOND));
        GridRecipeDefinition recipe = grid("stored", GridTier.ELITE, RecipeKind.SHAPED, 4, pattern);
        repository.save(recipe);
        assertEquals(Material.DIAMOND, repository.get(recipe.key()).orElseThrow().ingredients().get(3).getType());
        Path file;
        try (var files = Files.list(directory.resolve("grid-recipes"))) { file = files.findFirst().orElseThrow(); }
        Files.writeString(file, "schema: invalid");
        assertThrows(java.io.IOException.class, repository::load);
        assertTrue(repository.get(recipe.key()).isPresent());
    }

    @Test
    void placedTableRoundTripsAndRemoves() throws Exception {
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> method.getName().equals("getUID")
                        ? UUID.fromString("00000000-0000-0000-0000-000000000001") : null);
        Location location = new Location(world, 8, 65, -3);
        TableRepository tables = new TableRepository(directory);
        tables.put(location, GridTier.ELITE);
        TableRepository fresh = new TableRepository(directory);
        fresh.load();
        assertEquals(GridTier.ELITE, fresh.get(location));
        assertTrue(fresh.remove(location));
        assertFalse(fresh.remove(location));
        TableRepository after = new TableRepository(directory);
        after.load();
        assertEquals(null, after.get(location));
    }

    private static List<ItemStack> empty(int size) {
        return new ArrayList<>(Arrays.asList(new ItemStack[size * size]));
    }

    @Test
    void shiftCraftLimitPersistsAndOlderGridDefaultsToSixtyFour() throws Exception {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE));
        GridRecipeDefinition limited = new GridRecipeDefinition(
                new NamespacedKey("recipeweaver", "limited_shift"), GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern,
                List.of(new WeightedResult(new ItemStack(Material.EMERALD), 1)),
                "", true, false, Collections.nCopies(4, IngredientMatchMode.EXACT),
                0, true, emptyAlternatives(2), emptyTags(2),
                Collections.nCopies(4, true), Collections.nCopies(4, null),
                Collections.nCopies(4, false), RecipeConditions.NONE,
                null, null, null, 2, 30);
        GridRecipeRepository repository = new GridRecipeRepository(plugin());
        repository.save(limited);
        Path file;
        try (var files = Files.list(directory.resolve("grid-recipes"))) {
            file = files.findFirst().orElseThrow();
        }
        String saved = Files.readString(file);
        assertTrue(saved.contains("schema: 14") && saved.contains("max-shift-batches: 2")
                && saved.contains("cooldown-seconds: 30"));
        String loadable = """
                schema: 13
                id: recipeweaver:limited_shift
                tier: ELITE
                kind: SHAPED
                size: 2
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: STONE
                result-count: 1
                results:
                  '0':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: EMERALD
                    weight: 1
                max-shift-batches: 2
                cooldown-seconds: 30
                """;
        Files.writeString(file, loadable);
        repository.load();
        assertEquals(2, repository.get(limited.key()).orElseThrow().maxShiftBatches());
        assertEquals(30, repository.get(limited.key()).orElseThrow().cooldownSeconds());
        Files.writeString(file, loadable.replace("schema: 13", "schema: 12")
                .replace("cooldown-seconds: 30", ""));
        repository.load();
        assertEquals(2, repository.get(limited.key()).orElseThrow().maxShiftBatches());
        assertEquals(0, repository.get(limited.key()).orElseThrow().cooldownSeconds());
        Files.writeString(file, loadable.replace("schema: 13", "schema: 11")
                .replace("max-shift-batches: 2", "").replace("cooldown-seconds: 30", ""));
        repository.load();
        assertEquals(64, repository.get(limited.key()).orElseThrow().maxShiftBatches());
        assertEquals(0, repository.get(limited.key()).orElseThrow().cooldownSeconds());
        Files.writeString(file, loadable.replace("max-shift-batches: 2", "max-shift-batches: 0"));
        assertThrows(java.io.IOException.class, repository::load);
        assertEquals(64, repository.get(limited.key()).orElseThrow().maxShiftBatches());
        Files.writeString(file, loadable.replace("cooldown-seconds: 30", "cooldown-seconds: 86401"));
        assertThrows(java.io.IOException.class, repository::load);
        Files.writeString(file, loadable.replace("cooldown-seconds: 30", "cooldown-seconds: later"));
        assertThrows(java.io.IOException.class, repository::load);
        assertThrows(IllegalArgumentException.class, () -> new GridRecipeDefinition(
                limited.key(), GridTier.ELITE, RecipeKind.SHAPED, 2, pattern,
                limited.resultChoices(), "", true, false,
                Collections.nCopies(4, IngredientMatchMode.EXACT), 0, true,
                emptyAlternatives(2), emptyTags(2), Collections.nCopies(4, true),
                Collections.nCopies(4, null), Collections.nCopies(4, false),
                RecipeConditions.NONE, null, null, null, 65));
        assertThrows(IllegalArgumentException.class, () -> new GridRecipeDefinition(
                limited.key(), GridTier.ELITE, RecipeKind.SHAPED, 2, pattern,
                limited.resultChoices(), "", true, false,
                Collections.nCopies(4, IngredientMatchMode.EXACT), 0, true,
                emptyAlternatives(2), emptyTags(2), Collections.nCopies(4, true),
                Collections.nCopies(4, null), Collections.nCopies(4, false),
                RecipeConditions.NONE, null, null, null, 2, -1));
    }

    @Test
    void gridCooldownIsPerRecipeExpiresAndCanBeRolledBack() {
        var data = new ItemStack(Material.STONE).getItemMeta().getPersistentDataContainer();
        NamespacedKey first = new NamespacedKey("recipeweaver", "cooldown_first");
        NamespacedKey second = new NamespacedKey("recipeweaver", "cooldown_second");
        NamespacedKey sameShortKey = new NamespacedKey("other_plugin", "cooldown_first");
        assertTrue(GridCooldowns.ready(data, first, 1_000L));
        Runnable undo = GridCooldowns.commit(data, java.util.Map.of(first, 5), 1_000L);
        assertFalse(GridCooldowns.ready(data, first, 5_999L));
        assertTrue(GridCooldowns.ready(data, first, 6_000L));
        assertTrue(GridCooldowns.ready(data, second, 1_000L));
        assertTrue(GridCooldowns.ready(data, sameShortKey, 1_000L));
        undo.run();
        assertTrue(GridCooldowns.ready(data, first, 1_000L));
    }

    @Test
    void perPlayerGridCraftLimitPersistsAndRollsBackWithATransaction() throws Exception {
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.STONE));
        NamespacedKey key = new NamespacedKey("recipeweaver", "lifetime_limited");
        GridRecipeDefinition recipe = new GridRecipeDefinition(key, GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern,
                List.of(new WeightedResult(new ItemStack(Material.EMERALD), 1)),
                "", true, false, Collections.nCopies(4, IngredientMatchMode.EXACT),
                0, true, emptyAlternatives(2), emptyTags(2),
                Collections.nCopies(4, true), Collections.nCopies(4, null),
                Collections.nCopies(4, false), RecipeConditions.NONE,
                null, null, null, 64, 0, -1, ResultMetadataMode.NONE, 3);
        GridRecipeRepository repository = new GridRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("grid-recipes"))) {
            file = files.findFirst().orElseThrow();
        }
        String saved = Files.readString(file);
        assertTrue(saved.contains("schema: 14")
                && saved.contains("max-crafts-per-player: 3"));
        String fixture = """
                schema: 14
                id: recipeweaver:lifetime_limited
                tier: ELITE
                kind: SHAPED
                size: 2
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: STONE
                result-count: 1
                results:
                  '0':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: EMERALD
                    weight: 1
                max-crafts-per-player: 3
                """;
        Files.writeString(file, fixture);
        repository.load();
        assertEquals(3, repository.get(key).orElseThrow().maxCraftsPerPlayer());
        Files.writeString(file, fixture.replace("schema: 14", "schema: 13")
                .replace("max-crafts-per-player: 3", ""));
        repository.load();
        assertEquals(0, repository.get(key).orElseThrow().maxCraftsPerPlayer());
        Files.writeString(file, fixture.replace("max-crafts-per-player: 3", "max-crafts-per-player: -1"));
        assertThrows(java.io.IOException.class, repository::load);
        Files.writeString(file, fixture.replace("max-crafts-per-player: 3", "max-crafts-per-player: many"));
        assertThrows(java.io.IOException.class, repository::load);
        Files.writeString(file, fixture.replace("max-crafts-per-player: 3", "max-crafts-per-player: 1000001"));
        assertThrows(java.io.IOException.class, repository::load);

        var data = new ItemStack(Material.STONE).getItemMeta().getPersistentDataContainer();
        NamespacedKey other = new NamespacedKey("recipeweaver", "other_limited");
        assertTrue(GridCraftLimits.allows(data, key, 3, 0));
        Runnable undo = GridCraftLimits.commit(data, java.util.Map.of(key, 2));
        assertTrue(GridCraftLimits.allows(data, key, 3, 0));
        assertFalse(GridCraftLimits.allows(data, key, 3, 1));
        assertTrue(GridCraftLimits.allows(data, other, 3, 0));
        undo.run();
        assertTrue(GridCraftLimits.allows(data, key, 3, 2));
        GridCraftLimits.commit(data, java.util.Map.of(key, 3));
        assertFalse(GridCraftLimits.allows(data, key, 3, 0));
    }

    @Test
    void gridResultCopiesMatchedIngredientNameAndLoreWithoutChangingTemplate() throws Exception {
        ItemStack named = new ItemStack(Material.STONE);
        ItemMeta namedMeta = named.getItemMeta();
        namedMeta.setDisplayName("Named source");
        namedMeta.setLore(List.of("Source lore"));
        named.setItemMeta(namedMeta);
        List<ItemStack> pattern = empty(2);
        pattern.set(0, named);
        ItemStack template = new ItemStack(Material.EMERALD, 2);
        GridRecipeDefinition recipe = new GridRecipeDefinition(
                new NamespacedKey("recipeweaver", "copy_result_metadata"), GridTier.ELITE,
                RecipeKind.SHAPED, 2, pattern, List.of(new WeightedResult(template, 1)),
                "", true, false, Collections.nCopies(4, IngredientMatchMode.EXACT),
                0, true, emptyAlternatives(2), emptyTags(2),
                Collections.nCopies(4, true), Collections.nCopies(4, null),
                Collections.nCopies(4, false), RecipeConditions.NONE, null, null, null,
                64, 0, 0, ResultMetadataMode.NAME_AND_LORE);
        List<ItemStack> translated = empty(2);
        translated.set(3, named.clone());
        GridMatcher.MatchPlan plan = GridMatcher.plan(recipe, translated);
        ItemStack copied = GridResultMetadata.apply(recipe, plan, translated, template);
        assertEquals(Material.EMERALD, copied.getType());
        assertEquals(2, copied.getAmount());
        assertEquals("Named source", copied.getItemMeta().getDisplayName());
        assertEquals(List.of("Source lore"), copied.getItemMeta().getLore());
        assertFalse(template.getItemMeta().hasDisplayName());

        GridRecipeRepository repository = new GridRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("grid-recipes"))) {
            file = files.findFirst().orElseThrow();
        }
        String saved = Files.readString(file);
        assertTrue(saved.contains("result-metadata-mode: NAME_AND_LORE")
                && saved.contains("result-copy-source-slot: 0"));
        String fixture = """
                schema: 13
                id: recipeweaver:copy_result_metadata
                tier: ELITE
                kind: SHAPED
                size: 2
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: STONE
                result-count: 1
                results:
                  '0':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: EMERALD
                    weight: 1
                result-metadata-mode: NAME_AND_LORE
                result-copy-source-slot: 0
                """;
        Files.writeString(file, fixture);
        repository.load();
        assertEquals(ResultMetadataMode.NAME_AND_LORE,
                repository.get(recipe.key()).orElseThrow().resultMetadataMode());
        Files.writeString(file, fixture.replace("schema: 13", "schema: 12")
                .replace("result-metadata-mode: NAME_AND_LORE", "")
                .replace("result-copy-source-slot: 0", ""));
        repository.load();
        assertEquals(ResultMetadataMode.NONE,
                repository.get(recipe.key()).orElseThrow().resultMetadataMode());
        Files.writeString(file, fixture.replace("result-copy-source-slot: 0",
                "result-copy-source-slot: 3"));
        assertThrows(java.io.IOException.class, repository::load);
    }

    @Test
    void customDataOnlyIngredientModePersistsAcrossReload() throws Exception {
        // MockBukkit cannot deserialize an ItemStack with ItemMeta, so this
        // fixture checks mode storage. ItemDataRuleTest checks typed PDC matching.
        ItemStack sample = new ItemStack(Material.PAPER);
        List<ItemStack> pattern = empty(2);
        pattern.set(0, sample);
        List<IngredientMatchMode> modes = new ArrayList<>(
                Collections.nCopies(4, IngredientMatchMode.EXACT));
        modes.set(0, IngredientMatchMode.CUSTOM_DATA_ONLY);
        GridRecipeDefinition recipe = new GridRecipeDefinition(
                new NamespacedKey("recipeweaver", "custom_data_only_roundtrip"),
                GridTier.ELITE, RecipeKind.SHAPED, 2, pattern,
                new ItemStack(Material.DIAMOND), "", true, false, modes, 0, true);
        GridRecipeRepository repository = new GridRecipeRepository(plugin());
        repository.save(recipe);
        Path file;
        try (var files = Files.list(directory.resolve("grid-recipes"))) {
            file = files.findFirst().orElseThrow();
        }
        assertTrue(Files.readString(file).contains("CUSTOM_DATA_ONLY"));
        Files.writeString(file, """
                schema: 13
                id: recipeweaver:custom_data_only_roundtrip
                tier: ELITE
                kind: SHAPED
                size: 2
                ingredients:
                  '0':
                    ==: org.bukkit.inventory.ItemStack
                    v: 1
                    type: PAPER
                match-modes:
                  '0': CUSTOM_DATA_ONLY
                result-count: 1
                results:
                  '0':
                    item:
                      ==: org.bukkit.inventory.ItemStack
                      v: 1
                      type: DIAMOND
                    weight: 1
                """);
        repository.load();
        GridRecipeDefinition loaded = repository.get(recipe.key()).orElseThrow();
        assertEquals(IngredientMatchMode.CUSTOM_DATA_ONLY, loaded.matchModes().get(0));
        assertTrue(loaded.matchesItemSample(0, new ItemStack(Material.PAPER)));
        assertFalse(loaded.matchesItemSample(0, new ItemStack(Material.BOOK)));
    }

    @Test
    void fieldSelectedMatchAndComponentResultModesPersistAcrossGridReload() throws Exception {
        GridRecipeRepository repository = new GridRecipeRepository(plugin());
        List<ItemStack> pattern = empty(2);
        pattern.set(0, new ItemStack(Material.PAPER));
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
        for (IngredientMatchMode mode : selectors) {
            List<IngredientMatchMode> modes = new ArrayList<>(
                    Collections.nCopies(4, IngredientMatchMode.MATERIAL));
            modes.set(0, mode);
            NamespacedKey key = new NamespacedKey("recipeweaver",
                    "selected_" + mode.name().toLowerCase());
            repository.save(new GridRecipeDefinition(key, GridTier.ELITE,
                    RecipeKind.SHAPED, 2, pattern, new ItemStack(Material.DIAMOND),
                    "", true, false, modes, 0, true));
            Path file = encodedRecipePath(directory.resolve("grid-recipes"), key);
            assertTrue(Files.readString(file).contains(mode.name()));
            // MockBukkit cannot deserialize its own weighted ItemStack output.
            Files.writeString(file, """
                    schema: 14
                    id: %s
                    tier: ELITE
                    kind: SHAPED
                    size: 2
                    ingredients:
                      '0':
                        ==: org.bukkit.inventory.ItemStack
                        v: 1
                        type: PAPER
                    match-modes:
                      '0': %s
                    result-count: 1
                    results:
                      '0':
                        item:
                          ==: org.bukkit.inventory.ItemStack
                          v: 1
                          type: DIAMOND
                        weight: 1
                    """.formatted(key, mode));
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
            NamespacedKey key = new NamespacedKey("recipeweaver",
                    "copy_" + mode.name().toLowerCase());
            repository.save(new GridRecipeDefinition(key, GridTier.ELITE,
                    RecipeKind.SHAPED, 2, pattern,
                    List.of(new WeightedResult(new ItemStack(Material.DIAMOND), 1)),
                    "", true, false, Collections.nCopies(4, IngredientMatchMode.MATERIAL),
                    0, true, emptyAlternatives(2), emptyTags(2),
                    Collections.nCopies(4, true), Collections.nCopies(4, null),
                    Collections.nCopies(4, false), RecipeConditions.NONE, null, null, null,
                    64, 0, 0, mode));
            Path file = encodedRecipePath(directory.resolve("grid-recipes"), key);
            assertTrue(Files.readString(file).contains("result-metadata-mode: " + mode));
            // MockBukkit cannot deserialize its own weighted ItemStack output.
            Files.writeString(file, """
                    schema: 14
                    id: %s
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
                          type: DIAMOND
                        weight: 1
                    result-metadata-mode: %s
                    result-copy-source-slot: 0
                    """.formatted(key, mode));
        }
        repository.load();
        for (IngredientMatchMode mode : selectors) {
            var loaded = repository.get(new NamespacedKey("recipeweaver",
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
            assertFalse(loaded.matchesItemSample(0, new ItemStack(Material.BOOK)), mode.name());
        }
        for (ResultMetadataMode mode : copyModes) {
            var loaded = repository.get(new NamespacedKey("recipeweaver",
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

    private static List<List<ItemStack>> emptyAlternatives(int size) {
        List<List<ItemStack>> choices = new ArrayList<>(size * size);
        for (int i = 0; i < size * size; i++) choices.add(new ArrayList<>());
        return choices;
    }

    private static List<NamespacedKey> emptyTags(int size) {
        return new ArrayList<>(Collections.nCopies(size * size, null));
    }

    private static GridRecipeDefinition grid(String name, GridTier tier, RecipeKind kind,
                                             int size, List<ItemStack> pattern) {
        return new GridRecipeDefinition(new NamespacedKey("recipeweaver", name), tier, kind, size,
                pattern, new ItemStack(Material.EMERALD), "", true, false);
    }

    private Plugin plugin() {
        File root = directory.toFile();
        Logger logger = Logger.getLogger("RecipeWeaverGridTest");
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getDataFolder" -> root;
                    case "getName" -> "RecipeWeaver";
                    case "getLogger" -> logger;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
