package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.*;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.StationCraftBatch;
import dev.recipeweaver.api.StationKind;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class FurnaceCommitTrackerTest {
    private static final NamespacedKey KEY = new NamespacedKey("recipeweaver", "committed_cook");
    private static final FurnaceCommitTracker.Position POSITION =
            new FurnaceCommitTracker.Position(UUID.fromString(
                    "ad176722-0bc8-4c2a-b6ec-82b081f580de"), 12, 70, 20);

    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void allFurnaceKindsPublishExactlyOnceAfterOneSourceAndResultChange() {
        for (StationKind kind : new StationKind[] {
                StationKind.FURNACE, StationKind.BLAST_FURNACE, StationKind.SMOKER}) {
            FurnaceCommitTracker tracker = new FurnaceCommitTracker();
            ItemStack source = new ItemStack(Material.DIRT, 2);
            ItemStack previousResult = new ItemStack(Material.DIAMOND, 3);
            FurnaceCommitTracker.Pending pending = tracker.begin(POSITION, KEY, kind,
                    source, previousResult, new ItemStack(Material.DIRT),
                    new ItemStack(Material.DIAMOND));
            assertNotNull(pending);
            source.setAmount(64);
            previousResult.setAmount(64);
            StationCraftBatch batch = tracker.complete(pending, true,
                    new ItemStack(Material.DIRT), new ItemStack(Material.DIAMOND, 4));
            assertNotNull(batch);
            assertEquals(KEY, batch.recipe());
            assertEquals(kind, batch.kind());
            assertEquals(1, batch.result().getAmount());
            assertNull(tracker.complete(pending, true,
                    new ItemStack(Material.DIRT), new ItemStack(Material.DIAMOND, 4)));
        }
    }

    @Test void cancellationAndUnchangedInventoriesCannotPublish() {
        FurnaceCommitTracker tracker = new FurnaceCommitTracker();
        FurnaceCommitTracker.Pending cancelled = begin(tracker, null);
        assertNull(tracker.complete(cancelled, false, null, new ItemStack(Material.DIAMOND)));
        FurnaceCommitTracker.Pending unchanged = begin(tracker, null);
        assertNull(tracker.complete(unchanged, true, new ItemStack(Material.DIRT), null));
        FurnaceCommitTracker.Pending sourceOnly = begin(tracker, null);
        assertNull(tracker.complete(sourceOnly, true, null, null));
        FurnaceCommitTracker.Pending resultOnly = begin(tracker, null);
        assertNull(tracker.complete(resultOnly, true, new ItemStack(Material.DIRT),
                new ItemStack(Material.DIAMOND)));
    }

    @Test void hopperRemovalOrReplacementCannotPretendTheResultWasInserted() {
        FurnaceCommitTracker tracker = new FurnaceCommitTracker();
        FurnaceCommitTracker.Pending removed = begin(tracker, new ItemStack(Material.DIAMOND, 3));
        assertNull(tracker.complete(removed, true, null, new ItemStack(Material.DIAMOND, 3)));
        FurnaceCommitTracker.Pending swapped = begin(tracker, new ItemStack(Material.DIAMOND, 3));
        assertNull(tracker.complete(swapped, true, null, new ItemStack(Material.EMERALD, 4)));
    }

    @Test void secondCookBeforeTheFirstPostStateInvalidatesBothClaims() {
        FurnaceCommitTracker tracker = new FurnaceCommitTracker();
        FurnaceCommitTracker.Pending first = begin(tracker, null);
        assertNull(begin(tracker, null));
        assertNull(tracker.complete(first, true, null, new ItemStack(Material.DIAMOND)));
        // Once the ambiguous tick is retired, a later cook can prove itself.
        FurnaceCommitTracker.Pending later = begin(tracker, null);
        assertNotNull(tracker.complete(later, true, null, new ItemStack(Material.DIAMOND)));
    }

    @Test void unsupportedCampfireAndConflictingOutputCannotCreateClaims() {
        FurnaceCommitTracker tracker = new FurnaceCommitTracker();
        assertNull(tracker.begin(POSITION, KEY, StationKind.CAMPFIRE,
                new ItemStack(Material.DIRT), null, new ItemStack(Material.DIRT),
                new ItemStack(Material.DIAMOND)));
        assertNull(begin(tracker, new ItemStack(Material.EMERALD)));
        assertNull(tracker.begin(POSITION, KEY, StationKind.FURNACE,
                new ItemStack(Material.STONE), null, new ItemStack(Material.DIRT),
                new ItemStack(Material.DIAMOND)));
    }

    @Test void breakingCookingBlockInvalidatesPendingFurnaceCompletion() {
        FurnaceCommitTracker tracker = new FurnaceCommitTracker();
        FurnaceCommitTracker.Pending pending = begin(tracker, null);
        assertNotNull(pending);
        tracker.invalidate(POSITION);
        assertNull(tracker.complete(pending, true, null,
                new ItemStack(Material.DIAMOND)));
        assertNotNull(begin(tracker, null),
                "A replacement furnace may start a fresh independent cook");
    }

    private static FurnaceCommitTracker.Pending begin(FurnaceCommitTracker tracker,
            ItemStack previousResult) {
        return tracker.begin(POSITION, KEY, StationKind.FURNACE,
                new ItemStack(Material.DIRT), previousResult,
                new ItemStack(Material.DIRT), new ItemStack(Material.DIAMOND));
    }
}
