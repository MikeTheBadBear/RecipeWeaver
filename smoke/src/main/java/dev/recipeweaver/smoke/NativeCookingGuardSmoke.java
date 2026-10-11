package dev.recipeweaver.smoke;

import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.StationCraftCompleteEvent;
import dev.recipeweaver.api.StationRecipeDefinition;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Campfire;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.CampfireStartEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.FurnaceStartSmeltEvent;
import org.bukkit.event.Listener;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Recipe;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Exercises real Bukkit start and completion events for all native cooking stations. */
final class NativeCookingGuardSmoke {
    private NativeCookingGuardSmoke() { }

    static void verifyOwned(RecipeWeaverApi api, NamespacedKey furnace, NamespacedKey blast,
            NamespacedKey smoker, NamespacedKey campfire) {
        checkOne(api, furnace, Material.FURNACE, 301);
        checkOne(api, blast, Material.BLAST_FURNACE, 302);
        checkOne(api, smoker, Material.SMOKER, 303);
        checkOne(api, campfire, Material.CAMPFIRE, 304);
        verifyCampfireSlots(api, campfire);
    }

    /** Synthetic commit events verify the public null-player cooking condition callback. */
    static void verifyCookingExtensionConditions(JavaPlugin smoke, RecipeWeaverApi api,
            NamespacedKey furnace, NamespacedKey blast, NamespacedKey smoker,
            NamespacedKey campfire) {
        NamespacedKey[] keys = {furnace, blast, smoker, campfire};
        Material[] blocks = {Material.FURNACE, Material.BLAST_FURNACE,
                Material.SMOKER, Material.CAMPFIRE};
        for (int index = 0; index < keys.length; index++) {
            NamespacedKey key = keys[index];
            StationRecipeDefinition saved = api.stationRecipe(key).orElseThrow();
            CookingRecipe<?> nativeRecipe = (CookingRecipe<?>) Bukkit.getRecipe(key);
            check(nativeRecipe != null, "Missing native cooking recipe for callback " + key);
            AtomicInteger checks = new AtomicInteger();
            api.registerStationCondition(smoke, key, (player, recipe, inputs) -> {
                check(player == null && recipe.key().equals(key)
                                && inputs.size() == 1 && inputs.get(0).getType() == Material.DIRT,
                        "Native cooking callback received wrong player, recipe, or input");
                inputs.get(0).setType(Material.STONE);
                checks.incrementAndGet();
                return false;
            });
            try {
                Block owned = block(blocks[index], Bukkit.getWorlds().get(0), 330 + index);
                ItemStack source = new ItemStack(Material.DIRT);
                start(owned, nativeRecipe, source);
                check(cook(owned, source, saved.result()).isCancelled(),
                        blocks[index] + " ignored a registered station condition");
                check(checks.get() == 1 && source.getType() == Material.DIRT,
                        blocks[index] + " callback did not get one detached input");
                if (index == 0) {
                    FurnaceRecipe vanilla = null;
                    var recipes = Bukkit.recipeIterator();
                    while (recipes.hasNext()) {
                        Recipe candidate = recipes.next();
                        if (candidate instanceof FurnaceRecipe furnaceRecipe
                                && furnaceRecipe.getKey().getNamespace().equals("minecraft")
                                && furnaceRecipe.getInput().getType() != Material.DIRT
                                && furnaceRecipe.getResult().getType() != Material.DIAMOND) {
                            vanilla = furnaceRecipe;
                            break;
                        }
                    }
                    check(vanilla != null, "No unrelated vanilla furnace recipe for callback smoke");
                    Block other = block(Material.FURNACE, Bukkit.getWorlds().get(0), 338);
                    start(other, vanilla, vanilla.getInput());
                    check(!cook(other, vanilla.getInput(), vanilla.getResult()).isCancelled(),
                            "Owned station condition denied an unrelated vanilla cook");
                    check(checks.get() == 1, "Unrelated vanilla cook called an owned condition");
                }
            } finally {
                check(api.unregisterStationCondition(smoke, key),
                        "Could not remove native cooking condition " + key);
            }
        }
    }

