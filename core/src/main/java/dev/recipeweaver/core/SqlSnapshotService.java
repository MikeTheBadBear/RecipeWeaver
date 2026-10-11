package dev.recipeweaver.core;

import dev.recipeweaver.api.SqlMirrorMode;
import dev.recipeweaver.api.SqlPreparedSource;
import dev.recipeweaver.api.SqlSourceStatus;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Manual local SQL snapshots. The live repositories still use their ordered local files. */
final class SqlSnapshotService {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private final Path dataRoot;
    private final BackupService backups;
    private final AtomicBoolean busy = new AtomicBoolean();

    SqlSnapshotService(Path dataRoot, BackupService backups) {
        this.dataRoot = dataRoot;
        this.backups = backups;
    }

    boolean busy() { return busy.get(); }

    CompletableFuture<Integer> exportAsync(SqlDocumentStore.Backend backend, AsyncIoService io) {
        return exportToAsync(io, SqlMirrorMode.valueOf(backend.name()),
                () -> localStore(dataRoot, backend));
    }

    CompletableFuture<Integer> exportRemoteAsync(RemoteSqlProfile profile, AsyncIoService io) {
        return exportToAsync(io, profile.backend(), profile::store);
    }

    private CompletableFuture<Integer> exportToAsync(AsyncIoService io, SqlMirrorMode mode,
                                                      Callable<SqlDocumentStore> target) {
        return run(io, () -> {
            DiskSnapshot source = DiskSnapshot.read(dataRoot);
            SqlDocumentStore store = target.call();
            store.replaceSnapshot(source);
            if (!source.documents().equals(store.readSnapshot().documents())) {
                throw new IOException("SQL snapshot verification failed");
            }
            io.mirrorExported(mode, source);
            return source.documents().size();
        });
    }

    /** Materializes an SQL snapshot as a regular directory backup for the validated restore flow. */
    CompletableFuture<String> prepareRestoreAsync(SqlDocumentStore.Backend backend, AsyncIoService io) {
        return prepareRestoreFromAsync(io, () -> localStore(dataRoot, backend));
    }

    CompletableFuture<String> prepareRemoteRestoreAsync(RemoteSqlProfile profile, AsyncIoService io) {
        return prepareRestoreFromAsync(io, profile::store);
    }

    CompletableFuture<Boolean> testRemoteAsync(RemoteSqlProfile profile, AsyncIoService io) {
        return run(io, () -> profile.store().verifyRoundTrip());
    }

    CompletableFuture<List<SqlSourceStatus>> auditAsync(List<SqlMirrorMode> selected,
                                                          RemoteSqlProfile profile, AsyncIoService io) {
        return auditCandidatesAsync(selected, profile, io).thenApply(candidates ->
                candidates.stream().map(AuditCandidate::status).toList());
    }

    /** Reads SQL on the I/O worker; callers decode Bukkit data on the server thread. */
    CompletableFuture<List<AuditCandidate>> auditCandidatesAsync(List<SqlMirrorMode> selected,
                                                                    RemoteSqlProfile profile,
                                                                    AsyncIoService io) {
        List<SqlMirrorMode> modes = SqlMirrorSelection.validate(selected, profile);
        return runObserved(io, () -> {
            DiskSnapshot local;
            try { local = DiskSnapshot.read(dataRoot); }
            catch (IOException unreadableLocal) { local = null; }
            List<AuditCandidate> result = new ArrayList<>();
            for (SqlMirrorMode mode : modes) {
                try {
                    SqlDocumentStore.SourceSnapshot source = store(mode, profile).readSourceSnapshot();
                    DiskSnapshot remote = source.snapshot();
                    validateSourceStructure(remote);
                    int missing = 0;
                    int changed = 0;
                    int extra = 0;
                    if (local != null) {
                        for (var document : local.documents().entrySet()) {
                            String candidate = remote.file(document.getKey());
                            if (candidate == null) missing++;
                            else if (!candidate.equals(document.getValue())) changed++;
                        }
                        for (String path : remote.documents().keySet()) {
                            if (!local.documents().containsKey(path)) extra++;
                        }
                    }
                    result.add(new AuditCandidate(new SqlSourceStatus(mode,
                            remote.documents().size(), missing, changed, extra,
                            source.sha256(), null, local != null), remote));
                } catch (IOException ex) {
                    result.add(new AuditCandidate(new SqlSourceStatus(mode, 0, 0, 0, 0,
                            null, sourceError(mode, ex)), null));
                }
            }
            return List.copyOf(result);
        });
    }

