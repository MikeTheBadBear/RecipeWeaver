package dev.recipeweaver.core;

import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import java.util.Collection;
import java.util.Map;
import java.util.WeakHashMap;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareInventoryResultEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.StonecuttingRecipe;

/** Rechecks a selected RecipeWeaver stonecutter result when its recipe or input changes. */
final class NativeStonecutterGuard implements Listener {
    private record Preview(NamespacedKey key, ItemStack result) { }

    private final RecipeWeaverPlugin plugin;
    private final Map<Inventory, Preview> previews = new WeakHashMap<>();

    NativeStonecutterGuard(RecipeWeaverPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(PrepareInventoryResultEvent event) {
        if (event.getView().getType() != InventoryType.STONECUTTER) return;
        Inventory inventory = event.getInventory();
        ItemStack result = event.getResult();
        Preview prior = previews.get(inventory);
        if (!present(result)) {
            if (prior != null && sameResult(prior.result(), inventory.getItem(1))) {
                inventory.setItem(1, null);
            }
            return;
        }
        Collection<? extends Recipe> candidates = Bukkit.getRecipesFor(result);
        StonecuttingRecipe selectedNative = DiscoveryMatches.selectedStonecuttingRecipe(
                event.getView());
        NamespacedKey selected = selectedNative == null
                ? DiscoveryMatches.stonecutting(event.getView(), inventory.getItem(0), result,
                        candidates)
                : selectedNative.getKey();
        Player player = event.getView().getPlayer() instanceof Player current ? current : null;
        if (selected != null) {
            previews.put(inventory, new Preview(selected, result.clone()));
            if (!allows(selected, inventory.getItem(0), result, player)) clear(event);
        } else if (hasOwnedMatch(inventory.getItem(0), result, candidates)) {
            // On an older API, or when the selected index is invalid, two
            // equal-output recipes cannot be distinguished. Do not let an
            // unverified owned result bypass its conditions.
            previews.remove(inventory);
            clear(event);
        } else if (prior != null && sameResult(prior.result(), result)) {
            if (!allows(prior.key(), inventory.getItem(0), result, player)) clear(event);
        } else {
            previews.remove(inventory);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getType() != InventoryType.STONECUTTER
                || event.getRawSlot() != 1
                || event.getClickedInventory() != event.getView().getTopInventory()) return;
        Inventory inventory = event.getView().getTopInventory();
        ItemStack result = event.getCurrentItem();
        if (present(result)) {
            Collection<? extends Recipe> candidates = Bukkit.getRecipesFor(result);
            StonecuttingRecipe selectedNative = DiscoveryMatches.selectedStonecuttingRecipe(
                    event.getView());
            NamespacedKey selected = selectedNative == null
                    ? DiscoveryMatches.stonecutting(event.getView(), inventory.getItem(0), result,
                            candidates)
                    : selectedNative.getKey();
            if (selected != null) {
                if (!allows(selected, inventory.getItem(0), result,
                        event.getWhoClicked() instanceof Player current ? current : null)) {
                    deny(event, inventory);
                }
                return;
            }
            if (hasOwnedMatch(inventory.getItem(0), result, candidates)) {
                deny(event, inventory);
                return;
            }
        }
        Preview prior = previews.get(inventory);
        if (prior == null) return;
        if (!sameResult(prior.result(), event.getCurrentItem())) {
            previews.remove(inventory);
            return;
        }
        Player player = event.getWhoClicked() instanceof Player current ? current : null;
        if (allows(prior.key(), inventory.getItem(0), event.getCurrentItem(), player)) return;
        deny(event, inventory);
    }

    private boolean allows(NamespacedKey key, ItemStack input, ItemStack result, Player player) {
        if (plugin.disabledVanillaRecipes().contains(key)) return false;
        if (!key.getNamespace().equals("recipeweaver")) {
            return Bukkit.getRecipe(key) instanceof StonecuttingRecipe nativeRecipe
                    && present(input) && nativeRecipe.getInputChoice().test(input)
                    && sameResult(nativeRecipe.getResult(), result);
        }
        StationRecipeDefinition saved = plugin.stationRecipe(key).orElse(null);
        return saved != null && saved.enabled() && !plugin.lockedDown()
                && saved.kind() == StationKind.STONECUTTER
                && (saved.conditions().unrestricted() || GridConditionEvaluator.matches(
                        saved.conditions(), player, player == null ? null : player.getWorld()))
                && Bukkit.getRecipe(key) instanceof StonecuttingRecipe
                && StationIngredientMatcher.matches(saved, 0, input)
                && sameResult(saved.result(), result);
    }

    private static void clear(PrepareInventoryResultEvent event) {
        event.setResult(null);
        event.getInventory().setItem(1, null);
    }

    private static void deny(InventoryClickEvent event, Inventory inventory) {
        event.setCancelled(true);
        event.setCurrentItem(null);
        inventory.setItem(1, null);
    }

    static boolean hasOwnedMatch(ItemStack input, ItemStack result,
                                 Collection<? extends Recipe> candidates) {
        if (!present(input) || !present(result)) return false;
        for (Recipe candidate : candidates) {
            if (candidate instanceof StonecuttingRecipe recipe
                    && recipe.getKey().getNamespace().equals("recipeweaver")
                    && sameResult(recipe.getResult(), result)
                    && recipe.getInputChoice().test(input)) return true;
        }
        return false;
    }

    private static boolean sameResult(ItemStack expected, ItemStack actual) {
        return present(expected) && present(actual)
                && expected.getAmount() == actual.getAmount() && expected.isSimilar(actual);
    }

    private static boolean present(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }
}
