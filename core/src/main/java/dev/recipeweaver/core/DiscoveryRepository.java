package dev.recipeweaver.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/** Persisted RecipeWeaver recipe discoveries, indexed by player UUID. */
final class DiscoveryRepository {
    private final Plugin plugin;
    private final Path directory;
    private final Map<UUID, Set<NamespacedKey>> known = new ConcurrentHashMap<>();
    private final Map<UUID, Set<NamespacedKey>> pendingKnown = new LinkedHashMap<>();
    private final Map<UUID, Map<NamespacedKey, CompletableFuture<Boolean>>> pendingWrites =
            new LinkedHashMap<>();

    DiscoveryRepository(Plugin plugin) { this(plugin, plugin.getDataFolder().toPath()); }

    DiscoveryRepository(Plugin plugin, Path root) {
        this.plugin = plugin;
        directory = root.resolve("discoveries");
    }

    void load() throws IOException { LegacyIoGuard.testOnly(plugin); load(DiskSnapshot.read(directory.getParent())); }

    void load(DiskSnapshot snapshot) throws IOException {
        Map<UUID, Set<NamespacedKey>> loaded = new LinkedHashMap<>();
        for (String name : snapshot.directory("discoveries").keySet()) {
            if (!name.endsWith(".yml")) throw new IOException("Invalid discovery file: " + name);
            try {
                UUID player = UUID.fromString(name.substring(0, name.length() - 4));
                if (!name.equals(player + ".yml")) throw new IllegalArgumentException("Invalid player UUID");
                YamlConfiguration config = snapshot.yaml("discoveries/" + name);
                if (config.getInt("schema") != 1) throw new IllegalArgumentException("Unsupported schema");
                Object raw = config.get("recipes");
                if (!(raw instanceof java.util.List<?> values)) {
                    throw new IllegalArgumentException("Missing recipe IDs");
                }
                Set<NamespacedKey> keys = new LinkedHashSet<>();
                for (Object value : values) {
                    if (!(value instanceof String id)) throw new IllegalArgumentException("Invalid recipe ID");
                    NamespacedKey key = NamespacedKey.fromString(id, plugin);
                    if (key == null || !key.getNamespace().equals("recipeweaver")
                            || !id.equals(key.toString()) || !keys.add(key)) {
                        throw new IllegalArgumentException("Invalid or duplicate recipe ID");
                    }
                }
                if (loaded.putIfAbsent(player, Set.copyOf(keys)) != null) {
                    throw new IllegalArgumentException("Duplicate player UUID");
                }
            } catch (IOException | RuntimeException ex) {
                throw new IOException("Invalid discovery file " + name + ": " + ex.getMessage(), ex);
            }
        }
        known.clear();
        known.putAll(loaded);
        synchronized (this) {
            pendingKnown.clear();
            pendingKnown.putAll(loaded);
            pendingWrites.clear();
        }
    }

    boolean knows(UUID player, NamespacedKey key) {
        return known.getOrDefault(player, Set.of()).contains(key);
    }

    boolean discover(UUID player, NamespacedKey key) throws IOException {
        if (!key.getNamespace().equals("recipeweaver")) {
            throw new IllegalArgumentException("Only RecipeWeaver recipes can be discovered");
        }
        if (knows(player, key)) return false;
        Set<NamespacedKey> next = new LinkedHashSet<>(known.getOrDefault(player, Set.of()));
        next.add(key);
        RecipeRepository.writeAtomically(directory.resolve(player + ".yml"), encode(next));
        Set<NamespacedKey> snapshot = Set.copyOf(next);
        known.put(player, snapshot);
        synchronized (this) { pendingKnown.put(player, snapshot); }
        return true;
    }

    /** Serializes on the caller thread, then writes the immutable snapshot on the I/O worker. */
    synchronized CompletableFuture<Boolean> discoverAsync(UUID player, NamespacedKey key, AsyncIoService io) {
        if (!key.getNamespace().equals("recipeweaver")) {
            throw new IllegalArgumentException("Only RecipeWeaver recipes can be discovered");
        }
        Set<NamespacedKey> current = pendingKnown.getOrDefault(player, known.getOrDefault(player, Set.of()));
        if (current.contains(key)) {
            if (knows(player, key)) return CompletableFuture.completedFuture(false);
            CompletableFuture<Boolean> pending = pendingWrites.getOrDefault(player, Map.of()).get(key);
            return pending == null ? CompletableFuture.completedFuture(false)
                    : pending.thenApply(saved -> false);
        }
        Set<NamespacedKey> next = new LinkedHashSet<>(current);
        next.add(key);
        Set<NamespacedKey> snapshot = Set.copyOf(next);
        String yaml = encode(snapshot);
        pendingKnown.put(player, snapshot);
        CompletableFuture<Boolean> write = io.submit(() -> {
            io.writeDocument(directory.resolve(player + ".yml"), yaml);
            known.put(player, snapshot);
            return true;
        });
        Map<NamespacedKey, CompletableFuture<Boolean>> writes = pendingWrites.computeIfAbsent(
                player, ignored -> new LinkedHashMap<>());
        for (NamespacedKey discovered : snapshot) {
            if (!knows(player, discovered)) writes.put(discovered, write);
        }
        write.whenComplete((saved, failure) -> {
            synchronized (DiscoveryRepository.this) {
                Map<NamespacedKey, CompletableFuture<Boolean>> currentWrites = pendingWrites.get(player);
                if (currentWrites != null) {
                    currentWrites.entrySet().removeIf(entry -> entry.getValue() == write);
                    if (currentWrites.isEmpty()) pendingWrites.remove(player);
                }
                if (failure != null && pendingKnown.get(player) == snapshot) {
                    pendingKnown.put(player, known.getOrDefault(player, Set.of()));
                }
            }
        });
        return write;
    }

    private static String encode(Set<NamespacedKey> keys) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("schema", 1);
        config.set("recipes", keys.stream().map(NamespacedKey::toString).sorted().toList());
        return config.saveToString();
    }
}
