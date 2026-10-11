package dev.recipeweaver.core;

import dev.recipeweaver.api.RecipeDefinition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

/** Rechecks native workbench previews and takes after a recipe changes or is disabled. */
final class NativeCraftGuard implements Listener {
    private final RecipeWeaverPlugin plugin;
    private final NativePreviewTracker previews = new NativePreviewTracker();

    NativeCraftGuard(RecipeWeaverPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepare(PrepareItemCraftEvent event) {
        CraftingInventory inventory = event.getInventory();
        Recipe recipe = event.getRecipe();
        ItemStack result = inventory.getResult();
        if (recipe instanceof Keyed) {
            previews.remember(inventory, result);
            Player player = event.getView().getPlayer() instanceof Player viewer ? viewer : null;
            if (!allows(recipe, inventory, result, player)) inventory.setResult(null);
        } else if (recipe == null && previews.matches(inventory, result)) {
            inventory.setResult(null);
        } else if (recipe != null || result != null) {
            previews.forget(inventory);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        Recipe recipe = event.getRecipe();
        if (recipe == null && !previews.matches(event.getInventory(), event.getCurrentItem())) {
            previews.forget(event.getInventory());
            return;
        }
        Player player = event.getWhoClicked() instanceof Player viewer ? viewer : null;
        if (recipe == null || !allows(recipe, event.getInventory(), event.getCurrentItem(), player)) {
            event.setCancelled(true);
            event.getInventory().setResult(null);
            event.setCurrentItem(null);
        }
    }

    private boolean allows(Recipe recipe, CraftingInventory inventory, ItemStack presented,
                           Player player) {
        if (!(recipe instanceof Keyed keyed)) return true;
        NamespacedKey key = keyed.getKey();
        if (plugin.disabledVanillaRecipes().contains(key)) return false;
        if (!key.getNamespace().equals("recipeweaver")) return true;
        return allowsRecipe(plugin.recipe(key).orElse(null), plugin.lockedDown(),
                inventory.getMatrix(), presented, player, player == null ? null : player.getWorld());
    }

    static boolean allowsRecipe(RecipeDefinition saved, boolean lockedDown,
                                ItemStack[] matrix, ItemStack presented) {
        return allowsRecipe(saved, lockedDown, matrix, presented, null, null);
    }

    static boolean allowsRecipe(RecipeDefinition saved, boolean lockedDown,
                                ItemStack[] matrix, ItemStack presented,
                                Player player, World world) {
        if (saved == null || !saved.enabled() || lockedDown || presented == null
                || presented.getType().isAir()) return false;
        ItemStack expected = saved.result();
        List<ItemStack> normalized = matrix(matrix);
        return normalized != null && expected.getAmount() == presented.getAmount()
                && expected.isSimilar(presented) && RecipeMatcher.matches(saved, normalized)
                && GridConditionEvaluator.matches(saved.conditions(), player, world);
    }

    static boolean allowsAutomatedRecipe(RecipeDefinition saved, boolean lockedDown,
                                          ItemStack[] matrix, ItemStack presented,
                                          World world, Location location) {
        if (saved == null || !saved.enabled() || lockedDown || presented == null
                || presented.getType().isAir()) return false;
        ItemStack expected = saved.result();
        List<ItemStack> normalized = matrix(matrix);
        return normalized != null && expected.getAmount() == presented.getAmount()
                && expected.isSimilar(presented) && RecipeMatcher.matches(saved, normalized)
                && GridConditionEvaluator.matchesAutomated(saved.conditions(), world, location);
    }

    private static List<ItemStack> matrix(ItemStack[] source) {
        if (source == null) return null;
        if (source.length == 9) return Arrays.asList(source);
        if (source.length != 4) return null;
        List<ItemStack> normalized = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        normalized.set(0, source[0]);
        normalized.set(1, source[1]);
        normalized.set(3, source[2]);
        normalized.set(4, source[3]);
        return normalized;
    }
}
