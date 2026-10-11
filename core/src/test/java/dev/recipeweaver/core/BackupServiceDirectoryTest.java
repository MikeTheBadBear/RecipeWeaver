package dev.recipeweaver.core;

import dev.recipeweaver.api.BackupFormat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BackupServiceDirectoryTest {
    @TempDir Path root;

    @Test
    void directoryBackupRestoresSettingsAndMixedRetentionKeepsLatest() throws Exception {
        SettingsRepository settings = new SettingsRepository(root);
        settings.setLockdown(true);
        settings.setBackupFormat(BackupFormat.DIRECTORY);
        BackupService backups = new BackupService(plugin(), settings::load, () -> 2,
                settings::backupFormat, Runnable::run);
        String source = backups.createBackup();
        assertTrue(source.endsWith(".dir"));
        assertTrue(Files.isRegularFile(root.resolve("backups").resolve(source).resolve("settings.yml")));

        settings.setLockdown(false);
        String recovery = backups.restore(source);
        assertTrue(recovery.endsWith(".dir"));
        assertTrue(settings.lockdown());
        assertEquals(BackupFormat.DIRECTORY, settings.backupFormat());
        assertTrue(backups.listBackups().contains(recovery));

        settings.setBackupFormat(BackupFormat.ZIP);
        String zipped = backups.createBackup();
        assertTrue(zipped.endsWith(".zip"));
        assertEquals(2, backups.listBackups().size());
        assertTrue(backups.listBackups().contains(zipped));
    }

    @Test
    void malformedDirectoryBackupCannotReplaceLiveSettings() throws Exception {
        SettingsRepository settings = new SettingsRepository(root);
        settings.setLockdown(true);
        BackupService backups = new BackupService(plugin(), settings::load);
        Path invalid = root.resolve("backups")
                .resolve("RecipeWeaver-backup-20260926-120000-abcdef12.dir");
        Files.createDirectories(invalid.resolve("recipes").resolve("nested"));
        Files.writeString(invalid.resolve("recipes").resolve("nested").resolve("recipe.yml"),
                "schema: 1\n");
        assertThrows(IOException.class, () -> backups.restore(invalid.getFileName().toString()));
        settings.load();
        assertTrue(settings.lockdown());
    }

    @Test
    void asyncDirectoryRestorePublishesValidatedSnapshotOnMainCallback() throws Exception {
        SettingsRepository settings = new SettingsRepository(root);
        settings.setLockdown(true);
        settings.setBackupFormat(BackupFormat.DIRECTORY);
        BackupService backups = new BackupService(plugin(), null, () -> 0,
                settings::backupFormat, Runnable::run);
        String source = backups.createBackup();
        settings.setLockdown(false);
        ExecutorService main = Executors.newSingleThreadExecutor(task ->
                new Thread(task, "RecipeWeaver-Test-Main"));
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("RecipeWeaverDirectoryBackupTest"))) {
            BackupService runtime = new BackupService(plugin(), null, () -> 0,
                    settings::backupFormat, main::execute);
            String previous = runtime.restoreAsync(source, io, settings::load).get(15, TimeUnit.SECONDS);
            assertTrue(previous.endsWith(".dir"));
            assertTrue(settings.lockdown());
            assertFalse(runtime.busy());
        } finally {
            main.shutdown();
            assertTrue(main.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Plugin plugin() {
        File dataFolder = root.toFile();
        Logger logger = Logger.getLogger("RecipeWeaverDirectoryBackupTest");
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getDataFolder" -> dataFolder;
                    case "getName" -> "RecipeWeaver";
                    case "getLogger" -> logger;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
