package dev.recipeweaver.core;

import dev.recipeweaver.api.BackupFormat;
import dev.recipeweaver.api.SqlMirrorMode;
import dev.recipeweaver.api.SqlReadPolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

final class SettingsRepository {
    private final Path path;
    private boolean productionInstance;
    private final Consumer<Runnable> mainCallback;
    private State current = new State(false, 0, false, BackupFormat.ZIP, Set.of(),
            List.of(), RemoteSqlProfile.defaults(), SqlReadPolicy.LOCAL_ONLY);
    private State durable = current;
    private long revision;
    private long durableRevision;
    private int pendingWrites;
    private CompletableFuture<Void> latestWrite = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> delayedFailure;
    private Throwable delayedCause;

    SettingsRepository(Plugin plugin) {
        this(plugin, action -> Bukkit.getScheduler().runTask(plugin, action));
    }

    SettingsRepository(Plugin plugin, Consumer<Runnable> mainCallback) {
        this(plugin.getDataFolder().toPath(), mainCallback);
        productionInstance = plugin instanceof RecipeWeaverPlugin;
    }

    SettingsRepository(Path dataRoot) { this(dataRoot, Runnable::run); }

    SettingsRepository(Path dataRoot, Consumer<Runnable> mainCallback) {
        path = dataRoot.resolve("settings.yml");
        this.mainCallback = mainCallback;
    }

    void load() throws IOException {
        requireTestOnly();
        load(DiskSnapshot.read(path.getParent()));
    }

