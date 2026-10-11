package dev.recipeweaver.core;

import dev.recipeweaver.api.StationCraftBatch;
import dev.recipeweaver.api.StationCraftCompleteEvent;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Campfire;
import org.bukkit.block.Furnace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.CampfireStartEvent;
import org.bukkit.event.inventory.FurnaceStartSmeltEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.SmokingRecipe;

/** Rechecks the selected cooking recipe immediately before a native result commits. */
final class NativeCookingGuard implements Listener {
    private record Position(UUID world, int x, int y, int z) {
        static Position of(Block block) {
            return new Position(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    static record Started(NamespacedKey key, StationKind kind, ItemStack source,
                          ItemStack result, int campfireSlot) { }

    private final RecipeWeaverPlugin plugin;
    private final CampfireDiscoveryTracker campfireDiscovery = new CampfireDiscoveryTracker();
    private final FurnaceCommitTracker furnaceCommits = new FurnaceCommitTracker();
    private final CampfireCommitTracker campfireCommits = new CampfireCommitTracker();
    // Campfires can cook four items at once. A bound prevents abandoned starts
    // from retaining positions indefinitely when a chunk unloads mid-cook.
    private final Map<Position, List<Started>> started = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Position, List<Started>> eldest) {
            return size() > 4096;
        }
    };

