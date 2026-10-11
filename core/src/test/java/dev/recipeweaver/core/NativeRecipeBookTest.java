package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class NativeRecipeBookTest {
    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test
    void concealsOnlyAlreadyDiscoveredHiddenWorkbenchAndStationRecipes() {
        RecipeDefinition hiddenWorkbench = workbench("hidden_workbench", false, true);
        RecipeDefinition visibleWorkbench = workbench("visible_workbench", true, false);
        RecipeDefinition disabledWorkbench = workbench("disabled_workbench", false, false);
        RecipeDefinition unknownHidden = workbench("unknown_hidden", true, true);
        StationRecipeDefinition hiddenStation = station("hidden_station", true);
        StationRecipeDefinition visibleStation = station("visible_station", false);
        Set<NamespacedKey> known = new HashSet<>(Set.of(hiddenWorkbench.key(),
                disabledWorkbench.key(), visibleWorkbench.key(), hiddenStation.key(),
                visibleStation.key()));
        List<NamespacedKey> removed = new ArrayList<>();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getDiscoveredRecipes")) return Set.copyOf(known);
                    if (method.getName().equals("undiscoverRecipes")) {
                        @SuppressWarnings("unchecked")
                        Collection<NamespacedKey> keys = (Collection<NamespacedKey>) arguments[0];
                        removed.addAll(keys);
                        known.removeAll(keys);
                        return keys.size();
                    }
                    return null;
                });

        NativeRecipeBook.concealKnown(player,
                List.of(hiddenWorkbench, disabledWorkbench, visibleWorkbench, unknownHidden),
                List.of(hiddenStation, visibleStation));

        assertEquals(Set.of(hiddenWorkbench.key(), disabledWorkbench.key(), hiddenStation.key()),
                Set.copyOf(removed));
        assertEquals(Set.of(visibleWorkbench.key(), visibleStation.key()), known);
        NativeRecipeBook.concealKnown(player,
                List.of(hiddenWorkbench, disabledWorkbench, visibleWorkbench, unknownHidden),
                List.of(hiddenStation, visibleStation));
        assertEquals(3, removed.size());
    }

    @Test
    void reopensOnlyEarnedVisibleRegisteredNativeRecipes() {
        RecipeDefinition visibleWorkbench = workbench("earned_workbench", true, false);
        RecipeDefinition hiddenWorkbench = workbench("hidden_workbench", true, true);
        RecipeDefinition disabledWorkbench = workbench("disabled_workbench", false, false);
        StationRecipeDefinition visibleStation = station("earned_station", false);
        StationRecipeDefinition unregisteredStation = station("unregistered_station", false);
        Set<NamespacedKey> known = new HashSet<>();
        List<NamespacedKey> revealed = new ArrayList<>();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getDiscoveredRecipes")) return Set.copyOf(known);
                    if (method.getName().equals("discoverRecipes")) {
                        @SuppressWarnings("unchecked")
                        Collection<NamespacedKey> keys = (Collection<NamespacedKey>) arguments[0];
                        revealed.addAll(keys);
                        known.addAll(keys);
                        return keys.size();
                    }
                    return null;
                });
        Set<NamespacedKey> earned = Set.of(visibleWorkbench.key(), hiddenWorkbench.key(),
                disabledWorkbench.key(), visibleStation.key(), unregisteredStation.key());
        var workbench = List.of(visibleWorkbench, hiddenWorkbench, disabledWorkbench);
        var stations = List.of(visibleStation, unregisteredStation);
        NativeRecipeBook.revealKnown(player, workbench, stations,
                earned::contains, key -> !key.equals(unregisteredStation.key()));
        assertEquals(Set.of(visibleWorkbench.key(), visibleStation.key()), Set.copyOf(revealed));
        NativeRecipeBook.revealKnown(player, workbench, stations,
                earned::contains, key -> !key.equals(unregisteredStation.key()));
        assertEquals(2, revealed.size());
        assertTrue(known.containsAll(revealed));
    }

    @Test
    void revealEarnedOnlyShowsCurrentVisibleRegisteredNativeRecipeOnce() {
        RecipeDefinition visibleWorkbench = workbench("crafted_workbench", true, false);
        RecipeDefinition hiddenWorkbench = workbench("crafted_hidden", true, true);
        RecipeDefinition disabledWorkbench = workbench("crafted_disabled", false, false);
        StationRecipeDefinition visibleStation = station("crafted_station", false);
        StationRecipeDefinition hiddenStation = station("crafted_hidden_station", true);
        Set<NamespacedKey> known = new HashSet<>();
        List<NamespacedKey> revealed = new ArrayList<>();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getDiscoveredRecipes")) return Set.copyOf(known);
                    if (method.getName().equals("discoverRecipes")) {
                        @SuppressWarnings("unchecked")
                        Collection<NamespacedKey> keys = (Collection<NamespacedKey>) arguments[0];
                        revealed.addAll(keys);
                        known.addAll(keys);
                        return keys.size();
                    }
                    return null;
                });

        NativeRecipeBook.revealEarned(player, visibleWorkbench.key(), visibleWorkbench,
                null, false, true);
        NativeRecipeBook.revealEarned(player, visibleWorkbench.key(), visibleWorkbench,
                null, false, true);
        NativeRecipeBook.revealEarned(player, visibleStation.key(), null,
                visibleStation, false, true);
        NativeRecipeBook.revealEarned(player, hiddenWorkbench.key(), hiddenWorkbench,
                null, false, true);
        NativeRecipeBook.revealEarned(player, disabledWorkbench.key(), disabledWorkbench,
                null, false, true);
        NativeRecipeBook.revealEarned(player, hiddenStation.key(), null,
                hiddenStation, false, true);
        NativeRecipeBook.revealEarned(player, key("locked"),
                workbench("locked", true, false), null, true, true);
        NativeRecipeBook.revealEarned(player, key("unregistered"),
                workbench("unregistered", true, false), null, false, false);
        NativeRecipeBook.revealEarned(player, key("stale"),
                visibleWorkbench, null, false, true);
        NativeRecipeBook.revealEarned(player, key("custom_grid"),
                null, null, false, true);

        assertEquals(List.of(visibleWorkbench.key(), visibleStation.key()), revealed);
        assertEquals(Set.of(visibleWorkbench.key(), visibleStation.key()), known);
    }

    @Test
    void apiDiscoveryHookQueuesRevealOnlyAfterDurableSuccess() {
        List<Runnable> nextTick = new ArrayList<>();
        List<String> revealed = new ArrayList<>();
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        assertTrue(NativeRecipeBook.afterDurable(pending,
                () -> nextTick.add(() -> revealed.add("earned"))) == pending);
        assertTrue(nextTick.isEmpty());
        pending.complete(true);
        assertEquals(1, nextTick.size());
        assertTrue(revealed.isEmpty());
        nextTick.remove(0).run();
        assertEquals(List.of("earned"), revealed);

        CompletableFuture<Boolean> alreadyEarned = CompletableFuture.completedFuture(false);
        NativeRecipeBook.afterDurable(alreadyEarned,
                () -> nextTick.add(() -> revealed.add("known")));
        assertEquals(1, nextTick.size());
        assertEquals(List.of("earned"), revealed);

        CompletableFuture<Boolean> failed = new CompletableFuture<>();
        NativeRecipeBook.afterDurable(failed,
                () -> nextTick.add(() -> revealed.add("failed")));
        failed.completeExceptionally(new IllegalStateException("disk failed"));
        assertEquals(1, nextTick.size());
        assertTrue(failed.isCompletedExceptionally());
    }

    @Test
    void failedDiscoverySchedulesConcealOnlyForTheFailedWrite() {
        List<Throwable> failures = new ArrayList<>();
        CompletableFuture<Boolean> successful = new CompletableFuture<>();
        NativeRecipeBook.afterFailed(successful, failures::add);
        successful.complete(true);
        NativeRecipeBook.afterFailed(CompletableFuture.completedFuture(false), failures::add);
        assertTrue(failures.isEmpty());

        IllegalStateException diskFailure = new IllegalStateException("discovery save failed");
        CompletableFuture<Boolean> failed = new CompletableFuture<>();
        NativeRecipeBook.afterFailed(failed, failures::add);
        failed.completeExceptionally(diskFailure);
        assertEquals(List.of(diskFailure), failures);
    }

    @Test
    void failedWriteConcealsOnlyWhenTheKeyRemainsUnearned() {
        NamespacedKey key = key("failed_write");
        Set<NamespacedKey> known = new HashSet<>(Set.of(key));
        List<NamespacedKey> concealed = new ArrayList<>();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getDiscoveredRecipes")) return Set.copyOf(known);
                    if (method.getName().equals("undiscoverRecipes")) {
                        @SuppressWarnings("unchecked")
                        Collection<NamespacedKey> keys = (Collection<NamespacedKey>) arguments[0];
                        concealed.addAll(keys);
                        known.removeAll(keys);
                        return keys.size();
                    }
                    return null;
                });

        NativeRecipeBook.concealFailed(player, key, true);
        assertEquals(Set.of(key), known);
        NativeRecipeBook.concealFailed(player, key, false);
        assertEquals(List.of(key), concealed);
        assertTrue(known.isEmpty());
    }

    @Test
    void joinAdoptsOnlyDiscoveredRegisteredOwnedNativeRecipes() {
        RecipeDefinition workbench = workbench("legacy_workbench", true, false);
        StationRecipeDefinition station = station("legacy_station", false);
        StationRecipeDefinition customOnly = station("custom_only", false);
        RecipeDefinition alreadyEarned = workbench("already_earned", true, false);
        NamespacedKey removed = key("removed_recipe");
        NamespacedKey vanilla = NamespacedKey.minecraft("bread");
        Set<NamespacedKey> nativeBook = Set.of(workbench.key(), station.key(),
                customOnly.key(), alreadyEarned.key(), removed, vanilla);
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, arguments) ->
                        method.getName().equals("getDiscoveredRecipes") ? nativeBook : null);

        assertEquals(Set.of(workbench.key(), station.key()),
                NativeRecipeBook.adoptable(player, List.of(workbench, alreadyEarned),
                        List.of(station, customOnly),
                        candidate -> !candidate.equals(customOnly.key()),
                        candidate -> candidate.equals(alreadyEarned.key())));
    }

    @Test
    void reconciliationConcealsNativeBookEntriesMissingDurableDiscovery() {
        RecipeDefinition earned = workbench("durably_earned", true, false);
        StationRecipeDefinition unearned = station("unsaved_station", false);
        NamespacedKey vanilla = NamespacedKey.minecraft("bread");
        Set<NamespacedKey> known = new HashSet<>(Set.of(earned.key(), unearned.key(), vanilla));
        List<NamespacedKey> concealed = new ArrayList<>();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getDiscoveredRecipes")) return Set.copyOf(known);
                    if (method.getName().equals("undiscoverRecipes")) {
                        @SuppressWarnings("unchecked")
                        Collection<NamespacedKey> keys = (Collection<NamespacedKey>) arguments[0];
                        concealed.addAll(keys);
                        known.removeAll(keys);
                        return keys.size();
                    }
                    return null;
                });

        NativeRecipeBook.concealKnown(player, List.of(earned), List.of(unearned), false,
                key -> true, key -> key.equals(earned.key()));
        assertEquals(List.of(unearned.key()), concealed);
        assertEquals(Set.of(earned.key(), vanilla), known);
    }

    @Test
    void hiddenToVisibleTransitionRestoresPreviouslyEarnedNativeBookEntry() {
        NamespacedKey key = key("visibility_transition");
        Set<NamespacedKey> known = new HashSet<>(Set.of(key));
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getDiscoveredRecipes")) return Set.copyOf(known);
                    if (method.getName().equals("undiscoverRecipes")) {
                        @SuppressWarnings("unchecked")
                        Collection<NamespacedKey> keys = (Collection<NamespacedKey>) arguments[0];
                        known.removeAll(keys);
                        return keys.size();
                    }
                    if (method.getName().equals("discoverRecipes")) {
                        @SuppressWarnings("unchecked")
                        Collection<NamespacedKey> keys = (Collection<NamespacedKey>) arguments[0];
                        known.addAll(keys);
                        return keys.size();
                    }
                    return null;
                });
        NativeRecipeBook.concealKnown(player,
                List.of(workbench("visibility_transition", true, true)), List.of());
        assertTrue(known.isEmpty());
        NativeRecipeBook.revealKnown(player,
                List.of(workbench("visibility_transition", true, false)), List.of(),
                key::equals, key::equals);
        assertEquals(Set.of(key), known);
    }

    @Test
    void removingNativeRecipeClearsOnlyItsVanillaBookEntryAndReaddCanRestoreIt() {
        NamespacedKey removed = key("removed_then_readded");
        NamespacedKey untouched = key("unrelated");
        Set<NamespacedKey> known = new HashSet<>(Set.of(removed, untouched));
        List<NamespacedKey> concealed = new ArrayList<>();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getDiscoveredRecipes")) return Set.copyOf(known);
                    if (method.getName().equals("undiscoverRecipes")) {
                        @SuppressWarnings("unchecked")
                        Collection<NamespacedKey> keys = (Collection<NamespacedKey>) arguments[0];
                        concealed.addAll(keys);
                        known.removeAll(keys);
                        return keys.size();
                    }
                    if (method.getName().equals("discoverRecipes")) {
                        @SuppressWarnings("unchecked")
                        Collection<NamespacedKey> keys = (Collection<NamespacedKey>) arguments[0];
                        known.addAll(keys);
                        return keys.size();
                    }
                    return null;
                });
        NativeRecipeBook.concealRemoved(player, removed);
        NativeRecipeBook.concealRemoved(player, removed);
        assertEquals(List.of(removed), concealed);
        assertEquals(Set.of(untouched), known);
        NativeRecipeBook.revealKnown(player,
                List.of(workbench("removed_then_readded", true, false)), List.of(),
                removed::equals, removed::equals);
        assertEquals(Set.of(removed, untouched), known);
    }

    @Test
    void reconnectConcealsRecipesDeletedWhileOfflineWithoutTouchingCurrentOrVanillaEntries() {
        NamespacedKey deletedWorkbench = key("deleted_while_offline");
        NamespacedKey staleGrid = key("stale_custom_grid");
        NamespacedKey currentWorkbench = key("current_workbench");
        NamespacedKey currentStation = key("current_station");
        NamespacedKey vanilla = NamespacedKey.minecraft("bread");
        Set<NamespacedKey> known = new HashSet<>(Set.of(deletedWorkbench, staleGrid,
                currentWorkbench, currentStation, vanilla));
        List<NamespacedKey> concealed = new ArrayList<>();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getDiscoveredRecipes")) return Set.copyOf(known);
                    if (method.getName().equals("undiscoverRecipes")) {
                        @SuppressWarnings("unchecked")
                        Collection<NamespacedKey> keys = (Collection<NamespacedKey>) arguments[0];
                        concealed.addAll(keys);
                        known.removeAll(keys);
                        return keys.size();
                    }
                    if (method.getName().equals("discoverRecipes")) {
                        @SuppressWarnings("unchecked")
                        Collection<NamespacedKey> keys = (Collection<NamespacedKey>) arguments[0];
                        known.addAll(keys);
                        return keys.size();
                    }
                    return null;
                });
        List<RecipeDefinition> workbench = List.of(workbench("current_workbench", true, false));
        List<StationRecipeDefinition> stations = List.of(station("current_station", false));

        NativeRecipeBook.concealOrphans(player, workbench, stations);
        assertEquals(Set.of(deletedWorkbench, staleGrid), Set.copyOf(concealed));
        assertEquals(Set.of(currentWorkbench, currentStation, vanilla), known);
        NativeRecipeBook.concealOrphans(player, workbench, stations);
        assertEquals(2, concealed.size());

        NativeRecipeBook.revealKnown(player,
                List.of(workbench("deleted_while_offline", true, false)), List.of(),
                deletedWorkbench::equals, deletedWorkbench::equals);
        assertTrue(known.contains(deletedWorkbench));
        assertTrue(known.contains(vanilla));
    }

    @Test
    void nativeToCustomStationChangeConcealsOldBookEntryButKeepsEarnedDiscovery() {
        NamespacedKey changed = key("changed_station_kind");
        StationRecipeDefinition nativeRecipe = station("changed_station_kind", false);
        StationRecipeDefinition customRecipe = new StationRecipeDefinition(changed,
                StationKind.ANVIL,
                List.of(new ItemStack(Material.STONE), new ItemStack(Material.IRON_INGOT)),
                new ItemStack(Material.DIAMOND), "", 100, 0, 1, true, false);
        Set<NamespacedKey> known = new HashSet<>(Set.of(changed));
        List<NamespacedKey> concealed = new ArrayList<>();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getDiscoveredRecipes")) return Set.copyOf(known);
                    if (method.getName().equals("undiscoverRecipes")) {
                        @SuppressWarnings("unchecked")
                        Collection<NamespacedKey> keys = (Collection<NamespacedKey>) arguments[0];
                        concealed.addAll(keys);
                        known.removeAll(keys);
                        return keys.size();
                    }
                    if (method.getName().equals("discoverRecipes")) {
                        @SuppressWarnings("unchecked")
                        Collection<NamespacedKey> keys = (Collection<NamespacedKey>) arguments[0];
                        known.addAll(keys);
                        return keys.size();
                    }
                    return null;
                });

        NativeRecipeBook.concealOrphans(player, List.of(), List.of(customRecipe));
        assertEquals(Set.of(changed), known);
        NativeRecipeBook.concealKnown(player, List.of(), List.of(customRecipe), false,
                key -> false);
        assertEquals(List.of(changed), concealed);
        assertTrue(known.isEmpty());
        NativeRecipeBook.revealKnown(player, List.of(), List.of(customRecipe), changed::equals,
                key -> false);
        assertTrue(known.isEmpty());
        NativeRecipeBook.revealKnown(player, List.of(), List.of(nativeRecipe), changed::equals,
                key -> true);
        assertEquals(Set.of(changed), known);
    }

    @Test
    void discoveryPacketsRespectHiddenDisabledLockdownAndVanillaExclusions() {
        RecipeDefinition visible = workbench("visible_packet", true, false);
        RecipeDefinition hidden = workbench("hidden_packet", true, true);
        RecipeDefinition disabled = workbench("disabled_packet", false, false);
        StationRecipeDefinition visibleStation = station("visible_station_packet", false);
        StationRecipeDefinition hiddenStation = station("hidden_station_packet", true);
        assertFalse(NativeRecipeBook.blocksDiscovery(visible.key(), visible, null, false, false));
        assertTrue(NativeRecipeBook.blocksDiscovery(hidden.key(), hidden, null, false, false));
        assertTrue(NativeRecipeBook.blocksDiscovery(disabled.key(), disabled, null, false, false));
        assertTrue(NativeRecipeBook.blocksDiscovery(visible.key(), visible, null, true, false));
        assertFalse(NativeRecipeBook.blocksDiscovery(
                visibleStation.key(), null, visibleStation, false, false));
        assertTrue(NativeRecipeBook.blocksDiscovery(
                hiddenStation.key(), null, hiddenStation, false, false));
        assertTrue(NativeRecipeBook.blocksDiscovery(key("unknown"), null, null, false, false));
        NamespacedKey vanilla = NamespacedKey.minecraft("oak_planks");
        assertFalse(NativeRecipeBook.blocksDiscovery(vanilla, null, null, true, false));
        assertTrue(NativeRecipeBook.blocksDiscovery(vanilla, null, null, false, true));
    }

    @Test
    void earnedCustomOnlyStationCannotReenterTheNativeBook() {
        StationRecipeDefinition customOnly = new StationRecipeDefinition(
                key("custom_only_unlock"), StationKind.ANVIL,
                List.of(new ItemStack(Material.STONE), new ItemStack(Material.IRON_INGOT)),
                new ItemStack(Material.DIAMOND), "", 100, 0, 1, true, false);
        assertFalse(NativeRecipeBook.defersDiscovery(customOnly.key(), null, customOnly,
                false, false, true));
        assertTrue(NativeRecipeBook.blocksUnregisteredNativeKey(customOnly.key(), false));
        assertFalse(NativeRecipeBook.blocksUnregisteredNativeKey(customOnly.key(), true));
        assertFalse(NativeRecipeBook.blocksUnregisteredNativeKey(
                NamespacedKey.minecraft("bread"), false));
    }

    @Test
    void visibleNativeUnlockWaitsForDurabilityAndEarnedRevealPassesThrough() {
        RecipeDefinition visible = workbench("deferred_workbench", true, false);
        StationRecipeDefinition station = station("deferred_station", false);
        RecipeDefinition hidden = workbench("deferred_hidden", true, true);
        assertTrue(NativeRecipeBook.defersDiscovery(visible.key(), visible, null,
                false, false, false));
        assertTrue(NativeRecipeBook.defersDiscovery(station.key(), null, station,
                false, false, false));
        assertFalse(NativeRecipeBook.defersDiscovery(visible.key(), visible, null,
                false, false, true));
        assertFalse(NativeRecipeBook.defersDiscovery(hidden.key(), hidden, null,
                false, false, false));
        assertFalse(NativeRecipeBook.defersDiscovery(visible.key(), visible, null,
                true, false, false));
        assertFalse(NativeRecipeBook.defersDiscovery(visible.key(), visible, null,
                false, true, false));
        assertFalse(NativeRecipeBook.defersDiscovery(key("unknown"), null, null,
                false, false, false));
        assertFalse(NativeRecipeBook.defersDiscovery(NamespacedKey.minecraft("bread"),
                null, null, false, false, false));
    }

    @Test
    void lockdownConcealsVisibleNativeEntriesAndUnlockRestoresEarnedOnes() {
        RecipeDefinition visible = workbench("lockdown_workbench", true, false);
        StationRecipeDefinition station = station("lockdown_station", false);
        Set<NamespacedKey> known = new HashSet<>(Set.of(visible.key(), station.key()));
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getDiscoveredRecipes")) return Set.copyOf(known);
                    if (method.getName().equals("undiscoverRecipes")) {
                        @SuppressWarnings("unchecked")
                        Collection<NamespacedKey> keys = (Collection<NamespacedKey>) arguments[0];
                        known.removeAll(keys);
                        return keys.size();
                    }
                    if (method.getName().equals("discoverRecipes")) {
                        @SuppressWarnings("unchecked")
                        Collection<NamespacedKey> keys = (Collection<NamespacedKey>) arguments[0];
                        known.addAll(keys);
                        return keys.size();
                    }
                    return null;
                });
        NativeRecipeBook.concealKnown(player, List.of(visible), List.of(station), true);
        assertTrue(known.isEmpty());
        NativeRecipeBook.revealKnown(player, List.of(visible), List.of(station),
                key -> true, key -> true);
        assertEquals(Set.of(visible.key(), station.key()), known);
    }

    private static RecipeDefinition workbench(String name, boolean enabled, boolean hidden) {
        List<ItemStack> ingredients = new ArrayList<>(java.util.Collections.nCopies(9, null));
        ingredients.set(0, new ItemStack(Material.STONE));
        return new RecipeDefinition(key(name), RecipeKind.SHAPELESS, ingredients,
                new ItemStack(Material.DIAMOND), "", enabled, hidden);
    }

    private static StationRecipeDefinition station(String name, boolean hidden) {
        return new StationRecipeDefinition(key(name), StationKind.FURNACE,
                List.of(new ItemStack(Material.STONE)), new ItemStack(Material.DIAMOND),
                "", 100, 0, 1, true, hidden);
    }

    private static NamespacedKey key(String name) { return new NamespacedKey("recipeweaver", name); }
}
