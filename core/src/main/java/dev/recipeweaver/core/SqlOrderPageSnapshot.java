package dev.recipeweaver.core;

import dev.recipeweaver.api.SqlMirrorMode;
import java.util.List;

/** Binds SQL menu positions to the source order the player actually saw. */
record SqlOrderPageSnapshot(List<SqlMirrorMode> sources, RemoteSqlProfile profile) {
    SqlOrderPageSnapshot {
        sources = List.copyOf(sources);
        java.util.Objects.requireNonNull(profile, "profile");
    }

    boolean stillCurrent(List<SqlMirrorMode> selected, RemoteSqlProfile currentProfile) {
        return sources.equals(selected) && profile.equals(currentProfile);
    }

    SqlMirrorMode sourceAt(int index) {
        return index >= 0 && index < sources.size() ? sources.get(index) : null;
    }
}