    void load(DiskSnapshot snapshot) throws IOException {
        if (pendingWrites != 0) throw new IOException("Settings writes are still pending");
        State loaded = new State(false, 0, false, BackupFormat.ZIP, Set.of(),
                List.of(), RemoteSqlProfile.defaults(), SqlReadPolicy.LOCAL_ONLY);
        YamlConfiguration config = snapshot.yaml("settings.yml");
        if (config != null) {
            int schema = config.getInt("schema");
            if ((schema < 1 || schema > 9) || !config.isBoolean("lockdown")) {
                throw new IOException("Invalid settings.yml schema or lockdown value");
            }
            int retention = 0;
            if (config.contains("backup-retention")) {
                if (!config.isInt("backup-retention")) {
                    throw new IOException("Invalid backup-retention setting");
                }
                retention = config.getInt("backup-retention");
                if (retention < 0 || retention > 10000) {
                    throw new IOException("Backup retention must be 0 through 10000");
                }
            }
            if (config.contains("book-item-enabled") && !config.isBoolean("book-item-enabled")) {
                throw new IOException("Invalid book-item-enabled setting");
            }
            BackupFormat format = BackupFormat.ZIP;
            if (schema >= 2) {
                String raw = config.getString("backup-format");
                if (raw == null) throw new IOException("Missing backup-format setting");
                try { format = BackupFormat.valueOf(raw); }
                catch (IllegalArgumentException ex) { throw new IOException("Invalid backup-format setting", ex); }
            }
            Set<NamespacedKey> disabled = Set.of();
            if (schema >= 3) {
                Object raw = config.get("disabled-vanilla-recipes");
                if (!(raw instanceof List<?> values)) {
                    throw new IOException("Missing disabled-vanilla-recipes list");
                }
                Set<NamespacedKey> keys = new HashSet<>();
                for (Object value : values) {
                    if (!(value instanceof String id)) {
                        throw new IOException("Invalid disabled vanilla recipe ID");
                    }
                    NamespacedKey key = NamespacedKey.fromString(id);
                    if (key == null || !id.equals(key.toString())
                            || !key.getNamespace().equals(NamespacedKey.MINECRAFT)
                            || !keys.add(key)) {
                        throw new IOException("Invalid or duplicate disabled vanilla recipe ID");
                    }
                }
                if (keys.size() > 256) throw new IOException("Too many disabled vanilla recipes");
                disabled = Set.copyOf(keys);
            }
            List<SqlMirrorMode> mirrors = List.of();
            if (schema >= 6) {
                Object raw = config.get("sql-mirrors");
                if (!(raw instanceof List<?> entries)) throw new IOException("Missing sql-mirrors list");
                List<SqlMirrorMode> parsed = new java.util.ArrayList<>();
                for (Object entry : entries) {
                    if (!(entry instanceof String name)) throw new IOException("Invalid SQL mirror backend");
                    try { parsed.add(SqlMirrorMode.valueOf(name)); }
                    catch (IllegalArgumentException ex) {
                        throw new IOException("Invalid SQL mirror backend", ex);
                    }
                }
                mirrors = parsed;
            } else if (schema >= 4) {
                String raw = config.getString("sql-mirror");
                if (raw == null) throw new IOException("Missing sql-mirror setting");
                try {
                    SqlMirrorMode old = SqlMirrorMode.valueOf(raw);
                    mirrors = old == SqlMirrorMode.NONE ? List.of() : List.of(old);
                }
                catch (IllegalArgumentException ex) { throw new IOException("Invalid sql-mirror setting", ex); }
            }
            RemoteSqlProfile remote = RemoteSqlProfile.defaults();
            if (schema >= 5) {
                if (!config.isString("remote-sql.backend")
                        || !config.isString("remote-sql.jdbc-url")
                        || !config.isString("remote-sql.username")
                        || !config.isString("remote-sql.password")) {
                    throw new IOException("Missing or invalid remote SQL settings");
                }
                String rawBackend = config.getString("remote-sql.backend");
                String url = config.getString("remote-sql.jdbc-url");
                String user = config.getString("remote-sql.username");
                String secret = config.getString("remote-sql.password");
                if (rawBackend == null || url == null || user == null || secret == null) {
                    throw new IOException("Missing remote SQL settings");
                }
                try { remote = new RemoteSqlProfile(SqlMirrorMode.valueOf(rawBackend), url, user, secret); }
                catch (IllegalArgumentException ex) { throw new IOException("Invalid remote SQL settings", ex); }
            }
            SqlReadPolicy readPolicy = SqlReadPolicy.LOCAL_ONLY;
            if (schema >= 7) {
                String raw = config.getString("sql-read-policy");
                if (raw == null) throw new IOException("Missing SQL read policy");
                try { readPolicy = SqlReadPolicy.valueOf(raw); }
                catch (IllegalArgumentException ex) { throw new IOException("Invalid SQL read policy", ex); }
                if (schema < 9 && readPolicy == SqlReadPolicy.PREFER_SQL_AT_STARTUP_AND_RELOAD) {
                    throw new IOException("Runtime SQL reload policy requires settings schema 9");
                }
            }
            try {
                loaded = new State(config.getBoolean("lockdown"), retention,
                        config.getBoolean("book-item-enabled", false), format, disabled, mirrors, remote,
                        readPolicy);
            } catch (IllegalArgumentException ex) {
                throw new IOException("Invalid SQL source settings", ex);
            }
        }
        current = loaded;
        durable = loaded;
        revision = 0;
        durableRevision = 0;
        latestWrite = CompletableFuture.completedFuture(null);
    }

    boolean lockdown() { return current.lockdown(); }
    int backupRetention() { return current.backupRetention(); }
    boolean bookItemEnabled() { return current.bookItemEnabled(); }
    BackupFormat backupFormat() { return current.backupFormat(); }
    Set<NamespacedKey> disabledVanillaRecipes() { return current.disabledVanillaRecipes(); }
    SqlMirrorMode sqlMirrorBackend() { return current.sqlMirrorBackend(); }
    List<SqlMirrorMode> sqlMirrors() { return current.sqlMirrors(); }
    SqlReadPolicy sqlReadPolicy() { return current.sqlReadPolicy(); }
    RemoteSqlProfile remoteSqlProfile() { return current.remoteSqlProfile(); }