    private static void verifyCampfireSlots(RecipeWeaverApi api, NamespacedKey firstKey) {
        StationRecipeDefinition first = api.stationRecipe(firstKey).orElseThrow();
        NamespacedKey secondKey = new NamespacedKey("smoke", "campfire_second_slot");
        CampfireRecipe secondNative = new CampfireRecipe(secondKey, first.result(),
                Material.STONE, 0, 20);
        Bukkit.removeRecipe(secondKey);
        check(Bukkit.addRecipe(secondNative), "Could not register the second campfire slot recipe");
        try {
            CampfireRecipe firstNative = (CampfireRecipe) Bukkit.getRecipe(firstKey);
            check(firstNative != null && secondNative != null,
                    "Both campfire slot recipes must be registered");
            CampfireSlots slots = new CampfireSlots();
            Block block = block(Material.CAMPFIRE, Bukkit.getWorlds().get(0), 306, slots.state);
            ItemStack dirt = new ItemStack(Material.DIRT);
            ItemStack stone = new ItemStack(Material.STONE);
            ItemStack result = first.result();

            start(block, firstNative, dirt);
            slots.items[0] = dirt;
            start(block, secondNative, stone);
            slots.items[1] = stone;
            slots.cookTimeTotal[0] = slots.cookTimeTotal[1] = 1;

            // A changed first slot must not borrow the valid second slot's token.
            slots.items[0] = stone;
            slots.cookTime[0] = 1;
            check(cook(block, stone, result).isCancelled(),
                    "Changed campfire slot borrowed another slot's valid cook");
            check(slots.cookTime[0] == 0 && slots.cookTimeTotal[0] >= 20,
                    "Denied campfire slot still stalls later slots every tick");

            slots.items[0] = dirt;
            slots.cookTime[0] = 0;
            slots.cookTime[1] = 1;
            check(!cook(block, stone, result).isCancelled(),
                    "A valid second campfire slot was denied after the first slot changed");

            slots.items[1] = null;
            slots.cookTime[1] = 0;
            slots.items[1] = stone;
            slots.cookTime[1] = 1;
            check(cook(block, stone, result).isCancelled(),
                    "Campfire slot completed twice from one start token");
        } finally {
            Bukkit.removeRecipe(secondKey);
        }
    }

    private static void checkOne(RecipeWeaverApi api, NamespacedKey key,
            Material material, int x) {
        StationRecipeDefinition saved = api.stationRecipe(key).orElseThrow();
        CookingRecipe<?> recipe = (CookingRecipe<?>) Bukkit.getRecipe(key);
        if (recipe == null) throw new IllegalStateException("Missing cooking recipe " + key);
        Block block = block(material, Bukkit.getWorlds().get(0), x);
        ItemStack input = new ItemStack(Material.DIRT);
        ItemStack result = saved.result();

        check(cook(block, input, result).isCancelled(),
                material + " cooked an owned result without a selected start after reload");
        start(block, recipe, input);
        check(!cook(block, input, result).isCancelled(), material + " valid cook was denied");

        start(block, recipe, input);
        check(cook(block, new ItemStack(Material.STONE), result).isCancelled(),
                material + " cooked a changed input");

        start(block, recipe, input);
        check(cook(block, input, new ItemStack(Material.GOLD_INGOT, 64)).isCancelled(),
                material + " accepted a changed 64-item result");

        start(block, recipe, input);
        check(api.removeStationRecipe(key), material + " recipe removal failed");
        check(cook(block, input, result).isCancelled(), material + " cooked a removed recipe");
        api.saveStationRecipe(saved);
        check(Bukkit.getRecipe(key) != null, material + " recipe was not restored");
    }

