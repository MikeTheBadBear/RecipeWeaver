package dev.recipeweaver.core;

import dev.recipeweaver.api.BackupFormat;
import dev.recipeweaver.api.SqlMirrorMode;
import dev.recipeweaver.api.SqlReadPolicy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Queue;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.NamespacedKey;

final class SettingsRepositoryAsyncTest {
    @TempDir Path directory;

    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test
    void rapidSettingsWritesPersistInOrderAndPublishAfterMainCallbacks() throws Exception {
        Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        SettingsRepository settings = new SettingsRepository(directory, callbacks::add);
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("RecipeWeaverSettingsAsyncTest"))) {
            CompletableFuture<Void> lockdown = settings.setLockdownAsync(true, io);
            CompletableFuture<Void> retention = settings.setBackupRetentionAsync(7, io);
            CompletableFuture<Void> bookItem = settings.setBookItemEnabledAsync(true, io);
            CompletableFuture<Void> format = settings.setBackupFormatAsync(BackupFormat.DIRECTORY, io);
            CompletableFuture<Void> mirror = settings.setSqlMirrorModeAsync(SqlMirrorMode.SQLITE, io);
            NamespacedKey vanilla = NamespacedKey.minecraft("oak_planks");
            CompletableFuture<Void> excluded = settings.setVanillaRecipeDisabledAsync(vanilla, true, io);
            assertTrue(settings.lockdown());
            assertEquals(7, settings.backupRetention());
            assertTrue(settings.bookItemEnabled());
            assertEquals(BackupFormat.DIRECTORY, settings.backupFormat());
            assertEquals(SqlMirrorMode.SQLITE, settings.sqlMirrorBackend());
            assertTrue(settings.disabledVanillaRecipes().contains(vanilla));
            assertThrows(IllegalStateException.class, () -> settings.setLockdown(false));
            io.flush().join();
            assertFalse(lockdown.isDone());
            assertFalse(retention.isDone());
            assertFalse(bookItem.isDone());
            assertFalse(format.isDone());
            assertFalse(mirror.isDone());
            assertFalse(excluded.isDone());
            drain(callbacks);
            lockdown.join();
            retention.join();
            bookItem.join();
            format.join();
            mirror.join();
            excluded.join();
            SettingsRepository loaded = new SettingsRepository(directory);
            loaded.load();
            assertTrue(loaded.lockdown());
            assertEquals(7, loaded.backupRetention());
            assertTrue(loaded.bookItemEnabled());
            assertEquals(BackupFormat.DIRECTORY, loaded.backupFormat());
            assertEquals(SqlMirrorMode.SQLITE, loaded.sqlMirrorBackend());
            assertTrue(loaded.disabledVanillaRecipes().contains(vanilla));
            assertTrue(Files.readString(directory.resolve("settings.yml")).contains("schema: 9"));
        }
    }

    @Test
    void failedLatestWriteRollsBackToLastDurableSettings() throws Exception {
        Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        SettingsRepository settings = new SettingsRepository(directory, callbacks::add);
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("RecipeWeaverSettingsFailureTest"))) {
            CompletableFuture<Void> first = settings.setLockdownAsync(true, io);
            io.flush().join();
            Files.delete(directory.resolve("settings.yml"));
            Files.createDirectory(directory.resolve("settings.yml"));
            CompletableFuture<Void> failed = settings.setBackupRetentionAsync(5, io);
            assertThrows(CompletionException.class, () -> io.flush().join());
            List<Runnable> reversed = new java.util.ArrayList<>(callbacks);
            callbacks.clear();
            java.util.Collections.reverse(reversed);
            reversed.get(0).run();
            assertFalse(failed.isDone());
            reversed.get(1).run();
            first.join();
            assertThrows(CompletionException.class, failed::join);
            assertTrue(settings.lockdown());
            assertEquals(0, settings.backupRetention());
        }
    }

    @Test
    void repeatedSqlSelectionWaitsForThePendingWriteToBecomeDurable() throws Exception {
        Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        SettingsRepository settings = new SettingsRepository(directory, callbacks::add);
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("settings-noop-durability"))) {
            CompletableFuture<Void> first = settings.setSqlMirrorsAsync(List.of(SqlMirrorMode.SQLITE), io);
            CompletableFuture<Void> repeated = settings.setSqlMirrorsAsync(List.of(SqlMirrorMode.SQLITE), io);
            assertFalse(repeated.isDone());
            io.flush().join();
            assertFalse(repeated.isDone());
            drain(callbacks);
            first.join();
            repeated.join();
            assertTrue(settings.setSqlMirrorsAsync(List.of(SqlMirrorMode.SQLITE), io).isDone());
            SettingsRepository loaded = new SettingsRepository(directory);
            loaded.load();
            assertEquals(List.of(SqlMirrorMode.SQLITE), loaded.sqlMirrors());
        }
    }

    @Test
    void repeatedSqlSelectionReportsTheOriginalWriteFailure() throws Exception {
        Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        Files.createDirectory(directory.resolve("settings.yml"));
        SettingsRepository settings = new SettingsRepository(directory, callbacks::add);
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("settings-noop-failure"))) {
            CompletableFuture<Void> first = settings.setSqlMirrorsAsync(List.of(SqlMirrorMode.SQLITE), io);
            CompletableFuture<Void> repeated = settings.setSqlMirrorsAsync(List.of(SqlMirrorMode.SQLITE), io);
            assertFalse(repeated.isDone());
            assertThrows(CompletionException.class, () -> io.flush().join());
            drain(callbacks);
            assertThrows(CompletionException.class, first::join);
            assertThrows(CompletionException.class, repeated::join);
            assertEquals(List.of(), settings.sqlMirrors());
        }
    }

    @Test
    void vanillaRecipeListRejectsInvalidKeysAndOlderSchemaDefaultsEmpty() throws Exception {
        SettingsRepository settings = new SettingsRepository(directory);
        NamespacedKey key = NamespacedKey.minecraft("oak_planks");
        settings.setVanillaRecipeDisabled(key, true);
        assertThrows(IllegalArgumentException.class,
                () -> settings.setVanillaRecipeDisabled(new NamespacedKey("recipeweaver", "bad"), true));
        SettingsRepository reloaded = new SettingsRepository(directory);
        reloaded.load();
        assertEquals(java.util.Set.of(key), reloaded.disabledVanillaRecipes());
        settings.setVanillaRecipeDisabled(key, false);
        reloaded.load();
        assertTrue(reloaded.disabledVanillaRecipes().isEmpty());
        Files.writeString(directory.resolve("settings.yml"), """
                schema: 2
                lockdown: false
                backup-format: ZIP
                """);
        reloaded.load();
        assertTrue(reloaded.disabledVanillaRecipes().isEmpty());
        assertEquals(SqlMirrorMode.NONE, reloaded.sqlMirrorBackend());
        Files.writeString(directory.resolve("settings.yml"), """
                schema: 3
                lockdown: false
                backup-format: ZIP
                disabled-vanilla-recipes: []
                """);
        reloaded.load();
        assertEquals(SqlMirrorMode.NONE, reloaded.sqlMirrorBackend());
        Files.writeString(directory.resolve("settings.yml"), """
                schema: 3
                lockdown: false
                backup-format: ZIP
                disabled-vanilla-recipes:
                - recipeweaver:invalid
                """);
        assertThrows(java.io.IOException.class, reloaded::load);
        Files.writeString(directory.resolve("settings.yml"), """
                schema: 4
                lockdown: false
                backup-format: ZIP
                disabled-vanilla-recipes: []
                sql-mirror: ORACLE
                """);
        assertThrows(java.io.IOException.class, reloaded::load);
    }

    @Test
    void remoteSqlProfilePersistsWithoutExposingPasswordAndOldSchemaGetsDefaults() throws Exception {
        SettingsRepository settings = new SettingsRepository(directory);
        RemoteSqlProfile profile = new RemoteSqlProfile(SqlMirrorMode.POSTGRESQL,
                "jdbc:postgresql://127.0.0.1:5432/recipes", "rw_admin", "test-secret");
        settings.setRemoteSqlProfile(profile);
        settings.setSqlMirrorMode(SqlMirrorMode.POSTGRESQL);
        SettingsRepository reloaded = new SettingsRepository(directory);
        reloaded.load();
        assertEquals(profile, reloaded.remoteSqlProfile());
        assertEquals(SqlMirrorMode.POSTGRESQL, reloaded.sqlMirrorBackend());
        assertFalse(reloaded.remoteSqlProfile().toString().contains("test-secret"));
        assertThrows(IllegalArgumentException.class,
                () -> reloaded.setRemoteSqlProfile(profile.withBackend(SqlMirrorMode.MYSQL)));
        Files.writeString(directory.resolve("settings.yml"), """
                schema: 4
                lockdown: false
                backup-format: ZIP
                disabled-vanilla-recipes: []
                sql-mirror: NONE
                """);
        reloaded.load();
        assertEquals(RemoteSqlProfile.defaults(), reloaded.remoteSqlProfile());
    }

    @Test
    void schemaFiveRejectsMismatchedRemoteMirrorAndMalformedCredentials() throws Exception {
        String valid = """
                schema: 5
                lockdown: false
                backup-format: ZIP
                disabled-vanilla-recipes: []
                sql-mirror: POSTGRESQL
                remote-sql:
                  backend: MYSQL
                  jdbc-url: jdbc:mysql://127.0.0.1:3306/recipes
                  username: rw_admin
                  password: test-secret
                """;
        Files.writeString(directory.resolve("settings.yml"), valid);
        SettingsRepository settings = new SettingsRepository(directory);
        assertThrows(java.io.IOException.class, settings::load);
        Files.writeString(directory.resolve("settings.yml"),
                valid.replace("sql-mirror: POSTGRESQL", "sql-mirror: NONE")
                        .replace("password: test-secret", "password: [invalid]"));
        assertThrows(java.io.IOException.class, settings::load);
    }

    @Test
    void orderedMirrorsSurviveSaveReloadAndOtherSettingChanges() throws Exception {
        SettingsRepository settings = new SettingsRepository(directory);
        RemoteSqlProfile profile = RemoteSqlProfile.defaults().withBackend(SqlMirrorMode.POSTGRESQL);
        settings.setRemoteSqlProfile(profile);
        List<SqlMirrorMode> order = List.of(SqlMirrorMode.H2, SqlMirrorMode.POSTGRESQL,
                SqlMirrorMode.SQLITE);
        settings.setSqlMirrors(order);
        settings.setBookItemEnabled(true);
        SettingsRepository loaded = new SettingsRepository(directory);
        loaded.load();
        assertEquals(order, loaded.sqlMirrors());
        assertEquals(SqlMirrorMode.H2, loaded.sqlMirrorBackend());
        assertTrue(loaded.bookItemEnabled());
        assertTrue(Files.readString(directory.resolve("settings.yml")).contains("sql-mirrors:"));
        assertThrows(IllegalArgumentException.class,
                () -> loaded.setRemoteSqlProfile(profile.withBackend(SqlMirrorMode.MYSQL)));
    }

    @Test
    void schemaFiveSingleMirrorMigratesAndSchemaSixRejectsBadLists() throws Exception {
        SettingsRepository settings = new SettingsRepository(directory);
        String older = """
                schema: 5
                lockdown: false
                backup-format: ZIP
                disabled-vanilla-recipes: []
                sql-mirror: H2
                remote-sql:
                  backend: MYSQL
                  jdbc-url: jdbc:mysql://127.0.0.1:3306/recipeweaver
                  username: ''
                  password: ''
                """;
        Files.writeString(directory.resolve("settings.yml"), older);
        settings.load();
        assertEquals(List.of(SqlMirrorMode.H2), settings.sqlMirrors());
        settings.setBookItemEnabled(true);
        assertTrue(Files.readString(directory.resolve("settings.yml")).contains("schema: 9"));
        String current = Files.readString(directory.resolve("settings.yml"));
        for (String bad : List.of("[SQLITE, SQLITE]", "[NONE]", "[MYSQL, POSTGRESQL]",
                "[POSTGRESQL]", "[bogus]")) {
            Files.writeString(directory.resolve("settings.yml"), current.replace(
                    "sql-mirrors:\n- H2", "sql-mirrors: " + bad));
            assertThrows(java.io.IOException.class, settings::load);
        }
        Files.writeString(directory.resolve("settings.yml"), current);
        settings.load();
        assertEquals(List.of(SqlMirrorMode.H2), settings.sqlMirrors());
    }

    @Test
    void startupRecoveryPolicyPersistsAndOldSettingsDefaultToLocalOnly() throws Exception {
        SettingsRepository settings = new SettingsRepository(directory);
        assertThrows(IllegalArgumentException.class,
                () -> settings.setSqlReadPolicy(SqlReadPolicy.RECOVER_ON_STARTUP));
        settings.setSqlMirrors(List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2));
        settings.setSqlReadPolicy(SqlReadPolicy.RECOVER_ON_STARTUP);
        SettingsRepository loaded = new SettingsRepository(directory);
        loaded.load();
        assertEquals(SqlReadPolicy.RECOVER_ON_STARTUP, loaded.sqlReadPolicy());
        assertEquals(List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2), loaded.sqlMirrors());
        settings.setSqlMirrors(List.of());
        loaded.load();
        assertEquals(SqlReadPolicy.LOCAL_ONLY, loaded.sqlReadPolicy());
        assertTrue(loaded.sqlMirrors().isEmpty());

        String oldSchema = """
                schema: 6
                lockdown: false
                backup-format: ZIP
                disabled-vanilla-recipes: []
                sql-mirrors:
                - SQLITE
                remote-sql:
                  backend: MYSQL
                  jdbc-url: jdbc:mysql://127.0.0.1:3306/recipeweaver
                  username: ''
                  password: ''
                """;
        Files.writeString(directory.resolve("settings.yml"), oldSchema);
        loaded.load();
        assertEquals(SqlReadPolicy.LOCAL_ONLY, loaded.sqlReadPolicy());
        assertEquals(List.of(SqlMirrorMode.SQLITE), loaded.sqlMirrors());
    }

    @Test
    void preferredSqlStartupPolicyPersistsAndSchemaSevenMigrates() throws Exception {
        SettingsRepository settings = new SettingsRepository(directory);
        assertThrows(IllegalArgumentException.class,
                () -> settings.setSqlReadPolicy(SqlReadPolicy.PREFER_SQL_AT_STARTUP));
        settings.setSqlMirrors(List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2));
        settings.setSqlReadPolicy(SqlReadPolicy.RECOVER_ON_STARTUP);
        Path file = directory.resolve("settings.yml");
        Files.writeString(file, Files.readString(file).replace("schema: 9", "schema: 7"));
        settings.load();
        assertEquals(SqlReadPolicy.RECOVER_ON_STARTUP, settings.sqlReadPolicy());
        settings.setSqlReadPolicy(SqlReadPolicy.PREFER_SQL_AT_STARTUP);
        assertTrue(Files.readString(file).contains("schema: 9"));
        SettingsRepository loaded = new SettingsRepository(directory);
        loaded.load();
        assertEquals(SqlReadPolicy.PREFER_SQL_AT_STARTUP, loaded.sqlReadPolicy());
        assertEquals(List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2), loaded.sqlMirrors());
        loaded.setSqlMirrors(List.of());
        assertEquals(SqlReadPolicy.LOCAL_ONLY, loaded.sqlReadPolicy());
    }

    @Test
    void schemaSevenRejectsUnknownRecoveryPolicyAndEmptyRecoverySelection() throws Exception {
        SettingsRepository settings = new SettingsRepository(directory);
        settings.setSqlMirrors(List.of(SqlMirrorMode.SQLITE));
        settings.setSqlReadPolicy(SqlReadPolicy.RECOVER_ON_STARTUP);
        String valid = Files.readString(directory.resolve("settings.yml"));
        Files.writeString(directory.resolve("settings.yml"), valid.replace(
                "sql-read-policy: RECOVER_ON_STARTUP", "sql-read-policy: UNKNOWN"));
        assertThrows(java.io.IOException.class, settings::load);
        Files.writeString(directory.resolve("settings.yml"), valid.replace(
                "sql-mirrors:\n- SQLITE", "sql-mirrors: []"));
        assertThrows(java.io.IOException.class, settings::load);
    }

    @Test
    void runtimeSqlReloadPolicyPersistsAndSchemaEightLoadsStartupOnlyPreference() throws Exception {
        SettingsRepository settings = new SettingsRepository(directory);
        assertThrows(IllegalArgumentException.class,
                () -> settings.setSqlReadPolicy(SqlReadPolicy.PREFER_SQL_AT_STARTUP_AND_RELOAD));
        settings.setSqlMirrors(List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2));
        settings.setSqlReadPolicy(SqlReadPolicy.PREFER_SQL_AT_STARTUP);
        Path file = directory.resolve("settings.yml");
        Files.writeString(file, Files.readString(file).replace("schema: 9", "schema: 8"));
        settings.load();
        assertEquals(SqlReadPolicy.PREFER_SQL_AT_STARTUP, settings.sqlReadPolicy());
        settings.setSqlReadPolicy(SqlReadPolicy.PREFER_SQL_AT_STARTUP_AND_RELOAD);
        assertTrue(Files.readString(file).contains("schema: 9"));
        SettingsRepository loaded = new SettingsRepository(directory);
        loaded.load();
        assertEquals(SqlReadPolicy.PREFER_SQL_AT_STARTUP_AND_RELOAD, loaded.sqlReadPolicy());
        loaded.setSqlMirrors(List.of());
        assertEquals(SqlReadPolicy.LOCAL_ONLY, loaded.sqlReadPolicy());
    }

    @Test
    void schemaEightRejectsRuntimeOnlyPolicyValue() throws Exception {
        SettingsRepository settings = new SettingsRepository(directory);
        settings.setSqlMirrors(List.of(SqlMirrorMode.SQLITE));
        settings.setSqlReadPolicy(SqlReadPolicy.PREFER_SQL_AT_STARTUP_AND_RELOAD);
        Path file = directory.resolve("settings.yml");
        Files.writeString(file, Files.readString(file).replace("schema: 9", "schema: 8"));
        assertThrows(java.io.IOException.class, settings::load);
    }

    private static void drain(Queue<Runnable> callbacks) {
        Runnable callback;
        while ((callback = callbacks.poll()) != null) callback.run();
    }
}
