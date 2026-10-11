package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import dev.recipeweaver.api.WeatherCondition;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.recipe.CookingBookCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class NativeSmithingGuardTest {
    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void transformChecksAllInputsWithoutRejectingBaseMetadataOnTheResult() {
        var saved = new StationRecipeDefinition(
                new NamespacedKey("recipeweaver", "smith_guard_test"),
                StationKind.SMITHING_TRANSFORM, List.of(
                        new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                        new ItemStack(Material.DIAMOND_SWORD),
                        new ItemStack(Material.NETHERITE_INGOT)),
                new ItemStack(Material.NETHERITE_SWORD), "", 100, 0, 0, true, false);
        ItemStack result = new ItemStack(Material.NETHERITE_SWORD);
        Damageable meta = (Damageable) result.getItemMeta();
        meta.setDamage(12);
        result.setItemMeta(meta);
        assertTrue(NativeSmithingGuard.allowsRecipe(saved, false,
                new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                new ItemStack(Material.DIAMOND_SWORD), new ItemStack(Material.NETHERITE_INGOT),
                result));
        assertFalse(NativeSmithingGuard.allowsRecipe(saved, false,
                new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                new ItemStack(Material.IRON_SWORD), new ItemStack(Material.NETHERITE_INGOT),
                result));
        assertFalse(NativeSmithingGuard.allowsRecipe(saved, true,
                new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                new ItemStack(Material.DIAMOND_SWORD), new ItemStack(Material.NETHERITE_INGOT),
                result));
        assertFalse(NativeSmithingGuard.allowsRecipe(saved, false,
                new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                new ItemStack(Material.DIAMOND_SWORD), new ItemStack(Material.NETHERITE_INGOT),
                new ItemStack(Material.DIAMOND_SWORD)));
    }

    @Test void trimAcceptsOnlyTheBaseMaterialAfterMatchingAllThreeInputs() {
        var saved = new StationRecipeDefinition(
                new NamespacedKey("recipeweaver", "trim_guard_test"),
                StationKind.SMITHING_TRIM, List.of(
                        new ItemStack(Material.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE),
                        new ItemStack(Material.DIAMOND_CHESTPLATE),
                        new ItemStack(Material.IRON_INGOT)),
                null, "", 100, 0, 0, true, false);
        assertTrue(NativeSmithingGuard.allowsRecipe(saved, false,
                new ItemStack(Material.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE),
                new ItemStack(Material.DIAMOND_CHESTPLATE), new ItemStack(Material.IRON_INGOT),
                new ItemStack(Material.DIAMOND_CHESTPLATE)));
        assertFalse(NativeSmithingGuard.allowsRecipe(saved, false,
                new ItemStack(Material.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE),
                new ItemStack(Material.DIAMOND_CHESTPLATE), new ItemStack(Material.IRON_INGOT),
                new ItemStack(Material.DIAMOND_CHESTPLATE, 2)));
    }

    @Test void conditionalSmithingRequiresPermissionAtEachPreviewAndTake() {
        RecipeConditions conditions = new RecipeConditions("recipeweaver.smith", null,
                null, -1, -1, WeatherCondition.ANY, 0, null, 0, null);
        StationRecipeDefinition saved = new StationRecipeDefinition(
                new NamespacedKey("recipeweaver", "conditioned_smith"),
                StationKind.SMITHING_TRANSFORM, List.of(
                        new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                        new ItemStack(Material.DIAMOND_SWORD),
                        new ItemStack(Material.NETHERITE_INGOT)),
                new ItemStack(Material.NETHERITE_SWORD), "", 100, 0, 0, true, false,
                List.of(IngredientMatchMode.EXACT, IngredientMatchMode.EXACT,
                        IngredientMatchMode.EXACT),
                List.of(List.of(), List.of(), List.of()), Arrays.asList(null, null, null),
                CookingBookCategory.MISC, 0, 0, conditions);
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class<?>[] {World.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "world";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AtomicBoolean permitted = new AtomicBoolean();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    case "hasPermission" -> permitted.get();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ItemStack template = new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        ItemStack base = new ItemStack(Material.DIAMOND_SWORD);
        ItemStack addition = new ItemStack(Material.NETHERITE_INGOT);
        ItemStack result = new ItemStack(Material.NETHERITE_SWORD);
        assertFalse(NativeSmithingGuard.allowsRecipe(saved, false,
                template, base, addition, result, player));
        assertFalse(NativeSmithingGuard.allowsRecipe(saved, false,
                template, base, addition, result));
        permitted.set(true);
        assertTrue(NativeSmithingGuard.allowsRecipe(saved, false,
                template, base, addition, result, player));
        permitted.set(false);
        assertFalse(NativeSmithingGuard.allowsRecipe(saved, false,
                template, base, addition, result, player));
    }
}
