package dev.recipeweaver.smoke;

import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.RecipeWeaverApi;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapelessRecipe;

/** Fires native preview and result-click events against the production craft guard. */
final class NativeCraftGuardSmoke {
    static final NamespacedKey KEY = new NamespacedKey("recipeweaver", "smoke_native_guard");

    private NativeCraftGuardSmoke() { }

    static void verify(RecipeWeaverApi api) {
        api.saveRecipe(recipe(true));
        checkSaved(api);
        Recipe nativeRecipe = Bukkit.getRecipe(KEY);
        check(nativeRecipe != null, "Guarded native recipe did not register");
        Probe probe = new Probe(nativeRecipe, new ItemStack(Material.BLAZE_POWDER),
                new ItemStack(Material.AMETHYST_SHARD, 2));
        probe.prepare();
        check(probe.result() != null, "Valid native craft preview was denied");
        probe.matrix(new ItemStack(Material.DIRT));
        probe.result(new ItemStack(Material.AMETHYST_SHARD, 2));
        probe.prepare();
        check(probe.result() == null, "Stale native preview survived an input change");
        probe.matrix(new ItemStack(Material.BLAZE_POWDER));
        probe.result(new ItemStack(Material.AMETHYST_SHARD, 2));
        check(!probe.click().isCancelled(), "Valid native result click was denied");

        probe.recipe(null);
        probe.result(new ItemStack(Material.AMETHYST_SHARD, 2));
        probe.prepare();
        check(probe.result() == null, "A missing native recipe retained its owned preview");
        probe.result(new ItemStack(Material.AMETHYST_SHARD, 2));
        check(probe.click().isCancelled(), "A missing native recipe allowed its stale result");
        probe.result(new ItemStack(Material.EMERALD));
        probe.prepare();
        check(probe.result().getType() == Material.EMERALD,
                "Missing recipe handling cleared another plugin's distinct result");
        check(!probe.click().isCancelled(),
                "Missing recipe handling denied another plugin's distinct result");
        probe.recipe(nativeRecipe);

        api.saveRecipe(recipe(false));
        probe.result(new ItemStack(Material.AMETHYST_SHARD, 2));
        probe.prepare();
        check(probe.result() == null, "Disabled native recipe retained a stale preview");
        probe.result(new ItemStack(Material.AMETHYST_SHARD, 2));
        check(probe.click().isCancelled(), "Disabled native recipe allowed a stale result click");
        api.saveRecipe(recipe(true));
        checkSaved(api);

        check(api.disabledVanillaRecipes().contains(VanillaRecipeSmoke.VANILLA_KEY),
                "Vanilla exclusion must be active for native guard probe");
        ShapelessRecipe staleVanilla = new ShapelessRecipe(VanillaRecipeSmoke.VANILLA_KEY,
                new ItemStack(Material.OAK_PLANKS, 4));
        staleVanilla.addIngredient(Material.OAK_LOG);
        Probe vanilla = new Probe(staleVanilla, new ItemStack(Material.OAK_LOG),
                new ItemStack(Material.OAK_PLANKS, 4));
        vanilla.prepare();
        check(vanilla.result() == null, "Disabled vanilla recipe retained a stale preview");
        vanilla.result(new ItemStack(Material.OAK_PLANKS, 4));
        check(vanilla.click().isCancelled(), "Disabled vanilla recipe allowed a stale result click");

        RecipeConditions permission = new RecipeConditions("recipeweaver.smoke.workbench",
                null, null, -1, -1, dev.recipeweaver.api.WeatherCondition.ANY,
                0, null, 0, null);
        api.saveRecipe(recipe(true, permission));
        try {
            Recipe conditionedNative = Bukkit.getRecipe(KEY);
            if (Bukkit.getBukkitVersion().startsWith("1.20.6")) {
                check(conditionedNative == null && api.recipe(KEY).isPresent(),
                        "1.20.6 registered a condition-dependent native recipe without a crafter event");
            } else {
                check(conditionedNative != null, "Conditional workbench recipe did not register");
                Probe conditioned = new Probe(conditionedNative,
                        new ItemStack(Material.BLAZE_POWDER),
                        new ItemStack(Material.AMETHYST_SHARD, 2));
                conditioned.permission(false);
                conditioned.prepare();
                check(conditioned.result() == null,
                        "Workbench condition allowed a preview without permission");
                conditioned.result(new ItemStack(Material.AMETHYST_SHARD, 2));
                check(conditioned.click().isCancelled(),
                        "Workbench condition allowed a result take without permission");
                conditioned.permission(true);
                conditioned.result(new ItemStack(Material.AMETHYST_SHARD, 2));
                conditioned.prepare();
                check(conditioned.result() != null && !conditioned.click().isCancelled(),
                        "Workbench condition denied an eligible result take");
            }
        } finally {
            api.saveRecipe(recipe(true));
        }
    }

