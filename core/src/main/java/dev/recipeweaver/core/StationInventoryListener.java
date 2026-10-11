package dev.recipeweaver.core;

import java.util.logging.Level;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import dev.recipeweaver.api.StationCraftBatch;
import dev.recipeweaver.api.StationCraftCompleteEvent;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.NamespacedKey;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Supplies custom anvil and grindstone results and explicitly handles their take transaction. */
final class StationInventoryListener implements Listener {
    private final RecipeWeaverPlugin plugin;
    private final Set<NamespacedKey> metadataWarnings = new HashSet<>();
    private final Map<Inventory, OwnedPreview> ownedPreviews = new WeakHashMap<>();

    private record OwnedPreview(NamespacedKey key, StationRecipeDefinition definition,
                                ItemStack first, ItemStack second,
                                ItemStack result) {
        static OwnedPreview of(StationRecipeDefinition recipe, Inventory inventory,
                               ItemStack result) {
            return new OwnedPreview(recipe.key(), recipe, copy(inventory.getItem(0)),
                    copy(inventory.getItem(1)), copy(result));
        }

        boolean matches(StationRecipeDefinition recipe, Inventory inventory) {
            return definition == recipe && key.equals(recipe.key())
                    && sameInput(first, inventory.getItem(0))
                    && sameInput(second, inventory.getItem(1));
        }
    }

