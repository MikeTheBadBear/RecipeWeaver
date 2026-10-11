package dev.recipeweaver.core;

import dev.recipeweaver.api.CraftCommandAction;
import dev.recipeweaver.api.CraftCommandSender;
import dev.recipeweaver.api.CraftSoundAction;
import dev.recipeweaver.api.StationRecipeDefinition;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.command.CommandSender;

/** Built-in actions for a verified automated cook, which has no acting player. */
final class AutomatedCookingActions {
    @FunctionalInterface interface SoundPlayer {
        void play(Location location, CraftSoundAction action);
    }

    private AutomatedCookingActions() { }

    static void run(StationRecipeDefinition recipe, Location origin, Consumer<String> warning) {
        run(recipe, origin, (location, action) -> location.getWorld().playSound(location,
                        action.sound().toString(), SoundCategory.BLOCKS,
                        action.volume(), action.pitch()),
                Bukkit::dispatchCommand, Bukkit.getConsoleSender(), warning);
    }

    static void run(StationRecipeDefinition recipe, Location origin, SoundPlayer sounds,
                    BiPredicate<CommandSender, String> commands, CommandSender console,
                    Consumer<String> warning) {
        Objects.requireNonNull(warning, "warning");
        if (recipe == null || !recipe.enabled() || origin == null || origin.getWorld() == null
                || !recipe.kind().cooking()) return;
        CraftSoundAction sound = recipe.craftSound();
        if (sound != null) {
            try { sounds.play(origin.clone(), sound); }
            catch (RuntimeException failure) {
                warnSafely(warning, "Cooking sound action failed after commit: " + failure);
            }
        }
        CraftCommandAction command = recipe.craftCommand();
        if (command == null) return;
        if (command.sender() != CraftCommandSender.CONSOLE) {
            warnSafely(warning, "Cooking command requires a console sender because no player made this craft");
            return;
        }
        try {
            String rendered = renderCommand(command.command(), origin);
            if (!commands.test(console, rendered)) {
                warnSafely(warning, "Cooking command was not handled: " + recipe.key());
            }
        } catch (RuntimeException failure) {
            warnSafely(warning, "Cooking command action failed after commit: " + failure);
        }
    }

    private static void warnSafely(Consumer<String> warning, String message) {
        // The cook has already committed. A broken warning sink must not abort
        // another postcommit action or unwind back through the event listener.
        try { warning.accept(message); }
        catch (RuntimeException ignored) { }
    }

    static String renderCommand(String command, Location origin) {
        if (command.contains("{player}") || command.contains("{uuid}")
                || command.matches("(?s).*%[^%]+%.*")) {
            throw new IllegalArgumentException("Automated cooking cannot resolve player placeholders");
        }
        return PlaceholderHook.checkedCommand(command
                .replace("{world}", origin.getWorld().getName())
                .replace("{x}", Integer.toString(origin.getBlockX()))
                .replace("{y}", Integer.toString(origin.getBlockY()))
                .replace("{z}", Integer.toString(origin.getBlockZ())));
    }

}
