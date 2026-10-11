package dev.recipeweaver.api;

import java.util.Objects;

/** One command after a committed custom-grid craft. Supports {player} and {uuid} tokens. */
public record CraftCommandAction(String command, CraftCommandSender sender, boolean perBatch) {
    public CraftCommandAction {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(sender, "sender");
        command = command.trim();
        if (command.isEmpty() || command.length() > 1024 || command.startsWith("/")) {
            throw new IllegalArgumentException("Craft command must be 1 through 1024 characters without a leading /");
        }
        for (int index = 0; index < command.length(); index++) {
            if (Character.isISOControl(command.charAt(index))) {
                throw new IllegalArgumentException("Craft command must be one line without control characters");
            }
        }
    }
}
