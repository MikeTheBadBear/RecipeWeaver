package dev.recipeweaver.core;

import dev.recipeweaver.api.CraftCommandAction;
import dev.recipeweaver.api.CraftCommandSender;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.BiFunction;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Runs configured commands after a custom-grid inventory transaction commits. */
final class GridCraftCommands {
    private GridCraftCommands() { }

    static List<String> execute(Player player, List<CraftCommandAction> committedBatches) {
        return execute(player, committedBatches, Bukkit::dispatchCommand);
    }

    static List<String> execute(Player player, List<CraftCommandAction> committedBatches,
                                BiPredicate<CommandSender, String> dispatcher) {
        return execute(player, committedBatches, dispatcher, PlaceholderHook::expand);
    }

    static List<String> execute(Player player, List<CraftCommandAction> committedBatches,
                                BiPredicate<CommandSender, String> dispatcher,
                                BiFunction<Player, String, String> placeholders) {
        Set<CraftCommandAction> playedOnce = new HashSet<>();
        List<String> failures = new ArrayList<>();
        int actionNumber = 0;
        for (CraftCommandAction action : committedBatches) {
            if (action == null || (!action.perBatch() && !playedOnce.add(action))) continue;
            actionNumber++;
            CommandSender sender = action.sender() == CraftCommandSender.CONSOLE
                    ? Bukkit.getConsoleSender() : player;
            try {
                String command = action.command().replace("{player}", player.getName())
                        .replace("{uuid}", player.getUniqueId().toString());
                command = PlaceholderHook.checkedCommand(placeholders.apply(player, command));
                if (!dispatcher.test(sender, command)) {
                    failures.add("Craft command action " + actionNumber + " was not handled");
                }
            } catch (RuntimeException ex) {
                failures.add("Craft command action " + actionNumber + " failed: "
                        + ex.getClass().getSimpleName());
            }
        }
        return List.copyOf(failures);
    }
}
