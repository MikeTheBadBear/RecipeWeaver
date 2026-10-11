package dev.recipeweaver.smoke;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import dev.recipeweaver.api.RecipeWeaverApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Test-only check of packaged JDBC drivers and the production ordered I/O worker. */
final class SqlStoreSmoke {
    record Result(boolean sqlitePrior, boolean h2Prior) { }

    private SqlStoreSmoke() { }

    static CompletableFuture<Result> start(Plugin smoke) throws ReflectiveOperationException {
        Plugin production = Bukkit.getPluginManager().getPlugin("RecipeWeaver");
        if (production == null) throw new IllegalStateException("RecipeWeaver plugin is missing");
        ClassLoader loader = production.getClass().getClassLoader();
        Field ioField = production.getClass().getDeclaredField("io");
        ioField.setAccessible(true);
        Object io = ioField.get(production);
        Method submit = io.getClass().getDeclaredMethod("submit", Callable.class);
        submit.setAccessible(true);
        Path root = smoke.getDataFolder().toPath().toAbsolutePath().normalize();
        @SuppressWarnings("unchecked")
        CompletableFuture<Result> future = (CompletableFuture<Result>) submit.invoke(io,
                (Callable<Result>) () -> runOnWorker(root, loader));
        return future;
    }

    static CompletableFuture<Boolean> verifyLiveMirror(Plugin smoke)
            throws ReflectiveOperationException {
        return verifyLiveMirrors(smoke, false);
    }

    /** Corrupts one selected source on RecipeWeaver-IO to test ordered runtime fallback. */
    static CompletableFuture<Void> corruptSqliteItems() throws ReflectiveOperationException {
        Plugin production = Bukkit.getPluginManager().getPlugin("RecipeWeaver");
        if (production == null) throw new IllegalStateException("RecipeWeaver plugin is missing");
        ClassLoader loader = production.getClass().getClassLoader();
        Field ioField = production.getClass().getDeclaredField("io");
        ioField.setAccessible(true);
        Object io = ioField.get(production);
        Method submit = io.getClass().getDeclaredMethod("submit", Callable.class);
        submit.setAccessible(true);
        Path root = production.getDataFolder().toPath().toAbsolutePath().normalize();
        @SuppressWarnings("unchecked")
        CompletableFuture<Void> future = (CompletableFuture<Void>) submit.invoke(io,
                (Callable<Void>) () -> {
                    check(Thread.currentThread().getName().equals("RecipeWeaver-IO"),
                            "SQL fault injection ran on the wrong thread");
                    Class<?> storeClass = Class.forName("dev.recipeweaver.core.SqlDocumentStore",
                            true, loader);
                    Class<?> backendClass = Class.forName(
                            "dev.recipeweaver.core.SqlDocumentStore$Backend", true, loader);
                    Constructor<?> constructor = storeClass.getDeclaredConstructor(
                            backendClass, String.class, String.class, String.class);
                    constructor.setAccessible(true);
                    Object store = constructor.newInstance(backend(backendClass, "SQLITE"),
                            "jdbc:sqlite:" + root.resolve("sql-snapshots/recipeweaver.sqlite"),
                            "", "");
                    Method put = storeClass.getDeclaredMethod("put", String.class, String.class);
                    put.setAccessible(true);
                    put.invoke(store, "items.yml", "schema: 999\nitems: {}\n");
                    return null;
                });
        return future;
    }

    static CompletableFuture<Boolean> verifyLiveMirrors(Plugin smoke)
            throws ReflectiveOperationException {
        return verifyLiveMirrors(smoke, true);
    }

