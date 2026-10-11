package dev.recipeweaver.core;

import java.lang.reflect.InvocationTargetException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Optional PlaceholderAPI expansion for committed craft commands. */
final class PlaceholderHook {
    private PlaceholderHook() { }

    static String expand(Player player, String command) {
        if (command.indexOf('%') < 0) return command;
        Plugin plugin = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (plugin == null || !plugin.isEnabled()) return command;
        try {
            Class<?> api = plugin.getClass().getClassLoader()
                    .loadClass("me.clip.placeholderapi.PlaceholderAPI");
            Object result = api.getMethod("setPlaceholders", Player.class, String.class)
                    .invoke(null, player, command);
            if (!(result instanceof String expanded)) {
                throw new IllegalStateException("PlaceholderAPI returned no command text");
            }
            return expanded;
        } catch (ReflectiveOperationException | LinkageError ex) {
            Throwable cause = ex instanceof InvocationTargetException target
                    ? target.getCause() : ex;
            throw new IllegalStateException("PlaceholderAPI expansion failed", cause);
        }
    }

    static String checkedCommand(String command) {
        if (command == null || command.isBlank() || command.length() > 1024
                || command.stripLeading().startsWith("/")) {
            throw new IllegalArgumentException("Expanded craft command is invalid");
        }
        for (int index = 0; index < command.length(); index++) {
            if (Character.isISOControl(command.charAt(index))) {
                throw new IllegalArgumentException("Expanded craft command has control characters");
            }
        }
        return command;
    }
}
