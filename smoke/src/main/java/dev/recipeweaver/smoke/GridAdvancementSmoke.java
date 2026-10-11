package dev.recipeweaver.smoke;

import dev.recipeweaver.api.CraftAdvancementAction;
import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.GridTier;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.WeightedResult;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Test-only persistence and live-dispatch probe for custom-grid advancement actions. */
final class GridAdvancementSmoke {
    static final NamespacedKey KEY = new NamespacedKey("recipeweaver", "smoke_grid_advancement");
    static final CraftAdvancementAction SAVED_ACTION = new CraftAdvancementAction(
            NamespacedKey.minecraft("story/root"));

    private GridAdvancementSmoke() { }

    static void verify(RecipeWeaverApi api) {
        api.saveGridRecipe(recipe(SAVED_ACTION));
        checkRecovered(api);
        probeDispatch();
    }

    static void mutateForRestore(RecipeWeaverApi api) {
        api.saveGridRecipe(recipe(null));
        check(api.gridRecipe(KEY).orElseThrow().craftAdvancement() == null,
                "Grid advancement backup mutation did not save");
    }

    static void checkRecovered(RecipeWeaverApi api) {
        check(SAVED_ACTION.equals(api.gridRecipe(KEY).orElseThrow().craftAdvancement()),
                "Grid advancement action was not recovered");
    }

    private static GridRecipeDefinition recipe(CraftAdvancementAction action) {
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[4]));
        ingredients.set(0, new ItemStack(Material.NETHERITE_SCRAP));
        return new GridRecipeDefinition(KEY, GridTier.ELITE, RecipeKind.SHAPED, 2,
                ingredients, List.of(new WeightedResult(new ItemStack(Material.GOLD_INGOT), 1)),
                "smoke", true, false, Collections.nCopies(4, IngredientMatchMode.EXACT),
                0, true, Collections.nCopies(4, List.of()), Collections.nCopies(4, null),
                Collections.nCopies(4, true), Collections.nCopies(4, null),
                Collections.nCopies(4, false), RecipeConditions.NONE, null, action);
    }

    private static void probeDispatch() {
        Advancement advancement = Bukkit.getAdvancement(SAVED_ACTION.advancement());
        check(advancement != null, "Vanilla story/root advancement is missing");
        Set<String> remaining = new HashSet<>(advancement.getCriteria());
        check(!remaining.isEmpty(), "Vanilla story/root has no criteria to award");
        int originalCriteria = remaining.size();
        AtomicInteger awards = new AtomicInteger();
        AdvancementProgress progress = (AdvancementProgress) Proxy.newProxyInstance(
                AdvancementProgress.class.getClassLoader(), new Class<?>[] {AdvancementProgress.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getRemainingCriteria" -> List.copyOf(remaining);
                    case "awardCriteria" -> {
                        boolean removed = remaining.remove(arguments[0]);
                        if (removed) awards.incrementAndGet();
                        yield removed;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getAdvancementProgress" -> {
                        check(SAVED_ACTION.advancement().equals(
                                ((Advancement) arguments[0]).getKey()),
                                "Craft action used the wrong advancement");
                        yield progress;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        try {
            ClassLoader loader = Bukkit.getPluginManager().getPlugin("RecipeWeaver")
                    .getClass().getClassLoader();
            var dispatcher = Class.forName("dev.recipeweaver.core.GridCraftAdvancements", true, loader)
                    .getDeclaredMethod("award", Player.class, List.class);
            dispatcher.setAccessible(true);
            dispatcher.invoke(null, player, List.of(SAVED_ACTION, SAVED_ACTION));
            dispatcher.invoke(null, player, List.of(SAVED_ACTION));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not run the live craft advancement dispatcher", ex);
        }
        check(awards.get() == originalCriteria && remaining.isEmpty(),
                "Craft advancement criteria were not granted exactly once");
    }

    private static void check(boolean passed, String detail) {
        if (!passed) throw new IllegalStateException(detail);
    }
}
