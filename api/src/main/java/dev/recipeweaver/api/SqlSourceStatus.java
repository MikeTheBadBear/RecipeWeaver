package dev.recipeweaver.api;

import java.util.Objects;

/** Read-only SQL inspection. An invalid but readable snapshot keeps its hash for guarded local replacement. */
public record SqlSourceStatus(SqlMirrorMode backend, int documents, int missing,
                              int changed, int extra, String sha256, String failure,
                              boolean comparisonAvailable) {
    public SqlSourceStatus(SqlMirrorMode backend, int documents, int missing,
                           int changed, int extra, String sha256, String failure) {
        this(backend, documents, missing, changed, extra, sha256, failure, true);
    }

    public SqlSourceStatus {
        Objects.requireNonNull(backend, "backend");
        if (backend == SqlMirrorMode.NONE || documents < 0 || missing < 0
                || changed < 0 || extra < 0 || (failure == null && sha256 == null)) {
            throw new IllegalArgumentException("Invalid SQL source status");
        }
        if (!comparisonAvailable && (missing != 0 || changed != 0 || extra != 0)) {
            throw new IllegalArgumentException("Unavailable comparison cannot report differences");
        }
    }

    public boolean current() {
        return failure == null && comparisonAvailable
                && missing == 0 && changed == 0 && extra == 0;
    }
}
