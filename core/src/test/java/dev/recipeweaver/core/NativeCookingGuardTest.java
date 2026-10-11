package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.SmokingRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class NativeCookingGuardTest {
    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void missingStartDeniesMatchingOwnedCookButLeavesUnrelatedVanillaCook() {
        ItemStack dirt = new ItemStack(Material.DIRT);
        ItemStack diamond = new ItemStack(Material.DIAMOND);
        StationRecipeDefinition owned = new StationRecipeDefinition(
                new NamespacedKey("recipeweaver", "missing_campfire_start"),
                StationKind.CAMPFIRE, List.of(dirt), diamond, "", 20, 0, 0, true, false);
        StationRecipeDefinition disabled = new StationRecipeDefinition(
                new NamespacedKey("recipeweaver", "missing_disabled_start"),
                StationKind.FURNACE, List.of(dirt), diamond, "", 20, 0, 0, false, false);

        assertTrue(NativeCookingGuard.requiresTrackedStart(List.of(owned),
                StationKind.CAMPFIRE, dirt, diamond));
        assertTrue(NativeCookingGuard.requiresTrackedStart(List.of(disabled),
                StationKind.FURNACE, dirt, diamond));
        assertFalse(NativeCookingGuard.requiresTrackedStart(List.of(owned),
                StationKind.CAMPFIRE, new ItemStack(Material.STONE), diamond));
        assertFalse(NativeCookingGuard.requiresTrackedStart(List.of(owned),
                StationKind.FURNACE, dirt, diamond));
        assertFalse(NativeCookingGuard.requiresTrackedStart(List.of(),
                StationKind.CAMPFIRE, dirt, diamond));
    }

    @Test void missingStartDeniesOwnedCookEvenWhenAnotherListenerChangesOutput() {
        StationRecipeDefinition owned = new StationRecipeDefinition(
                new NamespacedKey("recipeweaver", "changed_campfire_output"),
                StationKind.CAMPFIRE, List.of(new ItemStack(Material.DIRT)),
                new ItemStack(Material.DIAMOND), "", 20, 0, 0, true, false);
        assertTrue(NativeCookingGuard.requiresTrackedStart(List.of(owned),
                StationKind.CAMPFIRE, new ItemStack(Material.DIRT),
                new ItemStack(Material.EMERALD)));
    }

    @Test void missingStartDeniesDisabledVanillaFurnaceAlreadyInProgress() {
        FurnaceRecipe disabled = new FurnaceRecipe(NamespacedKey.minecraft("old_cook"),
                new ItemStack(Material.DIAMOND),
                new RecipeChoice.ExactChoice(new ItemStack(Material.DIRT)), 0, 20);
        assertTrue(NativeCookingGuard.requiresTrackedDisabledCook(List.of(disabled),
                StationKind.FURNACE, new ItemStack(Material.DIRT), new ItemStack(Material.DIAMOND)));
    }

    @Test void missingStartDisabledCookDeniesChangedOutputButNotDifferentInputOrStation() {
        FurnaceRecipe disabled = new FurnaceRecipe(NamespacedKey.minecraft("old_cook"),
                new ItemStack(Material.DIAMOND),
                new RecipeChoice.ExactChoice(new ItemStack(Material.DIRT)), 0, 20);
        assertFalse(NativeCookingGuard.requiresTrackedDisabledCook(List.of(disabled),
                StationKind.FURNACE, new ItemStack(Material.STONE), new ItemStack(Material.DIAMOND)));
        assertTrue(NativeCookingGuard.requiresTrackedDisabledCook(List.of(disabled),
                StationKind.FURNACE, new ItemStack(Material.DIRT), new ItemStack(Material.EMERALD)));
        assertFalse(NativeCookingGuard.requiresTrackedDisabledCook(List.of(disabled),
                StationKind.CAMPFIRE, new ItemStack(Material.DIRT), new ItemStack(Material.DIAMOND)));
    }

    @Test void missingStartDeniesDisabledVanillaCampfireAlreadyInProgress() {
        CampfireRecipe disabled = new CampfireRecipe(NamespacedKey.minecraft("old_campfire"),
                new ItemStack(Material.DIAMOND),
                new RecipeChoice.ExactChoice(new ItemStack(Material.DIRT)), 0, 20);
        assertTrue(NativeCookingGuard.requiresTrackedDisabledCook(List.of(disabled),
                StationKind.CAMPFIRE, new ItemStack(Material.DIRT), new ItemStack(Material.DIAMOND)));
    }

    @Test void changedFurnaceOutputCannotEscapeRestrictedStartByChangingInputToo() {
        var owned = new NativeCookingGuard.Started(
                new NamespacedKey("recipeweaver", "restricted_furnace"),
                StationKind.FURNACE, new ItemStack(Material.DIRT),
                new ItemStack(Material.DIAMOND), -1);
        var vanilla = new NativeCookingGuard.Started(NamespacedKey.minecraft("restricted_smoker"),
                StationKind.SMOKER, new ItemStack(Material.DIRT),
                new ItemStack(Material.DIAMOND), -1);
        assertTrue(NativeCookingGuard.changedOutputRequiresDenial(List.of(owned),
                StationKind.FURNACE, -1, List.of()));
        assertTrue(NativeCookingGuard.changedOutputRequiresDenial(List.of(vanilla),
                StationKind.SMOKER, -1, List.of(vanilla.key())));
        assertFalse(NativeCookingGuard.changedOutputRequiresDenial(List.of(vanilla),
                StationKind.SMOKER, -1, List.of()));
        assertFalse(NativeCookingGuard.changedOutputRequiresDenial(List.of(owned),
                StationKind.BLAST_FURNACE, -1, List.of()));
    }

    @Test void changedVanillaRecipeCannotAuthorizeOldCookingStart() {
        NamespacedKey key = NamespacedKey.minecraft("replaced_furnace");
        ItemStack dirt = new ItemStack(Material.DIRT);
        ItemStack diamond = new ItemStack(Material.DIAMOND);
        var start = new NativeCookingGuard.Started(key, StationKind.FURNACE,
                dirt, diamond, -1);
        var original = new FurnaceRecipe(key, diamond,
                new RecipeChoice.ExactChoice(dirt), 0, 20);
        assertTrue(NativeCookingGuard.activeVanillaCookMatches(start, original, dirt, diamond));
        assertFalse(NativeCookingGuard.activeVanillaCookMatches(start, null, dirt, diamond));
        assertFalse(NativeCookingGuard.activeVanillaCookMatches(start,
                new FurnaceRecipe(key, diamond,
                        new RecipeChoice.ExactChoice(new ItemStack(Material.STONE)), 0, 20),
                dirt, diamond));
        assertFalse(NativeCookingGuard.activeVanillaCookMatches(start,
                new FurnaceRecipe(key, new ItemStack(Material.EMERALD),
                        new RecipeChoice.ExactChoice(dirt), 0, 20), dirt, diamond));
        assertFalse(NativeCookingGuard.activeVanillaCookMatches(start,
                new SmokingRecipe(key, diamond, new RecipeChoice.ExactChoice(dirt), 0, 20),
                dirt, diamond));
    }

    @Test void unidentifiedCampfireSlotCannotBorrowOwnedTokenFromAnotherSlot() {
        ItemStack dirt = new ItemStack(Material.DIRT);
        var token = new NativeCookingGuard.Started(
                new NamespacedKey("recipeweaver", "other_slot"), StationKind.CAMPFIRE,
                dirt, new ItemStack(Material.DIAMOND), 2);
        assertTrue(NativeCookingGuard.unattributedCampfireRequiresDenial(List.of(token), dirt,
                new ItemStack(Material.DIAMOND), List.of(), List.of(), List.of()));
        assertTrue(NativeCookingGuard.unattributedCampfireRequiresDenial(List.of(token),
                new ItemStack(Material.STONE), new ItemStack(Material.DIAMOND),
                List.of(), List.of(), List.of()));
        assertFalse(NativeCookingGuard.unattributedCampfireRequiresDenial(List.of(token),
                new ItemStack(Material.STONE), new ItemStack(Material.EMERALD),
                List.of(), List.of(), List.of()));
    }

    @Test void unslottedOwnedStartDeniesCookWhoseInputChanged() {
        var start = new NativeCookingGuard.Started(
                new NamespacedKey("recipeweaver", "changed_input"), StationKind.CAMPFIRE,
                new ItemStack(Material.DIRT), new ItemStack(Material.DIAMOND), -1);
        ItemStack changed = new ItemStack(Material.STONE);
        assertNull(NativeCookingGuard.uniqueUnslottedCampfireStart(
                List.of(start), changed, new ItemStack(Material.DIAMOND)));
        assertTrue(NativeCookingGuard.unattributedCampfireRequiresDenial(
                List.of(start), changed, new ItemStack(Material.DIAMOND),
                List.of(), List.of(), List.of()));
    }

    @Test void oneUnslottedCampfireStartCanAuthorizeItsMatchingCook() {
        ItemStack dirt = new ItemStack(Material.DIRT);
        ItemStack diamond = new ItemStack(Material.DIAMOND);
        var unslotted = new NativeCookingGuard.Started(
                new NamespacedKey("recipeweaver", "unslotted_start"), StationKind.CAMPFIRE,
                dirt, diamond, -1);
        var other = new NativeCookingGuard.Started(
                NamespacedKey.minecraft("unrelated"), StationKind.CAMPFIRE,
                new ItemStack(Material.STONE), diamond, 2);
        assertSame(unslotted, NativeCookingGuard.uniqueUnslottedCampfireStart(
                List.of(unslotted, other), dirt, diamond));
        assertNull(NativeCookingGuard.uniqueUnslottedCampfireStart(
                List.of(unslotted, other), dirt, new ItemStack(Material.EMERALD)));
    }

    @Test void unslottedCampfireStartCannotBorrowWhenTwoTokensMatch() {
        ItemStack dirt = new ItemStack(Material.DIRT);
        ItemStack diamond = new ItemStack(Material.DIAMOND);
        var unslotted = new NativeCookingGuard.Started(
                new NamespacedKey("recipeweaver", "unslotted_start"), StationKind.CAMPFIRE,
                dirt, diamond, -1);
        var slotted = new NativeCookingGuard.Started(
                NamespacedKey.minecraft("another_slot"), StationKind.CAMPFIRE,
                dirt, diamond, 2);
        assertNull(NativeCookingGuard.uniqueUnslottedCampfireStart(
                List.of(unslotted, slotted), dirt, diamond));
        assertNull(NativeCookingGuard.uniqueUnslottedCampfireStart(
                List.of(slotted), dirt, diamond));
    }

    @Test void unidentifiedCampfireSlotCannotBorrowDisabledVanillaToken() {
        NamespacedKey disabled = NamespacedKey.minecraft("blocked_campfire");
        ItemStack dirt = new ItemStack(Material.DIRT);
        var token = new NativeCookingGuard.Started(disabled, StationKind.CAMPFIRE,
                dirt, new ItemStack(Material.DIAMOND), 3);
        assertTrue(NativeCookingGuard.unattributedCampfireRequiresDenial(List.of(token), dirt,
                new ItemStack(Material.DIAMOND), List.of(), List.of(), List.of(disabled)));
    }

    @Test void unidentifiedCampfireSlotLeavesUnrestrictedVanillaCookAvailable() {
        ItemStack dirt = new ItemStack(Material.DIRT);
        var token = new NativeCookingGuard.Started(NamespacedKey.minecraft("allowed_campfire"),
                StationKind.CAMPFIRE, dirt, new ItemStack(Material.DIAMOND), 1);
        assertFalse(NativeCookingGuard.unattributedCampfireRequiresDenial(List.of(token), dirt,
                new ItemStack(Material.DIAMOND), List.of(), List.of(), List.of()));
    }
}
