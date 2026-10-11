package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.*;

import be.seeseemelk.mockbukkit.MockBukkit;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class ItemProvidersTest {
    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void ownsNamespaceAndClonesResults() {
        List<String> warnings = new ArrayList<>();
        ItemProviders providers = new ItemProviders(warnings::add);
        Plugin first = owner("First");
        Plugin second = owner("Second");
        ItemStack source = new ItemStack(Material.DIAMOND, 2);
        NamespacedKey key = new NamespacedKey("example", "gem");
        assertTrue(providers.register(first, "example", ignored -> source));
        assertFalse(providers.register(second, "example", ignored -> new ItemStack(Material.DIRT)));
        assertFalse(providers.unregister(second, "example"));
        ItemStack resolved = providers.resolve(key).orElseThrow();
        resolved.setAmount(7);
        assertEquals(2, source.getAmount());
        assertEquals(2, providers.resolve(key).orElseThrow().getAmount());
        assertTrue(providers.register(first, "example", ignored -> new ItemStack(Material.GOLD_INGOT)));
        assertEquals(Material.GOLD_INGOT, providers.resolve(key).orElseThrow().getType());
        providers.removeOwner(first);
        assertTrue(providers.resolve(key).isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test void rejectsBadResultsAndContainsProviderFailure() {
        List<String> warnings = new ArrayList<>();
        ItemProviders providers = new ItemProviders(warnings::add);
        Plugin owner = owner("First");
        NamespacedKey key = new NamespacedKey("example", "gem");
        assertThrows(IllegalArgumentException.class,
                () -> providers.register(owner, "recipeweaver", ignored -> null));
        assertThrows(IllegalArgumentException.class,
                () -> providers.register(owner, "Uppercase", ignored -> null));
        assertTrue(providers.register(owner, "example", ignored -> new ItemStack(Material.AIR)));
        assertTrue(providers.resolve(key).isEmpty());
        assertTrue(providers.register(owner, "example", ignored -> { throw new IllegalStateException("fixture"); }));
        assertTrue(providers.resolve(key).isEmpty());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("fixture"));
    }

    private static Plugin owner(String name) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "isEnabled" -> true;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
