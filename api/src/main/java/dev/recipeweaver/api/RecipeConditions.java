package dev.recipeweaver.api;

import java.util.Objects;
import org.bukkit.NamespacedKey;

/** Immutable player and world requirements. Null entries and -1 time bounds mean unrestricted. */
public record RecipeConditions(String permission, String world, NamespacedKey biome,
                               int timeStart, int timeEnd, WeatherCondition weather,
                               int minimumLevel, String scoreboardObjective, int minimumScore,
                               NamespacedKey advancement) {
    public static final RecipeConditions NONE = new RecipeConditions(null, null, null,
            -1, -1, WeatherCondition.ANY, 0, null, 0, null);

    public RecipeConditions {
        permission = optional(permission);
        world = optional(world);
        scoreboardObjective = optional(scoreboardObjective);
        Objects.requireNonNull(weather, "weather");
        if (permission != null && (permission.length() > 128
                || !permission.matches("[A-Za-z0-9._*:-]+"))) {
            throw new IllegalArgumentException("Invalid required permission");
        }
        if (world != null && (world.length() > 128 || world.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("Invalid required world");
        }
        if ((timeStart == -1) != (timeEnd == -1)
                || timeStart < -1 || timeStart > 23999 || timeEnd < -1 || timeEnd > 23999) {
            throw new IllegalArgumentException("Time bounds must both be 0 through 23999 or disabled");
        }
        if (minimumLevel < 0 || minimumLevel > 100000) {
            throw new IllegalArgumentException("Minimum experience level must be 0 through 100000");
        }
        if (scoreboardObjective != null && (scoreboardObjective.length() > 128
                || scoreboardObjective.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("Invalid scoreboard objective");
        }
        if (scoreboardObjective == null && minimumScore != 0) {
            throw new IllegalArgumentException("A minimum score needs an objective");
        }
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public boolean unrestricted() {
        return permission == null && world == null && biome == null && timeStart == -1
                && weather == WeatherCondition.ANY && minimumLevel == 0
                && scoreboardObjective == null && advancement == null;
    }
}
