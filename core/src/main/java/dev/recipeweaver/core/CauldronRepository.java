package dev.recipeweaver.core;

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
import org.bukkit.inventory.ItemStack;

/** Persists the first ingredient while a player is mixing in a water cauldron. */
final class CauldronRepository {
    private final Path path;
    private final boolean allowSynchronous;
    private final BooleanSupplier snapshotBusy;
    private final Consumer<Runnable> mainCallback;
    private final Map<Position, ItemStack> pending = new LinkedHashMap<>();
    private boolean pendingWrite;
    private volatile Runnable pendingCompletion;
    private Position writingPosition;

    CauldronRepository(Path root) { this(root, () -> false); }

    CauldronRepository(Path root, BooleanSupplier snapshotBusy) {
        this(root, snapshotBusy, Runnable::run, true);
    }

    CauldronRepository(Path root, BooleanSupplier snapshotBusy, Consumer<Runnable> mainCallback) {
        this(root, snapshotBusy, mainCallback, false);
    }

    private CauldronRepository(Path root, BooleanSupplier snapshotBusy,
                               Consumer<Runnable> mainCallback, boolean allowSynchronous) {
        path = root.resolve("cauldrons.yml");
        this.allowSynchronous = allowSynchronous;
        this.snapshotBusy = snapshotBusy;
        this.mainCallback = mainCallback;
    }

    void load() throws IOException {
        requireTestOnly();
        load(DiskSnapshot.read(path.getParent()));
    }

    void load(DiskSnapshot snapshot) throws IOException {
        if (pendingWrite) throw new IOException("Cauldron write is still pending");
        YamlConfiguration config = snapshot.yaml("cauldrons.yml");
        if (config == null) { pending.clear(); return; }
        if (config.getInt("schema") != 1) throw new IOException("Unsupported cauldrons.yml schema");
        Map<Position, ItemStack> loaded = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("pending");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                try {
                    Position position = Position.parse(id);
                    ItemStack item = section.getItemStack(id);
                    if (item == null || item.getType().isAir() || item.getAmount() < 1
                            || item.getAmount() > item.getMaxStackSize()) {
                        throw new IllegalArgumentException("Invalid pending ingredient");
                    }
                    if (loaded.putIfAbsent(position, item.clone()) != null) {
                        throw new IllegalArgumentException("Duplicate cauldron");
                    }
                } catch (RuntimeException ex) {
                    throw new IOException("Invalid cauldron entry " + id + ": " + ex.getMessage(), ex);
                }
            }
        }
        replace(loaded);
    }

    ItemStack get(Location location) {
        ItemStack item = pending.get(Position.of(location));
        return item == null ? null : item.clone();
    }

    boolean pendingWrite() { return pendingWrite; }
    /** Called on the server thread after the I/O worker's shutdown barrier. */
    void completePendingOnShutdown() {
        Runnable completion = pendingCompletion;
        if (completion != null) completion.run();
    }


    boolean isProtected(Location location) {
        Position position = Position.of(location);
        return pending.containsKey(position)
                || (pendingWrite && position.equals(writingPosition));
    }

    /** Legacy synchronous helper for focused tests; runtime listeners use putAsync. */
    void put(Location location, ItemStack item) throws IOException {
        requireTestOnly();
        requireWritable();
        validate(item);
        Map<Position, ItemStack> next = new LinkedHashMap<>(pending);
        next.put(Position.of(location), item.clone());
        RecipeRepository.writeAtomically(path, serialize(next));
        replace(next);
    }

    CompletableFuture<Void> putAsync(Location location, ItemStack item, AsyncIoService io)
            throws IOException {
        requireWritable();
        validate(item);
        Position position = Position.of(location);
        Map<Position, ItemStack> next = new LinkedHashMap<>(pending);
        next.put(position, item.clone());
        return enqueue(next, position, io);
    }

    /** Legacy synchronous helper for focused tests; runtime listeners use removeAsync. */
    ItemStack remove(Location location) throws IOException {
        requireTestOnly();
        requireWritable();
        Position position = Position.of(location);
        ItemStack old = pending.get(position);
        if (old == null) return null;
        Map<Position, ItemStack> next = new LinkedHashMap<>(pending);
        next.remove(position);
        RecipeRepository.writeAtomically(path, serialize(next));
        replace(next);
        return old.clone();
    }

    CompletableFuture<ItemStack> removeAsync(Location location, AsyncIoService io)
            throws IOException {
        requireWritable();
        Position position = Position.of(location);
        ItemStack old = pending.get(position);
        if (old == null) return CompletableFuture.completedFuture(null);
        Map<Position, ItemStack> next = new LinkedHashMap<>(pending);
        next.remove(position);
        return enqueue(next, position, io).thenApply(ignored -> old.clone());
    }

    private CompletableFuture<Void> enqueue(Map<Position, ItemStack> next,
                                            Position position, AsyncIoService io) {
        // Bukkit ItemStack serialization stays on the caller's server thread.
        String yaml = serialize(next);
        pendingWrite = true;
        writingPosition = position;
        CompletableFuture<Void> result = new CompletableFuture<>();
        io.submit(() -> {
            io.writeDocument(path, yaml);
            return null;
        }).whenComplete((ignored, failure) -> {
            AtomicBoolean completed = new AtomicBoolean();
            Runnable completion = () -> {
                if (!completed.compareAndSet(false, true)) return;
                if (failure == null) replace(next);
                pendingWrite = false;
                writingPosition = null;
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

    private void replace(Map<Position, ItemStack> values) {
        pending.clear();
        values.forEach((position, item) -> pending.put(position, item.clone()));
    }

    private void requireTestOnly() {
        if (!allowSynchronous) throw new IllegalStateException(
                "Synchronous position storage is unavailable in the live plugin");
    }

    private void requireWritable() throws IOException {
        if (snapshotBusy.getAsBoolean()) {
            throw new IOException("Backup, reload, or restore in progress; retry when it finishes");
        }
        if (pendingWrite) throw new IOException("Cauldron save in progress; retry when it finishes");
    }

    private static void validate(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() < 1
                || item.getAmount() > item.getMaxStackSize()) {
            throw new IllegalArgumentException("A pending cauldron ingredient must fit one stack");
        }
    }

    private static String serialize(Map<Position, ItemStack> values) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("schema", 1);
        for (var entry : values.entrySet()) {
            config.set("pending." + entry.getKey().id(), entry.getValue());
        }
        return config.saveToString();
    }

    private record Position(UUID world, int x, int y, int z) {
        static Position of(Location location) {
            if (location.getWorld() == null) throw new IllegalArgumentException("Cauldron has no world");
            return new Position(location.getWorld().getUID(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        static Position parse(String id) {
            String[] parts = id.split("_", -1);
            if (parts.length != 4) throw new IllegalArgumentException("Invalid position");
            return new Position(UUID.fromString(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        }

        String id() { return world + "_" + x + "_" + y + "_" + z; }
    }
}
