package dev.recipeweaver.api;

/** Controls the priority of selected SQL sources at startup and during reload. */
public enum SqlReadPolicy {
    LOCAL_ONLY,
    RECOVER_ON_STARTUP,
    PREFER_SQL_AT_STARTUP,
    PREFER_SQL_AT_STARTUP_AND_RELOAD
}
