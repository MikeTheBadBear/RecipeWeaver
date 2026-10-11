package dev.recipeweaver.core;

import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.StationRecipeDefinition;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

/** Keeps hidden native recipes out of a player's vanilla recipe book. */
final class NativeRecipeBook {
    private NativeRecipeBook() { }

    /** An earned custom-only key must not bypass the native unlock guard. */
    static boolean blocksUnregisteredNativeKey(NamespacedKey key, boolean registered) {
        return key.getNamespace().equals("recipeweaver") && !registered;
    }

    /** Rejects discovery packets for excluded, unavailable, or unknown RecipeWeaver recipes. */
    static boolean blocksDiscovery(NamespacedKey key, RecipeDefinition workbench,
                                   StationRecipeDefinition station, boolean lockedDown,
                                   boolean excluded) {
        if (excluded) return true;
        if (!key.getNamespace().equals("recipeweaver")) return false;
        if (workbench != null) return lockedDown || !workbench.enabled() || workbench.hidden();
        if (station != null) return lockedDown || !station.enabled() || station.hidden();
        return true;
    }

    /** New native unlocks wait for RecipeWeaver's durable discovery write. */
    static boolean defersDiscovery(NamespacedKey key, RecipeDefinition workbench,
                                   StationRecipeDefinition station, boolean lockedDown,
                                   boolean excluded, boolean earned) {
        return key.getNamespace().equals("recipeweaver") && !earned
                && !blocksDiscovery(key, workbench, station, lockedDown, excluded);
    }

    static void concealKnown(Player player, Collection<RecipeDefinition> workbench,
                             Collection<StationRecipeDefinition> stations) {
        concealKnown(player, workbench, stations, false);
    }

    static void concealKnown(Player player, Collection<RecipeDefinition> workbench,
                             Collection<StationRecipeDefinition> stations, boolean lockedDown) {
        concealKnown(player, workbench, stations, lockedDown, key -> true);
    }

    /** Removes entries for recipes that are now custom-only or failed native registration. */
    static void concealKnown(Player player, Collection<RecipeDefinition> workbench,
                             Collection<StationRecipeDefinition> stations, boolean lockedDown,
                             Predicate<NamespacedKey> registered) {
        concealKnown(player, workbench, stations, lockedDown, registered, key -> true);
    }

    /** Only durable RecipeWeaver discoveries may remain in the vanilla book. */
    static void concealKnown(Player player, Collection<RecipeDefinition> workbench,
                             Collection<StationRecipeDefinition> stations, boolean lockedDown,
                             Predicate<NamespacedKey> registered,
                             Predicate<NamespacedKey> earned) {
        Set<NamespacedKey> known = player.getDiscoveredRecipes();
        Set<NamespacedKey> hidden = new LinkedHashSet<>();
        for (RecipeDefinition recipe : workbench) {
            if (known.contains(recipe.key()) && (lockedDown || !recipe.enabled()
                    || recipe.hidden() || !registered.test(recipe.key())
                    || !earned.test(recipe.key()))) {
                hidden.add(recipe.key());
            }
        }
        for (StationRecipeDefinition recipe : stations) {
            if (known.contains(recipe.key()) && (lockedDown || !recipe.enabled()
                    || recipe.hidden() || !registered.test(recipe.key())
                    || !earned.test(recipe.key()))) {
                hidden.add(recipe.key());
            }
        }
        if (!hidden.isEmpty()) player.undiscoverRecipes(hidden);
    }

    /** Imports pre-existing native unlocks without adopting removed or other-plugin recipes. */
    static Set<NamespacedKey> adoptable(Player player, Collection<RecipeDefinition> workbench,
                                       Collection<StationRecipeDefinition> stations,
                                       Predicate<NamespacedKey> registered,
                                       Predicate<NamespacedKey> earned) {
        Set<NamespacedKey> known = player.getDiscoveredRecipes();
        Set<NamespacedKey> adopt = new LinkedHashSet<>();
        for (RecipeDefinition recipe : workbench) {
            if (known.contains(recipe.key()) && registered.test(recipe.key())
                    && !earned.test(recipe.key())) adopt.add(recipe.key());
        }
        for (StationRecipeDefinition recipe : stations) {
            if (known.contains(recipe.key()) && registered.test(recipe.key())
                    && !earned.test(recipe.key())) adopt.add(recipe.key());
        }
        return Set.copyOf(adopt);
    }

