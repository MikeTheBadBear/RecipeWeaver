package dev.recipeweaver.core;

import dev.recipeweaver.api.ItemProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Server-thread-only provider registry. Provider failures cannot break a crafting or command path. */
final class ItemProviders {
    private record Registration(Plugin owner, ItemProvider provider) { }

    private final Map<String, Registration> providers = new LinkedHashMap<>();
    private final Consumer<String> warning;

    ItemProviders(Consumer<String> warning) { this.warning = Objects.requireNonNull(warning); }

    boolean register(Plugin owner, String namespace, ItemProvider provider) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(provider, "provider");
        validateNamespace(namespace);
        if (!owner.isEnabled()) throw new IllegalStateException("Item provider owner is disabled");
        Registration existing = providers.get(namespace);
        if (existing != null && existing.owner() != owner) return false;
        providers.put(namespace, new Registration(owner, provider));
        return true;
    }

    boolean unregister(Plugin owner, String namespace) {
        Objects.requireNonNull(owner, "owner");
        validateNamespace(namespace);
        Registration existing = providers.get(namespace);
        if (existing == null || existing.owner() != owner) return false;
        providers.remove(namespace);
        return true;
    }

    void removeOwner(Plugin owner) {
        providers.entrySet().removeIf(entry -> entry.getValue().owner() == owner);
    }

    void clear() { providers.clear(); }

    Optional<ItemStack> resolve(NamespacedKey key) {
        Objects.requireNonNull(key, "key");
        Registration registered = providers.get(key.getNamespace());
        if (registered == null || !registered.owner().isEnabled()) return Optional.empty();
        try {
            ItemStack item = registered.provider().resolve(key);
            return item == null || item.getType().isAir() || item.getAmount() < 1
                    ? Optional.empty() : Optional.of(item.clone());
        } catch (Exception | LinkageError ex) {
            warning.accept("Item provider " + key.getNamespace() + " failed: " + ex);
            return Optional.empty();
        }
    }

    private static void validateNamespace(String namespace) {
        if (namespace == null || !namespace.matches("[a-z0-9._-]+")
                || namespace.equals("recipeweaver")) {
            throw new IllegalArgumentException("Invalid or reserved item provider namespace: " + namespace);
        }
    }
}
