package dev.recipeweaver.smoke;

import dev.recipeweaver.api.CraftSoundAction;
import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.GridTier;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.WeightedResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Proxy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Test-only persistence probe for custom-grid sound actions. */
final class GridSoundSmoke {
    static final NamespacedKey KEY = new NamespacedKey("recipeweaver", "smoke_grid_sound");
    static final CraftSoundAction SAVED_SOUND = new CraftSoundAction(
            NamespacedKey.minecraft("block.note_block.bell"), 1.25F, 0.75F, true);

    private GridSoundSmoke() { }

    static void verify(RecipeWeaverApi api) {
        api.saveGridRecipe(recipe(SAVED_SOUND));
        checkRecovered(api);
        probeDispatch();
    }

    static void mutateForRestore(RecipeWeaverApi api) {
        api.saveGridRecipe(recipe(new CraftSoundAction(SAVED_SOUND.sound(), 1.0F, 1.0F, false)));
        check(!api.gridRecipe(KEY).orElseThrow().craftSound().perBatch(),
                "Grid sound backup mutation did not save");
    }

    static void checkRecovered(RecipeWeaverApi api) {
        check(SAVED_SOUND.equals(api.gridRecipe(KEY).orElseThrow().craftSound()),
                "Grid sound action was not recovered");
    }

    private static GridRecipeDefinition recipe(CraftSoundAction sound) {
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[4]));
        ingredients.set(0, new ItemStack(Material.ECHO_SHARD));
        return new GridRecipeDefinition(KEY, GridTier.ELITE, RecipeKind.SHAPED, 2,
                ingredients, List.of(new WeightedResult(new ItemStack(Material.GOLD_INGOT), 1)),
                "smoke", true, false, Collections.nCopies(4, IngredientMatchMode.EXACT),
                0, true, Collections.nCopies(4, List.of()), Collections.nCopies(4, null),
                Collections.nCopies(4, true), Collections.nCopies(4, null),
                Collections.nCopies(4, false), RecipeConditions.NONE, sound);
    }

    private static void probeDispatch() {
        AtomicInteger played = new AtomicInteger();
        Player listener = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getLocation" -> new Location(Bukkit.getWorlds().get(0), 0, 64, 0);
                    case "playSound" -> {
                        check(SAVED_SOUND.sound().toString().equals(arguments[1]),
                                "Craft sound dispatch used the wrong key");
                        played.incrementAndGet();
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        CraftSoundAction once = new CraftSoundAction(SAVED_SOUND.sound(), 1.0F, 1.0F, false);
        try {
            ClassLoader loader = Bukkit.getPluginManager().getPlugin("RecipeWeaver")
                    .getClass().getClassLoader();
            var dispatcher = Class.forName("dev.recipeweaver.core.GridCraftSounds", true, loader)
                    .getDeclaredMethod("play", Player.class, List.class);
            dispatcher.setAccessible(true);
            dispatcher.invoke(null, listener, List.of(once, once, SAVED_SOUND, SAVED_SOUND));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not run the live craft sound dispatcher", ex);
        }
        check(played.get() == 3, "Craft sound cadence did not play once and per batch");
    }

    private static void check(boolean passed, String detail) {
        if (!passed) throw new IllegalStateException(detail);
    }
}
