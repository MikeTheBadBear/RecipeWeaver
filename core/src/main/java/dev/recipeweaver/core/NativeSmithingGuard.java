package dev.recipeweaver.core;

import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.SmithingInventory;

/** Rechecks native smithing previews and result takes after a recipe changes. */
final class NativeSmithingGuard implements Listener {
    private final RecipeWeaverPlugin plugin;
    private final NativePreviewTracker previews = new NativePreviewTracker();

    NativeSmithingGuard(RecipeWeaverPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepare(PrepareSmithingEvent event) {
        SmithingInventory inventory = event.getInventory();
        Recipe recipe = inventory.getRecipe();
        ItemStack result = event.getResult();
        if (recipe instanceof Keyed) {
            previews.remember(inventory, result);
            Player player = event.getView().getPlayer() instanceof Player current ? current : null;
            if (!allows(inventory, result, player)) event.setResult(null);
        } else if (recipe == null && previews.matches(inventory, result)) {
            event.setResult(null);
            inventory.setResult(null);
        } else if (recipe != null || result != null) {
            previews.forget(inventory);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTake(SmithItemEvent event) {
        if (event.getSlotType() != InventoryType.SlotType.RESULT || event.getRawSlot() != 3) return;
        Recipe recipe = event.getInventory().getRecipe();
        if (recipe == null && !previews.matches(event.getInventory(), event.getCurrentItem())) {
            previews.forget(event.getInventory());
            return;
        }
        Player player = event.getWhoClicked() instanceof Player current ? current : null;
        if (recipe == null || !allows(event.getInventory(), event.getCurrentItem(), player)) {
            event.setCancelled(true);
            event.getInventory().setResult(null);
            event.setCurrentItem(null);
        }
    }

    private boolean allows(SmithingInventory inventory, ItemStack presented, Player player) {
        Recipe nativeRecipe = inventory.getRecipe();
        if (!(nativeRecipe instanceof Keyed keyed)) return true;
        NamespacedKey key = keyed.getKey();
        if (plugin.disabledVanillaRecipes().contains(key)) return false;
        if (!key.getNamespace().equals("recipeweaver")) return true;
        StationRecipeDefinition saved = plugin.stationRecipe(key).orElse(null);
        return allowsRecipe(saved, plugin.lockedDown(),
                inventory.getItem(0), inventory.getItem(1), inventory.getItem(2), presented, player);
    }

    static boolean allowsRecipe(StationRecipeDefinition saved, boolean lockedDown,
                                ItemStack template, ItemStack base, ItemStack addition,
                                ItemStack presented) {
        return allowsRecipe(saved, lockedDown, template, base, addition, presented, null);
    }

    static boolean allowsRecipe(StationRecipeDefinition saved, boolean lockedDown,
                                ItemStack template, ItemStack base, ItemStack addition,
                                ItemStack presented, Player player) {
        if (saved == null || !saved.enabled() || lockedDown || !present(presented)
                || (saved.kind() != StationKind.SMITHING_TRANSFORM
                    && saved.kind() != StationKind.SMITHING_TRIM)) return false;
        if (!saved.conditions().unrestricted() && !GridConditionEvaluator.matches(
                saved.conditions(), player, player == null ? null : player.getWorld())) return false;
        ItemStack[] actual = {template, base, addition};
        for (int slot = 0; slot < 3; slot++) {
            if (!StationIngredientMatcher.matches(saved, slot, actual[slot])) return false;
        }
        if (saved.kind() == StationKind.SMITHING_TRIM) {
            return presented.getType() == base.getType() && presented.getAmount() == 1;
        }
        ItemStack expected = saved.result();
        // Bukkit may copy base-item components into a transform result, so compare its
        // material and amount while the three ingredient choices remain authoritative.
        return expected.getType() == presented.getType()
                && expected.getAmount() == presented.getAmount();
    }

    private static boolean present(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }
}
