package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.*;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.GridCraftBatch;
import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.GridTier;
import dev.recipeweaver.api.RecipeKind;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class GridExtensionsTest {
    private static final NamespacedKey KEY = new NamespacedKey("recipeweaver", "extension_test");

    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void conditionsComposeAndCannotMutateOtherInputs() {
        List<String> warnings = new ArrayList<>();
        GridExtensions extensions = new GridExtensions(warnings::add);
        Plugin first = owner("First", new AtomicBoolean(true));
        Plugin second = owner("Second", new AtomicBoolean(true));
        ItemStack source = new ItemStack(Material.STONE);
        GridRecipeDefinition recipe = recipe();
        Player player = player();
        extensions.registerCondition(first, KEY, (who, matched, inputs) -> {
            assertSame(player, who);
            assertSame(recipe, matched);
            inputs.get(0).setAmount(7);
            assertThrows(UnsupportedOperationException.class, () -> inputs.add(source));
            return true;
        });
        extensions.registerCondition(second, KEY, (who, matched, inputs) ->
                inputs.get(0).getAmount() == 1);
        assertTrue(extensions.allows(player, recipe, Arrays.asList(source, null, null, null)));
        assertEquals(1, source.getAmount());
        extensions.registerCondition(second, KEY, (who, matched, inputs) -> false);
        assertFalse(extensions.allows(player, recipe, Arrays.asList(source, null, null, null)));
        assertTrue(extensions.unregisterCondition(second, KEY));
        assertFalse(extensions.unregisterCondition(second, KEY));
        assertTrue(extensions.allows(player, recipe, Arrays.asList(source, null, null, null)));
        assertTrue(warnings.isEmpty());
    }

    @Test void failingConditionDeniesAndWarnsOnce() {
        List<String> warnings = new ArrayList<>();
        GridExtensions extensions = new GridExtensions(warnings::add);
        AtomicBoolean enabled = new AtomicBoolean(true);
        Plugin owner = owner("Broken", enabled);
        extensions.registerCondition(owner, KEY, (who, matched, inputs) -> {
            throw new IllegalStateException("fixture failure");
        });
        List<ItemStack> inputs = Arrays.asList(new ItemStack(Material.STONE), null, null, null);
        assertFalse(extensions.allows(player(), recipe(), inputs));
        assertFalse(extensions.allows(player(), recipe(), inputs));
        assertEquals(1, warnings.size());
        enabled.set(false);
        assertTrue(extensions.allows(player(), recipe(), inputs));
        extensions.removeOwner(owner);
        assertFalse(extensions.unregisterCondition(owner, KEY));
        assertThrows(IllegalStateException.class,
                () -> extensions.registerCondition(owner, KEY, (who, matched, items) -> true));
    }

    @Test void postCommitActionsHonorCadenceAndIsolateFailures() {
        List<String> warnings = new ArrayList<>();
        GridExtensions extensions = new GridExtensions(warnings::add);
        Plugin first = owner("First", new AtomicBoolean(true));
        Plugin second = owner("Second", new AtomicBoolean(true));
        AtomicInteger once = new AtomicInteger();
        AtomicInteger perBatch = new AtomicInteger();
        extensions.registerAction(first, KEY, false, (who, batch) -> {
            once.incrementAndGet();
            batch.result().setAmount(9);
            throw new IllegalStateException("action failure");
        });
        extensions.registerAction(second, KEY, true, (who, batch) -> {
            perBatch.incrementAndGet();
            assertEquals(1, batch.result().getAmount());
        });
        GridCraftBatch batch = new GridCraftBatch(KEY, new ItemStack(Material.DIAMOND));
        extensions.runActions(player(), List.of(batch, batch));
        assertEquals(1, once.get());
        assertEquals(2, perBatch.get());
        assertEquals(1, warnings.size());
        extensions.runActions(player(), List.of(batch));
        assertEquals(2, once.get());
        assertEquals(3, perBatch.get());
        assertEquals(1, warnings.size());
        assertTrue(extensions.unregisterAction(first, KEY));
        extensions.removeOwner(second);
        extensions.runActions(player(), List.of(batch));
        assertEquals(2, once.get());
        assertEquals(3, perBatch.get());
    }

    @Test void removedOwnerCannotRunLaterInTheSameConditionOrActionDispatch() {
        GridExtensions extensions = new GridExtensions(message -> fail(message));
        Plugin first = owner("First", new AtomicBoolean(true));
        Plugin removed = owner("Removed", new AtomicBoolean(true));
        AtomicInteger staleConditions = new AtomicInteger();
        extensions.registerCondition(first, KEY, (who, recipe, inputs) -> {
            extensions.removeOwner(removed);
            return true;
        });
        extensions.registerCondition(removed, KEY, (who, recipe, inputs) -> {
            staleConditions.incrementAndGet();
            return false;
        });
        assertTrue(extensions.allows(player(), recipe(),
                Arrays.asList(new ItemStack(Material.STONE), null, null, null)));
        assertEquals(0, staleConditions.get());

        AtomicInteger staleActions = new AtomicInteger();
        extensions.registerAction(first, KEY, false,
                (who, batch) -> extensions.removeOwner(removed));
        extensions.registerAction(removed, KEY, true,
                (who, batch) -> staleActions.incrementAndGet());
        extensions.runActions(player(), List.of(new GridCraftBatch(KEY,
                new ItemStack(Material.DIAMOND))));
        assertEquals(0, staleActions.get());
    }

    private static GridRecipeDefinition recipe() {
        return new GridRecipeDefinition(KEY, GridTier.ELITE, RecipeKind.SHAPED, 2,
                Arrays.asList(new ItemStack(Material.STONE), null, null, null),
                new ItemStack(Material.DIAMOND), "", true, false);
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "ExtensionPlayer";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Plugin owner(String name, AtomicBoolean enabled) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "isEnabled" -> enabled.get();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
