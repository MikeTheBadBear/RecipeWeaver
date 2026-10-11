package dev.recipeweaver.core;

import dev.recipeweaver.api.SqlMirrorMode;
import java.util.List;

/** Keeps remote SQL controls bound to the connection shown when the menu opened. */
record RemoteSqlPageSnapshot(RemoteSqlProfile profile, List<SqlMirrorMode> selectedSources) {
    RemoteSqlPageSnapshot {
        java.util.Objects.requireNonNull(profile, "profile");
        selectedSources = List.copyOf(selectedSources);
    }

    boolean stillCurrent(RemoteSqlProfile current, List<SqlMirrorMode> selected) {
        return profile.equals(current) && selectedSources.equals(selected);
    }
}
