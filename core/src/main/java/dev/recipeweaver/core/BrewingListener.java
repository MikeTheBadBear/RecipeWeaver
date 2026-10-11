package dev.recipeweaver.core;

import java.util.logging.Level;
import dev.recipeweaver.api.StationCraftBatch;
import dev.recipeweaver.api.StationCraftCompleteEvent;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrewingStand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BrewingStartEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.entity.Item;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;

/** Native brew-result overrides plus a tick-driven path for non-vanilla ingredient pairs. */
final class BrewingListener implements Listener {
    private final RecipeWeaverPlugin plugin;
    private final Set<Position> tracked = new HashSet<>();
    private final Map<Position, Job> jobs = new HashMap<>();
    private final Map<Position, CompletedBrew> completed = new HashMap<>();
    private final Map<Position, NativePlan> nativePlans = new HashMap<>();
    private long ticks;

    BrewingListener(RecipeWeaverPlugin plugin) {
        this.plugin = plugin;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) scan(chunk);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.BREWING_STAND) track(event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Position position = Position.of(event.getBlock());
        tracked.remove(position);
        jobs.remove(position);
        completed.remove(position);
        nativePlans.remove(position);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) { scan(event.getChunk()); }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        tracked.removeIf(position -> position.world.equals(chunk.getWorld().getUID())
                && position.x >> 4 == chunk.getX() && position.z >> 4 == chunk.getZ());
        jobs.keySet().removeIf(position -> position.world.equals(chunk.getWorld().getUID())
                && position.x >> 4 == chunk.getX() && position.z >> 4 == chunk.getZ());
        completed.keySet().removeIf(position -> position.world.equals(chunk.getWorld().getUID())
                && position.x >> 4 == chunk.getX() && position.z >> 4 == chunk.getZ());
        nativePlans.keySet().removeIf(position -> position.world.equals(chunk.getWorld().getUID())
                && position.x >> 4 == chunk.getX() && position.z >> 4 == chunk.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) { track(event.getInventory()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        track(top);
        if (!(top instanceof BrewerInventory brewer) || brewer.getLocation() == null
                || event.getClickedInventory() != top || event.getRawSlot() < 0
                || event.getRawSlot() > 2) return;
        Position position = Position.of(brewer.getLocation().getBlock());
        if (!DiscoveryListener.takesResult(event.getAction())) {
            if (event.getAction() != org.bukkit.event.inventory.InventoryAction.NOTHING) {
                CompletedBrew brew = completed.get(position);
                if (brew != null) {
                    brew.forget(event.getRawSlot());
                    if (!brew.hasPending()) completed.remove(position);
                }
            }
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) return;
        CompletedBrew brew = completed.get(position);
        if (brew == null) return;
        NamespacedKey key = brew.take(event.getRawSlot(), event.getCurrentItem());
        if (!brew.hasPending()) completed.remove(position);
        if (key != null) plugin.markDiscovered(player, key);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        track(top);
        if (top instanceof BrewerInventory brewer && brewer.getLocation() != null
                && event.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot < 3)) {
            Position position = Position.of(brewer.getLocation().getBlock());
            CompletedBrew brew = completed.get(position);
            if (brew != null) {
                for (int slot : event.getRawSlots()) {
                    if (slot >= 0 && slot < 3) brew.forget(slot);
                }
                if (!brew.hasPending()) completed.remove(position);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        track(event.getSource());
        track(event.getDestination());
        forgetCompleted(event.getSource());
        forgetCompleted(event.getDestination());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStart(BrewingStartEvent event) {
        if (plugin.lockedDown() || !(event.getBlock().getState() instanceof BrewingStand stand)) return;
        BrewerInventory inventory = stand.getInventory();
        ItemStack ingredient = inventory.getIngredient();
        int brewTime = compatibleStartTime(matches(event.getBlock(), inventory, ingredient));
        if (brewTime > 0) {
            event.setTotalBrewTime(brewTime);
            track(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        Position position = Position.of(event.getBlock());
        nativePlans.remove(position);
        BrewerInventory inventory = event.getContents();
        ItemStack ingredient = inventory.getIngredient();
        List<ItemStack> results = event.getResults();
        StationRecipeDefinition[] recipes = new StationRecipeDefinition[3];
        ItemStack[] selected = new ItemStack[3];
        for (int slot = 0; slot < 3; slot++) {
            BrewMatch match = findMatch(event.getBlock(), ingredient, inventory.getItem(slot));
            if (deniesNativeBottle(match, slot, results.size())) {
                // A denied owned bottle cannot fall back to vanilla brewing,
                // which would still consume the shared ingredient and fuel.
                event.setCancelled(true);
                jobs.remove(position);
                return;
            }
            recipes[slot] = match.recipe();
        }
        if (!nativeBrewCanProceed(recipes)) {
            event.setCancelled(true);
            jobs.remove(position);
            return;
        }
        try {
            for (int slot = 0; slot < Math.min(3, results.size()); slot++) {
                StationRecipeDefinition recipe = recipes[slot];
                if (recipe == null) continue;
                ItemStack result = selectResult(recipe, ingredient, inventory.getItem(slot));
                results.set(slot, result);
                selected[slot] = result;
            }
        } catch (RuntimeException ex) {
            event.setCancelled(true);
            jobs.remove(position);
            plugin.logAsync(Level.WARNING, "Could not select native brewing result at "
                    + event.getBlock().getLocation() + ": " + ex);
            return;
        }
        if (firstMatch(recipes) != null) {
            boolean controlled = requiresControlledCommit(recipes);
            ItemStack[] expectedBottles = bottles(inventory);
            BrewTransaction transaction = controlled ? BrewTransaction.plan(recipes, ingredient,
                    expectedBottles, results.toArray(new ItemStack[0])) : null;
            if (controlled) event.setCancelled(true);
            NativePlan plan = new NativePlan(event, new BrewOutcome(recipes, selected),
                    controlled, copy(ingredient), expectedBottles, transaction);
            nativePlans.put(position, plan);
            Bukkit.getScheduler().runTask(plugin, () -> nativePlans.remove(position, plan));
        }
        jobs.remove(position);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBrewComplete(BrewEvent event) {
        Position position = Position.of(event.getBlock());
        NativePlan plan = nativePlans.get(position);
        if (plan == null || plan.event != event) return;
        nativePlans.remove(position, plan);
        if (plan.controlled) {
            // Vanilla's ingredient decrement and bottle replacement must not run for custom
            // amount, catalyst, or remainder rules. The next tick checks every input again.
            event.setCancelled(true);
            if (plan.transaction != null
                    && nativeResultsUnchanged(plan.transaction, event.getResults())) {
                Block block = event.getBlock();
                Bukkit.getScheduler().runTask(plugin, () -> finishControlledNative(block, plan));
            }
            return;
        }
        if (event.isCancelled()) return;
        plan.outcome.retainEventResults(event.getResults());
        Block block = event.getBlock();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!(block.getState() instanceof BrewingStand stand)) return;
            ItemStack[] live = bottles(stand.getInventory());
            recordAndPublish(position, plan.outcome.takeCommitted(live), live);
        });
    }

    private void forgetCompleted(Inventory inventory) {
        if (inventory instanceof BrewerInventory brewer && brewer.getLocation() != null) {
            completed.remove(Position.of(brewer.getLocation().getBlock()));
        }
    }

    private void scan(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof BrewingStand) track(state.getBlock());
        }
    }

    private void track(Inventory inventory) {
        if (!(inventory instanceof BrewerInventory) || inventory.getLocation() == null) return;
        Block block = inventory.getLocation().getBlock();
        track(block);
    }

    private void track(Block block) {
        if (block.getType() == Material.BREWING_STAND) tracked.add(Position.of(block));
    }

    private void tick() {
        ticks++;
        List<Position> positions = new ArrayList<>(tracked);
        for (Position position : positions) {
            if (!jobs.containsKey(position) && ticks % 20 != 0) continue;
            World world = Bukkit.getWorld(position.world);
            if (world == null || !world.isChunkLoaded(position.x >> 4, position.z >> 4)) {
                tracked.remove(position);
                jobs.remove(position);
                completed.remove(position);
                nativePlans.remove(position);
                continue;
            }
            Block block = world.getBlockAt(position.x, position.y, position.z);
            if (block.getType() != Material.BREWING_STAND
                    || !(block.getState() instanceof BrewingStand stand)) {
                tracked.remove(position);
                jobs.remove(position);
                completed.remove(position);
                nativePlans.remove(position);
                continue;
            }
            if (plugin.lockedDown() || stand.getBrewingTime() > 0 || stand.getFuelLevel() < 1) {
                jobs.remove(position);
                continue;
            }
            BrewerInventory inventory = stand.getInventory();
            ItemStack ingredient = inventory.getIngredient();
            StationRecipeDefinition[] matches = matches(block, inventory, ingredient);
            StationRecipeDefinition first = firstMatch(matches);
            if (first == null || !compatibleSharedRules(matches)) {
                jobs.remove(position);
                continue;
            }
            ItemStack[] bottles = bottles(inventory);
            Job job = jobs.get(position);
            if (job == null || !job.matches(ingredient, bottles, matches)) {
                jobs.put(position, new Job(matches, copy(ingredient), bottles,
                        first.cookingTicks() + 2));
                continue;
            }
            job.remaining--;
            if (job.remaining > 0) continue;
            jobs.remove(position);
            finish(block, stand, inventory, ingredient, matches);
        }
    }

    private void finish(Block block, BrewingStand stand, BrewerInventory inventory,
                        ItemStack ingredient, StationRecipeDefinition[] matches) {
        ItemStack[] oldBottles = bottles(inventory);
        if (stand.getFuelLevel() < 1 || stand.getBrewingTime() > 0) return;
        if (!Arrays.equals(matches, matches(block, inventory, ingredient))) return;
        List<ItemStack> results = new ArrayList<>(3);
        int matched = 0;
        try {
            for (int slot = 0; slot < 3; slot++) {
                StationRecipeDefinition recipe = matches[slot];
                if (recipe != null) {
                    results.add(selectResult(recipe, ingredient, oldBottles[slot]));
                    matched++;
                } else results.add(copy(oldBottles[slot]));
            }
        } catch (RuntimeException ex) {
            plugin.logAsync(Level.WARNING, "Could not select brewing result at "
                    + block.getLocation() + ": " + ex);
            return;
        }
        if (matched == 0) return;
        BrewTransaction transaction = BrewTransaction.plan(matches, ingredient, oldBottles,
                results.toArray(new ItemStack[0]));
        if (transaction == null) return;
        commit(block, stand, transaction, matches, true);
    }

    private void finishControlledNative(Block block, NativePlan plan) {
        if (plugin.lockedDown() || block.getType() != Material.BREWING_STAND
                || !(block.getState() instanceof BrewingStand stand)) return;
        BrewerInventory inventory = stand.getInventory();
        ItemStack liveIngredient = inventory.getIngredient();
        ItemStack[] liveBottles = bottles(inventory);
        StationRecipeDefinition[] liveRecipes = matches(block, inventory, liveIngredient);
        if (!unchangedNativeInputs(plan.ingredient, plan.bottles, plan.outcome.recipes,
                liveIngredient, liveBottles, liveRecipes)) return;
        if (stand.getBrewingTime() > 0) return;
        commit(block, stand, plan.transaction, plan.outcome.recipes, false);
    }

    static boolean unchangedNativeInputs(ItemStack expectedIngredient, ItemStack[] expectedBottles,
                                         StationRecipeDefinition[] expectedRecipes,
                                         ItemStack actualIngredient, ItemStack[] actualBottles,
                                         StationRecipeDefinition[] actualRecipes) {
        return java.util.Objects.equals(expectedIngredient, actualIngredient)
                && Arrays.equals(expectedBottles, actualBottles)
                && Arrays.equals(expectedRecipes, actualRecipes);
    }

    static boolean nativeResultsUnchanged(BrewTransaction transaction, List<ItemStack> results) {
        if (transaction == null || results.size() != 3) return false;
        for (int slot = 0; slot < 3; slot++) {
            if (!sameOrEmpty(transaction.bottle(slot), results.get(slot))) return false;
        }
        return true;
    }

    private void commit(Block block, BrewingStand stand, BrewTransaction transaction,
                        StationRecipeDefinition[] matches, boolean consumeFuel) {
        ItemStack oldIngredient = copy(stand.getInventory().getIngredient());
        ItemStack[] oldBottles = bottles(stand.getInventory());
        int oldFuel = stand.getFuelLevel();
        if (consumeFuel && oldFuel < 1) return;
        List<Item> reserved = new ArrayList<>();
        boolean attemptedUpdate = false;
        try {
            // Reserve every returned item before modifying the stand. A cancelled item spawn
            // prevents the brew, and a failed stand update removes all reserved entities.
            for (ItemStack returned : transaction.returned()) {
                Item item = block.getWorld().dropItemNaturally(
                        block.getLocation().add(0.5, 0.5, 0.5), returned.clone());
                if (item != null) reserved.add(item);
                if (item == null || !item.isValid()) {
                    throw new IllegalStateException("Brewing remainder could not spawn");
                }
                item.setPickupDelay(20);
            }
            BrewerInventory snapshot = stand.getSnapshotInventory();
            for (int slot = 0; slot < 3; slot++) snapshot.setItem(slot, transaction.bottle(slot));
            snapshot.setIngredient(transaction.ingredient());
            if (consumeFuel) stand.setFuelLevel(oldFuel - 1);
            attemptedUpdate = true;
            if (!stand.update(true, false)) throw new IllegalStateException("Brewing stand changed while brewing");
            BrewingStand verified = (BrewingStand) block.getState();
            BrewerInventory live = verified.getInventory();
            if (!sameOrEmpty(transaction.ingredient(), live.getIngredient())) {
                throw new IllegalStateException("Brewing ingredient was not committed");
            }
            for (int slot = 0; slot < 3; slot++) {
                if (!sameOrEmpty(transaction.bottle(slot), live.getItem(slot))) {
                    throw new IllegalStateException("Brewing bottle was not committed");
                }
            }
            if (verified.getFuelLevel() != oldFuel - (consumeFuel ? 1 : 0)) {
                throw new IllegalStateException("Brewing fuel was not committed");
            }
        } catch (RuntimeException ex) {
            for (Item item : reserved) item.remove();
            if (attemptedUpdate) {
                try {
                    BrewingStand rollback = (BrewingStand) block.getState();
                    BrewerInventory snapshot = rollback.getSnapshotInventory();
                    for (int slot = 0; slot < 3; slot++) snapshot.setItem(slot, oldBottles[slot]);
                    snapshot.setIngredient(oldIngredient);
                    rollback.setFuelLevel(oldFuel);
                    rollback.update(true, false);
                } catch (RuntimeException rollbackFailure) {
                    plugin.logAsync(Level.SEVERE, "Brewing rollback failed at " + block.getLocation()
                            + ": " + rollbackFailure);
                }
            }
            plugin.logAsync(Level.WARNING, "Could not complete custom brewing at " + block.getLocation() + ": " + ex);
            return;
        }
        try {
            BrewingStand committed = (BrewingStand) block.getState();
            ItemStack[] live = bottles(committed.getInventory());
            BrewOutcome outcome = new BrewOutcome(matches, transaction.matchedResults());
            recordAndPublish(Position.of(block), outcome.takeCommitted(live), live);
        } catch (RuntimeException ex) {
            plugin.logAsync(Level.SEVERE, "Brewing committed but could not publish completion at "
                    + block.getLocation() + ": " + ex);
        }
    }

    private StationRecipeDefinition[] matches(Block block, BrewerInventory inventory,
                                              ItemStack ingredient) {
        StationRecipeDefinition[] matches = new StationRecipeDefinition[3];
        for (int slot = 0; slot < 3; slot++) {
            matches[slot] = find(block, ingredient, inventory.getItem(slot));
        }
        return matches;
    }

    private static StationRecipeDefinition firstMatch(StationRecipeDefinition[] matches) {
        for (StationRecipeDefinition recipe : matches) if (recipe != null) return recipe;
        return null;
    }

    private StationRecipeDefinition find(Block block, ItemStack ingredient, ItemStack bottle) {
        return findMatch(block, ingredient, bottle).recipe();
    }

    private BrewMatch findMatch(Block block, ItemStack ingredient, ItemStack bottle) {
        return resolve(plugin.stationRecipes(), ingredient, bottle, recipe ->
                (recipe.conditions().unrestricted() || GridConditionEvaluator.matchesAutomated(
                        recipe.conditions(), block.getWorld(), block.getLocation()))
                && plugin.stationExtensions().allows(null, recipe, List.of(ingredient, bottle))
                && !plugin.lockedDown()
                && plugin.stationRecipe(recipe.key()).orElse(null) == recipe);
    }

    static BrewMatch resolve(Iterable<StationRecipeDefinition> recipes, ItemStack ingredient,
                             ItemStack bottle, Predicate<StationRecipeDefinition> allowed) {
        if (!GridMatcher.present(ingredient) || !GridMatcher.present(bottle)) {
            return new BrewMatch(null, false);
        }
        boolean claimedInputs = false;
        for (StationRecipeDefinition recipe : recipes) {
            if (recipe.kind() != StationKind.BREWING || !recipe.enabled()) continue;
            if (!StationIngredientMatcher.matches(recipe, 0, ingredient)
                    || !StationIngredientMatcher.matches(recipe, 1, bottle)) continue;
            claimedInputs = true;
            // A bottle stack is not a native brewing slot. Replacing an overstack would
            // silently discard its excess items, so claim and deny it instead.
            if (bottle.getAmount() != 1) continue;
            if (allowed.test(recipe)) return new BrewMatch(recipe, true);
        }
        return new BrewMatch(null, claimedInputs);
    }

    static record BrewMatch(StationRecipeDefinition recipe, boolean claimedInputs) { }

    static boolean deniesNativeBottle(BrewMatch match, int slot, int resultSlots) {
        return match.claimedInputs() && (match.recipe() == null || slot >= resultSlots);
    }

    private static ItemStack[] bottles(BrewerInventory inventory) {
        ItemStack[] result = new ItemStack[3];
        for (int i = 0; i < 3; i++) result[i] = copy(inventory.getItem(i));
        return result;
    }

    private static ItemStack copy(ItemStack item) {
        return GridMatcher.present(item) ? item.clone() : null;
    }

    private static ItemStack selectResult(StationRecipeDefinition recipe, ItemStack ingredient,
                                          ItemStack bottle) {
        return selectResultForTicket(recipe, ingredient, bottle,
                ThreadLocalRandom.current().nextInt(recipe.totalResultWeight()));
    }

    static ItemStack selectResultForTicket(StationRecipeDefinition recipe, ItemStack ingredient,
                                           ItemStack bottle, int ticket) {
        ItemStack chosen = recipe.resultForTicket(ticket);
        if (recipe.resultCopySourceSlot() < 0) return chosen;
        ItemStack source = recipe.resultCopySourceSlot() == 0 ? ingredient : bottle;
        return GridResultMetadata.copyFrom(source, chosen, recipe.resultMetadataMode());
    }

    static boolean requiresControlledCommit(StationRecipeDefinition[] recipes) {
        for (StationRecipeDefinition recipe : recipes) {
            if (recipe == null) continue;
            if (recipe.ingredients().get(0).getAmount() != 1 || !recipe.consumes(0)
                    || recipe.remainder(0) != null || recipe.remainder(1) != null) return true;
        }
        return false;
    }

    static boolean compatibleSharedRules(StationRecipeDefinition[] recipes) {
        StationRecipeDefinition first = firstMatch(recipes);
        if (first == null) return false;
        int amount = first.ingredients().get(0).getAmount();
        boolean consumes = first.consumes(0);
        ItemStack remainder = first.remainder(0);
        int brewTime = first.cookingTicks();
        for (StationRecipeDefinition recipe : recipes) {
            if (recipe == null) continue;
            if (recipe.ingredients().get(0).getAmount() != amount
                    || recipe.consumes(0) != consumes
                    || recipe.cookingTicks() != brewTime
                    || !java.util.Objects.equals(recipe.remainder(0), remainder)) return false;
        }
        return true;
    }

    static int compatibleStartTime(StationRecipeDefinition[] recipes) {
        return compatibleSharedRules(recipes) ? firstMatch(recipes).cookingTicks() : -1;
    }

    static boolean nativeBrewCanProceed(StationRecipeDefinition[] recipes) {
        return firstMatch(recipes) == null || compatibleSharedRules(recipes);
    }

    private void recordAndPublish(Position position, List<CompletedSlot> slots, ItemStack[] live) {
        CompletedBrew next = CompletedBrew.merged(completed.get(position), slots, live);
        if (next.hasPending()) completed.put(position, next);
        else completed.remove(position);
        for (CompletedSlot slot : slots) {
            try {
                Bukkit.getPluginManager().callEvent(
                        new StationCraftCompleteEvent(null, slot.batch()));
            } catch (RuntimeException ex) {
                plugin.logAsync(Level.WARNING,
                        "Brewing craft event failed after commit: " + ex);
            }
            try { plugin.stationExtensions().runActions(null, slot.batch()); }
            catch (RuntimeException ex) {
                plugin.logAsync(Level.WARNING,
                        "Brewing extension actions failed after commit: " + ex);
            }
        }
    }

    private static boolean sameResult(ItemStack expected, ItemStack actual) {
        if (!GridMatcher.present(expected) || !GridMatcher.present(actual)
                || expected.getAmount() != actual.getAmount()
                || expected.getType() != actual.getType()) return false;
        if (expected.isSimilar(actual)) return true;
        if (!(expected.getItemMeta() instanceof PotionMeta expectedMeta)
                || !(actual.getItemMeta() instanceof PotionMeta actualMeta)) return false;
        Map<String, Object> expectedItem = new LinkedHashMap<>(expected.serialize());
        Map<String, Object> actualItem = new LinkedHashMap<>(actual.serialize());
        expectedItem.remove("meta");
        actualItem.remove("meta");
        return expectedItem.equals(actualItem)
                && normalizedPotionMeta(expectedMeta.serialize())
                        .equals(normalizedPotionMeta(actualMeta.serialize()));
    }

    private static boolean sameOrEmpty(ItemStack expected, ItemStack actual) {
        return !GridMatcher.present(expected) && !GridMatcher.present(actual)
                || sameResult(expected, actual);
    }

    /** Older servers may add an empty custom-effects list when applying a brewed bottle. */
    static Map<String, Object> normalizedPotionMeta(Map<String, Object> serialized) {
        Map<String, Object> fields = new LinkedHashMap<>(serialized);
        if (fields.get("custom-effects") instanceof List<?> effects && effects.isEmpty()) {
            fields.remove("custom-effects");
        }
        return fields;
    }

    private record NativePlan(BrewEvent event, BrewOutcome outcome, boolean controlled,
                              ItemStack ingredient, ItemStack[] bottles,
                              BrewTransaction transaction) { }

    /** One shared ingredient charge and one bottle result for each matching slot. */
    static final class BrewTransaction {
        private final ItemStack ingredient;
        private final ItemStack[] bottles;
        private final ItemStack[] matchedResults;
        private final List<ItemStack> returned;

        private BrewTransaction(ItemStack ingredient, ItemStack[] bottles,
                                ItemStack[] matchedResults, List<ItemStack> returned) {
            this.ingredient = copy(ingredient);
            this.bottles = bottles.clone();
            this.matchedResults = matchedResults.clone();
            this.returned = List.copyOf(returned);
        }

        static BrewTransaction plan(StationRecipeDefinition[] recipes, ItemStack ingredient,
                                    ItemStack[] currentBottles, ItemStack[] results) {
            if (recipes == null || recipes.length != 3 || currentBottles == null
                    || currentBottles.length != 3 || results == null || results.length != 3
                    || !GridMatcher.present(ingredient) || !compatibleSharedRules(recipes)) {
                return null;
            }
            StationRecipeDefinition first = firstMatch(recipes);
            int required = first.ingredients().get(0).getAmount();
            if (ingredient.getAmount() < required) return null;
            ItemStack nextIngredient = copy(ingredient);
            List<ItemStack> returned = new ArrayList<>();
            if (first.consumes(0)) {
                int left = ingredient.getAmount() - required;
                nextIngredient = left == 0 ? null : ingredient.clone();
                if (nextIngredient != null) nextIngredient.setAmount(left);
                ItemStack remainder = first.remainder(0);
                if (remainder != null) {
                    if (nextIngredient == null) nextIngredient = remainder;
                    else if (nextIngredient.isSimilar(remainder)
                            && nextIngredient.getAmount() + remainder.getAmount()
                                    <= nextIngredient.getMaxStackSize()) {
                        nextIngredient.setAmount(nextIngredient.getAmount() + remainder.getAmount());
                    } else returned.add(remainder);
                }
            }
            ItemStack[] nextBottles = new ItemStack[3];
            ItemStack[] matchedResults = new ItemStack[3];
            for (int slot = 0; slot < 3; slot++) {
                StationRecipeDefinition recipe = recipes[slot];
                if (recipe == null) {
                    ItemStack current = currentBottles[slot];
                    ItemStack result = results[slot];
                    if (GridMatcher.present(current) != GridMatcher.present(result)) return null;
                    if (GridMatcher.present(result)
                            && (result.getAmount() != 1
                            || !StationRecipeDefinition.bottleMaterial(result.getType()))) return null;
                    nextBottles[slot] = copy(result);
                    continue;
                }
                if (slot >= results.length || !GridMatcher.present(results[slot])
                        || !StationIngredientMatcher.matches(recipe, 0, ingredient)
                        || !StationIngredientMatcher.matches(recipe, 1, currentBottles[slot])
                        || currentBottles[slot].getAmount() != 1) return null;
                if (results[slot].getAmount() != 1
                        || !StationRecipeDefinition.bottleMaterial(results[slot].getType())
                        || !matchesResultChoice(recipe, ingredient, currentBottles[slot],
                                results[slot])) return null;
                nextBottles[slot] = copy(results[slot]);
                matchedResults[slot] = copy(results[slot]);
                ItemStack remainder = recipe.remainder(1);
                if (remainder != null) returned.add(remainder);
            }
            return new BrewTransaction(nextIngredient, nextBottles, matchedResults, returned);
        }

        private static boolean matchesResultChoice(StationRecipeDefinition recipe,
                                                   ItemStack ingredient, ItemStack bottle,
                                                   ItemStack result) {
            ItemStack source = recipe.resultCopySourceSlot() == 0 ? ingredient : bottle;
            for (var choice : recipe.resultChoices()) {
                ItemStack expected = choice.item();
                if (recipe.resultCopySourceSlot() >= 0) {
                    expected = GridResultMetadata.copyFrom(source, expected,
                            recipe.resultMetadataMode());
                }
                if (sameResult(expected, result)) return true;
            }
            return false;
        }

        ItemStack ingredient() { return copy(ingredient); }
        ItemStack bottle(int slot) { return copy(bottles[slot]); }
        ItemStack[] matchedResults() { return matchedResults.clone(); }
        List<ItemStack> returned() { return returned; }
    }

    static record CompletedSlot(int index, StationCraftBatch batch) { }

    /** Filters changed or missing bottle outputs and can publish its slots only once. */
    static final class BrewOutcome {
        private final StationRecipeDefinition[] recipes;
        private final ItemStack[] expected;
        private boolean consumed;

        BrewOutcome(StationRecipeDefinition[] recipes, ItemStack[] expected) {
            if (recipes.length != 3 || expected.length != 3) {
                throw new IllegalArgumentException("Brewing needs exactly three bottle slots");
            }
            this.recipes = recipes.clone();
            this.expected = new ItemStack[3];
            for (int slot = 0; slot < 3; slot++) this.expected[slot] = copy(expected[slot]);
        }

        void retainEventResults(List<ItemStack> results) {
            for (int slot = 0; slot < 3; slot++) {
                if (slot >= results.size() || !sameResult(expected[slot], results.get(slot))) {
                    recipes[slot] = null;
                }
            }
        }

        List<CompletedSlot> takeCommitted(ItemStack[] actual) {
            if (consumed) return List.of();
            consumed = true;
            List<CompletedSlot> committed = new ArrayList<>();
            for (int slot = 0; slot < 3; slot++) {
                if (recipes[slot] != null && slot < actual.length
                        && sameResult(expected[slot], actual[slot])) {
                    committed.add(new CompletedSlot(slot,
                            new StationCraftBatch(recipes[slot].key(), StationKind.BREWING,
                                    expected[slot])));
                }
            }
            return List.copyOf(committed);
        }
    }

    static final class CompletedBrew {
        private final NamespacedKey[] keys;
        private final ItemStack[] outputs;

        private CompletedBrew(NamespacedKey[] keys, ItemStack[] results) {
            this.keys = keys.clone();
            this.outputs = new ItemStack[3];
            for (int slot = 0; slot < Math.min(3, results.length); slot++) {
                this.outputs[slot] = copy(results[slot]);
            }
        }

        static CompletedBrew merged(CompletedBrew previous, List<CompletedSlot> committed,
                                    ItemStack[] live) {
            NamespacedKey[] keys = new NamespacedKey[3];
            ItemStack[] outputs = new ItemStack[3];
            if (previous != null) {
                for (int slot = 0; slot < 3; slot++) {
                    if (previous.keys[slot] != null && slot < live.length
                            && sameResult(previous.outputs[slot], live[slot])) {
                        keys[slot] = previous.keys[slot];
                        outputs[slot] = previous.outputs[slot];
                    }
                }
            }
            for (CompletedSlot slot : committed) {
                keys[slot.index()] = slot.batch().recipe();
                outputs[slot.index()] = slot.batch().result();
            }
            return new CompletedBrew(keys, outputs);
        }

        void forget(int slot) {
            keys[slot] = null;
            outputs[slot] = null;
        }

        boolean hasPending() {
            for (NamespacedKey key : keys) if (key != null) return true;
            return false;
        }

        NamespacedKey take(int slot, ItemStack current) {
            NamespacedKey key = keys[slot];
            boolean matches = key != null && sameResult(outputs[slot], current);
            forget(slot);
            return matches ? key : null;
        }
    }

    private record Position(UUID world, int x, int y, int z) {
        static Position of(Block block) {
            return new Position(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private static final class Job {
        private final StationRecipeDefinition[] recipes;
        private final ItemStack ingredient;
        private final ItemStack[] bottles;
        private int remaining;

        private Job(StationRecipeDefinition[] recipes, ItemStack ingredient,
                    ItemStack[] bottles, int remaining) {
            this.recipes = recipes.clone();
            this.ingredient = ingredient;
            this.bottles = bottles.clone();
            this.remaining = remaining;
        }

        private boolean matches(ItemStack ingredient, ItemStack[] bottles,
                                StationRecipeDefinition[] recipes) {
            if (this.recipes.length != recipes.length) return false;
            for (int slot = 0; slot < recipes.length; slot++) {
                if (this.recipes[slot] != recipes[slot]) return false;
            }
            return equal(this.ingredient, ingredient) && Arrays.equals(this.bottles, bottles);
        }

        private static boolean equal(ItemStack left, ItemStack right) {
            return java.util.Objects.equals(left, right);
        }
    }
}
