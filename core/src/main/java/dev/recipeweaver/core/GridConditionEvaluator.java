package dev.recipeweaver.core;

import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.WeatherCondition;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

/** Checks conditional custom grids on the server thread before preview and each craft batch. */
final class GridConditionEvaluator {
    private GridConditionEvaluator() { }

    static boolean matches(RecipeConditions conditions, Player player, World world) {
        if (conditions.unrestricted()) return true;
        if (player == null || world == null) return false;
        if (player.getWorld() != world) return false;
        if (conditions.permission() != null && !player.hasPermission(conditions.permission())) return false;
        if (conditions.world() != null && !conditions.world().equals(world.getName())) return false;
        if (conditions.biome() != null) {
            Location location = player.getLocation();
            if (location == null || location.getWorld() != world
                    || !conditions.biome().equals(world.getBlockAt(location).getBiome().getKey())) return false;
        }
        if (conditions.timeStart() >= 0 && !timeMatches(conditions.timeStart(),
                conditions.timeEnd(), world.getTime())) return false;
        if (conditions.weather() != WeatherCondition.ANY
                && !weatherMatches(conditions.weather(), world.hasStorm(), world.isThundering())) return false;
        if (conditions.minimumLevel() > 0 && player.getLevel() < conditions.minimumLevel()) return false;
        if (conditions.scoreboardObjective() != null) {
            Scoreboard board = player.getScoreboard();
            Objective objective = board == null ? null : board.getObjective(conditions.scoreboardObjective());
            if (objective == null) return false;
            Score score = objective.getScore(player.getName());
            if (!score.isScoreSet() || score.getScore() < conditions.minimumScore()) return false;
        }
        if (conditions.advancement() != null) {
            Advancement advancement = Bukkit.getAdvancement(conditions.advancement());
            if (advancement == null || !player.getAdvancementProgress(advancement).isDone()) return false;
        }
        return true;
    }

    /** Evaluates block automation without a player. Player-only requirements fail closed. */
    static boolean matchesAutomated(RecipeConditions conditions, World world, Location location) {
        if (conditions.unrestricted()) return true;
        if (world == null || conditions.permission() != null || conditions.minimumLevel() > 0
                || conditions.scoreboardObjective() != null || conditions.advancement() != null) return false;
        if (conditions.world() != null && !conditions.world().equals(world.getName())) return false;
        if (conditions.biome() != null && (location == null || location.getWorld() != world
                || !conditions.biome().equals(world.getBlockAt(location).getBiome().getKey()))) return false;
        if (conditions.timeStart() >= 0 && !timeMatches(conditions.timeStart(),
                conditions.timeEnd(), world.getTime())) return false;
        return conditions.weather() == WeatherCondition.ANY
                || weatherMatches(conditions.weather(), world.hasStorm(), world.isThundering());
    }

    static boolean timeMatches(int start, int end, long worldTime) {
        int time = (int) Math.floorMod(worldTime, 24000);
        return start <= end ? time >= start && time <= end : time >= start || time <= end;
    }

    static boolean weatherMatches(WeatherCondition required, boolean rain, boolean thunder) {
        return switch (required) {
            case ANY -> true;
            case CLEAR -> !rain && !thunder;
            case RAIN -> rain && !thunder;
            case THUNDER -> thunder;
        };
    }
}