    void setLockdown(boolean enabled) throws IOException {
        requireTestOnly();
        writeSync(new State(enabled, current.backupRetention(), current.bookItemEnabled(),
                current.backupFormat(), current.disabledVanillaRecipes(), current.sqlMirrors(),
                current.remoteSqlProfile(), current.sqlReadPolicy()));
    }

    void setBackupRetention(int count) throws IOException {
        requireTestOnly();
        validateRetention(count);
        writeSync(new State(current.lockdown(), count, current.bookItemEnabled(),
                current.backupFormat(), current.disabledVanillaRecipes(), current.sqlMirrors(),
                current.remoteSqlProfile(), current.sqlReadPolicy()));
    }

    void setBookItemEnabled(boolean enabled) throws IOException {
        requireTestOnly();
        writeSync(new State(current.lockdown(), current.backupRetention(), enabled,
                current.backupFormat(), current.disabledVanillaRecipes(), current.sqlMirrors(),
                current.remoteSqlProfile(), current.sqlReadPolicy()));
    }

    CompletableFuture<Void> setLockdownAsync(boolean enabled, AsyncIoService io) {
        return enqueue(new State(enabled, current.backupRetention(), current.bookItemEnabled(),
                current.backupFormat(), current.disabledVanillaRecipes(), current.sqlMirrors(),
                current.remoteSqlProfile(), current.sqlReadPolicy()), io);
    }

    CompletableFuture<Void> setBackupRetentionAsync(int count, AsyncIoService io) {
        validateRetention(count);
        return enqueue(new State(current.lockdown(), count, current.bookItemEnabled(),
                current.backupFormat(), current.disabledVanillaRecipes(), current.sqlMirrors(),
                current.remoteSqlProfile(), current.sqlReadPolicy()), io);
    }

    CompletableFuture<Void> setBookItemEnabledAsync(boolean enabled, AsyncIoService io) {
        return enqueue(new State(current.lockdown(), current.backupRetention(), enabled,
                current.backupFormat(), current.disabledVanillaRecipes(), current.sqlMirrors(),
                current.remoteSqlProfile(), current.sqlReadPolicy()), io);
    }

    void setBackupFormat(BackupFormat format) throws IOException {
        requireTestOnly();
        writeSync(new State(current.lockdown(), current.backupRetention(),
                current.bookItemEnabled(), java.util.Objects.requireNonNull(format),
                current.disabledVanillaRecipes(), current.sqlMirrors(), current.remoteSqlProfile(),
                current.sqlReadPolicy()));
    }

    CompletableFuture<Void> setBackupFormatAsync(BackupFormat format, AsyncIoService io) {
        return enqueue(new State(current.lockdown(), current.backupRetention(),
                current.bookItemEnabled(), java.util.Objects.requireNonNull(format),
                current.disabledVanillaRecipes(), current.sqlMirrors(), current.remoteSqlProfile(),
                current.sqlReadPolicy()), io);
    }

    void setSqlMirrorMode(SqlMirrorMode backend) throws IOException {
        requireTestOnly();
        writeSync(withSqlMirror(backend));
    }

    CompletableFuture<Void> setSqlMirrorModeAsync(SqlMirrorMode backend, AsyncIoService io) {
        State next = withSqlMirror(backend);
        return unchangedOrEnqueue(next, io);
    }

    private State withSqlMirror(SqlMirrorMode backend) {
        java.util.Objects.requireNonNull(backend, "backend");
        return withSqlMirrors(backend == SqlMirrorMode.NONE ? List.of() : List.of(backend));
    }

    void setSqlMirrors(List<SqlMirrorMode> mirrors) throws IOException {
        requireTestOnly();
        writeSync(withSqlMirrors(mirrors));
    }

