package dev.recipeweaver.smoke;

import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.StationRecipeDefinition;
import dev.recipeweaver.api.WeatherCondition;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareInventoryResultEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.StonecutterInventory;

/** Exercises an open stonecutter result after its input or recipe changes. */
final class NativeStonecutterGuardSmoke {
    private NativeStonecutterGuardSmoke() { }

    static void verify(RecipeWeaverApi api, NamespacedKey key) {
        StationRecipeDefinition saved = api.stationRecipe(key).orElseThrow();
        Probe probe = new Probe();
        check(probe.prepare() != null, "Stonecutter result preview was not tracked");

        probe.input(new ItemStack(Material.DIRT));
        probe.result(new ItemStack(Material.GOLD_INGOT));
        check(probe.click().isCancelled() && probe.result() == null,
                "Stonecutter result survived a changed input");

        probe.input(new ItemStack(Material.STONE));
        probe.result(new ItemStack(Material.GOLD_INGOT));
        check(probe.prepare() != null, "Stonecutter preview did not recover");
        check(api.removeStationRecipe(key), "Stonecutter smoke recipe removal failed");
        probe.result(new ItemStack(Material.GOLD_INGOT));
        check(probe.click().isCancelled() && probe.result() == null,
                "Removed stonecutter recipe allowed a stale result click");
        api.saveStationRecipe(saved);

        probe.result(new ItemStack(Material.GOLD_INGOT));
        check(probe.prepare() != null, "Restored stonecutter preview was absent");
        check(api.removeStationRecipe(key), "Second stonecutter smoke recipe removal failed");
        probe.result(new ItemStack(Material.GOLD_INGOT));
        check(probe.prepare() == null && probe.result() == null,
                "Removed stonecutter recipe retained an owned preview");

        probe.result(new ItemStack(Material.EMERALD));
        check(probe.prepare() != null && probe.result().getType() == Material.EMERALD,
                "Stonecutter guard cleared another plugin's distinct result");
        check(!probe.click().isCancelled(),
                "Stonecutter guard denied another plugin's distinct result");
        api.saveStationRecipe(saved);
        check(api.stationRecipe(key).isPresent() && Bukkit.getRecipe(key) != null,
                "Stonecutter smoke recipe was not restored");

        api.saveStationRecipe(new StationRecipeDefinition(saved.key(), saved.kind(),
                saved.ingredients(), saved.result(), saved.group(), saved.cookingTicks(),
                saved.experience(), saved.anvilLevels(), saved.enabled(), saved.hidden(),
                saved.matchModes(), saved.alternatives(), saved.itemTags(),
                saved.bookCategory(), 0, 0,
                new RecipeConditions("recipeweaver.smoke.stonecutter", null, null,
                        -1, -1, WeatherCondition.ANY, 0, null, 0, null)));
        probe.permission(false);
        probe.result(saved.result());
        check(probe.prepare() == null && probe.result() == null,
                "Stonecutter permission denial kept a result preview");
        probe.result(saved.result());
        check(probe.click().isCancelled() && probe.result() == null,
                "Stonecutter permission denial allowed a result take");
        probe.permission(true);
        probe.result(saved.result());
        check(probe.prepare() != null && !probe.click().isCancelled(),
                "Stonecutter permission approval did not allow preview and take");
        probe.permission(false);
        probe.result(saved.result());
        check(probe.click().isCancelled() && probe.result() == null,
                "Stonecutter permission loss after preview allowed a stale take");
        api.saveStationRecipe(saved);
    }

    static void verifyVanillaExclusion(RecipeWeaverApi api) {
        StonecuttingRecipe vanilla = null;
        var recipes = Bukkit.recipeIterator();
        while (recipes.hasNext()) {
            Recipe candidate = recipes.next();
            if (!(candidate instanceof StonecuttingRecipe stonecutting)
                    || !stonecutting.getKey().getNamespace().equals("minecraft")) continue;
            ItemStack input = stonecutting.getInput();
            ItemStack result = stonecutting.getResult();
            long matches = Bukkit.getRecipesFor(result).stream()
                    .filter(recipe -> recipe instanceof StonecuttingRecipe option
                            && option.getResult().getAmount() == result.getAmount()
                            && option.getResult().isSimilar(result)
                            && option.getInputChoice().test(input))
                    .count();
            if (matches == 1) {
                vanilla = stonecutting;
                break;
            }
        }
        check(vanilla != null, "No unambiguous vanilla stonecutter recipe was available");
        NamespacedKey key = vanilla.getKey();
        Probe probe = new Probe(vanilla.getInput(), vanilla.getResult());
        check(probe.prepare() != null, "Allowed vanilla stonecutter preview was denied");
        api.setVanillaRecipeDisabledAsync(key, true);
        check(api.disabledVanillaRecipes().contains(key),
                "Vanilla stonecutter exclusion did not become active");
        probe.result(vanilla.getResult());
        check(probe.click().isCancelled() && probe.result() == null,
                "An in-progress vanilla stonecutter result survived exclusion");
        api.setVanillaRecipeDisabledAsync(key, false);
        check(!api.disabledVanillaRecipes().contains(key),
                "Vanilla stonecutter exclusion was not cleared");
    }

