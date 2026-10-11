package dev.recipeweaver.smoke;

import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.WeatherCondition;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Crafter;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Pulses a real redstone powered crafter and checks input, output, and denial. */
final class NativeCrafterPhysicalSmoke implements Listener {
    private static final NamespacedKey VANILLA_STICK_KEY = NamespacedKey.minecraft("stick");
    private enum Phase { VALID, RESULT_DENIED, REMAINDER_DENIED, ENVIRONMENT_ALLOWED,
        WEATHER_DENIED, PLAYER_DENIED, DONE }

    private final JavaPlugin smoke;
    private final RecipeWeaverApi api;
    private final NamespacedKey key;
    private final NamespacedKey exactKey;
    private final CompletableFuture<Void> result = new CompletableFuture<>();
    private Block crafterBlock;
    private Block chestBlock;
    private Block powerBlock;
    private BlockState previousCrafter;
    private BlockState previousChest;
    private BlockState previousPower;
    private Chunk chunk;
    private boolean wasForceLoaded;
    private boolean placed;
    private boolean eventAvailable;
    private boolean remainderAvailable;
    private Phase phase = Phase.VALID;
    private RecipeDefinition original;
    private boolean mutatedRecipe;
    private boolean vanillaRecipeDisabled;
    private int readinessAttempts;
    private int validEvents;
    private int resultDeniedEvents;
    private int remainderDeniedEvents;
    private int environmentAllowedEvents;
    private int weatherDeniedEvents;
    private int playerDeniedEvents;
    private int allowedDeniedEvents;
    private int changedResults;
    private int injectedRemainders;

    private NativeCrafterPhysicalSmoke(JavaPlugin smoke, RecipeWeaverApi api,
                                      NamespacedKey key, NamespacedKey exactKey) {
        this.smoke = smoke;
        this.api = api;
        this.key = key;
        this.exactKey = exactKey;
    }

    static CompletableFuture<Void> start(JavaPlugin smoke, RecipeWeaverApi api,
                                         NamespacedKey key, NamespacedKey exactKey) {
        NativeCrafterPhysicalSmoke pulse = new NativeCrafterPhysicalSmoke(smoke, api, key, exactKey);
        pulse.prepare();
        return pulse.result;
    }

