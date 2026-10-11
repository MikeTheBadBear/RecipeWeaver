package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.recipeweaver.api.BackupFormat;
import org.junit.jupiter.api.Test;

final class SettingsPageSnapshotTest {
    private final SettingsPageSnapshot page =
            new SettingsPageSnapshot(false, false, BackupFormat.ZIP);

    @Test
    void bookToggleRejectsAChangeMadeAfterThePageOpened() {
        assertTrue(page.stillCurrentFor(9, false, false, BackupFormat.ZIP));
        assertFalse(page.stillCurrentFor(9, true, false, BackupFormat.ZIP));
        assertTrue(page.stillCurrentFor(9, false, true, BackupFormat.DIRECTORY));
    }

    @Test
    void lockdownToggleRejectsAChangeMadeAfterThePageOpened() {
        assertTrue(page.stillCurrentFor(13, false, false, BackupFormat.ZIP));
        assertFalse(page.stillCurrentFor(13, false, true, BackupFormat.ZIP));
    }

    @Test
    void backupFormatToggleRejectsAChangeMadeAfterThePageOpened() {
        assertTrue(page.stillCurrentFor(19, false, false, BackupFormat.ZIP));
        assertFalse(page.stillCurrentFor(19, false, false, BackupFormat.DIRECTORY));
    }
}
