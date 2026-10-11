package dev.recipeweaver.smoke;

import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import dev.recipeweaver.api.WeatherCondition;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.recipe.CookingBookCategory;

/** Fires production native smithing preparation and result-take listeners. */
final class NativeSmithingGuardSmoke {
    static final NamespacedKey KEY = new NamespacedKey("recipeweaver", "smoke_native_smith_guard");
    private static final NamespacedKey TRIM = new NamespacedKey("recipeweaver", "smoke_trim");

    private NativeSmithingGuardSmoke() { }

    static void verify(RecipeWeaverApi api) {
        api.saveStationRecipe(recipe(true));
        checkSaved(api);
        Recipe nativeRecipe = Bukkit.getRecipe(KEY);
        ItemStack decorated = new ItemStack(Material.NETHERITE_SWORD);
        Damageable meta = (Damageable) decorated.getItemMeta();
        meta.setDamage(12);
        decorated.setItemMeta(meta);
        Probe probe = new Probe(nativeRecipe, new ItemStack[] {
                new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                new ItemStack(Material.DIAMOND_SWORD),
                new ItemStack(Material.NETHERITE_INGOT), decorated});
        check(probe.prepare() != null, "Valid transform preview was denied");
        check(!probe.click().isCancelled(), "Valid transform result click was denied");
        probe.recipe(null);
        probe.result(decorated);
        check(probe.prepare() == null, "Missing transform recipe retained its owned preview");
        probe.result(decorated);
        check(probe.click().isCancelled(), "Missing transform recipe allowed its stale result");
        probe.result(new ItemStack(Material.EMERALD));
        check(probe.prepare().getType() == Material.EMERALD,
                "Missing smithing recipe cleared another plugin's distinct result");
        check(!probe.click().isCancelled(),
                "Missing smithing recipe denied another plugin's distinct result");
        probe.recipe(nativeRecipe);
        probe.input(1, new ItemStack(Material.IRON_SWORD));
        probe.result(decorated);
        check(probe.prepare() == null, "Changed smithing base retained a stale preview");
        probe.result(decorated);
        check(probe.click().isCancelled(), "Changed smithing base allowed a stale take");
        probe.input(1, new ItemStack(Material.DIAMOND_SWORD));
        api.saveStationRecipe(recipe(false));
        probe.result(decorated);
        check(probe.prepare() == null, "Disabled smithing recipe retained a stale preview");
        probe.result(decorated);
        check(probe.click().isCancelled(), "Disabled smithing recipe allowed a stale take");
        api.saveStationRecipe(recipe(true));
        checkSaved(api);

        api.saveStationRecipe(conditionedRecipe());
        probe.permission(false);
        probe.result(decorated);
        check(probe.prepare() == null, "Smithing permission denial kept a result preview");
        probe.result(decorated);
        check(probe.click().isCancelled(), "Smithing permission denial allowed a result take");
        probe.permission(true);
        probe.result(decorated);
        check(probe.prepare() != null && !probe.click().isCancelled(),
                "Smithing permission approval did not allow preview and take");
        probe.permission(false);
        probe.result(decorated);
        check(probe.click().isCancelled(),
                "Smithing permission loss after preview allowed a stale take");
        api.saveStationRecipe(recipe(true));
        checkSaved(api);

        Recipe nativeTrim = Bukkit.getRecipe(TRIM);
        check(nativeTrim != null, "Saved trim recipe is missing");
        Probe trim = new Probe(nativeTrim, new ItemStack[] {
                new ItemStack(Material.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE),
                new ItemStack(Material.DIAMOND_CHESTPLATE),
                new ItemStack(Material.REDSTONE),
                new ItemStack(Material.DIAMOND_CHESTPLATE)});
        check(trim.prepare() != null, "Valid trim preview was denied");
        check(!trim.click().isCancelled(), "Valid trim result click was denied");
        trim.input(2, new ItemStack(Material.DIRT));
        trim.result(new ItemStack(Material.DIAMOND_CHESTPLATE));
        check(trim.prepare() == null, "Changed trim addition retained a stale preview");
        trim.result(new ItemStack(Material.DIAMOND_CHESTPLATE));
        check(trim.click().isCancelled(), "Changed trim addition allowed a stale take");
    }

    static boolean saved(RecipeWeaverApi api) {
        return api.stationRecipe(KEY).map(recipe -> recipe.enabled()
                && recipe.kind() == StationKind.SMITHING_TRANSFORM
                && recipe.result().getType() == Material.NETHERITE_SWORD).orElse(false);
    }