    StationInventoryListener(RecipeWeaverPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnvil(PrepareAnvilEvent event) {
        if (TextPrompt.owns(event.getInventory())) return;
        if (plugin.lockedDown()) {
            clearOwnedPreview(event.getInventory(), () -> event.setResult(null));
            return;
        }
        StationRecipeDefinition recipe = find(StationKind.ANVIL, event.getInventory());
        if (recipe == null) {
            clearOwnedPreview(event.getInventory(), () -> event.setResult(null));
            return;
        }
        Player player = event.getView().getPlayer() instanceof Player current ? current : null;
        if (!allows(player, recipe, event.getInventory())) {
            ownedPreviews.put(event.getInventory(), OwnedPreview.of(recipe, event.getInventory(), null));
            event.setResult(null);
            event.getInventory().setItem(2, null);
            return;
        }
        event.getInventory().setRepairCost(recipe.anvilLevels());
        ItemStack result = previewResult(recipe, event.getInventory());
        ownedPreviews.put(event.getInventory(), OwnedPreview.of(recipe, event.getInventory(), result));
        event.setResult(result);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGrindstone(PrepareGrindstoneEvent event) {
        if (plugin.lockedDown()) {
            clearOwnedPreview(event.getInventory(), () -> event.setResult(null));
            return;
        }
        StationRecipeDefinition recipe = find(StationKind.GRINDSTONE, event.getInventory());
        if (recipe == null) {
            clearOwnedPreview(event.getInventory(), () -> event.setResult(null));
            return;
        }
        Player player = event.getView().getPlayer() instanceof Player current ? current : null;
        if (!allows(player, recipe, event.getInventory())) {
            ownedPreviews.put(event.getInventory(), OwnedPreview.of(recipe, event.getInventory(), null));
            event.setResult(null);
            event.getInventory().setItem(2, null);
            return;
        }
        ItemStack result = previewResult(recipe, event.getInventory());
        ownedPreviews.put(event.getInventory(), OwnedPreview.of(recipe, event.getInventory(), result));
        event.setResult(result);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResultClick(InventoryClickEvent event) {
        if (event.getRawSlot() != 2 || !(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        StationKind kind = switch (event.getView().getType()) {
            case ANVIL -> StationKind.ANVIL;
            case GRINDSTONE -> StationKind.GRINDSTONE;
            default -> null;
        };
        if (kind == null || TextPrompt.owns(top)) return;
        StationRecipeDefinition recipe = find(kind, top);
        OwnedPreview owned = ownedPreviews.get(top);
        if (recipe == null && owned == null) return;
        event.setCancelled(true);
        if (plugin.lockedDown() || recipe == null
                || (owned != null && !owned.key().equals(recipe.key()))) {
            event.setCurrentItem(null);
            top.setItem(2, null);
            ownedPreviews.remove(top);
            return;
        }
        if (!allows(player, recipe, top)) {
            event.setCurrentItem(null);
            top.setItem(2, null);
            return;
        }
        ItemStack result;
        if (recipe.resultChoices().size() > 1) {
            if (owned == null || !owned.matches(recipe, top)) {
                event.setCurrentItem(null);
                top.setItem(2, null);
                ownedPreviews.remove(top);
                return;
            }
            result = copy(owned.result());
        } else {
            result = result(recipe, top, recipe.result());
        }
        if (!sameResult(result, event.getCurrentItem())) {
            event.setCurrentItem(null);
            top.setItem(2, null);
            return;
        }
        ClickType click = event.getClick();
        boolean shift = click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
        if (!shift && click != ClickType.LEFT && click != ClickType.RIGHT) return;
        if (kind == StationKind.ANVIL && player.getGameMode() != GameMode.CREATIVE
                && player.getLevel() < recipe.anvilLevels()) {
            player.sendMessage("You need " + recipe.anvilLevels() + " levels for this recipe.");
            return;
        }

        ItemStack oldCursor = copy(player.getItemOnCursor());
        ItemStack[] oldStorage = player.getInventory().getStorageContents();
        ItemStack[] nextStorage = shift ? insert(oldStorage, result) : null;
        ItemStack nextCursor = shift ? null : combine(oldCursor, result);
        if ((shift && nextStorage == null) || (!shift && nextCursor == null)) return;
        ItemStack oldFirst = copy(top.getItem(0));
        ItemStack oldSecond = copy(top.getItem(1));
        ItemStack oldResult = copy(top.getItem(2));
        InputPlan inputPlan = planInputs(recipe, oldFirst, oldSecond,
                shift ? nextStorage : oldStorage);
        if (inputPlan == null) return;
        int oldLevel = player.getLevel();
        Runnable undoLimit = null;
        Runnable undoDelay = null;
        try {
            player.getInventory().setStorageContents(inputPlan.storage());
            if (!shift) player.setItemOnCursor(nextCursor);
            top.setItem(0, inputPlan.first());
            top.setItem(1, inputPlan.second());
            top.setItem(2, null);
            event.setCurrentItem(null);
            if (kind == StationKind.ANVIL && player.getGameMode() != GameMode.CREATIVE) {
                player.setLevel(oldLevel - recipe.anvilLevels());
            }
            if (recipe.maxCraftsPerPlayer() > 0) {
                undoLimit = GridCraftLimits.commit(player, Map.of(recipe.key(), 1));
            }
            if (recipe.cooldownSeconds() > 0) {
                undoDelay = GridCooldowns.commit(player,
                        Map.of(recipe.key(), recipe.cooldownSeconds()));
            }
        } catch (RuntimeException ex) {
            undo(undoDelay, ex);
            undo(undoLimit, ex);
            top.setItem(0, oldFirst);
            top.setItem(1, oldSecond);
            top.setItem(2, oldResult);
            player.setLevel(oldLevel);
            player.getInventory().setStorageContents(oldStorage);
            player.setItemOnCursor(oldCursor);
            plugin.logAsync(Level.WARNING, "Could not complete " + kind + " transaction: " + ex.getMessage());
            return;
        }
        ownedPreviews.remove(top);
        plugin.markDiscovered(player, recipe.key());
        if (recipe.craftSound() != null) {
            try { GridCraftSounds.play(player, List.of(recipe.craftSound())); }
            catch (RuntimeException ex) {
                plugin.logAsync(Level.WARNING, "Station craft sound failed after commit: " + ex);
            }
        }
        if (recipe.craftAdvancement() != null) {
            try { GridCraftAdvancements.award(player, List.of(recipe.craftAdvancement())); }
            catch (RuntimeException ex) {
                plugin.logAsync(Level.WARNING, "Station craft advancement failed after commit: " + ex);
            }
        }
        if (recipe.craftCommand() != null) {
            try {
                for (String failure : GridCraftCommands.execute(player, List.of(recipe.craftCommand()))) {
                    plugin.logAsync(Level.WARNING, failure);
                }
            } catch (RuntimeException ex) {
                plugin.logAsync(Level.WARNING, "Station craft command failed after commit: " + ex);
            }
        }
        StationCraftBatch batch = new StationCraftBatch(recipe.key(), kind, result);
        try { Bukkit.getPluginManager().callEvent(new StationCraftCompleteEvent(player, batch)); }
        catch (RuntimeException ex) {
            plugin.logAsync(Level.WARNING, "Station craft event failed after commit: " + ex);
        }
        try { plugin.stationExtensions().runActions(player, batch); }
        catch (RuntimeException ex) {
            plugin.logAsync(Level.WARNING, "Station extension actions failed after commit: " + ex);
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory() == null
                    || player.getOpenInventory().getTopInventory() != top) return;
            if (!plugin.lockedDown()) {
                StationRecipeDefinition next = find(kind, top);
                if (next != null && allows(player, next, top)) {
                    if (kind == StationKind.ANVIL
                            && top instanceof org.bukkit.inventory.AnvilInventory anvil) {
                        anvil.setRepairCost(next.anvilLevels());
                    }
                    ItemStack preview = previewResult(next, top);
                    ownedPreviews.put(top, OwnedPreview.of(next, top, preview));
                    top.setItem(2, preview);
                }
            }
            player.updateInventory();
        });
    }

    private StationRecipeDefinition find(StationKind kind, Inventory inventory) {
        StationRecipeDefinition best = null;
        int fewestOptional = Integer.MAX_VALUE;
        for (StationRecipeDefinition recipe : plugin.stationRecipes()) {
            if (recipe.kind() != kind || !recipe.enabled()) continue;
            if (!matches(recipe, inventory.getItem(0), inventory.getItem(1))) continue;
            int optional = 0;
            for (boolean value : recipe.optionalSlots()) if (value) optional++;
            if (optional < fewestOptional) {
                best = recipe;
                fewestOptional = optional;
            }
        }
        return best;
    }

    private void clearOwnedPreview(Inventory inventory, Runnable clearResult) {
        if (ownedPreviews.remove(inventory) != null) {
            clearResult.run();
            inventory.setItem(2, null);
        }
    }

    private boolean allows(Player player, StationRecipeDefinition recipe, Inventory inventory) {
        return (recipe.conditions().unrestricted() || GridConditionEvaluator.matches(
                        recipe.conditions(), player, player == null ? null : player.getWorld()))
                && GridCraftLimits.allows(player, recipe.key(), recipe.maxCraftsPerPlayer())
                && GridCooldowns.ready(player, recipe.key(), recipe.cooldownSeconds())
                && plugin.stationExtensions().allows(player, recipe,
                        java.util.Arrays.asList(inventory.getItem(0), inventory.getItem(1)));
    }

    private ItemStack previewResult(StationRecipeDefinition recipe, Inventory inventory) {
        OwnedPreview previous = ownedPreviews.get(inventory);
        if (previous != null && previous.result() != null
                && previous.matches(recipe, inventory)) return copy(previous.result());
        ItemStack choice = recipe.resultChoices().size() > 1
                ? recipe.resultForTicket(ThreadLocalRandom.current().nextInt(
                        recipe.totalResultWeight())) : recipe.result();
        return result(recipe, inventory, choice);
    }

    private ItemStack result(StationRecipeDefinition recipe, Inventory inventory,
                             ItemStack choice) {
        try {
            return GridResultMetadata.copyFrom(
                    recipe.resultCopySourceSlot() < 0 ? null
                            : inventory.getItem(recipe.resultCopySourceSlot()),
                    choice, recipe.resultMetadataMode());
        } catch (RuntimeException ex) {
            if (metadataWarnings.add(recipe.key())) {
                plugin.logAsync(Level.WARNING, "Station result metadata could not be copied for "
                        + recipe.key() + ": " + ex);
            }
            return null;
        }
    }

    private static void undo(Runnable rollback, RuntimeException failure) {
        if (rollback == null) return;
        try { rollback.run(); }
        catch (RuntimeException undoFailure) { failure.addSuppressed(undoFailure); }
    }

    static boolean matches(List<ItemStack> expected, ItemStack... actual) {
        if (expected.size() != actual.length) return false;
        for (int i = 0; i < actual.length; i++) {
            if (expected.get(i) == null) {
                if (present(actual[i])) return false;
            } else if (!present(actual[i]) || !expected.get(i).isSimilar(actual[i])) {
                return false;
            }
        }
        return true;
    }

    static boolean matches(StationRecipeDefinition recipe, ItemStack... actual) {
        if (recipe.kind().slots() != actual.length) return false;
        for (int slot = 0; slot < actual.length; slot++) {
            if (!StationIngredientMatcher.matches(recipe, slot, actual[slot])) return false;
        }
        return true;
    }

    static ItemStack[] insert(ItemStack[] storage, ItemStack result) {
        ItemStack[] next = new ItemStack[storage.length];
        for (int i = 0; i < storage.length; i++) next[i] = copy(storage[i]);
        int remaining = result.getAmount();
        for (ItemStack stack : next) {
            if (!present(stack) || !stack.isSimilar(result)) continue;
            int added = Math.min(remaining, Math.max(0, stack.getMaxStackSize() - stack.getAmount()));
            stack.setAmount(stack.getAmount() + added);
            remaining -= added;
            if (remaining == 0) return next;
        }
        for (int i = 0; i < next.length; i++) {
            if (present(next[i])) continue;
            int added = Math.min(remaining, result.getMaxStackSize());
            next[i] = result.clone();
            next[i].setAmount(added);
            remaining -= added;
            if (remaining == 0) return next;
        }
        return null;
    }

    private static ItemStack combine(ItemStack cursor, ItemStack result) {
        if (!present(cursor)) {
            return result.getAmount() <= result.getMaxStackSize() ? result.clone() : null;
        }
        if (!cursor.isSimilar(result) || cursor.getAmount() + result.getAmount() > cursor.getMaxStackSize()) {
            return null;
        }
        ItemStack combined = cursor.clone();
        combined.setAmount(cursor.getAmount() + result.getAmount());
        return combined;
    }

    static InputPlan planInputs(StationRecipeDefinition recipe, ItemStack first,
                                ItemStack second, ItemStack[] storage) {
        ItemStack[] input = {copy(first), copy(second)};
        ItemStack[] nextStorage = storage.clone();
        for (int slot = 0; slot < recipe.kind().slots(); slot++) {
            if (!StationIngredientMatcher.matches(recipe, slot, input[slot])) return null;
            if (!recipe.hasIngredient(slot) || !present(input[slot]) || !recipe.consumes(slot)) continue;
            int remaining = input[slot].getAmount() - recipe.ingredients().get(slot).getAmount();
            input[slot] = remaining == 0 ? null : input[slot].clone();
            if (remaining > 0) input[slot].setAmount(remaining);
            ItemStack remainder = recipe.remainder(slot);
            if (remainder == null) continue;
            if (input[slot] == null) input[slot] = remainder;
            else {
                nextStorage = insert(nextStorage, remainder);
                if (nextStorage == null) return null;
            }
        }
        return new InputPlan(input[0], input[1], nextStorage);
    }

    record InputPlan(ItemStack first, ItemStack second, ItemStack[] storage) { }

    private static boolean sameResult(ItemStack expected, ItemStack actual) {
        return present(expected) && present(actual) && expected.getAmount() == actual.getAmount()
                && expected.isSimilar(actual);
    }

    private static boolean present(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }

    private static ItemStack copy(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private static boolean sameInput(ItemStack first, ItemStack second) {
        if (!present(first) || !present(second)) return !present(first) && !present(second);
        return first.equals(second);
    }
}
