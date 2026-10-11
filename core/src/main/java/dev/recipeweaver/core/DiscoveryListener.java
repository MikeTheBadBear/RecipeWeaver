package dev.recipeweaver.core;

import org.bukkit.Keyed;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import org.bukkit.block.Furnace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerRecipeDiscoverEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/** Records native discovery and player-completed recipe transactions for the book. */
final class DiscoveryListener implements Listener {
    private final RecipeWeaverPlugin plugin;

    DiscoveryListener(RecipeWeaverPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNativeBookDiscover(PlayerRecipeDiscoverEvent event) {
        NamespacedKey key = event.getRecipe();
        if (NativeRecipeBook.blocksUnregisteredNativeKey(key, Bukkit.getRecipe(key) != null)
                || plugin.nativeRecipeBookUnavailable(key)) {
            event.setCancelled(true);
            return;
        }
        if (plugin.nativeRecipeBookNeedsDurableUnlock(event.getPlayer(), key)) {
            event.setCancelled(true);
            plugin.markDiscovered(event.getPlayer(), key);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.adoptNativeRecipeBook(event.getPlayer());
        plugin.reconcileNativeRecipeBook(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorkbenchCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || !(event.getRecipe() instanceof Keyed keyed)
                || !takesCraftResult(event.getAction(), event.getSlotType(),
                        event.getCurrentItem())) return;
        NamespacedKey key = keyed.getKey();
        if (key.getNamespace().equals("recipeweaver")) plugin.markDiscovered(player, key);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmithItem(SmithItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getSlotType() != InventoryType.SlotType.RESULT
                || !takesResult(event.getAction())
                || event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) return;
        NamespacedKey key = DiscoveryMatches.keyed(event.getInventory().getRecipe());
        if (key == null) return;
        StationRecipeDefinition recipe = plugin.stationRecipe(key).orElse(null);
        if (recipe != null && recipe.enabled()
                && (recipe.kind() == StationKind.SMITHING_TRANSFORM
                    || recipe.kind() == StationKind.SMITHING_TRIM)) {
            plugin.markDiscovered(player, key);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStonecuttingTake(InventoryClickEvent event) {
        if (event.getView().getType() != InventoryType.STONECUTTER
                || event.getClickedInventory() != event.getView().getTopInventory()
                || event.getSlotType() != InventoryType.SlotType.RESULT
                || !(event.getWhoClicked() instanceof Player player)
                || !takesResult(event.getAction())
                || event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) return;
        NamespacedKey key = DiscoveryMatches.stonecutting(
                event.getView(), event.getView().getTopInventory().getItem(0),
                event.getCurrentItem(),
                Bukkit.getRecipesFor(event.getCurrentItem()));
        if (key == null) return;
        StationRecipeDefinition recipe = plugin.stationRecipe(key).orElse(null);
        if (recipe != null && recipe.enabled() && recipe.kind() == StationKind.STONECUTTER) {
            plugin.markDiscovered(player, key);
        }
    }

    /** Covers result clicks on servers that do not emit FurnaceExtractEvent for every click path. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnaceResultClick(InventoryClickEvent event) {
        InventoryType type = event.getView().getType();
        if (type != InventoryType.FURNACE && type != InventoryType.BLAST_FURNACE
                && type != InventoryType.SMOKER) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()
                || event.getRawSlot() != 2
                || !(event.getWhoClicked() instanceof Player player)
                || !takesResult(event.getAction())
                || event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()
                || !(event.getView().getTopInventory().getHolder() instanceof Furnace furnace)) return;
        NamespacedKey key = DiscoveryMatches.furnace(furnace.getRecipesUsed(),
                event.getCurrentItem());
        if (key == null) return;
        StationRecipeDefinition recipe = plugin.stationRecipe(key).orElse(null);
        if (recipe != null && recipe.enabled() && switch (type) {
            case FURNACE -> recipe.kind() == StationKind.FURNACE;
            case BLAST_FURNACE -> recipe.kind() == StationKind.BLAST_FURNACE;
            case SMOKER -> recipe.kind() == StationKind.SMOKER;
            default -> false;
        }) plugin.markDiscovered(player, key);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFurnaceExtract(FurnaceExtractEvent event) {
        if (event.getItemAmount() < 1 || !(event.getBlock().getState() instanceof Furnace furnace)) return;
        for (NamespacedKey key : DiscoveryMatches.furnaceExtractionKeys(
                furnace.getRecipesUsed(), event.getItemType())) {
            StationRecipeDefinition recipe = plugin.stationRecipe(key).orElse(null);
            if (recipe != null && recipe.enabled()
                    && (recipe.kind() == StationKind.FURNACE
                        || recipe.kind() == StationKind.BLAST_FURNACE
                        || recipe.kind() == StationKind.SMOKER)) {
                plugin.markDiscovered(event.getPlayer(), key);
            }
        }
    }

    static boolean takesResult(InventoryAction action) {
        return switch (action) {
            case PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE,
                    MOVE_TO_OTHER_INVENTORY, HOTBAR_MOVE_AND_READD,
                    HOTBAR_SWAP, DROP_ALL_SLOT, DROP_ONE_SLOT -> true;
            default -> false;
        };
    }

    static boolean takesCraftResult(InventoryAction action, InventoryType.SlotType slot,
                                    org.bukkit.inventory.ItemStack result) {
        return slot == InventoryType.SlotType.RESULT && result != null
                && !result.getType().isAir() && takesResult(action);
    }
}
