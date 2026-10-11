package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.StationCraftBatch;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import dev.recipeweaver.api.WeightedResult;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class BrewingOutcomeTest {
    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void matchingBottleSlotsCreateSeparateBatchesAndChangedEventOutputIsExcluded() {
        StationRecipeDefinition weighted = StationRecipeDefinition.withResults(
                key("weighted"), StationKind.BREWING,
                List.of(new ItemStack(Material.NETHER_WART), new ItemStack(Material.POTION)),
                List.of(new WeightedResult(new ItemStack(Material.POTION), 1),
                        new WeightedResult(new ItemStack(Material.SPLASH_POTION), 2)),
                "", 20, 0, 0, true, false);
        StationRecipeDefinition other = new StationRecipeDefinition(key("other"),
                StationKind.BREWING,
                List.of(new ItemStack(Material.NETHER_WART),
                        new ItemStack(Material.SPLASH_POTION)),
                new ItemStack(Material.LINGERING_POTION), "", 20, 0, 0, true, false);
        ItemStack first = weighted.resultForTicket(0);
        ItemStack second = weighted.resultForTicket(2);
        ItemStack third = other.result();
        BrewingListener.BrewOutcome bothBottles = new BrewingListener.BrewOutcome(
                new StationRecipeDefinition[] {weighted, weighted, null},
                new ItemStack[] {first, second, null});
        var separateBatches = bothBottles.takeCommitted(
                new ItemStack[] {first, second, null});
        assertEquals(List.of(0, 1), separateBatches.stream()
                .map(BrewingListener.CompletedSlot::index).toList());
        assertEquals(List.of(weighted.key(), weighted.key()), separateBatches.stream()
                .map(slot -> slot.batch().recipe()).toList());
        assertTrue(separateBatches.get(0).batch() != separateBatches.get(1).batch());
        assertTrue(bothBottles.takeCommitted(new ItemStack[] {first, second, null}).isEmpty());
        BrewingListener.BrewOutcome outcome = new BrewingListener.BrewOutcome(
                new StationRecipeDefinition[] {weighted, weighted, other},
                new ItemStack[] {first, second, third});
        outcome.retainEventResults(List.of(first, new ItemStack(Material.GLASS_BOTTLE), third));
        var committed = outcome.takeCommitted(new ItemStack[] {first, second, third});
        assertEquals(List.of(0, 2), committed.stream()
                .map(BrewingListener.CompletedSlot::index).toList());
        assertEquals(List.of(weighted.key(), other.key()), committed.stream()
                .map(slot -> slot.batch().recipe()).toList());
        assertTrue(committed.stream().allMatch(slot -> slot.batch().kind() == StationKind.BREWING));
        assertTrue(outcome.takeCommitted(new ItemStack[] {first, second, third}).isEmpty());
    }

    @Test void missingLiveResultCannotPublishLaterOrDuplicate() {
        StationRecipeDefinition recipe = new StationRecipeDefinition(key("missing"),
                StationKind.BREWING,
                List.of(new ItemStack(Material.NETHER_WART), new ItemStack(Material.POTION)),
                new ItemStack(Material.SPLASH_POTION), "", 20, 0, 0, true, false);
        ItemStack expected = recipe.result();
        BrewingListener.BrewOutcome outcome = new BrewingListener.BrewOutcome(
                new StationRecipeDefinition[] {recipe, null, null},
                new ItemStack[] {expected, null, null});
        assertTrue(outcome.takeCommitted(new ItemStack[] {new ItemStack(Material.POTION),
                null, null}).isEmpty());
        assertTrue(outcome.takeCommitted(new ItemStack[] {expected, null, null}).isEmpty());
    }

    @Test void emptyPotionEffectsAreEquivalentButRealMetadataChangesRemainDistinct() {
        Map<String, Object> expectedFields = Map.of(
                "meta-type", "POTION", "display-name", "Native brew");
        Map<String, Object> appliedFields = new LinkedHashMap<>(expectedFields);
        appliedFields.put("custom-effects", List.of());
        assertEquals(BrewingListener.normalizedPotionMeta(expectedFields),
                BrewingListener.normalizedPotionMeta(appliedFields));
        appliedFields.put("custom-effects", List.of("real effect"));
        assertNotEquals(BrewingListener.normalizedPotionMeta(expectedFields),
                BrewingListener.normalizedPotionMeta(appliedFields));

        StationRecipeDefinition recipe = new StationRecipeDefinition(key("named"),
                StationKind.BREWING,
                List.of(new ItemStack(Material.NETHER_WART),
                        new ItemStack(Material.SPLASH_POTION)),
                new ItemStack(Material.LINGERING_POTION), "", 20, 0, 0, true, false);
        ItemStack expected = new ItemStack(Material.LINGERING_POTION);
        ItemMeta named = expected.getItemMeta();
        named.setDisplayName("Native brew");
        expected.setItemMeta(named);
        ItemStack changed = expected.clone();
        ItemMeta changedName = changed.getItemMeta();
        changedName.setDisplayName("Different brew");
        changed.setItemMeta(changedName);
        BrewingListener.BrewOutcome outcome = new BrewingListener.BrewOutcome(
                new StationRecipeDefinition[] {recipe, null, null},
                new ItemStack[] {expected, null, null});
        assertTrue(outcome.takeCommitted(new ItemStack[] {changed, null, null}).isEmpty());
    }

    @Test void deniedNativeBottleIsClaimedWithoutBlockingUnrelatedVanillaBrews() {
        ItemStack ingredient = new ItemStack(Material.NETHER_WART);
        ItemStack bottle = new ItemStack(Material.POTION);
        StationRecipeDefinition denied = new StationRecipeDefinition(key("denied_native"),
                StationKind.BREWING, List.of(ingredient, bottle),
                new ItemStack(Material.SPLASH_POTION), "", 20, 0, 0, true, false);
        StationRecipeDefinition eligible = new StationRecipeDefinition(key("eligible_native"),
                StationKind.BREWING, List.of(ingredient, bottle),
                new ItemStack(Material.LINGERING_POTION), "", 20, 0, 0, true, false);

        BrewingListener.BrewMatch vetoed = BrewingListener.resolve(List.of(denied),
                ingredient, bottle, recipe -> false);
        assertTrue(vetoed.claimedInputs());
        assertNull(vetoed.recipe());
        assertTrue(BrewingListener.deniesNativeBottle(vetoed, 0, 3));
        BrewingListener.BrewMatch unrelated = BrewingListener.resolve(List.of(denied),
                ingredient, new ItemStack(Material.GLASS_BOTTLE), recipe -> false);
        assertFalse(unrelated.claimedInputs());
        assertNull(unrelated.recipe());
        assertFalse(BrewingListener.deniesNativeBottle(unrelated, 0, 3));
        BrewingListener.BrewMatch noBottle = BrewingListener.resolve(List.of(denied),
                ingredient, null, recipe -> false);
        assertFalse(noBottle.claimedInputs());

        BrewingListener.BrewMatch laterAllowed = BrewingListener.resolve(
                List.of(denied, eligible), ingredient, bottle, recipe -> recipe == eligible);
        assertTrue(laterAllowed.claimedInputs());
        assertSame(eligible, laterAllowed.recipe());
        assertFalse(BrewingListener.deniesNativeBottle(laterAllowed, 0, 3));
        assertTrue(BrewingListener.deniesNativeBottle(laterAllowed, 3, 3));
    }

    @Test void pendingDiscoverySurvivesAnUnchangedBottleAndNeighborSlotEdit() {
        ItemStack first = new ItemStack(Material.SPLASH_POTION);
        ItemStack second = new ItemStack(Material.LINGERING_POTION);
        BrewingListener.CompletedBrew pending = BrewingListener.CompletedBrew.merged(null,
                List.of(completed(0, key("first_pending"), first),
                        completed(1, key("second_pending"), second)),
                new ItemStack[] {first, second, null});

        pending.forget(1);
        assertTrue(pending.hasPending());
        assertNull(pending.take(1, second));
        assertEquals(key("first_pending"), pending.take(0, first));
        assertNull(pending.take(0, first));
        assertFalse(pending.hasPending());
    }

    @Test void laterBrewMergesUnchangedBottleButCannotAwardChangedOrRepeatedResults() {
        ItemStack earlier = new ItemStack(Material.SPLASH_POTION);
        ItemStack later = new ItemStack(Material.LINGERING_POTION);
        BrewingListener.CompletedBrew previous = BrewingListener.CompletedBrew.merged(null,
                List.of(completed(0, key("earlier"), earlier)),
                new ItemStack[] {earlier, null, null});
        BrewingListener.CompletedBrew retained = BrewingListener.CompletedBrew.merged(previous,
                List.of(completed(1, key("later"), later)),
                new ItemStack[] {earlier, later, null});
        assertEquals(key("earlier"), retained.take(0, earlier));
        assertEquals(key("later"), retained.take(1, later));
        assertNull(retained.take(0, earlier));
        assertNull(retained.take(1, later));

        BrewingListener.CompletedBrew changed = BrewingListener.CompletedBrew.merged(previous,
                List.of(completed(1, key("later"), later)),
                new ItemStack[] {new ItemStack(Material.POTION), later, null});
        assertNull(changed.take(0, new ItemStack(Material.POTION)));
        assertEquals(key("later"), changed.take(1, later));
    }

    private static BrewingListener.CompletedSlot completed(int slot, NamespacedKey key,
                                                           ItemStack result) {
        return new BrewingListener.CompletedSlot(slot,
                new StationCraftBatch(key, StationKind.BREWING, result));
    }

    private static NamespacedKey key(String name) {
        return new NamespacedKey("recipeweaver", "brewing_outcome_" + name);
    }
}
