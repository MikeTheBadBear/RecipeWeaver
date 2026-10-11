package dev.recipeweaver.core;

import dev.recipeweaver.api.GridTier;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Persistent locations of placed RecipeWeaver advanced and elite tables. */
final class TableRepository {
    private final Path path;
    private final boolean allowSynchronous;
    private final BooleanSupplier snapshotBusy;
    private final Consumer<Runnable> mainCallback;
    private final Map<Position, GridTier> tables = new LinkedHashMap<>();
    private boolean pendingWrite;
    private volatile Runnable pendingCompletion;

    TableRepository(Path root) { this(root, () -> false); }

    TableRepository(Path root, BooleanSupplier snapshotBusy) {
        this(root, snapshotBusy, Runnable::run, true);
    }

    TableRepository(Path root, BooleanSupplier snapshotBusy, Consumer<Runnable> mainCallback) {
        this(root, snapshotBusy, mainCallback, false);
    }

    private TableRepository(Path root, BooleanSupplier snapshotBusy,
                            Consumer<Runnable> mainCallback, boolean allowSynchronous) {
        path = root.resolve("tables.yml");
        this.allowSynchronous = allowSynchronous;
        this.snapshotBusy = snapshotBusy;
        this.mainCallback = mainCallback;
    }

    void load() throws IOException {
        requireTestOnly();
        load(DiskSnapshot.read(path.getParent()));
    }

    void load(DiskSnapshot snapshot) throws IOException {
        if (pendingWrite) throw new IOException("Table write is still pending");
        YamlConfiguration config = snapshot.yaml("tables.yml");
        if (config == null) { tables.clear(); return; }
        if (config.getInt("schema") != 1) throw new IOException("Unsupported tables.yml schema");
        Map<Position, GridTier> loaded = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("tables");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                try {
                    String[] parts = id.split("_", -1);
                    if (parts.length != 4) throw new IllegalArgumentException("Invalid position");
                    Position position = new Position(UUID.fromString(parts[0]),
                            Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                    GridTier tier = GridTier.valueOf(section.getString(id, ""));
                    if (loaded.putIfAbsent(position, tier) != null) throw new IllegalArgumentException("Duplicate table");
                } catch (RuntimeException ex) {
                    throw new IOException("Invalid table entry " + id + ": " + ex.getMessage(), ex);
                }
            }
        }
        tables.clear();
        tables.putAll(loaded);
    }

    GridTier get(Location location) { return tables.get(Position.of(location)); }
    boolean pendingWrite() { return pendingWrite; }
    /** Called on the server thread after the I/O worker's shutdown barrier. */
    void completePendingOnShutdown() {
        Runnable completion = pendingCompletion;
        if (completion != null) completion.run();
    }


    /** Legacy synchronous helper for focused tests; runtime listeners use putAsync. */
    void put(Location location, GridTier tier) throws IOException {
        requireTestOnly();
        requireWritable();
        Map<Position, GridTier> next = new LinkedHashMap<>(tables);
        next.put(Position.of(location), tier);
        RecipeRepository.writeAtomically(path, serialize(next));
        replace(next);
    }

    CompletableFuture<Void> putAsync(Location location, GridTier tier, AsyncIoService io) throws IOException {
        requireWritable();
        Map<Position, GridTier> next = new LinkedHashMap<>(tables);
        next.put(Position.of(location), tier);
        return enqueue(next, io);
    }

    /** Legacy synchronous helper for focused tests; runtime listeners use removeAsync. */
    boolean remove(Location location) throws IOException {
        requireTestOnly();
        requireWritable();
        Position position = Position.of(location);
        if (!tables.containsKey(position)) return false;
        Map<Position, GridTier> next = new LinkedHashMap<>(tables);
        next.remove(position);
        RecipeRepository.writeAtomically(path, serialize(next));
        replace(next);
        return true;
    }

    CompletableFuture<Boolean> removeAsync(Location location, AsyncIoService io) throws IOException {
        requireWritable();
        Position position = Position.of(location);
        if (!tables.containsKey(position)) return CompletableFuture.completedFuture(false);
        Map<Position, GridTier> next = new LinkedHashMap<>(tables);
        next.remove(position);
        return enqueue(next, io).thenApply(ignored -> true);
    }

    private CompletableFuture<Void> enqueue(Map<Position, GridTier> next, AsyncIoService io) {
        // Position and YAML preparation use Bukkit state only on the caller's server thread.
        String yaml = serialize(next);
        Map<Position, GridTier> previous = new LinkedHashMap<>(tables);
        replace(next);
        pendingWrite = true;
        CompletableFuture<Void> result = new CompletableFuture<>();
        io.submit(() -> {
            io.writeDocument(path, yaml);
            return null;
        }).whenComplete((ignored, failure) -> {
            AtomicBoolean completed = new AtomicBoolean();
            Runnable completion = () -> {
                if (!completed.compareAndSet(false, true)) return;
                pendingWrite = false;
                if (failure != null) replace(previous);
                pendingCompletion = null;
                if (failure == null) result.complete(null);
                else result.completeExceptionally(failure);
            };
            pendingCompletion = completion;
            try { mainCallback.accept(completion); }
            catch (RuntimeException callbackFailure) {
                // Plugin disable can reject new scheduler tasks. The shutdown barrier
                // drains this completion on the server thread after the write finishes.
            }
        });
        return result;
    }

    private void replace(Map<Position, GridTier> values) {
        tables.clear();
        tables.putAll(values);
    }

    private void requireTestOnly() {
        if (!allowSynchronous) throw new IllegalStateException(
                "Synchronous position storage is unavailable in the live plugin");
    }

    private void requireWritable() throws IOException {
        if (snapshotBusy.getAsBoolean()) {
            throw new IOException("Backup, reload, or restore in progress; retry when it finishes");
        }
        if (pendingWrite) throw new IOException("Table save in progress; retry when it finishes");
    }

    private static String serialize(Map<Position, GridTier> values) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("schema", 1);
        for (var entry : values.entrySet()) {
            Position position = entry.getKey();
            config.set("tables." + position.world + "_" + position.x + "_"
                    + position.y + "_" + position.z, entry.getValue().name());
        }
        return config.saveToString();
    }

    private record Position(UUID world, int x, int y, int z) {
        static Position of(Location location) {
            if (location.getWorld() == null) throw new IllegalArgumentException("Table has no world");
            return new Position(location.getWorld().getUID(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }
}
