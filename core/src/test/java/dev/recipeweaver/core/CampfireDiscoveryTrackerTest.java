package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class CampfireDiscoveryTrackerTest {
    private World world;
    private Block block;
    private Location spawn;
    private NamespacedKey key;

    @BeforeEach void setUp() {
        MockBukkit.mock();
        world = MockBukkit.getMock().addSimpleWorld("campfire-discovery");
        block = world.getBlockAt(10, 64, 20);
        spawn = new Location(world, 10.5, 65, 20.5);
        key = new NamespacedKey("recipeweaver", "campfire_discovery_test");
    }

    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void verifiedSpawnMovesOneUseCookTokenOffThePlayerItem() {
        CampfireDiscoveryTracker tracker = new CampfireDiscoveryTracker();
        ItemStack raw = new ItemStack(Material.DIAMOND);
        ItemStack tagged = tracker.tagCookResult(raw, key, block);
        assertFalse(raw.hasItemMeta());
        assertTrue(tagged.getItemMeta().getPersistentDataContainer()
                .has(CampfireDiscoveryTracker.COOK_TOKEN));

        var first = tracker.claimSpawn(tagged, spawn);
        assertEquals(key, first.recipe());
        assertTrue(raw.isSimilar(first.item()),
                "Removing the cook token must restore the exact recipe result");
        assertFalse(first.item().getItemMeta().getPersistentDataContainer()
                .has(CampfireDiscoveryTracker.COOK_TOKEN));
        var duplicate = tracker.claimSpawn(tagged, spawn);
        assertNull(duplicate.recipe());
        assertFalse(duplicate.item().getItemMeta().getPersistentDataContainer()
                .has(CampfireDiscoveryTracker.COOK_TOKEN));
    }

    @Test void unrelatedChangedAndExpiredDropsCannotBorrowCookProvenance() {
        AtomicLong time = new AtomicLong();
        CampfireDiscoveryTracker tracker = new CampfireDiscoveryTracker(time::get);
        assertNull(tracker.claimSpawn(new ItemStack(Material.DIAMOND), spawn).recipe());

        ItemStack changed = tracker.tagCookResult(new ItemStack(Material.DIAMOND), key, block);
        changed.setType(Material.EMERALD);
        assertNull(tracker.claimSpawn(changed, spawn).recipe());
        assertFalse(tracker.claimSpawn(changed, spawn).item().getItemMeta()
                .getPersistentDataContainer().has(CampfireDiscoveryTracker.COOK_TOKEN));

        ItemStack farAway = tracker.tagCookResult(new ItemStack(Material.DIAMOND), key, block);
        assertNull(tracker.claimSpawn(farAway, new Location(world, 100, 65, 100)).recipe());

        ItemStack cancelledCook = tracker.tagCookResult(new ItemStack(Material.DIAMOND), key, block);
        // A cancelled cook has no spawn. Even a late copy of its event result is rejected.
        time.addAndGet(TimeUnit.SECONDS.toNanos(11));
        assertNull(tracker.claimSpawn(cancelledCook, spawn).recipe());
        assertFalse(tracker.claimSpawn(cancelledCook, spawn).item().getItemMeta()
                .getPersistentDataContainer().has(CampfireDiscoveryTracker.COOK_TOKEN));
    }

    @Test void persistedDropKeyCanBeConsumedAfterTrackerRestartOnlyForCurrentCampfire() {
        CampfireDiscoveryTracker restarted = new CampfireDiscoveryTracker();
        ItemStack output = new ItemStack(Material.DIAMOND);
        StationRecipeDefinition saved = recipe(key, StationKind.CAMPFIRE, true, output);
        CampfireRecipe nativeRecipe = new CampfireRecipe(key, output,
                Material.DIRT, 0, 20);
        PersistentDataContainer entityData = new ItemStack(Material.PAPER)
                .getItemMeta().getPersistentDataContainer();
        entityData.set(CampfireDiscoveryTracker.DROP_RECIPE,
                PersistentDataType.STRING, key.toString());

        assertEquals(key, restarted.consumePickup(entityData, output,
                candidate -> candidate.equals(key) ? saved : null,
                candidate -> candidate.equals(key) ? nativeRecipe : null));
        assertNull(restarted.consumePickup(entityData, output,
                candidate -> saved, candidate -> nativeRecipe));
        assertFalse(entityData.has(CampfireDiscoveryTracker.DROP_RECIPE));
    }

    @Test void markedDropCannotMergeAwayBeforePickup() {
        PersistentDataContainer owned = new ItemStack(Material.PAPER)
                .getItemMeta().getPersistentDataContainer();
        PersistentDataContainer unrelated = new ItemStack(Material.PAPER)
                .getItemMeta().getPersistentDataContainer();
        assertFalse(CampfireDiscoveryTracker.protectsMerge(unrelated, unrelated));
        owned.set(CampfireDiscoveryTracker.DROP_RECIPE,
                PersistentDataType.STRING, key.toString());
        assertTrue(CampfireDiscoveryTracker.protectsMerge(owned, unrelated));
        assertTrue(CampfireDiscoveryTracker.protectsMerge(unrelated, owned));
    }

    @Test void pickupRejectsMalformedRemovedDisabledAndChangedRecipes() {
        CampfireDiscoveryTracker tracker = new CampfireDiscoveryTracker();
        ItemStack output = new ItemStack(Material.DIAMOND);
        StationRecipeDefinition saved = recipe(key, StationKind.CAMPFIRE, true, output);
        CampfireRecipe nativeRecipe = new CampfireRecipe(key, output, Material.DIRT, 0, 20);
        PersistentDataContainer data = new ItemStack(Material.PAPER)
                .getItemMeta().getPersistentDataContainer();
        for (String raw : List.of("not a key", "minecraft:campfire_discovery_test",
                "recipeweaver:missing", key.toString())) {
            data.set(CampfireDiscoveryTracker.DROP_RECIPE, PersistentDataType.STRING, raw);
            assertNull(tracker.consumePickup(data, output,
                    candidate -> candidate.equals(key) ? saved : null,
                    candidate -> null));
            assertFalse(data.has(CampfireDiscoveryTracker.DROP_RECIPE));
        }
        data.set(CampfireDiscoveryTracker.DROP_RECIPE,
                PersistentDataType.STRING, key.toString());
        assertNull(tracker.consumePickup(data, new ItemStack(Material.EMERALD),
                candidate -> saved, candidate -> nativeRecipe));
        data.set(CampfireDiscoveryTracker.DROP_RECIPE,
                PersistentDataType.STRING, key.toString());
        assertNull(tracker.consumePickup(data, output,
                candidate -> recipe(key, StationKind.CAMPFIRE, false, output),
                candidate -> nativeRecipe));
        data.set(CampfireDiscoveryTracker.DROP_RECIPE,
                PersistentDataType.STRING, key.toString());
        assertNull(tracker.consumePickup(data, output,
                candidate -> recipe(key, StationKind.SMOKER, true, output),
                candidate -> nativeRecipe));
    }

    private static StationRecipeDefinition recipe(NamespacedKey key, StationKind kind,
            boolean enabled, ItemStack output) {
        return new StationRecipeDefinition(key, kind, List.of(new ItemStack(Material.DIRT)),
                output, "", 20, 0, 0, enabled, false);
    }
}
