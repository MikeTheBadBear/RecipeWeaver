package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.recipeweaver.api.SqlReadPolicy;
import org.junit.jupiter.api.Test;

final class StudioMenusSqlPolicyTest {
    @Test
    void noSelectedSqlSourceLeavesTheMenuAtLocalOnly() {
        assertNull(StudioMenus.nextSqlReadPolicy(SqlReadPolicy.LOCAL_ONLY, false));
    }

    @Test
    void selectedSourceEnablesEveryReadPolicyAndCyclesBackToLocal() {
        SqlReadPolicy policy = SqlReadPolicy.LOCAL_ONLY;
        policy = StudioMenus.nextSqlReadPolicy(policy, true);
        assertEquals(SqlReadPolicy.RECOVER_ON_STARTUP, policy);
        policy = StudioMenus.nextSqlReadPolicy(policy, true);
        assertEquals(SqlReadPolicy.PREFER_SQL_AT_STARTUP, policy);
        policy = StudioMenus.nextSqlReadPolicy(policy, true);
        assertEquals(SqlReadPolicy.PREFER_SQL_AT_STARTUP_AND_RELOAD, policy);
        assertEquals(SqlReadPolicy.LOCAL_ONLY, StudioMenus.nextSqlReadPolicy(policy, true));
    }
}
