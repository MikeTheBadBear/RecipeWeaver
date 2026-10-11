package dev.recipeweaver.core;

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
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.ResultMetadataMode;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import dev.recipeweaver.api.WeightedResult;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.recipe.CookingBookCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CauldronRulesTest {
    @TempDir Path directory;

    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test
    void firstIngredientAmountCanBeConsumedWithoutTouchingTheRestOfTheHand() {
        ItemStack[] storage = new ItemStack[9];
        storage[2] = new ItemStack(Material.CLAY_BALL, 5);
        ItemStack[] next = CauldronListener.consumeHand(storage, 2, 3, true);
        assertEquals(2, next[2].getAmount());
        assertEquals(5, storage[2].getAmount());
        assertEquals(3, CauldronListener.amount(storage[2], 3).getAmount());
    }

    @Test
    void secondIngredientAmountConsumesExactlyTheRequiredCount() {
        ItemStack[] storage = new ItemStack[9];
        storage[1] = new ItemStack(Material.REDSTONE, 7);
        ItemStack[] next = CauldronListener.consumeHand(storage, 1, 4, true);
        assertEquals(3, next[1].getAmount());
        assertEquals(7, storage[1].getAmount());
    }

    @Test
    void secondInputCanBeKeptWithoutMakingAnExtraStack() {
        ItemStack[] storage = new ItemStack[9];
        storage[0] = new ItemStack(Material.GLASS_BOTTLE, 2);
        ItemStack[] next = CauldronListener.consumeHand(storage, 0, 2, false);
        assertEquals(2, next[0].getAmount());
        assertEquals(2, storage[0].getAmount());
        assertFalse(next[0] == storage[0]);
        assertTrue(recipe(List.of(true, false), Arrays.asList(null, null), -1,
                ResultMetadataMode.NONE, List.of(result(Material.DIAMOND, 1))).hasIngredient(1));
    }

    @Test
    void keptSecondInputMustStillBeInTheSameHandAtCommit() {
        ItemStack[] storage = new ItemStack[9];
        ItemStack expected = new ItemStack(Material.GLASS_BOTTLE, 2);
        storage[4] = expected.clone();
        assertTrue(CauldronListener.keptSecondStillPresent(storage, 4, expected));
        storage[4] = new ItemStack(Material.GLASS_BOTTLE);
        assertFalse(CauldronListener.keptSecondStillPresent(storage, 4, expected));
        storage[4] = new ItemStack(Material.REDSTONE, 2);
        assertFalse(CauldronListener.keptSecondStillPresent(storage, 4, expected));
        storage[4] = null;
        assertFalse(CauldronListener.keptSecondStillPresent(storage, 4, expected));
    }

    @Test
    void firstPendingIngredientWritesAndReturnsItsRequiredAmount() throws Exception {
        Location location = new Location(MockBukkit.getMock().addSimpleWorld("multi-cauldron"), 3, 64, 4);
        CauldronRepository first = new CauldronRepository(directory);
        first.put(location, new ItemStack(Material.CLAY_BALL, 3));
        assertTrue(Files.readString(directory.resolve("cauldrons.yml")).contains("amount: 3"));
        assertEquals(3, first.get(location).getAmount());
        assertEquals(3, first.remove(location).getAmount());
        assertNull(first.get(location));
    }

    @Test
    void missingRequiredHandAmountCannotBeConsumed() {
        ItemStack[] storage = new ItemStack[9];
        storage[0] = new ItemStack(Material.REDSTONE, 2);
        assertNull(CauldronListener.consumeHand(storage, 0, 3, true));
        assertEquals(2, storage[0].getAmount());
    }

    @Test
    void firstIngredientRemainderNeedsCapacityBeforeMixStarts() {
        StationRecipeDefinition recipe = recipe(List.of(true, true),
                Arrays.asList(new ItemStack(Material.BUCKET), null), -1,
                ResultMetadataMode.NONE, List.of(result(Material.DIAMOND, 1)));
        ItemStack[] storage = fullStorage();
        storage[0] = null;
        assertFalse(CauldronListener.completionFits(recipe, storage,
                new ItemStack(Material.DIAMOND)));
    }

    @Test
    void secondIngredientRemainderNeedsCapacityBeforeMixStarts() {
        StationRecipeDefinition recipe = recipe(List.of(true, true),
                Arrays.asList(null, new ItemStack(Material.GLASS_BOTTLE)), -1,
                ResultMetadataMode.NONE, List.of(result(Material.DIAMOND, 1)));
        ItemStack[] storage = fullStorage();
        storage[0] = null; // The result fits; only its separate remainder does not.
        assertFalse(CauldronListener.completionFits(recipe, storage,
                new ItemStack(Material.DIAMOND)));
    }

    @Test
    void resultAndBothRemaindersFitWhenThreeSlotsAreFree() {
        StationRecipeDefinition recipe = recipe(List.of(true, true),
                Arrays.asList(new ItemStack(Material.BUCKET),
                        new ItemStack(Material.GLASS_BOTTLE)), -1,
                ResultMetadataMode.NONE, List.of(result(Material.DIAMOND, 1)));
        ItemStack[] storage = fullStorage();
        storage[0] = null;
        storage[1] = null;
        storage[2] = null;
        assertTrue(CauldronListener.completionFits(recipe, storage,
                new ItemStack(Material.DIAMOND)));
        assertNull(storage[0]);
    }

    @Test
    void resultCanCopyLoreFromThePersistedFirstInput() {
        StationRecipeDefinition recipe = recipe(List.of(true, true),
                Arrays.asList(null, null), 0, ResultMetadataMode.LORE,
                List.of(result(Material.DIAMOND, 1)));
        ItemStack first = lore(Material.CLAY_BALL, "First ingredient");
        ItemStack copied = CauldronListener.resultForTicket(recipe, first,
                lore(Material.REDSTONE, "Second ingredient"), 0);
        assertEquals(List.of("First ingredient"), copied.getItemMeta().getLore());
        assertEquals(Material.DIAMOND, copied.getType());
        assertEquals(List.of("First ingredient"), first.getItemMeta().getLore());
    }

    @Test
    void resultCanCopyLoreFromTheActualSecondInput() {
        StationRecipeDefinition recipe = recipe(List.of(true, true),
                Arrays.asList(null, null), 1, ResultMetadataMode.LORE,
                List.of(result(Material.DIAMOND, 1)));
        ItemStack copied = CauldronListener.resultForTicket(recipe,
                lore(Material.CLAY_BALL, "First ingredient"),
                lore(Material.REDSTONE, "Second ingredient"), 0);
        assertEquals(List.of("Second ingredient"), copied.getItemMeta().getLore());
    }

    @Test
    void weightedCauldronOutcomeCopiesLoreAfterTicketSelection() {
        StationRecipeDefinition recipe = recipe(List.of(true, true),
                Arrays.asList(null, null), 1, ResultMetadataMode.LORE,
                List.of(result(Material.DIAMOND, 1), result(Material.EMERALD, 2)));
        ItemStack first = lore(Material.CLAY_BALL, "First");
        ItemStack second = lore(Material.REDSTONE, "Second");
        ItemStack low = CauldronListener.resultForTicket(recipe, first, second, 0);
        ItemStack high = CauldronListener.resultForTicket(recipe, first, second, 2);
        assertEquals(Material.DIAMOND, low.getType());
        assertEquals(Material.EMERALD, high.getType());
        assertEquals(List.of("Second"), high.getItemMeta().getLore());
    }

    @Test
    void keptFirstInputIsRejectedBecauseItWouldDuplicateOnRetrieval() {
        assertThrows(IllegalArgumentException.class, () -> recipe(List.of(false, true),
                Arrays.asList(null, null), -1, ResultMetadataMode.NONE,
                List.of(result(Material.DIAMOND, 1))));
    }

    @Test
    void cauldronAllowsPostCommitPlayerActionsButNotPlayerCooldowns() {
        CraftSoundAction sound = new CraftSoundAction(key("sound"), 1.0F, 1.0F, false);
        CraftAdvancementAction advancement = new CraftAdvancementAction(key("advancement"));
        CraftCommandAction command = new CraftCommandAction("say crafted",
                CraftCommandSender.CONSOLE, false);
        StationRecipeDefinition recipe = new StationRecipeDefinition(key("actions"),
                StationKind.CAULDRON, List.of(new ItemStack(Material.CLAY_BALL),
                        new ItemStack(Material.REDSTONE)), new ItemStack(Material.DIAMOND),
                "", 200, 0, 0, true, false,
                List.of(IngredientMatchMode.EXACT, IngredientMatchMode.EXACT),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                sound, advancement, command, -1, ResultMetadataMode.NONE,
                List.of(true, true), Arrays.asList(null, null));
        assertEquals(sound, recipe.craftSound());
        assertEquals(advancement, recipe.craftAdvancement());
        assertEquals(command, recipe.craftCommand());
    }

    private static StationRecipeDefinition recipe(List<Boolean> consumes,
            List<ItemStack> remainders, int copySource, ResultMetadataMode mode,
            List<WeightedResult> results) {
        return new StationRecipeDefinition(key("rule"), StationKind.CAULDRON,
                List.of(new ItemStack(Material.CLAY_BALL, 3),
                        new ItemStack(Material.REDSTONE, 2)), results.get(0).item(),
                "", 200, 0, 0, true, false,
                List.of(IngredientMatchMode.MATERIAL, IngredientMatchMode.MATERIAL),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                null, null, null, copySource, mode,
                consumes, remainders, List.of(false, false), results);
    }

    private static WeightedResult result(Material material, int weight) {
        return new WeightedResult(new ItemStack(material), weight);
    }

    private static ItemStack lore(Material material, String text) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setLore(List.of(text));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack[] fullStorage() {
        ItemStack[] storage = new ItemStack[9];
        Arrays.fill(storage, new ItemStack(Material.COBBLESTONE, 64));
        return storage;
    }

    private static NamespacedKey key(String id) {
        return new NamespacedKey("recipeweaver", id);
    }
}