    CompletableFuture<Void> setSqlMirrorsAsync(List<SqlMirrorMode> mirrors, AsyncIoService io) {
        State next = withSqlMirrors(mirrors);
        return unchangedOrEnqueue(next, io);
    }

    private State withSqlMirrors(List<SqlMirrorMode> mirrors) {
        SqlReadPolicy policy = mirrors.isEmpty() ? SqlReadPolicy.LOCAL_ONLY : current.sqlReadPolicy();
        return new State(current.lockdown(), current.backupRetention(), current.bookItemEnabled(),
                current.backupFormat(), current.disabledVanillaRecipes(),
                mirrors, current.remoteSqlProfile(), policy);
    }

    void setSqlReadPolicy(SqlReadPolicy policy) throws IOException {
        requireTestOnly();
        writeSync(withSqlReadPolicy(policy));
    }

    CompletableFuture<Void> setSqlReadPolicyAsync(SqlReadPolicy policy, AsyncIoService io) {
        State next = withSqlReadPolicy(policy);
        return unchangedOrEnqueue(next, io);
    }

    private State withSqlReadPolicy(SqlReadPolicy policy) {
        return new State(current.lockdown(), current.backupRetention(), current.bookItemEnabled(),
                current.backupFormat(), current.disabledVanillaRecipes(), current.sqlMirrors(),
                current.remoteSqlProfile(), java.util.Objects.requireNonNull(policy, "policy"));
    }

    void setRemoteSqlProfile(RemoteSqlProfile profile) throws IOException {
        requireTestOnly();
        writeSync(withRemoteSqlProfile(profile));
    }

    CompletableFuture<Void> setRemoteSqlProfileAsync(RemoteSqlProfile profile, AsyncIoService io) {
        State next = withRemoteSqlProfile(profile);
        return unchangedOrEnqueue(next, io);
    }

    private State withRemoteSqlProfile(RemoteSqlProfile profile) {
        java.util.Objects.requireNonNull(profile, "profile");
        if (current.sqlMirrors().stream().anyMatch(mode -> mode.remote() && mode != profile.backend())) {
            throw new IllegalArgumentException("Disable the live remote mirror before changing backend");
        }
        return new State(current.lockdown(), current.backupRetention(), current.bookItemEnabled(),
                current.backupFormat(), current.disabledVanillaRecipes(), current.sqlMirrors(), profile,
                current.sqlReadPolicy());
    }

    void setVanillaRecipeDisabled(NamespacedKey key, boolean disabled) throws IOException {
        requireTestOnly();
        writeSync(withVanillaRecipe(key, disabled));
    }

    CompletableFuture<Void> setVanillaRecipeDisabledAsync(NamespacedKey key, boolean disabled,
                                                           AsyncIoService io) {
        State next = withVanillaRecipe(key, disabled);
        return unchangedOrEnqueue(next, io);
    }

    private State withVanillaRecipe(NamespacedKey key, boolean disabled) {
        if (key == null || !key.getNamespace().equals(NamespacedKey.MINECRAFT)) {
            throw new IllegalArgumentException("Only minecraft recipe keys can be disabled");
        }
        Set<NamespacedKey> next = new HashSet<>(current.disabledVanillaRecipes());
        if (disabled) next.add(key);
        else next.remove(key);
        if (next.size() > 256) throw new IllegalArgumentException("At most 256 vanilla recipes may be disabled");
        return new State(current.lockdown(), current.backupRetention(),
                current.bookItemEnabled(), current.backupFormat(), next, current.sqlMirrors(),
                current.remoteSqlProfile(), current.sqlReadPolicy());
    }

    private CompletableFuture<Void> unchangedOrEnqueue(State next, AsyncIoService io) {
        // The visible state is optimistic until its worker write and main callback finish.
        // A repeated request must share that durability result, including a write failure.
        return next.equals(current)
                ? pendingWrites == 0 ? CompletableFuture.completedFuture(null) : latestWrite
                : enqueue(next, io);
    }

