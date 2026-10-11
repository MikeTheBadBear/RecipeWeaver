package dev.recipeweaver.smoke;

import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import dev.recipeweaver.api.RecipeWeaverApi;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerRecipeDiscoverEvent;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.StonecuttingRecipe;

/** Synthetic player result events. The production listeners still use real Bukkit events. */
final class StationDiscoverySmoke {
    private StationDiscoverySmoke() { }

    static void fire(Player player, NamespacedKey furnaceKey, NamespacedKey cutterKey,
                     NamespacedKey smithKey) {
        PlayerRecipeDiscoverEvent unknown = unknownDiscovery(player);
        Bukkit.getPluginManager().callEvent(unknown);
        if (!unknown.isCancelled()) {
            throw new IllegalStateException("Unknown RecipeWeaver key entered the vanilla recipe book");
        }
        Recipe cooking = Bukkit.getRecipe(furnaceKey);
        if (!(cooking instanceof CookingRecipe<?> cookingRecipe)) {
            throw new IllegalStateException("Missing cooking recipe for discovery smoke");
        }
        Furnace furnace = proxy(Furnace.class, (method, args) ->
                method.equals("getRecipesUsed") ? Map.of(cookingRecipe, 1) : null);
        Block block = proxy(Block.class, (method, args) ->
                method.equals("getState") ? furnace : null);
        Bukkit.getPluginManager().callEvent(furnaceExtract(player, block, cooking.getResult()));

        Inventory cutter = inventory(Inventory.class, InventoryType.STONECUTTER,
                new ItemStack[] {new ItemStack(Material.STONE), new ItemStack(Material.GOLD_INGOT)}, null);
        InventoryView cutterView = view(player, cutter, InventoryType.STONECUTTER);
        Bukkit.getPluginManager().callEvent(new InventoryClickEvent(cutterView,
                InventoryType.SlotType.RESULT, 1,
                org.bukkit.event.inventory.ClickType.LEFT, InventoryAction.PICKUP_ALL));

        Recipe smithRecipe = Bukkit.getRecipe(smithKey);
        if (smithRecipe == null) throw new IllegalStateException("Missing smithing recipe for discovery smoke");
        SmithingInventory smith = inventory(SmithingInventory.class, InventoryType.SMITHING,
                new ItemStack[] {new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                        new ItemStack(Material.DIAMOND_SWORD), new ItemStack(Material.NETHERITE_INGOT),
                        smithRecipe.getResult()}, smithRecipe);
        InventoryView smithView = view(player, smith, InventoryType.SMITHING);
        Bukkit.getPluginManager().callEvent(new SmithItemEvent(smithView,
                InventoryType.SlotType.RESULT, 3,
                org.bukkit.event.inventory.ClickType.LEFT, InventoryAction.PICKUP_ALL));
    }

    static void fireAmbiguousFurnaceExtraction(Player player, NamespacedKey firstKey,
                                               NamespacedKey secondKey,
                                               NamespacedKey otherResultKey) {
        Recipe first = Bukkit.getRecipe(firstKey);
        Recipe second = Bukkit.getRecipe(secondKey);
        Recipe other = Bukkit.getRecipe(otherResultKey);
        if (!(first instanceof CookingRecipe<?> firstCooking)
                || !(second instanceof CookingRecipe<?> secondCooking)
                || !(other instanceof CookingRecipe<?> otherCooking)
                || first.getResult().getType() != second.getResult().getType()) {
            throw new IllegalStateException("Missing equal-output furnace discovery recipes");
        }
        Furnace furnace = proxy(Furnace.class, (method, args) ->
                method.equals("getRecipesUsed")
                        ? Map.of(firstCooking, 2, secondCooking, 1, otherCooking, 3)
                        : null);
        Block block = proxy(Block.class, (method, args) ->
                method.equals("getState") ? furnace : null);
        Bukkit.getPluginManager().callEvent(furnaceExtract(player, block, first.getResult()));
    }

