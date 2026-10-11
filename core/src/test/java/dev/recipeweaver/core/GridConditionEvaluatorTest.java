package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.WeatherCondition;
import java.lang.reflect.Proxy;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.junit.jupiter.api.Test;

final class GridConditionEvaluatorTest {
    @Test
    void timeWrapAndWeatherBoundaries() {
        assertTrue(GridConditionEvaluator.timeMatches(23000, 1000, 23999));
        assertTrue(GridConditionEvaluator.timeMatches(23000, 1000, 0));
        assertFalse(GridConditionEvaluator.timeMatches(23000, 1000, 12000));
        assertTrue(GridConditionEvaluator.timeMatches(500, 500, 24500));
        assertFalse(GridConditionEvaluator.timeMatches(500, 500, 501));
        assertTrue(GridConditionEvaluator.weatherMatches(WeatherCondition.CLEAR, false, false));
        assertFalse(GridConditionEvaluator.weatherMatches(WeatherCondition.CLEAR, true, false));
        assertTrue(GridConditionEvaluator.weatherMatches(WeatherCondition.RAIN, true, false));
        assertFalse(GridConditionEvaluator.weatherMatches(WeatherCondition.RAIN, true, true));
        assertTrue(GridConditionEvaluator.weatherMatches(WeatherCondition.THUNDER, true, true));
    }

    @Test
    void playerWorldAndScoreRequirementsRejectMissingOrLowScores() {
        RecipeConditions conditions = new RecipeConditions("recipeweaver.craft.special", "world",
                null, 23000, 1000, WeatherCondition.RAIN, 7, "crafts", 2, null);
        World world = world("world", 23500, true, false);
        assertFalse(GridConditionEvaluator.matches(conditions, null, world));
        assertFalse(GridConditionEvaluator.matches(conditions,
                player(false, 8, true, 3, world), world));
        assertFalse(GridConditionEvaluator.matches(conditions,
                player(true, 6, true, 3, world), world));
        assertFalse(GridConditionEvaluator.matches(conditions,
                player(true, 8, false, 3, world), world));
        assertFalse(GridConditionEvaluator.matches(conditions,
                player(true, 8, true, 1, world), world));
        World other = world("other", 23500, true, false);
        assertFalse(GridConditionEvaluator.matches(conditions,
                player(true, 8, true, 3, other), other));
        World daytime = world("world", 12000, true, false);
        assertFalse(GridConditionEvaluator.matches(conditions,
                player(true, 8, true, 3, daytime), daytime));
        assertTrue(GridConditionEvaluator.matches(conditions,
                player(true, 8, true, 3, world), world));
    }

    @Test
    void playerOnlyConditionRejectsAPlayerWhoLeftTheCraftWorld() {
        World cauldronWorld = world("world", 12000, false, false);
        World destination = world("elsewhere", 12000, false, false);
        RecipeConditions permissionOnly = new RecipeConditions("recipeweaver.craft.special",
                null, null, -1, -1, WeatherCondition.ANY, 0, null, 0, null);
        assertTrue(GridConditionEvaluator.matches(permissionOnly,
                player(true, 0, false, 0, cauldronWorld), cauldronWorld));
        assertFalse(GridConditionEvaluator.matches(permissionOnly,
                player(true, 0, false, 0, destination), cauldronWorld));
    }

