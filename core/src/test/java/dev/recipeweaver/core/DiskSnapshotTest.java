package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DiskSnapshotTest {
    @TempDir Path root;

    @Test
    void workerReadCapturesImmutableContentsBeforeLaterWrite() throws Exception {
        Path settings = root.resolve("settings.yml");
        Files.writeString(settings, "schema: 1\nlockdown: false\n");
        Files.createDirectories(root.resolve("recipes"));
        Files.writeString(root.resolve("recipes").resolve("one.yml"), "id: recipeweaver:one\n");
        Logger logger = Logger.getLogger("RecipeWeaverSnapshotTest");
        try (AsyncIoService io = new AsyncIoService(logger)) {
            var captured = io.submit(() -> new Object[] {
                    Thread.currentThread().getName(), DiskSnapshot.read(root)
            }).get();
            assertEquals("RecipeWeaver-IO", captured[0]);
            DiskSnapshot snapshot = (DiskSnapshot) captured[1];
            Files.writeString(settings, "schema: 1\nlockdown: true\n");
            SettingsRepository loaded = new SettingsRepository(root);
            loaded.load(snapshot);
            assertFalse(loaded.lockdown());
            assertTrue(snapshot.directory("recipes").containsKey("one.yml"));
            assertNull(snapshot.file("missing.yml"));
        }
    }

    @Test
    void snapshotRejectsNestedDirectoriesInOwnedData() throws Exception {
        Files.createDirectories(root.resolve("recipes").resolve("nested"));
        assertThrows(IOException.class, () -> DiskSnapshot.read(root));
    }
}
