package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.recipeweaver.api.SqlMirrorMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SqlOrderPageSnapshotTest {
    @Test
    void changedSourceOrderCannotRetargetADisplayedSourceClick() {
        RemoteSqlProfile profile = RemoteSqlProfile.defaults();
        SqlOrderPageSnapshot page = new SqlOrderPageSnapshot(
                List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2), profile);

        assertEquals(SqlMirrorMode.SQLITE, page.sourceAt(0));
        assertEquals(SqlMirrorMode.H2, page.sourceAt(1));
        assertTrue(page.stillCurrent(List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2), profile));
        assertFalse(page.stillCurrent(List.of(SqlMirrorMode.H2, SqlMirrorMode.SQLITE), profile));
        assertFalse(page.stillCurrent(List.of(SqlMirrorMode.H2), profile));
        assertNull(page.sourceAt(2));
    }

    @Test
    void laterMutationsOfSourceListDoNotChangeDisplayedPositions() {
        List<SqlMirrorMode> selected = new ArrayList<>(List.of(SqlMirrorMode.SQLITE));
        RemoteSqlProfile profile = RemoteSqlProfile.defaults();
        SqlOrderPageSnapshot page = new SqlOrderPageSnapshot(selected, profile);

        selected.add(SqlMirrorMode.H2);

        assertEquals(List.of(SqlMirrorMode.SQLITE), page.sources());
        assertFalse(page.stillCurrent(selected, profile));
    }

    @Test
    void changedRemoteConnectionInvalidatesTheDisplayedSqlMenu() {
        RemoteSqlProfile profile = RemoteSqlProfile.defaults();
        SqlOrderPageSnapshot page = new SqlOrderPageSnapshot(
                List.of(SqlMirrorMode.MYSQL), profile);

        assertFalse(page.stillCurrent(List.of(SqlMirrorMode.MYSQL),
                profile.withUrl("jdbc:mysql://127.0.0.1:3306/other_db")));
        assertFalse(page.stillCurrent(List.of(SqlMirrorMode.MYSQL),
                profile.withPassword("changed")));
    }
}
