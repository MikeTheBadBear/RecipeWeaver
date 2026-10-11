package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.ResultMetadataMode;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import dev.recipeweaver.api.WeightedResult;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.recipe.CookingBookCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class BrewingRulesTest {
    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void sharedIngredientAmountMayExceedOneButBottleAmountMayNot() {
        assertNotNull(recipe("amount", 3, true, null, null,
                -1, ResultMetadataMode.NONE, oneResult()));
        assertThrows(IllegalArgumentException.class, () -> definition("large_bottle",
                new ItemStack(Material.NETHER_WART), new ItemStack(Material.POTION, 2),
                true, null, null, -1, ResultMetadataMode.NONE, oneResult()));
    }

    @Test void ingredientCanBeKeptButBottleCannotBeKept() {
        assertFalse(recipe("kept", 1, false, null, null,
                -1, ResultMetadataMode.NONE, oneResult()).consumes(0));
        assertThrows(IllegalArgumentException.class, () -> definitionWithBottleConsumption(
                "kept_bottle", false));
    }

    @Test void consumedIngredientCanReturnAnItemButKeptIngredientCannot() {
        ItemStack glass = new ItemStack(Material.GLASS_BOTTLE);
        assertEquals(glass, recipe("returned", 1, true, glass, null,
                -1, ResultMetadataMode.NONE, oneResult()).remainder(0));
        assertThrows(IllegalArgumentException.class, () -> recipe("kept_returned", 1,
                false, glass, null, -1, ResultMetadataMode.NONE, oneResult()));
    }

    @Test void bottleCanReturnASeparateItemPerConvertedSlot() {
        ItemStack glass = new ItemStack(Material.GLASS_BOTTLE);
        StationRecipeDefinition recipe = recipe("bottle_return", 1, true, null,
                glass, -1, ResultMetadataMode.NONE, oneResult());
        BrewingListener.BrewTransaction plan = plan(recipe, recipe,
                new ItemStack(Material.NETHER_WART, 2));
        assertNotNull(plan);
        assertEquals(List.of(glass, glass), plan.returned());
        assertEquals(Material.POTION, plan.bottle(0).getType());
        assertEquals(Material.POTION, plan.bottle(1).getType());
    }

    @Test void threeBottlesChargeSharedIngredientAmountOnlyOnce() {
        StationRecipeDefinition recipe = recipe("charge_once", 2, true, null, null,
                -1, ResultMetadataMode.NONE, oneResult());
        BrewingListener.BrewTransaction plan = plan(recipe, recipe, recipe,
                new ItemStack(Material.NETHER_WART, 3));
        assertNotNull(plan);
        assertEquals(1, plan.ingredient().getAmount());
        assertTrue(plan.returned().isEmpty());
    }

    @Test void keptSharedIngredientSurvivesAllConvertedBottles() {
        StationRecipeDefinition recipe = recipe("kept_input", 2, false, null, null,
                -1, ResultMetadataMode.NONE, oneResult());
        BrewingListener.BrewTransaction plan = plan(recipe, recipe,
                new ItemStack(Material.NETHER_WART, 4));
        assertNotNull(plan);
        assertEquals(4, plan.ingredient().getAmount());
        assertEquals(Material.POTION, plan.bottle(0).getType());
    }

    @Test void ingredientRemainderOccupiesSlotWhenStackIsExhausted() {
        StationRecipeDefinition recipe = recipe("empty_slot_return", 2, true,
                new ItemStack(Material.GLASS_BOTTLE), null,
                -1, ResultMetadataMode.NONE, oneResult());
        BrewingListener.BrewTransaction plan = plan(recipe,
                new ItemStack(Material.NETHER_WART, 2));
        assertNotNull(plan);
        assertEquals(Material.GLASS_BOTTLE, plan.ingredient().getType());
        assertTrue(plan.returned().isEmpty());
    }

    @Test void ingredientRemainderIsDeliveredSeparatelyWhenStackRemains() {
        ItemStack glass = new ItemStack(Material.GLASS_BOTTLE);
        StationRecipeDefinition recipe = recipe("occupied_slot_return", 2, true,
                glass, null, -1, ResultMetadataMode.NONE, oneResult());
        BrewingListener.BrewTransaction plan = plan(recipe,
                new ItemStack(Material.NETHER_WART, 3));
        assertNotNull(plan);
        assertEquals(1, plan.ingredient().getAmount());
        assertEquals(Material.NETHER_WART, plan.ingredient().getType());
        assertEquals(List.of(glass), plan.returned());
    }

    @Test void compatibleRemainderMergesWithoutWorldDrop() {
        StationRecipeDefinition recipe = recipe("merged_return", 2, true,
                new ItemStack(Material.NETHER_WART), null,
                -1, ResultMetadataMode.NONE, oneResult());
        BrewingListener.BrewTransaction plan = plan(recipe,
                new ItemStack(Material.NETHER_WART, 3));
        assertNotNull(plan);
        assertEquals(2, plan.ingredient().getAmount());
        assertTrue(plan.returned().isEmpty());
    }

    @Test void conflictingBottleRecipesCannotChargeDifferentSharedCosts() {
        StationRecipeDefinition one = recipe("cost_one", 1, true, null, null,
                -1, ResultMetadataMode.NONE, oneResult());
        StationRecipeDefinition two = recipe("cost_two", 2, true, null, null,
                -1, ResultMetadataMode.NONE, oneResult());
        StationRecipeDefinition[] recipes = {one, two, null};
        assertFalse(BrewingListener.compatibleSharedRules(recipes));
        assertNull(BrewingListener.BrewTransaction.plan(recipes,
                new ItemStack(Material.NETHER_WART, 3), bottles(), outputs()));
    }

    @Test void nativeDeferredCommitRejectsChangedIngredientBottleOrRecipe() {
        StationRecipeDefinition first = recipe("native_first", 2, true, null, null,
                -1, ResultMetadataMode.NONE, oneResult());
        StationRecipeDefinition later = recipe("native_later", 2, true, null, null,
                -1, ResultMetadataMode.NONE, oneResult());
        ItemStack ingredient = new ItemStack(Material.NETHER_WART, 2);
        ItemStack[] bottles = bottles();
        StationRecipeDefinition[] expected = {first, null, null};
        assertTrue(BrewingListener.unchangedNativeInputs(ingredient, bottles, expected,
                ingredient.clone(), bottles(), expected.clone()));
        assertFalse(BrewingListener.unchangedNativeInputs(ingredient, bottles, expected,
                new ItemStack(Material.NETHER_WART), bottles(), expected));
        ItemStack[] changedBottle = bottles();
        changedBottle[0] = new ItemStack(Material.SPLASH_POTION);
        assertFalse(BrewingListener.unchangedNativeInputs(ingredient, bottles, expected,
                ingredient.clone(), changedBottle, expected));
        assertFalse(BrewingListener.unchangedNativeInputs(ingredient, bottles, expected,
                ingredient.clone(), bottles(), new StationRecipeDefinition[] {later, null, null}));
    }

    @Test void nativeAdvancedRulesCancelVanillaPathAndRejectAlteredEventResults() {
        StationRecipeDefinition recipe = recipe("native_advanced", 2, true, null, null,
                -1, ResultMetadataMode.NONE, oneResult());
        StationRecipeDefinition[] recipes = {recipe, null, null};
        assertTrue(BrewingListener.requiresControlledCommit(recipes));
        BrewingListener.BrewTransaction plan = BrewingListener.BrewTransaction.plan(
                recipes, new ItemStack(Material.NETHER_WART, 2), bottles(), outputs());
        assertNotNull(plan);
        assertTrue(BrewingListener.nativeResultsUnchanged(plan,
                java.util.Arrays.asList(outputs())));
        assertFalse(BrewingListener.nativeResultsUnchanged(plan,
                java.util.Arrays.asList(new ItemStack(Material.SPLASH_POTION),
                        new ItemStack(Material.POTION), null)));
    }

    @Test void overstackedBottleIsClaimedAndDeniedBeforeResultReplacement() {
        StationRecipeDefinition recipe = recipe("overstack", 1, true, null, null,
                -1, ResultMetadataMode.NONE, oneResult());
        BrewingListener.BrewMatch match = BrewingListener.resolve(List.of(recipe),
                new ItemStack(Material.NETHER_WART), new ItemStack(Material.POTION, 2),
                unused -> true);
        assertTrue(match.claimedInputs());
        assertNull(match.recipe());
        assertTrue(BrewingListener.deniesNativeBottle(match, 0, 3));
    }

    @Test void brewingDefinitionRejectsNonBottleWeightedResult() {
        assertThrows(IllegalArgumentException.class, () -> recipe("bad_output", 1,
                true, null, null, -1, ResultMetadataMode.NONE,
                List.of(new WeightedResult(new ItemStack(Material.POTION), 1),
                        new WeightedResult(new ItemStack(Material.DIAMOND), 1))));
    }

    @Test void customBrewRejectsMixedBottleDurations() {
        StationRecipeDefinition quick = recipeWithCookingTicks("quick", 20);
        StationRecipeDefinition slow = recipeWithCookingTicks("slow", 40);
        StationRecipeDefinition[] mixed = {quick, slow, null};
        assertFalse(BrewingListener.compatibleSharedRules(mixed));
        assertNull(BrewingListener.BrewTransaction.plan(mixed,
                new ItemStack(Material.NETHER_WART), bottles(), outputs()));
    }

    @Test void nativeStartDoesNotApplyAnArbitraryMixedDuration() {
        StationRecipeDefinition quick = recipeWithCookingTicks("start_quick", 20);
        StationRecipeDefinition slow = recipeWithCookingTicks("start_slow", 40);
        assertEquals(-1, BrewingListener.compatibleStartTime(
                new StationRecipeDefinition[] {quick, slow, null}));
        assertEquals(20, BrewingListener.compatibleStartTime(
                new StationRecipeDefinition[] {quick, quick, null}));
    }

    @Test void nativeCompletionDeniesRecipesWithConflictingDurations() {
        StationRecipeDefinition quick = recipeWithCookingTicks("native_quick", 20);
        StationRecipeDefinition slow = recipeWithCookingTicks("native_slow", 40);
        assertFalse(BrewingListener.nativeBrewCanProceed(
                new StationRecipeDefinition[] {quick, slow, null}));
        assertTrue(BrewingListener.nativeBrewCanProceed(
                new StationRecipeDefinition[] {quick, quick, null}));
        assertTrue(BrewingListener.nativeBrewCanProceed(
                new StationRecipeDefinition[] {null, null, null}));
    }

    @Test void controlledBrewRejectsMissingOrExtraResultSlots() {
        StationRecipeDefinition recipe = recipe("result_slots", 2, true, null,
                null, -1, ResultMetadataMode.NONE, oneResult());
        ItemStack ingredient = new ItemStack(Material.NETHER_WART, 2);
        StationRecipeDefinition[] matched = {recipe, null, null};
        assertNotNull(BrewingListener.BrewTransaction.plan(matched, ingredient,
                bottles(), outputs()));
        assertNull(BrewingListener.BrewTransaction.plan(matched, ingredient,
                bottles(), new ItemStack[] {new ItemStack(Material.POTION)}));
        assertNull(BrewingListener.BrewTransaction.plan(matched, ingredient,
                bottles(), new ItemStack[] {new ItemStack(Material.POTION),
                        new ItemStack(Material.POTION), null, new ItemStack(Material.POTION)}));
    }

    @Test void controlledBrewRejectsMultipleItemsInOneBottleResult() {
        StationRecipeDefinition recipe = recipe("stacked_result", 2, true, null,
                null, -1, ResultMetadataMode.NONE, oneResult());
        assertNull(BrewingListener.BrewTransaction.plan(
                new StationRecipeDefinition[] {recipe, null, null},
                new ItemStack(Material.NETHER_WART, 2), bottles(),
                new ItemStack[] {new ItemStack(Material.POTION, 2),
                        new ItemStack(Material.POTION), null}));
    }

    @Test void controlledBrewRejectsNonBottleResultMaterial() {
        StationRecipeDefinition recipe = recipe("wrong_result_material", 2, true, null,
                null, -1, ResultMetadataMode.NONE, oneResult());
        assertNull(BrewingListener.BrewTransaction.plan(
                new StationRecipeDefinition[] {recipe, null, null},
                new ItemStack(Material.NETHER_WART, 2), bottles(),
                new ItemStack[] {new ItemStack(Material.DIAMOND),
                        new ItemStack(Material.POTION), null}));
    }

    @Test void controlledBrewRejectsForgedResultMetadata() {
        StationRecipeDefinition recipe = recipe("forged_result", 2, true, null,
                null, -1, ResultMetadataMode.NONE, oneResult());
        assertNull(BrewingListener.BrewTransaction.plan(
                new StationRecipeDefinition[] {recipe, null, null},
                new ItemStack(Material.NETHER_WART, 2), bottles(),
                new ItemStack[] {named(Material.POTION, "Not a selected result"),
                        new ItemStack(Material.POTION), null}));
    }

    @Test void controlledBrewRejectsInvalidUnmatchedBottleResult() {
        StationRecipeDefinition recipe = recipe("invalid_unmatched", 2, true, null,
                null, -1, ResultMetadataMode.NONE, oneResult());
        StationRecipeDefinition[] matched = {recipe, null, null};
        ItemStack ingredient = new ItemStack(Material.NETHER_WART, 2);
        assertNull(BrewingListener.BrewTransaction.plan(matched, ingredient,
                bottles(), new ItemStack[] {new ItemStack(Material.POTION),
                        new ItemStack(Material.DIAMOND), null}));
        assertNull(BrewingListener.BrewTransaction.plan(matched, ingredient,
                bottles(), new ItemStack[] {new ItemStack(Material.POTION),
                        new ItemStack(Material.POTION, 2), null}));
    }

    @Test void controlledBrewPreservesUnmatchedBottleOccupancy() {
        StationRecipeDefinition recipe = recipe("unmatched_occupancy", 2, true, null,
                null, -1, ResultMetadataMode.NONE, oneResult());
        StationRecipeDefinition[] matched = {recipe, null, null};
        ItemStack ingredient = new ItemStack(Material.NETHER_WART, 2);
        assertNull(BrewingListener.BrewTransaction.plan(matched, ingredient,
                bottles(), new ItemStack[] {new ItemStack(Material.POTION), null, null}));
        assertNull(BrewingListener.BrewTransaction.plan(matched, ingredient,
                bottles(), new ItemStack[] {new ItemStack(Material.POTION),
                        new ItemStack(Material.POTION), new ItemStack(Material.POTION)}));
        assertNotNull(BrewingListener.BrewTransaction.plan(matched, ingredient,
                bottles(), outputs()));
    }

    @Test void bottleLoreCopiesAfterWeightedOutcomeSelection() {
        ItemStack bottle = lore(Material.POTION, "Input bottle lore");
        StationRecipeDefinition recipe = recipe("weighted_copy", 1, true, null, null,
                1, ResultMetadataMode.LORE,
                List.of(new WeightedResult(named(Material.POTION, "First outcome"), 1),
                        new WeightedResult(named(Material.SPLASH_POTION, "Second outcome"), 2)));
        ItemStack chosen = BrewingListener.selectResultForTicket(recipe,
                lore(Material.NETHER_WART, "Wrong source lore"), bottle, 2);
        assertEquals(Material.SPLASH_POTION, chosen.getType());
        assertEquals(List.of("Input bottle lore"), chosen.getItemMeta().getLore());
        assertEquals("Second outcome", chosen.getItemMeta().getDisplayName());
        assertFalse(recipe.resultChoices().get(1).item().getItemMeta().hasLore());
        assertEquals(List.of("Input bottle lore"), bottle.getItemMeta().getLore());
    }

    @Test void ingredientCanSupplySelectiveResultLore() {
        StationRecipeDefinition recipe = recipe("ingredient_copy", 1, true, null, null,
                0, ResultMetadataMode.LORE,
                List.of(new WeightedResult(named(Material.POTION, "Target potion"), 1)));
        ItemStack chosen = BrewingListener.selectResultForTicket(recipe,
                lore(Material.NETHER_WART, "Input herb lore"),
                lore(Material.POTION, "Wrong bottle lore"), 0);
        assertEquals(List.of("Input herb lore"), chosen.getItemMeta().getLore());
        assertEquals("Target potion", chosen.getItemMeta().getDisplayName());
        assertEquals(Material.POTION, chosen.getType());
    }

    private static List<WeightedResult> oneResult() {
        return List.of(new WeightedResult(new ItemStack(Material.POTION), 1));
    }

    private static StationRecipeDefinition recipe(String id, int ingredientAmount,
                                                   boolean consumeIngredient,
                                                   ItemStack ingredientRemainder,
                                                   ItemStack bottleRemainder,
                                                   int copySource, ResultMetadataMode copyMode,
                                                   List<WeightedResult> choices) {
        return definition(id, new ItemStack(Material.NETHER_WART, ingredientAmount),
                new ItemStack(Material.POTION), consumeIngredient, ingredientRemainder,
                bottleRemainder, copySource, copyMode, choices);
    }

    private static StationRecipeDefinition definition(String id, ItemStack ingredient,
                                                       ItemStack bottle, boolean consumeIngredient,
                                                       ItemStack ingredientRemainder,
                                                       ItemStack bottleRemainder,
                                                       int copySource, ResultMetadataMode copyMode,
                                                       List<WeightedResult> choices) {
        return definitionAtTicks(id, ingredient, bottle, consumeIngredient,
                ingredientRemainder, bottleRemainder, copySource, copyMode, choices, 20);
    }

    private static StationRecipeDefinition recipeWithCookingTicks(String id, int ticks) {
        return definitionAtTicks(id, new ItemStack(Material.NETHER_WART),
                new ItemStack(Material.POTION), true, null, null,
                -1, ResultMetadataMode.NONE, oneResult(), ticks);
    }

    private static StationRecipeDefinition definitionAtTicks(String id, ItemStack ingredient,
                                                              ItemStack bottle,
                                                              boolean consumeIngredient,
                                                              ItemStack ingredientRemainder,
                                                              ItemStack bottleRemainder,
                                                              int copySource,
                                                              ResultMetadataMode copyMode,
                                                              List<WeightedResult> choices,
                                                              int cookingTicks) {
        return new StationRecipeDefinition(new NamespacedKey("recipeweaver", "brew_rules_" + id),
                StationKind.BREWING, List.of(ingredient, bottle), choices.get(0).item(), "",
                cookingTicks,
                0, 0, true, false,
                List.of(IngredientMatchMode.EXACT, IngredientMatchMode.EXACT),
                List.of(List.of(), List.of()), java.util.Arrays.asList(null, null),
                CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                null, null, null, copySource, copyMode,
                List.of(consumeIngredient, true),
                java.util.Arrays.asList(ingredientRemainder, bottleRemainder),
                List.of(false, false), choices);
    }

    private static StationRecipeDefinition definitionWithBottleConsumption(String id,
                                                                            boolean consumeBottle) {
        return new StationRecipeDefinition(new NamespacedKey("recipeweaver", "brew_rules_" + id),
                StationKind.BREWING, List.of(new ItemStack(Material.NETHER_WART),
                        new ItemStack(Material.POTION)), new ItemStack(Material.POTION), "", 20,
                0, 0, true, false,
                List.of(IngredientMatchMode.EXACT, IngredientMatchMode.EXACT),
                List.of(List.of(), List.of()), java.util.Arrays.asList(null, null),
                CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                null, null, null, -1, ResultMetadataMode.NONE,
                List.of(true, consumeBottle), java.util.Arrays.asList(null, null),
                List.of(false, false), oneResult());
    }

    private static BrewingListener.BrewTransaction plan(StationRecipeDefinition recipe,
                                                         ItemStack ingredient) {
        return plan(recipe, null, null, ingredient);
    }

    private static BrewingListener.BrewTransaction plan(StationRecipeDefinition first,
                                                         StationRecipeDefinition second,
                                                         ItemStack ingredient) {
        return plan(first, second, null, ingredient);
    }

    private static BrewingListener.BrewTransaction plan(StationRecipeDefinition first,
                                                         StationRecipeDefinition second,
                                                         StationRecipeDefinition third,
                                                         ItemStack ingredient) {
        ItemStack[] bottleInputs = bottles();
        ItemStack[] results = outputs();
        if (third != null) {
            bottleInputs[2] = new ItemStack(Material.POTION);
            results[2] = new ItemStack(Material.POTION);
        }
        return BrewingListener.BrewTransaction.plan(
                new StationRecipeDefinition[] {first, second, third},
                ingredient, bottleInputs, results);
    }

    private static ItemStack[] bottles() {
        return new ItemStack[] {new ItemStack(Material.POTION),
                new ItemStack(Material.POTION), null};
    }

    private static ItemStack[] outputs() {
        return new ItemStack[] {new ItemStack(Material.POTION),
                new ItemStack(Material.POTION), null};
    }

    private static ItemStack named(Material material, String title) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(title);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack lore(Material material, String line) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setLore(List.of(line));
        item.setItemMeta(meta);
        return item;
    }
}