    /** Synthetic 26.2 selected-view probe; no player client is involved. */
    static boolean verifySelectedGuardApi(RecipeWeaverApi api,
            NamespacedKey firstKey, NamespacedKey secondKey) {
        try { Class.forName("org.bukkit.inventory.view.StonecutterView"); }
        catch (ClassNotFoundException unsupported) { return false; }
        StationRecipeDefinition second = api.stationRecipe(secondKey).orElseThrow();
        try {
            api.saveStationRecipe(new StationRecipeDefinition(second.key(), second.kind(),
                    second.ingredients(), second.result(), second.group(), second.cookingTicks(),
                    second.experience(), second.anvilLevels(), second.enabled(), second.hidden(),
                    second.matchModes(), second.alternatives(), second.itemTags(),
                    second.bookCategory(), 0, 0,
                    new RecipeConditions("recipeweaver.smoke.stonecutter", null, null,
                            -1, -1, WeatherCondition.ANY, 0, null, 0, null)));
            StonecuttingRecipe firstNative = (StonecuttingRecipe) Bukkit.getRecipe(firstKey);
            StonecuttingRecipe secondNative = (StonecuttingRecipe) Bukkit.getRecipe(secondKey);
            check(firstNative != null && secondNative != null,
                    "Selected stonecutter guard recipes were not registered");
            Probe probe = new Probe(firstNative.getInput(), firstNative.getResult());
            AtomicInteger selected = new AtomicInteger(1);
            probe.selectedView(List.of(firstNative, secondNative), selected);
            check(probe.prepare() == null && probe.result() == null,
                    "Selected denied stonecutter key kept its result preview");
            probe.result(firstNative.getResult());
            check(probe.click().isCancelled() && probe.result() == null,
                    "Selected denied stonecutter key bypassed the result take guard");
            probe.result(new ItemStack(Material.DIAMOND));
            check(probe.prepare() == null && probe.result() == null,
                    "Changed selected stonecutter output kept a result preview");
            probe.result(new ItemStack(Material.DIAMOND));
            check(probe.click().isCancelled() && probe.result() == null,
                    "Changed selected stonecutter output bypassed the take guard");
            selected.set(0);
            probe.result(firstNative.getResult());
            check(probe.prepare() != null && !probe.click().isCancelled(),
                    "Allowed equal-output stonecutter key was denied");
            selected.set(2);
            probe.result(firstNative.getResult());
            check(probe.prepare() == null && probe.result() == null,
                    "Invalid selected stonecutter index kept an ambiguous owned preview");
            probe.result(firstNative.getResult());
            check(probe.click().isCancelled() && probe.result() == null,
                    "Invalid selected stonecutter index took an ambiguous owned result");
            return true;
        } finally {
            api.saveStationRecipe(second);
        }
    }

    private static final class Probe {
        private final ItemStack[] slots = {
                new ItemStack(Material.STONE), new ItemStack(Material.GOLD_INGOT)};
        private final AtomicBoolean permitted = new AtomicBoolean();
        private final Inventory top;
        private InventoryView view;

        Probe() { this(new ItemStack(Material.STONE), new ItemStack(Material.GOLD_INGOT)); }

        Probe(ItemStack input, ItemStack result) {
            slots[0] = input.clone();
            slots[1] = result.clone();
            top = (Inventory) Proxy.newProxyInstance(StonecutterInventory.class.getClassLoader(),
                    new Class<?>[] {StonecutterInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "getType" -> InventoryType.STONECUTTER;
                        case "getSize" -> 2;
                        case "getHolder" -> null;
                        case "getItem" -> slots[(int) args[0]];
                        case "setItem" -> { slots[(int) args[0]] = (ItemStack) args[1]; yield null; }
                        case "getContents" -> slots.clone();
                        default -> throw new UnsupportedOperationException("StonecutterInventory." + method.getName());
                    });
            Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                    new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> UUID.fromString("12345678-1234-1234-1234-123456789ad4");
                        case "getName" -> "NativeStonecutterGuardSmoke";
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
                @Override public InventoryType getType() { return InventoryType.STONECUTTER; }
                @Override public String getTitle() { return "Stonecutter guard smoke"; }
                @Override public String getOriginalTitle() { return getTitle(); }
                @Override public void setTitle(String title) { }
            };
        }

        void selectedView(List<StonecuttingRecipe> offered, AtomicInteger selected) {
            Class<?> type;
            try { type = Class.forName("org.bukkit.inventory.view.StonecutterView"); }
            catch (ClassNotFoundException ex) { throw new IllegalStateException(ex); }
            InventoryView base = view;
            view = (InventoryView) Proxy.newProxyInstance(type.getClassLoader(),
                    new Class<?>[] {type}, (proxy, method, args) -> switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "getSelectedRecipeIndex" -> selected.get();
                        case "getRecipes" -> offered;
                        case "getRecipeAmount" -> offered.size();
                        case "getTopInventory" -> top;
                        default -> {
                            try { yield method.invoke(base, args); }
                            catch (InvocationTargetException ex) { throw ex.getCause(); }
                        }
                    });
        }

        ItemStack prepare() {
            PrepareInventoryResultEvent event = new PrepareInventoryResultEvent(view, slots[1]);
            Bukkit.getPluginManager().callEvent(event);
            return event.getResult();
        }

        InventoryClickEvent click() {
            InventoryClickEvent event = new InventoryClickEvent(view,
                    InventoryType.SlotType.RESULT, 1, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            Bukkit.getPluginManager().callEvent(event);
            return event;
        }

        void input(ItemStack item) { slots[0] = item; }
        void result(ItemStack item) { slots[1] = item; }
        ItemStack result() { return slots[1]; }
        void permission(boolean allowed) { permitted.set(allowed); }
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
