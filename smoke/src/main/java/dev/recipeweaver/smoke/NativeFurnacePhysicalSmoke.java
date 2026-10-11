package dev.recipeweaver.smoke;

import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.CraftCommandAction;
import dev.recipeweaver.api.CraftCommandSender;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.StationCraftCompleteEvent;
import dev.recipeweaver.api.StationRecipeDefinition;
import dev.recipeweaver.api.WeatherCondition;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Furnace;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.FurnaceStartSmeltEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Verifies actual furnace ticks, inventory changes, and commit-time guard denial. */
final class NativeFurnacePhysicalSmoke implements Listener {
    private static NativeFurnacePhysicalSmoke active;
    private enum Phase { VALID, DENIED, ENVIRONMENT_ALLOWED,
        WEATHER_DENIED, PLAYER_DENIED, DONE }

    private static final class Probe {
        final NamespacedKey key;
        final Material material;
        final StationRecipeDefinition saved;
        final Block block;
        final BlockState previous;
        final Chunk chunk;
        final boolean wasForceLoaded;
        boolean removed;
        boolean conditioned;
        int allowedValid;
        int completedValid;
        int completedDenied;
        int deniedStale;
        int allowedStale;
        int allowedEnvironment;
        int deniedWeather;
        int deniedPlayer;
        int allowedRestricted;

        Probe(RecipeWeaverApi api, NamespacedKey key, Material material, Block block) {
            this.key = key;
            this.material = material;
            this.saved = api.stationRecipe(key).orElseThrow();
            this.block = block;
            this.previous = block.getState();
            this.chunk = block.getChunk();
            this.wasForceLoaded = chunk.isForceLoaded();
        }
    }

    private final JavaPlugin smoke;
    private final RecipeWeaverApi api;
    private final NamespacedKey furnace;
    private final NamespacedKey blast;
    private final NamespacedKey smoker;
    private final CompletableFuture<Void> result = new CompletableFuture<>();
    private final List<Probe> probes = new ArrayList<>();
    private Phase phase = Phase.VALID;
    private int readinessAttempts;
    private int cookingCommands;

    private NativeFurnacePhysicalSmoke(JavaPlugin smoke, RecipeWeaverApi api,
            NamespacedKey furnace, NamespacedKey blast, NamespacedKey smoker) {
        this.smoke = smoke;
        this.api = api;
        this.furnace = furnace;
        this.blast = blast;
        this.smoker = smoker;
    }

    static CompletableFuture<Void> start(JavaPlugin smoke, RecipeWeaverApi api,
            NamespacedKey furnace, NamespacedKey blast, NamespacedKey smoker) {
        NativeFurnacePhysicalSmoke pulse = new NativeFurnacePhysicalSmoke(
                smoke, api, furnace, blast, smoker);
        pulse.prepare();
        return pulse.result;
    }

    private void prepare() {
        World world = Bukkit.getWorlds().get(0);
        Location spawn = world.getSpawnLocation();
        // Use the upper build area so an ordinary hill near spawn cannot
        // collide with the temporary blocks on repeated smoke runs.
        int y = world.getMaxHeight() - 16;
        // Failed smoke runs can leave a probe block behind in the retained test world.
        // Choose a fresh upper-air level instead of modifying that prior block.
        while (y > world.getMaxHeight() - 192
                && (!world.getBlockAt(spawn.getBlockX() + 30, y, spawn.getBlockZ() + 30)
                        .getType().isAir()
                    || !world.getBlockAt(spawn.getBlockX() + 32, y, spawn.getBlockZ() + 30)
                        .getType().isAir()
                    || !world.getBlockAt(spawn.getBlockX() + 34, y, spawn.getBlockZ() + 30)
                        .getType().isAir())) {
            y--;
        }
        try {
            probes.add(new Probe(api, furnace, Material.FURNACE,
                    world.getBlockAt(spawn.getBlockX() + 30, y, spawn.getBlockZ() + 30)));
            probes.add(new Probe(api, blast, Material.BLAST_FURNACE,
                    world.getBlockAt(spawn.getBlockX() + 32, y, spawn.getBlockZ() + 30)));
            probes.add(new Probe(api, smoker, Material.SMOKER,
                    world.getBlockAt(spawn.getBlockX() + 34, y, spawn.getBlockZ() + 30)));
            for (Probe probe : probes) {
                check(probe.previous.getType().isAir(),
                        "Physical " + probe.material + " probe site is occupied");
            }
            for (Probe probe : probes) {
                probe.chunk.setForceLoaded(true);
                probe.block.setType(probe.material, false);
            }
            active = this;
            Bukkit.getPluginManager().registerEvents(this, smoke);
            // Other smoke checks publish asynchronous reloads during setup.
            Bukkit.getScheduler().runTaskLater(smoke, this::armWhenReady, 50L);
        } catch (Throwable failure) {
            cleanup();
            throw new IllegalStateException("Could not prepare physical furnace pulse", failure);
        }
    }

