package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.*;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.StationCraftBatch;
import dev.recipeweaver.api.StationCraftCompleteEvent;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
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

final class StationExtensionsTest {
    private static final NamespacedKey KEY = new NamespacedKey("recipeweaver", "station_extension_test");

    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void conditionsComposeOnDefensiveInputsAndFailClosed() {
        List<String> warnings = new ArrayList<>();
        StationExtensions extensions = new StationExtensions(warnings::add);
        Plugin first = owner("First", new AtomicBoolean(true));
        AtomicBoolean secondEnabled = new AtomicBoolean(true);
        Plugin second = owner("Second", secondEnabled);
        StationRecipeDefinition recipe = recipe();
        Player player = player();
        ItemStack source = new ItemStack(Material.STONE);
        extensions.registerCondition(first, KEY, (who, matched, inputs) -> {
            assertSame(player, who);
            assertSame(recipe, matched);
            inputs.get(0).setAmount(12);
            assertThrows(UnsupportedOperationException.class, () -> inputs.add(source));
            return true;
        });
        extensions.registerCondition(second, KEY, (who, matched, inputs) ->
                inputs.get(0).getAmount() == 1 && inputs.get(1) == null);
        assertTrue(extensions.allows(player, recipe, Arrays.asList(source, null)));
        assertEquals(1, source.getAmount());
        extensions.registerCondition(second, KEY, (who, matched, inputs) -> {
            throw new IllegalStateException("fixture failure");
        });
        assertFalse(extensions.allows(player, recipe, Arrays.asList(source, null)));
        assertFalse(extensions.allows(player, recipe, Arrays.asList(source, null)));
        assertEquals(1, warnings.size());
        secondEnabled.set(false);
        assertTrue(extensions.allows(player, recipe, Arrays.asList(source, null)));
        extensions.removeOwner(first);
        extensions.removeOwner(second);
        assertFalse(extensions.unregisterCondition(first, KEY));
    }

    @Test void actionsRunOnlyOnCallAndAreIsolated() {
        List<String> warnings = new ArrayList<>();
        StationExtensions extensions = new StationExtensions(warnings::add);
        Plugin first = owner("First", new AtomicBoolean(true));
        Plugin second = owner("Second", new AtomicBoolean(true));
        AtomicInteger calls = new AtomicInteger();
        extensions.registerAction(first, KEY, (who, batch) -> {
            batch.result().setAmount(7);
            throw new IllegalStateException("fixture failure");
        });
        extensions.registerAction(second, KEY, (who, batch) -> {
            assertEquals(KEY, batch.recipe());
            assertEquals(StationKind.ANVIL, batch.kind());
            assertEquals(1, batch.result().getAmount());
            calls.incrementAndGet();
        });
        StationCraftBatch batch = new StationCraftBatch(KEY, StationKind.ANVIL,
                new ItemStack(Material.DIAMOND));
        extensions.runActions(player(), batch);
        extensions.runActions(player(), batch);
        assertEquals(2, calls.get());
        assertEquals(1, warnings.size());
        assertTrue(extensions.unregisterAction(first, KEY));
        extensions.removeOwner(second);
        extensions.runActions(player(), batch);
        assertEquals(2, calls.get());
    }

    @Test void removedOwnerCannotRunLaterInTheSameConditionOrActionDispatch() {
        StationExtensions extensions = new StationExtensions(message -> fail(message));
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
        assertTrue(extensions.allows(player(), recipe(), List.of(new ItemStack(Material.STONE))));
        assertEquals(0, staleConditions.get());

        AtomicInteger staleActions = new AtomicInteger();
        extensions.registerAction(first, KEY, (who, batch) -> extensions.removeOwner(removed));
        extensions.registerAction(removed, KEY, (who, batch) -> staleActions.incrementAndGet());
        extensions.runActions(player(), new StationCraftBatch(KEY, StationKind.ANVIL,
                new ItemStack(Material.DIAMOND)));
        assertEquals(0, staleActions.get());
    }

    @Test void cauldronCallbacksReceiveDetachedIngredientsAndCommittedBatch() {
        StationExtensions extensions = new StationExtensions(message -> fail(message));
        Plugin owner = owner("CauldronOwner", new AtomicBoolean(true));
        Player player = player();
        StationRecipeDefinition recipe = new StationRecipeDefinition(KEY, StationKind.CAULDRON,
                List.of(new ItemStack(Material.COBBLESTONE), new ItemStack(Material.GRAVEL)),
                new ItemStack(Material.LAPIS_LAZULI), "", 20, 0, 0, true, false);
        ItemStack first = new ItemStack(Material.COBBLESTONE);
        ItemStack second = new ItemStack(Material.GRAVEL);
        AtomicBoolean allowed = new AtomicBoolean(false);
        AtomicInteger checks = new AtomicInteger();
        extensions.registerCondition(owner, KEY, (who, matched, inputs) -> {
            assertSame(player, who);
            assertSame(recipe, matched);
            assertEquals(Material.COBBLESTONE, inputs.get(0).getType());
            assertEquals(Material.GRAVEL, inputs.get(1).getType());
            inputs.get(0).setType(Material.DIRT);
            checks.incrementAndGet();
            return allowed.get();
        });
        assertFalse(extensions.allows(player, recipe, List.of(first, second)));
        assertEquals(Material.COBBLESTONE, first.getType());
        allowed.set(true);
        assertTrue(extensions.allows(player, recipe, List.of(first, second)));
        assertEquals(2, checks.get());

        AtomicInteger actions = new AtomicInteger();
        extensions.registerAction(owner, KEY, (who, batch) -> {
            assertSame(player, who);
            assertEquals(StationKind.CAULDRON, batch.kind());
            assertEquals(Material.LAPIS_LAZULI, batch.result().getType());
            actions.incrementAndGet();
        });
        ItemStack output = new ItemStack(Material.LAPIS_LAZULI);
        StationCraftBatch batch = new StationCraftBatch(KEY, StationKind.CAULDRON, output);
        output.setType(Material.DIRT);
        extensions.runActions(player, batch);
        assertEquals(1, actions.get());
        assertEquals(Material.LAPIS_LAZULI, batch.result().getType());
        assertThrows(IllegalArgumentException.class, () -> new StationCraftBatch(
                KEY, StationKind.SMITHING_TRANSFORM, new ItemStack(Material.IRON_INGOT)));
    }

