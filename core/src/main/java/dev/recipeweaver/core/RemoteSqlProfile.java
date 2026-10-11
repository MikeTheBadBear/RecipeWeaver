package dev.recipeweaver.core;

import dev.recipeweaver.api.SqlMirrorMode;
import java.util.Locale;
import java.util.Objects;

/** Connection settings for one selected remote mirror or snapshot target. */
record RemoteSqlProfile(SqlMirrorMode backend, String jdbcUrl, String username, String password) {
    RemoteSqlProfile {
        Objects.requireNonNull(backend, "backend");
        if (!backend.remote()) throw new IllegalArgumentException("Choose a remote SQL backend");
        if (Objects.requireNonNull(jdbcUrl, "jdbcUrl").chars().anyMatch(Character::isISOControl)
                || Objects.requireNonNull(username, "username").chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid SQL connection text");
        }
        jdbcUrl = jdbcUrl.trim();
        username = username.trim();
        password = Objects.requireNonNull(password, "password");
        if (jdbcUrl.length() > 1024 || jdbcUrl.chars().anyMatch(Character::isISOControl)
                || !jdbcUrl.startsWith(prefix(backend))) {
            throw new IllegalArgumentException("JDBC URL does not match " + backend);
        }
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        if (lower.matches(".*(?:[?;&]|:)\\s*(?:user|username|password|pwd)\\s*=.*")) {
            throw new IllegalArgumentException("Enter credentials in the separate fields");
        }
        if (username.length() > 48 || username.chars().anyMatch(Character::isISOControl)
                || password.length() > 1024 || password.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid SQL credentials");
        }
    }

    static RemoteSqlProfile defaults() {
        return new RemoteSqlProfile(SqlMirrorMode.MYSQL,
                defaultUrl(SqlMirrorMode.MYSQL), "", "");
    }

    static String defaultUrl(SqlMirrorMode backend) {
        return switch (backend) {
            case MYSQL -> "jdbc:mysql://127.0.0.1:3306/recipeweaver";
            case MARIADB -> "jdbc:mariadb://127.0.0.1:3306/recipeweaver";
            case POSTGRESQL -> "jdbc:postgresql://127.0.0.1:5432/recipeweaver";
            case SQL_SERVER -> "jdbc:sqlserver://127.0.0.1:1433;databaseName=recipeweaver";
            case ORACLE -> "jdbc:oracle:thin:@//127.0.0.1:1521/FREEPDB1";
            default -> throw new IllegalArgumentException("Choose a remote SQL backend");
        };
    }

    private static String prefix(SqlMirrorMode backend) {
        return switch (backend) {
            case MYSQL -> "jdbc:mysql:";
            case MARIADB -> "jdbc:mariadb:";
            case POSTGRESQL -> "jdbc:postgresql:";
            case SQL_SERVER -> "jdbc:sqlserver:";
            case ORACLE -> "jdbc:oracle:";
            default -> throw new IllegalArgumentException("Choose a remote SQL backend");
        };
    }

    RemoteSqlProfile withBackend(SqlMirrorMode selected) {
        return new RemoteSqlProfile(selected, defaultUrl(selected), "", "");
    }

    RemoteSqlProfile withUrl(String value) { return new RemoteSqlProfile(backend, value, username, password); }
    RemoteSqlProfile withUsername(String value) { return new RemoteSqlProfile(backend, jdbcUrl, value, password); }
    RemoteSqlProfile withPassword(String value) { return new RemoteSqlProfile(backend, jdbcUrl, username, value); }

    SqlDocumentStore store() {
        return new SqlDocumentStore(SqlDocumentStore.Backend.valueOf(backend.name()),
                jdbcUrl, username, password);
    }

    @Override public String toString() {
        return "RemoteSqlProfile[backend=" + backend + ", jdbcUrl=<redacted>, username="
                + username + ", password=<redacted>]";
    }
}
