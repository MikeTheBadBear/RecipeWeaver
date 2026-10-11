package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.StonecuttingRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class NativeStonecutterGuardTest {
    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void unresolvedOwnedEqualOutputsFailClosedWithoutBlockingUnrelatedVanilla() {
        ItemStack input = new ItemStack(Material.STONE);
        ItemStack result = new ItemStack(Material.GOLD_INGOT);
        StonecuttingRecipe owned = recipe(new NamespacedKey("recipeweaver", "equal_owned"),
                input, result);
        StonecuttingRecipe second = recipe(new NamespacedKey("minecraft", "equal_vanilla"),
                input, result);
        List<StonecuttingRecipe> ambiguous = List.of(owned, second);

        assertNull(DiscoveryMatches.stonecutting(input, result, ambiguous));
        assertTrue(NativeStonecutterGuard.hasOwnedMatch(input, result, ambiguous));
        assertEquals(owned.getKey(), DiscoveryMatches.selectedStonecutting(
                0, ambiguous, input, result));
        assertEquals(second.getKey(), DiscoveryMatches.selectedStonecutting(
                1, ambiguous, input, result));
        assertFalse(NativeStonecutterGuard.hasOwnedMatch(input, result, List.of(second)));
        assertFalse(NativeStonecutterGuard.hasOwnedMatch(new ItemStack(Material.DIRT),
                result, ambiguous));
        assertFalse(NativeStonecutterGuard.hasOwnedMatch(input,
                new ItemStack(Material.EMERALD), ambiguous));
    }

    private static StonecuttingRecipe recipe(NamespacedKey key,
            ItemStack input, ItemStack result) {
        return new StonecuttingRecipe(key, result, new RecipeChoice.ExactChoice(input));
    }
}
