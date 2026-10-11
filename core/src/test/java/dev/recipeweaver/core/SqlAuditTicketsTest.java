package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.recipeweaver.api.SqlMirrorMode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SqlAuditTicketsTest {
    @Test
    void newerAuditAndChangedSourceRejectOlderMenuResults() {
        SqlAuditTickets tickets = new SqlAuditTickets();
        UUID player = UUID.randomUUID();
        RemoteSqlProfile profile = RemoteSqlProfile.defaults();
        List<SqlMirrorMode> selected = List.of(SqlMirrorMode.SQLITE);
        SqlAuditTickets.Request first = tickets.begin(player, selected, profile);
        SqlAuditTickets.Request second = tickets.begin(player, selected, profile);
        assertFalse(tickets.current(player, first, selected, profile));
        assertTrue(tickets.current(player, second, selected, profile));

        assertFalse(tickets.current(player, second, List.of(SqlMirrorMode.H2), profile));
        assertFalse(tickets.current(player, second, selected,
                profile.withBackend(SqlMirrorMode.POSTGRESQL)));
        tickets.invalidateAll();
        assertFalse(tickets.current(player, second, selected, profile));
    }

    @Test
    void onePlayersAuditDoesNotInvalidateAnotherPlayersAudit() {
        SqlAuditTickets tickets = new SqlAuditTickets();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        RemoteSqlProfile profile = RemoteSqlProfile.defaults();
        List<SqlMirrorMode> selected = List.of(SqlMirrorMode.SQLITE);
        SqlAuditTickets.Request first = tickets.begin(firstPlayer, selected, profile);
        SqlAuditTickets.Request second = tickets.begin(secondPlayer, selected, profile);
        tickets.remove(firstPlayer);
        assertFalse(tickets.current(firstPlayer, first, selected, profile));
        assertTrue(tickets.current(secondPlayer, second, selected, profile));
    }
}