    private CompletableFuture<Void> enqueue(State next, AsyncIoService io) {
        if (delayedFailure != null) {
            delayedFailure.completeExceptionally(delayedCause);
            delayedFailure = null;
            delayedCause = null;
        }
        String yaml = serialize(next);
        long submitted = ++revision;
        current = next;
        pendingWrites++;
        CompletableFuture<Void> result = new CompletableFuture<>();
        latestWrite = result;
        io.submit(() -> {
            io.writeDocument(path, yaml);
            return null;
        }).whenComplete((ignored, failure) -> {
            try {
                mainCallback.accept(() -> {
                    pendingWrites--;
                    if (failure == null && submitted > durableRevision) {
                        durable = next;
                        durableRevision = submitted;
                    }
                    if (pendingWrites == 0 && durableRevision != revision) current = durable;
                    if (failure == null) result.complete(null);
                    else if (submitted == revision && pendingWrites != 0) {
                        delayedFailure = result;
                        delayedCause = failure;
                    } else result.completeExceptionally(failure);
                    if (pendingWrites == 0 && delayedFailure != null) {
                        delayedFailure.completeExceptionally(delayedCause);
                        delayedFailure = null;
                        delayedCause = null;
                    }
                });
            } catch (RuntimeException callbackFailure) {
                result.completeExceptionally(callbackFailure);
            }
        });
        return result;
    }

    private void requireTestOnly() {
        if (productionInstance) throw new IllegalStateException(
                "Synchronous settings helper is unavailable in the live plugin");
    }

    private void writeSync(State next) throws IOException {
        if (pendingWrites != 0) throw new IllegalStateException("Settings writes are still pending");
        RecipeRepository.writeAtomically(path, serialize(next));
        current = next;
        durable = next;
    }

    private static void validateRetention(int count) {
        if (count < 0 || count > 10000) {
            throw new IllegalArgumentException("Backup retention must be 0 through 10000");
        }
    }

    private static String serialize(State state) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("schema", 9);
        config.set("lockdown", state.lockdown());
        config.set("backup-retention", state.backupRetention());
        config.set("book-item-enabled", state.bookItemEnabled());
        config.set("backup-format", state.backupFormat().name());
        config.set("disabled-vanilla-recipes", state.disabledVanillaRecipes().stream()
                .map(NamespacedKey::toString).sorted().toList());
        config.set("sql-mirrors", state.sqlMirrors().stream().map(Enum::name).toList());
        config.set("sql-read-policy", state.sqlReadPolicy().name());
        config.set("remote-sql.backend", state.remoteSqlProfile().backend().name());
        config.set("remote-sql.jdbc-url", state.remoteSqlProfile().jdbcUrl());
        config.set("remote-sql.username", state.remoteSqlProfile().username());
        config.set("remote-sql.password", state.remoteSqlProfile().password());
        return config.saveToString();
    }

    private record State(boolean lockdown, int backupRetention, boolean bookItemEnabled,
                         BackupFormat backupFormat, Set<NamespacedKey> disabledVanillaRecipes,
                         List<SqlMirrorMode> sqlMirrors, RemoteSqlProfile remoteSqlProfile,
                         SqlReadPolicy sqlReadPolicy) {
        private State {
            disabledVanillaRecipes = Set.copyOf(disabledVanillaRecipes);
            sqlMirrors = SqlMirrorSelection.validate(sqlMirrors, remoteSqlProfile);
            java.util.Objects.requireNonNull(sqlReadPolicy, "sqlReadPolicy");
            if (sqlReadPolicy != SqlReadPolicy.LOCAL_ONLY && sqlMirrors.isEmpty()) {
                throw new IllegalArgumentException("Select an SQL source before enabling SQL reads");
            }
        }

        private SqlMirrorMode sqlMirrorBackend() {
            return sqlMirrors.isEmpty() ? SqlMirrorMode.NONE : sqlMirrors.get(0);
        }
    }
}