    static void verifyVanillaExclusion(RecipeWeaverApi api) {
        FurnaceRecipe vanilla = null;
        var recipes = Bukkit.recipeIterator();
        while (recipes.hasNext()) {
            Recipe candidate = recipes.next();
            if (candidate instanceof FurnaceRecipe furnace
                    && furnace.getKey().getNamespace().equals("minecraft")) {
                vanilla = furnace;
                break;
            }
        }
        check(vanilla != null, "No vanilla furnace recipe was available for exclusion smoke");
        NamespacedKey key = vanilla.getKey();
        Block block = block(Material.FURNACE, Bukkit.getWorlds().get(0), 305);
        ItemStack input = vanilla.getInput();
        ItemStack result = vanilla.getResult();
        check(!cook(block, input, result).isCancelled(),
                "An unrelated vanilla cook without a selected start was denied");
        Bukkit.getLogger().info("RW_NATIVE_COOKING_NO_START_PASS");
        start(block, vanilla, input);
        check(!cook(block, input, result).isCancelled(),
                "Allowed vanilla cook was denied");

        start(block, vanilla, input);
        api.setVanillaRecipeDisabledAsync(key, true);
        check(api.disabledVanillaRecipes().contains(key),
                "Vanilla cooking exclusion did not become active");
        check(cook(block, input, result).isCancelled(),
                "Vanilla cook continued after its recipe was disabled");
        start(block, vanilla, input);
        check(cook(block, input, new ItemStack(Material.GOLD_INGOT, 64)).isCancelled(),
                "Disabled vanilla cook accepted a changed 64-item result");
        api.setVanillaRecipeDisabledAsync(key, false);
        check(!api.disabledVanillaRecipes().contains(key),
                "Vanilla cooking exclusion was not cleared");
    }

    /** Lets a real lit campfire tick two simultaneous cooks after synthetic starts. */
    static CompletableFuture<Void> startPhysicalCampfirePulse(JavaPlugin smoke,
            RecipeWeaverApi api, NamespacedKey firstKey) {
        CompletableFuture<Void> pulse = new PhysicalCampfirePulse(smoke, api, firstKey).start();
        pulse.whenComplete((ignored, failure) -> {
            if (failure != null) smoke.getLogger().log(java.util.logging.Level.SEVERE,
                    "Physical campfire pulse failed", failure);
        });
        return pulse;
    }

    /** Runs lit block ticks, including a recipe removal during the commit event. */
    static CompletableFuture<Void> startPhysicalFurnacePulse(JavaPlugin smoke,
            RecipeWeaverApi api, NamespacedKey furnace, NamespacedKey blast,
            NamespacedKey smoker) {
        return NativeFurnacePhysicalSmoke.start(smoke, api, furnace, blast, smoker);
    }

    private static final class PhysicalCampfirePulse implements Listener {
        private final JavaPlugin smoke;
        private final RecipeWeaverApi api;
        private final NamespacedKey firstKey;
        private final NamespacedKey secondKey = new NamespacedKey("smoke",
                "campfire_physical_second");
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private Block valid;
        private Block changed;
        private BlockState previousValid;
        private BlockState previousChanged;
        private Chunk validChunk;
        private Chunk changedChunk;
        private boolean validWasForceLoaded;
        private boolean changedWasForceLoaded;
        private int allowedValid;
        private int deniedChanged;
        private int allowedNeighbor;
        private int readinessAttempts;
        private Item ownedDrop;
        private int taggedCooks;
        private int committedOwned;
        private int nearbyDiamondDrops;
        private int temporaryDropTokens;

        private PhysicalCampfirePulse(JavaPlugin smoke, RecipeWeaverApi api, NamespacedKey firstKey) {
            this.smoke = smoke;
            this.api = api;
            this.firstKey = firstKey;
        }

