package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class StudioMenusGroupInputTest {
    @Test
    void noneClearsAWorkbenchGroupFromTheInGameTextPrompt() {
        assertEquals("", StudioMenus.groupInput(" NoNe "));
        assertEquals("Weapons", StudioMenus.groupInput(" Weapons "));
    }

    @Test
    void equivalentWhitespacePersistsAsOneGroupName() {
        assertEquals("Magic Tools", StudioMenus.groupInput(" Magic   Tools "));
    }
}
