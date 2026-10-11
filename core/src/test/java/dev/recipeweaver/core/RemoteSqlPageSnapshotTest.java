package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.recipeweaver.api.SqlMirrorMode;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RemoteSqlPageSnapshotTest {
    @Test
    void backendOrCredentialsChangedOutsideTheOpenMenuRequireRefresh() {
        RemoteSqlProfile profile = RemoteSqlProfile.defaults();
        RemoteSqlPageSnapshot page = new RemoteSqlPageSnapshot(profile,
                List.of(SqlMirrorMode.MYSQL));

        assertTrue(page.stillCurrent(profile, List.of(SqlMirrorMode.MYSQL)));
        assertFalse(page.stillCurrent(profile.withBackend(SqlMirrorMode.POSTGRESQL),
                List.of(SqlMirrorMode.MYSQL)));
        assertFalse(page.stillCurrent(profile.withUsername("other"),
                List.of(SqlMirrorMode.MYSQL)));
        assertFalse(page.stillCurrent(profile.withPassword("other"),
                List.of(SqlMirrorMode.MYSQL)));
        assertFalse(page.stillCurrent(profile, List.of()));
    }
}
