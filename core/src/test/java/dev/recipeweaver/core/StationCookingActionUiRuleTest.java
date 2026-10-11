package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class StationCookingActionUiRuleTest {
    @Test void cookingCommandEditorAcceptsConsoleOnlyTextWithoutPlayerTokens() {
        assertEquals("say ready", StationMenus.validateAutomatedCommand("  say ready  "));
    }

    @Test void cookingCommandEditorRejectsPlayerAndPlaceholderTokensBeforeSaving() {
        for (String command : new String[] {
                "say {player}", "say {uuid}", "say {PLAYER}", "say %player_name%"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> StationMenus.validateAutomatedCommand(command), command);
        }
    }
}
