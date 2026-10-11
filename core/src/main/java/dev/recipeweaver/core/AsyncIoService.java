package dev.recipeweaver.core;

import dev.recipeweaver.api.SqlMirrorMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/** One ordered worker for RecipeWeaver-owned disk operations and log emission. */
final class AsyncIoService implements AutoCloseable {
    private final Logger logger;
    private final Path dataRoot;
    private final ExecutorService executor;
    private final ScheduledExecutorService retryTimer;
    private volatile Thread worker;
    private final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    private volatile List<SqlMirrorMode> mirrorModes = List.of();
    private volatile Map<SqlMirrorMode, String> mirrorFailures = Map.of();
    private final Map<SqlMirrorMode, MirrorState> mirrors = new LinkedHashMap<>();

    AsyncIoService(Logger logger) {
        this(logger, null);
    }

    AsyncIoService(Logger logger, Path dataRoot) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dataRoot = dataRoot == null ? null : dataRoot.toAbsolutePath().normalize();
        executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "RecipeWeaver-IO");
            thread.setDaemon(false);
            worker = thread;
            return thread;
        });
        retryTimer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "RecipeWeaver-SQL-Retry");
            thread.setDaemon(true);
            return thread;
        });
    }

    SqlMirrorMode mirrorBackend() {
        return mirrorModes.isEmpty() ? SqlMirrorMode.NONE : mirrorModes.get(0);
    }

    List<SqlMirrorMode> mirrorModes() { return mirrorModes; }

    String mirrorFailure() {
        return mirrorFailures.values().stream().findFirst().orElse(null);
    }

    Map<SqlMirrorMode, String> mirrorFailures() { return mirrorFailures; }

    /** Called only from a submitted worker action after local settings have been loaded. */
    void configureMirror(SqlMirrorMode backend) {
        configureMirror(backend, RemoteSqlProfile.defaults());
    }

    void configureMirror(SqlMirrorMode backend, RemoteSqlProfile remoteProfile) {
        Objects.requireNonNull(backend, "backend");
        configureMirrors(backend == SqlMirrorMode.NONE ? List.of() : List.of(backend), remoteProfile);
    }

    void configureMirrors(List<SqlMirrorMode> selected, RemoteSqlProfile remoteProfile) {
        requireWorker();
        List<SqlMirrorMode> validated = SqlMirrorSelection.validate(selected, remoteProfile);
        Map<SqlMirrorMode, MirrorState> previous = new LinkedHashMap<>(mirrors);
        mirrors.clear();
        for (SqlMirrorMode mode : validated) {
            RemoteSqlProfile profile = mode.remote() ? remoteProfile : null;
            MirrorState known = previous.get(mode);
            mirrors.put(mode, known != null && Objects.equals(known.profile, profile)
                    ? known : new MirrorState(mode, profile));
        }
        for (MirrorState old : previous.values()) {
            if (mirrors.get(old.mode) != old) cancelRetry(old);
        }
        mirrorModes = validated;
        mirrorFailures = Map.of();
        if (!validated.isEmpty()) synchronizeMirrors();
    }

    /** The local document is authoritative; SQL propagation follows its durable write. */
    void writeDocument(Path path, String content) throws IOException {
        requireWorker();
        if (!mirrorModes.isEmpty()) mirrorPath(path);
        RecipeRepository.writeAtomically(path, content);
        propagate(path, content, false);
    }

    void deleteDocument(Path path) throws IOException {
        requireWorker();
        if (!mirrorModes.isEmpty()) mirrorPath(path);
        Files.deleteIfExists(path);
        propagate(path, null, true);
    }

    /** Retries selected SQL mirrors only when their contents have not diverged. */
    boolean synchronizeMirror() {
        return synchronizeMirrors();
    }

    boolean synchronizeMirrors() {
        requireWorker();
        if (mirrors.isEmpty()) return true;
        DiskSnapshot snapshot;
        try {
            if (dataRoot == null) throw new IOException("SQL mirror has no local data root");
            snapshot = DiskSnapshot.read(dataRoot);
        } catch (IOException ex) {
            for (MirrorState state : mirrors.values()) markMirrorFailure(state, ex);
            return false;
        }
        boolean allCurrent = true;
        for (MirrorState state : mirrors.values()) {
            if (!synchronize(state, snapshot)) allCurrent = false;
        }
        return allCurrent;
    }

    boolean synchronizeMirror(SqlMirrorMode mode) {
        requireWorker();
        MirrorState state = mirrors.get(mode);
        if (state == null) throw new IllegalArgumentException("SQL mirror is not selected: " + mode);
        try {
            if (dataRoot == null) throw new IOException("SQL mirror has no local data root");
            return synchronize(state, DiskSnapshot.read(dataRoot));
        }
        catch (IOException ex) {
            markMirrorFailure(state, ex);
            return false;
        }
    }

    private boolean synchronize(MirrorState state, DiskSnapshot snapshot) {
        try {
            if (state.store == null) state.store = SqlSnapshotService.localStore(dataRoot,
                    SqlDocumentStore.Backend.valueOf(state.mode.name()));
            if (!state.store.replaceSnapshotIfUnchanged(snapshot, state.synchronizedDocuments)) {
                markMirrorConflict(state);
                return false;
            }
            acceptSnapshot(state, snapshot);
            return true;
        } catch (IOException ex) {
            markMirrorFailure(state, ex);
            return false;
        }
    }

    /** Explicit local-wins resolution for one audited SQL copy. */
    boolean replaceMirrorFromLocal(SqlMirrorMode mode, String expectedSha256) throws IOException {
        requireWorker();
        MirrorState state = mirrors.get(mode);
        if (state == null) throw new IllegalArgumentException("SQL mirror is not selected: " + mode);
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected SQL source SHA-256 is invalid");
        }
        if (dataRoot == null) throw new IOException("SQL mirror has no local data root");
        DiskSnapshot local = DiskSnapshot.read(dataRoot);
        try {
            if (state.store == null) state.store = SqlSnapshotService.localStore(dataRoot,
                    SqlDocumentStore.Backend.valueOf(mode.name()));
            if (!state.store.replaceSnapshotIfCurrentHash(local, expectedSha256)) {
                throw new IOException("SQL source changed since inspection; inspect it again");
            }
            if (!local.documents().equals(state.store.readSnapshot().documents())) {
                throw new IOException("SQL mirror verification failed");
            }
            acceptSnapshot(state, local);
            return true;
        } catch (IOException ex) {
            if (!"SQL source changed since inspection; inspect it again".equals(ex.getMessage())) {
                markMirrorFailure(state, ex);
            }
            throw ex;
        }
    }

    private void acceptSnapshot(MirrorState state, DiskSnapshot snapshot) {
        cancelRetry(state);
        boolean recovered = state.failure != null;
        state.synchronizedDocuments = new LinkedHashMap<>(snapshot.documents());
        state.dirty = false;
        state.conflict = false;
        state.failure = null;
        state.retryAfterMillis = 0;
        state.failedAttempts = 0;
        publishFailures();
        if (recovered) logger.info("RecipeWeaver " + state.mode + " SQL mirror recovered");
    }

    /** Manual export is an explicit overwrite and becomes the selected mirror's new baseline. */
    void mirrorExported(SqlMirrorMode mode, DiskSnapshot snapshot) {
        requireWorker();
        MirrorState state = mirrors.get(mode);
        if (state != null) acceptSnapshot(state, snapshot);
    }

    private void propagate(Path path, String content, boolean deleted) throws IOException {
        if (mirrors.isEmpty()) return;
        String relative = mirrorPath(path);
        for (MirrorState state : mirrors.values()) {
            if (state.dirty || state.store == null) {
                if (!state.conflict && System.currentTimeMillis() >= state.retryAfterMillis) {
                    try { synchronize(state, DiskSnapshot.read(dataRoot)); }
                    catch (IOException ex) { markMirrorFailure(state, ex); }
                }
                continue;
            }
            try {
                SqlDocumentStore.Document desired = deleted
                        ? SqlDocumentStore.Document.tombstone()
                        : SqlDocumentStore.Document.value(content);
                if (!state.store.writeIfSnapshotUnchanged(relative,
                        state.synchronizedDocuments, desired)) {
                    markMirrorConflict(state);
                    continue;
                }
                if (deleted) state.synchronizedDocuments.remove(relative);
                else state.synchronizedDocuments.put(relative, content);
            } catch (IOException ex) {
                markMirrorFailure(state, ex);
            }
        }
    }

    private void markMirrorConflict(MirrorState state) {
        cancelRetry(state);
        state.dirty = true;
        state.conflict = true;
        String detail = state.mode + " conflict: SQL copy changed; inspect sources and choose local or SQL";
        if (!Objects.equals(state.failure, detail)) {
            logger.warning("RecipeWeaver SQL mirror paused: " + detail);
        }
        state.failure = detail;
        publishFailures();
    }

    private String mirrorPath(Path path) throws IOException {
        if (dataRoot == null) throw new IOException("SQL mirror has no local data root");
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(dataRoot)) throw new IOException("Data path is outside RecipeWeaver root");
        String relative = dataRoot.relativize(absolute).toString().replace('\\', '/');
        SqlDocumentStore.validatePath(relative);
        return relative;
    }

    private void markMirrorFailure(MirrorState state, IOException failure) {
        state.dirty = true;
        state.conflict = false;
        state.failedAttempts = Math.min(7, state.failedAttempts + 1);
        long delay = Math.min(60000L, 1000L << (state.failedAttempts - 1));
        state.retryAfterMillis = System.currentTimeMillis() + delay;
        String detail = state.mode + " propagation failed";
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sql) {
                if (sql.getSQLState() != null) detail += " (SQLState " + sql.getSQLState() + ")";
                break;
            }
            cause = cause.getCause();
        }
        if (!Objects.equals(state.failure, detail)) {
            logger.log(Level.WARNING, "RecipeWeaver SQL mirror is behind local data: " + detail);
        }
        state.failure = detail;
        publishFailures();
        scheduleRetry(state, delay);
    }

    private void scheduleRetry(MirrorState state, long delayMillis) {
        cancelRetry(state);
        // The timer only queues work; all file and SQL access stays on the ordered I/O worker.
        try {
            state.retryTask = retryTimer.schedule(() -> {
                try { executor.execute(() -> retryMirror(state)); }
                catch (RejectedExecutionException ignored) { /* Shutdown has already begun. */ }
            }, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // A write already queued before close may discover a mirror outage during shutdown.
        }
    }

    private void retryMirror(MirrorState state) {
        requireWorker();
        state.retryTask = null;
        if (mirrors.get(state.mode) != state || !state.dirty || state.conflict) return;
        try {
            if (dataRoot == null) throw new IOException("SQL mirror has no local data root");
            synchronize(state, DiskSnapshot.read(dataRoot));
        } catch (IOException ex) {
            markMirrorFailure(state, ex);
        }
    }

    private static void cancelRetry(MirrorState state) {
        if (state.retryTask != null) state.retryTask.cancel(false);
        state.retryTask = null;
    }

    private void publishFailures() {
        Map<SqlMirrorMode, String> failed = new LinkedHashMap<>();
        for (MirrorState state : mirrors.values()) {
            if (state.failure != null) failed.put(state.mode, state.failure);
        }
        mirrorFailures = Collections.unmodifiableMap(failed);
    }

    private static final class MirrorState {
        private final SqlMirrorMode mode;
        private final RemoteSqlProfile profile;
        private SqlDocumentStore store;
        private boolean dirty = true;
        private boolean conflict;
        private Map<String, String> synchronizedDocuments;
        private String failure;
        private int failedAttempts;
        private long retryAfterMillis;
        private ScheduledFuture<?> retryTask;

        private MirrorState(SqlMirrorMode mode, RemoteSqlProfile profile) {
            this.mode = mode;
            this.profile = profile;
            this.store = profile == null ? null : profile.store();
        }
    }

    private void requireWorker() {
        if (Thread.currentThread() != worker) {
            throw new IllegalStateException("RecipeWeaver document I/O must run on RecipeWeaver-IO");
        }
    }

    <T> CompletableFuture<T> submit(Callable<T> action) {
        return schedule(action, true);
    }

    /** Queue an action in order, but do not run it after an earlier tracked write failed. */
    <T> CompletableFuture<T> submitAfterSuccessfulWrites(Callable<T> action) {
        return schedule(action, true, true);
    }

    /** Read-only source operations need the same queue barrier without tracking expected read failures. */
    <T> CompletableFuture<T> submitObservedAfterSuccessfulWrites(Callable<T> action) {
        return schedule(action, false, true);
    }

    /** Read/validation operations report through their returned future without poisoning later writes. */
    <T> CompletableFuture<T> submitObserved(Callable<T> action) {
        return schedule(action, false);
    }

    private <T> CompletableFuture<T> schedule(Callable<T> action, boolean trackFailure) {
        return schedule(action, trackFailure, false);
    }

    private <T> CompletableFuture<T> schedule(Callable<T> action, boolean trackFailure,
                                              boolean requireSuccessfulWrites) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                if (requireSuccessfulWrites) {
                    Throwable previousFailure = firstFailure.getAndSet(null);
                    if (previousFailure != null) {
                        future.completeExceptionally(previousFailure);
                        return;
                    }
                }
                try {
                    future.complete(action.call());
                } catch (Throwable failure) {
                    if (trackFailure) firstFailure.compareAndSet(null, failure);
                    future.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            future.completeExceptionally(failure);
        }
        return future;
    }

    CompletableFuture<Void> flush() {
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        if (Thread.currentThread() == worker) {
            Throwable failure = firstFailure.getAndSet(null);
            if (failure == null) barrier.complete(null);
            else barrier.completeExceptionally(failure);
            return barrier;
        }
        try {
            executor.execute(() -> {
                Throwable failure = firstFailure.getAndSet(null);
                if (failure == null) barrier.complete(null);
                else barrier.completeExceptionally(failure);
            });
        } catch (RuntimeException failure) {
            barrier.completeExceptionally(failure);
        }
        return barrier;
    }

    void log(Level level, String message) {
        submitObserved(() -> {
            try {
                logger.log(level, message);
            } catch (RuntimeException failure) {
                // A failed server log handler must not become a failed data write.
                System.err.println("RecipeWeaver asynchronous log emission failed: " + failure);
            }
            return null;
        });
    }

    @Override
    public void close() {
        retryTimer.shutdownNow();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                throw new IllegalStateException("RecipeWeaver I/O worker did not finish within 30 seconds");
            }
            Throwable failure = firstFailure.getAndSet(null);
            if (failure != null) throw new IllegalStateException("RecipeWeaver I/O task failed", failure);
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while flushing RecipeWeaver I/O", ex);
        }
    }
}
