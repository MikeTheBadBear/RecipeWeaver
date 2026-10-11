package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.StonecuttingRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class DiscoveryMatchesTest {
    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test
    void stonecutterRequiresOneMatchingNativeRecipe() {
        StonecuttingRecipe custom = new StonecuttingRecipe(key("cut_one"),
                new ItemStack(Material.EMERALD), new RecipeChoice.ExactChoice(new ItemStack(Material.STONE)));
        StonecuttingRecipe otherInput = new StonecuttingRecipe(key("cut_other_input"),
                new ItemStack(Material.EMERALD), new RecipeChoice.ExactChoice(new ItemStack(Material.DIRT)));
        assertEquals(custom.getKey(), DiscoveryMatches.stonecutting(
                new ItemStack(Material.STONE), new ItemStack(Material.EMERALD),
                List.of(custom, otherInput)));
        assertEquals(custom.getKey(), DiscoveryMatches.stonecutting(new ItemStack(Material.STONE),
                new ItemStack(Material.EMERALD), List.of(custom, custom)));
        StonecuttingRecipe otherKey = new StonecuttingRecipe(key("cut_second"),
                new ItemStack(Material.EMERALD),
                new RecipeChoice.ExactChoice(new ItemStack(Material.STONE)));
        assertNull(DiscoveryMatches.stonecutting(new ItemStack(Material.STONE),
                new ItemStack(Material.EMERALD), List.of(custom, otherKey)));
        assertNull(DiscoveryMatches.stonecutting(new ItemStack(Material.DIRT),
                new ItemStack(Material.DIAMOND), List.of(custom, otherInput)));
    }

    @Test
    void selectedStonecutterRecipeResolvesEqualOutputKeysWithoutGuessing() {
        StonecuttingRecipe first = new StonecuttingRecipe(key("selected_first"),
                new ItemStack(Material.EMERALD),
                new RecipeChoice.ExactChoice(new ItemStack(Material.STONE)));
        StonecuttingRecipe second = new StonecuttingRecipe(key("selected_second"),
                new ItemStack(Material.EMERALD),
                new RecipeChoice.ExactChoice(new ItemStack(Material.STONE)));
        List<StonecuttingRecipe> offered = List.of(first, second);
        ItemStack input = new ItemStack(Material.STONE);
        ItemStack result = new ItemStack(Material.EMERALD);

        assertNull(DiscoveryMatches.stonecutting(input, result, offered));
        assertEquals(first.getKey(), DiscoveryMatches.selectedStonecutting(
                0, offered, input, result));
        assertEquals(second.getKey(), DiscoveryMatches.selectedStonecutting(
                1, offered, input, result));
        assertNull(DiscoveryMatches.selectedStonecutting(-1, offered, input, result));
        assertNull(DiscoveryMatches.selectedStonecutting(2, offered, input, result));
        assertNull(DiscoveryMatches.selectedStonecutting(1, offered,
                new ItemStack(Material.DIRT), result));
        assertNull(DiscoveryMatches.selectedStonecutting(1, offered, input,
                new ItemStack(Material.DIAMOND)));
        assertNull(DiscoveryMatches.stonecutting(new Object(), input, result, offered));
        assertEquals(first.getKey(), DiscoveryMatches.stonecutting(
                new Object(), input, result, List.of(first)));
    }

    @Test
    void furnaceExtractionCreditsEveryPositiveUseForTheTakenMaterial() {
        FurnaceRecipe emerald = new FurnaceRecipe(key("smelt_emerald"), new ItemStack(Material.EMERALD),
                new RecipeChoice.ExactChoice(new ItemStack(Material.STONE)), 0, 20);
        FurnaceRecipe diamond = new FurnaceRecipe(key("smelt_diamond"), new ItemStack(Material.DIAMOND),
                new RecipeChoice.ExactChoice(new ItemStack(Material.DIRT)), 0, 20);
        FurnaceRecipe secondEmerald = new FurnaceRecipe(key("smelt_other_emerald"),
                new ItemStack(Material.EMERALD),
                new RecipeChoice.ExactChoice(new ItemStack(Material.REDSTONE)), 0, 20);
        assertEquals(Set.of(emerald.getKey()), DiscoveryMatches.furnaceExtractionKeys(
                Map.of(emerald, 2, diamond, 1), Material.EMERALD));
        assertEquals(Set.of(emerald.getKey(), secondEmerald.getKey()),
                DiscoveryMatches.furnaceExtractionKeys(
                        Map.of(emerald, 2, secondEmerald, 1, diamond, 1), Material.EMERALD));
        assertEquals(Set.of(secondEmerald.getKey()), DiscoveryMatches.furnaceExtractionKeys(
                Map.of(emerald, 0, secondEmerald, 1, diamond, 4), Material.EMERALD));
        assertEquals(Set.of(), DiscoveryMatches.furnaceExtractionKeys(
                Map.of(emerald, 2), Material.AIR));
        FurnaceRecipe sameKey = new FurnaceRecipe(emerald.getKey(), new ItemStack(Material.EMERALD),
                new RecipeChoice.ExactChoice(new ItemStack(Material.STONE)), 0, 20);
        Map<FurnaceRecipe, Integer> repeatedKey = new java.util.IdentityHashMap<>();
        repeatedKey.put(emerald, 1);
        repeatedKey.put(sameKey, 1);
        assertEquals(Set.of(emerald.getKey()), DiscoveryMatches.furnaceExtractionKeys(
                repeatedKey, Material.EMERALD));
    }

    @Test
    void furnaceClickUsesResultMetadataToResolveSameMaterialRecipes() {
        ItemStack special = new ItemStack(Material.EMERALD);
        var meta = special.getItemMeta();
        meta.setDisplayName("Special emerald");
        special.setItemMeta(meta);
        FurnaceRecipe plainRecipe = new FurnaceRecipe(key("plain_emerald"),
                new ItemStack(Material.EMERALD),
                new RecipeChoice.ExactChoice(new ItemStack(Material.STONE)), 0, 20);
        FurnaceRecipe specialRecipe = new FurnaceRecipe(key("special_emerald"), special,
                new RecipeChoice.ExactChoice(new ItemStack(Material.DIRT)), 0, 20);
        Map<FurnaceRecipe, Integer> used = Map.of(plainRecipe, 2, specialRecipe, 1);
        assertEquals(Set.of(plainRecipe.getKey(), specialRecipe.getKey()),
                DiscoveryMatches.furnaceExtractionKeys(used, Material.EMERALD));
        assertEquals(plainRecipe.getKey(), DiscoveryMatches.furnace(used,
                new ItemStack(Material.EMERALD, 4)));
        ItemStack extractedSpecial = special.clone();
        extractedSpecial.setAmount(3);
        assertEquals(specialRecipe.getKey(), DiscoveryMatches.furnace(used, extractedSpecial));
    }

    @Test
    void onlyActionsThatTakeTheResultDiscoverARecipe() {
        assertTrue(DiscoveryListener.takesResult(InventoryAction.PICKUP_ALL));
        assertTrue(DiscoveryListener.takesResult(InventoryAction.MOVE_TO_OTHER_INVENTORY));
        assertFalse(DiscoveryListener.takesResult(InventoryAction.NOTHING));
        assertFalse(DiscoveryListener.takesResult(InventoryAction.PLACE_ALL));
        assertFalse(DiscoveryListener.takesCraftResult(InventoryAction.PICKUP_ALL,
                org.bukkit.event.inventory.InventoryType.SlotType.CRAFTING,
                new ItemStack(Material.EMERALD)));
        assertFalse(DiscoveryListener.takesCraftResult(InventoryAction.PICKUP_ALL,
                org.bukkit.event.inventory.InventoryType.SlotType.RESULT, null));
        assertTrue(DiscoveryListener.takesCraftResult(InventoryAction.PICKUP_ALL,
                org.bukkit.event.inventory.InventoryType.SlotType.RESULT,
                new ItemStack(Material.EMERALD)));
    }

    private static NamespacedKey key(String name) { return new NamespacedKey("recipeweaver", name); }
}