    static boolean saved(RecipeWeaverApi api) {
        return api.recipe(KEY).map(saved -> saved.enabled()
                && saved.result().getType() == Material.AMETHYST_SHARD
                && saved.result().getAmount() == 2).orElse(false);
    }

    static void checkSaved(RecipeWeaverApi api) {
        check(saved(api) && Bukkit.getRecipe(KEY) != null,
                "Guarded native recipe did not persist or register");
    }

    static void mutateForRestore(RecipeWeaverApi api) {
        api.saveRecipe(recipe(false));
        check(!saved(api) && Bukkit.getRecipe(KEY) == null,
                "Native guard backup mutation did not disable its recipe");
    }

    private static RecipeDefinition recipe(boolean enabled) {
        return recipe(enabled, RecipeConditions.NONE);
    }

    private static RecipeDefinition recipe(boolean enabled, RecipeConditions conditions) {
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        ingredients.set(0, new ItemStack(Material.BLAZE_POWDER));
        return new RecipeDefinition(KEY, RecipeKind.SHAPELESS, ingredients,
                new ItemStack(Material.AMETHYST_SHARD, 2), "smoke", enabled, false,
                java.util.Collections.nCopies(9, dev.recipeweaver.api.IngredientMatchMode.MATERIAL),
                java.util.Collections.nCopies(9, List.of()),
                java.util.Collections.nCopies(9, null),
                org.bukkit.inventory.recipe.CraftingBookCategory.MISC, conditions);
    }

    private static final class Probe {
        private final AtomicReference<Recipe> recipe;
        private final AtomicReference<ItemStack> result;
        private final AtomicReference<ItemStack[]> matrix;
        private final AtomicBoolean permission = new AtomicBoolean(true);
        private final CraftingInventory top;
        private final InventoryView view;

        Probe(Recipe recipe, ItemStack input, ItemStack output) {
            this.recipe = new AtomicReference<>(recipe);
            result = new AtomicReference<>(output);
            matrix = new AtomicReference<>(new ItemStack[] {input, null, null, null});
            top = (CraftingInventory) Proxy.newProxyInstance(
                    CraftingInventory.class.getClassLoader(),
                    new Class<?>[] {CraftingInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "getRecipe" -> this.recipe.get();
                        case "getMatrix" -> matrix.get();
                        case "setMatrix" -> { matrix.set((ItemStack[]) args[0]); yield null; }
                        case "getResult" -> result.get();
                        case "setResult" -> { result.set((ItemStack) args[0]); yield null; }
                        case "getItem" -> (int) args[0] == 0 ? result.get()
                                : matrix.get()[(int) args[0] - 1];
                        case "setItem" -> {
                            if ((int) args[0] == 0) result.set((ItemStack) args[1]);
                            else matrix.get()[(int) args[0] - 1] = (ItemStack) args[1];
                            yield null;
                        }
                        case "getSize" -> 5;
                        case "getType" -> InventoryType.CRAFTING;
                        case "getHolder" -> null;
                        case "getContents" -> {
                            ItemStack[] contents = new ItemStack[5];
                            contents[0] = result.get();
                            System.arraycopy(matrix.get(), 0, contents, 1, 4);
                            yield contents;
                        }
                        default -> throw new UnsupportedOperationException("CraftingInventory." + method.getName());
                    });
            Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                    new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> UUID.fromString("12345678-1234-1234-1234-123456789ad2");
                        case "getName" -> "NativeCraftGuardSmoke";
                        case "getWorld" -> Bukkit.getWorlds().get(0);
                        case "hasPermission" -> permission.get();
                        case "isOnline" -> true;
                        default -> throw new UnsupportedOperationException("Player." + method.getName());
                    });
            Inventory bottom = Bukkit.createInventory(null, 9);
            view = new InventoryView() {
                @Override public Inventory getTopInventory() { return top; }
                @Override public Inventory getBottomInventory() { return bottom; }
                @Override public Player getPlayer() { return player; }
                @Override public InventoryType getType() { return InventoryType.CRAFTING; }
                @Override public String getTitle() { return "Native craft guard smoke"; }
                @Override public String getOriginalTitle() { return getTitle(); }
                @Override public void setTitle(String title) { }
            };
        }

        void prepare() {
            Bukkit.getPluginManager().callEvent(new PrepareItemCraftEvent(top, view, false));
        }

        CraftItemEvent click() {
            CraftItemEvent event = new CraftItemEvent(top.getRecipe(), view,
                    InventoryType.SlotType.RESULT, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            Bukkit.getPluginManager().callEvent(event);
            return event;
        }

        void matrix(ItemStack input) {
            matrix.set(new ItemStack[] {input, null, null, null});
        }

        void recipe(Recipe current) { recipe.set(current); }
        void permission(boolean allowed) { permission.set(allowed); }
        void result(ItemStack output) { result.set(output); }
        ItemStack result() { return result.get(); }
    }

    private static void check(boolean passed, String detail) {
        if (!passed) throw new IllegalStateException(detail);
    }
}
