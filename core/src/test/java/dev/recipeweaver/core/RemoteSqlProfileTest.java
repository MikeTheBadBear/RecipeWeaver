package dev.recipeweaver.core;

import dev.recipeweaver.api.SqlMirrorMode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

final class RemoteSqlProfileTest {
    @Test
    void fiveRemoteBackendsHaveBundledDriversAndValidDefaultConnections() throws Exception {
        for (SqlMirrorMode mode : SqlMirrorMode.values()) {
            if (!mode.remote()) continue;
            RemoteSqlProfile profile = new RemoteSqlProfile(mode,
                    RemoteSqlProfile.defaultUrl(mode), "rw", "secret");
            assertEquals(SqlDocumentStore.Backend.valueOf(mode.name()),
                    SqlDocumentStore.Backend.valueOf(profile.backend().name()));
            assertFalse(profile.toString().contains("secret"));
            assertFalse(profile.toString().contains(profile.jdbcUrl()));
            Class.forName(switch (mode) {
                case MYSQL -> "com.mysql.cj.jdbc.Driver";
                case MARIADB -> "org.mariadb.jdbc.Driver";
                case POSTGRESQL -> "org.postgresql.Driver";
                case SQL_SERVER -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
                case ORACLE -> "oracle.jdbc.OracleDriver";
                default -> throw new AssertionError(mode);
            });
            profile.store();
        }
    }

    @Test
    void invalidSchemesEmbeddedCredentialsAndControlCharactersAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new RemoteSqlProfile(SqlMirrorMode.SQLITE,
                        "jdbc:sqlite:local", "", ""));
        assertThrows(IllegalArgumentException.class, () ->
                new RemoteSqlProfile(SqlMirrorMode.POSTGRESQL,
                        "jdbc:mysql://localhost/recipes", "", ""));
        assertThrows(IllegalArgumentException.class, () ->
                new RemoteSqlProfile(SqlMirrorMode.MYSQL,
                        "jdbc:mysql://localhost/recipes?password=leaked", "", ""));
        assertThrows(IllegalArgumentException.class, () ->
                new RemoteSqlProfile(SqlMirrorMode.MYSQL,
                        "jdbc:mysql://localhost/recipes", "admin\n", ""));
        assertTrue(RemoteSqlProfile.defaults().withBackend(SqlMirrorMode.ORACLE)
                .password().isEmpty());
    }
}
