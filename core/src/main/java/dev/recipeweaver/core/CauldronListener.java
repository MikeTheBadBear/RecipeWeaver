package dev.recipeweaver.core;

import java.util.logging.Level;
import dev.recipeweaver.api.StationCraftBatch;
import dev.recipeweaver.api.StationCraftCompleteEvent;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Two-item water-cauldron mixing with a persisted first ingredient. */
final class CauldronListener implements Listener {
    private final RecipeWeaverPlugin plugin;
    private int pendingBreakCleanups;

    boolean pendingBreakCleanup() { return pendingBreakCleanups > 0; }

    CauldronListener(RecipeWeaverPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null || (block.getType() != Material.WATER_CAULDRON
                && block.getType() != Material.CAULDRON)) return;
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        CauldronRepository repository = plugin.cauldrons();
        ItemStack pending = repository.get(block.getLocation());
        if (plugin.storageSnapshotBusy() || repository.pendingWrite()) {
            if (pending != null || repository.isProtected(block.getLocation())
                    || (block.getType() == Material.WATER_CAULDRON
                            && findFirst(hand, player, block) != null)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.YELLOW
                        + "Wait for the RecipeWeaver cauldron save, backup, or reload to finish.");
            }
            return;
        }
        if (pending == null) {
            if (plugin.lockedDown() || block.getType() != Material.WATER_CAULDRON
                    || !(block.getBlockData() instanceof Levelled level) || level.getLevel() < 1) return;
            StationRecipeDefinition firstRecipe = findFirst(hand, player, block);
            if (firstRecipe == null) return;
            event.setCancelled(true);
            startMix(player, block, hand, firstRecipe);
            return;
        }

        event.setCancelled(true);
        if (!present(hand)) {
            retrieve(player, block, pending);
            return;
        }
        if (plugin.lockedDown()) {
            player.sendMessage(ChatColor.RED
                    + "RecipeWeaver is in lockdown. Use an empty hand to retrieve the ingredient.");
            return;
        }
        if (block.getType() != Material.WATER_CAULDRON
                || !(block.getBlockData() instanceof Levelled level) || level.getLevel() < 1) {
            player.sendMessage(ChatColor.RED + "Add water before completing this cauldron mix.");
            return;
        }
        StationRecipeDefinition recipe = findSecond(pending, hand, player, block);
        if (recipe == null) {
            player.sendMessage(ChatColor.RED + "That item does not complete the cauldron mix.");
            return;
        }
        finishMix(player, block, hand, pending, recipe);
    }

    private void startMix(Player player, Block block, ItemStack hand,
                          StationRecipeDefinition recipe) {
        int amount = recipe.ingredients().get(0).getAmount();
        ItemStack first = amount(hand, amount);
        ItemStack[] original = storage(player);
        ItemStack[] consumed = consumeHand(original,
                player.getInventory().getHeldItemSlot(), amount, true);
        if (consumed == null) return;
        try {
            player.getInventory().setStorageContents(consumed);
            player.updateInventory();
        } catch (RuntimeException ex) {
            restoreInventory(player, original, ex);
            player.sendMessage(ChatColor.RED + "Could not consume cauldron ingredient: " + ex.getMessage());
            return;
        }
        try {
            plugin.putCauldronAsync(block.getLocation(), first).whenComplete((ignored, failure) ->
                    onMain(() -> {
                        if (failure != null) {
                            deliver(player, block, first);
                            player.sendMessage(ChatColor.RED
                                    + "Could not start cauldron mix: " + failure.getMessage());
                            return;
                        }
                        if (block.getType() != Material.WATER_CAULDRON) {
                            try {
                                plugin.removeCauldronAsync(block.getLocation())
                                        .whenComplete((removed, cleanupFailure) -> onMain(() -> {
                                            if (cleanupFailure == null) deliver(player, block, first);
                                            else plugin.logAsync(Level.SEVERE,
                                                    "Could not recover cancelled cauldron start at "
                                                            + block.getLocation() + ": " + cleanupFailure);
                                        }));
                            } catch (IOException ex) {
                                plugin.logAsync(Level.SEVERE,
                                        "Could not recover cancelled cauldron start at "
                                                + block.getLocation() + ": " + ex);
                            }
                            return;
                        }
                        player.sendMessage(ChatColor.AQUA
                                + "First cauldron ingredient added. Add the second item, or right-click with an empty hand to retrieve it.");
                    }));
        } catch (IOException | RuntimeException ex) {
            deliver(player, block, first);
            player.sendMessage(ChatColor.RED + "Could not start cauldron mix: " + ex.getMessage());
        }
    }

    private void retrieve(Player player, Block block, ItemStack pending) {
        if (StationInventoryListener.insert(storage(player), pending) == null) {
            player.sendMessage(ChatColor.RED + "Make room to retrieve the pending ingredient.");
            return;
        }
        try {
            plugin.removeCauldronAsync(block.getLocation()).whenComplete((removed, failure) ->
                    onMain(() -> {
                        if (failure != null) {
                            player.sendMessage(ChatColor.RED
                                    + "Could not retrieve cauldron ingredient: " + failure.getMessage());
                            return;
                        }
                        if (removed != null) deliver(player, block, removed);
                        player.sendMessage(ChatColor.YELLOW + "Retrieved the pending cauldron ingredient.");
                    }));
        } catch (IOException | RuntimeException ex) {
            player.sendMessage(ChatColor.RED + "Could not retrieve cauldron ingredient: " + ex.getMessage());
        }
    }

    private void finishMix(Player player, Block block, ItemStack hand, ItemStack pending,
                           StationRecipeDefinition recipe) {
        ItemStack second = amount(hand, recipe.ingredients().get(1).getAmount());
        boolean secondConsumed = recipe.consumes(1);
        int heldSlot = player.getInventory().getHeldItemSlot();
        ItemStack selectedResult;
        try {
            int ticket = recipe.resultChoices().size() > 1
                    ? ThreadLocalRandom.current().nextInt(recipe.totalResultWeight()) : 0;
            selectedResult = resultForTicket(recipe, pending, second, ticket);
        } catch (RuntimeException ex) {
            plugin.logAsync(Level.WARNING, "Could not prepare cauldron result for "
                    + recipe.key() + ": " + ex);
            player.sendMessage(ChatColor.RED + "Could not prepare the cauldron result.");
            return;
        }
        ItemStack[] original = storage(player);
        ItemStack[] consumed = consumeHand(original, heldSlot,
                second.getAmount(), secondConsumed);
        if (consumed == null) return;
        if (!completionFits(recipe, consumed, selectedResult)) {
            player.sendMessage(ChatColor.RED
                    + "Make room in your inventory for the cauldron result and returned items.");
            return;
        }
        try {
            player.getInventory().setStorageContents(consumed);
            player.updateInventory();
        } catch (RuntimeException ex) {
            restoreInventory(player, original, ex);
            player.sendMessage(ChatColor.RED + "Could not consume cauldron ingredient: " + ex.getMessage());
            return;
        }
        try {
            plugin.removeCauldronAsync(block.getLocation()).whenComplete((removed, failure) ->
                    onMain(() -> {
                        if (failure != null) {
                            if (secondConsumed) deliver(player, block, second);
                            player.sendMessage(ChatColor.RED + "Cauldron mix failed: " + failure.getMessage());
                            return;
                        }
                        if (removed == null) {
                            if (secondConsumed) deliver(player, block, second);
                            player.sendMessage(ChatColor.RED + "Cauldron ingredient disappeared.");
                            return;
                        }
                        if (plugin.lockedDown()
                                || plugin.stationRecipe(recipe.key()).orElse(null) != recipe
                                || (!secondConsumed && !keptSecondStillPresent(
                                        player.getInventory().getStorageContents(), heldSlot, second))
                                || !conditionsAllow(recipe, player, block)
                                || !extensionAllows(recipe, player, removed, second)
                                || plugin.lockedDown()
                                || plugin.stationRecipe(recipe.key()).orElse(null) != recipe) {
                            deliver(player, block, removed);
                            if (secondConsumed) deliver(player, block, second);
                            player.sendMessage(ChatColor.RED
                                    + "Cauldron recipe changed or its conditions are no longer met; ingredients returned.");
                            return;
                        }
                        if (block.getType() != Material.WATER_CAULDRON
                                || !(block.getBlockData() instanceof Levelled level)
                                || level.getLevel() < 1) {
                            deliver(player, block, removed);
                            if (secondConsumed) deliver(player, block, second);
                            player.sendMessage(ChatColor.RED + "Cauldron water changed; ingredients returned.");
                            return;
                        }
                        BlockData oldData = block.getBlockData().clone();
                        try {
                            if (level.getLevel() == 1) block.setType(Material.CAULDRON);
                            else {
                                Levelled reduced = (Levelled) oldData.clone();
                                reduced.setLevel(level.getLevel() - 1);
                                block.setBlockData(reduced);
                            }
                        } catch (RuntimeException ex) {
                            try { block.setBlockData(oldData); }
                            catch (RuntimeException restoreFailure) { ex.addSuppressed(restoreFailure); }
                            deliver(player, block, removed);
                            if (secondConsumed) deliver(player, block, second);
                            plugin.logAsync(Level.SEVERE, "Could not consume cauldron water at "
                                    + block.getLocation() + ": " + ex);
                            player.sendMessage(ChatColor.RED + "Cauldron mix failed; ingredients returned.");
                            return;
                        }
                        if (!deliver(player, block, selectedResult)) return;
                        for (int slot = 0; slot < recipe.kind().slots(); slot++) {
                            if (!recipe.consumes(slot)) continue;
                            ItemStack remainder = recipe.remainder(slot);
                            if (remainder != null && !deliver(player, block, remainder)) {
                                plugin.logAsync(Level.SEVERE, "Could not return cauldron remainder for "
                                        + recipe.key() + " slot " + slot);
                            }
                        }
                        try { plugin.markDiscovered(player, recipe.key()); }
                        catch (RuntimeException ex) {
                            plugin.logAsync(Level.WARNING, "Could not record cauldron discovery: " + ex);
                        }
                        StationCraftBatch batch = new StationCraftBatch(
                                recipe.key(), StationKind.CAULDRON, selectedResult);
                        if (recipe.craftSound() != null) {
                            try { GridCraftSounds.play(player, List.of(recipe.craftSound())); }
                            catch (RuntimeException ex) {
                                plugin.logAsync(Level.WARNING, "Cauldron craft sound failed after commit: " + ex);
                            }
                        }
                        if (recipe.craftAdvancement() != null) {
                            try { GridCraftAdvancements.award(player, List.of(recipe.craftAdvancement())); }
                            catch (RuntimeException ex) {
                                plugin.logAsync(Level.WARNING, "Cauldron craft advancement failed after commit: " + ex);
                            }
                        }
                        if (recipe.craftCommand() != null) {
                            try {
                                for (String actionFailure : GridCraftCommands.execute(
                                        player, List.of(recipe.craftCommand()))) {
                                    plugin.logAsync(Level.WARNING, actionFailure);
                                }
                            } catch (RuntimeException ex) {
                                plugin.logAsync(Level.WARNING, "Cauldron craft command failed after commit: " + ex);
                            }
                        }
                        try {
                            Bukkit.getPluginManager().callEvent(
                                    new StationCraftCompleteEvent(player, batch));
                        } catch (RuntimeException ex) {
                            plugin.logAsync(Level.WARNING,
                                    "Cauldron craft event failed after commit: " + ex);
                        }
                        try { plugin.stationExtensions().runActions(player, batch); }
                        catch (RuntimeException ex) {
                            plugin.logAsync(Level.WARNING,
                                    "Cauldron extension actions failed after commit: " + ex);
                        }
                    }));
        } catch (IOException | RuntimeException ex) {
            if (secondConsumed) deliver(player, block, second);
            player.sendMessage(ChatColor.RED + "Cauldron mix failed: " + ex.getMessage());
        }
    }

    /** Deliver against the player's current inventory, or drop one item if it cannot be inserted. */
    private boolean deliver(Player player, Block block, ItemStack item) {
        try {
            ItemStack[] next = StationInventoryListener.insert(storage(player), item);
            if (next != null) {
                player.getInventory().setStorageContents(next);
                player.updateInventory();
                return true;
            }
        } catch (RuntimeException ex) {
            plugin.logAsync(Level.WARNING, "Could not return a cauldron item to "
                    + player.getName() + ": " + ex);
        }
        try {
            block.getWorld().dropItemNaturally(block.getLocation(), item.clone());
            return true;
        }
        catch (RuntimeException ex) {
            plugin.logAsync(Level.SEVERE, "Could not drop cauldron item at "
                    + block.getLocation() + ": " + ex);
            return false;
        }
    }

    private void restoreInventory(Player player, ItemStack[] original, RuntimeException cause) {
        try {
            player.getInventory().setStorageContents(original);
            player.updateInventory();
        } catch (RuntimeException restoreFailure) {
            cause.addSuppressed(restoreFailure);
            plugin.logAsync(Level.SEVERE, "Could not restore cauldron inventory for "
                    + player.getName() + ": " + restoreFailure);
        }
    }

    private void onMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) action.run();
        else {
            try { Bukkit.getScheduler().runTask(plugin, action); }
            catch (RuntimeException ex) {
                plugin.logAsync(Level.SEVERE, "Could not finish cauldron transaction on server thread: " + ex);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLevelChange(CauldronLevelChangeEvent event) {
        if (plugin.cauldrons().isProtected(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        CauldronRepository repository = plugin.cauldrons();
        if (!repository.isProtected(block.getLocation())) return;
        if (plugin.storageSnapshotBusy() || repository.pendingWrite()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.YELLOW
                    + "Wait for the RecipeWeaver cauldron save, backup, or reload to finish.");
            return;
        }
        BlockData oldData = block.getBlockData().clone();
        pendingBreakCleanups++;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (block.getType() == Material.WATER_CAULDRON || block.getType() == Material.CAULDRON) {
                pendingBreakCleanups--;
                return;
            }
            try {
                plugin.removeCauldronAsync(block.getLocation()).whenComplete((removed, failure) ->
                        onMain(() -> {
                            try {
                                if (failure == null) {
                                    if (removed != null) block.getWorld()
                                            .dropItemNaturally(block.getLocation(), removed);
                                } else {
                                    plugin.logAsync(Level.SEVERE,
                                            "Could not recover broken cauldron ingredient: " + failure);
                                    if (block.getType().isAir()) block.setBlockData(oldData);
                                }
                            } finally {
                                pendingBreakCleanups--;
                            }
                        }));
            } catch (IOException | RuntimeException ex) {
                plugin.logAsync(Level.SEVERE, "Could not recover broken cauldron ingredient: " + ex);
                if (block.getType().isAir()) block.setBlockData(oldData);
                pendingBreakCleanups--;
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> plugin.cauldrons().isProtected(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> plugin.cauldrons().isProtected(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block ->
                plugin.cauldrons().isProtected(block.getLocation()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block ->
                plugin.cauldrons().isProtected(block.getLocation()))) event.setCancelled(true);
    }

    private StationRecipeDefinition findFirst(ItemStack item, Player player, Block block) {
        if (!present(item)) return null;
        for (StationRecipeDefinition recipe : plugin.stationRecipes()) {
            if (recipe.kind() == StationKind.CAULDRON && recipe.enabled()
                    && StationIngredientMatcher.matches(recipe, 0, item)
                    && conditionsAllow(recipe, player, block)) return recipe;
        }
        return null;
    }

    private StationRecipeDefinition findSecond(ItemStack first, ItemStack second,
                                               Player player, Block block) {
        if (!present(second)) return null;
        for (StationRecipeDefinition recipe : plugin.stationRecipes()) {
            if (recipe.kind() != StationKind.CAULDRON || !recipe.enabled()) continue;
            if (StationIngredientMatcher.matches(recipe, 0, first)
                    && StationIngredientMatcher.matches(recipe, 1, second)
                    && conditionsAllow(recipe, player, block)
                    && extensionAllows(recipe, player, first, second)
                    && !plugin.lockedDown()
                    && plugin.stationRecipe(recipe.key()).orElse(null) == recipe) return recipe;
        }
        return null;
    }

    private static boolean conditionsAllow(StationRecipeDefinition recipe, Player player,
                                           Block block) {
        return recipe.conditions().unrestricted() || GridConditionEvaluator.matches(
                recipe.conditions(), player, block.getWorld());
    }

    private boolean extensionAllows(StationRecipeDefinition recipe, Player player,
                                    ItemStack first, ItemStack second) {
        return plugin.stationExtensions().allows(player, recipe,
                List.of(first.clone(), second.clone()));
    }

    private static ItemStack[] storage(Player player) {
        ItemStack[] source = player.getInventory().getStorageContents();
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) copy[i] = source[i] == null ? null : source[i].clone();
        return copy;
    }

    static ItemStack[] consumeHand(ItemStack[] storage, int slot, int amount, boolean consume) {
        if (storage == null || slot < 0 || slot >= storage.length || amount < 1) return null;
        ItemStack item = storage[slot];
        if (!present(item) || item.getAmount() < amount) return null;
        ItemStack[] next = new ItemStack[storage.length];
        for (int i = 0; i < storage.length; i++) next[i] = storage[i] == null ? null : storage[i].clone();
        if (consume) {
            if (item.getAmount() == amount) next[slot] = null;
            else next[slot].setAmount(item.getAmount() - amount);
        }
        return next;
    }

    static ItemStack amount(ItemStack item, int amount) {
        if (!present(item) || amount < 1 || item.getAmount() < amount) {
            throw new IllegalArgumentException("Cauldron input is smaller than the recipe amount");
        }
        ItemStack copy = item.clone();
        copy.setAmount(amount);
        return copy;
    }

    static ItemStack resultForTicket(StationRecipeDefinition recipe, ItemStack first,
                                     ItemStack second, int ticket) {
        ItemStack choice = recipe.resultChoices().size() > 1
                ? recipe.resultForTicket(ticket) : recipe.result();
        ItemStack source = switch (recipe.resultCopySourceSlot()) {
            case 0 -> first;
            case 1 -> second;
            default -> null;
        };
        return GridResultMetadata.copyFrom(source, choice, recipe.resultMetadataMode());
    }

    static boolean keptSecondStillPresent(ItemStack[] storage, int slot, ItemStack expected) {
        return storage != null && slot >= 0 && slot < storage.length
                && present(expected) && present(storage[slot])
                && storage[slot].getAmount() >= expected.getAmount()
                && storage[slot].isSimilar(expected);
    }

    static boolean completionFits(StationRecipeDefinition recipe, ItemStack[] storage,
                                  ItemStack result) {
        ItemStack[] next = storage;
        for (int slot = 0; slot < recipe.kind().slots(); slot++) {
            if (!recipe.consumes(slot)) continue;
            ItemStack remainder = recipe.remainder(slot);
            if (remainder == null) continue;
            next = StationInventoryListener.insert(next, remainder);
            if (next == null) return false;
        }
        return StationInventoryListener.insert(next, result) != null;
    }

    private static boolean present(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }
}
