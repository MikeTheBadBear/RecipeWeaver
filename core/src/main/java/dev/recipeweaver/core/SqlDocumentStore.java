package dev.recipeweaver.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/** Transactional raw-document storage. Bukkit item decoding stays on the server thread. */
final class SqlDocumentStore {
    private static final String TABLE = "rw_documents";
    private static final String H2_GUARD_TABLE = "rw_write_guard";
    private static final int MAX_FILES = 10000;
    private static final int MAX_STORED_ROWS = MAX_FILES * 2;
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    enum Backend {
        SQLITE("org.sqlite.JDBC", "jdbc:sqlite:", "TEXT"),
        H2("org.h2.Driver", "jdbc:h2:", "CLOB"),
        MYSQL("com.mysql.cj.jdbc.Driver", "jdbc:mysql:", "LONGTEXT"),
        MARIADB("org.mariadb.jdbc.Driver", "jdbc:mariadb:", "LONGTEXT"),
        POSTGRESQL("org.postgresql.Driver", "jdbc:postgresql:", "TEXT"),
        SQL_SERVER("com.microsoft.sqlserver.jdbc.SQLServerDriver", "jdbc:sqlserver:", "NVARCHAR(MAX)"),
        ORACLE("oracle.jdbc.OracleDriver", "jdbc:oracle:", "CLOB");

        private final String driver;
        private final String urlPrefix;
        private final String contentType;

        Backend(String driver, String urlPrefix, String contentType) {
            this.driver = driver;
            this.urlPrefix = urlPrefix;
            this.contentType = contentType;
        }
    }

    record Document(String content, boolean deleted) {
        Document {
            if (deleted == (content != null)) {
                throw new IllegalArgumentException("A document must have content or be deleted");
            }
        }

        static Document value(String content) { return new Document(Objects.requireNonNull(content), false); }
        static Document tombstone() { return new Document(null, true); }
    }