    @Test void automatedBrewingCallbacksAcceptNullPlayerAndDetachedBottleInputs() {
        StationExtensions extensions = new StationExtensions(message -> fail(message));
        Plugin owner = owner("BrewingOwner", new AtomicBoolean(true));
        StationRecipeDefinition recipe = new StationRecipeDefinition(KEY, StationKind.BREWING,
                List.of(new ItemStack(Material.NETHER_WART), new ItemStack(Material.POTION)),
                new ItemStack(Material.SPLASH_POTION), "", 20, 0, 0, true, false);
        ItemStack ingredient = new ItemStack(Material.NETHER_WART, 2);
        ItemStack bottle = new ItemStack(Material.POTION);
        AtomicInteger conditions = new AtomicInteger();
        extensions.registerCondition(owner, KEY, (player, matched, inputs) -> {
            assertNull(player);
            assertSame(recipe, matched);
            assertEquals(2, inputs.get(0).getAmount());
            assertEquals(Material.POTION, inputs.get(1).getType());
            inputs.get(0).setType(Material.DIRT);
            conditions.incrementAndGet();
            return true;
        });
        assertTrue(extensions.allows(null, recipe, List.of(ingredient, bottle)));
        assertEquals(Material.NETHER_WART, ingredient.getType());
        assertEquals(1, conditions.get());

        AtomicInteger actions = new AtomicInteger();
        extensions.registerAction(owner, KEY, (player, batch) -> {
            assertNull(player);
            assertEquals(StationKind.BREWING, batch.kind());
            assertEquals(Material.SPLASH_POTION, batch.result().getType());
            actions.incrementAndGet();
        });
        StationCraftBatch first = new StationCraftBatch(KEY, StationKind.BREWING,
                new ItemStack(Material.SPLASH_POTION));
        StationCraftCompleteEvent event = new StationCraftCompleteEvent(null, first);
        assertNull(event.player());
        assertEquals(StationKind.BREWING, event.batch().kind());
        extensions.runActions(null, first);
        extensions.runActions(null, new StationCraftBatch(KEY, StationKind.BREWING,
                new ItemStack(Material.SPLASH_POTION)));
        assertEquals(2, actions.get());
        assertThrows(NullPointerException.class, () -> new StationCraftCompleteEvent(
                null, new StationCraftBatch(KEY, StationKind.ANVIL,
                        new ItemStack(Material.DIAMOND))));
    }

    @Test void automatedCookingConditionsReceiveNullPlayerAndDetachedSingleInput() {
        StationExtensions extensions = new StationExtensions(message -> fail(message));
        Plugin owner = owner("CookingOwner", new AtomicBoolean(true));
        for (StationKind kind : List.of(StationKind.FURNACE, StationKind.BLAST_FURNACE,
                StationKind.SMOKER, StationKind.CAMPFIRE)) {
            StationRecipeDefinition recipe = new StationRecipeDefinition(KEY, kind,
                    List.of(new ItemStack(Material.DIRT)), new ItemStack(Material.DIAMOND),
                    "", 20, 0, 0, true, false);
            ItemStack source = new ItemStack(Material.DIRT);
            AtomicInteger checks = new AtomicInteger();
            extensions.registerCondition(owner, KEY, (player, selected, inputs) -> {
                assertNull(player);
                assertSame(recipe, selected);
                assertEquals(1, inputs.size());
                assertEquals(Material.DIRT, inputs.get(0).getType());
                inputs.get(0).setType(Material.STONE);
                checks.incrementAndGet();
                return false;
            });
            assertFalse(extensions.allows(null, recipe, List.of(source)));
            assertEquals(Material.DIRT, source.getType());
            assertEquals(1, checks.get());
        }
    }

    @Test void provenFurnaceBatchesAllowNullPlayerCompletionDispatch() {
        StationExtensions extensions = new StationExtensions(message -> fail(message));
        Plugin owner = owner("CookActionOwner", new AtomicBoolean(true));
        AtomicInteger actions = new AtomicInteger();
        extensions.registerAction(owner, KEY, (player, batch) -> {
            assertNull(player);
            assertEquals(KEY, batch.recipe());
            assertEquals(Material.DIAMOND, batch.result().getType());
            actions.incrementAndGet();
        });
        for (StationKind kind : List.of(StationKind.FURNACE, StationKind.BLAST_FURNACE,
                StationKind.SMOKER, StationKind.CAMPFIRE)) {
            StationCraftBatch batch = new StationCraftBatch(KEY, kind,
                    new ItemStack(Material.DIAMOND));
            assertNull(new StationCraftCompleteEvent(null, batch).player());
            extensions.runActions(null, batch);
        }
        assertEquals(4, actions.get());
    }

    private static StationRecipeDefinition recipe() {
        return new StationRecipeDefinition(KEY, StationKind.ANVIL,
                Arrays.asList(new ItemStack(Material.STONE), null),
                new ItemStack(Material.DIAMOND), "", 200, 0, 0, true, false);
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return "StationExtensionPlayer";
                    throw new UnsupportedOperationException(method.getName());
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