    NativeCookingGuard(RecipeWeaverPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFurnaceStart(FurnaceStartSmeltEvent event) {
        remember(event.getBlock(), event.getRecipe(), event.getSource());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCampfireStart(CampfireStartEvent event) {
        remember(event.getBlock(), event.getRecipe(), event.getSource());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCook(BlockCookEvent event) {
        StationKind kind = kind(event.getBlock().getType());
        if (kind == null) return;
        Position position = Position.of(event.getBlock());
        List<Started> candidates = started.get(position);
        int campfireSlot = kind == StationKind.CAMPFIRE
                ? cookingCampfireSlot(event.getBlock(), event.getSource()) : -1;
        if (candidates == null || candidates.isEmpty()) {
            // A cook may already be in progress when this plugin reloads. With
            // no selected start token, a matching owned or excluded input cannot
            // safely bypass its current rules even if another listener changed output.
            if (requiresTrackedStart(plugin.stationRecipes(), kind,
                    event.getSource(), event.getResult())
                    || requiresTrackedDisabledCook(plugin.removedVanillaRecipes(), kind,
                            event.getSource(), event.getResult())) {
                event.setCancelled(true);
                if (campfireSlot >= 0) deferDeniedCampfireSlot(event.getBlock(), campfireSlot);
            }
            return;
        }
        if (kind == StationKind.CAMPFIRE && campfireSlot < 0) {
            // Some synthetic or platform start events expose no slot on either
            // side. Preserve one unambiguous unslotted start, but never borrow
            // a token that was bound to a physical slot.
            Started selected = uniqueUnslottedCampfireStart(candidates,
                    event.getSource(), event.getResult());
            if (selected != null && allows(selected, event.getBlock(),
                    event.getSource(), event.getResult())) {
                candidates.remove(selected);
                if (candidates.isEmpty()) started.remove(position);
                return;
            }
            if (unattributedCampfireRequiresDenial(candidates, event.getSource(), event.getResult(),
                    plugin.stationRecipes(), plugin.removedVanillaRecipes(),
                    plugin.disabledVanillaRecipes())) event.setCancelled(true);
            return;
        }
        List<Started> sameOutput = candidates.stream()
                .filter(candidate -> candidate.kind() == kind
                        && sameResult(candidate.result(), event.getResult())).toList();
        if (sameOutput.isEmpty()) {
            // A changed result must not evade validation of the selected owned
            // recipe (or a vanilla recipe disabled after its start event).
            // Unrelated cooking output remains another plugin's responsibility.
            boolean restricted = changedOutputRequiresDenial(candidates, kind,
                    campfireSlot, plugin.disabledVanillaRecipes());
            if (restricted) {
                event.setCancelled(true);
                if (campfireSlot >= 0) deferDeniedCampfireSlot(event.getBlock(), campfireSlot);
            }
            return;
        }
        if (campfireSlot >= 0) {
            final int selectedSlot = campfireSlot;
            Started selected = candidates.stream()
                    .filter(candidate -> candidate.campfireSlot() == selectedSlot).findFirst().orElse(null);
            // A token from another campfire slot must never authorize this one,
            // even when both slots have the same source and result.
            if (selected == null || !sameOutput.contains(selected)
                    || !sameSource(selected.source(), event.getSource())
                    || !allows(selected, event.getBlock(), event.getSource(), event.getResult())) {
                event.setCancelled(true);
                deferDeniedCampfireSlot(event.getBlock(), campfireSlot);
                return;
            }
            candidates.remove(selected);
            if (candidates.isEmpty()) started.remove(position);
            if (selected.key().getNamespace().equals("recipeweaver")) {
                event.setResult(campfireDiscovery.tagCookResult(
                        event.getResult(), selected.key(), event.getBlock(), campfireSlot));
            }
            return;
        }
        for (Started candidate : sameOutput) {
            if (!sameSource(candidate.source(), event.getSource())) continue;
            if (!allows(candidate, event.getBlock(), event.getSource(), event.getResult())) continue;
            candidates.remove(candidate);
            if (candidates.isEmpty()) started.remove(position);
            if (candidate.key().getNamespace().equals("recipeweaver")) {
                queueFurnaceCompletion(event, candidate);
            }
            return;
        }
        event.setCancelled(true);
    }

    private void queueFurnaceCompletion(BlockCookEvent event, Started selected) {
        Block block = event.getBlock();
        if (!(block.getState() instanceof Furnace furnace)) return;
        FurnaceCommitTracker.Pending pending = furnaceCommits.begin(
                new FurnaceCommitTracker.Position(block.getWorld().getUID(),
                        block.getX(), block.getY(), block.getZ()),
                selected.key(), selected.kind(), furnace.getInventory().getSmelting(),
                furnace.getInventory().getResult(), event.getSource(), event.getResult());
        if (pending == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                    || block.getType() != materialFor(selected.kind())
                    || !(block.getState() instanceof Furnace current)) {
                furnaceCommits.discard(pending);
                return;
            }
            StationCraftBatch batch = furnaceCommits.complete(pending,
                    !event.isCancelled() && sameResult(pending.expectedResult(), event.getResult()),
                    current.getInventory().getSmelting(), current.getInventory().getResult());
            if (batch == null) return;
            dispatchCompletion(batch, block);
        });
    }

    private void dispatchCompletion(StationCraftBatch batch, Block origin) {
        try { Bukkit.getPluginManager().callEvent(new StationCraftCompleteEvent(null, batch)); }
        catch (RuntimeException ex) {
            plugin.logAsync(Level.WARNING, "Cooking completion event failed after commit: " + ex);
        }
        StationRecipeDefinition saved = plugin.stationRecipe(batch.recipe()).orElse(null);
        if (saved != null && saved.enabled() && saved.kind() == batch.kind()) {
            try { AutomatedCookingActions.run(saved,
                    origin.getLocation().add(0.5, 0.5, 0.5),
                    warning -> plugin.logAsync(Level.WARNING, warning)); }
            catch (RuntimeException ex) {
                plugin.logAsync(Level.WARNING, "Cooking built-in actions failed after commit: " + ex);
            }
        }
        try { plugin.stationExtensions().runActions(null, batch); }
        catch (RuntimeException ex) {
            plugin.logAsync(Level.WARNING, "Cooking extension actions failed after commit: " + ex);
        }
    }

    private static Material materialFor(StationKind kind) {
        return switch (kind) {
            case FURNACE -> Material.FURNACE;
            case BLAST_FURNACE -> Material.BLAST_FURNACE;
            case SMOKER -> Material.SMOKER;
            default -> throw new IllegalArgumentException("Not a furnace cooking kind: " + kind);
        };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        StationKind broken = kind(block.getType());
        if (broken == null) return;
        Position position = Position.of(block);
        started.remove(position);
        if (broken == StationKind.CAMPFIRE) {
            campfireDiscovery.invalidateOrigin(position.world(), position.x(),
                    position.y(), position.z());
            campfireCommits.invalidateOrigin(position.world(), position.x(),
                    position.y(), position.z());
        } else {
            furnaceCommits.invalidate(new FurnaceCommitTracker.Position(position.world(),
                    position.x(), position.y(), position.z()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCampfireDrop(ItemSpawnEvent event) {
        var drop = event.getEntity();
        ItemStack spawned = drop.getItemStack();
        CampfireDiscoveryTracker.SpawnClaim claim = campfireDiscovery.claimSpawn(
                spawned, drop.getLocation());
        if (claim.item() != spawned) drop.setItemStack(claim.item());
        if (claim.recipe() != null) {
            drop.getPersistentDataContainer().set(CampfireDiscoveryTracker.DROP_RECIPE,
                    PersistentDataType.STRING, claim.recipe().toString());
            CampfireCommitTracker.Pending pending = campfireCommits.begin(drop.getUniqueId(), claim);
            if (pending != null) Bukkit.getScheduler().runTask(plugin, () -> {
                CampfireDiscoveryTracker.CookOrigin origin = pending.origin();
                var world = Bukkit.getWorld(origin.world());
                if (world == null || !world.isChunkLoaded(origin.x() >> 4, origin.z() >> 4)) {
                    campfireCommits.discard(pending);
                    return;
                }
                Block originBlock = world.getBlockAt(origin.x(), origin.y(), origin.z());
                if (!(originBlock.getState() instanceof Campfire campfire)) {
                    campfireCommits.discard(pending);
                    return;
                }
                StationCraftBatch batch = campfireCommits.complete(pending,
                        !event.isCancelled(), drop.isValid(), drop.getItemStack(),
                        drop.getPersistentDataContainer().get(CampfireDiscoveryTracker.DROP_RECIPE,
                                PersistentDataType.STRING),
                        !present(campfire.getItem(origin.slot())));
                if (batch != null) dispatchCompletion(batch, originBlock);
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCampfireDropMerge(ItemMergeEvent event) {
        if (CampfireDiscoveryTracker.protectsMerge(
                event.getEntity().getPersistentDataContainer(),
                event.getTarget().getPersistentDataContainer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCampfirePickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || event.getRemaining() >= event.getItem().getItemStack().getAmount()) return;
        NamespacedKey key = campfireDiscovery.consumePickup(
                event.getItem().getPersistentDataContainer(), event.getItem().getItemStack(),
                candidate -> plugin.stationRecipe(candidate).orElse(null), Bukkit::getRecipe);
        if (key != null) plugin.markDiscovered(player, key);
    }

    private void remember(Block block, CookingRecipe<?> recipe, ItemStack source) {
        StationKind kind = kind(block.getType());
        if (kind == null || !sameKind(kind, recipe) || source == null) return;
        NamespacedKey key = recipe.getKey();
        Position position = Position.of(block);
        // Track vanilla starts too: a vanilla exclusion can be switched on
        // while an already selected cook is still in progress.
        int campfireSlot = kind == StationKind.CAMPFIRE ? emptyCampfireSlot(block) : -1;
        Started preview = new Started(key, kind, source.clone(), recipe.getResult().clone(),
                campfireSlot);
        if (kind != StationKind.CAMPFIRE) {
            started.put(position, new ArrayList<>(List.of(preview)));
        } else {
            List<Started> slots = started.computeIfAbsent(position, ignored -> new ArrayList<>());
            if (campfireSlot >= 0) slots.removeIf(candidate -> candidate.campfireSlot() == campfireSlot);
            if (slots.size() == 4) slots.remove(0);
            slots.add(preview);
        }
    }

    private static int emptyCampfireSlot(Block block) {
        if (!(block.getState() instanceof Campfire campfire)) return -1;
        for (int slot = 0; slot < campfire.getSize(); slot++) {
            if (!present(campfire.getItem(slot))) return slot;
        }
        return -1;
    }

    private static int cookingCampfireSlot(Block block, ItemStack source) {
        if (!(block.getState() instanceof Campfire campfire)) return -1;
        for (int slot = 0; slot < campfire.getSize(); slot++) {
            if (sameSource(campfire.getItem(slot), source)
                    && campfire.getCookTime(slot) >= campfire.getCookTimeTotal(slot)) return slot;
        }
        return -1;
    }

    private static void deferDeniedCampfireSlot(Block block, int slot) {
        if (!(block.getState() instanceof Campfire campfire) || slot >= campfire.getSize()) return;
        if (!present(campfire.getItem(slot))) return;
        // A cancelled campfire cook exits the vanilla tick loop. Without
        // moving this slot below its threshold, an earlier denied slot keeps
        // later valid slots from ever reaching their own completion event.
        campfire.setCookTime(slot, 0);
        campfire.setCookTimeTotal(slot, Math.max(20, campfire.getCookTimeTotal(slot)));
        campfire.update(false, false);
    }

    private boolean allows(Started preview, Block block, ItemStack source, ItemStack result) {
        if (plugin.disabledVanillaRecipes().contains(preview.key())) return false;
        if (!preview.key().getNamespace().equals("recipeweaver")) {
            return activeVanillaCookMatches(preview, Bukkit.getRecipe(preview.key()),
                    source, result);
        }
        StationRecipeDefinition saved = plugin.stationRecipe(preview.key()).orElse(null);
        return saved != null && saved.enabled() && !plugin.lockedDown()
                && saved.kind() == preview.kind()
                && sameKind(preview.kind(), Bukkit.getRecipe(preview.key()))
                && StationIngredientMatcher.matches(saved, 0, source)
                && sameResult(saved.result(), result)
                && (saved.conditions().unrestricted() || GridConditionEvaluator.matchesAutomated(
                        saved.conditions(), block.getWorld(), block.getLocation()))
                && plugin.stationExtensions().allows(null, saved, List.of(source));
    }

    static boolean changedOutputRequiresDenial(List<Started> candidates, StationKind kind,
            int campfireSlot, Collection<NamespacedKey> disabled) {
        return candidates.stream().anyMatch(candidate -> candidate.kind() == kind
                && (candidate.key().getNamespace().equals("recipeweaver")
                        || disabled.contains(candidate.key()))
                // A furnace has one input slot. Changing both that input and
                // the output must not erase the restricted start at this block.
                && (kind != StationKind.CAMPFIRE
                        || candidate.campfireSlot() == campfireSlot));
    }

    static boolean activeVanillaCookMatches(Started preview, Recipe active,
            ItemStack source, ItemStack result) {
        return active instanceof CookingRecipe<?> cooking
                && preview.key().equals(cooking.getKey())
                && sameKind(preview.kind(), cooking)
                && present(source) && cooking.getInputChoice().test(source)
                && sameResult(cooking.getResult(), result);
    }

    static boolean requiresTrackedStart(Collection<StationRecipeDefinition> recipes,
            StationKind kind, ItemStack source, ItemStack result) {
        if (source == null || result == null || result.getType().isAir()) return false;
        return recipes.stream().anyMatch(saved -> saved.kind() == kind
                && StationIngredientMatcher.matches(saved, 0, source));
    }

    static boolean requiresTrackedDisabledCook(Collection<Recipe> removed,
            StationKind kind, ItemStack source, ItemStack result) {
        if (!present(source) || !present(result)) return false;
        return removed.stream().anyMatch(recipe -> recipe instanceof CookingRecipe<?> cooking
                && sameKind(kind, cooking)
                && cooking.getInputChoice().test(source));
    }

    static boolean unattributedCampfireRequiresDenial(List<Started> candidates,
            ItemStack source, ItemStack result, Collection<StationRecipeDefinition> owned,
            Collection<Recipe> removed, Collection<NamespacedKey> disabled) {
        if (!present(source)) return false;
        if (candidates.stream().anyMatch(candidate -> candidate.kind() == StationKind.CAMPFIRE
                && (candidate.key().getNamespace().equals("recipeweaver")
                        || disabled.contains(candidate.key()))
                && (sameSource(candidate.source(), source)
                        || sameResult(candidate.result(), result)))) return true;
        // A missing token must not be treated as permission to use a matching
        // owned or disabled recipe when the slot could not be determined.
        return owned.stream().anyMatch(saved -> saved.kind() == StationKind.CAMPFIRE
                && StationIngredientMatcher.matches(saved, 0, source))
                || removed.stream().anyMatch(recipe -> recipe instanceof CampfireRecipe cooking
                        && cooking.getInputChoice().test(source));
    }

    static Started uniqueUnslottedCampfireStart(List<Started> candidates,
            ItemStack source, ItemStack result) {
        Started selected = null;
        for (Started candidate : candidates) {
            if (candidate.kind() != StationKind.CAMPFIRE
                    || !sameSource(candidate.source(), source)
                    || !sameResult(candidate.result(), result)) continue;
            if (selected != null) return null;
            selected = candidate;
        }
        return selected != null && selected.campfireSlot() < 0 ? selected : null;
    }

    private static StationKind kind(Material material) {
        return switch (material) {
            case FURNACE -> StationKind.FURNACE;
            case BLAST_FURNACE -> StationKind.BLAST_FURNACE;
            case SMOKER -> StationKind.SMOKER;
            case CAMPFIRE, SOUL_CAMPFIRE -> StationKind.CAMPFIRE;
            default -> null;
        };
    }

    private static boolean sameKind(StationKind kind, Object recipe) {
        return switch (kind) {
            case FURNACE -> recipe instanceof FurnaceRecipe;
            case BLAST_FURNACE -> recipe instanceof BlastingRecipe;
            case SMOKER -> recipe instanceof SmokingRecipe;
            case CAMPFIRE -> recipe instanceof CampfireRecipe;
            default -> false;
        };
    }

    private static boolean sameResult(ItemStack expected, ItemStack actual) {
        return expected != null && actual != null && !actual.getType().isAir()
                && expected.getAmount() == actual.getAmount() && expected.isSimilar(actual);
    }

    private static boolean sameSource(ItemStack started, ItemStack actual) {
        return started != null && actual != null && !actual.getType().isAir()
                && started.isSimilar(actual);
    }

    private static boolean present(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }
}
