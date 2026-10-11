package dev.recipeweaver.core;

import dev.recipeweaver.api.SqlMirrorMode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Run with a test database URL and user; no production credentials are used. */
final class RemoteSqlIntegrationTest {
    @TempDir Path root;

    @Test
    @EnabledIfEnvironmentVariable(named = "RW_TEST_POSTGRES_URL", matches = ".+")
    void postgresqlApplicationSnapshotRestoreAndLiveMirrorRoundTrip() throws Exception {
        roundTrip(new RemoteSqlProfile(SqlMirrorMode.POSTGRESQL,
                System.getenv("RW_TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("RW_TEST_POSTGRES_USER", "rw_smoke"),
                System.getenv().getOrDefault("RW_TEST_POSTGRES_PASSWORD", "")));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RW_TEST_MYSQL_URL", matches = ".+")
    void mysqlApplicationSnapshotRestoreAndLiveMirrorRoundTrip() throws Exception {
        roundTrip(new RemoteSqlProfile(SqlMirrorMode.MYSQL,
                System.getenv("RW_TEST_MYSQL_URL"),
                System.getenv().getOrDefault("RW_TEST_MYSQL_USER", "root"),
                System.getenv().getOrDefault("RW_TEST_MYSQL_PASSWORD", "")));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RW_TEST_MARIADB_URL", matches = ".+")
    void mariaDbDriverAgainstMySqlRoundTrip() throws Exception {
        roundTrip(new RemoteSqlProfile(SqlMirrorMode.MARIADB,
                System.getenv("RW_TEST_MARIADB_URL"),
                System.getenv().getOrDefault("RW_TEST_MYSQL_USER", "root"),
                System.getenv().getOrDefault("RW_TEST_MYSQL_PASSWORD", "")));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RW_TEST_SQL_SERVER_URL", matches = ".+")
    void sqlServerApplicationSnapshotRestoreAndLiveMirrorRoundTrip() throws Exception {
        roundTrip(new RemoteSqlProfile(SqlMirrorMode.SQL_SERVER,
                System.getenv("RW_TEST_SQL_SERVER_URL"),
                System.getenv().getOrDefault("RW_TEST_SQL_SERVER_USER", "rw_smoke"),
                System.getenv().getOrDefault("RW_TEST_SQL_SERVER_PASSWORD", "")));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RW_TEST_ORACLE_URL", matches = ".+")
    void oracleApplicationSnapshotRestoreAndLiveMirrorRoundTrip() throws Exception {
        roundTrip(new RemoteSqlProfile(SqlMirrorMode.ORACLE,
                System.getenv("RW_TEST_ORACLE_URL"),
                System.getenv().getOrDefault("RW_TEST_ORACLE_USER", "rw_smoke"),
                System.getenv().getOrDefault("RW_TEST_ORACLE_PASSWORD", "")));
    }

    private void roundTrip(RemoteSqlProfile profile) throws Exception {
        SettingsRepository settings = new SettingsRepository(root);
        settings.setRemoteSqlProfile(profile);
        settings.setLockdown(true);
        BackupService backups = new BackupService(plugin(root), settings::load);
        SqlSnapshotService snapshots = new SqlSnapshotService(root, backups);
        try (AsyncIoService io = new AsyncIoService(Logger.getLogger("RemoteSqlIntegration"), root)) {
            assertTrue(snapshots.testRemoteAsync(profile, io).get(20, TimeUnit.SECONDS));
            assertEquals(1, snapshots.exportRemoteAsync(profile, io).get(20, TimeUnit.SECONDS));
            assertTrue(profile.store().readSnapshot().file("settings.yml")
                    .contains("lockdown: true"));
            settings.setLockdown(false);
            String prepared = snapshots.prepareRemoteRestoreAsync(profile, io)
                    .get(20, TimeUnit.SECONDS);
            assertTrue(Files.isRegularFile(root.resolve("backups").resolve(prepared)
                    .resolve("settings.yml")));
            backups.restore(prepared);
            assertTrue(settings.lockdown());
            assertEquals(profile, settings.remoteSqlProfile());

            Path item = root.resolve("items.yml");
            io.submit(() -> { io.configureMirror(profile.backend(), profile); return null; })
                    .get(20, TimeUnit.SECONDS);
            io.submit(() -> { io.writeDocument(item, "live: first\n"); return null; })
                    .get(20, TimeUnit.SECONDS);
            assertEquals("live: first\n", profile.store().readSnapshot().file("items.yml"));
            io.submit(() -> { io.deleteDocument(item); return null; }).get(20, TimeUnit.SECONDS);
            assertFalse(Files.exists(item));
            assertTrue(profile.store().readAll().get("items.yml").deleted());
        }
    }

    private static Plugin plugin(Path root) {
        File dataFolder = root.toFile();
        Logger logger = Logger.getLogger("RemoteSqlIntegration");
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getDataFolder" -> dataFolder;
                    case "getName" -> "RecipeWeaver";
                    case "getLogger" -> logger;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
