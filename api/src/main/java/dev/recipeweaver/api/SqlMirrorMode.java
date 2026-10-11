package dev.recipeweaver.api;

/** Local file data remains authoritative; the selected SQL database receives ordered copies. */
public enum SqlMirrorMode {
    NONE,
    SQLITE,
    H2,
    MYSQL,
    MARIADB,
    POSTGRESQL,
    SQL_SERVER,
    ORACLE;

    public boolean remote() {
        return this != NONE && this != SQLITE && this != H2;
    }
}