    private static CompletableFuture<Boolean> verifyLiveMirrors(Plugin smoke, boolean includeH2)
            throws ReflectiveOperationException {
        Plugin production = Bukkit.getPluginManager().getPlugin("RecipeWeaver");
        if (production == null) throw new IllegalStateException("RecipeWeaver plugin is missing");
        ClassLoader loader = production.getClass().getClassLoader();
        Field ioField = production.getClass().getDeclaredField("io");
        ioField.setAccessible(true);
        Object io = ioField.get(production);
        Method submit = io.getClass().getDeclaredMethod("submit", Callable.class);
        submit.setAccessible(true);
        Path root = production.getDataFolder().toPath().toAbsolutePath().normalize();
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) submit.invoke(io,
                (Callable<Boolean>) () -> {
                    check(Thread.currentThread().getName().equals("RecipeWeaver-IO"),
                            "Live mirror verification did not run on RecipeWeaver-IO");
                    Class<?> sourceClass = Class.forName("dev.recipeweaver.core.SqlDocumentStore",
                            true, loader);
                    Class<?> backendClass = Class.forName(
                            "dev.recipeweaver.core.SqlDocumentStore$Backend", true, loader);
                    Constructor<?> constructor = sourceClass.getDeclaredConstructor(
                            backendClass, String.class, String.class, String.class);
                    constructor.setAccessible(true);
                    Method readAll = sourceClass.getDeclaredMethod("readAll");
                    readAll.setAccessible(true);
                    for (String mode : includeH2 ? new String[]{"SQLITE", "H2"}
                            : new String[]{"SQLITE"}) {
                        String url = mode.equals("SQLITE")
                                ? "jdbc:sqlite:" + root.resolve("sql-snapshots/recipeweaver.sqlite")
                                : "jdbc:h2:file:" + root.resolve("sql-snapshots/recipeweaver-h2")
                                        .toString().replace('\\', '/');
                        Object store = constructor.newInstance(backend(backendClass, mode), url, "", "");
                        Map<?, ?> documents = (Map<?, ?>) readAll.invoke(store);
                        check(Files.readString(root.resolve("settings.yml"))
                                        .equals(content(documents.get("settings.yml"))),
                                mode + " mirror lost current settings");
                        check(Files.readString(root.resolve("items.yml"))
                                        .equals(content(documents.get("items.yml"))),
                                mode + " mirror lost a live custom-item save");
                    }
                    return true;
                });
        return future;
    }

    /** Exercises the live plugin's local-SQL export and normal validated restore path. */
    static CompletableFuture<String> snapshotRoundTrip(Plugin smoke, RecipeWeaverApi api) {
        try {
            Class<?> backendClass = Class.forName("dev.recipeweaver.core.SqlDocumentStore$Backend",
                    true, api.getClass().getClassLoader());
            Object sqlite = backend(backendClass, "SQLITE");
            Object h2 = backend(backendClass, "H2");
            Plugin production = Bukkit.getPluginManager().getPlugin("RecipeWeaver");
            check(production != null, "RecipeWeaver plugin is missing");
            return SqlStoreSmoke.<Integer>onMain(smoke,
                    () -> invoke(api, "exportLocalSqlAsync", backendClass, sqlite))
                    .thenCompose(sqliteCount -> {
                        check(sqliteCount > 0, "SQLite snapshot was empty");
                        return SqlStoreSmoke.<Integer>onMain(smoke,
                                () -> invoke(api, "exportLocalSqlAsync", backendClass, h2));
                    }).thenCompose(h2Count -> {
                        check(h2Count > 0, "H2 snapshot was empty");
                        return SqlStoreSmoke.<String>onMain(smoke, () -> invoke(api,
                                "prepareLocalSqlRestoreAsync", backendClass, sqlite));
                    }).thenCompose(name -> {
                        check(Files.isRegularFile(production.getDataFolder().toPath()
                                        .resolve("backups").resolve(name).resolve("settings.yml")),
                                "SQL snapshot did not materialize a settings backup");
                        return SqlStoreSmoke.<String>onMain(smoke, () -> api.restoreDataAsync(name))
                                .thenApply(previous -> {
                                    check(previous != null && previous.endsWith(".zip"),
                                            "SQL snapshot restore did not save previous data");
                                    return name;
                                });
                    });
        } catch (ReflectiveOperationException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> CompletionStage<T> invoke(RecipeWeaverApi api, String name,
                                                  Class<?> backendClass, Object backend)
            throws ReflectiveOperationException {
        Method method = api.getClass().getDeclaredMethod(name, backendClass);
        method.setAccessible(true);
        return (CompletionStage<T>) method.invoke(api, backend);
    }

    private static <T> CompletableFuture<T> onMain(Plugin smoke,
                                                    Callable<CompletionStage<T>> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable run = () -> {
            try {
                action.call().whenComplete((value, failure) -> {
                    if (failure == null) result.complete(value);
                    else result.completeExceptionally(failure);
                });
            } catch (Throwable ex) { result.completeExceptionally(ex); }
        };
        if (Bukkit.isPrimaryThread()) run.run();
        else Bukkit.getScheduler().runTask(smoke, run);
        return result;
    }

    private static Result runOnWorker(Path root, ClassLoader loader) throws Exception {
        check(Thread.currentThread().getName().equals("RecipeWeaver-IO"),
                "SQL store probe did not run on RecipeWeaver-IO");
        Files.createDirectories(root);
        Class<?> sourceClass = Class.forName("dev.recipeweaver.core.SqlDocumentStore", true, loader);
        Class<?> backendClass = Class.forName("dev.recipeweaver.core.SqlDocumentStore$Backend", true, loader);
        Constructor<?> constructor = sourceClass.getDeclaredConstructor(
                backendClass, String.class, String.class, String.class);
        constructor.setAccessible(true);
        Method readAll = sourceClass.getDeclaredMethod("readAll");
        Method put = sourceClass.getDeclaredMethod("put", String.class, String.class);
        Method delete = sourceClass.getDeclaredMethod("delete", String.class);
        readAll.setAccessible(true);
        put.setAccessible(true);
        delete.setAccessible(true);

        Object sqlite = constructor.newInstance(backend(backendClass, "SQLITE"),
                "jdbc:sqlite:" + root.resolve("sql-store-smoke.sqlite"), "", "");
        boolean sqlitePrior = verifyStore(sqlite, readAll, put, delete);
        verifyGlobalGuard(sqlite, sourceClass, put, delete, readAll, loader);
        Object h2 = constructor.newInstance(backend(backendClass, "H2"),
                "jdbc:h2:file:" + root.resolve("sql-store-smoke-h2").toString().replace('\\', '/'),
                "", "");
        boolean h2Prior = verifyStore(h2, readAll, put, delete);
        verifyGlobalGuard(h2, sourceClass, put, delete, readAll, loader);
        verifyConcurrentGuard(root, sourceClass, backendClass, constructor, loader);
        return new Result(sqlitePrior, h2Prior);
    }

    private static void verifyConcurrentGuard(Path root, Class<?> sourceClass,
                                              Class<?> backendClass, Constructor<?> constructor,
                                              ClassLoader loader) throws Exception {
        Object store = constructor.newInstance(backend(backendClass, "H2"),
                "jdbc:h2:file:" + root.resolve("sql-concurrent-guard-h2")
                        .toString().replace('\\', '/'), "", "");
        Method replaceAll = sourceClass.getDeclaredMethod("replaceAll", Map.class);
        Method readAll = sourceClass.getDeclaredMethod("readAll");
        Class<?> documentClass = Class.forName(
                "dev.recipeweaver.core.SqlDocumentStore$Document", true, loader);
        Method value = documentClass.getDeclaredMethod("value", String.class);
        Method guarded = sourceClass.getDeclaredMethod("writeIfSnapshotUnchanged",
                String.class, Map.class, documentClass);
        replaceAll.setAccessible(true);
        readAll.setAccessible(true);
        value.setAccessible(true);
        guarded.setAccessible(true);
        replaceAll.invoke(store, Map.of("settings.yml", value.invoke(null, "baseline: true\n")));
        Map<String, String> baseline = Map.of("settings.yml", "baseline: true\n");
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = workers.submit(() -> {
                start.await();
                return guardedWrite(guarded, store, value, "recipes/one.yml", baseline,
                        "winner: one\n");
            });
            Future<Boolean> second = workers.submit(() -> {
                start.await();
                return guardedWrite(guarded, store, value, "items.yml", baseline,
                        "winner: two\n");
            });
            start.countDown();
            int accepted = (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
            check(accepted == 1, "H2 concurrent writes accepted the same SQL baseline twice");
        }
        Map<?, ?> documents = (Map<?, ?>) readAll.invoke(store);
        check(documents.size() == 2 && documents.containsKey("settings.yml")
                        && (documents.containsKey("recipes/one.yml")
                        ^ documents.containsKey("items.yml")),
                "H2 concurrent guard left an unexpected SQL source");
    }

    private static boolean guardedWrite(Method guarded, Object store, Method value,
                                        String path, Map<String, String> baseline,
                                        String content) throws Exception {
        try { return (boolean) guarded.invoke(store, path, baseline, value.invoke(null, content)); }
        catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof IOException) return false;
            throw ex;
        }
    }

    private static void verifyGlobalGuard(Object store, Class<?> sourceClass,
                                          Method put, Method delete, Method readAll,
                                          ClassLoader loader) throws Exception {
        Class<?> documentClass = Class.forName(
                "dev.recipeweaver.core.SqlDocumentStore$Document", true, loader);
        Method value = documentClass.getDeclaredMethod("value", String.class);
        Method guarded = sourceClass.getDeclaredMethod("writeIfSnapshotUnchanged",
                String.class, Map.class, documentClass);
        value.setAccessible(true);
        guarded.setAccessible(true);
        Map<String, String> baseline = Map.of("settings.yml", "persisted: true\n");
        put.invoke(store, "stations/external.yml", "external: true\n");
        Object desired = value.invoke(null, "guarded: true\n");
        check(!(boolean) guarded.invoke(store, "settings.yml", baseline, desired),
                "SQL incremental guard missed an independent document");
        check("persisted: true\n".equals(content(((Map<?, ?>) readAll.invoke(store)).get("settings.yml"))),
                "SQL incremental guard changed data after a conflict");
        delete.invoke(store, "stations/external.yml");
        check((boolean) guarded.invoke(store, "settings.yml", baseline, desired),
                "SQL incremental guard refused a current baseline");
        check((boolean) guarded.invoke(store, "settings.yml", baseline, desired),
                "SQL incremental guard rejected an idempotent retry");
        check("guarded: true\n".equals(content(((Map<?, ?>) readAll.invoke(store)).get("settings.yml"))),
                "SQL incremental guard did not save the desired document");
        put.invoke(store, "settings.yml", "persisted: true\n");
    }

    private static boolean verifyStore(Object store, Method readAll, Method put, Method delete)
            throws Exception {
        Map<?, ?> before = (Map<?, ?>) readAll.invoke(store);
        Object old = before.get("settings.yml");
        boolean prior = old != null && "persisted: true\n".equals(content(old));
        put.invoke(store, "settings.yml", "persisted: true\n");
        put.invoke(store, "recipes/probe.yml", "schema: 1\n");
        Map<?, ?> saved = (Map<?, ?>) readAll.invoke(store);
        check("persisted: true\n".equals(content(saved.get("settings.yml"))),
                "SQL document save/read lost settings");
        check("schema: 1\n".equals(content(saved.get("recipes/probe.yml"))),
                "SQL document save/read lost recipe data");
        delete.invoke(store, "recipes/probe.yml");
        Object tombstone = ((Map<?, ?>) readAll.invoke(store)).get("recipes/probe.yml");
        Method deleted = tombstone.getClass().getDeclaredMethod("deleted");
        deleted.setAccessible(true);
        check((boolean) deleted.invoke(tombstone), "SQL delete marker was not durable");
        return prior;
    }

    private static String content(Object document) throws Exception {
        if (document == null) return null;
        Method method = document.getClass().getDeclaredMethod("content");
        method.setAccessible(true);
        return (String) method.invoke(document);
    }

    private static Object backend(Class<?> type, String name) {
        for (Object value : type.getEnumConstants()) {
            if (value.toString().equals(name)) return value;
        }
        throw new IllegalStateException("Missing SQL backend " + name);
    }

    private static void check(boolean passed, String detail) {
        if (!passed) throw new IllegalStateException(detail);
    }
}
