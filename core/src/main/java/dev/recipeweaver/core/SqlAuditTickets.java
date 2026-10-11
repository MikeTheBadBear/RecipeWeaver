package dev.recipeweaver.core;

import dev.recipeweaver.api.SqlMirrorMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Rejects delayed SQL menu audits after a newer audit or source change. */
final class SqlAuditTickets {
    private final Map<UUID, Request> active = new HashMap<>();
    private long sequence;

    Request begin(UUID player, List<SqlMirrorMode> selected, RemoteSqlProfile profile) {
        Request request = new Request(++sequence, List.copyOf(selected), profile);
        active.put(player, request);
        return request;
    }

    boolean current(UUID player, Request request, List<SqlMirrorMode> selected,
                    RemoteSqlProfile profile) {
        return active.get(player) == request && request.selected().equals(selected)
                && request.profile().equals(profile);
    }

    void remove(UUID player) { active.remove(player); }
    void invalidateAll() { active.clear(); }

    record Request(long sequence, List<SqlMirrorMode> selected, RemoteSqlProfile profile) { }
}
