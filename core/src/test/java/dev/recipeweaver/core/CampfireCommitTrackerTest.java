package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.*;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.StationCraftBatch;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import java.util.List;
import java.util.UUID;
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

final class CampfireCommitTrackerTest {
    private final NamespacedKey key = new NamespacedKey("recipeweaver", "postcommit_campfire");
    private World world;
    private Block block;
    private Location spawn;

    @BeforeEach void setUp() {
        MockBukkit.mock();
        world = MockBukkit.getMock().addSimpleWorld("campfire-commit");
        block = world.getBlockAt(8, 70, 12);
        spawn = new Location(world, 8.5, 71, 12.5);
    }

    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void acceptedSpawnAndClearedSlotPublishOnceWithoutItemToken() {
        CampfireDiscoveryTracker discovery = new CampfireDiscoveryTracker();
        ItemStack tagged = discovery.tagCookResult(
                new ItemStack(Material.DIAMOND), key, block, 2);
        CampfireDiscoveryTracker.SpawnClaim claim = discovery.claimSpawn(tagged, spawn);
        assertEquals(key, claim.recipe());
        assertEquals(2, claim.origin().slot());
        assertFalse(claim.item().getItemMeta().getPersistentDataContainer()
                .has(CampfireDiscoveryTracker.COOK_TOKEN));

        CampfireCommitTracker tracker = new CampfireCommitTracker();
        CampfireCommitTracker.Pending pending = tracker.begin(UUID.randomUUID(), claim);
        StationCraftBatch batch = tracker.complete(pending, true, true,
                claim.item(), key.toString(), true);
        assertNotNull(batch);
        assertEquals(StationKind.CAMPFIRE, batch.kind());
        assertEquals(key, batch.recipe());
        assertNull(tracker.complete(pending, true, true,
                claim.item(), key.toString(), true));
        assertNull(discovery.claimSpawn(tagged, spawn).recipe(),
                "The cook token cannot create a second drop claim");
    }

    @Test void cancelledSpawnChangedDropAndUnclearedSlotNeverPublish() {
        CampfireCommitTracker tracker = new CampfireCommitTracker();
        CampfireDiscoveryTracker.SpawnClaim claim = claim();
        assertNull(tracker.complete(tracker.begin(UUID.randomUUID(), claim), false, true,
                claim.item(), key.toString(), true));
        assertNull(tracker.complete(tracker.begin(UUID.randomUUID(), claim), true, true,
                new ItemStack(Material.EMERALD), key.toString(), true));
        assertNull(tracker.complete(tracker.begin(UUID.randomUUID(), claim), true, true,
                claim.item(), key.toString(), false));
        assertNull(tracker.complete(tracker.begin(UUID.randomUUID(), claim), true, false,
                claim.item(), key.toString(), true));
    }

    @Test void mergeAndPickupCannotDuplicateOrLeakPostcommitClaim() {
        CampfireDiscoveryTracker discovery = new CampfireDiscoveryTracker();
        CampfireDiscoveryTracker.SpawnClaim claim = discovery.claimSpawn(
                discovery.tagCookResult(new ItemStack(Material.DIAMOND), key, block, 0), spawn);
        PersistentDataContainer dropData = new ItemStack(Material.PAPER)
                .getItemMeta().getPersistentDataContainer();
        PersistentDataContainer otherData = new ItemStack(Material.PAPER)
                .getItemMeta().getPersistentDataContainer();
        dropData.set(CampfireDiscoveryTracker.DROP_RECIPE,
                PersistentDataType.STRING, key.toString());
        assertTrue(CampfireDiscoveryTracker.protectsMerge(dropData, otherData));
        assertTrue(CampfireDiscoveryTracker.protectsMerge(otherData, dropData));

        CampfireCommitTracker tracker = new CampfireCommitTracker();
        UUID entity = UUID.randomUUID();
        CampfireCommitTracker.Pending pending = tracker.begin(entity, claim);
        assertNull(tracker.begin(entity, claim), "Duplicate spawn claims invalidate attribution");
        assertNull(tracker.complete(pending, true, true,
                claim.item(), key.toString(), true));

        CampfireCommitTracker.Pending pickedUp = tracker.begin(UUID.randomUUID(), claim);
        StationRecipeDefinition saved = new StationRecipeDefinition(key, StationKind.CAMPFIRE,
                List.of(new ItemStack(Material.DIRT)), new ItemStack(Material.DIAMOND),
                "", 20, 0, 0, true, false);
        CampfireRecipe registered = new CampfireRecipe(key,
                new ItemStack(Material.DIAMOND), Material.DIRT, 0, 20);
        assertEquals(key, discovery.consumePickup(dropData, claim.item(),
                candidate -> saved, candidate -> registered));
        assertFalse(dropData.has(CampfireDiscoveryTracker.DROP_RECIPE));
        assertNull(tracker.complete(pickedUp, true, false,
                claim.item(), null, true),
                "A pickup before verification still discovers but cannot prove a live drop");
    }

    @Test void breakingCampfireInvalidatesPendingDropAndUnspawnedCookToken() {
        CampfireDiscoveryTracker discovery = new CampfireDiscoveryTracker();
        ItemStack tagged = discovery.tagCookResult(
                new ItemStack(Material.DIAMOND), key, block, 0);
        CampfireDiscoveryTracker.SpawnClaim claim = discovery.claimSpawn(tagged, spawn);
        CampfireCommitTracker tracker = new CampfireCommitTracker();
        CampfireCommitTracker.Pending pending = tracker.begin(UUID.randomUUID(), claim);
        tracker.invalidateOrigin(world.getUID(), block.getX(), block.getY(), block.getZ());
        assertNull(tracker.complete(pending, true, true,
                claim.item(), key.toString(), true));

        ItemStack notSpawned = discovery.tagCookResult(
                new ItemStack(Material.DIAMOND), key, block, 1);
        discovery.invalidateOrigin(world.getUID(), block.getX(), block.getY(), block.getZ());
        assertNull(discovery.claimSpawn(notSpawned, spawn).recipe());
    }

    private CampfireDiscoveryTracker.SpawnClaim claim() {
        CampfireDiscoveryTracker discovery = new CampfireDiscoveryTracker();
        return discovery.claimSpawn(discovery.tagCookResult(
                new ItemStack(Material.DIAMOND), key, block, 0), spawn);
    }
}