    /** An invalid decoded source cannot be offered as a selectable SQL restore. */
    static List<SqlSourceStatus> validateAuditedSources(List<AuditCandidate> candidates,
                                                         BackupService backups) {
        List<SqlSourceStatus> result = new ArrayList<>(candidates.size());
        for (AuditCandidate candidate : candidates) {
            SqlSourceStatus status = candidate.status();
            if (status.failure() != null) {
                result.add(status);
                continue;
            }
            try {
                backups.validateSnapshot(candidate.snapshot());
                result.add(status);
            } catch (IOException | IllegalArgumentException invalid) {
                result.add(new SqlSourceStatus(status.backend(), status.documents(),
                        status.missing(), status.changed(), status.extra(), status.sha256(),
                        status.backend() + " invalid snapshot", status.comparisonAvailable()));
            }
        }
        return List.copyOf(result);
    }

    record AuditCandidate(SqlSourceStatus status, DiskSnapshot snapshot) { }

    CompletableFuture<SqlPreparedSource> prepareOrderedRestoreAsync(List<SqlMirrorMode> selected,
                                                                       RemoteSqlProfile profile,
                                                                       AsyncIoService io) {
        List<SqlMirrorMode> modes = SqlMirrorSelection.validate(selected, profile);
        return run(io, () -> {
            for (SqlMirrorMode mode : modes) {
                DiskSnapshot snapshot;
                try {
                    snapshot = store(mode, profile).readSnapshot();
                    validateSourceStructure(snapshot);
                } catch (IOException ex) {
                    continue;
                }
                return new SqlPreparedSource(mode, writePreparedBackup(snapshot));
            }
            throw new IOException("No selected SQL source has a readable, valid snapshot");
        });
    }

