package dev.recipeweaver.core;

import dev.recipeweaver.api.SqlMirrorMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SqlMirrorSelectionTest {
    private final RemoteSqlProfile profile = RemoteSqlProfile.defaults();

    @Test
    void toggleAndMoveKeepAnOrderedUniqueSelection() {
        List<SqlMirrorMode> selected = SqlMirrorSelection.toggle(List.of(), SqlMirrorMode.H2, profile);
        selected = SqlMirrorSelection.toggle(selected, SqlMirrorMode.SQLITE, profile);
        selected = SqlMirrorSelection.toggle(selected, SqlMirrorMode.MYSQL, profile);
        assertEquals(List.of(SqlMirrorMode.H2, SqlMirrorMode.SQLITE, SqlMirrorMode.MYSQL), selected);
        assertEquals(List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2, SqlMirrorMode.MYSQL),
                SqlMirrorSelection.move(selected, 1, -1, profile));
        assertEquals(selected, SqlMirrorSelection.move(selected, 0, -1, profile));
        assertEquals(List.of(SqlMirrorMode.H2, SqlMirrorMode.MYSQL),
                SqlMirrorSelection.toggle(selected, SqlMirrorMode.SQLITE, profile));
    }

    @Test
    void rejectDuplicateNoneMismatchedRemoteAndTooManyTargets() {
        for (List<SqlMirrorMode> invalid : List.of(
                List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.SQLITE),
                List.of(SqlMirrorMode.NONE),
                List.of(SqlMirrorMode.POSTGRESQL),
                List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2,
                        SqlMirrorMode.MYSQL, SqlMirrorMode.POSTGRESQL))) {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlMirrorSelection.validate(invalid, profile));
        }
    }
}
