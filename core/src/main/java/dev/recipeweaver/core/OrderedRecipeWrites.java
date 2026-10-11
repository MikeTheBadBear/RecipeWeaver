package dev.recipeweaver.core;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.NamespacedKey;

/** Main-thread cache updates paired with ordered off-thread file mutations. */
final class OrderedRecipeWrites<T> {
    private final Map<NamespacedKey, T> values;
    private final Consumer<Runnable> mainCallback;
    private final Map<NamespacedKey, WriteState<T>> writes = new LinkedHashMap<>();

    OrderedRecipeWrites(Map<NamespacedKey, T> values, Consumer<Runnable> mainCallback) {
        this.values = values;
        this.mainCallback = mainCallback;
    }

    boolean hasPending() {
        return writes.values().stream().anyMatch(write -> write.pending != 0);
    }

    void clear() { writes.clear(); }

    void requireNoPendingWrite(NamespacedKey key) {
        WriteState<T> state = writes.get(key);
        if (state != null && state.pending != 0) {
            throw new IllegalStateException("Recipe write is still pending: " + key);
        }
    }

    void forget(NamespacedKey key) { writes.remove(key); }

    CompletableFuture<Void> saveAsync(NamespacedKey key, T desired, AsyncIoService io,
                                      IoAction action, Runnable onRollback) {
        return enqueue(key, desired, io, action, onRollback);
    }

    CompletableFuture<Boolean> removeAsync(NamespacedKey key, AsyncIoService io,
                                           IoAction action, Runnable onRollback) {
        if (!values.containsKey(key)) return CompletableFuture.completedFuture(false);
        return enqueue(key, null, io, action, onRollback).thenApply(ignored -> true);
    }

    private CompletableFuture<Void> enqueue(NamespacedKey key, T desired,
                                            AsyncIoService io, IoAction action, Runnable onRollback) {
        WriteState<T> state = writes.computeIfAbsent(key, ignored -> new WriteState<>(values.get(key)));
        if (state.delayedFailure != null) {
            state.delayedFailure.completeExceptionally(state.delayedCause);
            state.delayedFailure = null;
            state.delayedCause = null;
        }
        long submitted = ++state.latestRevision;
        state.pending++;
        state.rollback = onRollback;
        applyCached(key, desired);
        CompletableFuture<Void> result = new CompletableFuture<>();
        io.submit(() -> {
            action.run();
            return null;
        }).whenComplete((ignored, failure) -> {
            try {
                mainCallback.accept(() -> {
                    state.pending--;
                    if (failure == null && submitted > state.durableRevision) {
                        state.durable = desired;
                        state.durableRevision = submitted;
                    }
                    if (state.pending == 0) {
                        if (state.durableRevision != state.latestRevision) {
                            applyCached(key, state.durable);
                            writes.remove(key, state);
                            state.rollback.run();
                        } else writes.remove(key, state);
                    }
                    if (failure == null) result.complete(null);
                    else if (submitted == state.latestRevision && state.pending != 0) {
                        state.delayedFailure = result;
                        state.delayedCause = failure;
                    } else result.completeExceptionally(failure);
                    if (state.pending == 0 && state.delayedFailure != null) {
                        state.delayedFailure.completeExceptionally(state.delayedCause);
                        state.delayedFailure = null;
                        state.delayedCause = null;
                    }
                });
            } catch (RuntimeException callbackFailure) {
                result.completeExceptionally(callbackFailure);
            }
        });
        return result;
    }

    private void applyCached(NamespacedKey key, T desired) {
        if (desired == null) values.remove(key);
        else values.put(key, desired);
    }

    @FunctionalInterface
    interface IoAction { void run() throws IOException; }

    private static final class WriteState<T> {
        private T durable;
        private long latestRevision;
        private long durableRevision;
        private int pending;
        private Runnable rollback;
        private CompletableFuture<Void> delayedFailure;
        private Throwable delayedCause;

        private WriteState(T durable) { this.durable = durable; }
    }
}