        private CompletableFuture<Void> start() {
            StationRecipeDefinition first = api.stationRecipe(firstKey).orElseThrow();
            CampfireRecipe secondNative = new CampfireRecipe(secondKey, first.result(),
                    Material.STONE, 0, 20);
            Bukkit.removeRecipe(secondKey);
            check(Bukkit.addRecipe(secondNative),
                    "Could not register the second physical campfire recipe");
            World world = Bukkit.getWorlds().get(0);
            Location spawn = world.getSpawnLocation();
            valid = world.getBlockAt(spawn.getBlockX() + 24,
                    spawn.getBlockY() + 10, spawn.getBlockZ() + 24);
            changed = valid.getRelative(1, 0, 0);
            validChunk = valid.getChunk();
            changedChunk = changed.getChunk();
            validWasForceLoaded = validChunk.isForceLoaded();
            changedWasForceLoaded = changedChunk.isForceLoaded();
            validChunk.setForceLoaded(true);
            changedChunk.setForceLoaded(true);
            previousValid = valid.getState();
            previousChanged = changed.getState();
            try {
                valid.setType(Material.CAMPFIRE, false);
                changed.setType(Material.CAMPFIRE, false);
                Bukkit.getPluginManager().registerEvents(this, smoke);
                // The main smoke setup publishes several asynchronous reloads.
                // Arm after they settle so the two-tick cook exercises the guard
                // with the selected RecipeWeaver recipe still registered.
                Bukkit.getScheduler().runTaskLater(smoke, this::armWhenReady, 50L);
            } catch (Throwable failure) {
                cleanup();
                throw new IllegalStateException("Could not arm physical campfire pulse", failure);
            }
            return result;
        }

        private void armWhenReady() {
            try {
                CampfireRecipe firstNative = (CampfireRecipe) Bukkit.getRecipe(firstKey);
                CampfireRecipe secondNative = (CampfireRecipe) Bukkit.getRecipe(secondKey);
                if (firstNative == null || secondNative == null) {
                    check(++readinessAttempts < 5,
                            "Physical campfire recipes were unavailable after reload");
                    Bukkit.getScheduler().runTaskLater(smoke, this::armWhenReady, 5L);
                    return;
                }
                arm(valid, 0, firstNative, new ItemStack(Material.DIRT));
                arm(changed, 0, firstNative, new ItemStack(Material.DIRT));
                arm(changed, 1, secondNative, new ItemStack(Material.STONE));
                Campfire changedState = (Campfire) changed.getState();
                changedState.setItem(0, new ItemStack(Material.STONE));
                check(changedState.update(false, false),
                        "Could not change the first physical campfire slot");
                Bukkit.getScheduler().runTaskLater(smoke, this::finish, 20L);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
                cleanup();
            }
        }