    @Test
    void invalidConditionPairsAreRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RecipeConditions(null, null, null, 100, -1,
                        WeatherCondition.ANY, 0, null, 0, null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RecipeConditions(null, null, null, -1, -1,
                        WeatherCondition.ANY, 0, null, 1, null));
    }

    @Test
    void biomeUsesTheCraftWorldAndRejectsAPlayerInAnotherWorld() {
        World plains = biomeWorld(Biome.PLAINS);
        World desert = biomeWorld(Biome.DESERT);
        RecipeConditions conditions = new RecipeConditions(null, null,
                NamespacedKey.minecraft("plains"), -1, -1, WeatherCondition.ANY,
                0, null, 0, null);
        Player visitor = locatedPlayer(plains);
        assertTrue(GridConditionEvaluator.matches(conditions, visitor, plains));
        assertFalse(GridConditionEvaluator.matches(conditions, visitor, desert));
        assertFalse(GridConditionEvaluator.matches(conditions, locatedPlayer(desert), plains));
        assertFalse(GridConditionEvaluator.matches(conditions, locatedPlayer(null), plains));
    }

    @Test
    void automatedCraftsHonorEnvironmentAndFailClosedForPlayerRequirements() {
        World factory = automatedWorld(23500);
        RecipeConditions environment = new RecipeConditions(null, "factory",
                NamespacedKey.minecraft("plains"), 23000, 1000, WeatherCondition.RAIN,
                0, null, 0, null);
        assertTrue(GridConditionEvaluator.matchesAutomated(environment,
                factory, new Location(factory, 2, 64, 3)));
        World daytime = automatedWorld(12000);
        assertFalse(GridConditionEvaluator.matchesAutomated(environment,
                daytime, new Location(daytime, 2, 64, 3)));
        assertFalse(GridConditionEvaluator.matchesAutomated(environment,
                factory, new Location(daytime, 2, 64, 3)));
        RecipeConditions playerOnly = new RecipeConditions("recipeweaver.special", null,
                null, -1, -1, WeatherCondition.ANY, 0, null, 0, null);
        assertFalse(GridConditionEvaluator.matchesAutomated(playerOnly,
                factory, new Location(factory, 2, 64, 3)));
    }

    private static World automatedWorld(long time) {
        Block block = (Block) Proxy.newProxyInstance(Block.class.getClassLoader(),
                new Class<?>[] {Block.class}, (proxy, method, args) ->
                        method.getName().equals("getBiome") ? Biome.PLAINS : null);
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class<?>[] {World.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "factory";
                    case "getTime" -> time;
                    case "hasStorm" -> true;
                    case "isThundering" -> false;
                    case "getBlockAt" -> block;
                    default -> null;
                });
    }

    private static World biomeWorld(Biome biome) {
        Block block = (Block) Proxy.newProxyInstance(Block.class.getClassLoader(),
                new Class<?>[] {Block.class}, (proxy, method, args) ->
                        method.getName().equals("getBiome") ? biome : null);
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class<?>[] {World.class}, (proxy, method, args) ->
                        switch (method.getName()) {
                            case "getBlockAt" -> block;
                            case "hasStorm", "isThundering" -> false;
                            default -> null;
                        });
    }

    private static Player locatedPlayer(World world) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) ->
                        switch (method.getName()) {
                            case "getLocation" -> world == null ? null : new Location(world, 2, 64, 3);
                            case "getWorld" -> world;
                            case "getLevel" -> 0;
                            default -> null;
                        });
    }

    private static World world(String name, long time, boolean rain, boolean thunder) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class<?>[] {World.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getTime" -> time;
                    case "hasStorm" -> rain;
                    case "isThundering" -> thunder;
                    default -> null;
                });
    }

    private static Player player(boolean permission, int level, boolean scoreSet,
                                 int scoreValue, World world) {
        Score score = (Score) Proxy.newProxyInstance(Score.class.getClassLoader(),
                new Class<?>[] {Score.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isScoreSet" -> scoreSet;
                    case "getScore" -> scoreValue;
                    default -> null;
                });
        Objective objective = (Objective) Proxy.newProxyInstance(Objective.class.getClassLoader(),
                new Class<?>[] {Objective.class}, (proxy, method, args) ->
                        method.getName().equals("getScore") ? score : null);
        Scoreboard board = (Scoreboard) Proxy.newProxyInstance(Scoreboard.class.getClassLoader(),
                new Class<?>[] {Scoreboard.class}, (proxy, method, args) ->
                        method.getName().equals("getObjective") ? objective : null);
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> permission;
                    case "getLevel" -> level;
                    case "getScoreboard" -> board;
                    case "getName" -> "ConditionTester";
                    case "getWorld" -> world;
                    default -> null;
                });
    }
}
