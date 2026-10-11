package dev.recipeweaver.core;

import dev.recipeweaver.api.SqlMirrorMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Local files are first; these are the ordered SQL write targets that follow. */
final class SqlMirrorSelection {
    private SqlMirrorSelection() { }

    static List<SqlMirrorMode> validate(List<SqlMirrorMode> selected, RemoteSqlProfile profile) {
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(profile, "profile");
        if (selected.size() > 3) throw new IllegalArgumentException("At most three SQL mirrors are supported");
        Set<SqlMirrorMode> seen = new HashSet<>();
        int remoteCount = 0;
        for (SqlMirrorMode mode : selected) {
            if (mode == null || mode == SqlMirrorMode.NONE || !seen.add(mode)) {
                throw new IllegalArgumentException("SQL mirrors must be unique nonempty backends");
            }
            if (mode.remote()) {
                remoteCount++;
                if (mode != profile.backend()) {
                    throw new IllegalArgumentException("Remote SQL mirror does not match its profile");
                }
            }
        }
        if (remoteCount > 1) throw new IllegalArgumentException("Only one remote SQL profile is configured");
        return List.copyOf(selected);
    }

    static List<SqlMirrorMode> toggle(List<SqlMirrorMode> current, SqlMirrorMode target,
                                      RemoteSqlProfile profile) {
        if (target == null || target == SqlMirrorMode.NONE) {
            throw new IllegalArgumentException("Choose a SQL backend");
        }
        List<SqlMirrorMode> next = new ArrayList<>(current);
        if (!next.remove(target)) next.add(target);
        return validate(next, profile);
    }

    static List<SqlMirrorMode> move(List<SqlMirrorMode> current, int index, int direction,
                                    RemoteSqlProfile profile) {
        int target = index + direction;
        if (index < 0 || index >= current.size() || target < 0 || target >= current.size()) {
            return validate(current, profile);
        }
        List<SqlMirrorMode> next = new ArrayList<>(current);
        java.util.Collections.swap(next, index, target);
        return validate(next, profile);
    }
}
