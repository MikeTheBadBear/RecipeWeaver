package dev.recipeweaver.smoke;

import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.SqlMirrorMode;
import dev.recipeweaver.api.SqlReadPolicy;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Test-only raw-local-read recovery probe for a preferred SQLite reload. */
final class SqlReloadRecoverySmoke {
    private static final byte[] INVALID_UTF8 = {(byte) 0xc3, (byte) 0x28};

    private SqlReloadRecoverySmoke() { }

    /** Call from the smoke plugin's server-thread sequence while SQLite is a healthy mirror. */
    static CompletableFuture<Void> verify(Plugin smoke, RecipeWeaverApi api) {
        if (!Bukkit.isPrimaryThread()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "SQL reload recovery probe must start on the server thread"));
        }
        if (api.sqlReadPolicy() != SqlReadPolicy.PREFER_SQL_AT_STARTUP_AND_RELOAD
                || !api.sqlMirrorModes().contains(SqlMirrorMode.SQLITE)
                || !api.sqlMirrorFailures().isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "SQL reload recovery probe needs a healthy preferred SQLite mirror"));
        }
        Plugin production = Bukkit.getPluginManager().getPlugin("RecipeWeaver");
        if (production == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("RecipeWeaver is missing"));
        }
        Path root = production.getDataFolder().toPath();
        Path settings = root.resolve("settings.yml");
        return onMain(smoke, api::listBackupsAsync).thenCompose(before ->
                onProductionIo(production, () -> Files.readAllBytes(settings)).thenCompose(original -> {
                    CompletableFuture<Void> result = new CompletableFuture<>();
                    runProbe(smoke, api, production, root, settings, original, before)
                            .whenComplete((ignored, failure) -> {
                                if (failure == null) {
                                    result.complete(null);
                                    return;
                                }
                                Throwable cause = unwrap(failure);
                                onProductionIo(production, () -> {
                                    Files.write(settings, original);
                                    return null;
                                }).whenComplete((restored, cleanupFailure) -> {
                                    if (cleanupFailure != null) cause.addSuppressed(unwrap(cleanupFailure));
                                    result.completeExceptionally(cause);
                                });
                            });
                    return result;
                }));
    }

    private static CompletableFuture<Void> runProbe(Plugin smoke, RecipeWeaverApi api,
                                                     Plugin production, Path root, Path settings,
                                                     byte[] original, List<String> before) {
        return onProductionIo(production, () -> {
            Files.write(settings, INVALID_UTF8);
            return null;
        }).thenCompose(ignored -> onMain(smoke, api::reloadDataAsync))
                .thenCompose(ignored -> onMain(smoke, () -> {
                    check(api.lastReloadSqlSource() == SqlMirrorMode.SQLITE,
                            "Reload did not select SQLite after unreadable local data");
                    return api.listBackupsAsync();
                }))
                .thenCompose(after -> onProductionIo(production, () -> {
                    check(Arrays.equals(original, Files.readAllBytes(settings)),
                            "Preferred SQL reload did not repair local settings bytes");
                    boolean preserved = false;
                    for (String name : after) {
                        if (before.contains(name) || !name.endsWith(".zip")) continue;
                        try (ZipFile zip = new ZipFile(root.resolve("backups").resolve(name).toFile())) {
                            ZipEntry entry = zip.getEntry("settings.yml");
                            if (entry != null && Arrays.equals(INVALID_UTF8,
                                    zip.getInputStream(entry).readAllBytes())) {
                                preserved = true;
                                break;
                            }
                        }
                    }
                    check(preserved, "Recovery archive did not preserve unreadable local bytes");
                    return null;
                }));
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
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        };
        if (Bukkit.isPrimaryThread()) run.run();
        else Bukkit.getScheduler().runTask(smoke, run);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> CompletableFuture<T> onProductionIo(Plugin production,
                                                             Callable<T> action) {
        try {
            Field ioField = production.getClass().getDeclaredField("io");
            ioField.setAccessible(true);
            Object io = ioField.get(production);
            Method submit = io.getClass().getDeclaredMethod("submit", Callable.class);
            submit.setAccessible(true);
            return (CompletableFuture<T>) submit.invoke(io, action);
        } catch (ReflectiveOperationException failure) {
            return CompletableFuture.failedFuture(failure instanceof InvocationTargetException target
                    ? target.getCause() : failure);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    private static void check(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