    private final Backend backend;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    SqlDocumentStore(Backend backend, String jdbcUrl, String username, String password) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        if (!jdbcUrl.startsWith(backend.urlPrefix) || jdbcUrl.length() > 2048
                || jdbcUrl.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("JDBC URL does not match " + backend);
        }
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
    }

    boolean testConnection() throws IOException {
        try (Connection connection = open()) {
            return connection.isValid(5);
        } catch (SQLException ex) {
            throw new IOException("Could not connect to " + backend, ex);
        }
    }

    /** Proves this account can save and read RecipeWeaver documents, then removes the probe. */
    boolean verifyRoundTrip() throws IOException {
        String token = UUID.randomUUID().toString().replace("-", "");
        String path = "recipes/sql_probe_" + token + ".yml";
        String content = "probe: " + token + "\n";
        insertProbe(path, content);
        try {
            if (!content.equals(readProbe(path))) {
                throw new IOException("SQL probe did not read back its saved document");
            }
            return true;
        } finally {
            removeProbe(path, content);
        }
    }

    /** Keep the connection probe independent of unrelated source validation and file limits. */
    private void insertProbe(String path, String content) throws IOException {
        try (Connection connection = open()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                ensureTable(connection);
                lockH2Source(connection);
                insert(connection, path, Document.value(content));
                connection.commit();
            } catch (SQLException | IOException ex) {
                connection.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new IOException("Could not save " + backend + " SQL probe", ex);
        }
    }

    private String readProbe(String path) throws IOException {
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT content, deleted FROM " + TABLE + " WHERE path = ?")) {
            query.setString(1, path);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next() || rows.getInt(2) != 0) return null;
                return rows.getString(1);
            }
        } catch (SQLException ex) {
            throw new IOException("Could not read " + backend + " SQL probe", ex);
        }
    }

    private void removeProbe(String path, String content) throws IOException {
        try (Connection connection = open()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                ensureTable(connection);
                lockH2Source(connection);
                Document found = readLocked(connection, path);
                if (found != null) {
                    if (found.deleted() || !content.equals(found.content())) {
                        throw new IOException("SQL probe changed before cleanup");
                    }
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM " + TABLE + " WHERE path = ?")) {
                        delete.setString(1, path);
                        if (delete.executeUpdate() != 1) {
                            throw new IOException("SQL probe cleanup did not remove its document");
                        }
                    }
                }
                connection.commit();
            } catch (SQLException | IOException ex) {
                connection.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new IOException("Could not clean up " + backend + " SQL probe", ex);
        }
    }

    Map<String, Document> readAll() throws IOException {
        try (Connection connection = open()) {
            if (!tableExists(connection)) return Map.of();
            Map<String, Document> result = new LinkedHashMap<>();
            long bytes = 0;
            int liveFiles = 0;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT path, content, deleted FROM " + TABLE + " ORDER BY path");
                 ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String path = rows.getString(1);
                    validatePath(path);
                    Document document = readDocument(path, rows.getString(2), rows.getInt(3));
                    boolean deleted = document.deleted();
                    String content = document.content();
                    if (result.size() >= MAX_STORED_ROWS) {
                        throw new IOException("SQL source exceeds stored-row limit");
                    }
                    if (!deleted) {
                        if (++liveFiles > MAX_FILES) throw new IOException("SQL source exceeds file limit");
                        bytes += content.getBytes(StandardCharsets.UTF_8).length;
                        if (bytes > MAX_BYTES) throw new IOException("SQL source exceeds byte limit");
                    }
                    if (result.putIfAbsent(path, document) != null) {
                        throw new IOException("Duplicate SQL document: " + path);
                    }
                }
            }
            return java.util.Collections.unmodifiableMap(result);
        } catch (SQLException ex) {
            throw new IOException("Could not read " + backend + " documents", ex);
        }
    }

    DiskSnapshot readSnapshot() throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        readAll().forEach((path, document) -> {
            if (!document.deleted()) values.put(path, document.content());
        });
        return DiskSnapshot.fromDocuments(values);
    }

    /** One consistent read of live files and deletion markers for audited source choices. */
    SourceSnapshot readSourceSnapshot() throws IOException {
        Map<String, Document> rows = readAll();
        return new SourceSnapshot(DiskSnapshot.fromDocuments(liveDocuments(rows)),
                SqlSnapshotService.sha256Stored(rows));
    }

    record SourceSnapshot(DiskSnapshot snapshot, String sha256) { }

    void replaceSnapshot(DiskSnapshot snapshot) throws IOException {
        Map<String, Document> documents = new LinkedHashMap<>();
        snapshot.documents().forEach((path, content) -> documents.put(path, Document.value(content)));
        replaceAll(documents);
    }

    /** Compare and replace under one transaction so an intervening SQL write cannot be lost. */
    boolean replaceSnapshotIfUnchanged(DiskSnapshot snapshot, Map<String, String> expected)
            throws IOException {
        return guardedReplace(snapshot, expected, null);
    }

    /** Explicit local-wins replacement, guarded by the SHA-256 of the inspected SQL copy. */
    boolean replaceSnapshotIfCurrentHash(DiskSnapshot snapshot, String expectedSha256)
            throws IOException {
        Objects.requireNonNull(expectedSha256, "expectedSha256");
        return guardedReplace(snapshot, null, expectedSha256);
    }

    private boolean guardedReplace(DiskSnapshot snapshot, Map<String, String> expected,
                                   String expectedSha256) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        // DiskSnapshot.read also captures regular files that repositories ignore.
        // Reject those paths before a mirror write can make the SQL source unreadable.
        for (String path : snapshot.documents().keySet()) validatePath(path);
        try (Connection connection = open()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                ensureTable(connection);
                lockH2Source(connection);
                if (backend == Backend.ORACLE) {
                    try (Statement lock = connection.createStatement()) {
                        lock.execute("LOCK TABLE " + TABLE + " IN EXCLUSIVE MODE");
                    }
                }
                Map<String, Document> rows = readAllLocked(connection);
                Map<String, String> current = liveDocuments(rows);
                boolean match = expectedSha256 != null
                        ? expectedSha256.equals(SqlSnapshotService.sha256Stored(rows))
                        : (current.equals(snapshot.documents())
                                && (expected != null || rows.size() == current.size()))
                                || (expected == null ? rows.isEmpty() : current.equals(expected));
                if (!match) {
                    connection.rollback();
                    return false;
                }
                // An explicit local-wins choice replaces the audited SQL source, including
                // deletion markers. Equal live files alone do not make that replacement a no-op.
                boolean removeAuditedDeletions = expectedSha256 != null
                        && rows.size() != current.size();
                if (!current.equals(snapshot.documents()) || removeAuditedDeletions) {
                    try (Statement clear = connection.createStatement()) {
                        clear.executeUpdate("DELETE FROM " + TABLE);
                    }
                    for (var entry : snapshot.documents().entrySet()) {
                        insert(connection, entry.getKey(), Document.value(entry.getValue()));
                    }
                }
                connection.commit();
                return true;
            } catch (SQLException | IOException ex) {
                connection.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new IOException("Could not synchronize " + backend + " documents", ex);
        }
    }

    /** Guard an incremental mirror write against another writer changing the same SQL path. */
    boolean writeIfUnchanged(String path, String expectedContent, Document desired)
            throws IOException {
        validatePath(path);
        validateDocument(desired);
        try (Connection connection = open()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                ensureTable(connection);
                lockH2Source(connection);
                Document found = readLocked(connection, path);
                String actual = found == null || found.deleted() ? null : found.content();
                String wanted = desired.deleted() ? null : desired.content();
                if (!Objects.equals(actual, expectedContent) && !Objects.equals(actual, wanted)) {
                    connection.rollback();
                    return false;
                }
                if (!Objects.equals(actual, wanted)) {
                    if (found == null) insert(connection, path, desired);
                    else update(connection, path, desired);
                }
                connection.commit();
                return true;
            } catch (SQLException | IOException ex) {
                connection.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new IOException("Could not conditionally write " + backend + " document", ex);
        }
    }

    /** An incremental mirror write must also reject independent changes to other SQL documents. */
    boolean writeIfSnapshotUnchanged(String path, Map<String, String> expected,
                                     Document desired) throws IOException {
        validatePath(path);
        Objects.requireNonNull(expected, "expected");
        validateDocument(desired);
        Map<String, String> wanted = new LinkedHashMap<>(expected);
        if (desired.deleted()) wanted.remove(path);
        else wanted.put(path, desired.content());
        // Incremental propagation must obey the same complete-snapshot limits as
        // a full export; otherwise one successful write can make the SQL source
        // unreadable by the next audit or restore.
        DiskSnapshot.fromDocuments(wanted);
        try (Connection connection = open()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                ensureTable(connection);
                lockH2Source(connection);
                if (backend == Backend.ORACLE) {
                    try (Statement lock = connection.createStatement()) {
                        lock.execute("LOCK TABLE " + TABLE + " IN EXCLUSIVE MODE");
                    }
                }
                Map<String, Document> rows = readAllLocked(connection);
                Map<String, String> current = liveDocuments(rows);
                if (!current.equals(expected) && !current.equals(wanted)) {
                    connection.rollback();
                    return false;
                }
                if (!current.equals(wanted)) {
                    if (rows.containsKey(path)) update(connection, path, desired);
                    else {
                        // Tombstones document earlier deletions, but they must not crowd out
                        // new live data or make an otherwise valid mirror unreadable.
                        if (rows.size() >= MAX_FILES) {
                            try (Statement compact = connection.createStatement()) {
                                compact.executeUpdate("DELETE FROM " + TABLE + " WHERE deleted = 1");
                            }
                        }
                        insert(connection, path, desired);
                    }
                }
                connection.commit();
                return true;
            } catch (SQLException | IOException ex) {
                connection.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new IOException("Could not conditionally write " + backend + " snapshot", ex);
        }
    }

    private Map<String, Document> readAllLocked(Connection connection)
            throws SQLException, IOException {
        String sql = "SELECT path, content, deleted FROM " + TABLE;
        if (backend == Backend.SQL_SERVER) sql += " WITH (TABLOCKX, HOLDLOCK)";
        sql += " ORDER BY path";
        if (backend != Backend.SQLITE && backend != Backend.SQL_SERVER && backend != Backend.ORACLE) {
            sql += " FOR UPDATE";
        }
        Map<String, Document> result = new LinkedHashMap<>();
        long bytes = 0;
        int liveFiles = 0;
        try (PreparedStatement query = connection.prepareStatement(sql);
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                String path = rows.getString(1);
                validatePath(path);
                Document document = readDocument(path, rows.getString(2), rows.getInt(3));
                if (result.size() >= MAX_STORED_ROWS) {
                    throw new IOException("SQL source exceeds stored-row limit");
                }
                if (!document.deleted()) {
                    if (++liveFiles > MAX_FILES) throw new IOException("SQL source exceeds file limit");
                    bytes += document.content().getBytes(StandardCharsets.UTF_8).length;
                    if (bytes > MAX_BYTES) throw new IOException("SQL source exceeds byte limit");
                }
                if (result.putIfAbsent(path, document) != null) {
                    throw new IOException("Duplicate SQL document: " + path);
                }
            }
        }
        return result;
    }

    private Document readLocked(Connection connection, String path) throws SQLException, IOException {
        String sql = "SELECT content, deleted FROM " + TABLE;
        if (backend == Backend.SQL_SERVER) sql += " WITH (UPDLOCK, HOLDLOCK)";
        sql += " WHERE path = ?";
        if (backend != Backend.SQLITE && backend != Backend.SQL_SERVER) sql += " FOR UPDATE";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, path);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return null;
                return readDocument(path, row.getString(1), row.getInt(2));
            }
        }
    }

    private static Document readDocument(String path, String content, int deleted) throws IOException {
        if (deleted != 0 && deleted != 1) {
            throw new IOException("Invalid SQL deletion marker: " + path);
        }
        try { return new Document(content, deleted == 1); }
        catch (IllegalArgumentException ex) {
            throw new IOException("Invalid SQL document: " + path, ex);
        }
    }

    private static Map<String, String> liveDocuments(Map<String, Document> documents) {
        Map<String, String> values = new LinkedHashMap<>();
        documents.forEach((path, document) -> {
            if (!document.deleted()) values.put(path, document.content());
        });
        return values;
    }

    void put(String path, String content) throws IOException {
        write(path, Document.value(content));
    }

    void delete(String path) throws IOException {
        write(path, Document.tombstone());
    }

    void write(String path, Document document) throws IOException {
        validatePath(path);
        validateDocument(document);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                ensureTable(connection);
                lockH2Source(connection);
                upsert(connection, path, document);
                connection.commit();
            } catch (SQLException | IOException ex) {
                connection.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new IOException("Could not write " + backend + " document", ex);
        }
    }

    /** Replaces the complete source in one database transaction, including deletion markers. */
    void replaceAll(Map<String, Document> documents) throws IOException {
        Objects.requireNonNull(documents, "documents");
        if (documents.size() > MAX_FILES) throw new IOException("SQL source exceeds file limit");
        long bytes = 0;
        for (var entry : documents.entrySet()) {
            validatePath(entry.getKey());
            validateDocument(entry.getValue());
            if (!entry.getValue().deleted()) {
                bytes += entry.getValue().content().getBytes(StandardCharsets.UTF_8).length;
                if (bytes > MAX_BYTES) throw new IOException("SQL source exceeds byte limit");
            }
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                ensureTable(connection);
                lockH2Source(connection);
                try (Statement clear = connection.createStatement()) {
                    clear.executeUpdate("DELETE FROM " + TABLE);
                }
                for (var entry : documents.entrySet()) {
                    insert(connection, entry.getKey(), entry.getValue());
                }
                connection.commit();
            } catch (SQLException | IOException ex) {
                connection.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new IOException("Could not replace " + backend + " documents", ex);
        }
    }

    private Connection open() throws SQLException, IOException {
        try { Class.forName(backend.driver); }
        catch (ClassNotFoundException ex) {
            throw new IOException("Bundled " + backend + " JDBC driver is missing", ex);
        }
        if (backend == Backend.SQLITE || backend == Backend.H2) {
            return DriverManager.getConnection(jdbcUrl);
        }
        Properties options = new Properties();
        if (!username.isEmpty()) options.setProperty("user", username);
        if (!password.isEmpty()) options.setProperty("password", password);
        switch (backend) {
            case MYSQL, MARIADB -> {
                options.setProperty("connectTimeout", "5000");
                options.setProperty("socketTimeout", "15000");
            }
            case POSTGRESQL -> {
                options.setProperty("loginTimeout", "5");
                options.setProperty("connectTimeout", "5");
                options.setProperty("socketTimeout", "15");
            }
            case SQL_SERVER -> {
                options.setProperty("loginTimeout", "5");
                options.setProperty("socketTimeout", "15000");
            }
            case ORACLE -> {
                options.setProperty("oracle.net.CONNECT_TIMEOUT", "5000");
                options.setProperty("oracle.jdbc.ReadTimeout", "15000");
            }
            default -> throw new IllegalStateException("Unexpected SQL backend");
        }
        return DriverManager.getConnection(jdbcUrl, options);
    }

    private void ensureTable(Connection connection) throws SQLException {
        if (!tableExists(connection)) {
            String pathType = backend == Backend.ORACLE ? "VARCHAR2(512)"
                    : backend == Backend.SQL_SERVER ? "NVARCHAR(512)" : "VARCHAR(512)";
            try (Statement create = connection.createStatement()) {
                create.executeUpdate("CREATE TABLE " + TABLE + " (path " + pathType
                        + " PRIMARY KEY, content " + backend.contentType + ", deleted SMALLINT NOT NULL)");
            }
        }
        if (backend == Backend.H2 && !tableExists(connection, H2_GUARD_TABLE)) {
            try (Statement create = connection.createStatement()) {
                create.executeUpdate("CREATE TABLE " + H2_GUARD_TABLE
                        + " (id INTEGER PRIMARY KEY, revision BIGINT NOT NULL)");
                create.executeUpdate("INSERT INTO " + H2_GUARD_TABLE + " (id, revision) VALUES (1, 0)");
            }
        }
    }

    /** H2's serializable isolation permits write skew; update one row before reading the source. */
    private void lockH2Source(Connection connection) throws SQLException {
        if (backend != Backend.H2) return;
        try (Statement lock = connection.createStatement()) {
            if (lock.executeUpdate("UPDATE " + H2_GUARD_TABLE
                    + " SET revision = revision + 1 WHERE id = 1") != 1) {
                throw new SQLException("H2 SQL write guard is missing");
            }
        }
    }

    private static boolean tableExists(Connection connection) throws SQLException {
        return tableExists(connection, TABLE);
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tables = metadata.getTables(connection.getCatalog(),
                connection.getSchema(), null, new String[] {"TABLE"})) {
            while (tables.next()) {
                if (table.equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
            }
        }
        return false;
    }

    private static void upsert(Connection connection, String path, Document document)
            throws SQLException, IOException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + TABLE + " SET content = ?, deleted = ? WHERE path = ?")) {
            bind(update, document, 1);
            update.setString(3, path);
            if (update.executeUpdate() != 0) return;
        }
        insert(connection, path, document);
    }

    private static void update(Connection connection, String path, Document document)
            throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + TABLE + " SET content = ?, deleted = ? WHERE path = ?")) {
            bind(statement, document, 1);
            statement.setString(3, path);
            if (statement.executeUpdate() != 1) throw new IOException("SQL document changed during update");
        }
    }

    private static void insert(Connection connection, String path, Document document)
            throws SQLException, IOException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (path, content, deleted) VALUES (?, ?, ?)")) {
            insert.setString(1, path);
            bind(insert, document, 2);
            if (insert.executeUpdate() != 1) throw new IOException("SQL document insert failed");
        }
    }

    private static void bind(PreparedStatement statement, Document document, int contentIndex)
            throws SQLException {
        statement.setString(contentIndex, document.content());
        statement.setInt(contentIndex + 1, document.deleted() ? 1 : 0);
    }

    private static void validateDocument(Document document) throws IOException {
        if (document == null) throw new IOException("SQL document is missing");
        if (!document.deleted() && document.content().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IOException("SQL document exceeds byte limit");
        }
    }

    static void validatePath(String path) throws IOException {
        if (path == null || path.length() > 512) throw new IOException("Invalid SQL document path");
        if (path.equals("items.yml") || path.equals("settings.yml")
                || path.equals("tables.yml") || path.equals("cauldrons.yml")) return;
        if (path.matches("(recipes|stations|grid-recipes)/[A-Za-z0-9_-]+\\.yml")) return;
        if (path.matches("discoveries/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}"
                + "[0-9a-f]{12}\\.yml")) return;
        throw new IOException("Invalid SQL document path");
    }
}
