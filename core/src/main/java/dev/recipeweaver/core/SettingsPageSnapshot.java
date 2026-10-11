package dev.recipeweaver.core;

import dev.recipeweaver.api.BackupFormat;
import java.util.Objects;

/** The values shown beside each setting that can be changed with one click. */
record SettingsPageSnapshot(boolean bookItemEnabled, boolean lockdown, BackupFormat backupFormat) {
    SettingsPageSnapshot {
        Objects.requireNonNull(backupFormat, "backupFormat");
    }

    boolean stillCurrentFor(int slot, boolean currentBookItem, boolean currentLockdown,
                            BackupFormat currentBackupFormat) {
        return switch (slot) {
            case 9 -> bookItemEnabled == currentBookItem;
            case 13 -> lockdown == currentLockdown;
            case 19 -> backupFormat == currentBackupFormat;
            default -> true;
        };
    }
}