    /** Tests the 26.2 public view bridge with a synthetic selected recipe. */
    static boolean verifySelectedStonecutterApi(RecipeWeaverApi api, NamespacedKey firstKey,
                                                NamespacedKey secondKey) {
        Class<?> viewType;
        try { viewType = Class.forName("org.bukkit.inventory.view.StonecutterView"); }
        catch (ClassNotFoundException unsupported) { return false; }
        Recipe first = Bukkit.getRecipe(firstKey);
        Recipe second = Bukkit.getRecipe(secondKey);
        if (!(first instanceof StonecuttingRecipe firstCut)
                || !(second instanceof StonecuttingRecipe secondCut)) {
            throw new IllegalStateException("Missing equal-output stonecutter recipes");
        }
        ItemStack input = new ItemStack(Material.STONE);
        ItemStack result = firstCut.getResult();
        if (!firstCut.getInputChoice().test(input)
                || !secondCut.getInputChoice().test(input)
                || !result.isSimilar(secondCut.getResult())) {
            throw new IllegalStateException("Stonecutter selection fixture is not ambiguous");
        }
        AtomicInteger selected = new AtomicInteger(1);
        Object view = Proxy.newProxyInstance(viewType.getClassLoader(),
                new Class<?>[] {viewType}, (proxy, method, args) -> switch (method.getName()) {
                    case "getSelectedRecipeIndex" -> selected.get();
                    case "getRecipes" -> List.of(firstCut, secondCut);
                    default -> null;
                });
        try {
            Class<?> matcher = Class.forName("dev.recipeweaver.core.DiscoveryMatches", true,
                    api.getClass().getClassLoader());
            var resolve = matcher.getDeclaredMethod("stonecutting", Object.class,
                    ItemStack.class, ItemStack.class, java.util.Collection.class);
            resolve.setAccessible(true);
            if (!secondKey.equals(resolve.invoke(null, view, input, result,
                    List.of(firstCut, secondCut)))) {
                throw new IllegalStateException("Selected second stonecutter key was lost");
            }
            selected.set(2);
            if (resolve.invoke(null, view, input, result, List.of(firstCut, secondCut)) != null) {
                throw new IllegalStateException("Out-of-range stonecutter index discovered a key");
            }
            selected.set(1);
            if (resolve.invoke(null, view, input, new ItemStack(Material.DIAMOND),
                    List.of(firstCut, secondCut)) != null) {
                throw new IllegalStateException("Changed stonecutter output discovered a key");
            }
            return true;
        } catch (InvocationTargetException ex) {
            throw new IllegalStateException("Selected stonecutter resolver failed", ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not invoke selected stonecutter resolver", ex);
        }
    }

    private static PlayerRecipeDiscoverEvent unknownDiscovery(Player player) {
        NamespacedKey key = new NamespacedKey("recipeweaver", "smoke_unknown_discovery");
        try {
            try {
                return PlayerRecipeDiscoverEvent.class.getConstructor(Player.class,
                        NamespacedKey.class, boolean.class).newInstance(player, key, false);
            } catch (NoSuchMethodException olderApi) {
                return PlayerRecipeDiscoverEvent.class.getConstructor(Player.class,
                        NamespacedKey.class).newInstance(player, key);
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not construct unknown-discovery probe", ex);
        }
    }

    static void fireBrewingTake(Player player, BrewerInventory brewer) {
        fireBrewingTake(player, brewer, 0);
    }

    static void fireBrewingTake(Player player, BrewerInventory brewer, int slot) {
        Bukkit.getPluginManager().callEvent(new InventoryClickEvent(
                view(player, brewer, InventoryType.BREWING), InventoryType.SlotType.CONTAINER,
                slot, org.bukkit.event.inventory.ClickType.LEFT, InventoryAction.PICKUP_ALL));
    }

    static void fireBrewingSlotChange(Player player, BrewerInventory brewer, int slot) {
        Bukkit.getPluginManager().callEvent(new InventoryClickEvent(
                view(player, brewer, InventoryType.BREWING), InventoryType.SlotType.CONTAINER,
                slot, org.bukkit.event.inventory.ClickType.LEFT, InventoryAction.PLACE_ALL));
    }

    static void fireFurnaceResultClick(Player player, NamespacedKey furnaceKey) {
        Recipe recipe = Bukkit.getRecipe(furnaceKey);
        if (!(recipe instanceof CookingRecipe<?> cookingRecipe)) {
            throw new IllegalStateException("Missing cooking recipe for result-click discovery smoke");
        }
        Furnace furnace = proxy(Furnace.class, (method, args) ->
                method.equals("getRecipesUsed") ? Map.of(cookingRecipe, 1) : null);
        ItemStack[] contents = {null, null, recipe.getResult()};
        Inventory inventory = (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                new Class<?>[]{Inventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "getType" -> InventoryType.FURNACE;
                    case "getSize" -> 3;
                    case "getItem" -> contents[(int) args[0]];
                    case "getHolder" -> furnace;
                    default -> null;
                });
        Bukkit.getPluginManager().callEvent(new InventoryClickEvent(
                view(player, inventory, InventoryType.FURNACE), InventoryType.SlotType.RESULT,
                2, org.bukkit.event.inventory.ClickType.LEFT, InventoryAction.PICKUP_ALL));
    }

    private static FurnaceExtractEvent furnaceExtract(Player player, Block block, ItemStack result) {
        try {
            try {
                return FurnaceExtractEvent.class.getConstructor(Player.class, Block.class,
                                Material.class, int.class, int.class)
                        .newInstance(player, block, result.getType(), 1, 0);
            } catch (NoSuchMethodException modernApi) {
                return FurnaceExtractEvent.class.getConstructor(Player.class, Block.class,
                                ItemStack.class, int.class, int.class)
                        .newInstance(player, block, result, 1, 0);
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not construct furnace extraction probe", ex);
        }
    }

    private static InventoryView view(Player player, Inventory top, InventoryType type) {
        Inventory bottom = Bukkit.createInventory(null, 9);
        return new InventoryView() {
            @Override public Inventory getTopInventory() { return top; }
            @Override public Inventory getBottomInventory() { return bottom; }
            @Override public Player getPlayer() { return player; }
            @Override public InventoryType getType() { return type; }
            @Override public String getTitle() { return "RecipeWeaver smoke"; }
            @Override public String getOriginalTitle() { return "RecipeWeaver smoke"; }
            @Override public void setTitle(String title) { }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T extends Inventory> T inventory(Class<T> type, InventoryType inventoryType,
                                                     ItemStack[] contents, Recipe recipe) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "getType" -> inventoryType;
                    case "getSize" -> contents.length;
                    case "getItem" -> contents[(int) args[0]];
                    case "getResult" -> contents[contents.length - 1];
                    case "getRecipe" -> recipe;
                    case "getHolder" -> null;
                    default -> null;
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, MethodReturn implementation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> implementation.value(method.getName(), args));
    }

    private interface MethodReturn { Object value(String method, Object[] args); }
}