    private void armWhenReady() {
        try {
            if (probes.stream().anyMatch(probe -> Bukkit.getRecipe(probe.key) == null)) {
                check(++readinessAttempts < 5,
                        "Physical furnace recipes were unavailable after reload");
                Bukkit.getScheduler().runTaskLater(smoke, this::armWhenReady, 5L);
                return;
            }
            Probe actionProbe = probes.get(0);
            api.saveStationRecipe(withCommand(actionProbe.saved));
            actionProbe.conditioned = true;
            for (Probe probe : probes) arm(probe);
            Bukkit.getScheduler().runTaskLater(smoke, this::checkValidAndArmDenied, 10L);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private static void arm(Probe probe) {
        Furnace state = (Furnace) probe.block.getState();
        state.setCookTime((short) 0);
        state.setCookTimeTotal(2);
        state.setBurnTime((short) 80);
        check(state.update(false, false), "Could not light physical " + probe.material);
        Furnace live = (Furnace) probe.block.getState();
        live.getInventory().setSmelting(new ItemStack(Material.DIRT));
        live.getInventory().setResult(null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void accelerate(FurnaceStartSmeltEvent event) {
        if (find(event.getBlock()) != null) event.setTotalCookTime(2);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void changeResult(FurnaceSmeltEvent event) {
        Probe probe = find(event.getBlock());
        if (phase == Phase.DENIED && probe != null && !probe.removed) {
            event.setResult(new ItemStack(Material.GOLD_INGOT, 64));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void observe(FurnaceSmeltEvent event) {
        Probe probe = find(event.getBlock());
        if (probe == null) return;
        if (phase == Phase.VALID && !event.isCancelled()) probe.allowedValid++;
        if (phase == Phase.DENIED) {
            if (event.isCancelled()) {
                probe.deniedStale++;
                // Stop retries after observing the actual denied event. The
                // recipe is restored during cleanup after inventory checks.
                if (!probe.removed) {
                    check(api.removeStationRecipe(probe.key),
                            "Could not pause physical " + probe.material + " recipe");
                    probe.removed = true;
                }
            } else {
                probe.allowedStale++;
            }
        }
        if (phase == Phase.ENVIRONMENT_ALLOWED && !event.isCancelled()) {
            probe.allowedEnvironment++;
        }
        if (phase == Phase.WEATHER_DENIED || phase == Phase.PLAYER_DENIED) {
            if (event.isCancelled()) {
                if (phase == Phase.WEATHER_DENIED) probe.deniedWeather++;
                else probe.deniedPlayer++;
                if (!probe.removed) {
                    check(api.removeStationRecipe(probe.key),
                            "Could not pause restricted physical " + probe.material + " recipe");
                    probe.removed = true;
                }
            } else probe.allowedRestricted++;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void observeCompletion(StationCraftCompleteEvent event) {
        Probe probe = probes.stream().filter(candidate ->
                candidate.key.equals(event.batch().recipe())).findFirst().orElse(null);
        if (probe == null) return;
        check(event.player() == null && event.batch().result().isSimilar(probe.saved.result()),
                "Physical cooking completion carried a player or wrong output");
        if (phase == Phase.VALID) probe.completedValid++;
        else if (phase == Phase.DENIED) probe.completedDenied++;
    }

    private void checkValidAndArmDenied() {
        try {
            for (Probe probe : probes) {
                check(probe.allowedValid == 1,
                        "Physical " + probe.material + " did not cook exactly once");
                check(probe.completedValid == 1,
                        "Physical " + probe.material + " did not publish exactly one proven completion");
                Furnace live = (Furnace) probe.block.getState();
                check(!present(live.getInventory().getSmelting())
                                && sameStack(live.getInventory().getResult(), probe.saved.result()),
                        "Physical " + probe.material + " did not consume one input into one result");
            }
            check(cookingCommands == 1,
                    "Committed physical furnace cook did not execute one console action");
            smoke.getLogger().info("RW_NATIVE_COOKING_ACTION_PASS");
            phase = Phase.DENIED;
            for (Probe probe : probes) arm(probe);
            Bukkit.getScheduler().runTaskLater(smoke, this::checkStaleAndArmConditions, 10L);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void checkStaleAndArmConditions() {
        try {
            for (Probe probe : probes) {
                Furnace live = (Furnace) probe.block.getState();
                check(probe.deniedStale == 1 && probe.allowedStale == 0,
                        "Physical " + probe.material + " stale result was not denied once");
                check(probe.completedDenied == 0,
                        "Denied physical " + probe.material + " published a false completion");
                check(sameStack(live.getInventory().getSmelting(), new ItemStack(Material.DIRT))
                                && !present(live.getInventory().getResult()),
                        "Denied " + probe.material + " cook consumed input or duplicated output");
                String marker = switch (probe.material) {
                    case FURNACE -> "RW_NATIVE_FURNACE_PHYSICAL_PASS";
                    case BLAST_FURNACE -> "RW_NATIVE_BLAST_PHYSICAL_PASS";
                    case SMOKER -> "RW_NATIVE_SMOKER_PHYSICAL_PASS";
                    default -> throw new IllegalStateException("Unexpected cooking block");
                };
                smoke.getLogger().info(marker);
            }
            check(cookingCommands == 1,
                    "Denied physical furnace cook executed a console action");
            smoke.getLogger().info("RW_NATIVE_COOKING_ACTION_DENIAL_PASS");
            smoke.getLogger().info("RW_NATIVE_COOKING_POSTCOMMIT_EVENT_PASS");
            Probe furnaceProbe = probes.get(0);
            api.saveStationRecipe(withConditions(furnaceProbe.saved, matchingEnvironment(furnaceProbe)));
            furnaceProbe.removed = false;
            furnaceProbe.conditioned = true;
            phase = Phase.ENVIRONMENT_ALLOWED;
            arm(furnaceProbe);
            Bukkit.getScheduler().runTaskLater(smoke, this::checkEnvironmentAllowed, 10L);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void checkEnvironmentAllowed() {
        try {
            Probe probe = probes.get(0);
            Furnace live = (Furnace) probe.block.getState();
            check(probe.allowedEnvironment == 1 && !present(live.getInventory().getSmelting())
                            && sameStack(live.getInventory().getResult(), probe.saved.result()),
                    "Environment-matched physical furnace did not cook exactly once");
            smoke.getLogger().info("RW_NATIVE_COOKING_ENVIRONMENT_PASS");
            World world = probe.block.getWorld();
            WeatherCondition wrongWeather = world.hasStorm()
                    ? WeatherCondition.CLEAR : WeatherCondition.RAIN;
            RecipeConditions environment = matchingEnvironment(probe);
            api.saveStationRecipe(withConditions(probe.saved, new RecipeConditions(null,
                    environment.world(), environment.biome(), environment.timeStart(),
                    environment.timeEnd(), wrongWeather, 0, null, 0, null)));
            phase = Phase.WEATHER_DENIED;
            arm(probe);
            Bukkit.getScheduler().runTaskLater(smoke, this::checkWeatherDenied, 10L);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void checkWeatherDenied() {
        try {
            Probe probe = probes.get(0);
            Furnace live = (Furnace) probe.block.getState();
            check(probe.deniedWeather >= 1 && probe.allowedRestricted == 0
                            && sameStack(live.getInventory().getSmelting(), new ItemStack(Material.DIRT))
                            && !present(live.getInventory().getResult()),
                    "Weather-denied physical furnace consumed input or produced output");
            smoke.getLogger().info("RW_NATIVE_COOKING_WEATHER_GUARD_PASS");
            api.saveStationRecipe(withConditions(probe.saved, new RecipeConditions(
                    "recipeweaver.smoke.furnace", null, null, -1, -1,
                    WeatherCondition.ANY, 0, null, 0, null)));
            probe.removed = false;
            phase = Phase.PLAYER_DENIED;
            arm(probe);
            Bukkit.getScheduler().runTaskLater(smoke, this::finish, 10L);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void finish() {
        try {
            Probe probe = probes.get(0);
            Furnace live = (Furnace) probe.block.getState();
            check(probe.deniedPlayer >= 1 && probe.allowedRestricted == 0
                            && sameStack(live.getInventory().getSmelting(), new ItemStack(Material.DIRT))
                            && !present(live.getInventory().getResult()),
                    "Player-rule-denied physical furnace consumed input or produced output");
            smoke.getLogger().info("RW_NATIVE_COOKING_PLAYER_RULE_GUARD_PASS");
            phase = Phase.DONE;
            cleanup();
            result.complete(null);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private static RecipeConditions matchingEnvironment(Probe probe) {
        World world = probe.block.getWorld();
        int time = (int) Math.floorMod(world.getTime(), 24000);
        return new RecipeConditions(null, world.getName(), probe.block.getBiome().getKey(),
                Math.floorMod(time - 100, 24000), Math.floorMod(time + 100, 24000),
                WeatherCondition.ANY, 0, null, 0, null);
    }

    private static StationRecipeDefinition withConditions(StationRecipeDefinition original,
                                                          RecipeConditions conditions) {
        return new StationRecipeDefinition(original.key(), original.kind(),
                original.ingredients(), original.result(), original.group(),
                original.cookingTicks(), original.experience(), original.anvilLevels(),
                original.enabled(), original.hidden(), original.matchModes(),
                original.alternatives(), original.itemTags(), original.bookCategory(),
                original.maxCraftsPerPlayer(), original.cooldownSeconds(), conditions,
                original.craftSound(), original.craftAdvancement(), original.craftCommand(),
                original.resultCopySourceSlot(), original.resultMetadataMode(),
                original.consumeSlots(), original.remainders(), original.optionalSlots());
    }

    private static StationRecipeDefinition withCommand(StationRecipeDefinition original) {
        return new StationRecipeDefinition(original.key(), original.kind(),
                original.ingredients(), original.result(), original.group(),
                original.cookingTicks(), original.experience(), original.anvilLevels(),
                original.enabled(), original.hidden(), original.matchModes(),
                original.alternatives(), original.itemTags(), original.bookCategory(),
                original.maxCraftsPerPlayer(), original.cooldownSeconds(), original.conditions(),
                original.craftSound(), original.craftAdvancement(),
                new CraftCommandAction("recipeweaversmokeaction cooking {world} {x} {y} {z}",
                        CraftCommandSender.CONSOLE, false),
                original.resultCopySourceSlot(), original.resultMetadataMode(),
                original.consumeSlots(), original.remainders(), original.optionalSlots(),
                original.resultChoices());
    }

    static boolean onActionCommand(CommandSender sender, String[] args) {
        NativeFurnacePhysicalSmoke probe = active;
        check(probe != null && args.length == 5 && sender == Bukkit.getConsoleSender(),
                "Physical cooking action had no active probe or console sender");
        Block block = probe.probes.get(0).block;
        check(args[1].equals(block.getWorld().getName())
                        && args[2].equals(Integer.toString(block.getX()))
                        && args[3].equals(Integer.toString(block.getY()))
                        && args[4].equals(Integer.toString(block.getZ())),
                "Physical cooking action had wrong world or block coordinates");
        probe.cookingCommands++;
        return true;
    }

    private void fail(Throwable failure) {
        result.completeExceptionally(failure);
        cleanup();
    }

    private void cleanup() {
        phase = Phase.DONE;
        if (active == this) active = null;
        HandlerList.unregisterAll(this);
        for (Probe probe : probes) {
            if (probe.removed || probe.conditioned) {
                api.saveStationRecipe(probe.saved);
                probe.removed = false;
                probe.conditioned = false;
            }
            probe.previous.update(true, false);
            if (!probe.wasForceLoaded) probe.chunk.setForceLoaded(false);
        }
    }

    private Probe find(Block block) {
        for (Probe probe : probes) {
            Block candidate = probe.block;
            if (block.getWorld().equals(candidate.getWorld()) && block.getX() == candidate.getX()
                    && block.getY() == candidate.getY() && block.getZ() == candidate.getZ()) {
                return probe;
            }
        }
        return null;
    }

    private static boolean sameStack(ItemStack actual, ItemStack expected) {
        return present(actual) && expected != null && actual.getAmount() == expected.getAmount()
                && actual.isSimilar(expected);
    }

    private static boolean present(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