    /** Cleans up book entries left behind when a recipe was deleted while the player was offline. */
    static void concealOrphans(Player player, Collection<RecipeDefinition> workbench,
                               Collection<StationRecipeDefinition> stations) {
        Set<NamespacedKey> current = new LinkedHashSet<>();
        for (RecipeDefinition recipe : workbench) current.add(recipe.key());
        for (StationRecipeDefinition recipe : stations) current.add(recipe.key());
        Set<NamespacedKey> orphans = new LinkedHashSet<>();
        for (NamespacedKey key : player.getDiscoveredRecipes()) {
            if (key.getNamespace().equals("recipeweaver") && !current.contains(key)) {
                orphans.add(key);
            }
        }
        if (!orphans.isEmpty()) player.undiscoverRecipes(orphans);
    }

    /** Restores visible native entries already earned in RecipeWeaver's durable discovery store. */
    static void revealKnown(Player player, Collection<RecipeDefinition> workbench,
                            Collection<StationRecipeDefinition> stations,
                            Predicate<NamespacedKey> discovered,
                            Predicate<NamespacedKey> registered) {
        Set<NamespacedKey> known = player.getDiscoveredRecipes();
        Set<NamespacedKey> reveal = new LinkedHashSet<>();
        for (RecipeDefinition recipe : workbench) {
            if (recipe.enabled() && !recipe.hidden() && !known.contains(recipe.key())
                    && discovered.test(recipe.key()) && registered.test(recipe.key())) {
                reveal.add(recipe.key());
            }
        }
        for (StationRecipeDefinition recipe : stations) {
            if (recipe.enabled() && !recipe.hidden() && !known.contains(recipe.key())
                    && discovered.test(recipe.key()) && registered.test(recipe.key())) {
                reveal.add(recipe.key());
            }
        }
        if (!reveal.isEmpty()) player.discoverRecipes(reveal);
    }

    /** Reveals one durably earned native recipe immediately after a successful discovery write. */
    static void revealEarned(Player player, NamespacedKey key, RecipeDefinition workbench,
                             StationRecipeDefinition station, boolean lockedDown,
                             boolean registered) {
        if (lockedDown || !registered || player.getDiscoveredRecipes().contains(key)) return;
        boolean visibleWorkbench = workbench != null && workbench.key().equals(key)
                && workbench.enabled() && !workbench.hidden();
        boolean visibleStation = station != null && station.key().equals(key)
                && station.enabled() && !station.hidden();
        if (visibleWorkbench || visibleStation) player.discoverRecipes(Set.of(key));
    }

    /** Attaches a book update without changing the API discovery result or revealing failed writes. */
    static CompletionStage<Boolean> afterDurable(CompletionStage<Boolean> discovery,
                                                  Runnable scheduleReveal) {
        discovery.whenComplete((saved, failure) -> {
            if (failure == null) scheduleReveal.run();
        });
        return discovery;
    }

    /** Failed saves must not leave an unearned native entry visible. */
    static CompletionStage<Boolean> afterFailed(CompletionStage<Boolean> discovery,
                                                 Consumer<Throwable> scheduleConceal) {
        discovery.whenComplete((saved, failure) -> {
            if (failure != null) scheduleConceal.accept(failure);
        });
        return discovery;
    }

    static void concealFailed(Player player, NamespacedKey key, boolean earned) {
        if (!earned) concealRemoved(player, key);
    }

    /** Removes a deleted native recipe from the vanilla book without discarding earned custom discovery. */
    static void concealRemoved(Player player, NamespacedKey key) {
        if (player.getDiscoveredRecipes().contains(key)) player.undiscoverRecipes(Set.of(key));
    }
}