    private void prepare() {
        World world = Bukkit.getWorlds().get(0);
        Location spawn = world.getSpawnLocation();
        int y = world.getMaxHeight() - 16;
        crafterBlock = world.getBlockAt(spawn.getBlockX() + 40, y, spawn.getBlockZ() + 40);
        chestBlock = crafterBlock.getRelative(BlockFace.EAST);
        powerBlock = crafterBlock.getRelative(BlockFace.UP);
        previousCrafter = crafterBlock.getState();
        previousChest = chestBlock.getState();
        previousPower = powerBlock.getState();
        chunk = crafterBlock.getChunk();
        wasForceLoaded = chunk.isForceLoaded();
        try {
            check(previousCrafter.getType().isAir() && previousChest.getType().isAir()
                            && previousPower.getType().isAir(),
                    "Physical crafter probe site is occupied");
            placed = true;
            chunk.setForceLoaded(true);
            chestBlock.setType(Material.CHEST, false);
            crafterBlock.setType(Material.CRAFTER, false);
            org.bukkit.block.data.type.Crafter facing =
                    (org.bukkit.block.data.type.Crafter) crafterBlock.getBlockData();
            facing.setOrientation(org.bukkit.block.data.type.Crafter.Orientation.EAST_UP);
            crafterBlock.setBlockData(facing, false);
            registerCraftObservers();
            Bukkit.getScheduler().runTaskLater(smoke, this::armWhenReady, 50L);
        } catch (Throwable failure) {
            cleanup();
            throw new IllegalStateException("Could not prepare physical crafter pulse", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private void registerCraftObservers() throws ReflectiveOperationException {
        Class<?> eventType;
        try {
            eventType = Class.forName("org.bukkit.event.block.CrafterCraftEvent");
        } catch (ClassNotFoundException ignored) {
            // Spigot 1.20.6 has a functioning crafter but no cancellable craft event.
            return;
        }
        Method getBlock = eventType.getMethod("getBlock");
        Method getRecipe = eventType.getMethod("getRecipe");
        Method setResult = eventType.getMethod("setResult", ItemStack.class);
        Method getRemainingItems;
        try { getRemainingItems = eventType.getMethod("getRemainingItems"); }
        catch (NoSuchMethodException ignored) { getRemainingItems = null; }
        Method remainingItems = getRemainingItems;
        Class<? extends Event> type = (Class<? extends Event>) eventType;
        eventAvailable = true;
        remainderAvailable = remainingItems != null;
        Bukkit.getPluginManager().registerEvent(type, this, EventPriority.LOWEST,
                (listener, event) -> {
                    if (!selected(event, getBlock, getRecipe)) return;
                    try {
                        if (phase == Phase.RESULT_DENIED) {
                            setResult.invoke(event, new ItemStack(Material.DIAMOND));
                            changedResults++;
                        } else if (phase == Phase.REMAINDER_DENIED && remainingItems != null) {
                            @SuppressWarnings("unchecked")
                            java.util.List<ItemStack> values =
                                    (java.util.List<ItemStack>) remainingItems.invoke(event);
                            check(values.size() == 9 || values.size() == 2,
                                    "Physical crafter returned an unexpected remainder count");
                            values.set(0, new ItemStack(Material.DIAMOND, 64));
                            injectedRemainders++;
                        }
                    } catch (ReflectiveOperationException ex) {
                        throw new IllegalStateException("Could not change crafter event output", ex);
                    }
                }, smoke, true);
        Bukkit.getPluginManager().registerEvent(type, this, EventPriority.MONITOR,
                (listener, event) -> {
                    if (!selected(event, getBlock, getRecipe)) return;
                    if (phase == Phase.VALID && !((Cancellable) event).isCancelled()) validEvents++;
                    if (phase == Phase.ENVIRONMENT_ALLOWED
                            && !((Cancellable) event).isCancelled()) environmentAllowedEvents++;
                    if (phase == Phase.RESULT_DENIED || phase == Phase.REMAINDER_DENIED
                            || phase == Phase.WEATHER_DENIED
                            || phase == Phase.PLAYER_DENIED) {
                        if (((Cancellable) event).isCancelled()) {
                            switch (phase) {
                                case RESULT_DENIED -> resultDeniedEvents++;
                                case REMAINDER_DENIED -> remainderDeniedEvents++;
                                case WEATHER_DENIED -> weatherDeniedEvents++;
                                case PLAYER_DENIED -> playerDeniedEvents++;
                                default -> throw new IllegalStateException("Unexpected crafter phase");
                            }
                        } else allowedDeniedEvents++;
                    }
                }, smoke, false);
    }

    private boolean selected(Event event, Method getBlock, Method getRecipe) {
        try {
            Block block = (Block) getBlock.invoke(event);
            Object recipe = getRecipe.invoke(event);
            return block.getWorld().equals(crafterBlock.getWorld())
                    && block.getX() == crafterBlock.getX()
                    && block.getY() == crafterBlock.getY()
                    && block.getZ() == crafterBlock.getZ()
                    && recipe instanceof Keyed keyed && keyed.getKey().equals(key);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not inspect crafter craft event", ex);
        }
    }

    private void armWhenReady() {
        try {
            if (Bukkit.getRecipe(key) == null) {
                check(++readinessAttempts < 5,
                        "Physical crafter recipe was unavailable after reload");
                Bukkit.getScheduler().runTaskLater(smoke, this::armWhenReady, 5L);
                return;
            }
            original = api.recipe(key).orElseThrow();
            setIngredients();
            powerBlock.setType(Material.REDSTONE_BLOCK, true);
            Bukkit.getScheduler().runTaskLater(smoke, this::checkValid, 12L);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void setIngredients() {
        Crafter state = (Crafter) crafterBlock.getState();
        state.getInventory().clear();
        state.getInventory().setItem(0, new ItemStack(Material.STONE));
        state.getInventory().setItem(1, new ItemStack(Material.DIRT));
    }

    private void checkValid() {
        try {
            check(!present(input(0)) && !present(input(1)),
                    "Physical crafter did not consume exactly one of each ingredient");
            check(outputCount() == 1,
                    "Physical crafter did not transfer exactly one result into chest");
            if (eventAvailable) {
                check(validEvents == 1, "Physical crafter did not fire one allowed craft event");
                phase = Phase.RESULT_DENIED;
                powerBlock.setType(Material.AIR, true);
                setIngredients();
                Bukkit.getScheduler().runTaskLater(smoke, this::armDenied, 2L);
            } else {
                smoke.getLogger().info("RW_NATIVE_CRAFTER_PHYSICAL_UNSUPPORTED_API");
                check(api.recipe(exactKey).isPresent() && Bukkit.getRecipe(exactKey) == null,
                        "Unobservable crafter retained an exact-choice recipe");
                smoke.getLogger().info("RW_NATIVE_CRAFTER_1206_EXACT_PASS");
                phase = Phase.PLAYER_DENIED;
                saveConditions(new RecipeConditions("recipeweaver.smoke.crafter", null,
                        null, -1, -1, WeatherCondition.ANY, 0, null, 0, null));
                check(Bukkit.getRecipe(key) == null,
                        "Unobservable crafter recipe with a player condition stayed registered");
                pulse(this::checkNoEventDenied);
            }
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void checkNoEventDenied() {
        try {
            check(same(input(0), Material.STONE) && same(input(1), Material.DIRT),
                    "Unobservable crafter consumed protected recipe inputs: first="
                            + input(0) + ", second=" + input(1)
                            + ", chest=" + java.util.Arrays.toString(
                                    ((Chest) chestBlock.getState()).getInventory().getContents()));
            check(outputCount() == 1,
                    "Unobservable crafter emitted a protected result");
            smoke.getLogger().info("RW_NATIVE_CRAFTER_1206_DENIAL_PASS");
            armVanillaCacheProbe();
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void armVanillaCacheProbe() {
        check(!api.disabledVanillaRecipes().contains(VANILLA_STICK_KEY),
                "Vanilla stick recipe was already excluded before the cache probe");
        org.bukkit.inventory.Recipe stick = Bukkit.getRecipe(VANILLA_STICK_KEY);
        check(stick != null && stick.getResult().getType() == Material.STICK
                        && stick.getResult().getAmount() == 4,
                "Vanilla stick recipe was unavailable for the cache probe");
        ((Chest) chestBlock.getState()).getBlockInventory().clear();
        Crafter crafter = (Crafter) crafterBlock.getState();
        crafter.getInventory().clear();
        crafter.getInventory().setItem(0, new ItemStack(Material.OAK_PLANKS, 2));
        crafter.getInventory().setItem(3, new ItemStack(Material.OAK_PLANKS, 2));
        pulseVanilla(this::checkVanillaWarm);
    }

    private void checkVanillaWarm() {
        try {
            checkVanillaInputs();
            check(vanillaOutputCount() == 4,
                    "Physical crafter did not warm the vanilla stick recipe cache");
            vanillaRecipeDisabled = true;
            api.setVanillaRecipeDisabledAsync(VANILLA_STICK_KEY, true);
            check(api.disabledVanillaRecipes().contains(VANILLA_STICK_KEY)
                            && Bukkit.getRecipe(VANILLA_STICK_KEY) == null,
                    "Vanilla stick recipe was not excluded after the cache warmed");
            pulseVanilla(this::checkVanillaDenied);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void checkVanillaDenied() {
        try {
            checkVanillaInputs();
            check(vanillaOutputCount() == 4,
                    "Excluded warmed vanilla stick recipe produced another result");
            api.setVanillaRecipeDisabledAsync(VANILLA_STICK_KEY, false);
            check(!api.disabledVanillaRecipes().contains(VANILLA_STICK_KEY)
                            && Bukkit.getRecipe(VANILLA_STICK_KEY) != null,
                    "Vanilla stick recipe did not restore after the cache probe");
            vanillaRecipeDisabled = false;
            smoke.getLogger().info("RW_NATIVE_CRAFTER_1206_VANILLA_CACHE_DENIAL_PASS");
            smoke.getLogger().info("RW_NATIVE_CRAFTER_PHYSICAL_PASS");
            phase = Phase.DONE;
            cleanup();
            result.complete(null);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void pulseVanilla(Runnable verify) {
        powerBlock.setType(Material.AIR, true);
        Bukkit.getScheduler().runTaskLater(smoke, () -> {
            try {
                powerBlock.setType(Material.REDSTONE_BLOCK, true);
                Bukkit.getScheduler().runTaskLater(smoke, verify, 12L);
            } catch (Throwable failure) {
                fail(failure);
            }
        }, 2L);
    }

    private void checkVanillaInputs() {
        check(same(input(0), Material.OAK_PLANKS)
                        && same(input(3), Material.OAK_PLANKS),
                "Vanilla cache probe consumed the wrong plank amounts");
        for (int slot = 0; slot < 9; slot++) {
            if (slot != 0 && slot != 3) {
                check(!present(input(slot)),
                        "Vanilla cache probe changed empty crafter slot " + slot);
            }
        }
    }

    private int vanillaOutputCount() {
        int amount = 0;
        for (ItemStack item : ((Chest) chestBlock.getState()).getBlockInventory().getContents()) {
            if (!present(item)) continue;
            check(item.getType() == Material.STICK,
                    "Vanilla cache probe produced an unexpected item: " + item);
            amount += item.getAmount();
        }
        for (var entity : crafterBlock.getWorld().getNearbyEntities(
                crafterBlock.getLocation().add(0.5, 0.5, 0.5), 3, 3, 3)) {
            check(!(entity instanceof org.bukkit.entity.Item drop
                            && drop.getItemStack().getType() == Material.STICK),
                    "Vanilla cache probe dropped sticks outside its output chest");
        }
        return amount;
    }

    private void armDenied() {
        try {
            powerBlock.setType(Material.REDSTONE_BLOCK, true);
            Bukkit.getScheduler().runTaskLater(smoke, this::checkResultDenied, 12L);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void checkResultDenied() {
        try {
            check(changedResults == 1 && resultDeniedEvents == 1 && allowedDeniedEvents == 0,
                    "Physical crafter changed result was not denied exactly once");
            check(same(input(0), Material.STONE) && same(input(1), Material.DIRT)
                            && outputCount() == 1,
                    "Denied physical crafter consumed input or duplicated output");
            if (remainderAvailable) {
                phase = Phase.REMAINDER_DENIED;
                pulse(this::checkRemainderDenied);
            } else {
                smoke.getLogger().info("RW_NATIVE_CRAFTER_REMAINDER_UNSUPPORTED_API");
                armEnvironmentAllowed();
            }
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void checkRemainderDenied() {
        try {
            check(injectedRemainders == 1 && remainderDeniedEvents == 1
                            && allowedDeniedEvents == 0,
                    "Physical crafter accepted a forged 64-item remainder");
            check(same(input(0), Material.STONE) && same(input(1), Material.DIRT)
                            && outputCount() == 1,
                    "Remainder-denied crafter consumed input or duplicated output");
            smoke.getLogger().info("RW_NATIVE_CRAFTER_REMAINDER_GUARD_PASS");
            armEnvironmentAllowed();
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void armEnvironmentAllowed() {
        try {
            phase = Phase.ENVIRONMENT_ALLOWED;
            saveConditions(matchingEnvironment());
            pulse(this::checkEnvironmentAllowed);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void checkEnvironmentAllowed() {
        try {
            check(environmentAllowedEvents == 1 && allowedDeniedEvents == 0,
                    "Matching crafter world, biome, and time conditions denied a real craft");
            check(!present(input(0)) && !present(input(1)) && outputCount() == 2,
                    "Environment-allowed crafter did not consume exactly one input pair");
            smoke.getLogger().info("RW_NATIVE_CRAFTER_ENVIRONMENT_PASS");
            phase = Phase.WEATHER_DENIED;
            World world = crafterBlock.getWorld();
            WeatherCondition wrongWeather = world.hasStorm()
                    ? WeatherCondition.CLEAR : WeatherCondition.RAIN;
            saveConditions(withWeather(matchingEnvironment(), wrongWeather));
            pulse(this::checkWeatherDenied);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void checkWeatherDenied() {
        try {
            check(weatherDeniedEvents == 1 && allowedDeniedEvents == 0,
                    "Weather-mismatched physical crafter was not denied");
            check(same(input(0), Material.STONE) && same(input(1), Material.DIRT)
                            && outputCount() == 2,
                    "Weather-denied crafter consumed input or duplicated output");
            smoke.getLogger().info("RW_NATIVE_CRAFTER_WEATHER_GUARD_PASS");
            phase = Phase.PLAYER_DENIED;
            saveConditions(new RecipeConditions("recipeweaver.smoke.crafter", null,
                    null, -1, -1, WeatherCondition.ANY, 0, null, 0, null));
            pulse(this::finish);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void finish() {
        try {
            check(playerDeniedEvents == 1 && allowedDeniedEvents == 0,
                    "Physical crafter accepted a player-only permission rule");
            check(same(input(0), Material.STONE) && same(input(1), Material.DIRT)
                            && outputCount() == 2,
                    "Player-rule-denied crafter consumed input or duplicated output");
            smoke.getLogger().info("RW_NATIVE_CRAFTER_PLAYER_RULE_GUARD_PASS");
            smoke.getLogger().info("RW_NATIVE_CRAFTER_PHYSICAL_PASS");
            phase = Phase.DONE;
            cleanup();
            result.complete(null);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void pulse(Runnable verify) {
        powerBlock.setType(Material.AIR, true);
        setIngredients();
        Bukkit.getScheduler().runTaskLater(smoke, () -> {
            try {
                powerBlock.setType(Material.REDSTONE_BLOCK, true);
                Bukkit.getScheduler().runTaskLater(smoke, verify, 12L);
            } catch (Throwable failure) {
                fail(failure);
            }
        }, 2L);
    }

    private RecipeConditions matchingEnvironment() {
        World world = crafterBlock.getWorld();
        int time = (int) Math.floorMod(world.getTime(), 24000);
        return new RecipeConditions(null, world.getName(),
                crafterBlock.getBiome().getKey(),
                Math.floorMod(time - 100, 24000), Math.floorMod(time + 100, 24000),
                WeatherCondition.ANY, 0, null, 0, null);
    }

    private static RecipeConditions withWeather(RecipeConditions environment,
                                                WeatherCondition weather) {
        return new RecipeConditions(null, environment.world(), environment.biome(),
                environment.timeStart(), environment.timeEnd(), weather,
                0, null, 0, null);
    }

    private void saveConditions(RecipeConditions conditions) {
        api.saveRecipe(new RecipeDefinition(original.key(), original.kind(),
                original.ingredients(), original.result(), original.group(),
                original.enabled(), original.hidden(), original.matchModes(),
                original.alternatives(), original.itemTags(), original.bookCategory(),
                conditions));
        mutatedRecipe = true;
    }

    private ItemStack input(int slot) {
        return ((Crafter) crafterBlock.getState()).getInventory().getItem(slot);
    }

    private int outputCount() {
        int amount = 0;
        for (ItemStack item : ((Chest) chestBlock.getState()).getInventory().getContents()) {
            if (!present(item)) continue;
            check(item.getType() == api.recipe(key).orElseThrow().result().getType(),
                    "Physical crafter produced an unexpected item");
            amount += item.getAmount();
        }
        return amount;
    }

    private void fail(Throwable failure) {
        smoke.getLogger().severe("RW_NATIVE_CRAFTER_PHYSICAL_FAIL " + failure);
        failure.printStackTrace();
        result.completeExceptionally(failure);
        cleanup();
    }

    private void cleanup() {
        phase = Phase.DONE;
        HandlerList.unregisterAll(this);
        if (vanillaRecipeDisabled) {
            try { api.setVanillaRecipeDisabledAsync(VANILLA_STICK_KEY, false); }
            catch (RuntimeException ex) {
                smoke.getLogger().severe("Could not restore vanilla stick recipe after crafter probe: " + ex);
            }
            vanillaRecipeDisabled = false;
        }
        if (mutatedRecipe) {
            api.saveRecipe(original);
            mutatedRecipe = false;
        }
        if (placed) {
            powerBlock.setType(Material.AIR, true);
            previousCrafter.update(true, false);
            previousChest.update(true, false);
            previousPower.update(true, false);
            if (!wasForceLoaded) chunk.setForceLoaded(false);
        }
    }

    private static boolean same(ItemStack item, Material material) {
        return present(item) && item.getType() == material && item.getAmount() == 1;
    }

    private static boolean present(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
