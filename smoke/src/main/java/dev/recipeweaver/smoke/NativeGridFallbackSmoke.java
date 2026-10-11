package dev.recipeweaver.smoke;

import dev.recipeweaver.api.GridTier;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.WeatherCondition;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.recipe.CraftingBookCategory;

/** Exercises the production advanced-table fallback against each real server's recipe engine. */
final class NativeGridFallbackSmoke {
    private NativeGridFallbackSmoke() { }

    static void verify(RecipeWeaverApi api) throws Exception {
        var field = api.getClass().getDeclaredField("gridMenus");
        field.setAccessible(true);
        Object menus = field.get(api);
        Method find = menus.getClass().getDeclaredMethod("find", Player.class, World.class,
                GridTier.class, int.class, List.class);
        find.setAccessible(true);
        World world = Bukkit.getWorlds().getFirst();
        Player eligible = player(world, true);
        List<ItemStack> honey = empty();
        honey.set(0, new ItemStack(Material.HONEY_BOTTLE, 2));
        Object match = find.invoke(menus, eligible, world, GridTier.ADVANCED, 3, honey);
        check(match != null, "Vanilla honey recipe did not match in advanced table");
        Method after = match.getClass().getDeclaredMethod("after", List.class);
        after.setAccessible(true);
        Object effect = after.invoke(match, honey);
        Method returns = effect.getClass().getDeclaredMethod("returned");
        returns.setAccessible(true);
        List<?> overflow = (List<?>) returns.invoke(effect);
        check(overflow.size() == 1 && overflow.getFirst() instanceof ItemStack bottle
                && bottle.getType() == Material.GLASS_BOTTLE && bottle.getAmount() == 1,
                "Advanced-table fallback lost the overflow glass bottle");

        NamespacedKey key = new NamespacedKey("recipeweaver", "smoke_native_grid_fallback");
        List<ItemStack> input = empty();
        ItemStack ingredient = new ItemStack(Material.NETHERITE_SCRAP);
        var meta = ingredient.getItemMeta();
        meta.setDisplayName("RecipeWeaver guarded fallback probe");
        ingredient.setItemMeta(meta);
        input.set(0, ingredient);
        var conditions = new RecipeConditions("recipeweaver.smoke.native_fallback", null,
                null, -1, -1, WeatherCondition.ANY, 0, null, 0, null);
        var definition = new RecipeDefinition(key, RecipeKind.SHAPELESS, input,
                new ItemStack(Material.ECHO_SHARD), "smoke", true, false,
                Collections.nCopies(9, IngredientMatchMode.EXACT), Collections.nCopies(9, List.of()),
                Collections.nCopies(9, null), CraftingBookCategory.MISC, conditions);
        try {
            api.saveRecipe(definition);
            // Old servers withhold guarded recipes from automatic crafters. Inject this
            // test-only native registration to verify the fallback still fails closed.
            if (Bukkit.getRecipe(key) == null) {
                Bukkit.addRecipe(new ShapelessRecipe(key, definition.result())
                        .addIngredient(new org.bukkit.inventory.RecipeChoice.ExactChoice(ingredient)));
            }
            check(find.invoke(menus, player(world, false), world, GridTier.ADVANCED, 3, input) == null,
                    "Advanced-table fallback bypassed the native recipe permission");
            check(find.invoke(menus, eligible, world, GridTier.ADVANCED, 3, input) != null,
                    "Eligible player could not use the guarded native fallback");
        } finally {
            api.removeRecipe(key);
            Bukkit.removeRecipe(key);
        }
    }

    private static List<ItemStack> empty() {
        return new ArrayList<>(Arrays.asList(new ItemStack[9]));
    }

    private static Player player(World world, boolean permission) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    case "hasPermission" -> permission;
                    default -> null;
                });
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new IllegalStateException(message);
    }
}
