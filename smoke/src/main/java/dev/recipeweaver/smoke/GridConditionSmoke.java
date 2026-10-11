package dev.recipeweaver.smoke;

import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.GridTier;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.WeatherCondition;
import dev.recipeweaver.api.WeightedResult;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

/** Test-only, main-thread probe for conditional custom-grid matching. */
final class GridConditionSmoke {
    static final NamespacedKey KEY = new NamespacedKey("recipeweaver", "smoke_grid_conditions");
    private static final String PERMISSION = "recipeweaver.smoke.condition";
    private static final String PLAYER_NAME = "RWConditionSmoke";

    private GridConditionSmoke() { }

    static RecipeConditions verify(RecipeWeaverApi api) throws Exception {
        World world = Bukkit.getWorlds().get(0);
        Location location = new Location(world, 0, 80, 0);
        NamespacedKey advancementKey = NamespacedKey.minecraft("story/root");
        Advancement advancement = Bukkit.getAdvancement(advancementKey);
        check(advancement != null, "Advancement fixture is missing");
        WeatherCondition weather = world.isThundering() ? WeatherCondition.THUNDER
                : world.hasStorm() ? WeatherCondition.RAIN : WeatherCondition.CLEAR;
        int start = (int) world.getTime();
        int end = (start + 100) % 24000;
        RecipeConditions conditions = new RecipeConditions(PERMISSION, world.getName(),
                location.getBlock().getBiome().getKey(), start, end, weather,
                7, "rw_condition_smoke", 2, advancementKey);
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[16]));
        ingredients.set(0, new ItemStack(Material.AMETHYST_SHARD));
        api.saveGridRecipe(new GridRecipeDefinition(KEY, GridTier.ELITE, RecipeKind.SHAPED,
                4, ingredients, List.of(new WeightedResult(new ItemStack(Material.NETHER_STAR), 1)),
                "smoke", true, false, java.util.Collections.nCopies(16, IngredientMatchMode.EXACT),
                0, true, java.util.Collections.nCopies(16, List.of()),
                java.util.Collections.nCopies(16, null),
                java.util.Collections.nCopies(16, true),
                java.util.Collections.nCopies(16, null), conditions));
        check(conditions.equals(api.gridRecipe(KEY).orElseThrow().conditions()),
                "Condition API save/read lost fields");

        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective("rw_condition_smoke", Criteria.DUMMY,
                "RecipeWeaver smoke");
        objective.getScore(PLAYER_NAME).setScore(3);
        Player eligible = player(world, location, board, true, 8, true);
        check(result(api, eligible, ingredients) == Material.NETHER_STAR,
                "Eligible player did not match conditional grid");
        check(result(api, player(world, location, board, false, 8, true), ingredients) == null,
                "Permission condition did not block grid");
        check(result(api, player(world, location, board, true, 6, true), ingredients) == null,
                "Experience condition did not block grid");
        objective.getScore(PLAYER_NAME).setScore(1);
        check(result(api, eligible, ingredients) == null,
                "Scoreboard condition did not block grid");
        objective.getScore(PLAYER_NAME).setScore(3);
        check(result(api, player(world, location, board, true, 8, false), ingredients) == null,
                "Advancement condition did not block grid");
        long oldTime = world.getTime();
        boolean oldStorm = world.hasStorm();
        boolean oldThunder = world.isThundering();
        try {
            world.setTime((start + 12000) % 24000);
            check(result(api, eligible, ingredients) == null,
                    "Time condition did not block grid");
            world.setTime(start);
            world.setStorm(weather == WeatherCondition.CLEAR);
            world.setThundering(false);
            if (weather == WeatherCondition.THUNDER) world.setStorm(false);
            check(result(api, eligible, ingredients) == null,
                    "Weather condition did not block grid");
        } finally {
            world.setTime(oldTime);
            world.setStorm(oldStorm);
            world.setThundering(oldThunder);
        }
        return conditions;
    }

    private static Player player(World world, Location location, Scoreboard board,
                                 boolean permission, int level, boolean advancementDone) {
        AdvancementProgress progress = (AdvancementProgress) Proxy.newProxyInstance(
                AdvancementProgress.class.getClassLoader(),
                new Class<?>[] {AdvancementProgress.class},
                (proxy, method, args) -> method.getName().equals("isDone") ? advancementDone : null);
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> PLAYER_NAME;
                    case "getWorld" -> world;
                    case "getLocation" -> location;
                    case "hasPermission" -> permission;
                    case "getLevel" -> level;
                    case "getScoreboard" -> board;
                    case "getAdvancementProgress" -> progress;
                    default -> null;
                });
    }

    private static Material result(RecipeWeaverApi api, Player player, List<ItemStack> input)
            throws Exception {
        var field = api.getClass().getDeclaredField("gridMenus");
        field.setAccessible(true);
        Object menus = field.get(api);
        var find = menus.getClass().getDeclaredMethod("find", Player.class, World.class,
                GridTier.class, int.class, List.class);
        find.setAccessible(true);
        Object match = find.invoke(menus, player, player.getWorld(), GridTier.ELITE, 4, input);
        if (match == null) return null;
        var result = match.getClass().getDeclaredMethod("result");
        result.setAccessible(true);
        return ((ItemStack) result.invoke(match)).getType();
    }

    private static void check(boolean passed, String detail) {
        if (!passed) throw new IllegalStateException(detail);
    }
}