        private static void arm(Block block, int slot, CampfireRecipe recipe, ItemStack source) {
            NativeCookingGuardSmoke.start(block, recipe, source);
            Campfire state = (Campfire) block.getState();
            state.setItem(slot, source);
            state.setCookTime(slot, 0);
            state.setCookTimeTotal(slot, 2);
            check(state.update(false, false), "Could not arm physical campfire slot " + slot);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void observeCook(BlockCookEvent event) {
            if (sameBlock(event.getBlock(), valid)) {
                if (!event.isCancelled()) {
                    allowedValid++;
                    if (event.getResult().getItemMeta().getPersistentDataContainer().has(
                            new NamespacedKey("recipeweaver", "campfire_cook_token"))) taggedCooks++;
                }
            } else if (sameBlock(event.getBlock(), changed)) {
                if (event.isCancelled()) deniedChanged++;
                else allowedNeighbor++;
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void observeDrop(ItemSpawnEvent event) {
            Item drop = event.getEntity();
            if (valid != null && drop.getItemStack().getType() == Material.DIAMOND
                    && drop.getWorld().equals(valid.getWorld())
                    && Math.abs(drop.getLocation().getX() - valid.getX()) <= 4
                    && Math.abs(drop.getLocation().getZ() - valid.getZ()) <= 4) {
                nearbyDiamondDrops++;
                if (drop.getItemStack().getItemMeta().getPersistentDataContainer().has(
                        new NamespacedKey("recipeweaver", "campfire_cook_token"))) {
                    temporaryDropTokens++;
                }
            }
            if (firstKey.toString().equals(drop.getPersistentDataContainer().get(
                    new NamespacedKey("recipeweaver", "campfire_drop_recipe"),
                    PersistentDataType.STRING))) {
                check(ownedDrop == null, "Physical campfire generated duplicate owned drops");
                check(!drop.getItemStack().getItemMeta().getPersistentDataContainer().has(
                        new NamespacedKey("recipeweaver", "campfire_cook_token")),
                        "Physical campfire exposed the temporary cook token on an item");
                ownedDrop = drop;
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void observeCompletion(StationCraftCompleteEvent event) {
            if (!firstKey.equals(event.batch().recipe())) return;
            check(event.player() == null
                            && event.batch().kind() == dev.recipeweaver.api.StationKind.CAMPFIRE
                            && event.batch().result().isSimilar(api.stationRecipe(firstKey)
                                    .orElseThrow().result()),
                    "Physical campfire completion carried a player, kind, or output mismatch");
            committedOwned++;
        }

        private void finish() {
            try {
                check(allowedValid == 1, "Physical valid campfire slot did not cook exactly once");
                check(deniedChanged >= 1,
                        "Physical changed campfire slot was not denied by the actual cook event");
                check(allowedNeighbor == 1,
                        "Denied campfire slot stalled or duplicated its valid neighbor");
                Campfire validState = (Campfire) valid.getState();
                Campfire changedState = (Campfire) changed.getState();
                check(!present(validState.getItem(0)) && present(changedState.getItem(0))
                                && !present(changedState.getItem(1)),
                        "Physical campfire pulse did not consume only the two valid slots");
                smoke.getLogger().info("RW_NATIVE_CAMPFIRE_PULSE_PASS");
                check(ownedDrop != null && ownedDrop.isValid(),
                        "Physical campfire did not carry owned recipe provenance to its drop"
                                + " (tagged cooks=" + taggedCooks + ", nearby diamond drops="
                                + nearbyDiamondDrops + ", temporary drop tokens="
                                + temporaryDropTokens + ")");
                check(committedOwned == 1,
                        "Physical campfire published a missing or duplicate postcommit event: "
                                + committedOwned);
                smoke.getLogger().info("RW_CAMPFIRE_POSTCOMMIT_EVENT_PASS");
                Item unmarked = ownedDrop.getWorld().dropItem(
                        ownedDrop.getLocation().clone().add(5, 0, 0),
                        ownedDrop.getItemStack().clone());
                try {
                    ItemMergeEvent ownedSource = new ItemMergeEvent(ownedDrop, unmarked);
                    Bukkit.getPluginManager().callEvent(ownedSource);
                    ItemMergeEvent ownedTarget = new ItemMergeEvent(unmarked, ownedDrop);
                    Bukkit.getPluginManager().callEvent(ownedTarget);
                    check(ownedSource.isCancelled() && ownedTarget.isCancelled(),
                            "Campfire provenance-bearing drop was allowed to merge away");
                    smoke.getLogger().info("RW_CAMPFIRE_DROP_MERGE_PASS");
                } finally {
                    unmarked.remove();
                }
                UUID playerId = UUID.randomUUID();
                Player pickup = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                        new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                            case "getUniqueId" -> playerId;
                            case "getName" -> "RecipeWeaverCampfirePickup";
                            case "hasDiscoveredRecipe" -> false;
                            default -> null;
                        });
                EntityPickupItemEvent pickupEvent = new EntityPickupItemEvent(pickup, ownedDrop, 0);
                Bukkit.getPluginManager().callEvent(pickupEvent);
                check(!pickupEvent.isCancelled(), "Synthetic campfire pickup was cancelled");
                check(!ownedDrop.getPersistentDataContainer().has(
                        new NamespacedKey("recipeweaver", "campfire_drop_recipe")),
                        "Campfire pickup did not consume the one-use entity marker");
                // A duplicate call waits for the pickup's write and reports false.
                // If pickup missed discovery, it would create a new write and report true.
                api.discoverRecipe(pickup, firstKey).whenComplete((newDiscovery, failure) ->
                        Bukkit.getScheduler().runTask(smoke, () -> {
                            try {
                                check(failure == null && !newDiscovery
                                                && api.hasDiscoveredRecipe(pickup, firstKey),
                                        "Physical campfire pickup was not saved durably");
                                smoke.getLogger().info("RW_CAMPFIRE_PICKUP_DISCOVERY_PASS");
                                result.complete(null);
                            } catch (Throwable pickupFailure) {
                                result.completeExceptionally(pickupFailure);
                            } finally {
                                cleanup();
                            }
                        }));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
                cleanup();
            }
        }

        private void cleanup() {
            HandlerList.unregisterAll(this);
            if (previousValid != null) previousValid.update(true, false);
            if (previousChanged != null) previousChanged.update(true, false);
            if (validChunk != null && !validWasForceLoaded) validChunk.setForceLoaded(false);
            if (changedChunk != null && changedChunk != validChunk && !changedWasForceLoaded) {
                changedChunk.setForceLoaded(false);
            }
            if (ownedDrop != null && ownedDrop.isValid()) ownedDrop.remove();
            Bukkit.removeRecipe(secondKey);
        }

        private static boolean sameBlock(Block first, Block second) {
            return first != null && second != null && first.getWorld().equals(second.getWorld())
                    && first.getX() == second.getX() && first.getY() == second.getY()
                    && first.getZ() == second.getZ();
        }
    }

    private static void start(Block block, CookingRecipe<?> recipe, ItemStack input) {
        if (recipe instanceof CampfireRecipe campfire) {
            Bukkit.getPluginManager().callEvent(new CampfireStartEvent(block, input, campfire));
        } else {
            Bukkit.getPluginManager().callEvent(new FurnaceStartSmeltEvent(block, input, recipe));
        }
    }

    private static BlockCookEvent cook(Block block, ItemStack input, ItemStack result) {
        BlockCookEvent event = block.getType() == Material.CAMPFIRE
                ? new BlockCookEvent(block, input, result)
                : new FurnaceSmeltEvent(block, input, result);
        Bukkit.getPluginManager().callEvent(event);
        return event;
    }

    private static Block block(Material material, World world, int x) {
        return block(material, world, x, null);
    }

    private static Block block(Material material, World world, int x, BlockState state) {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(),
                new Class<?>[] {Block.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "getType" -> material;
                    case "getState" -> state;
                    case "getWorld" -> world;
                    case "getX" -> x;
                    case "getY" -> 72;
                    case "getZ" -> -301;
                    default -> throw new UnsupportedOperationException("Block." + method.getName());
                });
    }

    private static final class CampfireSlots {
        private final ItemStack[] items = new ItemStack[4];
        private final int[] cookTime = new int[4];
        private final int[] cookTimeTotal = new int[4];
        private final Campfire state = (Campfire) Proxy.newProxyInstance(Campfire.class.getClassLoader(),
                new Class<?>[] {Campfire.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "getSize" -> items.length;
                    case "getItem" -> items[(int) args[0]];
                    case "getCookTime" -> cookTime[(int) args[0]];
                    case "getCookTimeTotal" -> cookTimeTotal[(int) args[0]];
                    case "setCookTime" -> { cookTime[(int) args[0]] = (int) args[1]; yield null; }
                    case "setCookTimeTotal" -> { cookTimeTotal[(int) args[0]] = (int) args[1]; yield null; }
                    case "update" -> true;
                    default -> throw new UnsupportedOperationException("Campfire." + method.getName());
                });
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static boolean present(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }
}
