package dev.recipeweaver.api;

import java.util.Objects;

/** A selected SQL snapshot prepared as a reviewable directory backup. */
public record SqlPreparedSource(SqlMirrorMode backend, String backupName) {
    public SqlPreparedSource {
        Objects.requireNonNull(backend, "backend");
        if (backend == SqlMirrorMode.NONE || Objects.requireNonNull(backupName, "backupName").isBlank()) {
            throw new IllegalArgumentException("Invalid prepared SQL source");
        }
    }
}