    CompletableFuture<SqlPreparedSource> prepareSelectedRestoreAsync(List<SqlMirrorMode> selected,
                                                                       RemoteSqlProfile profile,
                                                                       SqlMirrorMode mode,
                                                                       String expectedSha256,
                                                                       AsyncIoService io) {
        List<SqlMirrorMode> modes = SqlMirrorSelection.validate(selected, profile);
        if (mode == null || !modes.contains(mode)) {
            throw new IllegalArgumentException("SQL source is not selected: " + mode);
        }
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected SQL source SHA-256 is invalid");
        }
        return runObserved(io, () -> {
            SqlDocumentStore.SourceSnapshot source = store(mode, profile).readSourceSnapshot();
            DiskSnapshot snapshot = source.snapshot();
            if (!expectedSha256.equals(source.sha256())) {
                throw new IOException("SQL source changed since inspection; inspect it again");
            }
            validateSourceStructure(snapshot);
            return new SqlPreparedSource(mode, writePreparedBackup(snapshot));
        });
    }

    private SqlDocumentStore store(SqlMirrorMode mode, RemoteSqlProfile profile) throws IOException {
        return mode.remote() ? profile.store() : localStore(dataRoot,
                SqlDocumentStore.Backend.valueOf(mode.name()));
    }

    private static String sourceError(SqlMirrorMode mode, Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                return mode + " read failed (SQLState " + sql.getSQLState() + ")";
            }
            cause = cause.getCause();
        }
        return mode + " read failed";
    }

    static String sha256(Map<String, String> documents) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            documents.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                byte[] path = entry.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] content = entry.getValue().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(path.length).array());
                digest.update(path);
                digest.update(ByteBuffer.allocate(4).putInt(content.length).array());
                digest.update(content);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /** Include deletions in audited SQL identity; sources without tombstones keep their old digest. */
    static String sha256Stored(Map<String, SqlDocumentStore.Document> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            rows.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                byte[] path = entry.getKey().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(path.length).array());
                digest.update(path);
                SqlDocumentStore.Document document = entry.getValue();
                if (document.deleted()) {
                    digest.update(ByteBuffer.allocate(4).putInt(-1).array());
                } else {
                    byte[] content = document.content().getBytes(StandardCharsets.UTF_8);
                    digest.update(ByteBuffer.allocate(4).putInt(content.length).array());
                    digest.update(content);
                }
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private CompletableFuture<String> prepareRestoreFromAsync(AsyncIoService io,
                                                               Callable<SqlDocumentStore> source) {
        return run(io, () -> {
            DiskSnapshot snapshot = source.call().readSnapshot();
            validateSourceStructure(snapshot);
            return writePreparedBackup(snapshot);
        });
    }

    private static void validateSourceStructure(DiskSnapshot snapshot) throws IOException {
        if (snapshot.file("settings.yml") == null) {
            throw new IOException("SQL snapshot has no settings.yml");
        }
        for (String path : snapshot.documents().keySet()) {
            if (!allowedBackupPath(path)) throw new IOException("Invalid SQL backup path: " + path);
        }
    }

    private String writePreparedBackup(DiskSnapshot snapshot) throws IOException {
        if (snapshot.documents().isEmpty()) throw new IOException("SQL snapshot is empty");
        Path backupRoot = dataRoot.resolve("backups");
        if (BackupService.isUnsafeLink(backupRoot)) {
            throw new IOException("Backup directory is a symbolic link or junction");
        }
        Files.createDirectories(backupRoot);
        if (BackupService.isUnsafeLink(backupRoot)) {
            throw new IOException("Backup directory is a symbolic link or junction");
        }
        Path temporary = Files.createTempDirectory(backupRoot, ".rw-sql-");
        String name = "RecipeWeaver-backup-" + STAMP.format(Instant.now()) + "-"
                + UUID.randomUUID().toString().substring(0, 8) + ".dir";
        try {
            for (var entry : snapshot.documents().entrySet()) {
                String path = entry.getKey();
                if (!allowedBackupPath(path)) throw new IOException("Invalid SQL backup path: " + path);
                Path target = temporary.resolve(path).normalize();
                if (!target.startsWith(temporary)) throw new IOException("Invalid SQL backup path: " + path);
                Files.createDirectories(target.getParent());
                Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
            }
            BackupService.moveWithRetry(temporary, backupRoot.resolve(name));
            backups.listBackups();
            return name;
        } finally {
            deleteTree(temporary);
        }
    }

    private <T> CompletableFuture<T> run(AsyncIoService io, Callable<T> action) {
        return runObserved(io, action);
    }

    private <T> CompletableFuture<T> runObserved(AsyncIoService io, Callable<T> action) {
        if (!busy.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("SQL snapshot operation in progress"));
        }
        // Every SQL inspection, connection test, export and prepare call returns its own
        // failure to the caller. An expected bad source must not poison later I/O barriers.
        return io.submitObservedAfterSuccessfulWrites(action)
                .whenComplete((result, failure) -> busy.set(false));
    }

    static SqlDocumentStore localStore(Path dataRoot, SqlDocumentStore.Backend backend)
            throws IOException {
        if (backend != SqlDocumentStore.Backend.SQLITE && backend != SqlDocumentStore.Backend.H2) {
            throw new IOException("Only local SQLite and H2 databases are available");
        }
        Path directory = dataRoot.resolve("sql-snapshots");
        if (BackupService.isUnsafeLink(directory)) {
            throw new IOException("SQL snapshot directory is a symbolic link or junction");
        }
        Files.createDirectories(directory);
        if (BackupService.isUnsafeLink(directory)) {
            throw new IOException("SQL snapshot directory is a symbolic link or junction");
        }
        if (backend == SqlDocumentStore.Backend.SQLITE) {
            Path file = directory.resolve("recipeweaver.sqlite");
            if (BackupService.isUnsafeLink(file)) {
                throw new IOException("SQL snapshot is a symbolic link or junction");
            }
            return new SqlDocumentStore(backend, "jdbc:sqlite:" + file.toAbsolutePath(), null, null);
        }
        Path file = directory.resolve("recipeweaver-h2");
        if (BackupService.isUnsafeLink(directory.resolve("recipeweaver-h2.mv.db"))) {
            throw new IOException("SQL snapshot is a symbolic link or junction");
        }
        return new SqlDocumentStore(backend,
                "jdbc:h2:file:" + file.toAbsolutePath().toString().replace('\\', '/'), null, null);
    }

    private static boolean allowedBackupPath(String name) {
        return name.equals("settings.yml") || name.equals("items.yml")
                || name.equals("tables.yml") || name.equals("cauldrons.yml")
                || name.matches("(recipes|stations|grid-recipes)/[A-Za-z0-9_-]+\\.yml")
                || name.matches("discoveries/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\\.yml");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
