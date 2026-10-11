package dev.recipeweaver.smoke;

import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.ResultMetadataMode;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationCraftBatch;
import dev.recipeweaver.api.StationCraftCompleteEvent;
import dev.recipeweaver.api.StationRecipeDefinition;
import dev.recipeweaver.api.WeatherCondition;
import dev.recipeweaver.api.WeightedResult;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.recipe.CookingBookCategory;
import org.bukkit.potion.PotionType;

/** Real-server station transactions using a synthetic inventory holder where no client is needed. */
final class StationSmoke {
    private StationSmoke() { }

    static CompletionStage<Void> cauldronAsync(RecipeWeaverApi api, World world,
                                              JavaPlugin harness) {
        try {
            NamespacedKey key = new NamespacedKey("recipeweaver", "smoke_cauldron");
            api.saveStationRecipe(new StationRecipeDefinition(key, StationKind.CAULDRON,
                    List.of(new ItemStack(Material.STONE), new ItemStack(Material.DIRT)),
                    new ItemStack(Material.EMERALD), "smoke", 20, 0, 0, true, false,
                    List.of(IngredientMatchMode.EXACT, IngredientMatchMode.EXACT),
                    List.of(List.of(new ItemStack(Material.OAK_PLANKS)), List.of()),
                    Arrays.asList(null, null)));
            Block block = testBlock(world, 4);
            block.setType(Material.WATER_CAULDRON);
            Levelled full = (Levelled) block.getBlockData();
            full.setLevel(Math.min(3, full.getMaximumLevel()));
            block.setBlockData(full);
            int oldLevel = ((Levelled) block.getBlockData()).getLevel();
            FakePlayer fake = new FakePlayer(world, block.getLocation());
            boolean priorPending = interaction(fake.player, block, null);
            Bukkit.getLogger().info("RW_CAULDRON_PRIOR_SAVED=" + priorPending);
            CompletableFuture<Void> retrieved = priorPending
                    ? awaitCauldronIdle(api, harness, 200)
                    : CompletableFuture.completedFuture(null);
            return retrieved.thenRun(() -> {
                if (priorPending) {
                    check(fake.storage[0] != null && fake.storage[0].getType() == Material.STONE,
                            "Pending cauldron item did not survive restart");
                }
                fake.storage[0] = new ItemStack(Material.OAK_PLANKS);
                fake.storage[1] = new ItemStack(Material.DIRT);
                interact(fake.player, block, fake.storage[0]);
                check(fake.storage[0] == null, "Cauldron first input was not consumed");
                check(cauldronBusy(api), "Cauldron first save did not start");
            }).thenCompose(ignored -> awaitCauldronIdle(api, harness, 200))
                    .thenCompose(ignored -> api.reloadDataAsync().toCompletableFuture())
                    .thenRun(() -> {
                        fake.held = 1;
                        interact(fake.player, block, fake.storage[1]);
                        check(fake.storage[1] == null, "Cauldron second input was not consumed");
                        check(cauldronBusy(api), "Cauldron result deletion did not start");
                    }).thenCompose(ignored -> awaitCauldronIdle(api, harness, 200))
                    .thenRun(() -> {
                        check(fake.storage[0] != null && fake.storage[0].getType() == Material.EMERALD,
                                "Cauldron result was not delivered");
                        check(block.getType() == Material.WATER_CAULDRON
                                        && ((Levelled) block.getBlockData()).getLevel() == oldLevel - 1,
                                "Cauldron water was not consumed");
                        fake.storage[2] = new ItemStack(Material.STONE);
                        fake.held = 2;
                        interact(fake.player, block, fake.storage[2]);
                        check(fake.storage[2] == null,
                                "Pending cauldron item was not consumed for restart");
                        check(cauldronBusy(api), "Cauldron restart save did not start");
                    }).thenCompose(ignored -> awaitCauldronIdle(api, harness, 200))
                    .thenRun(() -> check(cauldronPending(api, block.getLocation()),
                            "Pending cauldron item was not persisted for restart"));
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    static CompletionStage<Void> cauldronConditionsAsync(RecipeWeaverApi api, World world,
                                                        JavaPlugin harness) {
        try {
            NamespacedKey key = new NamespacedKey("recipeweaver", "smoke_conditional_cauldron");
            RecipeConditions conditions = new RecipeConditions("recipeweaver.smoke.cauldron",
                    null, null, -1, -1, WeatherCondition.ANY, 0, null, 0, null);
            api.saveStationRecipe(new StationRecipeDefinition(key, StationKind.CAULDRON,
                    List.of(new ItemStack(Material.COBBLESTONE), new ItemStack(Material.GRAVEL)),
                    new ItemStack(Material.LAPIS_LAZULI), "smoke", 20, 0, 0, true, false,
                    List.of(IngredientMatchMode.EXACT, IngredientMatchMode.EXACT),
                    List.of(List.of(), List.of()), Arrays.asList(null, null),
                    org.bukkit.inventory.recipe.CookingBookCategory.MISC, 0, 0, conditions));
            check(cauldronConditionsSaved(api), "Conditional cauldron recipe was not registered");
            Block block = testBlock(world, 15);
            block.setType(Material.WATER_CAULDRON);
            Levelled full = (Levelled) block.getBlockData();
            full.setLevel(Math.min(3, full.getMaximumLevel()));
            block.setBlockData(full);
            int originalWater = ((Levelled) block.getBlockData()).getLevel();
            FakePlayer fake = new FakePlayer(world, block.getLocation());
            fake.storage[0] = new ItemStack(Material.COBBLESTONE);
            fake.storage[1] = new ItemStack(Material.GRAVEL);
            check(!interaction(fake.player, block, fake.storage[0])
                            && count(fake.storage, Material.COBBLESTONE) == 1
                            && !cauldronPending(api, block.getLocation(), Material.COBBLESTONE),
                    "Cauldron start without permission consumed the first ingredient");
            fake.permitted.set(true);
            interact(fake.player, block, fake.storage[0]);
            check(count(fake.storage, Material.COBBLESTONE) == 0 && cauldronBusy(api),
                    "Allowed cauldron start did not persist its first ingredient");
            return awaitCauldronIdle(api, harness, 200).thenRun(() -> {
                fake.held = 1;
                fake.permitted.set(false);
                interact(fake.player, block, fake.storage[1]);
                check(count(fake.storage, Material.GRAVEL) == 1
                                && cauldronPending(api, block.getLocation(), Material.COBBLESTONE),
                        "Cauldron completion without permission consumed its second ingredient");
                fake.permitted.set(true);
                interact(fake.player, block, fake.storage[1]);
                check(count(fake.storage, Material.GRAVEL) == 0 && cauldronBusy(api),
                        "Allowed cauldron completion did not start its durable removal");
                fake.permitted.set(false);
            }).thenCompose(ignored -> awaitCauldronIdle(api, harness, 200)).thenRun(() -> {
                check(count(fake.storage, Material.COBBLESTONE) == 1
                                && count(fake.storage, Material.GRAVEL) == 1
                                && count(fake.storage, Material.LAPIS_LAZULI) == 0
                                && ((Levelled) block.getBlockData()).getLevel() == originalWater
                                && !cauldronPending(api, block.getLocation(), Material.COBBLESTONE),
                        "Permission loss during cauldron I/O failed to return both inputs");
                fake.held = 0;
                fake.permitted.set(true);
                interact(fake.player, block, fake.storage[0]);
                check(cauldronBusy(api), "Cauldron retry did not persist the first ingredient");
            }).thenCompose(ignored -> awaitCauldronIdle(api, harness, 200)).thenRun(() -> {
                fake.held = 1;
                interact(fake.player, block, fake.storage[1]);
                check(cauldronBusy(api), "Cauldron retry did not remove the pending ingredient");
            }).thenCompose(ignored -> awaitCauldronIdle(api, harness, 200)).thenRun(() -> {
                check(count(fake.storage, Material.COBBLESTONE) == 0
                                && count(fake.storage, Material.GRAVEL) == 0
                                && count(fake.storage, Material.LAPIS_LAZULI) == 1
                                && ((Levelled) block.getBlockData()).getLevel() == originalWater - 1,
                        "Allowed cauldron retry lost inputs, duplicated output, or used wrong water");
            }).thenCompose(ignored -> api.reloadDataAsync().toCompletableFuture())
                    .thenRun(() -> check(cauldronConditionsSaved(api),
                            "Conditional cauldron recipe did not survive application reload"));
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    static boolean cauldronConditionsSaved(RecipeWeaverApi api) {
        NamespacedKey key = new NamespacedKey("recipeweaver", "smoke_conditional_cauldron");
        return api.stationRecipe(key).map(recipe -> recipe.kind() == StationKind.CAULDRON
                && "recipeweaver.smoke.cauldron".equals(recipe.conditions().permission()))
                .orElse(false);
    }

    static CompletionStage<Void> cauldronWeightedAsync(RecipeWeaverApi api, World world,
                                                      JavaPlugin harness) {
        try {
            NamespacedKey key = new NamespacedKey("recipeweaver", "smoke_weighted_cauldron");
            RecipeConditions conditions = new RecipeConditions("recipeweaver.smoke.weighted_cauldron",
                    null, null, -1, -1, WeatherCondition.ANY, 0, null, 0, null);
            api.saveStationRecipe(new StationRecipeDefinition(key, StationKind.CAULDRON,
                    List.of(new ItemStack(Material.FLINT), new ItemStack(Material.BONE_MEAL)),
                    new ItemStack(Material.GOLD_NUGGET), "smoke", 20, 0, 0, true, false,
                    List.of(IngredientMatchMode.EXACT, IngredientMatchMode.EXACT),
                    List.of(List.of(), List.of()), Arrays.asList(null, null),
                    org.bukkit.inventory.recipe.CookingBookCategory.MISC, 0, 0, conditions,
                    null, null, null, -1, ResultMetadataMode.NONE,
                    List.of(true, true), Arrays.asList(null, null), List.of(false, false),
                    List.of(new WeightedResult(new ItemStack(Material.GOLD_NUGGET), 2),
                            new WeightedResult(new ItemStack(Material.DIAMOND), 1))));
            check(cauldronWeightedSaved(api), "Weighted cauldron recipe was not registered");
            Block block = testBlock(world, 11);
            block.setType(Material.WATER_CAULDRON);
            Levelled full = (Levelled) block.getBlockData();
            full.setLevel(Math.min(3, full.getMaximumLevel()));
            block.setBlockData(full);
            int originalWater = ((Levelled) block.getBlockData()).getLevel();
            FakePlayer fake = new FakePlayer(world, block.getLocation());
            fake.permitted.set(true);
            fake.storage[0] = new ItemStack(Material.FLINT);
            fake.storage[1] = new ItemStack(Material.BONE_MEAL);
            interact(fake.player, block, fake.storage[0]);
            check(cauldronBusy(api), "Weighted cauldron first ingredient was not saved");
            return awaitCauldronIdle(api, harness, 200).thenRun(() -> {
                fake.held = 1;
                interact(fake.player, block, fake.storage[1]);
                check(cauldronBusy(api), "Weighted cauldron completion did not start removal");
                fake.permitted.set(false);
            }).thenCompose(ignored -> awaitCauldronIdle(api, harness, 200)).thenRun(() -> {
                check(count(fake.storage, Material.FLINT) == 1
                                && count(fake.storage, Material.BONE_MEAL) == 1
                                && weightedCauldronOutputCount(fake.storage) == 0
                                && ((Levelled) block.getBlockData()).getLevel() == originalWater
                                && !cauldronPending(api, block.getLocation(), Material.FLINT),
                        "Denied weighted cauldron attempt delivered an outcome or lost an input");
                fake.permitted.set(true);
                fake.held = 0;
                interact(fake.player, block, fake.storage[0]);
                check(cauldronBusy(api), "Weighted cauldron retry did not persist first input");
            }).thenCompose(ignored -> awaitCauldronIdle(api, harness, 200)).thenRun(() -> {
                fake.held = 1;
                interact(fake.player, block, fake.storage[1]);
                check(cauldronBusy(api), "Weighted cauldron retry did not remove first input");
            }).thenCompose(ignored -> awaitCauldronIdle(api, harness, 200)).thenRun(() -> {
                check(count(fake.storage, Material.FLINT) == 0
                                && count(fake.storage, Material.BONE_MEAL) == 0
                                && weightedCauldronOutputCount(fake.storage) == 1
                                && ((Levelled) block.getBlockData()).getLevel() == originalWater - 1,
                        "Weighted cauldron retry duplicated output or consumed the wrong resources");
            }).thenCompose(ignored -> api.reloadDataAsync().toCompletableFuture())
                    .thenRun(() -> check(cauldronWeightedSaved(api),
                            "Weighted cauldron choices did not survive application reload"));
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    static boolean cauldronWeightedSaved(RecipeWeaverApi api) {
        NamespacedKey key = new NamespacedKey("recipeweaver", "smoke_weighted_cauldron");
        return api.stationRecipe(key).map(recipe -> recipe.kind() == StationKind.CAULDRON
                && recipe.resultChoices().size() == 2
                && recipe.resultChoices().get(0).weight() == 2
                && recipe.resultChoices().get(1).weight() == 1).orElse(false);
    }

    static CompletionStage<Void> cauldronExtensionsAsync(RecipeWeaverApi api, World world,
                                                         JavaPlugin harness) {
        NamespacedKey key = new NamespacedKey("recipeweaver", "smoke_cauldron_extensions");
        Listener listener = new Listener() { };
        try {
            api.saveStationRecipe(new StationRecipeDefinition(key, StationKind.CAULDRON,
                    List.of(new ItemStack(Material.CHARCOAL), new ItemStack(Material.FLINT)),
                    new ItemStack(Material.REDSTONE), "smoke", 20, 0, 0, true, false));
            Block block = testBlock(world, 17);
            block.setType(Material.WATER_CAULDRON);
            Levelled full = (Levelled) block.getBlockData();
            full.setLevel(Math.min(3, full.getMaximumLevel()));
            block.setBlockData(full);
            int originalWater = ((Levelled) block.getBlockData()).getLevel();
            FakePlayer fake = new FakePlayer(world, block.getLocation());
            fake.storage[0] = new ItemStack(Material.CHARCOAL);
            fake.storage[1] = new ItemStack(Material.FLINT);
            AtomicBoolean permit = new AtomicBoolean(false);
            AtomicInteger conditionChecks = new AtomicInteger();
            AtomicInteger actionCalls = new AtomicInteger();
            AtomicInteger eventCalls = new AtomicInteger();
            AtomicReference<StationCraftBatch> actionBatch = new AtomicReference<>();
            AtomicReference<StationCraftBatch> eventBatch = new AtomicReference<>();
            AtomicBoolean actionAfterCommit = new AtomicBoolean();
            AtomicBoolean eventAfterCommit = new AtomicBoolean();
            api.registerStationCondition(harness, key, (player, recipe, inputs) -> {
                conditionChecks.incrementAndGet();
                boolean correct = player == fake.player && recipe.key().equals(key)
                        && inputs.size() == 2
                        && inputs.get(0).getType() == Material.CHARCOAL
                        && inputs.get(0).getAmount() == 1
                        && inputs.get(1).getType() == Material.FLINT
                        && inputs.get(1).getAmount() == 1;
                inputs.get(0).setType(Material.DIRT);
                return correct && permit.get();
            });
            api.registerStationAction(harness, key, (player, batch) -> {
                actionCalls.incrementAndGet();
                actionBatch.set(batch);
                actionAfterCommit.set(player == fake.player
                        && count(fake.storage, Material.REDSTONE) == 1
                        && block.getBlockData() instanceof Levelled level
                        && level.getLevel() == originalWater - 1);
            });
            Bukkit.getPluginManager().registerEvent(StationCraftCompleteEvent.class, listener,
                    EventPriority.NORMAL, (registered, fired) -> {
                        StationCraftCompleteEvent event = (StationCraftCompleteEvent) fired;
                        if (!event.batch().recipe().equals(key)) return;
                        eventCalls.incrementAndGet();
                        eventBatch.set(event.batch());
                        eventAfterCommit.set(event.player() == fake.player
                                && count(fake.storage, Material.REDSTONE) == 1
                                && block.getBlockData() instanceof Levelled level
                                && level.getLevel() == originalWater - 1);
                    }, harness);

            interact(fake.player, block, fake.storage[0]);
            check(cauldronBusy(api), "Cauldron extension first ingredient was not saved");
            CompletionStage<Void> flow = awaitCauldronIdle(api, harness, 200).thenRun(() -> {
                fake.held = 1;
                interact(fake.player, block, fake.storage[1]);
                check(count(fake.storage, Material.FLINT) == 1
                                && cauldronPending(api, block.getLocation(), Material.CHARCOAL)
                                && actionCalls.get() == 0 && eventCalls.get() == 0,
                        "Denied cauldron extension consumed input or published completion");
                permit.set(true);
                interact(fake.player, block, fake.storage[1]);
                check(cauldronBusy(api), "Allowed cauldron extension did not start removal");
                permit.set(false);
            }).thenCompose(ignored -> awaitCauldronIdle(api, harness, 200)).thenRun(() -> {
                check(count(fake.storage, Material.CHARCOAL) == 1
                                && count(fake.storage, Material.FLINT) == 1
                                && count(fake.storage, Material.REDSTONE) == 0
                                && ((Levelled) block.getBlockData()).getLevel() == originalWater
                                && actionCalls.get() == 0 && eventCalls.get() == 0,
                        "Cauldron extension denial during I/O failed to return both inputs");
                fake.held = 0;
                permit.set(true);
                interact(fake.player, block, fake.storage[0]);
                check(cauldronBusy(api), "Cauldron extension retry did not save first input");
            }).thenCompose(ignored -> awaitCauldronIdle(api, harness, 200)).thenRun(() -> {
                fake.held = 1;
                interact(fake.player, block, fake.storage[1]);
                check(cauldronBusy(api), "Cauldron extension retry did not remove first input");
            }).thenCompose(ignored -> awaitCauldronIdle(api, harness, 200)).thenRun(() -> {
                check(conditionChecks.get() >= 4
                                && count(fake.storage, Material.CHARCOAL) == 0
                                && count(fake.storage, Material.FLINT) == 0
                                && count(fake.storage, Material.REDSTONE) == 1
                                && ((Levelled) block.getBlockData()).getLevel() == originalWater - 1,
                        "Allowed cauldron extension retry did not commit exactly one result");
                check(actionCalls.get() == 1 && eventCalls.get() == 1
                                && actionAfterCommit.get() && eventAfterCommit.get()
                                && actionBatch.get() != null && eventBatch.get() != null
                                && actionBatch.get().kind() == StationKind.CAULDRON
                                && eventBatch.get().kind() == StationKind.CAULDRON
                                && actionBatch.get().result().getType() == Material.REDSTONE
                                && eventBatch.get().result().getType() == Material.REDSTONE,
                        "Cauldron callbacks were not published once after committed delivery");
            });
            return flow.whenComplete((ignored, failure) -> {
                api.unregisterStationCondition(harness, key);
                api.unregisterStationAction(harness, key);
                HandlerList.unregisterAll(listener);
            });
        } catch (Throwable failure) {
            api.unregisterStationCondition(harness, key);
            api.unregisterStationAction(harness, key);
            HandlerList.unregisterAll(listener);
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static int weightedCauldronOutputCount(ItemStack[] contents) {
        return count(contents, Material.GOLD_NUGGET) + count(contents, Material.DIAMOND);
    }

    private static CompletableFuture<Void> awaitCauldronIdle(RecipeWeaverApi api,
                                                             JavaPlugin harness,
                                                             int ticksLeft) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskLater(harness, () -> {
            try {
                if (!cauldronBusy(api)) result.complete(null);
                else if (ticksLeft <= 1) result.completeExceptionally(
                        new IllegalStateException("Cauldron I/O did not finish within 200 ticks"));
                else awaitCauldronIdle(api, harness, ticksLeft - 1)
                        .whenComplete((ignored, failure) -> {
                            if (failure == null) result.complete(null);
                            else result.completeExceptionally(failure);
                        });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }, 1L);
        return result;
    }

    private static boolean cauldronBusy(RecipeWeaverApi api) {
        try {
            Object repository = cauldronRepository(api);
            var method = repository.getClass().getDeclaredMethod("pendingWrite");
            method.setAccessible(true);
            return (boolean) method.invoke(repository);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not inspect cauldron I/O state", ex);
        }
    }

    private static boolean cauldronPending(RecipeWeaverApi api, Location location) {
        return cauldronPending(api, location, Material.STONE);
    }

    private static boolean cauldronPending(RecipeWeaverApi api, Location location,
                                           Material expected) {
        try {
            Object repository = cauldronRepository(api);
            var method = repository.getClass().getDeclaredMethod("get", Location.class);
            method.setAccessible(true);
            ItemStack item = (ItemStack) method.invoke(repository, location);
            return item != null && item.getType() == expected;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not inspect saved cauldron ingredient", ex);
        }
    }

    private static Object cauldronRepository(RecipeWeaverApi api)
            throws ReflectiveOperationException {
        var field = api.getClass().getDeclaredField("cauldronRepository");
        field.setAccessible(true);
        return field.get(api);
    }

    static Runnable brewing(RecipeWeaverApi api, World world, Player discoveryPlayer) {
        ItemStack base = new ItemStack(Material.POTION);
        ItemStack result = new ItemStack(Material.POTION);
        ItemMeta meta = result.getItemMeta();
        meta.setDisplayName("RecipeWeaver smoke brew");
        result.setItemMeta(meta);
        NamespacedKey key = new NamespacedKey("recipeweaver", "smoke_brewing");
        api.saveStationRecipe(new StationRecipeDefinition(key, StationKind.BREWING,
                List.of(new ItemStack(Material.DIRT), base), result, "smoke",
                20, 0, 0, true, false,
                List.of(IngredientMatchMode.MATERIAL, IngredientMatchMode.EXACT),
                List.of(List.of(new ItemStack(Material.REDSTONE)), List.of()),
                Arrays.asList(NamespacedKey.fromString("minecraft:planks"), null)));
        Block block = testBlock(world, 5);
        block.getChunk().setForceLoaded(true);
        block.setType(Material.BREWING_STAND);
        BrewingStand stand = (BrewingStand) block.getState();
        stand.setFuelLevel(1);
        check(stand.update(true, false), "Could not set brewing fuel");
        BrewerInventory inventory = ((BrewingStand) block.getState()).getInventory();
        inventory.setIngredient(new ItemStack(Material.BIRCH_PLANKS));
        inventory.setItem(0, base.clone());
        inventory.setItem(1, base.clone());
        inventory.setItem(2, base.clone());
        Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(block, block.getState(), block, new ItemStack(Material.BREWING_STAND), new FakePlayer(world, block.getLocation()).player, true, EquipmentSlot.HAND));

        return () -> {
            BrewingStand current = (BrewingStand) block.getState();
            BrewerInventory contents = current.getInventory();
            ItemStack brewed = contents.getItem(0);
            check(brewed != null && brewed.isSimilar(result),
                    "Custom brewing result missing: result=" + brewed
                    + " ingredient=" + contents.getIngredient()
                    + " fuel=" + current.getFuelLevel()
                    + " time=" + current.getBrewingTime());
            check(result.isSimilar(contents.getItem(1))
                            && result.isSimilar(contents.getItem(2)),
                    "Custom brewing did not commit all three independent bottles");
            check(contents.getIngredient() == null, "Custom brewing ingredient was not consumed");
            check(current.getFuelLevel() == 0, "Custom brewing fuel was not consumed");
            UUID freshId = UUID.randomUUID();
            Player fresh = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                    new Class<?>[] {Player.class}, (proxy, method, args) ->
                            switch (method.getName()) {
                                case "getUniqueId" -> freshId;
                                case "getName" -> "RecipeWeaverBrewSlotSmoke";
                                case "hasDiscoveredRecipe" -> false;
                                default -> null;
                            });
            check(!api.hasDiscoveredRecipe(fresh, key),
                    "Fresh bottle-take player already knew the custom brew");
            StationDiscoverySmoke.fireBrewingSlotChange(fresh, contents, 1);
            contents.setItem(1, new ItemStack(Material.GLASS_BOTTLE));
            StationDiscoverySmoke.fireBrewingTake(fresh, contents, 0);
            StationDiscoverySmoke.fireBrewingTake(discoveryPlayer, contents, 2);
            SmokePlugin.reloadForLegacyAssertions(api);
            check(api.hasDiscoveredRecipe(fresh, key),
                    "Changing a neighboring bottle erased the untouched brew discovery");
            check(api.hasDiscoveredRecipe(discoveryPlayer, key),
                    "Taking a finished custom brew did not discover its recipe");
            Bukkit.getLogger().info("RW_BREW_DISCOVERY_SLOT_RETAIN_PASS");
        };
    }

    static CompletionStage<Void> brewingRecipeChangeAsync(RecipeWeaverApi api, World world,
                                                          JavaPlugin harness) {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        try {
            NamespacedKey primaryKey = new NamespacedKey("recipeweaver", "smoke_brew_job_primary");
            NamespacedKey secondaryKey = new NamespacedKey("recipeweaver", "smoke_brew_job_secondary");
            ItemStack primaryResult = namedPotion(Material.POTION, "Primary job result");
            ItemStack priorSecondary = namedPotion(Material.LINGERING_POTION, "Old secondary result");
            ItemStack nextSecondary = namedPotion(Material.POTION, "New secondary result");
            api.saveStationRecipe(brewJobRecipe(primaryKey, Material.POTION, primaryResult));
            api.saveStationRecipe(brewJobRecipe(secondaryKey, Material.SPLASH_POTION,
                    priorSecondary));
            Block block = testBlock(world, 9);
            block.getChunk().setForceLoaded(true);
            block.setType(Material.BREWING_STAND);
            BrewingStand stand = (BrewingStand) block.getState();
            stand.setFuelLevel(1);
            check(stand.update(true, false), "Could not set hot-swap brewing fuel");
            BrewerInventory inventory = ((BrewingStand) block.getState()).getInventory();
            inventory.setIngredient(new ItemStack(Material.CLAY_BALL));
            inventory.setItem(0, new ItemStack(Material.POTION));
            inventory.setItem(1, new ItemStack(Material.SPLASH_POTION));
            Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(block, block.getState(), block,
                    new ItemStack(Material.BREWING_STAND),
                    new FakePlayer(world, block.getLocation()).player, true, EquipmentSlot.HAND));
            Bukkit.getScheduler().runTaskLater(harness, () -> {
                if (completed.isDone()) return;
                try {
                    api.saveStationRecipe(brewJobRecipe(secondaryKey,
                            Material.SPLASH_POTION, nextSecondary));
                } catch (Throwable failure) { completed.completeExceptionally(failure); }
            }, 25L);
            Bukkit.getScheduler().runTaskLater(harness, () -> {
                if (completed.isDone()) return;
                try {
                    BrewingStand current = (BrewingStand) block.getState();
                    BrewerInventory contents = current.getInventory();
                    check(contents.getIngredient() != null
                                    && contents.getIngredient().getType() == Material.CLAY_BALL
                                    && current.getFuelLevel() == 1
                                    && contents.getItem(0).getType() == Material.POTION
                                    && contents.getItem(1).getType() == Material.SPLASH_POTION,
                            "Brewing job finished on the old timer after a second-slot recipe changed");
                } catch (Throwable failure) { completed.completeExceptionally(failure); }
            }, 64L);
            Bukkit.getScheduler().runTaskLater(harness, () -> {
                if (completed.isDone()) return;
                try {
                    BrewingStand current = (BrewingStand) block.getState();
                    BrewerInventory contents = current.getInventory();
                    check(contents.getIngredient() == null && current.getFuelLevel() == 0
                                    && primaryResult.isSimilar(contents.getItem(0))
                                    && nextSecondary.isSimilar(contents.getItem(1)),
                            "Restarted brewing job lost an input or committed an outdated result");
                    completed.complete(null);
                } catch (Throwable failure) { completed.completeExceptionally(failure); }
            }, 95L);
        } catch (Throwable failure) {
            completed.completeExceptionally(failure);
        }
        return completed;
    }

    static CompletionStage<Void> brewingConditionsAsync(RecipeWeaverApi api, World world,
                                                       JavaPlugin harness) {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        try {
            NamespacedKey key = new NamespacedKey("recipeweaver", "smoke_condition_brewing");
            ItemStack result = namedPotion(Material.SPLASH_POTION, "Conditional brew result");
            api.saveStationRecipe(brewingConditionRecipe(key, result,
                    new RecipeConditions("recipeweaver.smoke.brewing", null, null,
                            -1, -1, WeatherCondition.ANY, 0, null, 0, null)));
            Block block = testBlock(world, 10);
            block.getChunk().setForceLoaded(true);
            block.setType(Material.BREWING_STAND);
            BrewingStand stand = (BrewingStand) block.getState();
            stand.setFuelLevel(1);
            check(stand.update(true, false), "Could not set conditional brewing fuel");
            BrewerInventory inventory = ((BrewingStand) block.getState()).getInventory();
            inventory.setIngredient(new ItemStack(Material.BRICK));
            inventory.setItem(0, new ItemStack(Material.POTION));
            Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(block, block.getState(), block,
                    new ItemStack(Material.BREWING_STAND),
                    new FakePlayer(world, block.getLocation()).player, true, EquipmentSlot.HAND));
            Bukkit.getScheduler().runTaskLater(harness, () -> {
                if (completed.isDone()) return;
                try {
                    check(unchangedBrew(block, Material.BRICK),
                            "Player-only brewing condition allowed automated ingredient use");
                    api.saveStationRecipe(brewingConditionRecipe(key, result,
                            new RecipeConditions(null, "recipeweaver_missing_world", null,
                                    -1, -1, WeatherCondition.ANY, 0, null, 0, null)));
                } catch (Throwable failure) { completed.completeExceptionally(failure); }
            }, 55L);
            Bukkit.getScheduler().runTaskLater(harness, () -> {
                if (completed.isDone()) return;
                try {
                    check(unchangedBrew(block, Material.BRICK),
                            "Wrong-world brewing condition allowed automated ingredient use");
                    api.saveStationRecipe(brewingConditionRecipe(key, result,
                            new RecipeConditions(null, world.getName(), null,
                                    -1, -1, WeatherCondition.ANY, 0, null, 0, null)));
                } catch (Throwable failure) { completed.completeExceptionally(failure); }
            }, 85L);
            Bukkit.getScheduler().runTaskLater(harness, () -> {
                if (completed.isDone()) return;
                try {
                    BrewingStand current = (BrewingStand) block.getState();
                    BrewerInventory contents = current.getInventory();
                    check(contents.getIngredient() == null && current.getFuelLevel() == 0
                                    && result.isSimilar(contents.getItem(0)),
                            "Matching brewing environment failed to commit one custom result");
                    api.reloadDataAsync().whenComplete((ignored, failure) -> {
                        if (failure != null) completed.completeExceptionally(failure);
                        else {
                            try {
                                check(brewingConditionsSaved(api, world.getName()),
                                        "Conditional brewing recipe did not survive application reload");
                                completed.complete(null);
                            } catch (Throwable checkFailure) {
                                completed.completeExceptionally(checkFailure);
                            }
                        }
                    });
                } catch (Throwable failure) { completed.completeExceptionally(failure); }
            }, 145L);
        } catch (Throwable failure) {
            completed.completeExceptionally(failure);
        }
        return completed;
    }

    static boolean brewingConditionsSaved(RecipeWeaverApi api, String worldName) {
        NamespacedKey key = new NamespacedKey("recipeweaver", "smoke_condition_brewing");
        return api.stationRecipe(key).map(recipe -> recipe.kind() == StationKind.BREWING
                && worldName.equals(recipe.conditions().world())
                && recipe.result().getType() == Material.SPLASH_POTION).orElse(false);
    }

    static CompletionStage<Void> brewingWeightedAsync(RecipeWeaverApi api, World world,
                                                      JavaPlugin harness) {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        try {
            NamespacedKey key = new NamespacedKey("recipeweaver", "smoke_weighted_brewing");
            ItemStack first = namedPotion(Material.POTION, "Weighted brew one");
            ItemStack second = namedPotion(Material.SPLASH_POTION, "Weighted brew two");
            api.saveStationRecipe(StationRecipeDefinition.withResults(key, StationKind.BREWING,
                    List.of(new ItemStack(Material.AMETHYST_SHARD), new ItemStack(Material.POTION)),
                    List.of(new WeightedResult(first, 1), new WeightedResult(second, 3)),
                    "smoke", 20, 0, 0, true, false));
            NamespacedKey nativeKey = new NamespacedKey("recipeweaver", "smoke_weighted_brewing_native");
            ItemStack nativeFirst = namedPotion(Material.LINGERING_POTION, "Native weighted brew one");
            ItemStack nativeSecond = namedPotion(Material.SPLASH_POTION, "Native weighted brew two");
            api.saveStationRecipe(StationRecipeDefinition.withResults(nativeKey, StationKind.BREWING,
                    List.of(new ItemStack(Material.NETHER_WART), new ItemStack(Material.POTION)),
                    List.of(new WeightedResult(nativeFirst, 2), new WeightedResult(nativeSecond, 1)),
                    "smoke", 20, 0, 0, true, false));
            check(brewingWeightedSaved(api), "Weighted brewing recipe was not registered");
            Block block = testBlock(world, 16);
            block.getChunk().setForceLoaded(true);
            block.setType(Material.BREWING_STAND);
            BrewingStand stand = (BrewingStand) block.getState();
            stand.setFuelLevel(1);
            check(stand.update(true, false), "Could not set weighted brewing fuel");
            BrewerInventory inventory = ((BrewingStand) block.getState()).getInventory();
            inventory.setIngredient(new ItemStack(Material.AMETHYST_SHARD, 2));
            inventory.setItem(0, new ItemStack(Material.POTION));
            inventory.setItem(1, new ItemStack(Material.POTION));
            inventory.setItem(2, new ItemStack(Material.SPLASH_POTION));
            Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(block, block.getState(), block,
                    new ItemStack(Material.BREWING_STAND),
                    new FakePlayer(world, block.getLocation()).player, true, EquipmentSlot.HAND));
            Block nativeBlock = testBlock(world, 17);
            nativeBlock.getChunk().setForceLoaded(true);
            nativeBlock.setType(Material.BREWING_STAND);
            BrewingStand nativeStand = (BrewingStand) nativeBlock.getState();
            nativeStand.setFuelLevel(1);
            check(nativeStand.update(true, false), "Could not set native weighted brewing fuel");
            BrewerInventory nativeInventory = ((BrewingStand) nativeBlock.getState()).getInventory();
            nativeInventory.setIngredient(new ItemStack(Material.NETHER_WART, 2));
            nativeInventory.setItem(0, new ItemStack(Material.POTION));
            nativeInventory.setItem(1, new ItemStack(Material.POTION));
            Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(nativeBlock,
                    nativeBlock.getState(), nativeBlock, new ItemStack(Material.BREWING_STAND),
                    new FakePlayer(world, nativeBlock.getLocation()).player, true,
                    EquipmentSlot.HAND));
            Bukkit.getScheduler().runTaskLater(harness, () -> {
                if (completed.isDone()) return;
                try {
                    BrewingStand current = (BrewingStand) block.getState();
                    BrewerInventory contents = current.getInventory();
                    ItemStack ingredient = contents.getIngredient();
                    check(ingredient != null && ingredient.getType() == Material.AMETHYST_SHARD
                                    && ingredient.getAmount() == 1 && current.getFuelLevel() == 0,
                            "Weighted brewing did not consume exactly one ingredient and fuel charge");
                    for (int slot = 0; slot < 2; slot++) {
                        ItemStack result = contents.getItem(slot);
                        check(result != null && result.getAmount() == 1
                                        && (result.isSimilar(first) || result.isSimilar(second)),
                                "Weighted brewing chose an invalid result in bottle slot " + slot);
                    }
                    check(new ItemStack(Material.SPLASH_POTION).isSimilar(contents.getItem(2)),
                            "Weighted brewing changed an unmatched bottle");
                    BrewingStand nativeCurrent = (BrewingStand) nativeBlock.getState();
                    BrewerInventory nativeContents = nativeCurrent.getInventory();
                    ItemStack nativeIngredient = nativeContents.getIngredient();
                    check(nativeIngredient != null && nativeIngredient.getType() == Material.NETHER_WART
                                    && nativeIngredient.getAmount() == 1
                                    && nativeCurrent.getFuelLevel() == 0,
                            "Native weighted brewing did not consume exactly one ingredient and fuel charge");
                    for (int slot = 0; slot < 2; slot++) {
                        ItemStack result = nativeContents.getItem(slot);
                        check(result != null && result.getAmount() == 1
                                        && (result.isSimilar(nativeFirst)
                                        || result.isSimilar(nativeSecond)),
                                "Native weighted brewing chose an invalid result in bottle slot " + slot);
                    }
                    api.reloadDataAsync().whenComplete((ignored, failure) -> {
                        if (failure != null) completed.completeExceptionally(failure);
                        else {
                            try {
                                check(brewingWeightedSaved(api),
                                        "Weighted brewing choices did not survive application reload");
                                completed.complete(null);
                            } catch (Throwable checkFailure) {
                                completed.completeExceptionally(checkFailure);
                            }
                        }
                    });
                } catch (Throwable failure) { completed.completeExceptionally(failure); }
            }, 75L);
        } catch (Throwable failure) {
            completed.completeExceptionally(failure);
        }
        return completed;
    }

    /** Pulses a native vanilla brew through the advanced, plugin-owned transaction path. */
    static CompletionStage<Void> brewingAdvancedRulesAsync(RecipeWeaverApi api, World world,
                                                           JavaPlugin harness) {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        Listener observer = new Listener() { };
        AtomicInteger nativeEvents = new AtomicInteger();
        AtomicBoolean uncancelledNativeEvent = new AtomicBoolean();
        try {
            NamespacedKey key = new NamespacedKey("recipeweaver", "smoke_advanced_brewing");
            ItemStack sourceBottle = namedPotion(Material.SPLASH_POTION, "Advanced brew source");
            PotionMeta sourceMeta = (PotionMeta) sourceBottle.getItemMeta();
            sourceMeta.setBasePotionType(PotionType.WATER);
            sourceBottle.setItemMeta(sourceMeta);
            api.saveStationRecipe(new StationRecipeDefinition(key, StationKind.BREWING,
                    List.of(new ItemStack(Material.DRAGON_BREATH, 2),
                            new ItemStack(Material.SPLASH_POTION)),
                    new ItemStack(Material.LINGERING_POTION), "smoke", 20, 0, 0,
                    true, false,
                    List.of(IngredientMatchMode.EXACT, IngredientMatchMode.MATERIAL),
                    List.of(List.of(), List.of()), Arrays.asList(null, null),
                    CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                    null, null, null, 1, ResultMetadataMode.NAME,
                    List.of(true, true),
                    Arrays.asList(new ItemStack(Material.AMETHYST_SHARD), null),
                    List.of(false, false),
                    List.of(new WeightedResult(new ItemStack(Material.LINGERING_POTION), 1),
                            new WeightedResult(new ItemStack(Material.POTION), 1))));
            Block block = testBlock(world, 22);
            block.getChunk().setForceLoaded(true);
            block.setType(Material.BREWING_STAND);
            Bukkit.getPluginManager().registerEvent(BrewEvent.class, observer,
                    EventPriority.MONITOR, (registered, fired) -> {
                        BrewEvent event = (BrewEvent) fired;
                        if (!sameBlock(event.getBlock(), block)) return;
                        nativeEvents.incrementAndGet();
                        if (!event.isCancelled()) uncancelledNativeEvent.set(true);
                    }, harness);
            BrewingStand stand = (BrewingStand) block.getState();
            stand.setFuelLevel(1);
            check(stand.update(true, false), "Could not set advanced brewing fuel");
            BrewerInventory inventory = ((BrewingStand) block.getState()).getInventory();
            inventory.setIngredient(new ItemStack(Material.DRAGON_BREATH, 2));
            inventory.setItem(0, sourceBottle);
            Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(block, block.getState(),
                    block, new ItemStack(Material.BREWING_STAND),
                    new FakePlayer(world, block.getLocation()).player, true,
                    EquipmentSlot.HAND));
            Bukkit.getScheduler().runTaskLater(harness, () -> {
                if (completed.isDone()) return;
                try {
                    BrewingStand current = (BrewingStand) block.getState();
                    BrewerInventory contents = current.getInventory();
                    ItemStack remainder = contents.getIngredient();
                    ItemStack result = contents.getItem(0);
                    check(nativeEvents.get() >= 1 && !uncancelledNativeEvent.get(),
                            "Advanced brew did not cancel a native BrewEvent for its block");
                    check(remainder != null && remainder.getType() == Material.AMETHYST_SHARD
                                    && remainder.getAmount() == 1 && current.getFuelLevel() == 0,
                            "Advanced native brew did not charge two shared ingredients once and return its item");
                    check(result != null && result.getAmount() == 1
                                    && (result.getType() == Material.LINGERING_POTION
                                    || result.getType() == Material.POTION)
                                    && result.getItemMeta().hasDisplayName()
                                    && "Advanced brew source".equals(
                                            result.getItemMeta().getDisplayName()),
                            "Advanced native brew did not copy bottle name after weighted selection");
                    api.reloadDataAsync().whenComplete((ignored, failure) -> {
                        if (failure != null) { completed.completeExceptionally(failure); return; }
                        try {
                            StationRecipeDefinition saved = api.stationRecipe(key).orElseThrow();
                            check(saved.ingredients().get(0).getAmount() == 2
                                            && saved.remainder(0).getType() == Material.AMETHYST_SHARD
                                            && saved.remainder(0).getAmount() == 1
                                            && saved.consumes(0) && saved.consumes(1)
                                            && saved.matchModes().get(1) == IngredientMatchMode.MATERIAL
                                            && saved.resultChoices().size() == 2
                                            && saved.resultChoices().get(0).item().getType()
                                                    == Material.LINGERING_POTION
                                            && saved.resultChoices().get(0).weight() == 1
                                            && saved.resultChoices().get(1).item().getType()
                                                    == Material.POTION
                                            && saved.resultChoices().get(1).weight() == 1
                                            && saved.resultMetadataMode() == ResultMetadataMode.NAME
                                            && saved.resultCopySourceSlot() == 1,
                                    "Advanced brewing controls did not survive reload");
                            completed.complete(null);
                        } catch (Throwable checkFailure) {
                            completed.completeExceptionally(checkFailure);
                        }
                    });
                } catch (Throwable failure) { completed.completeExceptionally(failure); }
            }, 75L);
        } catch (Throwable failure) { completed.completeExceptionally(failure); }
        return completed.whenComplete((ignored, failure) -> HandlerList.unregisterAll(observer));
    }

    static boolean brewingWeightedSaved(RecipeWeaverApi api) {
        NamespacedKey key = new NamespacedKey("recipeweaver", "smoke_weighted_brewing");
        NamespacedKey nativeKey = new NamespacedKey("recipeweaver", "smoke_weighted_brewing_native");
        return api.stationRecipe(key).map(recipe -> recipe.kind() == StationKind.BREWING
                && recipe.resultChoices().size() == 2
                && recipe.resultChoices().get(0).weight() == 1
                && recipe.resultChoices().get(1).weight() == 3
                && "Weighted brew one".equals(recipe.resultChoices().get(0).item()
                        .getItemMeta().getDisplayName())
                && "Weighted brew two".equals(recipe.resultChoices().get(1).item()
                        .getItemMeta().getDisplayName())).orElse(false)
                && api.stationRecipe(nativeKey).map(recipe -> recipe.kind() == StationKind.BREWING
                        && recipe.resultChoices().size() == 2
                        && recipe.resultChoices().get(0).weight() == 2
                        && recipe.resultChoices().get(1).weight() == 1
                        && "Native weighted brew one".equals(recipe.resultChoices().get(0).item()
                                .getItemMeta().getDisplayName())
                        && "Native weighted brew two".equals(recipe.resultChoices().get(1).item()
                                .getItemMeta().getDisplayName())).orElse(false);
    }

    static CompletionStage<Void> brewingExtensionsAsync(RecipeWeaverApi api, World world,
                                                        JavaPlugin harness) {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        NamespacedKey customKey = new NamespacedKey("recipeweaver", "smoke_brewing_extension_custom");
        NamespacedKey nativeKey = new NamespacedKey("recipeweaver", "smoke_brewing_extension_native");
        NamespacedKey deniedKey = new NamespacedKey("recipeweaver", "smoke_brewing_native_denied");
        boolean priorLockdown = api.lockedDown();
        Listener listener = new Listener() { };
        try {
            ItemStack customResult = namedPotion(Material.SPLASH_POTION, "Extension custom brew");
            ItemStack nativeResult = namedPotion(Material.LINGERING_POTION, "Extension native brew");
            ItemStack nativeBottle = new ItemStack(Material.SPLASH_POTION);
            PotionMeta nativePotion = (PotionMeta) nativeBottle.getItemMeta();
            nativePotion.setBasePotionType(PotionType.WATER);
            nativeBottle.setItemMeta(nativePotion);
            api.saveStationRecipe(new StationRecipeDefinition(customKey, StationKind.BREWING,
                    List.of(new ItemStack(Material.ECHO_SHARD), new ItemStack(Material.POTION)),
                    customResult, "smoke", 20, 0, 0, true, false));
            api.saveStationRecipe(new StationRecipeDefinition(nativeKey, StationKind.BREWING,
                    List.of(new ItemStack(Material.NETHER_WART),
                            nativeBottle.clone()),
                    nativeResult, "smoke", 20, 0, 0, true, false,
                    List.of(IngredientMatchMode.EXACT, IngredientMatchMode.MATERIAL),
                    List.of(List.of(), List.of()), Arrays.asList(null, null)));
            api.saveStationRecipe(nativeDeniedBrewRecipe(deniedKey,
                    new RecipeConditions("recipeweaver.smoke.brewing", null, null,
                            -1, -1, WeatherCondition.ANY, 0, null, 0, null)));
            Block customBlock = testBlock(world, 18);
            customBlock.getChunk().setForceLoaded(true);
            customBlock.setType(Material.BREWING_STAND);
            BrewingStand customStand = (BrewingStand) customBlock.getState();
            customStand.setFuelLevel(1);
            check(customStand.update(true, false), "Could not set extension custom brewing fuel");
            BrewerInventory customInventory = ((BrewingStand) customBlock.getState()).getInventory();
            customInventory.setIngredient(new ItemStack(Material.ECHO_SHARD, 2));
            customInventory.setItem(0, new ItemStack(Material.POTION));
            customInventory.setItem(1, new ItemStack(Material.POTION));
            customInventory.setItem(2, new ItemStack(Material.SPLASH_POTION));
            AtomicBoolean customAllowed = new AtomicBoolean(false);
            AtomicBoolean badInput = new AtomicBoolean();
            AtomicBoolean badCommit = new AtomicBoolean();
            AtomicInteger customChecks = new AtomicInteger();
            AtomicInteger customEvents = new AtomicInteger();
            AtomicInteger customActions = new AtomicInteger();
            AtomicInteger nativeEvents = new AtomicInteger();
            AtomicInteger nativeActions = new AtomicInteger();
            AtomicInteger nativeBrews = new AtomicInteger();
            AtomicInteger nativeChecks = new AtomicInteger();
            AtomicInteger cancelledBrews = new AtomicInteger();
            AtomicInteger deniedBrews = new AtomicInteger();
            AtomicInteger deniedBeforeLockdown = new AtomicInteger();
            AtomicInteger deniedEvents = new AtomicInteger();
            AtomicBoolean deniedUncancelled = new AtomicBoolean();
            api.registerStationCondition(harness, customKey, (player, recipe, inputs) -> {
                customChecks.incrementAndGet();
                boolean valid = player == null && recipe.key().equals(customKey)
                        && inputs.size() == 2 && inputs.get(0).getType() == Material.ECHO_SHARD
                        && inputs.get(0).getAmount() == 2
                        && inputs.get(1).getType() == Material.POTION;
                if (!valid) badInput.set(true);
                inputs.get(0).setType(Material.DIRT);
                return valid && customAllowed.get();
            });
            api.registerStationCondition(harness, nativeKey, (player, recipe, inputs) -> {
                nativeChecks.incrementAndGet();
                boolean valid = player == null && recipe.key().equals(nativeKey)
                        && inputs.size() == 2 && inputs.get(0).getType() == Material.NETHER_WART
                        && inputs.get(0).getAmount() == 2
                        && inputs.get(1).getType() == Material.SPLASH_POTION
                        && inputs.get(1).getItemMeta() instanceof PotionMeta bottleMeta
                        && bottleMeta.getBasePotionType() == PotionType.WATER;
                if (!valid) badInput.set(true);
                return valid;
            });
            api.registerStationAction(harness, customKey, (player, batch) -> {
                customActions.incrementAndGet();
                if (player != null || batch.kind() != StationKind.BREWING
                        || !batch.result().isSimilar(customResult)
                        || ((BrewingStand) customBlock.getState()).getFuelLevel() != 0) {
                    badCommit.set(true);
                }
            });
            Block nativeBlock = testBlock(world, 19);
            Block cancelledBlock = testBlock(world, 20);
            Block deniedBlock = testBlock(world, 21);
            api.registerStationAction(harness, nativeKey, (player, batch) -> {
                nativeActions.incrementAndGet();
                if (player != null || batch.kind() != StationKind.BREWING
                        || !batch.result().isSimilar(nativeResult)
                        || ((BrewingStand) nativeBlock.getState()).getFuelLevel() != 0) {
                    badCommit.set(true);
                }
            });
            Bukkit.getPluginManager().registerEvent(StationCraftCompleteEvent.class, listener,
                    EventPriority.NORMAL, (registered, fired) -> {
                        StationCraftCompleteEvent event = (StationCraftCompleteEvent) fired;
                        if (event.batch().recipe().equals(customKey)) {
                            customEvents.incrementAndGet();
                            if (event.player() != null
                                    || !event.batch().result().isSimilar(customResult)) {
                                badCommit.set(true);
                            }
                        } else if (event.batch().recipe().equals(nativeKey)) {
                            nativeEvents.incrementAndGet();
                            if (event.player() != null
                                    || !event.batch().result().isSimilar(nativeResult)) {
                                badCommit.set(true);
                            }
                        } else if (event.batch().recipe().equals(deniedKey)) {
                            deniedEvents.incrementAndGet();
                        }
                    }, harness);
            Bukkit.getPluginManager().registerEvent(BrewEvent.class, listener,
                    EventPriority.HIGHEST, (registered, fired) -> {
                        BrewEvent event = (BrewEvent) fired;
                        Block eventBlock = event.getBlock();
                        if (sameBlock(eventBlock, nativeBlock)) {
                            nativeBrews.incrementAndGet();
                            ItemStack eventIngredient = event.getContents().getIngredient();
                            ItemStack eventBottle = event.getContents().getItem(0);
                            StationRecipeDefinition saved = api.stationRecipe(nativeKey).orElse(null);
                            harness.getLogger().info("Brewing extension native event: ingredient="
                                    + eventIngredient + ", bottle=" + eventBottle
                                    + ", saved ingredient match=" + (saved != null
                                            && saved.matchesItemSample(0, eventIngredient))
                                    + ", saved bottle match=" + (saved != null
                                            && saved.matchesItemSample(1, eventBottle))
                                    + ", expected bottle similar=" + nativeBottle.isSimilar(eventBottle)
                                    + ", results=" + event.getResults());
                        }
                        if (sameBlock(eventBlock, deniedBlock)) {
                            deniedBrews.incrementAndGet();
                            if (!event.isCancelled()) deniedUncancelled.set(true);
                            return;
                        }
                        if (!sameBlock(eventBlock, cancelledBlock)) return;
                        cancelledBrews.incrementAndGet();
                        event.setCancelled(true);
                    }, harness);
            Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(customBlock,
                    customBlock.getState(), customBlock,
                    new ItemStack(Material.BREWING_STAND),
                    new FakePlayer(world, customBlock.getLocation()).player, true,
                    EquipmentSlot.HAND));
            Bukkit.getScheduler().runTaskLater(harness, () -> {
                if (completed.isDone()) return;
                try {
                    BrewingStand current = (BrewingStand) customBlock.getState();
                    BrewerInventory contents = current.getInventory();
                    check(customChecks.get() > 0 && !badInput.get()
                                    && current.getFuelLevel() == 1
                                    && contents.getIngredient() != null
                                    && contents.getIngredient().getType() == Material.ECHO_SHARD
                                    && contents.getIngredient().getAmount() == 2
                                    && contents.getItem(0).getType() == Material.POTION
                                    && contents.getItem(1).getType() == Material.POTION
                                    && customEvents.get() == 0 && customActions.get() == 0,
                            "Denied brewing extension consumed inputs or published completion");
                    customAllowed.set(true);
                } catch (Throwable failure) { completed.completeExceptionally(failure); }
            }, 55L);
            Bukkit.getScheduler().runTaskLater(harness, () -> {
                if (completed.isDone()) return;
                try {
                    BrewingStand current = (BrewingStand) customBlock.getState();
                    BrewerInventory contents = current.getInventory();
                    check(contents.getIngredient() != null
                                    && contents.getIngredient().getType() == Material.ECHO_SHARD
                                    && contents.getIngredient().getAmount() == 1
                                    && current.getFuelLevel() == 0
                                    && customResult.isSimilar(contents.getItem(0))
                                    && customResult.isSimilar(contents.getItem(1))
                                    && new ItemStack(Material.SPLASH_POTION)
                                            .isSimilar(contents.getItem(2))
                                    && customEvents.get() == 2 && customActions.get() == 2
                                    && !badInput.get() && !badCommit.get(),
                            "Custom brewing extension did not publish one batch per committed bottle");
                    nativeBlock.getChunk().setForceLoaded(true);
                    nativeBlock.setType(Material.BREWING_STAND);
                    BrewingStand nativeStand = (BrewingStand) nativeBlock.getState();
                    nativeStand.setFuelLevel(1);
                    check(nativeStand.update(true, false),
                            "Could not set extension native brewing fuel");
                    BrewerInventory nativeInventory = ((BrewingStand) nativeBlock.getState())
                            .getInventory();
                    nativeInventory.setIngredient(new ItemStack(Material.NETHER_WART, 2));
                    nativeInventory.setItem(0, nativeBottle.clone());
                    nativeInventory.setItem(1, nativeBottle.clone());
                    Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(nativeBlock,
                            nativeBlock.getState(), nativeBlock,
                            new ItemStack(Material.BREWING_STAND),
                            new FakePlayer(world, nativeBlock.getLocation()).player, true,
                            EquipmentSlot.HAND));
                    cancelledBlock.getChunk().setForceLoaded(true);
                    cancelledBlock.setType(Material.BREWING_STAND);
                    BrewingStand cancelledStand = (BrewingStand) cancelledBlock.getState();
                    cancelledStand.setFuelLevel(1);
                    check(cancelledStand.update(true, false),
                            "Could not set cancelled brewing fuel");
                    BrewerInventory cancelledInventory = ((BrewingStand) cancelledBlock.getState())
                            .getInventory();
                    cancelledInventory.setIngredient(new ItemStack(Material.NETHER_WART, 2));
                    cancelledInventory.setItem(0, nativeBottle.clone());
                    Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(cancelledBlock,
                            cancelledBlock.getState(), cancelledBlock,
                            new ItemStack(Material.BREWING_STAND),
                            new FakePlayer(world, cancelledBlock.getLocation()).player, true,
                            EquipmentSlot.HAND));
                    deniedBlock.getChunk().setForceLoaded(true);
                    deniedBlock.setType(Material.BREWING_STAND);
                    BrewingStand deniedStand = (BrewingStand) deniedBlock.getState();
                    deniedStand.setFuelLevel(1);
                    check(deniedStand.update(true, false),
                            "Could not set denied native brewing fuel");
                    BrewerInventory deniedInventory = ((BrewingStand) deniedBlock.getState())
                            .getInventory();
                    deniedInventory.setIngredient(new ItemStack(Material.GUNPOWDER, 2));
                    deniedInventory.setItem(0, new ItemStack(Material.POTION));
                    Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(deniedBlock,
                            deniedBlock.getState(), deniedBlock,
                            new ItemStack(Material.BREWING_STAND),
                            new FakePlayer(world, deniedBlock.getLocation()).player, true,
                            EquipmentSlot.HAND));
                } catch (Throwable failure) { completed.completeExceptionally(failure); }
            }, 130L);
            Bukkit.getScheduler().runTaskLater(harness, () -> {
                if (completed.isDone()) return;
                try {
                    BrewingStand nativeRunning = (BrewingStand) nativeBlock.getState();
                    BrewingStand cancelledRunning = (BrewingStand) cancelledBlock.getState();
                    BrewingStand deniedRunning = (BrewingStand) deniedBlock.getState();
                    int nativeTime = nativeRunning.getBrewingTime();
                    int cancelledTime = cancelledRunning.getBrewingTime();
                    int deniedTime = deniedRunning.getBrewingTime();
                    harness.getLogger().info("Brewing extension native start times: "
                            + nativeTime + ", cancelled stand: " + cancelledTime
                            + ", denied stand: " + deniedTime);
                    check(nativeTime > 0 && cancelledTime > 0 && deniedTime > 0,
                            "Vanilla brewing did not start: native=" + nativeTime
                                    + ", cancelled=" + cancelledTime + ", denied=" + deniedTime);
                    nativeRunning.setBrewingTime(1);
                    cancelledRunning.setBrewingTime(1);
                    deniedRunning.setBrewingTime(1);
                    check(nativeRunning.update(true, false)
                                    && cancelledRunning.update(true, false)
                                    && deniedRunning.update(true, false),
                            "Could not shorten vanilla brewing for extension assertions");
                } catch (Throwable failure) { completed.completeExceptionally(failure); }
            }, 145L);
            Bukkit.getScheduler().runTaskLater(harness, () -> {
                if (completed.isDone()) return;
                try {
                    BrewingStand current = (BrewingStand) nativeBlock.getState();
                    BrewerInventory contents = current.getInventory();
                    BrewerInventory cancelledContents = ((BrewingStand) cancelledBlock.getState())
                            .getInventory();
                    BrewerInventory deniedContents = ((BrewingStand) deniedBlock.getState())
                            .getInventory();
                    ItemStack cancelledIngredient = cancelledContents.getIngredient();
                    ItemStack cancelledBottle = cancelledContents.getItem(0);
                    ItemStack deniedIngredient = deniedContents.getIngredient();
                    ItemStack deniedBottle = deniedContents.getItem(0);
                    check(contents.getIngredient() != null
                                    && contents.getIngredient().getType() == Material.NETHER_WART
                                    && contents.getIngredient().getAmount() == 1
                                    && current.getFuelLevel() == 0
                                    && matchingNamedPotion(nativeResult, contents.getItem(0))
                                    && matchingNamedPotion(nativeResult, contents.getItem(1))
                                    && nativeEvents.get() == 2 && nativeActions.get() == 2
                                    && nativeBrews.get() > 0
                                    && nativeChecks.get() > 0
                                    && customEvents.get() == 2 && customActions.get() == 2
                                    && cancelledBrews.get() > 0
                                    && cancelledIngredient != null
                                    && cancelledIngredient.getType() == Material.NETHER_WART
                                    && cancelledIngredient.getAmount() == 2
                                    && cancelledBottle != null
                                    && cancelledBottle.getType() == Material.SPLASH_POTION
                                    && cancelledBottle.getAmount() == 1
                                    && cancelledBottle.getItemMeta() instanceof PotionMeta cancelledMeta
                                    && cancelledMeta.getBasePotionType() == PotionType.WATER
                                    && deniedBrews.get() > 0 && !deniedUncancelled.get()
                                    && deniedEvents.get() == 0
                                    && deniedIngredient != null
                                    && deniedIngredient.getType() == Material.GUNPOWDER
                                    && deniedIngredient.getAmount() == 2
                                    && deniedBottle != null
                                    && deniedBottle.getType() == Material.POTION
                                    && deniedBottle.getAmount() == 1
                                    && !badInput.get() && !badCommit.get(),
                            "Native brewing extension lost a batch or published a cancelled brew"
                                    + ": ingredient=" + contents.getIngredient()
                                    + ", fuel=" + current.getFuelLevel()
                                    + ", brew time=" + current.getBrewingTime()
                                    + ", bottles=" + contents.getItem(0) + "," + contents.getItem(1)
                                    + ", native events/actions=" + nativeEvents.get()
                                    + "/" + nativeActions.get()
                                    + ", native BrewEvents=" + nativeBrews.get()
                                    + ", native condition checks=" + nativeChecks.get()
                                    + ", custom events/actions=" + customEvents.get()
                                    + "/" + customActions.get()
                                    + ", cancelled event count=" + cancelledBrews.get()
                                    + ", cancelled brew time=" + ((BrewingStand) cancelledBlock.getState())
                                            .getBrewingTime()
                                    + ", cancelled ingredient/bottle=" + cancelledIngredient
                                    + "/" + cancelledBottle
                                    + ", denied event/cancelled/completions=" + deniedBrews.get()
                                    + "/" + !deniedUncancelled.get() + "/" + deniedEvents.get()
                                    + ", denied ingredient/bottle=" + deniedIngredient
                                    + "/" + deniedBottle
                                    + ", bad input/commit=" + badInput.get() + "/" + badCommit.get());
                    harness.getLogger().info("RW_NATIVE_BREW_CONDITION_VETO_PASS");
                    api.saveStationRecipe(nativeDeniedBrewRecipe(deniedKey,
                            RecipeConditions.NONE));
                    check(api.stationRecipe(deniedKey).orElseThrow()
                                    .conditions().unrestricted(),
                            "Lockdown brewing probe still had a player-only condition");
                    deniedBeforeLockdown.set(deniedBrews.get());
                    api.setLockdownAsync(true);
                    check(api.lockedDown(), "Could not enable lockdown for native brewing probe");
                    deniedBlock.setType(Material.BREWING_STAND);
                    BrewingStand lockedStand = (BrewingStand) deniedBlock.getState();
                    lockedStand.setFuelLevel(1);
                    check(lockedStand.update(true, false),
                            "Could not reset lockdown brewing stand fuel");
                    BrewerInventory lockedInventory = ((BrewingStand) deniedBlock.getState())
                            .getInventory();
                    lockedInventory.setIngredient(new ItemStack(Material.GUNPOWDER, 2));
                    lockedInventory.setItem(0, new ItemStack(Material.POTION));
                    Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(deniedBlock,
                            deniedBlock.getState(), deniedBlock,
                            new ItemStack(Material.BREWING_STAND),
                            new FakePlayer(world, deniedBlock.getLocation()).player, true,
                            EquipmentSlot.HAND));
                } catch (Throwable failure) { completed.completeExceptionally(failure); }
            }, 215L);
            Bukkit.getScheduler().runTaskLater(harness, () -> {
                if (completed.isDone()) return;
                try {
                    BrewingStand lockedRunning = (BrewingStand) deniedBlock.getState();
                    check(lockedRunning.getBrewingTime() > 0,
                            "Vanilla brewing did not start under lockdown");
                    lockedRunning.setBrewingTime(1);
                    check(lockedRunning.update(true, false),
                            "Could not shorten lockdown brewing for veto assertion");
                } catch (Throwable failure) { completed.completeExceptionally(failure); }
            }, 230L);
            Bukkit.getScheduler().runTaskLater(harness, () -> {
                if (completed.isDone()) return;
                try {
                    BrewerInventory lockedContents = ((BrewingStand) deniedBlock.getState())
                            .getInventory();
                    ItemStack lockedIngredient = lockedContents.getIngredient();
                    ItemStack lockedBottle = lockedContents.getItem(0);
                    check(deniedBrews.get() > deniedBeforeLockdown.get()
                                    && !deniedUncancelled.get() && deniedEvents.get() == 0
                                    && lockedIngredient != null
                                    && lockedIngredient.getType() == Material.GUNPOWDER
                                    && lockedIngredient.getAmount() == 2
                                    && lockedBottle != null
                                    && lockedBottle.getType() == Material.POTION
                                    && lockedBottle.getAmount() == 1,
                            "Lockdown let a claimed native brew fall back to vanilla: events="
                                    + deniedBrews.get() + ", ingredient=" + lockedIngredient
                                    + ", bottle=" + lockedBottle);
                    harness.getLogger().info("RW_NATIVE_BREW_LOCKDOWN_VETO_PASS");
                    completed.complete(null);
                } catch (Throwable failure) { completed.completeExceptionally(failure); }
            }, 260L);
        } catch (Throwable failure) {
            completed.completeExceptionally(failure);
        }
        return completed.whenComplete((ignored, failure) -> {
            if (api.lockedDown() != priorLockdown) api.setLockdownAsync(priorLockdown);
            api.unregisterStationCondition(harness, customKey);
            api.unregisterStationCondition(harness, nativeKey);
            api.unregisterStationAction(harness, customKey);
            api.unregisterStationAction(harness, nativeKey);
            HandlerList.unregisterAll(listener);
        });
    }

    private static boolean unchangedBrew(Block block, Material ingredient) {
        BrewingStand stand = (BrewingStand) block.getState();
        BrewerInventory inventory = stand.getInventory();
        return stand.getFuelLevel() == 1
                && inventory.getIngredient() != null
                && inventory.getIngredient().getType() == ingredient
                && inventory.getItem(0) != null
                && inventory.getItem(0).getType() == Material.POTION;
    }

    private static StationRecipeDefinition brewingConditionRecipe(NamespacedKey key,
                                                                  ItemStack result,
                                                                  RecipeConditions conditions) {
        return new StationRecipeDefinition(key, StationKind.BREWING,
                List.of(new ItemStack(Material.BRICK), new ItemStack(Material.POTION)), result,
                "smoke", 20, 0, 0, true, false,
                List.of(IngredientMatchMode.EXACT, IngredientMatchMode.EXACT),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                org.bukkit.inventory.recipe.CookingBookCategory.MISC, 0, 0, conditions);
    }

    private static StationRecipeDefinition nativeDeniedBrewRecipe(NamespacedKey key,
                                                                  RecipeConditions conditions) {
        return new StationRecipeDefinition(key, StationKind.BREWING,
                List.of(new ItemStack(Material.GUNPOWDER), new ItemStack(Material.POTION)),
                namedPotion(Material.SPLASH_POTION, "Denied native brew"),
                "smoke", 20, 0, 0, true, false,
                List.of(IngredientMatchMode.EXACT, IngredientMatchMode.MATERIAL),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                org.bukkit.inventory.recipe.CookingBookCategory.MISC, 0, 0, conditions);
    }

    private static StationRecipeDefinition brewJobRecipe(NamespacedKey key, Material bottle,
                                                         ItemStack result) {
        return new StationRecipeDefinition(key, StationKind.BREWING,
                List.of(new ItemStack(Material.CLAY_BALL), new ItemStack(bottle)), result,
                "smoke", 40, 0, 0, true, false);
    }

    private static ItemStack namedPotion(Material material, String title) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(title);
        item.setItemMeta(meta);
        return item;
    }

    private static boolean sameBlock(Block left, Block right) {
        return left.getWorld().getUID().equals(right.getWorld().getUID())
                && left.getX() == right.getX() && left.getY() == right.getY()
                && left.getZ() == right.getZ();
    }

    private static boolean matchingNamedPotion(ItemStack expected, ItemStack actual) {
        if (actual == null || expected.getType() != actual.getType()
                || expected.getAmount() != actual.getAmount()
                || !(expected.getItemMeta() instanceof PotionMeta expectedMeta)
                || !(actual.getItemMeta() instanceof PotionMeta actualMeta)) return false;
        return java.util.Objects.equals(expectedMeta.getDisplayName(), actualMeta.getDisplayName())
                && expectedMeta.getBasePotionType() == actualMeta.getBasePotionType()
                && expectedMeta.getCustomEffects().equals(actualMeta.getCustomEffects())
                && java.util.Objects.equals(expectedMeta.getColor(), actualMeta.getColor());
    }

    private static Block testBlock(World world, int offset) {
        Location spawn = world.getSpawnLocation();
        return world.getBlockAt(spawn.getBlockX() + offset, spawn.getBlockY() + 10,
                spawn.getBlockZ() + offset);
    }

    private static void interact(Player player, Block block, ItemStack held) {
        check(interaction(player, block, held), "Custom cauldron interaction was not handled");
    }

    private static boolean interaction(Player player, Block block, ItemStack held) {
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                held, block, BlockFace.UP, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static int count(ItemStack[] contents, Material material) {
        int total = 0;
        for (ItemStack item : contents) if (item != null && item.getType() == material) {
            total += item.getAmount();
        }
        return total;
    }

    private static final class FakePlayer {
        private final ItemStack[] storage = new ItemStack[36];
        private int held;
        private final AtomicBoolean permitted = new AtomicBoolean();
        private final Player player;

        private FakePlayer(World world, Location location) {
            PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(
                    PlayerInventory.class.getClassLoader(), new Class<?>[] {PlayerInventory.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getStorageContents" -> Arrays.copyOf(storage, storage.length);
                        case "setStorageContents" -> {
                            ItemStack[] supplied = (ItemStack[]) args[0];
                            System.arraycopy(supplied, 0, storage, 0, storage.length);
                            yield null;
                        }
                        case "getHeldItemSlot" -> held;
                        case "getItemInMainHand" -> storage[held];
                        default -> throw new UnsupportedOperationException("PlayerInventory." + method.getName());
                    });
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getInventory" -> inventory;
                        case "getWorld" -> world;
                        case "getLocation" -> location;
                        case "getUniqueId" -> UUID.fromString("00000000-0000-0000-0000-000000000042");
                        case "getName" -> "RecipeWeaverSmoke";
                        case "hasPermission" -> permitted.get();
                        case "updateInventory", "sendMessage" -> null;
                        default -> throw new UnsupportedOperationException("Player." + method.getName());
                    });
        }
    }
}