    static void checkSaved(RecipeWeaverApi api) {
        check(saved(api) && Bukkit.getRecipe(KEY) != null,
                "Native smithing guard recipe did not persist or register");
    }

    static void mutateForRestore(RecipeWeaverApi api) {
        api.saveStationRecipe(recipe(false));
        check(!saved(api) && Bukkit.getRecipe(KEY) == null,
                "Smithing guard backup mutation did not disable its recipe");
    }

    private static StationRecipeDefinition recipe(boolean enabled) {
        return new StationRecipeDefinition(KEY, StationKind.SMITHING_TRANSFORM, List.of(
                new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                new ItemStack(Material.DIAMOND_SWORD),
                new ItemStack(Material.NETHERITE_INGOT)),
                new ItemStack(Material.NETHERITE_SWORD), "smoke", 100, 0, 0,
                enabled, false);
    }

    private static StationRecipeDefinition conditionedRecipe() {
        return new StationRecipeDefinition(KEY, StationKind.SMITHING_TRANSFORM, List.of(
                new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                new ItemStack(Material.DIAMOND_SWORD),
                new ItemStack(Material.NETHERITE_INGOT)),
                new ItemStack(Material.NETHERITE_SWORD), "smoke", 100, 0, 0,
                true, false,
                List.of(IngredientMatchMode.EXACT, IngredientMatchMode.EXACT,
                        IngredientMatchMode.EXACT),
                List.of(List.of(), List.of(), List.of()), Arrays.asList(null, null, null),
                CookingBookCategory.MISC, 0, 0,
                new RecipeConditions("recipeweaver.smoke.smithing", null, null,
                        -1, -1, WeatherCondition.ANY, 0, null, 0, null));
    }

    private static final class Probe {
        private final ItemStack[] contents;
        private final AtomicReference<Recipe> activeRecipe;
        private final AtomicBoolean permitted = new AtomicBoolean();
        private final SmithingInventory top;
        private final InventoryView view;

        Probe(Recipe recipe, ItemStack[] contents) {
            this.contents = contents;
            activeRecipe = new AtomicReference<>(recipe);
            top = (SmithingInventory) Proxy.newProxyInstance(
                    SmithingInventory.class.getClassLoader(),
                    new Class<?>[] {SmithingInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "getRecipe" -> activeRecipe.get();
                        case "getResult" -> contents[3];
                        case "setResult" -> { contents[3] = (ItemStack) args[0]; yield null; }
                        case "getItem" -> contents[(int) args[0]];
                        case "setItem" -> { contents[(int) args[0]] = (ItemStack) args[1]; yield null; }
                        case "getContents" -> contents.clone();
                        case "getSize" -> 4;
                        case "getType" -> InventoryType.SMITHING;
                        case "getHolder" -> null;
                        default -> throw new UnsupportedOperationException("SmithingInventory." + method.getName());
                    });
            Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                    new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> UUID.fromString("12345678-1234-1234-1234-123456789ad3");
                        case "getName" -> "NativeSmithingGuardSmoke";
                        case "isOnline" -> true;
                        case "getWorld" -> Bukkit.getWorlds().get(0);
                        case "hasPermission" -> permitted.get();
                        case "getLevel" -> 0;
                        default -> throw new UnsupportedOperationException("Player." + method.getName());
                    });
            Inventory bottom = Bukkit.createInventory(null, 9);
            view = new InventoryView() {
                @Override public Inventory getTopInventory() { return top; }
                @Override public Inventory getBottomInventory() { return bottom; }
                @Override public Player getPlayer() { return player; }
                @Override public InventoryType getType() { return InventoryType.SMITHING; }
                @Override public String getTitle() { return "Smithing guard smoke"; }
                @Override public String getOriginalTitle() { return getTitle(); }
                @Override public void setTitle(String title) { }
            };
        }

        ItemStack prepare() {
            PrepareSmithingEvent event = new PrepareSmithingEvent(view, contents[3]);
            Bukkit.getPluginManager().callEvent(event);
            return event.getResult();
        }

        SmithItemEvent click() {
            SmithItemEvent event = new SmithItemEvent(view,
                    InventoryType.SlotType.RESULT, 3, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            Bukkit.getPluginManager().callEvent(event);
            return event;
        }

        void input(int slot, ItemStack item) { contents[slot] = item; }
        void recipe(Recipe recipe) { activeRecipe.set(recipe); }
        void result(ItemStack item) { contents[3] = item; }
        void permission(boolean allowed) { permitted.set(allowed); }
    }

    private static void check(boolean passed, String detail) {
        if (!passed) throw new IllegalStateException(detail);
    }
}
